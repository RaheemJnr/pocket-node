package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.gateway.CacheManager
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room binding for the shared [BalanceCache] seam (M3 #4).
 *
 * Delegates to [CacheManager] rather than to `BalanceCacheDao` directly, so
 * the swallow-and-log behaviour the seam documents lives in exactly one place
 * and the send path (which still injects `CacheManager`) and the shared read
 * path cannot drift apart.
 */
@Singleton
class RoomBalanceCache @Inject constructor(
    private val cacheManager: CacheManager,
) : BalanceCache {

    override suspend fun getCachedBalance(network: String, walletId: String): BalanceResponse? =
        cacheManager.getCachedBalance(network, walletId = walletId)

    override suspend fun cacheBalance(
        response: BalanceResponse,
        network: String,
        walletId: String,
    ) = cacheManager.cacheBalance(response, network, walletId = walletId)
}
