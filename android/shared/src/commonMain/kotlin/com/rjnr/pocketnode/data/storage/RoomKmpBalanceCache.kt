package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import kotlinx.coroutines.CancellationException

/**
 * [BalanceCache] over the shared-core Room database.
 *
 * Keeps the swallow-and-log behaviour the seam documents and that
 * `CacheManager` implements on Android: a read that fails answers null, a
 * write that fails is dropped. Shared callers are written against that
 * contract and never try to recover from a cache failure.
 */
class RoomKmpBalanceCache(
    private val dao: BalanceCacheRoomDao,
    private val logger: Logger = NoopLogger,
    private val clock: Clock = SystemClock,
) : BalanceCache {

    override suspend fun getCachedBalance(network: String, walletId: String): BalanceResponse? = try {
        dao.getByWalletAndNetwork(walletId, network)?.toResponse()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(TAG, "Failed to read balance cache", e)
        null
    }

    override suspend fun cacheBalance(
        response: BalanceResponse,
        network: String,
        walletId: String,
    ) {
        try {
            dao.upsert(BalanceCacheRow.from(response, network, walletId, clock.nowMs()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to write balance cache", e)
        }
    }

    private companion object {
        const val TAG = "RoomKmpBalanceCache"
    }
}
