package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse
import com.rjnr.pocketnode.data.storage.PendingBroadcastStore
import com.rjnr.pocketnode.data.storage.TransactionStore
import com.rjnr.pocketnode.data.sync.TxFetchResult
import com.rjnr.pocketnode.data.sync.toBroadcastObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

fun interface TransactionStatusSource {
    suspend fun fetch(hash: String): Result<TransactionStatusResponse>
}

/** Bounded, read-only cold-start recovery. Unavailable/absent transactions remain pending. */
class StartupReconciler(
    private val pendingBroadcasts: PendingBroadcastStore,
    private val transactions: TransactionStore,
    private val logger: Logger,
) {
    suspend fun reconcile(
        walletId: String,
        networkName: String,
        scope: CoroutineScope,
        currentContext: () -> Pair<String, String>?,
        statusSource: TransactionStatusSource,
    ) {
        val context = walletId to networkName
        scope.launch {
            // Four finite passes allow a lagging client to catch up within this process.
            // The watchdog owns rows with a pending_broadcasts record; neither path resends.
            for (wait in listOf(15_000L, 30_000L, 60_000L, 120_000L)) {
                delay(wait)
                currentCoroutineContext().ensureActive()
                if (currentContext() != context) return@launch
                val hashes = try {
                    transactions.getOrphanPendingHashes(walletId, networkName)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    logger.w(TAG, "Orphan lookup unavailable: ${e.message}")
                    continue
                }
                if (hashes.isEmpty()) return@launch
                for (hash in hashes) {
                    currentCoroutineContext().ensureActive()
                    if (currentContext() != context) return@launch
                    try {
                        val response = withTimeoutOrNull(10_000L) { statusSource.fetch(hash).getOrThrow() }
                        currentCoroutineContext().ensureActive()
                        if (currentContext() != context) return@launch
                        val status = when (response?.toBroadcastObservation(hash)) {
                            is TxFetchResult.OnChain -> "CONFIRMED"
                            TxFetchResult.Rejected -> "FAILED"
                            else -> null
                        }
                        if (status != null) transactions.reconcileOrphan(hash, walletId, networkName, status)
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { logger.w(TAG, "Orphan observation unavailable: ${e.message}") }
                }
            }
        }
    }

    companion object {
        private const val TAG = "StartupReconciler"
    }
}
