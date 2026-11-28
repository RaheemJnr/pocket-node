package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.core.prefs.SyncStrategy
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.storage.SyncProgressStore
import com.rjnr.pocketnode.data.storage.WalletRecord
import com.rjnr.pocketnode.data.storage.WalletRegistry
import com.rjnr.pocketnode.data.wallet.AddressUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.8.7.1 hotfix: part B keeps each wallet's own start across a verification
 * handoff (the 1.8.6 -> 1.8.7.1 upgrade), part A offers a one-time scan to
 * wallets the 1.8.6 -> 1.8.7 upgrade already restarted at the tip.
 */
@RunWith(RobolectricTestRunner::class)
class UpgradeRescanTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val modes = mutableMapOf<String, SyncMode>()
    private val heights = mutableMapOf<String, Long>()
    private val prefs: SyncPreferences = mockk(relaxed = true) {
        every { getSyncMode(any(), any()) } answers { modes[secondArg<String?>()] ?: SyncMode.NEW_WALLET }
        every { getCustomBlockHeight(any(), any()) } answers { heights[secondArg<String?>()] }
        every { getSyncStrategy() } returns SyncStrategy.ALL_WALLETS
    }

    private fun client(token: String = "a".repeat(32)): LightClientApi = mockk {
        every { callRpc("get_verification_epoch") } returns """{"result":{"version":1,"generation":"$token"}}"""
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("verified_chain_cache", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(UpgradeRescanFlags.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    private fun script(byte: String) = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + byte.repeat(20),
    )

    private suspend fun wallet(id: String, byte: String, mode: SyncMode, createdAt: Long = 0L) {
        val s = script(byte)
        db.walletDao().insert(
            WalletEntity(
                walletId = id, name = id, type = "mnemonic", derivationPath = null, parentWalletId = null,
                mainnetAddress = AddressUtils.encode(s, NetworkType.MAINNET),
                testnetAddress = AddressUtils.encode(s, NetworkType.TESTNET),
                createdAt = createdAt, lastActiveAt = 1L,
            )
        )
        modes[id] = mode
    }

    private suspend fun tx(hash: String, walletId: String, block: Long, status: String = "CONFIRMED", timestamp: Long = 0L, cachedAt: Long = 1L) =
        db.transactionDao().insert(
            TransactionEntity(hash, "0x${block.toString(16)}", "0xhash", timestamp, "100", "in", "0x0", 10,
                "0x1", "TESTNET", status, false, cachedAt, walletId)
        )

    private fun hex(block: Long) = "\"block_number\":\"0x${block.toString(16)}\""

    // ---- Pure: part B own start ----

    @Test fun `own start is the earliest evidence minus the margin`() {
        assertEquals(4_000L, upgradeOwnStart(9_000L, 5_000L, null, SyncMode.NEW_WALLET, null))
        assertEquals(2_000L, upgradeOwnStart(9_000L, 5_000L, 3_000L, SyncMode.RECENT, null))
        assertEquals(800L, upgradeOwnStart(800L, 5_000L, null, SyncMode.NEW_WALLET, null))
    }

    @Test fun `own start floors at zero and honours tip-free mode starts`() {
        assertEquals(0L, upgradeOwnStart(9_000L, 400L, null, SyncMode.NEW_WALLET, null))
        assertEquals(0L, upgradeOwnStart(9_000L, 5_000L, null, SyncMode.FULL_HISTORY, null))
        assertEquals(1_500L, upgradeOwnStart(9_000L, 5_000L, null, SyncMode.CUSTOM, 1_500L))
    }

    @Test fun `own start is null without any evidence`() {
        assertNull(upgradeOwnStart(null, null, null, SyncMode.FULL_HISTORY, null))
        assertNull(upgradeOwnStart(null, null, null, SyncMode.CUSTOM, 1_500L))
    }

    @Test fun `genesis own start outside full history is saved as block 1`() {
        assertEquals(1L, ownStartSavedBlock(0L, SyncMode.NEW_WALLET))
        assertEquals(0L, ownStartSavedBlock(0L, SyncMode.FULL_HISTORY))
        assertEquals(4_000L, ownStartSavedBlock(4_000L, SyncMode.RECENT))
    }

    // ---- Pure: part A estimate ----

    @Test fun `estimate from a block hint keeps the margin and never exceeds the oldest hint`() {
        val start = estimateUpgradeRescanStart(1_000_000L, 0L, null, listOf(700_000L, 600_000L), SyncMode.NEW_WALLET)
        assertEquals(600_000L - UPGRADE_RESCAN_MARGIN_BLOCKS, start)
        assertTrue(start!! <= 600_000L - 10_000L)
    }

    @Test fun `estimate from a timestamp errs low and subtracts the recent window`() {
        val tipTs = 1_000_000_000_000L
        val day = 86_400_000L
        val fromTime = estimateUpgradeRescanStart(2_000_000L, tipTs, tipTs - day, emptyList(), SyncMode.NEW_WALLET)
        // 6 s blocks: one day is 14,400 blocks, a real day (8 to 13 s) is fewer.
        assertEquals(2_000_000L - 14_400L - 10_000L, fromTime)
        val recent = estimateUpgradeRescanStart(2_000_000L, tipTs, tipTs - day, emptyList(), SyncMode.RECENT)
        assertEquals(fromTime!! - RECENT_WINDOW_BLOCKS, recent)
        // A hint below the time estimate wins: never above the oldest row.
        assertEquals(50_000L - 10_000L, estimateUpgradeRescanStart(2_000_000L, tipTs, tipTs - day, listOf(50_000L), SyncMode.NEW_WALLET))
    }

    @Test fun `estimate floors at zero and is null without evidence`() {
        assertEquals(0L, estimateUpgradeRescanStart(1_000_000L, 0L, null, listOf(5_000L), SyncMode.NEW_WALLET))
        assertEquals(0L, conservativeBlockAt(100L, 10_000_000L, 0L))
        assertNull(estimateUpgradeRescanStart(1_000_000L, 1L, null, emptyList(), SyncMode.NEW_WALLET))
    }

    // ---- Part B: handoff keeps each wallet's own start ----

    /** DB as 1.8.6 left it (schema 20 after the Room migrations): no applied generation. */
    private suspend fun seed186() {
        wallet("new", "aa", SyncMode.NEW_WALLET)
        wallet("recent", "bb", SyncMode.RECENT)
        wallet("full", "cc", SyncMode.FULL_HISTORY)
        wallet("empty", "dd", SyncMode.NEW_WALLET)
        db.syncProgressDao().upsert(SyncProgressEntity("new", "TESTNET", 19_000_000, 19_100_000, 1))
        db.syncProgressDao().upsert(SyncProgressEntity("recent", "TESTNET", 18_900_000, 19_100_000, 1))
        db.syncProgressDao().upsert(SyncProgressEntity("full", "TESTNET", 19_050_000, 19_100_000, 1))
        tx("n1", "new", 18_500_000)
        tx("n2", "new", 18_700_000)
        tx("r1", "recent", 18_950_000)
        tx("f1", "full", 3_000_000)
        db.daoCellDao().upsert(
            DaoCellEntity(
                txHash = "d1", index = "0x0", capacity = 10_000_000_000, status = "DEPOSITED",
                depositBlockNumber = 18_800_000, depositBlockHash = "0xb", depositEpochHex = null,
                withdrawBlockNumber = null, withdrawBlockHash = null, withdrawEpochHex = null,
                compensation = 0, unlockEpochHex = null, depositTimestamp = 0, network = "TESTNET",
                lastUpdatedAt = 1, walletId = "recent",
            )
        )
    }

    @Test fun `handoff seeds every wallet's own start instead of dropping sync progress`() = runTest {
        seed186()
        VerificationCacheGate(context, db, syncPreferences = prefs).reconcile(client(), NetworkType.TESTNET)
        val rows = db.syncProgressDao().getAllForNetwork("TESTNET").associateBy { it.walletId }
        assertEquals(18_499_000L, rows.getValue("new").localSavedBlockNumber)
        assertEquals(18_499_000L, rows.getValue("new").lightStartBlockNumber)
        // DAO deposit at 18.8M is older than the RECENT wallet's only tx.
        assertEquals(18_799_000L, rows.getValue("recent").localSavedBlockNumber)
        assertEquals(0L, rows.getValue("full").localSavedBlockNumber)
        // No history and no previous row: registration keeps using the mode start.
        assertNull(rows["empty"])
        // History is still quarantined: the native cells are re-verified, not trusted.
        assertEquals("UNVERIFIED", db.transactionDao().getByTxHash("n1")!!.status)
        assertEquals("0x0", db.transactionDao().getByTxHash("n1")!!.blockNumber)
        // Clean 1.8.6 data: no upgrade-scan banner for these wallets.
        val flags = UpgradeRescanFlags(context)
        assertTrue(flags.isDone("new", "TESTNET"))
        assertTrue(flags.isDone("recent", "TESTNET"))
    }

    @Test fun `a second handoff over quarantined history neither moves the start up nor marks it done`() = runTest {
        wallet("new", "aa", SyncMode.NEW_WALLET)
        db.syncProgressDao().upsert(SyncProgressEntity("new", "TESTNET", 19_000_000, 19_100_000, 1))
        tx("n1", "new", 0, status = "UNVERIFIED")
        VerificationCacheGate(context, db, syncPreferences = prefs).reconcile(client("b".repeat(32)), NetworkType.TESTNET)
        assertEquals(19_000_000L, db.syncProgressDao().get("new", "TESTNET")!!.localSavedBlockNumber)
        assertFalse(UpgradeRescanFlags(context).isDone("new", "TESTNET"))
    }

    @Test fun `registration after the handoff requests each wallet's own start`() = runTest {
        seed186()
        VerificationCacheGate(context, db, syncPreferences = prefs).reconcile(client(), NetworkType.TESTNET)

        val sent = mutableListOf<String>()
        val light = mockk<LightClientApi>(relaxed = true) {
            every { getTipHeader() } returns """{"hash":"0x1","number":"0x${19_200_000L.toString(16)}","epoch":"0x0",
                |"timestamp":"0x1","parent_hash":"0x0","transactions_root":"0x0","proposals_hash":"0x0",
                |"extra_hash":"0x0","dao":"0x0","nonce":"0x0"}""".trimMargin()
            every { getScripts() } returns "[]"
            every { setScripts(any(), any()) } answers { sent += firstArg<String>(); true }
        }
        val registry = object : WalletRegistry {
            override suspend fun allWallets() = db.walletDao().getAll()
                .filter { it.walletId != "empty" }
                .map { WalletRecord(it.walletId, it.mainnetAddress, it.testnetAddress, it.lastActiveAt) }
        }
        val coordinator = SyncCoordinator(
            walletRegistry = registry,
            syncProgressStore = mockk<SyncProgressStore>(relaxed = true) { coEvery { updateLightStart(any(), any(), any(), any()) } returns 1 },
            subAccountCandidateStore = mockk(relaxed = true),
            transactionStore = mockk(relaxed = true),
            lightClient = light,
            syncPreferences = prefs,
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
            logger = NoopLogger,
        )
        coordinator.registerAllWalletScripts(
            SyncCoordinator.SyncContext(
                network = NetworkType.TESTNET,
                activeWalletId = "new",
                awaitNodeReady = { true },
                // GatewayRepository.getWalletSyncBlock: the saved row, else 0.
                getWalletSyncBlock = { db.syncProgressDao().get(it, "TESTNET")?.localSavedBlockNumber ?: 0L },
                onScriptsRegistered = {},
            )
        )
        val payload = sent.single()
        assertTrue(payload, payload.contains(hex(18_499_000L)))
        assertTrue(payload, payload.contains(hex(18_799_000L)))
        assertTrue(payload, payload.contains(hex(0L)))
        // The 1.8.7 behaviour this replaces: NEW_WALLET at the tip.
        assertFalse(payload, payload.contains(hex(19_200_000L)))
    }

    // ---- Part A: detection on a post-1.8.7 database ----

    private val tip = 19_200_000L
    private val tipTs = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun advisor() = UpgradeRescanAdvisor(context, db, prefs).apply { installTimeMs = { null } }

    /** What the 1.8.7 reconcile plus a NEW_WALLET restart at the tip leave behind. */
    private suspend fun seed187(mode: SyncMode = SyncMode.NEW_WALLET) {
        wallet("w", "aa", mode, createdAt = tipTs - 90 * day)
        db.syncProgressDao().upsert(SyncProgressEntity("w", "TESTNET", tip - 50, tip, 1))
        tx("old", "w", 0, status = "UNVERIFIED")
    }

    @Test fun `affected wallet gets an offer below its registered start`() = runTest {
        seed187()
        val offer = advisor().offer("w", NetworkType.TESTNET, tip, tipTs)
        val expected = conservativeBlockAt(tip, tipTs, tipTs - 90 * day) - UPGRADE_RESCAN_MARGIN_BLOCKS
        assertEquals(expected, offer)
        assertEquals(expected, UpgradeRescanFlags(context).offered("w", "TESTNET"))
    }

    @Test fun `NEW_WALLET restarted at the tip with older unverified rows gets the banner`() = runTest {
        seed187()
        tx("older", "w", 0, status = "UNVERIFIED", cachedAt = tipTs - 30 * day)
        assertTrue(advisor().offer("w", NetworkType.TESTNET, tip, tipTs) != null)
    }

    @Test fun `completed full history rescan with a leftover unverified row gets no banner`() = runTest {
        seed187(SyncMode.FULL_HISTORY)
        // Synced from 0; the row is stale (e.g. no longer on chain), not hidden.
        assertNull(advisor().offer("w", NetworkType.TESTNET, tip, tipTs))
    }

    @Test fun `custom start that covered the rows gets no banner, one above them does`() = runTest {
        wallet("w", "aa", SyncMode.CUSTOM, createdAt = tipTs - 90 * day)
        db.syncProgressDao().upsert(SyncProgressEntity("w", "TESTNET", tip - 50, tip, 1))
        // Last written by 1.8.6 five days ago: its block is at most ~14,400 below the tip.
        tx("old", "w", 0, status = "UNVERIFIED", cachedAt = tipTs - 5 * day)
        heights["w"] = tip - 1_000_000L
        assertNull(advisor().offer("w", NetworkType.TESTNET, tip, tipTs))
        heights["w"] = tip - 100L
        assertTrue(advisor().offer("w", NetworkType.TESTNET, tip, tipTs) != null)
    }

    @Test fun `leftover rule per mode`() {
        assertFalse(leftoverBelowStart(SyncMode.FULL_HISTORY, null, listOf(1L), listOf(1L), tip, tipTs))
        assertTrue(leftoverBelowStart(SyncMode.NEW_WALLET, null, emptyList(), emptyList(), tip, tipTs))
        assertTrue(leftoverBelowStart(SyncMode.CUSTOM, 5_000L, emptyList(), listOf(4_000L), tip, tipTs))
        assertFalse(leftoverBelowStart(SyncMode.CUSTOM, 5_000L, emptyList(), listOf(6_000L), tip, tipTs))
        assertTrue(optimisticBlockAt(tip, tipTs, tipTs - day) >= conservativeBlockAt(tip, tipTs, tipTs - day))
    }

    @Test fun `fresh install is never offered a scan`() = runTest {
        wallet("w", "aa", SyncMode.NEW_WALLET, createdAt = tipTs - day)
        db.syncProgressDao().upsert(SyncProgressEntity("w", "TESTNET", tip - 50, tip, 1))
        tx("new", "w", tip - 10)
        assertNull(advisor().offer("w", NetworkType.TESTNET, tip, tipTs))
    }

    @Test fun `full history wallet is never offered a scan`() = runTest {
        seed187(SyncMode.FULL_HISTORY)
        assertNull(advisor().offer("w", NetworkType.TESTNET, tip, tipTs))
    }

    @Test fun `no offer while the scan is still catching up`() = runTest {
        seed187()
        db.syncProgressDao().upsert(SyncProgressEntity("w", "TESTNET", 18_000_000, 18_500_000, 1))
        assertNull(advisor().offer("w", NetworkType.TESTNET, tip, tipTs))
    }

    @Test fun `done flag and not now both hide the offer`() = runTest {
        seed187()
        val advisor = advisor()
        advisor.dismissForLaunch("w", NetworkType.TESTNET)
        assertNull(advisor.offer("w", NetworkType.TESTNET, tip, tipTs))
        // A new launch (new advisor) offers again until the flag is set.
        assertTrue(advisor().offer("w", NetworkType.TESTNET, tip, tipTs) != null)
        UpgradeRescanFlags(context).markDone("w", "TESTNET")
        assertNull(advisor().offer("w", NetworkType.TESTNET, tip, tipTs))
    }

    @Test fun `done flag is set by a covering resync only`() = runTest {
        seed187()
        val advisor = advisor()
        val offered = advisor.offer("w", NetworkType.TESTNET, tip, tipTs)!!
        advisor.recordResync("w", NetworkType.TESTNET, SyncMode.CUSTOM, offered + 1)
        assertFalse(advisor.isDone("w", NetworkType.TESTNET))
        advisor.recordResync("w", NetworkType.TESTNET, SyncMode.RECENT, null)
        assertFalse(advisor.isDone("w", NetworkType.TESTNET))
        advisor.recordResync("w", NetworkType.TESTNET, SyncMode.CUSTOM, offered)
        assertTrue(advisor.isDone("w", NetworkType.TESTNET))
        context.getSharedPreferences(UpgradeRescanFlags.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        advisor.recordResync("w", NetworkType.TESTNET, SyncMode.FULL_HISTORY, null)
        assertTrue(advisor.isDone("w", NetworkType.TESTNET))
    }
}
