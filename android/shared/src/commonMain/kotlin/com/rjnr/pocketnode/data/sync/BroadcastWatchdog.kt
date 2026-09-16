package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.TipSource
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import com.rjnr.pocketnode.data.storage.PendingBroadcastStore
import com.rjnr.pocketnode.data.storage.TransactionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * Moved to `commonMain` in M3 #5. The only Android piece left behind is
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
                runCatching {
                    val tip = tipSource.fetchAndPublishTip()
                    val (walletId, network) = tipSource.activeWalletAndNetworkOrNull()
                        ?: return@runCatching
                    checkAll(currentTip = tip, walletId = walletId, network = network)
                }.onFailure { logger.w(TAG, "fallback checkAll: ${it.message}") }
            }
        }
    }

    fun stop() {
        tipJob?.cancel(); tipJob = null
        fallbackJob?.cancel(); fallbackJob = null
    }

    /** Public for testability; not called directly by app code. */
    suspend fun checkAll(currentTip: Long, walletId: String, network: String) {
        val rows = pendingBroadcasts.getActive(walletId, network)
        val now = clock.nowMs()
        for (row in rows) {
            // Per-row runCatching: cache-update-before-CAS can throw on a DB
            // hiccup; we want the row to stay non-terminal AND we want sibling
            // rows in the same pass to still be processed.
            runCatching { processRow(row, currentTip, now) }
                .onFailure { logger.w(TAG, "checkAll: row ${row.txHash} failed: ${it.message}") }
        }
    }

    private suspend fun processRow(
        row: PendingBroadcastRecord,
        currentTip: Long,
        now: Long
    ) {
        when (statusGateway.fetch(row.txHash)) {
            is TxFetchResult.OnChain -> {
                // Update cache BEFORE the terminal CAS: if the cache write throws,
                // the pending row stays in non-terminal state and the watchdog
                // retries on the next tick. Reverse order would drop the row from
                // getActive() with a stale PENDING transactions row.
                transactions.updateTransactionStatus(row.txHash, "CONFIRMED")
                val ok = pendingBroadcasts.compareAndUpdateState(
                    hash = row.txHash, expected = row.state,
                    next = "CONFIRMED", now = now
                )
                if (ok == 1) {
                    pendingBroadcasts.delete(row.txHash)
                }
            }
            TxFetchResult.InPool -> {
                // In-pool past the commit window = network rejection masked by
                // a stale local-mempool entry. CKB proposal+commit completes in
                // ~12 blocks; if we're still in-pool at submitted+25 (~6.5 min)
                // the chain has rejected the tx (e.g. double-spend, dependency
                // on a tx that itself never landed). Mark FAILED so the user
                // sees a terminal state and the retry CTA, instead of stuck-pending.
                if (currentTip >= row.submittedAtTipBlock + BLOCK_TIMEOUT) {
                    logger.w(TAG, "in-pool past +$BLOCK_TIMEOUT blocks for ${row.txHash} (submitted at ${row.submittedAtTipBlock}, tip $currentTip) — network rejected; marking FAILED")
                    transactions.updateTransactionStatus(row.txHash, "FAILED")
                    pendingBroadcasts.compareAndUpdateState(
                        hash = row.txHash, expected = row.state,
                        next = "FAILED", now = now
                    )
                } else {
                    // Healthy in-pool — waiting for commit.
                    if (row.state == "BROADCASTING") {
                        pendingBroadcasts.compareAndUpdateState(row.txHash, "BROADCASTING", "BROADCAST", now)
                    }
                    if (row.nullCount != 0) {
                        pendingBroadcasts.updateNullCount(row.txHash, 0, now)
                    }
                }
            }
            TxFetchResult.NotFound -> {
                val newCount = row.nullCount + 1
                pendingBroadcasts.updateNullCount(row.txHash, newCount, now)
                if (newCount >= NULL_THRESHOLD &&
                    currentTip >= row.submittedAtTipBlock + BLOCK_TIMEOUT
                ) {
                    transactions.updateTransactionStatus(row.txHash, "FAILED")
                    pendingBroadcasts.compareAndUpdateState(
                        hash = row.txHash, expected = row.state,
                        next = "FAILED", now = now
                    )
                }
            }
            TxFetchResult.Exception -> {
                logger.w(TAG, "fetch exception for ${row.txHash}; no state change")
            }
        }
    }

    companion object {
        private const val TAG = "BroadcastWatchdog"
        const val NULL_THRESHOLD = 3
        const val BLOCK_TIMEOUT = 25L
        const val FALLBACK_INTERVAL_MS = 15_000L
    }
}

sealed class TxFetchResult {
    data class OnChain(val blockHash: String) : TxFetchResult()
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
    override suspend fun fetch(hash: String): TxFetchResult = runCatching {
        val resp = ledger.getTransactionStatus(hash).getOrNull()
            ?: return TxFetchResult.NotFound
        when {
            resp.status == "unknown" -> TxFetchResult.NotFound
            resp.blockHash != null -> TxFetchResult.OnChain(resp.blockHash!!)
            else -> TxFetchResult.InPool
        }
    }.getOrElse { TxFetchResult.Exception }
}
