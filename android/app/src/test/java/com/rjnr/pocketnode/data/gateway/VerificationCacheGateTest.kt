package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
        for (hash in listOf("outgoing", "remote-pending", "other-network")) {
            db.daoCellDao().upsert(DaoCellEntity(hash, "0x0", 100, "PENDING", 0, "", null,
                null, null, null, 0, null, 1, "MAINNET", 1, "active"))
        }
        VerificationCacheGate(context, db).reconcile(client(), NetworkType.MAINNET)
        assertNotNull(db.daoCellDao().getByOutPoint("outgoing", "0x0"))
        assertNull(db.daoCellDao().getByOutPoint("remote-pending", "0x0"))
        assertNull(db.daoCellDao().getByOutPoint("other-network", "0x0"))
    }

}
