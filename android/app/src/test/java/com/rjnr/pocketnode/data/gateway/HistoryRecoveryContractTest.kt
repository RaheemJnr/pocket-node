package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.storage.*
import com.rjnr.pocketnode.data.wallet.AddressUtils
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HistoryRecoveryContractTest {
    private lateinit var db: AppDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val generation = "a".repeat(32)
    private val script = Script("0x" + "1".repeat(64), "type", "0x01")
    private val inactiveScript = script.copy(args = "0x02")
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() = db.close()
    private suspend fun jobs() {
        for (wallet in listOf("active", "inactive")) db.syncProgressDao().upsert(SyncProgressEntity(wallet, "MAINNET", 12_000_000, 17_000_000, 1))
        db.historyRecovery().prepare("MAINNET", generation)
    }
    private fun tip() = json.encodeToString(JniHeaderView("hash", "0x14", "0x0", "0x0", "parent", "tx", "prop", "extra", "dao", "0x0"))
    private fun registry() = object : WalletRegistry {
        override suspend fun allWallets() = listOf(
            WalletRecord("active", AddressUtils.encode(script, NetworkType.MAINNET), "", 2),
            WalletRecord("inactive", AddressUtils.encode(inactiveScript, NetworkType.MAINNET), "", 1),
        )
    }
    @Test fun `explicit recovery overrides mainnet fallback and clamps while retaining inactive obligations`() = runTest {
        jobs()
        val payloads = mutableListOf<String>()
        val api = mockk<LightClientApi>(relaxed = true) {
            every { getScripts() } returns json.encodeToString(listOf(JniScriptStatus(script, "lock", "0x1036640")))
            every { getTipHeader() } returns tip()
            every { setScripts(capture(payloads), any()) } returns true
        }
        val coordinator = SyncCoordinator(registry(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), api, mockk(relaxed = true), json, NoopLogger, historyRecovery = db.historyRecovery())
        assertTrue(coordinator.setScriptsAndRecord(listOf(JniScriptStatus(script, "lock", "0x1173f60")), listOf("active"), 0, NetworkType.MAINNET))
        val sent = json.decodeFromString<List<JniScriptStatus>>(payloads.single())
        assertEquals(setOf(script), sent.map { it.script }.toSet())
        assertTrue(sent.all { it.blockNumber == "0x0" && it.historyRecovery })
        assertEquals("PENDING", db.historyRecovery().job("inactive", "MAINNET")!!.status)
        assertNull(db.historyRecovery().job("inactive", "MAINNET")!!.targetBlock)
        coordinator.setScriptsAndRecord(listOf(JniScriptStatus(script, "lock", "0x0")), listOf("active"), 1, NetworkType.MAINNET)
        assertTrue(json.decodeFromString<List<JniScriptStatus>>(payloads.last()).single().historyRecovery)
        assertEquals("0x0", json.decodeFromString<List<JniScriptStatus>>(payloads.last()).single().blockNumber)
    }
    @Test fun `native success followed by bookkeeping failure leaves retryable obligation`() = runTest {
        jobs()
        val payloads = mutableListOf<String>()
        val api = mockk<LightClientApi>(relaxed = true) {
            every { getTipHeader() } returns tip()
            every { setScripts(capture(payloads), any()) } returns true
        }
        val failing = spyk(db.historyRecovery())
        coEvery { failing.registered(any(), any(), any(), any(), any()) } throws IllegalStateException("bookkeeping interrupted")
        fun coordinator(dao: HistoryRecoveryRoomDao) = SyncCoordinator(registry(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), api, mockk(relaxed = true), json, NoopLogger, historyRecovery = dao)
        try { coordinator(failing).setScriptsAndRecord(listOf(JniScriptStatus(script, "lock", "0x1173f60")), listOf("active"), 0, NetworkType.MAINNET); fail("accepted injected failure") } catch (_: IllegalStateException) { }
        assertEquals("PENDING", db.historyRecovery().job("active", "MAINNET")!!.status)
        assertTrue(coordinator(db.historyRecovery()).setScriptsAndRecord(listOf(JniScriptStatus(script, "lock", "0x1173f60")), listOf("active"), 0, NetworkType.MAINNET))
        assertEquals(2, payloads.size)
        assertTrue(payloads.all { json.decodeFromString<List<JniScriptStatus>>(it).all { status -> status.historyRecovery } })
    }
    @Test fun `candidate obligations survive parent completion without treating genesis as never scheduled`() = runTest {
        jobs()
        val candidate = SubAccountCandidateRecord("active", "m/44/309/1", 1, "0x03")
        val candidates = mockk<SubAccountCandidateStore>(relaxed = true)
        coEvery { candidates.getForParent("active") } returns listOf(candidate)
        coEvery { candidates.getForParent("inactive") } returns emptyList()
        val api = mockk<LightClientApi>(relaxed = true) {
            every { getTipHeader() } returns tip()
            every { setScripts(any(), any()) } returns true
        }
        val coordinator = SyncCoordinator(registry(), mockk(relaxed = true), candidates, mockk(relaxed = true), api, mockk(relaxed = true), json, NoopLogger, historyRecovery = db.historyRecovery())
        coordinator.setScriptsAndRecord(listOf(JniScriptStatus(script, "lock", "0x1173f60")), listOf("active"), 0, NetworkType.MAINNET)
        db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE name='history_script_recovery_jobs'").use {
            assertTrue("Candidate obligations need independent durable script identity", it.moveToFirst())
        }
        db.openHelper.writableDatabase.query("SELECT status FROM history_script_recovery_jobs WHERE parentWalletId='active' AND network='MAINNET'").use {
            assertTrue(it.moveToFirst()); assertEquals("REGISTERED", it.getString(0))
        }
    }
    @Test fun `known discovery queue remains bounded and independent of completed parent`() = runTest {
        jobs()
        db.historyRecovery().registered("active", "MAINNET", generation, 20, json.encodeToString(script))
        db.historyRecovery().complete("active", "MAINNET", generation, json.encodeToString(script), 20)
        val inventory = (1..7).map { SubAccountCandidateRecord("active", "path$it", it, "0x${(it + 16).toString(16)}") }
        val candidates = mockk<SubAccountCandidateStore>(relaxed = true)
        coEvery { candidates.getForParent("active") } returns inventory
        coEvery { candidates.getForParent("inactive") } returns emptyList()
        val api = mockk<LightClientApi>(relaxed = true) {
            every { getTipHeader() } returns tip()
            every { setScripts(any(), any()) } returns true
        }
        fun coordinator() = SyncCoordinator(registry(), mockk(relaxed = true), candidates, mockk(relaxed = true), api, mockk(relaxed = true), json, NoopLogger, historyRecovery = db.historyRecovery())
        coordinator().setScriptsAndRecord(listOf(JniScriptStatus(script, "lock", "0x14")), listOf("active"), 0, NetworkType.MAINNET)
        val scriptJobs = db.historyRecovery().scripts("MAINNET")
        assertEquals(7, scriptJobs.size)
        assertEquals(5, scriptJobs.count { it.status == "REGISTERED" })
        assertEquals(2, scriptJobs.count { it.status == "PENDING" })
        assertTrue(scriptJobs.all { it.parentWalletId == "active" })
        assertFalse(coordinator().refreshScriptRecovery(NetworkType.MAINNET))
        assertTrue(db.historyRecovery().scripts("MAINNET").none { it.status == "COMPLETE" })
        val selected = scriptJobs.first { it.status == "REGISTERED" }
        val candidateScript = json.decodeFromString<Script>(selected.scriptJson)
        every { api.getHistorySnapshot(selected.scriptJson) } returns json.encodeToString(proof().copy(script = candidateScript))
        assertTrue(coordinator().refreshScriptRecovery(NetworkType.MAINNET))
        assertEquals(1, db.historyRecovery().scripts("MAINNET").count { it.status == "COMPLETE" })
        assertEquals("COMPLETE", db.historyRecovery().job("active", "MAINNET")!!.status)
        assertNotEquals("COMPLETE", db.historyRecovery().job("inactive", "MAINNET")!!.status)
    }
    @Test fun `cold start without a tip still binds registration and can finish from owned canonical coverage`() = runTest {
        jobs()
        val api = mockk<LightClientApi>(relaxed = true) {
            every { getTipHeader() } returns null
            every { setScripts(any(), any()) } returns true
        }
        val coordinator = SyncCoordinator(registry(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), api, mockk(relaxed = true), json, NoopLogger, historyRecovery = db.historyRecovery())
        coordinator.setScriptsAndRecord(listOf(JniScriptStatus(script, "lock", "0x0")), listOf("active"), 0, NetworkType.MAINNET)
        assertEquals("REGISTERED", db.historyRecovery().job("active", "MAINNET")!!.status)
        assertNull(db.historyRecovery().job("active", "MAINNET")!!.targetBlock)
        read(proof())
        assertEquals("COMPLETE", db.historyRecovery().job("active", "MAINNET")!!.status)
        assertEquals(20L, db.historyRecovery().job("active", "MAINNET")!!.targetBlock)
    }
    @Test fun `generation change at candidate completion cannot retire current source slot`() = runTest {
        jobs()
        val candidateScript = script.copy(args = "0x03")
        db.subAccountCandidateDao().insertAll(listOf(com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity(
            parentWalletId = "active", derivationPath = "path1", accountIndex = 1, scriptArgs = candidateScript.args, createdAt = 1)))
        val candidates = RoomSubAccountCandidateStore(db.subAccountCandidateDao())
        val api = mockk<LightClientApi>(relaxed = true) {
            every { getTipHeader() } returns tip()
            every { setScripts(any(), any()) } returns true
            every { getHistorySnapshot(any()) } returns json.encodeToString(proof().copy(script = candidateScript))
        }
        val raced = spyk(db.historyRecovery())
        val coordinator = SyncCoordinator(registry(), mockk(relaxed = true), candidates, mockk(relaxed = true), api, mockk(relaxed = true), json, NoopLogger, historyRecovery = raced)
        coordinator.setScriptsAndRecord(listOf(JniScriptStatus(script, "lock", "0x0")), listOf("active"), 0, NetworkType.MAINNET)
        coEvery { raced.completeScript(any(), any(), any(), any(), any()) } coAnswers {
            db.historyRecovery().resetScripts("MAINNET", "b".repeat(32))
            db.historyRecovery().recordEpoch("MAINNET", "b".repeat(32))
            callOriginal()
        }
        coordinator.refreshScriptRecovery(NetworkType.MAINNET)
        assertEquals("PENDING", db.subAccountCandidateDao().getForParent("active").single().state)
    }
    @Test fun `wallet cap defers obligations and selecting a deferred wallet still recovers from genesis`() = runTest {
        val scripts = (0..5).map { script.copy(args = "0x${(it + 32).toString(16)}") }
        val owners = scripts.mapIndexed { index, lock -> WalletRecord("wallet$index", AddressUtils.encode(lock, NetworkType.MAINNET), "", index.toLong()) }
        for (owner in owners) db.syncProgressDao().upsert(SyncProgressEntity(owner.walletId, "MAINNET", 12_000_000, 17_000_000, 1))
        db.historyRecovery().prepare("MAINNET", generation)
        val registry = object : WalletRegistry { override suspend fun allWallets() = owners }
        val payloads = mutableListOf<String>()
        val api = mockk<LightClientApi>(relaxed = true) {
            every { getTipHeader() } returns tip()
            every { setScripts(capture(payloads), any()) } returns true
        }
        val coordinator = SyncCoordinator(registry, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), api, mockk(relaxed = true), json, NoopLogger, historyRecovery = db.historyRecovery())
        coordinator.setScriptsAndRecord(scripts.take(3).map { JniScriptStatus(it, "lock", "0x1036640") }, owners.take(3).map { it.walletId }, 0, NetworkType.MAINNET)
        val sent = json.decodeFromString<List<JniScriptStatus>>(payloads.last())
        assertEquals("Recovery must preserve the caller's bounded wallet selection", 3, sent.size)
        assertTrue(sent.all { it.historyRecovery && it.blockNumber == "0x0" })
        for (index in 3..5) assertEquals("PENDING", db.historyRecovery().job("wallet$index", "MAINNET")!!.status)
        coordinator.setScriptsAndRecord(listOf(JniScriptStatus(scripts[5], "lock", "0x1036640")), listOf("wallet5"), 0, NetworkType.MAINNET)
        val selected = json.decodeFromString<List<JniScriptStatus>>(payloads.last()).single()
        assertEquals(scripts[5], selected.script)
        assertTrue(selected.historyRecovery && selected.blockNumber == "0x0")
        assertEquals("REGISTERED", db.historyRecovery().job("wallet5", "MAINNET")!!.status)
        assertEquals("PENDING", db.historyRecovery().job("wallet4", "MAINNET")!!.status)
    }
    private fun proof() = CanonicalHistorySnapshot(1, true, "MAINNET", "0x" + "1".repeat(64), generation, script, "b".repeat(32), "0", "20", "0x" + "2".repeat(64), "20", emptySet(), 1)
    private suspend fun read(snapshot: CanonicalHistorySnapshot, reconciliationFails: Boolean = false, unavailableRecheck: Boolean = false) {
        val api = mockk<LightClientApi>(relaxed = true) {
            every { getTransactions(any(), any(), any(), any()) } returns """{"objects":[],"last_cursor":null}"""
            every { getHistorySnapshot(any()) } returnsMany (if (unavailableRecheck) listOf(json.encodeToString(snapshot), null, json.encodeToString(snapshot)) else listOf(json.encodeToString(snapshot)))
        }
        val store = mockk<TransactionStore>(relaxed = true)
        coEvery { store.getPendingNotIn(any(), any(), any()) } returns emptyList()
        coEvery { store.reconcileCanonicalHistory(any(), any(), any(), any()) } coAnswers {
            db.transactionDao().reconcileCanonicalHistory(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        if (reconciliationFails) coEvery { store.reconcileCanonicalHistory(any(), any(), any(), any()) } throws IllegalStateException("reconcile interrupted")
        val reader = LedgerReader(api, mockk(relaxed = true), store, mockk(relaxed = true), registry(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), json, NoopLogger, historyRecovery = db.historyRecovery())
        reader.getTransactions(script, "active", NetworkType.MAINNET)
    }
    @Test fun `skipped transaction recheck does not complete when the next native read recovers`() = runTest {
        jobs()
        db.historyRecovery().registered("active", "MAINNET", generation, 20, json.encodeToString(script))
        db.transactionDao().insert(com.rjnr.pocketnode.data.database.entity.TransactionEntity("0x" + "c".repeat(64), "0xa", "old", 1, "1", "in", "", 1, null, "MAINNET", "CONFIRMED", false, 1, "active"))
        read(proof(), unavailableRecheck = true)
        assertEquals("REGISTERED", db.historyRecovery().job("active", "MAINNET")!!.status)
    }
    @Test fun `completion requires durable genesis canonical coverage after successful reconciliation`() = runTest {
        jobs()
        db.historyRecovery().registered("active", "MAINNET", generation, 20, json.encodeToString(script))
        val valid = proof()
        for (bad in listOf(valid.copy(durableCoverageVersion = 0), valid.copy(complete = false), valid.copy(startHeight = "1"), valid.copy(processedThrough = "19"), valid.copy(storeGeneration = "other"), valid.copy(script = inactiveScript), valid.copy(network = "TESTNET"))) {
            read(bad)
            assertEquals("REGISTERED", db.historyRecovery().job("active", "MAINNET")!!.status)
        }
        read(valid, reconciliationFails = true)
        assertEquals("REGISTERED", db.historyRecovery().job("active", "MAINNET")!!.status)
        read(valid)
        assertEquals("COMPLETE", db.historyRecovery().job("active", "MAINNET")!!.status)
        assertEquals("PENDING", db.historyRecovery().job("inactive", "MAINNET")!!.status)
        // Ordinary restart and compatible epoch handoff must not create another scan.
        assertFalse(db.historyRecovery().prepare("MAINNET", generation))
        assertEquals("COMPLETE", db.historyRecovery().job("active", "MAINNET")!!.status)
    }
}
