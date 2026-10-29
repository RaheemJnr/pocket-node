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

    @Test fun `canonical snapshot removes orphan preserves pending and permits reinclusion`() = runTest {
        val hash = "0x" + "a".repeat(64)
        val script = com.rjnr.pocketnode.data.gateway.models.Script("0x" + "1".repeat(64), "type", "0x01")
        val snapshot = com.rjnr.pocketnode.data.gateway.CanonicalHistorySnapshot(1, true, "TESTNET", "0x" + "1".repeat(64), "store", script, "a".repeat(32), "0", "20", "0x" + "2".repeat(64), "20", emptySet())
        val record = com.rjnr.pocketnode.data.gateway.models.TransactionRecord(hash, "0xa", "0x" + "3".repeat(64), 0, "0x1", "in", "0x0", 10)
        transactions.cacheTransactions(listOf(record), "TESTNET", "w1")
        transactions.insertPendingTransaction("local", "TESTNET", "w1", "0x1", "out", "0x0", null)
        transactions.reconcileCanonicalHistory(snapshot, "w1", "TESTNET")
        assertNull(transactions.getByHash(hash, "TESTNET"))
        assertEquals("PENDING", transactions.getByHash("local", "TESTNET")?.status)
        transactions.cacheTransactions(listOf(record.copy(blockNumber = "0xb")), "TESTNET", "w1")
        transactions.reconcileCanonicalHistory(snapshot.copy(hashes = setOf(hash)), "w1", "TESTNET")
        assertEquals("0xb", transactions.getByHash(hash, "TESTNET")?.blockNumber)
    }

    private fun historyProof() = com.rjnr.pocketnode.data.gateway.CanonicalHistorySnapshot(
        1, true, "TESTNET", "0x" + "1".repeat(64), "store",
        com.rjnr.pocketnode.data.gateway.models.Script("0x" + "1".repeat(64), "type", "0x01"),
        "a".repeat(32), "0", "20", "0x" + "2".repeat(64), "20", emptySet())

    @Test fun `canonical cleanup retains incomplete wrong scope horizon and broadcast owned rows`() = runTest {
        val hash = "0x" + "a".repeat(64)
        val original = TransactionRow(hash, "0xa", "old", 0, "0x1", "in", "0x0", 2, null, "TESTNET", "CONFIRMED", false, 10, "w1")
        db.transactions().insert(original)
        val proof = historyProof()
        for (bad in listOf(proof.copy(complete = false), proof.copy(processedThrough = "19"), proof.copy(network = "MAINNET"), proof.copy(startHeight = "10"), proof.copy(tipHash = "bad"))) {
            transactions.reconcileCanonicalHistory(bad, "w1", "TESTNET")
            assertEquals(original, db.transactions().getByHash(hash, "TESTNET"))
        }
        transactions.reconcileCanonicalHistory(proof, "w2", "TESTNET")
        assertEquals(original, db.transactions().getByHash(hash, "TESTNET"))
        pending.insert(row().copy(txHash = hash))
        transactions.reconcileCanonicalHistory(proof, "w1", "TESTNET")
        assertEquals(original, db.transactions().getByHash(hash, "TESTNET"))
        pending.delete(hash)
        transactions.reconcileCanonicalHistory(proof, "w1", "TESTNET")
        assertNull(db.transactions().getByHash(hash, "TESTNET"))
    }

    @Test fun `canonical conditional deletion cannot erase late reinclusion or pending transition`() = runTest {
        val hash = "0x" + "a".repeat(64)
        val original = TransactionRow(hash, "0xa", "old", 0, "0x1", "in", "0x0", 2, null, "TESTNET", "CONFIRMED", false, 10, "w1")
        for (advanced in listOf(original.copy(blockNumber = "0xb", blockHash = "new", cachedAt = 11), original.copy(status = "PENDING"))) {
            db.transactions().insert(advanced)
            db.transactions().deleteCanonicalOrphan(hash, "w1", "TESTNET", original.blockNumber, original.blockHash, original.cachedAt)
            assertEquals(advanced, db.transactions().getByHash(hash, "TESTNET"))
        }
    }

    @Test fun `canonical cleanup failure rolls back earlier deletions`() = runTest {
        val hash = "0x" + "a".repeat(64)
        val original = TransactionRow(hash, "0xa", "old", 0, "0x1", "in", "0x0", 2, null, "TESTNET", "CONFIRMED", false, 10, "w1")
        db.transactions().insert(original)
        db.transactions().insert(original.copy(txHash = "0x" + "b".repeat(64)))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_history BEFORE DELETE ON transactions WHEN OLD.txHash = '0x${"b".repeat(64)}' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        val failure = runCatching { transactions.reconcileCanonicalHistory(historyProof(), "w1", "TESTNET") }.exceptionOrNull()
        org.junit.Assert.assertNotNull(failure)
        assertEquals(original, db.transactions().getByHash(hash, "TESTNET"))
        assertEquals(2, db.transactions().canonicalCandidates("w1", "TESTNET").size)
    }
}
