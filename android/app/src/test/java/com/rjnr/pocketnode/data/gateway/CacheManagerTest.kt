package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CacheManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var cacheManager: CacheManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheManager = CacheManager(db.transactionDao(), db.balanceCacheDao(), NoopLogger)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `cacheBalance and getCachedBalance round-trip`() = runTest {
        val response = BalanceResponse(
            address = "ckt1qz...",
            capacity = "0x174876e800",
            capacityCkb = "1000.0",
            asOfBlock = "0x100"
        )

        cacheManager.cacheBalance(response, "MAINNET")
        val cached = cacheManager.getCachedBalance("MAINNET")

        assertNotNull(cached)
        assertEquals("ckt1qz...", cached!!.address)
        assertEquals("0x174876e800", cached.capacity)
    }

    @Test
    fun `getCachedBalance returns null for missing network`() = runTest {
        assertNull(cacheManager.getCachedBalance("MAINNET"))
    }

    @Test
    fun `insertPendingTransaction and getPendingNotIn`() = runTest {
        cacheManager.insertPendingTransaction("0xtx1", "MAINNET")
        cacheManager.insertPendingTransaction("0xtx2", "MAINNET")

        val pending = cacheManager.getPendingNotIn("MAINNET", setOf("0xtx1"))
        assertEquals(1, pending.size)
        assertEquals("0xtx2", pending[0].txHash)
    }

    @Test
    fun `cacheTransactions stores records`() = runTest {
        val records = listOf(
            TransactionRecord(
                txHash = "0xabc",
                blockNumber = "0x100",
                blockHash = "0xhash",
                timestamp = 1700000000000L,
                balanceChange = "0x174876e800",
                direction = "in",
                fee = "0x0",
                confirmations = 5
            )
        )

        cacheManager.cacheTransactions(records, "MAINNET")

        val stored = db.transactionDao().getByTxHash("0xabc")
        assertNotNull(stored)
        assertEquals("0x174876e800", stored!!.balanceChange)
        assertEquals("CONFIRMED", stored.status)
    }

    @Test
    fun `clearAll removes all data`() = runTest {
        val response = BalanceResponse("addr", "0x100", "1.0", "0x1")
        cacheManager.cacheBalance(response, "MAINNET")
        cacheManager.insertPendingTransaction("0xtx1", "MAINNET")

        cacheManager.clearAll()

        assertNull(cacheManager.getCachedBalance("MAINNET"))
        assertEquals(0, cacheManager.getPendingNotIn("MAINNET", emptySet()).size)
    }
}
