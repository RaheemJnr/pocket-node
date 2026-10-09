package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.gateway.models.BalanceResponse

/**
 * The cached-balance read and write the shared read path performs.
 *
 * One row per (walletId, network): the last spendable balance computed for
 * that wallet, so the Home screen and the account switcher can paint a number
 * before the light client answers.
 *
 * Android binds it to `CacheManager` through `RoomBalanceCache`, which keeps
 * that class's swallow-and-log behaviour: a cache read that fails answers
 * null, a cache write that fails is dropped. Shared callers are written
 * against that contract and never try to recover from a cache failure.
 */
interface BalanceCache {

    /** Last cached balance for the wallet, or null when nothing is cached yet. */
    suspend fun getCachedBalance(network: String, walletId: String): BalanceResponse?

    /** Insert or replace the cached balance for the wallet. */
    suspend fun cacheBalance(response: BalanceResponse, network: String, walletId: String)
}
