package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Light-client transaction lookup, narrowed to the one call the legacy
 * reconcile needs. Deliberately keeps the `Result` wrapper: the reconcile
 * must tell a transient JNI/RPC failure apart from a successful "unknown"
 * response, which the coarser [com.rjnr.pocketnode.data.sync.TransactionStatusGateway]
 * collapses into a single NotFound.
 */
fun interface TransactionStatusSource {
    suspend fun fetch(hash: String): Result<TransactionStatusResponse>
}

/**
 * Cold-start pending-transaction reconciliation extracted from
 * [GatewayRepository] (#460 part 2).
 *
 * Runs once per process, immediately after the embedded node starts, and
 * does two things:
 *
 *  1. surfaces orphaned BROADCASTING rows for the active network so the
 *     watchdog picks them up on the next tip (#115 §5);
 *  2. reconciles legacy PENDING `transactions` rows that predate
 *     `pending_broadcasts` and therefore have no broadcast row at all,
 *     by asking the light client directly (#115).
 *
 * Both steps are `runCatching`-wrapped: a failure here must never stop the
 * sync poll or the background sync service from starting.
 *
 * Plain `@Singleton` with no dependency back on [GatewayRepository]; the
 * light-client lookup arrives through the [TransactionStatusSource] seam and
 * the deferred pass is launched in the caller's scope (the repository's), so
 * coroutine semantics are unchanged.
 */
@Singleton
class StartupReconciler @Inject constructor(
    private val pendingBroadcastDao: PendingBroadcastDao,
    private val cacheManager: CacheManager,
    private val logger: Logger,
) {

    /**
     * @param scope the caller's scope; the 15s-deferred legacy pass is
     *   launched there and is not awaited, exactly as before.
     */
    suspend fun reconcile(
        walletId: String,
        networkName: String,
        scope: CoroutineScope,
        statusSource: TransactionStatusSource,
    ) {
        // Cold-start recovery: surface any BROADCASTING orphan rows for the
        // active network so the watchdog can resolve them on the next tip.
        // Network-scoped — LightClientNative is per-network; querying for a
        // hash on a network whose light client isn't running would return null
        // spuriously and drive valid orphans to a false FAILED. (#115 §5)
        runCatching {
            val orphans = pendingBroadcastDao.getActive(walletId, networkName)
            val broadcasting = orphans.count { it.state == "BROADCASTING" }
            if (broadcasting > 0) {
                logger.w(
                    TAG,
                    "Cold-start: $broadcasting BROADCASTING orphan(s) on $networkName; watchdog will resolve"
                )
            }
        }

        // Legacy reconciliation: PENDING `transactions` rows that predate
        // pending_broadcasts have no broadcast row, so the watchdog can't
        // see them. Query the light client directly: on chain → CONFIRMED,
        // not found → FAILED, in pool → leave alone (the natural pending state).
        // (#115 — addresses the user's "old ghosts still showing pending" case.)
        runCatching {
            val orphanHashes = cacheManager.getOrphanPendingHashes(walletId, networkName)
            if (orphanHashes.isNotEmpty()) {
                logger.w(TAG, "Legacy reconcile: ${orphanHashes.size} orphan PENDING tx(s) on $networkName")
                scope.launch {
                    delay(15_000) // give light client time to be ready
                    for (hash in orphanHashes) {
                        val result = statusSource.fetch(hash)
                        // Distinguish transient lookup failure (Result.failure) from
                        // a successful "unknown" response. Only the latter means the
                        // light client knows it doesn't have the tx; the former is a
                        // JNI/RPC hiccup and must NOT permanently mark the row FAILED.
                        val resp = result.getOrNull()
                        val newStatus = when {
                            result.isFailure -> null      // transient — retry next init
                            resp == null -> null           // defensive
                            resp.status == "unknown" -> "FAILED"
                            resp.blockHash != null -> "CONFIRMED"
                            else -> null  // still in pool — leave PENDING
                        }
                        if (newStatus != null) {
                            cacheManager.updateTransactionStatus(hash, newStatus)
                            logger.d(TAG, "Legacy reconcile: $hash → $newStatus")
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "StartupReconciler"
    }
}
