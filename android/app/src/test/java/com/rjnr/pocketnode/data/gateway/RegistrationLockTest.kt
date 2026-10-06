package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.SyncStrategy
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.AccountStatusResponse
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.migration.WalletMigrationHelper
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * #539: the rest of script registration runs under the registration lock.
 *
 * Real [GatewayRepository] + real [SyncCoordinator] over Room in-memory and
 * real [WalletPreferences]; only the JNI surface ([LightClientBridge],
 * [LightClientReadOnly]) and unrelated collaborators are faked. Every race
 * is forced with hooks inside the fake light client and deferreds, never
 * with sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RegistrationLockTest {

    private lateinit var db: AppDatabase
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var bridge: FakeBridge
    private lateinit var nodeLifecycle: NodeLifecycle
    private lateinit var lightClient: LightClientReadOnly
    private lateinit var keyManager: KeyManager
    private lateinit var coordinator: SyncCoordinator
    private lateinit var repository: GatewayRepository

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val network = NetworkType.TESTNET
    private val tip = 20_000_000L

    /** What the fake light client reports from getScripts (null = nothing registered). */
    @Volatile
    private var reportedScripts: String? = null

    private class FakeBridge(private val tipJson: String) : LightClientBridge {
        val setScriptsCalls: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())
        /** Payloads of the sets the light client accepted. */
        val landed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** Results for the next setScripts calls, then true. */
        val results = ArrayDeque<Boolean>()
        var onTipRead: suspend () -> Unit = {}
        var onSetScripts: suspend () -> Unit = {}

        override suspend fun setScripts(scriptsJson: String, command: Int): Boolean {
            onSetScripts()
            val ok = synchronized(results) { results.removeFirstOrNull() } ?: true
            setScriptsCalls += scriptsJson to command
            if (ok) landed += scriptsJson
            return ok
        }

        override suspend fun getTipHeaderRaw(): String {
            onTipRead()
            return tipJson
        }

        override suspend fun getScriptsRaw(): String? = null
    }

    private class SignalLogger : Logger {
        @Volatile
        var onDebug: (String) -> Unit = {}
        override fun d(tag: String, msg: String) = onDebug(msg)
        override fun i(tag: String, msg: String) {}
        override fun w(tag: String, msg: String, t: Throwable?) {}
        override fun e(tag: String, msg: String, t: Throwable?) {}
    }

    private val coordinatorLogger = SignalLogger()

    private fun script(byte: String) = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + byte.repeat(20),
    )

    private val activeScript = script("aa")
    private val otherScript = script("bb")
    private val thirdScript = script("cc")

    private fun tipJson(n: Long) =
        """{"hash":"0x00","number":"0x${n.toString(16)}","epoch":"0x0","timestamp":"0x0",""" +
            """"parent_hash":"0x00","transactions_root":"0x00","proposals_hash":"0x00",""" +
            """"extra_hash":"0x00","dao":"0x00","nonce":"0x0"}"""

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletPreferences = WalletPreferences(ctx, NoopLogger)
        walletPreferences.setActiveWalletId(ACTIVE)
        bridge = FakeBridge(tipJson(tip))

        nodeLifecycle = mockk(relaxed = true)
        every { nodeLifecycle.currentNetwork } returns network
        coEvery { nodeLifecycle.awaitNodeReady() } returns true

        val realKeyManager = KeyManager(ctx, MnemonicManager(), NoopLogger)
        keyManager = mockk(relaxed = true)
        coEvery { keyManager.hasWallet() } returns true
        every { keyManager.deriveWalletInfoFromEntity(any()) } answers {
            realKeyManager.deriveWalletInfoFromEntity(firstArg())
        }
        lightClient = mockk(relaxed = true)
        coEvery { lightClient.getTipHeader() } returns tipJson(tip)
        coEvery { lightClient.getScripts() } answers { reportedScripts }

        runBlocking {
            seedWallet(ACTIVE, activeScript, lastActiveAt = 2L)
            seedWallet(OTHER, otherScript, lastActiveAt = 1L)
            seedProgress(ACTIVE, ACTIVE_PROGRESS)
            seedProgress(OTHER, OTHER_PROGRESS)
        }
        walletPreferences.setSyncMode(SyncMode.RECENT, walletId = ACTIVE)
        walletPreferences.setSyncMode(SyncMode.RECENT, walletId = OTHER)
        walletPreferences.setSyncMode(SyncMode.RECENT, walletId = THIRD)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Builds the coordinator and the repository and waits for the
     * repository's startup sequence to reach node init, so its own
     * active-wallet assignment never races the test.
     */
    private fun build(
        coordinatorDao: SyncProgressDao = db.syncProgressDao(),
        repositoryDao: SyncProgressDao = db.syncProgressDao(),
        walletMigrationHelper: WalletMigrationHelper = mockk(relaxed = true),
        awaitStartup: Boolean = true,
    ): CompletableDeferred<Unit> {
        coordinator = SyncCoordinator(
            walletDao = db.walletDao(),
            syncProgressDao = coordinatorDao,
            syncPreferences = walletPreferences,
            keyManager = KeyManager(ApplicationProvider.getApplicationContext(), MnemonicManager(), NoopLogger),
            json = json,
            lightClient = bridge,
            subAccountCandidateDao = db.subAccountCandidateDao(),
            transactionDao = db.transactionDao(),
            logger = coordinatorLogger,
        )
        val startupReachedNode = CompletableDeferred<Unit>()
        coEvery { nodeLifecycle.initializeNode(any(), any()) } answers { startupReachedNode.complete(Unit) }
        repository = testGatewayRepository(
            db = db,
            walletPreferences = walletPreferences,
            nodeLifecycle = nodeLifecycle,
            syncCoordinator = coordinator,
            keyManager = keyManager,
            lightClient = lightClient,
            json = json,
            syncProgressDao = repositoryDao,
            walletMigrationHelper = walletMigrationHelper,
        )
        runBlocking {
            if (awaitStartup) withTimeout(10_000) { startupReachedNode.await() }
            repository.initializeWallet().getOrThrow()
        }
        return startupReachedNode
    }

    private suspend fun seedWallet(id: String, script: Script, lastActiveAt: Long) {
        db.walletDao().insert(
            WalletEntity(
                walletId = id, name = id, type = KeyManager.WALLET_TYPE_MNEMONIC,
                derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null, accountIndex = 0,
                mainnetAddress = AddressUtils.encode(script, NetworkType.MAINNET),
                testnetAddress = AddressUtils.encode(script, NetworkType.TESTNET),
                isActive = id == ACTIVE, createdAt = 0L, lastActiveAt = lastActiveAt,
            )
        )
    }

    private suspend fun seedProgress(id: String, block: Long) {
        db.syncProgressDao().upsert(
            SyncProgressEntity(
                walletId = id, network = network.name,
                lightStartBlockNumber = block, localSavedBlockNumber = block, updatedAt = 0L,
            )
        )
    }

    private fun statusesJson(vararg pairs: Pair<Script, Long>): String = json.encodeToString(
        pairs.map { (s, block) -> JniScriptStatus(script = s, scriptType = "lock", blockNumber = "0x${block.toString(16)}") }
    )

    private fun argsOf(payload: String): Set<String> =
        json.decodeFromString<List<JniScriptStatus>>(payload).map { it.script.args }.toSet()

    // ------------------------------------------------------------------
    // S-A: the sync poll vs a resync
    // ------------------------------------------------------------------

    /**
     * The poll read the light client's scripts before the resync's set
     * landed (old block) and wrote that block over the progress the resync
     * had just reset, quietly undoing it on the next registration. The
     * poll tick here runs while the resync holds the lock (inside its set):
     * it must neither block nor write.
     */
    @Test
    fun `a poll tick racing a resync does not write the old block over the reset progress`() = runBlocking {
        build()
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        // A first registration, so the poll knows which wallet each script is.
        repository.registerAccountWithStrategy(savePreference = false).getOrThrow()
        // The light client still reports the pre-resync block for both wallets.
        reportedScripts = statusesJson(activeScript to ACTIVE_PROGRESS + 10, otherScript to OTHER_PROGRESS + 10)
        var poll: Result<AccountStatusResponse>? = null
        bridge.onSetScripts = {
            bridge.onSetScripts = {}
            poll = repository.getAccountStatus()
        }

        repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT).getOrThrow()

        assertTrue("the poll must not block or fail: ${poll?.exceptionOrNull()}", poll!!.isSuccess)
        assertEquals("the resync's reset stands", 0L, repository.getWalletSyncBlock(ACTIVE))
        assertEquals("nothing saved for the other wallet either", OTHER_PROGRESS, repository.getWalletSyncBlock(OTHER))

        // With the lock free again the next tick saves progress as usual.
        reportedScripts = statusesJson(activeScript to CUSTOM_HEIGHT + 5, otherScript to OTHER_PROGRESS + 10)
        repository.getAccountStatus().getOrThrow()
        assertEquals(CUSTOM_HEIGHT + 5, repository.getWalletSyncBlock(ACTIVE))
        assertEquals(OTHER_PROGRESS + 10, repository.getWalletSyncBlock(OTHER))
    }

    // ------------------------------------------------------------------
    // Rollback: under the lock, and never after a landed set
    // ------------------------------------------------------------------

    private fun assertRollbackRunsUnderLock(strategy: SyncStrategy) = runBlocking {
        val rollbackLocked = Collections.synchronizedList(mutableListOf<Boolean>())
        val real = db.syncProgressDao()
        val observing = object : SyncProgressDao by real {
            override suspend fun updateLocalSaved(walletId: String, network: String, block: Long, ts: Long): Int {
                if (walletId == ACTIVE && block == ACTIVE_PROGRESS) rollbackLocked += coordinator.isRegistrationLocked
                return real.updateLocalSaved(walletId, network, block, ts)
            }
        }
        build(repositoryDao = observing)
        walletPreferences.setSyncStrategy(strategy)
        bridge.results.addLast(false)

        assertTrue(repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT).isFailure)

        assertEquals("rolled back exactly once, under the registration lock", listOf(true), rollbackLocked.toList())
        assertEquals(ACTIVE_PROGRESS, repository.getWalletSyncBlock(ACTIVE))
        assertEquals(SyncMode.RECENT, walletPreferences.getSyncModeOrNull(walletId = ACTIVE))
    }

    @Test
    fun `a refused ALL_WALLETS resync rolls back under the registration lock`() =
        assertRollbackRunsUnderLock(SyncStrategy.ALL_WALLETS)

    @Test
    fun `a refused ACTIVE_ONLY resync rolls back under the registration lock`() =
        assertRollbackRunsUnderLock(SyncStrategy.ACTIVE_ONLY)

    /** The light client took the set; the bookkeeping write right after it fails. */
    private fun failingAfterLandingDao(): SyncProgressDao {
        val real = db.syncProgressDao()
        return object : SyncProgressDao by real {
            override suspend fun updateLightStart(walletId: String, network: String, lightStart: Long, ts: Long): Int =
                throw IllegalStateException("disk full")
        }
    }

    @Test
    fun `an ALL_WALLETS resync whose set landed is not rolled back when a later write fails`() = runBlocking {
        build(coordinatorDao = failingAfterLandingDao())
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)

        val result = repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT)

        assertEquals("disk full", result.exceptionOrNull()?.message)
        assertEquals("the set landed", 1, bridge.landed.size)
        // The light client runs the new start, so the prefs and progress keep it.
        assertEquals(SyncMode.CUSTOM, walletPreferences.getSyncModeOrNull(walletId = ACTIVE))
        assertEquals(CUSTOM_HEIGHT, walletPreferences.getCustomBlockHeight(walletId = ACTIVE))
        assertEquals(0L, repository.getWalletSyncBlock(ACTIVE))
    }

    @Test
    fun `an ACTIVE_ONLY resync whose set landed is not rolled back when a later write fails`() = runBlocking {
        build(coordinatorDao = failingAfterLandingDao())
        walletPreferences.setSyncStrategy(SyncStrategy.ACTIVE_ONLY)

        val result = repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT)

        assertEquals("disk full", result.exceptionOrNull()?.message)
        assertEquals("the set landed", 1, bridge.landed.size)
        assertEquals("the reset progress stays reset", 0L, repository.getWalletSyncBlock(ACTIVE))
    }

    // ------------------------------------------------------------------
    // Inputs computed under the lock
    // ------------------------------------------------------------------

    /**
     * R2 queues behind R1 (which holds the lock inside its set). A wallet
     * added while R2 waits must be in R2's set: R2 computes it only once it
     * holds the lock.
     */
    @Test
    fun `a registration queued behind another recomputes its set when it gets the lock`() = runBlocking {
        build()
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        val r1InSet = CompletableDeferred<Unit>()
        val releaseR1 = CompletableDeferred<Unit>()
        val r2AtLock = CompletableDeferred<Unit>()
        var registeringLogs = 0
        coordinatorLogger.onDebug = { msg ->
            // "Registering ..." is each registration's last step before the lock.
            if (msg.startsWith("Registering") && ++registeringLogs == 2) r2AtLock.complete(Unit)
        }
        bridge.onSetScripts = {
            bridge.onSetScripts = {}
            r1InSet.complete(Unit)
            releaseR1.await()
        }

        val r1 = launch(Dispatchers.IO) { repository.registerAccountWithStrategy(savePreference = false).getOrThrow() }
        withTimeout(10_000) { r1InSet.await() }
        val r2 = launch(Dispatchers.IO) { repository.registerAccountWithStrategy(savePreference = false).getOrThrow() }
        withTimeout(10_000) { r2AtLock.await() }
        // Added while R2 waits for the lock.
        seedWallet(THIRD, thirdScript, lastActiveAt = 3L)
        releaseR1.complete(Unit)
        r1.join()
        r2.join()

        assertEquals(2, bridge.landed.size)
        assertTrue("R1 ran before the wallet existed", thirdScript.args !in argsOf(bridge.landed[0]))
        assertTrue("R2 must see the wallet added while it queued", thirdScript.args in argsOf(bridge.landed[1]))
    }

    /** The bulk sync_progress read is dead work when the caller supplies the progress. */
    @Test
    fun `applyBalancedFilter skips the bulk progress read when progressOf is supplied`() = runBlocking {
        val dao = mockk<SyncProgressDao>(relaxed = true)
        val c = SyncCoordinator(
            walletDao = db.walletDao(),
            syncProgressDao = dao,
            syncPreferences = walletPreferences,
            keyManager = keyManager,
            json = json,
            lightClient = bridge,
            subAccountCandidateDao = db.subAccountCandidateDao(),
            transactionDao = db.transactionDao(),
            logger = NoopLogger,
        )
        val wallets = db.walletDao().getAll()

        val kept = c.applyBalancedFilter(wallets, ACTIVE, network, progressOf = { id ->
            if (id == ACTIVE) ACTIVE_PROGRESS else 1_000L
        })

        coVerify(exactly = 0) { dao.getAllForNetwork(any()) }
        assertEquals("OTHER lags far beyond the threshold", listOf(ACTIVE), kept.map { it.walletId })

        c.applyBalancedFilter(wallets, ACTIVE, network)
        coVerify(exactly = 1) { dao.getAllForNetwork(network.name) }
    }

    // ------------------------------------------------------------------
    // The active wallet always ends up registered
    // ------------------------------------------------------------------

    /**
     * Switch to B; while B's registration waits for the tip, switch to C,
     * whose own set the light client refuses. B's registration is
     * superseded. The wallet on screen (C) must still end up registered.
     */
    @Test
    fun `a superseded switch whose registration fails still leaves the active wallet registered`() = runBlocking {
        build()
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        seedWallet(THIRD, thirdScript, lastActiveAt = 3L)
        val b = db.walletDao().getById(OTHER)!!
        val c = db.walletDao().getById(THIRD)!!
        bridge.onTipRead = {
            bridge.onTipRead = {}
            synchronized(bridge.results) { bridge.results.addLast(false) }
            runCatching { repository.onActiveWalletChanged(c) }
        }

        runCatching { repository.onActiveWalletChanged(b) }

        assertEquals(THIRD, coordinator.registeredActiveWalletId)
        assertTrue("C's set landed", thirdScript.args in argsOf(bridge.landed.last()))
        assertTrue(repository.isRegistered.value)
    }

    /** A switch whose own set is refused is retried once for the active wallet. */
    @Test
    fun `a switch whose registration is refused is retried for the active wallet`() = runBlocking {
        build()
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        val b = db.walletDao().getById(OTHER)!!
        bridge.results.addLast(false)

        runCatching { repository.onActiveWalletChanged(b) }

        assertEquals("refused, then the retry", 2, bridge.setScriptsCalls.size)
        assertEquals(1, bridge.landed.size)
        assertEquals(OTHER, coordinator.registeredActiveWalletId)
    }

    /** ACTIVE_ONLY: same guarantee through the single-wallet path. */
    @Test
    fun `an ACTIVE_ONLY switch whose registration is refused is retried for the active wallet`() = runBlocking {
        build()
        walletPreferences.setSyncStrategy(SyncStrategy.ACTIVE_ONLY)
        val b = db.walletDao().getById(OTHER)!!
        bridge.results.addLast(false)

        repository.onActiveWalletChanged(b)

        assertEquals(2, bridge.setScriptsCalls.size)
        assertEquals(setOf(otherScript.args), argsOf(bridge.landed.single()))
        assertEquals(OTHER, coordinator.registeredActiveWalletId)
    }

    @Test
    fun `resolveActiveWalletType reassigning the active wallet triggers exactly one registration`() = runBlocking {
        build()
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        repository.registerAccountWithStrategy(savePreference = false).getOrThrow()
        assertEquals(ACTIVE, coordinator.registeredActiveWalletId)
        // Another component flipped the active wallet in Room without a switch.
        db.walletDao().deactivateAll()
        db.walletDao().activate(OTHER)

        repository.needsMnemonicBackup()
        val job = assertNotNullJob()
        job.join()

        assertEquals(2, bridge.landed.size)
        assertEquals(OTHER, coordinator.registeredActiveWalletId)

        // Resolving again with nothing changed registers nothing more.
        repository.needsMnemonicBackup()
        assertSame(job, repository.reassignRegistration)
        assertEquals(2, bridge.landed.size)
    }

    @Test
    fun `startup reassigning the active wallet after a set landed triggers exactly one registration`() = runBlocking {
        val migrationGate = CompletableDeferred<Unit>()
        val migration = mockk<WalletMigrationHelper>(relaxed = true)
        coEvery { migration.migrateIfNeeded() } coAnswers { migrationGate.await() }
        val startupReachedNode = build(walletMigrationHelper = migration, awaitStartup = false)
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        // A set lands for ACTIVE while startup is still migrating.
        repository.registerAccountWithStrategy(savePreference = false).getOrThrow()
        assertEquals(ACTIVE, coordinator.registeredActiveWalletId)
        // The migration ends up naming another active wallet.
        walletPreferences.setActiveWalletId(OTHER)

        migrationGate.complete(Unit)
        withTimeout(10_000) { startupReachedNode.await() }
        assertNotNullJob().join()

        assertEquals(2, bridge.landed.size)
        assertEquals(OTHER, coordinator.registeredActiveWalletId)
        assertTrue(otherScript.args in argsOf(bridge.landed.last()))
    }

    @Test
    fun `startup assigning the same active wallet triggers no registration`() = runBlocking {
        build()
        assertEquals(null, repository.reassignRegistration)
        assertTrue(bridge.setScriptsCalls.isEmpty())
    }

    private fun assertNotNullJob(): kotlinx.coroutines.Job {
        val job = repository.reassignRegistration
        assertNotNull("the reassignment must launch a registration", job)
        return job!!
    }

    private companion object {
        const val ACTIVE = "wallet-active"
        const val OTHER = "wallet-other"
        const val THIRD = "wallet-third"
        const val ACTIVE_PROGRESS = 19_500_000L
        const val OTHER_PROGRESS = 19_000_000L
        const val CUSTOM_HEIGHT = 5_000_000L
    }
}
