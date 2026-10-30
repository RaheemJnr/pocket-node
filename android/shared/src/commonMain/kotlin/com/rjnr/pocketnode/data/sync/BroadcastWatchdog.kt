package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.TipSource
import com.rjnr.pocketnode.data.send.hasSendInclusionFor
import com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse
import com.rjnr.pocketnode.data.storage.PendingBroadcastStore
import com.rjnr.pocketnode.data.storage.TransactionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives `pending_broadcasts` rows to terminal states via the
 * Neuron-style Monitor pattern (#94) adapted to CKB.
 *
 *   - Primary trigger: light-client tip events ([TipSource.tipFlow]).
 *   - Fallback timer: 15s loop. Defensive — tip events stalling would
 *     otherwise leave rows stuck.
 *   - Both routes call [checkAll], internally idempotent (CAS UPDATEs
 *     guard against tip-event vs fallback-timer races).
 *
 * Foreground-gated: skips checkAll when not at least STARTED.
 * Phase A is foreground-only by design; cold-start recovery
 * (`StartupReconciler`) handles process death.
 *
 * Moved to `commonMain` with the send pipeline. The only Android piece left behind is
 * [LifecycleProvider]'s real implementation, which reads
 * `ProcessLifecycleOwner`; iOS supplies its own from the scene phase.
 */
class BroadcastWatchdog(
    private val pendingBroadcasts: PendingBroadcastStore,
    private val statusGateway: TransactionStatusGateway,
    private val transactions: TransactionStore,
    private val tipSource: TipSource,
    private val lifecycleProvider: LifecycleProvider,
    dispatcher: CoroutineDispatcher,
    private val logger: Logger,
    private val clock: Clock = SystemClock,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val checkMutex = Mutex()
    private val lastPoll = mutableMapOf<String, Long>()
    private var tipJob: Job? = null
    private var fallbackJob: Job? = null

    fun start() {
        if (tipJob != null) return  // idempotent
        tipJob = scope.launch {
            tipSource.tipFlow.collect { tip ->
                if (!lifecycleProvider.isAtLeastStarted()) return@collect
                val (walletId, network) = tipSource.activeWalletAndNetworkOrNull() ?: return@collect
                checkAll(currentTip = tip, walletId = walletId, network = network)
            }
        }
        fallbackJob = scope.launch {
            while (true) {
                delay(FALLBACK_INTERVAL_MS)
                if (!lifecycleProvider.isAtLeastStarted()) continue
                try {
                    val tip = tipSource.fetchAndPublishTip()
                    val (walletId, network) = tipSource.activeWalletAndNetworkOrNull()
                        ?: continue
                    checkAll(currentTip = tip, walletId = walletId, network = network)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { logger.w(TAG, "fallback checkAll: ${e.message}") }
            }
        }
    }

    fun stop() {
        tipJob?.cancel(); tipJob = null
        fallbackJob?.cancel(); fallbackJob = null
    }

    /** One serialized foreground pass. Height never establishes rejection. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun checkAll(currentTip: Long, walletId: String, network: String) = checkMutex.withLock {
        val context = walletId to network
        fun isCurrent() = lifecycleProvider.isAtLeastStarted() && tipSource.activeWalletAndNetworkOrNull() == context
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return@withLock
        val rows = try { pendingBroadcasts.getActive(walletId, network)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            logger.w(TAG, "Active broadcasts unavailable: ${e.message}")
            return@withLock
        }
        // Bound bookkeeping to currently active rows, not the lifetime history.
        lastPoll.keys.retainAll(rows.map { it.txHash }.toSet())
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return@withLock
            val now = clock.nowMs()
            val interval = if (now - row.createdAt >= SLOW_AFTER_MS) SLOW_INTERVAL_MS else FALLBACK_INTERVAL_MS
            val last = lastPoll[row.txHash]
            if (last != null && now >= last && now - last < interval) continue
            lastPoll[row.txHash] = now
            try {
                val result = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { statusGateway.fetch(row.txHash) } ?: TxFetchResult.Exception
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@withLock
                val next = when (result) {
                    is TxFetchResult.OnChain -> "CONFIRMED"
                    TxFetchResult.Rejected -> "FAILED"
                    TxFetchResult.InPool -> "BROADCAST"
                    else -> row.state
                }
                val count = when (result) {
                    TxFetchResult.NotFound -> if (row.nullCount == Int.MAX_VALUE) Int.MAX_VALUE else row.nullCount + 1
                    TxFetchResult.InPool, TxFetchResult.Rejected -> 0
                    else -> row.nullCount
                }
                pendingBroadcasts.reconcileObservation(row, next, count, clock.nowMs())
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                logger.w(TAG, "checkAll: row ${row.txHash} failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "BroadcastWatchdog"
        // Kept for legacy failure-reason compatibility; no longer a failure threshold.
        const val NULL_THRESHOLD = 3
        const val FALLBACK_INTERVAL_MS = 15_000L
        const val SLOW_AFTER_MS = 360_000L
        const val SLOW_INTERVAL_MS = 60_000L
        const val LOOKUP_TIMEOUT_MS = 10_000L
    }
}

sealed class TxFetchResult {
    data class OnChain(val blockHash: String) : TxFetchResult()
    data object Rejected : TxFetchResult()
    data object InPool : TxFetchResult()
    data object NotFound : TxFetchResult()
    data object Exception : TxFetchResult()
}

/** Indirection over the transaction-status read for testability. */
fun interface TransactionStatusGateway {
    suspend fun fetch(hash: String): TxFetchResult
}

/** Lifecycle-state indirection. The Android impl reads ProcessLifecycleOwner. */
fun interface LifecycleProvider {
    fun isAtLeastStarted(): Boolean
}

/**
 * Real adapter wrapping [LedgerReader.getTransactionStatus]. Maps
 * `TransactionStatusResponse` to the watchdog's narrower [TxFetchResult]
 * vocabulary so the watchdog never has to re-parse bridge JSON.
 */
class LedgerTransactionStatusGateway(
    private val ledger: LedgerReader,
) : TransactionStatusGateway {
    override suspend fun fetch(hash: String): TxFetchResult = try {
        ledger.getTransactionStatus(hash).getOrThrow().toBroadcastObservation(hash)
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) { TxFetchResult.Exception }
}

/** Same inclusion boundary as foreground sends; unknown is not a rejection. */
internal fun TransactionStatusResponse.toBroadcastObservation(hash: String): TxFetchResult = when {
    txHash != hash -> TxFetchResult.Exception
    hasSendInclusionFor(hash) -> TxFetchResult.OnChain(blockHash!!)
    status == "rejected" -> TxFetchResult.Rejected
    status == "unknown" -> TxFetchResult.NotFound
    status == "pending" || status == "proposed" -> TxFetchResult.InPool
    else -> TxFetchResult.Exception
}
