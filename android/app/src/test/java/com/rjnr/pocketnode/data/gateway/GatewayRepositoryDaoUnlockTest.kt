package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.PendingDaoUnlockEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.JniTransactionWithStatus
import com.rjnr.pocketnode.data.gateway.models.JniTxStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * #529: what happens to a DAO position around its phase-2 unlock.
 *
 * The bug this pins: an unlock's output is a plain CKB cell, so nothing in
 * the live scan records that it spent the withdrawing cell. The cached
 * dao_cells row then fell into the #332 outside-window branch (a withdrawing
 * row carries the ORIGINAL deposit's block, always far behind the sync head),
 * so a claimed position kept rendering an Unlock button, kept the spinner
 * running and raised a spurious deep-rescan prompt.
 *
 * Only the light-client reads are faked; the Room side is a real in-memory
 * database, because the retirement IS a database write. Same scenarios as
 * ios/m3's DaoGatewayUnlockTest, driven through GatewayRepository's DAO entry
 * points, which is where this logic lives on main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [DaoUnlockShadowLightClientNative::class],
    instrumentedPackages = ["com.nervosnetwork.ckblightclient"],
)
class GatewayRepositoryDaoUnlockTest {

    private lateinit var db: AppDatabase
    private lateinit var daoSyncManager: DaoSyncManager
    private lateinit var depositReader: DaoDepositReader
    private lateinit var repository: GatewayRepository

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val walletId = "wallet-1"
    private val network = NetworkType.TESTNET
    private val script = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "11".repeat(20))
    private val withdrawingOutPoint = OutPoint("0x" + "ab".repeat(32), "0x0")
    private val unlockTxHash = "0x" + "cd".repeat(32)

    /** The script's sync head. Every fixture deposit predates it. */
    private val syncHead = 10_000L

    /**
     * What the faked chain says when an aged marker is checked. Default is
     * "no answer", the case that must never be read as a spend.
     */
    private var chainStatus: String?
        get() = DaoUnlockShadowLightClientNative.chainStatus
        set(value) { DaoUnlockShadowLightClientNative.chainStatus = value }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        daoSyncManager = DaoSyncManager(
            db.headerCacheDao(),
            db.daoCellDao(),
            db.pendingDaoWithdrawDao(),
            db.pendingDaoUnlockDao(),
            NoopLogger,
        )
        depositReader = mockk()

        // On main the repository reads the chain straight off the JNI object
        // (tip for the epoch, registered scripts for the sync head, one
        // transaction for an aged marker). MockK cannot stub a native method,
        // so a Robolectric shadow answers those reads instead.
        DaoUnlockShadowLightClientNative.tipHeader = json.encodeToString(tipHeader())
        DaoUnlockShadowLightClientNative.scripts = json.encodeToString(
            listOf(JniScriptStatus(script = script, blockNumber = "0x" + syncHead.toString(16)))
        )
        DaoUnlockShadowLightClientNative.chainStatus = null

        val walletPreferences = WalletPreferences(context, NoopLogger)
        walletPreferences.setActiveWalletId(walletId)
        // Keep the startup VACUUM off the in-memory database under test.
        walletPreferences.setLastVacuumAt(System.currentTimeMillis())

        val nodeLifecycle = mockk<NodeLifecycle>(relaxed = true)
        every { nodeLifecycle.currentNetwork } returns network

        val keyManager = mockk<KeyManager>(relaxed = true)
        coEvery { keyManager.hasWallet() } returns true
        every { keyManager.deriveWalletInfoFromEntity(any()) } returns
            WalletInfo("0xpub", script, "ckt1...", "ckb1...")

        repository = GatewayRepository(
            keyManager = keyManager,
            walletPreferences = walletPreferences,
            json = json,
            transactionBuilder = mockk(relaxed = true),
            cacheManager = mockk(relaxed = true),
            daoSyncManager = daoSyncManager,
            walletMigrationHelper = mockk(relaxed = true),
            walletDao = db.walletDao(),
            appDatabase = db,
            headerCacheDao = db.headerCacheDao(),
            syncProgressDao = db.syncProgressDao(),
            pendingBroadcastDao = db.pendingBroadcastDao(),
            broadcastClient = mockk(relaxed = true),
            syncCoordinator = mockk(relaxed = true),
            daoHeaderResolver = mockk(relaxed = true),
            daoDepositReader = depositReader,
            lightClient = LightClientReadOnly(json, NoopLogger),
            subAccountReconciler = mockk(relaxed = true),
            subAccountDiscovery = mockk(relaxed = true),
            syncServiceCommands = mockk(relaxed = true),
            nodeLifecycle = nodeLifecycle,
            syncPoller = mockk(relaxed = true),
            startupReconciler = mockk(relaxed = true),
            logger = NoopLogger,
        )

        runBlocking {
            db.walletDao().insert(
                WalletEntity(
                    walletId = walletId, name = walletId, type = KeyManager.WALLET_TYPE_MNEMONIC,
                    derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null, accountIndex = 0,
                    mainnetAddress = "ckb1...", testnetAddress = "ckt1...",
                    isActive = true, createdAt = 0L, lastActiveAt = 0L,
                )
            )
            // Sets the active wallet's lock script, which the DAO reads need.
            repository.initializeWallet().getOrThrow()
        }
    }

    private fun tipHeader() = JniHeaderView(
        hash = "0x" + "ee".repeat(32),
        number = "0x" + syncHead.toString(16),
        // epoch 200, index 0, length 1800
        epoch = "0x708000000000c8",
        timestamp = "0x18c8d0a7a00",
        parentHash = "0x" + "ef".repeat(32),
        transactionsRoot = "0x" + "e0".repeat(32),
        proposalsHash = "0x" + "e1".repeat(32),
        extraHash = "0x" + "e2".repeat(32),
        dao = "0x40b4d9a3ddc9e730",
        nonce = "0x0",
    )

    @After
    fun teardown() {
        DaoUnlockShadowLightClientNative.chainStatus = null
        db.close()
    }

    private fun committedOnChain() = "committed"

    private fun rejectedOnChain() = "rejected"

    private fun withdrawingDeposit(status: DaoCellStatus = DaoCellStatus.UNLOCKABLE) = DaoDeposit(
        outPoint = withdrawingOutPoint,
        capacity = 20_000_000_000L,
        status = status,
        // The ORIGINAL deposit's block: far behind the sync head, which is
        // exactly what used to push the row into the outside-window branch.
        depositBlockNumber = 500L,
        depositBlockHash = "0x" + "aa".repeat(32),
        withdrawBlockNumber = 900L,
        withdrawBlockHash = "0x" + "bb".repeat(32),
    )

    /** Seed dao_cells with the withdrawing row, the way a live scan would. */
    private suspend fun seedCachedWithdrawingRow(status: DaoCellStatus = DaoCellStatus.UNLOCKABLE) {
        coEvery { depositReader.list(any(), any(), any()) } returns listOf(withdrawingDeposit(status))
        repository.getDaoDeposits().getOrThrow()
    }

    private suspend fun markUnlockInFlight(
        txStatus: String,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        db.pendingDaoUnlockDao().upsert(
            PendingDaoUnlockEntity(
                withdrawingTxHash = withdrawingOutPoint.txHash,
                withdrawingIndex = withdrawingOutPoint.index,
                unlockTxHash = unlockTxHash,
                walletId = walletId,
                network = network.name,
                createdAt = createdAt,
            )
        )
        db.transactionDao().insert(unlockTxRow(txStatus))
    }

    private fun unlockTxRow(txStatus: String) = TransactionEntity(
        txHash = unlockTxHash,
        blockNumber = "0x0",
        blockHash = "",
        timestamp = System.currentTimeMillis(),
        balanceChange = "0x0",
        direction = "dao_unlock",
        fee = "0x0",
        confirmations = 0,
        blockTimestampHex = null,
        network = network.name,
        status = txStatus,
        isLocal = true,
        cachedAt = System.currentTimeMillis(),
        walletId = walletId,
    )

    @Test
    fun `cached row whose unlock committed and whose cell is gone is retired`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "CONFIRMED")

        // The unlock committed: the light client no longer returns the cell.
        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertTrue("retired deposit must not be in the list: $deposits", deposits.isEmpty())
        assertEquals(
            DaoCellStatus.COMPLETED.name,
            daoSyncManager.getByOutPoint(withdrawingOutPoint.txHash, withdrawingOutPoint.index)?.status,
        )
        assertTrue(
            "the resolved marker must be deleted",
            db.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network.name).isEmpty(),
        )
    }

    @Test
    fun `an unscored unlock holds the position confirming instead of retiring it`() = runTest {
        // Absence of the cell is NOT enough on its own for phase 2: an
        // unlock's output is an ordinary cell, so for a deposit outside the
        // sync window "absent" is indistinguishable from "never visible".
        // Retiring here would delete the marker, and a later FAILED verdict
        // could then never hand the position back.
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "PENDING")

        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals(DaoCellStatus.UNLOCKING, deposits.single().status)
        assertFalse(
            "a confirming position must not raise the deep-rescan banner",
            deposits.single().outsideSyncWindow,
        )
        assertEquals(
            "nothing is retired until the transaction is terminal",
            DaoCellStatus.UNLOCKABLE.name,
            daoSyncManager.getByOutPoint(withdrawingOutPoint.txHash, withdrawingOutPoint.index)?.status,
        )
        assertFalse(
            "the marker must survive to carry a later verdict",
            db.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network.name).isEmpty(),
        )
    }

    @Test
    fun `an unlock that later fails hands back a cell the light client cannot see`() = runTest {
        // The dangerous case: out-of-window cell, so absence proves nothing.
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "PENDING")
        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        repository.getDaoDeposits().getOrThrow()

        // The watchdog finally scores the transaction FAILED.
        db.transactionDao().updateStatusOnly(unlockTxHash, "FAILED")
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals(
            "the position must come back, not stay hidden as COMPLETED",
            DaoCellStatus.UNLOCKABLE.name,
            daoSyncManager.getByOutPoint(withdrawingOutPoint.txHash, withdrawingOutPoint.index)?.status,
        )
        assertTrue(db.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network.name).isEmpty())
        assertEquals(1, deposits.size)
    }

    @Test
    fun `an aged marker is retired only when the chain says the unlock committed`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(
            txStatus = "PENDING",
            createdAt = System.currentTimeMillis() - DAO_UNLOCK_MARKER_GRACE_MS - 1,
        )
        chainStatus = committedOnChain()

        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertTrue("a chain-confirmed unlock retires the row: $deposits", deposits.isEmpty())
        assertEquals(
            DaoCellStatus.COMPLETED.name,
            daoSyncManager.getByOutPoint(withdrawingOutPoint.txHash, withdrawingOutPoint.index)?.status,
        )
        assertTrue(db.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network.name).isEmpty())
    }

    @Test
    fun `an aged marker the node cannot resolve hands the position back, never retires it`() = runTest {
        // The hazard: an out-of-window cell is ALWAYS absent from the scan, and
        // its transaction can stay unscored locally for longer than the grace
        // period (a swallowed pending-row insert, or the app simply not running
        // while the watchdog would have checked). Retiring on that would hide
        // funds with no way back.
        seedCachedWithdrawingRow()
        markUnlockInFlight(
            txStatus = "PENDING",
            createdAt = System.currentTimeMillis() - DAO_UNLOCK_MARKER_GRACE_MS - 1,
        )
        chainStatus = null // node unavailable

        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals(
            "an unresolved unlock must never retire the position",
            DaoCellStatus.UNLOCKABLE.name,
            daoSyncManager.getByOutPoint(withdrawingOutPoint.txHash, withdrawingOutPoint.index)?.status,
        )
        assertEquals(1, deposits.size)
        assertTrue(db.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network.name).isEmpty())
    }

    @Test
    fun `an aged marker the chain rejected is written off`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(
            txStatus = "PENDING",
            createdAt = System.currentTimeMillis() - DAO_UNLOCK_MARKER_GRACE_MS - 1,
        )
        chainStatus = rejectedOnChain()

        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals(DaoCellStatus.UNLOCKABLE, deposits.single().status)
        assertTrue(
            "a written-off marker must not keep the card spinning",
            db.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network.name).isEmpty(),
        )
    }

    @Test
    fun `a young marker is not resolved from the chain at all`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "PENDING")
        chainStatus = committedOnChain() // would retire it if consulted

        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals(DaoCellStatus.UNLOCKING, deposits.single().status)
        assertFalse(db.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network.name).isEmpty())
    }

    @Test
    fun `a retired row stays retired across a relaunch`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "CONFIRMED")
        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        repository.getDaoDeposits().getOrThrow()

        // Second cold load: no marker left, and the row must not resurface.
        val deposits = repository.getDaoDeposits().getOrThrow()
        assertTrue("stale row resurfaced after retirement: $deposits", deposits.isEmpty())
    }

    @Test
    fun `a cached row with no unlock on record is still shown as outside-window`() = runTest {
        // #332 / #404 must not regress: a deposit older than the sync window
        // is invisible to the light client but is NOT spent.
        seedCachedWithdrawingRow(status = DaoCellStatus.DEPOSITED)

        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals(1, deposits.size)
        assertTrue(deposits.single().outsideSyncWindow)
        assertEquals(DaoCellStatus.DEPOSITED, deposits.single().status)
        assertEquals(
            DaoCellStatus.DEPOSITED.name,
            daoSyncManager.getByOutPoint(withdrawingOutPoint.txHash, withdrawingOutPoint.index)?.status,
        )
    }

    @Test
    fun `an in-flight unlock paints the still-live cell UNLOCKING`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "PENDING")

        // The spend is not indexed yet, so the cell still scans UNLOCKABLE.
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals(DaoCellStatus.UNLOCKING, deposits.single().status)
        assertFalse(
            "an unresolved marker must survive",
            db.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network.name).isEmpty(),
        )
    }

    @Test
    fun `a failed unlock hands the cell back as UNLOCKABLE`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "FAILED")

        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals(DaoCellStatus.UNLOCKABLE, deposits.single().status)
        assertTrue(
            "a failed marker must be cleared so the user can retry",
            db.pendingDaoUnlockDao().getByWalletAndNetwork(walletId, network.name).isEmpty(),
        )
        assertNotNull(
            daoSyncManager.getByOutPoint(withdrawingOutPoint.txHash, withdrawingOutPoint.index),
        )
    }

    @Test
    fun `unlocking an already-claimed position fails with a terminal message`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "CONFIRMED")
        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        repository.getDaoDeposits().getOrThrow()

        val result = repository.unlockDao(withdrawingOutPoint, privateKey = ByteArray(32))

        assertTrue(result.isFailure)
        assertEquals(GatewayRepository.ALREADY_UNLOCKED_MESSAGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun `an already-claimed position is refused before the auth prompt`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "CONFIRMED")
        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        repository.getDaoDeposits().getOrThrow()

        val result = repository.unlockPreflight(withdrawingOutPoint)

        assertTrue(result.isFailure)
        assertEquals(GatewayRepository.ALREADY_UNLOCKED_MESSAGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun `preflight refuses a second unlock while the first is in flight`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "PENDING")

        val result = repository.unlockPreflight(withdrawingOutPoint)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty()
                .startsWith(GatewayRepository.ALREADY_UNLOCKING_MESSAGE)
        )
    }

    @Test
    fun `preflight lets a genuinely unlockable position through`() = runTest {
        seedCachedWithdrawingRow()

        assertTrue(repository.unlockPreflight(withdrawingOutPoint).isSuccess)
    }

    @Test
    fun `an already-claimed position is recognised through a differently spelled index`() = runTest {
        // The row was written with index "0x0"; the caller hands back a padded
        // "0x00" for the same outpoint. A raw string compare would answer "not
        // claimed" and let the user build a doomed transaction.
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "CONFIRMED")
        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        repository.getDaoDeposits().getOrThrow()

        val paddedIndex = OutPoint(withdrawingOutPoint.txHash, "0x00")
        val result = repository.unlockDao(paddedIndex, privateKey = ByteArray(32))

        assertTrue(result.isFailure)
        assertEquals(GatewayRepository.ALREADY_UNLOCKED_MESSAGE, result.exceptionOrNull()?.message)
        assertEquals(
            GatewayRepository.ALREADY_UNLOCKED_MESSAGE,
            repository.unlockPreflight(paddedIndex).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `a marker spelled differently still retires the cached row it names`() = runTest {
        // dao_cells is keyed by the exact strings the row was written with, so
        // a status write naming the marker's own spelling would update zero
        // rows while the marker was deleted, losing the verdict.
        seedCachedWithdrawingRow()
        db.pendingDaoUnlockDao().upsert(
            PendingDaoUnlockEntity(
                withdrawingTxHash = withdrawingOutPoint.txHash,
                withdrawingIndex = "0x00", // cached row is stored as "0x0"
                unlockTxHash = unlockTxHash,
                walletId = walletId,
                network = network.name,
                createdAt = System.currentTimeMillis(),
            )
        )
        db.transactionDao().insert(unlockTxRow("CONFIRMED"))

        coEvery { depositReader.list(any(), any(), any()) } returns emptyList()
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals(
            DaoCellStatus.COMPLETED.name,
            daoSyncManager.getByOutPoint(withdrawingOutPoint.txHash, "0x0")?.status,
        )
        assertTrue("the retired row must not render: $deposits", deposits.isEmpty())
    }

    @Test
    fun `a live cell spelled differently from its cached row is not duplicated`() = runTest {
        seedCachedWithdrawingRow()

        // Same cell, padded index this time: a raw key compare would call it
        // absent, append the cached row and render two cards for one position.
        coEvery { depositReader.list(any(), any(), any()) } returns listOf(
            withdrawingDeposit().copy(outPoint = OutPoint(withdrawingOutPoint.txHash, "0x00"))
        )
        val deposits = repository.getDaoDeposits().getOrThrow()

        assertEquals("one cell must render one card: $deposits", 1, deposits.size)
    }

    @Test
    fun `the already-unlocking refusal names the transaction to check`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "PENDING")

        val message = repository.unlockPreflight(withdrawingOutPoint).exceptionOrNull()?.message

        assertNotNull(message)
        assertTrue(
            "expected the refusal to carry the unlock hash, got: $message",
            message!!.startsWith(GatewayRepository.ALREADY_UNLOCKING_MESSAGE) &&
                message.contains(unlockTxHash.take(10)) &&
                message.contains(unlockTxHash.takeLast(8)),
        )
    }

    @Test
    fun `unlocking a position whose unlock is still in flight fails fast`() = runTest {
        seedCachedWithdrawingRow()
        markUnlockInFlight(txStatus = "PENDING")

        val result = repository.unlockDao(withdrawingOutPoint, privateKey = ByteArray(32))

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty()
                .startsWith(GatewayRepository.ALREADY_UNLOCKING_MESSAGE)
        )
        assertNull(
            "no second transaction may be built",
            result.getOrNull(),
        )
    }
}

/**
 * Stands in for the light client's JNI surface in [GatewayRepositoryDaoUnlockTest].
 * Every native not named here keeps Robolectric's default (null / 0).
 */
@Implements(LightClientNative::class)
class DaoUnlockShadowLightClientNative {

    @Implementation
    fun nativeGetTipHeader(): String? = tipHeader

    @Implementation
    fun nativeGetHeader(@Suppress("UNUSED_PARAMETER") hash: String): String? = tipHeader

    @Implementation
    fun nativeGetScripts(): String? = scripts

    /**
     * The unlock transaction as the chain reports it, or null, which is the
     * bridge's own answer when the node does not know the transaction.
     */
    @Implementation
    fun nativeGetTransaction(@Suppress("UNUSED_PARAMETER") hash: String): String? =
        chainStatus?.let {
            Json.encodeToString(
                JniTransactionWithStatus(
                    txStatus = JniTxStatus(
                        status = it,
                        blockHash = if (it == "committed") "0x" + "ee".repeat(32) else null,
                    )
                )
            )
        }

    companion object {
        var tipHeader: String? = null
        var scripts: String? = null
        var chainStatus: String? = null
    }
}
