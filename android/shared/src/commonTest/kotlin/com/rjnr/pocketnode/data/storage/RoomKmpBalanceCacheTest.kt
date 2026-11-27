package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomKmpBalanceCacheTest {
    @Test
    fun legacyGenesisCacheIsUnavailableButNonzeroEmptyBalanceSurvives() = runTest {
        var stored: BalanceCacheRow? = null
        val dao = object : BalanceCacheRoomDao {
            override suspend fun upsert(row: BalanceCacheRow) { stored = row }
            override suspend fun getByWalletAndNetwork(walletId: String, network: String) = stored
            override suspend fun deleteByWalletAndNetwork(walletId: String, network: String) { stored = null }
        }
        val cache = RoomKmpBalanceCache(dao)
        for (height in listOf("0x0", "0", "0x00", "-1")) {
            val response = BalanceResponse("addr", "0x0", "0.0", height)
            dao.upsert(BalanceCacheRow.from(response, "TESTNET", "wallet", 0L))
            assertNull(cache.getCachedBalance("TESTNET", "wallet"), "Invalid cached height $height")
        }
        val valid = BalanceResponse("addr", "0x0", "0.0", "0xa")
        cache.cacheBalance(valid, "TESTNET", "wallet")
        assertEquals(valid, cache.getCachedBalance("TESTNET", "wallet"))
    }
}
