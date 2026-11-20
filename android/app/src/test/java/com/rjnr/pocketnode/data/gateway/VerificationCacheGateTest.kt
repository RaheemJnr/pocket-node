package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VerificationCacheGateTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private fun client(token: String = "a".repeat(32)): LightClientApi = mockk {
        every { callRpc("get_verification_epoch") } returns """{"result":{"version":1,"generation":"$token"}}"""
    }
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("verified_chain_cache", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() = db.close()
    private suspend fun seed() {
        for (wallet in listOf("active", "inactive")) db.syncProgressDao().upsert(SyncProgressEntity(wallet, "MAINNET", 100, 900, 1))
        db.syncProgressDao().upsert(SyncProgressEntity("other", "TESTNET", 200, 800, 1))
        for ((hash, local, status) in listOf(Triple("confirmed", false, "CONFIRMED"), Triple("outgoing", true, "PENDING"), Triple("failed-local", true, "FAILED"), Triple("remote-pending", false, "PENDING"))) {
            db.transactionDao().insert(TransactionEntity(hash, "0x384", "old-block", 1, "100", "out", "2", 10, "0x1", "MAINNET", status, local, 1, "active", 2))
        }
    }
    @Test fun `later verified hints refresh archive and survive repeated quarantine`() = runTest {
        seed()
        val gate = VerificationCacheGate(context, db)
        gate.reconcile(client(), NetworkType.MAINNET)
        db.openHelper.writableDatabase.execSQL("UPDATE transactions SET status='CONFIRMED', blockNumber='0x999', balanceChange='123', fee='9' WHERE txHash='confirmed'")
        gate.reconcile(client("b".repeat(32)), NetworkType.MAINNET)
        gate.reconcile(client("c".repeat(32)), NetworkType.MAINNET)
        val row = db.historyRecovery().archive("active", "MAINNET").single { it.txHash == "confirmed" }
        assertEquals("0x999", row.blockNumberHint)
        assertEquals("123", row.balanceChangeHint)
        assertEquals("9", row.feeHint)
    }

    @Test fun `later withdrawal refreshes existing DAO archive locator`() = runTest {
        val cell = com.rjnr.pocketnode.data.database.entity.DaoCellEntity(
            txHash = "deposit", index = "0x0", capacity = 1000, status = "DEPOSITED",
            depositBlockNumber = 10, depositBlockHash = "deposit-block", depositEpochHex = null,
            withdrawBlockNumber = null, withdrawBlockHash = null, withdrawEpochHex = null,
            compensation = 0, unlockEpochHex = null, depositTimestamp = 1, network = "MAINNET", lastUpdatedAt = 1, walletId = "active")
        db.daoCellDao().upsert(cell)
        val gate = VerificationCacheGate(context, db)
        gate.reconcile(client(), NetworkType.MAINNET)
        db.daoCellDao().upsert(cell.copy(withdrawBlockNumber = 20, status = "WITHDRAWING"))
        gate.reconcile(client("b".repeat(32)), NetworkType.MAINNET)
        assertEquals(20L, db.historyRecovery().daoHints("active", "MAINNET").single().withdrawBlockHint)
    }

    @Test fun `pre checkpoint history and inactive recovery survive verification erasure`() = runTest {
        seed()
        db.transactionDao().insert(TransactionEntity("historic", "0x1036640", "old", 123, "42", "in", "1", 9, "0x7b", "MAINNET", "CONFIRMED", false, 1, "inactive"))
        VerificationCacheGate(context, db).reconcile(client(), NetworkType.MAINNET)
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name='history_archive'").use {
            assertTrue("Verification erases pre-18.3M history without a durable archive", it.moveToFirst())
        }
        sql.query("SELECT blockNumberHint, balanceChangeHint FROM history_archive WHERE txHash='historic'").use {
            assertTrue(it.moveToFirst()); assertEquals("0x1036640", it.getString(0)); assertEquals("42", it.getString(1))
        }
        sql.query("SELECT startBlock, previousSavedBlock FROM history_recovery_jobs WHERE walletId='inactive' AND network='MAINNET'").use {
            assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertEquals(900L, it.getLong(1))
        }
        assertEquals("UNVERIFIED", db.transactionDao().getByTxHash("historic")!!.status)
    }
    @Test fun `already erased same generation identities retain unknown hints and receive recovery`() = runTest {
        seed()
        val generation = "a".repeat(32)
        context.getSharedPreferences("verified_chain_cache", Context.MODE_PRIVATE).edit().putString("MAINNET", generation).commit()
        db.openHelper.writableDatabase.execSQL("UPDATE transactions SET status='UNVERIFIED', blockNumber='0x0', balanceChange='0', blockTimestampHex=NULL")
        VerificationCacheGate(context, db).reconcile(client(), NetworkType.MAINNET)
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT name FROM sqlite_master WHERE name='history_archive'").use { assertTrue("Same verifier generation still needs archive repair", it.moveToFirst()) }
        sql.query("SELECT blockNumberHint, balanceChangeHint, timestampHint, directionHint FROM history_archive WHERE txHash='confirmed'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.isNull(1))
            assertEquals(1L, it.getLong(2)); assertEquals("out", it.getString(3))
        }
        sql.query("SELECT startBlock FROM history_recovery_jobs WHERE walletId='inactive' AND network='MAINNET'").use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
    }
    @Test fun `handoff covers inactive wallets preserves settings and local outgoing records`() = runTest {
        seed()
        val prefs = context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("sync_mode", "CUSTOM").putLong("custom_height", 100).commit()
        val gate = VerificationCacheGate(context, db)
        assertFalse(gate.isReady("MAINNET"))
        gate.reconcile(client(), NetworkType.MAINNET)
        assertTrue(gate.isReady("MAINNET"))
        assertTrue(db.syncProgressDao().getAllForNetwork("MAINNET").isEmpty())
        assertEquals(800L, db.syncProgressDao().get("other", "TESTNET")!!.localSavedBlockNumber)
        assertEquals("UNVERIFIED", db.transactionDao().getByTxHash("confirmed")!!.status)
        assertEquals("UNVERIFIED", db.transactionDao().getByTxHash("remote-pending")!!.status)
        assertEquals(setOf("outgoing", "failed-local"), db.transactionDao().getAllByWalletAndNetwork("active", "MAINNET").map { it.txHash }.toSet())
        assertEquals("CUSTOM", prefs.getString("sync_mode", null))
        assertEquals(100L, prefs.getLong("custom_height", -1))
        gate.close()
        assertFalse(gate.isReady("MAINNET"))
    }
    @Test fun `same generation is idempotent but new generation invalidates again`() = runTest {
        val gate = VerificationCacheGate(context, db)
        gate.reconcile(client(), NetworkType.MAINNET)
        db.syncProgressDao().upsert(SyncProgressEntity("inactive", "MAINNET", 100, 400, 1))
        gate.reconcile(client(), NetworkType.MAINNET)
        assertEquals(400L, db.syncProgressDao().get("inactive", "MAINNET")!!.localSavedBlockNumber)
        gate.reconcile(client("b".repeat(32)), NetworkType.MAINNET)
        assertNull(db.syncProgressDao().get("inactive", "MAINNET"))
    }
    @Test fun `malformed epoch leaves gate closed and data untouched`() = runTest {
        seed()
        val gate = VerificationCacheGate(context, db)
        try { gate.reconcile(client("bad"), NetworkType.MAINNET); fail("accepted bad epoch") } catch (_: IllegalArgumentException) { }
        assertFalse(gate.isReady("MAINNET"))
        assertEquals(2, db.syncProgressDao().getAllForNetwork("MAINNET").size)
        assertEquals("CONFIRMED", db.transactionDao().getByTxHash("confirmed")!!.status)
    }
    @Test fun `failed marker commit keeps gate closed and cleanup repeats before retry opens it`() = runTest {
        seed()
        val gate = VerificationCacheGate(context, db)
        val commit = gate.writeGeneration
        gate.writeGeneration = { _, _ -> false }
        try { gate.reconcile(client(), NetworkType.MAINNET); fail("accepted failed commit") } catch (_: IllegalStateException) { }
        assertFalse(gate.isReady("MAINNET"))
        assertTrue(db.syncProgressDao().getAllForNetwork("MAINNET").isEmpty())
        db.syncProgressDao().upsert(SyncProgressEntity("inactive", "MAINNET", 100, 400, 1))
        gate.writeGeneration = commit
        gate.reconcile(client(), NetworkType.MAINNET)
        assertTrue(gate.isReady("MAINNET"))
        assertTrue(db.syncProgressDao().getAllForNetwork("MAINNET").isEmpty())
    }
    @Test fun `network switch during reconciliation cannot reopen stale gate`() = runTest {
        val gate = VerificationCacheGate(context, db)
        val commit = gate.writeGeneration
        gate.writeGeneration = { network, generation ->
            val saved = commit(network, generation)
            gate.close()
            saved
        }
        try { gate.reconcile(client(), NetworkType.MAINNET); fail("stale gate reopened") } catch (_: IllegalStateException) { }
        assertFalse(gate.isReady("MAINNET"))
        assertTrue(gate.readyNetworks.value.isEmpty())
    }

    @Test fun `DAO pending preservation requires local provenance on the same network`() = runTest {
        seed()
        db.transactionDao().insert(TransactionEntity("other-network", "0x0", "", 1, "1", "out", "", 0, null, "TESTNET", "PENDING", true, 1, "active"))
        for ((hash, network) in listOf("broadcast-only" to "MAINNET", "broadcast-other" to "TESTNET")) {
            db.pendingBroadcastDao().insert(PendingBroadcastEntity(hash, "active", network,
                "{}", "[]", "BROADCAST", 1, 0, 1, 1))
        }
        val writer = DaoSyncManager(db.headerCacheDao(), db.daoCellDao(),
            db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(), NoopLogger, readyVerificationGate())
        for (hash in listOf("outgoing", "remote-pending", "other-network", "broadcast-only", "broadcast-other")) {
            writer.insertPendingDeposit(hash, 100, "MAINNET", walletId = "active")
            assertEquals("DEPOSITING", db.daoCellDao().getByOutPoint(hash, "0x0")!!.status)
        }
        VerificationCacheGate(context, db).reconcile(client(), NetworkType.MAINNET)
        assertNotNull(db.daoCellDao().getByOutPoint("outgoing", "0x0"))
        assertNull(db.daoCellDao().getByOutPoint("remote-pending", "0x0"))
        assertNull(db.daoCellDao().getByOutPoint("other-network", "0x0"))
        assertNotNull(db.daoCellDao().getByOutPoint("broadcast-only", "0x0"))
        assertNull(db.daoCellDao().getByOutPoint("broadcast-other", "0x0"))
    }

    @Test fun `failed marker after memory publication retries until a checked durable write succeeds`() = runTest {
        val gate = VerificationCacheGate(context, db)
        val preferences = context.getSharedPreferences("verified_chain_cache", Context.MODE_PRIVATE)
        var attempts = 0
        gate.writeGeneration = { network, generation ->
            // Model SharedPreferences publishing its memory map before reporting disk failure.
            preferences.edit().putString(network, generation).commit()
            ++attempts >= 3
        }
        repeat(2) {
            db.syncProgressDao().upsert(SyncProgressEntity("inactive", "MAINNET", 100, 400, 1))
            try { gate.reconcile(client(), NetworkType.MAINNET); fail("accepted failed commit") }
            catch (_: IllegalStateException) { }
            assertFalse(gate.isReady("MAINNET"))
            assertEquals("a".repeat(32), preferences.getString("MAINNET", null))
            assertTrue(db.syncProgressDao().getAllForNetwork("MAINNET").isEmpty())
        }
        gate.reconcile(client(), NetworkType.MAINNET)
        assertEquals(3, attempts)
        assertTrue(gate.isReady("MAINNET"))
    }

    @Test fun `close serializes with transaction commit and superseded mutations are rejected`() = runTest {
        val gate = VerificationCacheGate(context, db)
        gate.reconcile(client(), NetworkType.MAINNET)
        val ticket = gate.requireReadyRevision("MAINNET")
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val mutation = async {
            gate.commitIfCurrent("MAINNET", ticket) {
                db.syncProgressDao().upsert(SyncProgressEntity("active", "MAINNET", 100, 400, 1))
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            gate.commitIfCurrent("MAINNET", ticket) {
                db.syncProgressDao().upsert(SyncProgressEntity("cancelled", "MAINNET", 100, 400, 1))
            }
        }
        cancelled.cancel()
        val closing = async(start = CoroutineStart.UNDISPATCHED) { gate.close() }
        assertFalse(closing.isCompleted)
        finish.complete(Unit)
        mutation.await()
        closing.await()
        cancelled.join()
        assertNull(db.syncProgressDao().get("cancelled", "MAINNET"))
        assertFalse(gate.isReady("MAINNET"))
        assertEquals(400L, db.syncProgressDao().get("active", "MAINNET")!!.localSavedBlockNumber)
        try {
            gate.commitIfCurrent("MAINNET", ticket) { db.syncProgressDao().upsert(SyncProgressEntity("active", "MAINNET", 100, 900, 1)) }
            fail("superseded mutation ran")
        } catch (_: IllegalStateException) { }
        assertEquals(400L, db.syncProgressDao().get("active", "MAINNET")!!.localSavedBlockNumber)
    }

    @Test fun `mutation failure rolls back the whole staged transaction`() = runTest {
        val gate = VerificationCacheGate(context, db)
        gate.reconcile(client(), NetworkType.MAINNET)
        val ticket = gate.requireReadyRevision("MAINNET")
        try {
            gate.commitIfCurrent("MAINNET", ticket) {
                db.syncProgressDao().upsert(SyncProgressEntity("active", "MAINNET", 100, 400, 1))
                error("injected write failure")
            }
            fail("accepted write failure")
        } catch (_: IllegalStateException) { }
        assertNull(db.syncProgressDao().get("active", "MAINNET"))
    }

}
