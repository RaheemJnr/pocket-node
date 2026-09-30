package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.SyncStrategy
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.storage.RoomSubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.RoomSyncProgressStore
import com.rjnr.pocketnode.data.storage.RoomTransactionStore
import com.rjnr.pocketnode.data.storage.RoomWalletRegistry
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #431 (Codex P1 on PR #532): `resyncAccount` used the single-wallet
 * `registerAccount`, whose CMD_SET_SCRIPTS_ALL replaced the whole registered
 * set with only the active wallet's script. Under ALL_WALLETS / BALANCED
 * every other wallet stopped syncing on each sync-mode change.
 *
 * Real [GatewayRepository] + real [SyncCoordinator] (Room in-memory,
 * real [WalletPreferences]); only the light-client surface ([LightClientApi])
 * and the unrelated collaborators are faked.
 *
 * ios/m3 port: the coordinator lives in the shared core behind
 * [LightClientApi], whose calls are blocking, so the fake runs its suspend
 * hooks under runBlocking. The coordinator runs on Dispatchers.IO, as
 * SharedModule wires it on Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GatewayRepositoryResyncStrategyTest {

    private lateinit var db: AppDatabase
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var bridge: FakeBridge
    private lateinit var nodeLifecycle: NodeLifecycle
    private lateinit var repository: GatewayRepository

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val network = NetworkType.TESTNET
    private val tip = 20_000_000L

    private class FakeBridge(
        private val tipJson: String,
    ) : LightClientApi by mockk<LightClientApi>(relaxed = true) {
        val setScriptsCalls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        /** Runs while the coordinator waits for the tip, i.e. mid-registration. */
        @Volatile var onTipRead: suspend () -> Unit = {}
        @Volatile var setScriptsReturn = true
        /** Runs inside setScripts before the call is recorded (i.e. takes effect). */
        @Volatile var onSetScripts: suspend () -> Unit = {}

        override fun setScripts(scriptsJson: String, command: Int): Boolean {
            runBlocking { onSetScripts() }
            setScriptsCalls += scriptsJson to command
            return setScriptsReturn
        }

        override fun getTipHeader(): String {
            runBlocking { onTipRead() }
            return tipJson
        }

        override fun getScripts(): String? = null
    }

    /** Lets a test observe the coordinator reaching "Registering N scripts" (just before the lock). */
    private class SignalLogger : com.rjnr.pocketnode.core.log.Logger {
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

        val coordinator = newCoordinator(coordinatorLogger)

        nodeLifecycle = mockk(relaxed = true)
        every { nodeLifecycle.currentNetwork } returns network
        coEvery { nodeLifecycle.awaitNodeReady() } returns true

        // Tip and script reads go through LightClientReadOnly, so the
        // single-wallet registerAccount path also reaches the fake bridge's
        // setScripts: were resyncAccount to regress to it, these tests fail
        // on the registered wallet set, not on a missing native library.
        val realKeyManager = KeyManager(ctx, MnemonicManager(), NoopLogger)
        val keyManager = mockk<KeyManager>(relaxed = true)
        coEvery { keyManager.hasWallet() } returns true
        every { keyManager.deriveWalletInfoFromEntity(any()) } answers {
            realKeyManager.deriveWalletInfoFromEntity(firstArg())
        }
        val lightClient = mockk<LightClientReadOnly>(relaxed = true)
        coEvery { lightClient.getTipHeader() } returns tipJson(tip)
        coEvery { lightClient.getScripts() } returns null

        repository = testGatewayRepository(
            db = db,
            walletPreferences = walletPreferences,
            nodeLifecycle = nodeLifecycle,
            syncCoordinator = coordinator,
            keyManager = keyManager,
            lightClient = lightClient,
            json = json,
        )

        runBlocking {
            seedWallet(ACTIVE, activeScript, lastActiveAt = 2L)
            seedWallet(OTHER, otherScript, lastActiveAt = 1L)
            // Both wallets mid-sync; the other wallet's progress must survive.
            seedProgress(ACTIVE, ACTIVE_PROGRESS)
            seedProgress(OTHER, OTHER_PROGRESS)
            // Sets the active wallet's lock script, which registerAccount needs.
            repository.initializeWallet().getOrThrow()
        }
        walletPreferences.setSyncMode(SyncMode.RECENT, walletId = ACTIVE)
        walletPreferences.setSyncMode(SyncMode.RECENT, walletId = OTHER)
    }

    private fun newCoordinator(logger: com.rjnr.pocketnode.core.log.Logger) = SyncCoordinator(
        RoomWalletRegistry(db.walletDao()),
        RoomSyncProgressStore(db.syncProgressDao()),
        RoomSubAccountCandidateStore(db.subAccountCandidateDao()),
        RoomTransactionStore(
            db.transactionDao(),
            CacheManager(db.transactionDao(), db.balanceCacheDao(), NoopLogger),
        ),
        bridge,
        walletPreferences,
        json,
        logger,
        queryContext = Dispatchers.IO,
    )

    @After
    fun tearDown() {
        db.close()
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

    /** Block number each lock args was registered from, from the single setScripts call. */
    private fun registeredBlocks(): Map<String, Long> {
        val (payload, _) = bridge.setScriptsCalls.single()
        return json.decodeFromString<List<JniScriptStatus>>(payload).associate {
            it.script.args to it.blockNumber.removePrefix("0x").toLong(16)
        }
    }

    private fun assertResyncKeepsOtherWallet(strategy: SyncStrategy) = runBlocking {
        walletPreferences.setSyncStrategy(strategy)

        val result = repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT)

        assertTrue("resync failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(LightClientNative.CMD_SET_SCRIPTS_ALL, bridge.setScriptsCalls.single().second)
        val blocks = registeredBlocks()
        assertEquals("both wallets must stay registered", setOf(activeScript.args, otherScript.args), blocks.keys)
        assertEquals("active wallet restarts from the new mode", CUSTOM_HEIGHT, blocks[activeScript.args])
        assertEquals("other wallet resumes its own progress", OTHER_PROGRESS, blocks[otherScript.args])
        assertEquals(SyncMode.CUSTOM, walletPreferences.getSyncMode(walletId = ACTIVE))
        assertEquals(CUSTOM_HEIGHT, walletPreferences.getCustomBlockHeight(walletId = ACTIVE))
        assertEquals("other wallet's mode untouched", SyncMode.RECENT, walletPreferences.getSyncMode(walletId = OTHER))
        assertTrue(repository.isRegistered.value)
    }

    @Test
    fun `ALL_WALLETS resync registers every wallet, active from the new start block`() =
        assertResyncKeepsOtherWallet(SyncStrategy.ALL_WALLETS)

    @Test
    fun `BALANCED resync registers every wallet, active from the new start block`() =
        assertResyncKeepsOtherWallet(SyncStrategy.BALANCED)

    @Test
    fun `resync rewinds the active wallet even if the sync poll re-saves its old progress mid-registration`() =
        runBlocking {
            walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
            // The poll's getAccountStatus writes the light client's current
            // (old) block back for the active wallet after resync zeroed it.
            bridge.onTipRead = { seedProgress(ACTIVE, ACTIVE_PROGRESS) }

            repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT).getOrThrow()

            val blocks = registeredBlocks()
            assertEquals(CUSTOM_HEIGHT, blocks[activeScript.args])
            assertEquals(OTHER_PROGRESS, blocks[otherScript.args])
        }

    @Test
    fun `ACTIVE_ONLY resync keeps the single-wallet path and registers only the active wallet`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.ACTIVE_ONLY)

        repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT).getOrThrow()

        assertEquals(mapOf(activeScript.args to CUSTOM_HEIGHT), registeredBlocks())
        assertEquals(SyncMode.CUSTOM, walletPreferences.getSyncMode(walletId = ACTIVE))
    }

    @Test
    fun `ACTIVE_ONLY resync failure does not persist the new mode`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.ACTIVE_ONLY)
        coEvery { nodeLifecycle.awaitNodeReady() } returns false

        val result = repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT)

        assertEquals("Node initialization failed", result.exceptionOrNull()?.message)
        assertTrue(bridge.setScriptsCalls.isEmpty())
        assertNotEquals(SyncMode.CUSTOM, walletPreferences.getSyncMode(walletId = ACTIVE))
    }

    /**
     * Review S1: the ALL / BALANCED branch writes the prefs and zeroes the
     * progress before registering, so a failed registration must put all of
     * it back, or the mode the UI reports as failed applies on next startup.
     */
    @Test
    fun `a failed ALL_WALLETS resync leaves prefs and saved progress exactly as before`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        walletPreferences.setInitialSyncCompleted(false, walletId = ACTIVE)
        bridge.setScriptsReturn = false

        val result = repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT)

        assertTrue(result.isFailure)
        assertEquals(SyncMode.RECENT, walletPreferences.getSyncModeOrNull(walletId = ACTIVE))
        assertEquals(null, walletPreferences.getCustomBlockHeight(walletId = ACTIVE))
        assertEquals(false, walletPreferences.hasCompletedInitialSync(walletId = ACTIVE))
        assertEquals(ACTIVE_PROGRESS, repository.getWalletSyncBlock(ACTIVE))
        assertEquals(OTHER_PROGRESS, repository.getWalletSyncBlock(OTHER))
    }

    @Test
    fun `a failed resync with no mode ever stored leaves the mode unset`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.BALANCED)
        walletPreferences.clearSyncMode(walletId = ACTIVE)
        walletPreferences.setCustomBlockHeight(1_234L, walletId = ACTIVE)
        walletPreferences.setInitialSyncCompleted(true, walletId = ACTIVE)
        bridge.setScriptsReturn = false

        assertTrue(repository.resyncAccount(SyncMode.FULL_HISTORY, null).isFailure)

        assertEquals(null, walletPreferences.getSyncModeOrNull(walletId = ACTIVE))
        assertEquals(1_234L, walletPreferences.getCustomBlockHeight(walletId = ACTIVE))
        assertEquals(true, walletPreferences.hasCompletedInitialSync(walletId = ACTIVE))
        assertEquals(ACTIVE_PROGRESS, repository.getWalletSyncBlock(ACTIVE))
    }

    /**
     * Codex on ddd6609: registration can wait seconds for the tip while the
     * user switches wallets. The rollback must target the wallet the resync
     * was started for, not whichever wallet is active when it fails.
     */
    @Test
    fun `a failed resync rolls back the initiating wallet even if the active wallet changed mid-registration`() =
        runBlocking {
            walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
            val other = db.walletDao().getById(OTHER)!!
            bridge.setScriptsReturn = false
            // One-shot: the switch's own registration reads the tip too.
            bridge.onTipRead = {
                bridge.onTipRead = {}
                runCatching { repository.onActiveWalletChanged(other) }
            }

            assertTrue(repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT).isFailure)

            assertEquals(ACTIVE_PROGRESS, repository.getWalletSyncBlock(ACTIVE))
            assertEquals(SyncMode.RECENT, walletPreferences.getSyncModeOrNull(walletId = ACTIVE))
            assertEquals(null, walletPreferences.getCustomBlockHeight(walletId = ACTIVE))
            assertEquals("other wallet's progress untouched", OTHER_PROGRESS, repository.getWalletSyncBlock(OTHER))
            assertEquals(SyncMode.RECENT, walletPreferences.getSyncModeOrNull(walletId = OTHER))
            assertEquals(null, walletPreferences.getCustomBlockHeight(walletId = OTHER))
        }

    /**
     * Codex on b90260a: under BALANCED, a resync for A that is still waiting
     * for the tip when the user switches to a lagging wallet B must not
     * finish its own CMD_SET_SCRIPTS_ALL after the switch's, or it would
     * drop B as a laggard and leave the displayed wallet unsynced.
     */
    @Test
    fun `a stale BALANCED resync aborts before setScripts when the user switches to a lagging wallet`() =
        runBlocking {
            walletPreferences.setSyncStrategy(SyncStrategy.BALANCED)
            // C leads; B (OTHER) lags C by far more than the BALANCED threshold.
            seedWallet("wallet-c", script("cc"), lastActiveAt = 3L)
            seedProgress("wallet-c", ACTIVE_PROGRESS)
            seedProgress(OTHER, 1_000_000L)
            val laggard = db.walletDao().getById(OTHER)!!
            // One-shot: switch to B while A's registration waits for the tip.
            bridge.onTipRead = {
                bridge.onTipRead = {}
                repository.onActiveWalletChanged(laggard)
            }

            val result = repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT)

            assertEquals("Active wallet changed during registration", result.exceptionOrNull()?.message)
            // Only the switch's registration reached the light client, and it kept B.
            val blocks = registeredBlocks()
            assertTrue("switched-to wallet must stay registered", otherScript.args in blocks.keys)
            // A was rolled back to where it was.
            assertEquals(ACTIVE_PROGRESS, repository.getWalletSyncBlock(ACTIVE))
            assertEquals(SyncMode.RECENT, walletPreferences.getSyncModeOrNull(walletId = ACTIVE))
        }

    /**
     * Codex on 919afc9 (#539): the staleness check and the JNI set must be
     * one step. A's resync passes its check, then (while A's set is in
     * flight) the user switches to lagging B and B's registration runs. With
     * the registration mutex B waits for A's set and lands last, so B's set
     * stands; without it B's set would land first and A's (which dropped B
     * as a laggard) would overwrite it.
     */
    @Test
    fun `a switch landing between the resync's check and its set cannot be overwritten`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.BALANCED)
        seedWallet("wallet-c", script("cc"), lastActiveAt = 3L)
        seedProgress("wallet-c", ACTIVE_PROGRESS)
        seedProgress(OTHER, 1_000_000L) // B lags C far beyond the threshold
        val laggard = db.walletDao().getById(OTHER)!!
        var switchJob: kotlinx.coroutines.Job? = null
        // Signalled when B's registration logs "Registering N wallet
        // scripts", its last step before setScriptsAndRecord (the lock).
        val bAtLock = kotlinx.coroutines.CompletableDeferred<Unit>()
        var registeringLogs = 0
        coordinatorLogger.onDebug = { msg ->
            if (msg.startsWith("Registering") && ++registeringLogs == 2) bAtLock.complete(Unit)
        }
        // One-shot, inside A's set (after A's check passed).
        bridge.onSetScripts = {
            bridge.onSetScripts = {}
            switchJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                repository.onActiveWalletChanged(laggard)
            }
            // B is past its node and tip waits and at the lock. Without the
            // mutex it would now finish in milliseconds; with it, it cannot
            // until A's set returns. The short grace only lets the unguarded
            // case complete; it cannot make the test pass falsely.
            kotlinx.coroutines.withTimeout(10_000) { bAtLock.await() }
            kotlinx.coroutines.withTimeoutOrNull(300) { switchJob!!.join() }
        }

        repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT).getOrThrow()
        switchJob!!.join()

        assertEquals(2, bridge.setScriptsCalls.size)
        val (lastPayload, lastCmd) = bridge.setScriptsCalls.last()
        assertEquals(LightClientNative.CMD_SET_SCRIPTS_ALL, lastCmd)
        val lastArgs = json.decodeFromString<List<JniScriptStatus>>(lastPayload).map { it.script.args }
        assertTrue("the switched-to wallet's set must stand", otherScript.args in lastArgs)
    }

    /**
     * Review S1: the resync persists nothing before its set lands. A switch
     * during the resync's tip wait must see the resynced wallet X at its
     * saved progress and old mode, and the aborted resync must leave X as
     * it was, so the light client and the prefs agree.
     */
    @Test
    fun `a switch during the resync's tip wait registers the wallet at its saved state and the abort changes nothing`() =
        runBlocking {
            walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
            val other = db.walletDao().getById(OTHER)!!
            bridge.onTipRead = {
                bridge.onTipRead = {}
                repository.onActiveWalletChanged(other)
            }

            val result = repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT)

            assertTrue(result.exceptionOrNull() is ActiveWalletChangedException)
            // Only the switch's set reached the light client, with X unchanged.
            val blocks = registeredBlocks()
            assertEquals(ACTIVE_PROGRESS, blocks[activeScript.args])
            assertEquals(OTHER_PROGRESS, blocks[otherScript.args])
            assertEquals(ACTIVE_PROGRESS, repository.getWalletSyncBlock(ACTIVE))
            assertEquals(SyncMode.RECENT, walletPreferences.getSyncModeOrNull(walletId = ACTIVE))
            assertEquals(null, walletPreferences.getCustomBlockHeight(walletId = ACTIVE))
        }

    /**
     * Review S2: every all-wallet registration re-checks the live active
     * wallet under the lock. A poller-driven BALANCED re-registration
     * computed for A must not land after a switch to laggard B, and the
     * abort is a quiet no-op.
     */
    @Test
    fun `a BALANCED re-registration computed for A does not land after a switch to laggard B`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.BALANCED)
        seedProgress(OTHER, 1_000_000L) // B lags A (19.5M) far beyond the threshold
        var live = ACTIVE
        val coordinator = newCoordinator(NoopLogger)
        // The switch lands while the re-registration waits for the tip.
        bridge.onTipRead = { bridge.onTipRead = {}; live = OTHER }

        coordinator.maybeReregisterBalanced(
            SyncCoordinator.SyncContext(
                network = network,
                activeWalletId = ACTIVE,
                awaitNodeReady = { true },
                getWalletSyncBlock = { id -> repository.getWalletSyncBlock(id) },
                onScriptsRegistered = {},
                liveActiveWalletId = { live },
            ),
        )

        assertTrue("the stale set must not reach the light client", bridge.setScriptsCalls.isEmpty())
    }

    /**
     * Codex on 4cb0189: maybeReregisterBalanced filters for A, then hands the
     * filtered set to registerAllWalletScripts. A switch to laggard B between
     * the two must abort that stale set, not let it pass the under-lock check
     * against a fresh read of B.
     */
    @Test
    fun `a switch between the BALANCED filter and its registration aborts the stale set`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.BALANCED)
        seedProgress(OTHER, 1_000_000L) // B lags A (19.5M) far beyond the threshold
        var live = ACTIVE
        val coordinator = newCoordinator(NoopLogger)

        coordinator.maybeReregisterBalanced(
            SyncCoordinator.SyncContext(
                network = network,
                activeWalletId = ACTIVE,
                // The switch lands after the filter, during the node wait,
                // before registerAllWalletScripts reads the active wallet.
                awaitNodeReady = { live = OTHER; true },
                getWalletSyncBlock = { id -> repository.getWalletSyncBlock(id) },
                onScriptsRegistered = {},
                liveActiveWalletId = { live },
            ),
        )

        assertTrue("the stale set must not reach the light client", bridge.setScriptsCalls.isEmpty())
    }

    /** Review S3: an ACTIVE_ONLY resync that fails puts the wallet's saved progress back. */
    @Test
    fun `a failed ACTIVE_ONLY resync restores the wallet's saved progress`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.ACTIVE_ONLY)
        coEvery { nodeLifecycle.awaitNodeReady() } returns false

        assertTrue(repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT).isFailure)

        assertEquals(ACTIVE_PROGRESS, repository.getWalletSyncBlock(ACTIVE))
        assertEquals(OTHER_PROGRESS, repository.getWalletSyncBlock(OTHER))
    }

    /** Codex on b90260a (1): BALANCED keeps the LIVE active wallet, not the context's snapshot. */
    @Test
    fun `BALANCED keeps the live active wallet even when the context snapshot names another`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.BALANCED)
        seedProgress(OTHER, 1_000_000L) // lags ACTIVE (19.5M) by far more than the threshold
        val coordinator = newCoordinator(NoopLogger)

        coordinator.registerAllWalletScripts(
            SyncCoordinator.SyncContext(
                network = network,
                activeWalletId = ACTIVE, // stale snapshot
                awaitNodeReady = { true },
                getWalletSyncBlock = { id -> repository.getWalletSyncBlock(id) },
                onScriptsRegistered = {},
                liveActiveWalletId = { OTHER },
            ),
        )

        assertTrue("live active laggard kept", otherScript.args in registeredBlocks().keys)
    }

    @Test
    fun `ACTIVE_ONLY resync fails rather than apply its mode to a wallet switched to mid-registration`() =
        runBlocking {
            walletPreferences.setSyncStrategy(SyncStrategy.ACTIVE_ONLY)
            val other = db.walletDao().getById(OTHER)!!
            var switched = false
            coEvery { nodeLifecycle.awaitNodeReady() } coAnswers {
                if (!switched) {
                    switched = true
                    repository.onActiveWalletChanged(other)
                }
                true
            }

            val result = repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT)

            assertEquals("Active wallet changed during registration", result.exceptionOrNull()?.message)
            assertEquals(SyncMode.RECENT, walletPreferences.getSyncModeOrNull(walletId = OTHER))
            assertEquals(null, walletPreferences.getCustomBlockHeight(walletId = OTHER))
            assertNotEquals(SyncMode.CUSTOM, walletPreferences.getSyncMode(walletId = ACTIVE))
        }

    /** Review N2: the concurrency cap keeps the active wallet even when others are more recent. */
    @Test
    fun `the wallet cap never drops the active wallet`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        seedWallet("wallet-c", script("cc"), lastActiveAt = 10L)
        seedWallet("wallet-d", script("dd"), lastActiveAt = 11L)
        seedWallet("wallet-e", script("ee"), lastActiveAt = 12L)

        repository.resyncAccount(SyncMode.CUSTOM, CUSTOM_HEIGHT).getOrThrow()

        val blocks = registeredBlocks()
        assertEquals(3, blocks.size)
        assertEquals(CUSTOM_HEIGHT, blocks[activeScript.args])
        // The two most recent others fill the remaining slots.
        assertEquals(setOf(activeScript.args, script("ee").args, script("dd").args), blocks.keys)
    }

    /** Review N1: a CUSTOM height above the tip resets to the RECENT window, as registerAccount does. */
    @Test
    fun `ALL_WALLETS resync clamps a CUSTOM height above the tip`() = runBlocking {
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)

        repository.resyncAccount(SyncMode.CUSTOM, tip + 1_000L).getOrThrow()

        assertEquals(tip - 200_000L, registeredBlocks()[activeScript.args])
    }

    private companion object {
        const val ACTIVE = "wallet-active"
        const val OTHER = "wallet-other"
        const val ACTIVE_PROGRESS = 19_500_000L
        const val OTHER_PROGRESS = 19_000_000L
        const val CUSTOM_HEIGHT = 5_000_000L
    }
}
