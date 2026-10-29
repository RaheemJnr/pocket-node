package com.rjnr.pocketnode.data.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.*

/** Exercise the iOS shared DAO SQL against SQLite without a live node or simulator. */
@RunWith(RobolectricTestRunner::class)
class KmpReconciliationSqlTest {
    private lateinit var db: PocketNodeCoreDatabase
    private lateinit var pending: RoomKmpPendingBroadcastStore
    private lateinit var transactions: RoomKmpTransactionStore

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PocketNodeCoreDatabase::class.java)
            .allowMainThreadQueries().build()
        pending = RoomKmpPendingBroadcastStore(db.pendingBroadcasts())
        transactions = RoomKmpTransactionStore(db.transactions())
    }
    @After fun close() = db.close()

    private fun row() = PendingBroadcastRecord("0xaa", "BROADCAST", "[]", "{}", "w1", "TESTNET", 100, 0, 10, 20)
    private suspend fun seed() {
        pending.insert(row())
        transactions.insertPendingTransaction("0xaa", "TESTNET", "w1", "0x1", "out", "0x0", 7L)
    }

    @Test fun `shared observation confirms once and stale rejection loses`() = runTest {
        seed()
        assertTrue(pending.reconcileObservation(row(), "CONFIRMED", 0, 30))
        assertFalse(pending.reconcileObservation(row(), "FAILED", 0, 40))
        assertTrue(pending.getActive("w1", "TESTNET").isEmpty())
        assertEquals("CONFIRMED", transactions.getByHash("0xaa", "TESTNET")?.status)
    }

    @Test fun `shared rejection cannot downgrade confirmed activity or rearmed attempt`() = runTest {
        seed()
        transactions.updateTransactionStatus("0xaa", "CONFIRMED")
        assertFalse(pending.reconcileObservation(row(), "FAILED", 0, 30))
        assertEquals(row(), pending.getActive("w1", "TESTNET").single())
        assertEquals("CONFIRMED", transactions.getByHash("0xaa", "TESTNET")?.status)
        pending.insert(row().copy(lastCheckedAt = 999))
        assertFalse(pending.reconcileObservation(row(), "CONFIRMED", 0, 40))
        assertEquals(999L, pending.getActive("w1", "TESTNET").single().lastCheckedAt)
    }

    @Test fun `shared observation failure rolls back activity and pending deletion`() = runTest {
        seed()
        val before = transactions.getByHash("0xaa", "TESTNET")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_observation BEFORE DELETE ON pending_broadcasts BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertNotNull(runCatching { pending.reconcileObservation(row(), "CONFIRMED", 0, 30) }.exceptionOrNull())
        assertEquals(row(), pending.getActive("w1", "TESTNET").single())
        assertEquals(before, transactions.getByHash("0xaa", "TESTNET"))
    }

    @Test fun `shared orphan settlement requires same scope pending state and no new broadcast`() = runTest {
        seed()
        transactions.reconcileOrphan("0xaa", "w1", "TESTNET", "FAILED")
        assertEquals("PENDING", transactions.getByHash("0xaa", "TESTNET")?.status)
        pending.delete("0xaa")
        transactions.reconcileOrphan("0xaa", "w2", "TESTNET", "FAILED")
        transactions.reconcileOrphan("0xaa", "w1", "MAINNET", "FAILED")
        assertEquals("PENDING", transactions.getByHash("0xaa", "TESTNET")?.status)
        transactions.reconcileOrphan("0xaa", "w1", "TESTNET", "CONFIRMED")
        transactions.reconcileOrphan("0xaa", "w1", "TESTNET", "FAILED")
        assertEquals("CONFIRMED", transactions.getByHash("0xaa", "TESTNET")?.status)
    }
}
