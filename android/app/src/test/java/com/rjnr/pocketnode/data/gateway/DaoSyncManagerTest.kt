package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoSyncManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var manager: DaoSyncManager

    private val testWalletId = "test-wallet"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = DaoSyncManager(db.headerCacheDao(), db.daoCellDao(), db.pendingDaoWithdrawDao())
    }

    @After
    fun teardown() { db.close() }

    private fun makeHeaderView() = JniHeaderView(
        hash = "0xabc123",
        number = "0x100",
        epoch = "0x7080291000032",
        timestamp = "0x18c8d0a7a00",
        parentHash = "0xp",
        transactionsRoot = "0xt",
        proposalsHash = "0xpr",
        extraHash = "0xe",
        dao = "0x40b4d9a3ddc9e730",
        nonce = "0x0"
    )

    @Test
    fun `cacheHeader and getCachedHeader round-trip`() = runTest {
        manager.cacheHeader(makeHeaderView(), "MAINNET")
        val cached = manager.getCachedHeader("0xabc123")

        assertNotNull(cached)
        assertEquals("0x100", cached!!.number)
        assertEquals("0x40b4d9a3ddc9e730", cached.dao)
    }

    @Test
    fun `getCachedHeader returns null for missing hash`() = runTest {
        assertNull(manager.getCachedHeader("0xmissing"))
    }

    @Test
    fun `insertPendingDeposit creates DEPOSITING entry`() = runTest {
        manager.insertPendingDeposit("0xtx1", 10_200_000_000L, "MAINNET", walletId = testWalletId)

        val cell = manager.getByOutPoint("0xtx1", "0x0")
        assertNotNull(cell)
        assertEquals("DEPOSITING", cell!!.status)
        assertEquals(10_200_000_000L, cell.capacity)
    }

    @Test
    fun `getActiveDeposits excludes COMPLETED`() = runTest {
        manager.insertPendingDeposit("0x1", 100L, "MAINNET", walletId = testWalletId)
        manager.updateStatus("0x1", "0x0", "COMPLETED")
        manager.insertPendingDeposit("0x2", 200L, "MAINNET", walletId = testWalletId)

        val active = manager.getActiveDeposits("MAINNET", walletId = testWalletId)
        assertEquals(1, active.size)
        assertEquals("0x2", active[0].txHash)
    }

    @Test
    fun `clearAll removes everything`() = runTest {
        manager.cacheHeader(makeHeaderView(), "MAINNET")
        manager.insertPendingDeposit("0x1", 100L, "MAINNET", walletId = testWalletId)

        manager.clearAll()

        assertNull(manager.getCachedHeader("0xabc123"))
        assertTrue(manager.getActiveDeposits("MAINNET", walletId = testWalletId).isEmpty())
    }

    @Test
    fun `getActiveDeposits rejects blank walletId`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { manager.getActiveDeposits("MAINNET", walletId = "") }
        }
    }
}
