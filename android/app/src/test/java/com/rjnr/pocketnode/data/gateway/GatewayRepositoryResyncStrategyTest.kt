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
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
 * real [WalletPreferences]); only the JNI surface ([LightClientBridge]) and
 * the unrelated collaborators are faked.
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

    private class FakeBridge(private val tipJson: String) : LightClientBridge {
        val setScriptsCalls = mutableListOf<Pair<String, Int>>()
        /** Runs while the coordinator waits for the tip, i.e. mid-registration. */
        var onTipRead: suspend () -> Unit = {}
        var setScriptsReturn = true

        override suspend fun setScripts(scriptsJson: String, command: Int): Boolean {
            setScriptsCalls += scriptsJson to command
            return setScriptsReturn
        }

        override suspend fun getTipHeaderRaw(): String {
            onTipRead()
            return tipJson
        }

        override suspend fun getScriptsRaw(): String? = null
    }

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

        val coordinator = SyncCoordinator(
            walletDao = db.walletDao(),
            syncProgressDao = db.syncProgressDao(),
            syncPreferences = walletPreferences,
            keyManager = KeyManager(ctx, MnemonicManager(), NoopLogger),
            json = json,
            lightClient = bridge,
            subAccountCandidateDao = db.subAccountCandidateDao(),
            transactionDao = db.transactionDao(),
            logger = NoopLogger,
        )

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
