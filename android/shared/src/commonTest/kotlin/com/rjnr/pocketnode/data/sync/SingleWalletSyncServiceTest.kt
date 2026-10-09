package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.time.FakeClock
import com.rjnr.pocketnode.data.gateway.FakeLightClientApi
import com.rjnr.pocketnode.data.gateway.LightClientFixtures
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.getCheckpoint
import com.rjnr.pocketnode.data.gateway.models.toFromBlock
import com.rjnr.pocketnode.data.storage.EmptySubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.EmptyTransactionStore
import com.rjnr.pocketnode.data.storage.FakeSyncProgressStore
import com.rjnr.pocketnode.data.storage.InMemoryWalletRegistry
import com.rjnr.pocketnode.data.storage.SyncProgressRecord
import com.rjnr.pocketnode.data.wallet.AddressUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS registration and polling driver.
 *
 * Everything is asserted through what the light client was actually handed
 * (`setScripts`) and what was written to the two stores, rather than through
 * the service's own accessors: the point of the class is the payload, and a
 * test that only checked its return value would pass with the wrong block.
 */
class SingleWalletSyncServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The secp256k1-blake160 lock from [LightClientFixtures.GET_SCRIPTS], and its
     * address. Encoded from the script rather than pasted so the two cannot
     * drift: the service decodes the address back to a script and registers
     * that, and the assertions compare against these args.
     */
    private val scriptArgs = "0xda648442dbb7347e467d1d09da13e5cd3a0ef0e1"
    private val address = AddressUtils.encode(
        Script(
            codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
            hashType = "type",
            args = scriptArgs,
        ),
        NetworkType.TESTNET,
    )
    private val walletId = "wallet-a"
    private val network = NetworkType.TESTNET

    private val tip = LightClientFixtures.TIP_HEADER_NUMBER

    private val fake = FakeLightClientApi()
    private val preferences = FakeSyncPreferences()
    private val progressStore = FakeSyncProgressStore()
    private val registry = InMemoryWalletRegistry()
    private val clock = FakeClock()

    private val engine = SyncEngine(fake, preferences, json, NoopLogger, clock)

    private val coordinator = SyncCoordinator(
        walletRegistry = registry,
        syncProgressStore = progressStore,
        subAccountCandidateStore = EmptySubAccountCandidateStore,
        transactionStore = EmptyTransactionStore,
        lightClient = fake,
        syncPreferences = preferences,
        json = json,
        logger = NoopLogger,
        clock = clock,
    )

    private val service = makeService()

    /**
     * [scopeContext] is the poll loop's dispatcher. Most tests never start the
     * loop and take the production default; the polling ones pass the test
     * dispatcher so the loop runs on the test scheduler's virtual clock instead
     * of a real background thread.
     */
    private fun makeService(scopeContext: CoroutineDispatcher = Dispatchers.Default) =
        SingleWalletSyncService(
            coordinator = coordinator,
            // Its own engine, reading the node on the same dispatcher the loop
            // runs on. Sharing the default engine would leave the bridge reads
            // on `Dispatchers.Default`, outside the scheduler's virtual time,
            // and the assertions would race the poll.
            engine = SyncEngine(fake, preferences, json, NoopLogger, clock, scopeContext),
            syncProgressStore = progressStore,
            walletRegistry = registry,
            syncPreferences = preferences,
            logger = NoopLogger,
            clock = clock,
            scopeContext = scopeContext,
        )

    @AfterTest
    fun tearDown() {
        service.close()
    }

    /** Point [target] at the test wallet. Defaults to the shared instance. */
    private fun activate(target: SingleWalletSyncService = service) {
        target.setWallet(
            wallet = ActiveWallet(address, scriptArgs),
            walletId = walletId,
            network = network,
            mainnetAddress = "",
            testnetAddress = address,
        )
    }

    /** Every script status the light client was handed, newest call last. */
    private fun registeredStatuses(): List<List<JniScriptStatus>> =
        fake.callsTo("setScripts").map { json.decodeFromString(it.args[0] as String) }

    private fun onlyRegisteredBlockHex(): String {
        val statuses = registeredStatuses().single()
        assertEquals(1, statuses.size, "one wallet registers exactly one script")
        assertEquals(scriptArgs, statuses.single().script.args)
        assertEquals("lock", statuses.single().scriptType)
        return statuses.single().blockNumber
    }

    // MARK: - registerWallet

    private fun expectedHexFor(mode: SyncMode, customBlockHeight: Long?): String {
        val block = mode.toFromBlock(customBlockHeight, tip, network).toLong()
        return "0x${block.toString(16)}"
    }

    /** Script the node to accept, point the service at the wallet, register. */
    private suspend fun register(mode: SyncMode, customBlockHeight: Long? = null): Boolean {
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueueFlag("setScripts", true)
        activate()
        return service.registerWallet(mode, customBlockHeight) { true }
    }

    @Test
    fun `NEW_WALLET registers from the tip`() = runTest {
        assertTrue(register(SyncMode.NEW_WALLET))

        assertEquals(expectedHexFor(SyncMode.NEW_WALLET, null), onlyRegisteredBlockHex())
        assertEquals("0x${tip.toString(16)}", onlyRegisteredBlockHex())
    }

    @Test
    fun `RECENT registers 200k blocks back`() = runTest {
        assertTrue(register(SyncMode.RECENT))

        assertEquals(expectedHexFor(SyncMode.RECENT, null), onlyRegisteredBlockHex())
        assertEquals("0x${(tip - 200_000L).toString(16)}", onlyRegisteredBlockHex())
    }

    @Test
    fun `FULL_HISTORY registers from genesis`() = runTest {
        assertTrue(register(SyncMode.FULL_HISTORY))

        assertEquals(expectedHexFor(SyncMode.FULL_HISTORY, null), onlyRegisteredBlockHex())
        assertEquals("0x0", onlyRegisteredBlockHex())
    }

    @Test
    fun `CUSTOM registers from the height the user gave`() = runTest {
        assertTrue(register(SyncMode.CUSTOM, customBlockHeight = 12_345L))

        assertEquals(expectedHexFor(SyncMode.CUSTOM, 12_345L), onlyRegisteredBlockHex())
        assertEquals("0x3039", onlyRegisteredBlockHex())
    }

    @Test
    fun `a successful registration records the start block and the mode`() = runTest {
        assertTrue(register(SyncMode.RECENT))

        val row = progressStore.rows[walletId to network.name]
        assertEquals(tip - 200_000L, row?.lightStartBlockNumber, "the registered start block")
        assertEquals(tip - 200_000L, row?.localSavedBlockNumber, "and nothing processed past it yet")
        assertEquals(clock.now, row?.updatedAt)

        assertEquals(SyncMode.RECENT, preferences.getSyncModeOrNull(walletId = walletId))
        assertTrue(preferences.hasCompletedInitialSync(walletId = walletId))
        assertTrue(service.isRegistered.value)
    }

    @Test
    fun `CUSTOM persists the height alongside the mode`() = runTest {
        assertTrue(register(SyncMode.CUSTOM, customBlockHeight = 12_345L))

        assertEquals(SyncMode.CUSTOM, preferences.getSyncModeOrNull(walletId = walletId))
        assertEquals(12_345L, preferences.getCustomBlockHeight(walletId = walletId))
    }

    @Test
    fun `a non custom mode leaves the custom height alone`() = runTest {
        assertTrue(register(SyncMode.RECENT))

        assertNull(preferences.getCustomBlockHeight(walletId = walletId))
    }

    @Test
    fun `a refused registration persists nothing`() = runTest {
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueueFlag("setScripts", false)
        activate()

        assertFalse(service.registerWallet(SyncMode.RECENT, null) { true })

        assertTrue(progressStore.rows.isEmpty(), "no sync_progress row for a refused registration")
        assertNull(preferences.getSyncModeOrNull(walletId = walletId))
        assertFalse(preferences.hasCompletedInitialSync(walletId = walletId))
        assertFalse(service.isRegistered.value)
    }

    @Test
    fun `a node that is not ready is not asked to register`() = runTest {
        activate()

        assertFalse(service.registerWallet(SyncMode.RECENT, null) { false })

        assertTrue(fake.callsTo("setScripts").isEmpty())
        assertTrue(progressStore.rows.isEmpty())
    }

    @Test
    fun `with no wallet there is nothing to register`() = runTest {
        assertFalse(service.registerWallet(SyncMode.RECENT, null) { true })

        assertTrue(fake.callsTo("setScripts").isEmpty())
    }

    @Test
    fun `a custom height past the tip is clamped back to recent`() = runTest {
        // Reachable from the sheet's block-height field. Registering a filter
        // above the tip would never match anything; `registerAccount` clamps the
        // same way.
        assertTrue(register(SyncMode.CUSTOM, customBlockHeight = tip + 1_000L))

        assertEquals("0x${(tip - 200_000L).toString(16)}", onlyRegisteredBlockHex())
    }

    // MARK: - reregisterFromSavedProgress

    @Test
    fun `a later launch re-registers from the block already processed`() = runTest {
        progressStore.rows[walletId to network.name] = SyncProgressRecord(
            walletId = walletId,
            network = network.name,
            lightStartBlockNumber = 1_000L,
            localSavedBlockNumber = 5_000L,
            updatedAt = clock.now,
        )
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueueFlag("setScripts", true)
        activate()

        service.reregisterFromSavedProgress { true }

        // 5000 = 0x1388: the saved progress, not the block the mode implies.
        assertEquals("0x1388", onlyRegisteredBlockHex())
        assertTrue(service.isRegistered.value)
    }

    @Test
    fun `with no saved row the re-registration falls back to the stored mode`() = runTest {
        preferences.setSyncMode(SyncMode.FULL_HISTORY, walletId = walletId)
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueueFlag("setScripts", true)
        activate()

        service.reregisterFromSavedProgress { true }

        assertEquals("0x0", onlyRegisteredBlockHex())
    }

    @Test
    fun `re-registration with no wallet does nothing`() = runTest {
        service.reregisterFromSavedProgress { true }

        assertTrue(fake.callsTo("setScripts").isEmpty())
        assertFalse(service.isRegistered.value)
    }

    @Test
    fun `changing the mode is not undone by the next launch`() = runTest {
        // The regression this guards: `setScriptsAndRecord` writes through
        // `updateLightStart`, which preserves `localSavedBlockNumber` on
        // purpose. Leaving RECENT's progress behind would have the relaunch
        // resume from it and quietly ignore the new mode.
        assertTrue(register(SyncMode.RECENT))
        assertEquals(tip - 200_000L, progressStore.rows[walletId to network.name]?.localSavedBlockNumber)

        fake.calls.clear()
        assertTrue(service.registerWallet(SyncMode.FULL_HISTORY, null) { true })

        val row = progressStore.rows[walletId to network.name]
        assertEquals(0L, row?.lightStartBlockNumber, "the new mode's start block")
        assertEquals(0L, row?.localSavedBlockNumber, "and the old mode's progress is gone")

        fake.calls.clear()
        service.reregisterFromSavedProgress { true }

        assertEquals("0x0", onlyRegisteredBlockHex(), "the relaunch resumes on All history, not RECENT")
    }

    // MARK: - Mainnet clamp

    @Test
    fun `on mainnet a start block of zero falls back to the checkpoint`() = runTest {
        // RECENT on mainnet with no tip yet: `toFromBlock` answers
        // checkpoint - 200k, which is not zero, so drive the clamp with CUSTOM
        // and no height, the one combination that really resolves to 0.
        val mainnetService = SingleWalletSyncService(
            coordinator = SyncCoordinator(
                walletRegistry = registry,
                syncProgressStore = progressStore,
                subAccountCandidateStore = EmptySubAccountCandidateStore,
                transactionStore = EmptyTransactionStore,
                lightClient = fake,
                syncPreferences = preferences,
                json = json,
                logger = NoopLogger,
                clock = clock,
            ),
            engine = engine,
            syncProgressStore = progressStore,
            walletRegistry = registry,
            syncPreferences = preferences,
            logger = NoopLogger,
            clock = clock,
        )
        mainnetService.setWallet(
            wallet = ActiveWallet(address, scriptArgs),
            walletId = walletId,
            network = NetworkType.MAINNET,
            mainnetAddress = address,
            testnetAddress = "",
        )
        fake.enqueueFlag("setScripts", true)
        // No tip header scripted: the node is up but has not heard from peers.

        assertTrue(mainnetService.registerWallet(SyncMode.CUSTOM, null) { true })

        val checkpoint = getCheckpoint(NetworkType.MAINNET)
        assertEquals(18_300_000L, checkpoint, "the mainnet checkpoint this test is pinned to")
        assertEquals("0x${checkpoint.toString(16)}", onlyRegisteredBlockHex())
        mainnetService.close()
    }

    // MARK: - Polling

    @Test
    fun `stopping the poll ends it and clears the coachmark edge`() = runTest {
        val polling = makeService(StandardTestDispatcher(testScheduler))
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueue("getScripts", LightClientFixtures.GET_SCRIPTS)
        activate(polling)

        polling.startPolling()
        advanceTimeBy(10)
        assertTrue(polling.syncProgress.value.isSyncing, "the fixture script is far behind the tip")
        assertNotNull(polling.syncProgress.value.firstCatchingUpAtMs)

        polling.stopPolling()
        val callsAtStop = fake.callsTo("getTipHeader").size
        advanceTimeBy(60_000)

        // The last reading is deliberately left on screen; what `stop` clears
        // is the coachmark edge (#90), and what it ends is the loop.
        assertNull(
            polling.syncProgress.value.firstCatchingUpAtMs,
            "the grace timer restarts from a clean clock on the next start",
        )
        assertEquals(
            callsAtStop,
            fake.callsTo("getTipHeader").size,
            "no further polls after stop",
        )
        polling.close()
    }

    @Test
    fun `the poll advances the saved progress when the script has moved on`() = runTest {
        progressStore.rows[walletId to network.name] = SyncProgressRecord(
            walletId = walletId,
            network = network.name,
            lightStartBlockNumber = 500L,
            localSavedBlockNumber = 500L,
            updatedAt = 1L,
        )
        // 900 = 0x384, the block the registered script has scanned to.
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueue("getScripts", scriptsAt(900L))
        val polling = makeService(StandardTestDispatcher(testScheduler))
        activate(polling)
        polling.startPolling()
        advanceTimeBy(10)
        polling.stopPolling()

        assertEquals(900L, progressStore.rows[walletId to network.name]?.localSavedBlockNumber)
        assertEquals(
            500L,
            progressStore.rows[walletId to network.name]?.lightStartBlockNumber,
            "the registration block is left alone",
        )
        polling.close()
    }

    @Test
    fun `a poll read before a re-registration does not write after it`() = runTest {
        assertTrue(register(SyncMode.RECENT))
        // What a poll already in flight is carrying: the epoch as it was when
        // its `readChainSyncState` ran, and a block from the RECENT range.
        val epochDuringPoll = service.registrationEpoch
        val staleBlock = tip - 100_000L

        fake.enqueueFlag("setScripts", true)
        assertTrue(service.registerWallet(SyncMode.FULL_HISTORY, null) { true })
        assertEquals(0L, progressStore.rows[walletId to network.name]?.localSavedBlockNumber)

        service.recordProgress(staleBlock, epochDuringPoll)

        assertEquals(
            0L,
            progressStore.rows[walletId to network.name]?.localSavedBlockNumber,
            "the old range's block would be higher than the reset row forever",
        )

        // And the guard is what stopped it: the same block on the current
        // epoch is written.
        service.recordProgress(staleBlock, service.registrationEpoch)
        assertEquals(staleBlock, progressStore.rows[walletId to network.name]?.localSavedBlockNumber)
    }

    @Test
    fun `the poll never rewinds the saved progress`() = runTest {
        progressStore.rows[walletId to network.name] = SyncProgressRecord(
            walletId = walletId,
            network = network.name,
            lightStartBlockNumber = 500L,
            localSavedBlockNumber = 500L,
            updatedAt = 1L,
        )
        // A script mid-rescan can report a block behind the saved one.
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueue("getScripts", scriptsAt(400L))
        val polling = makeService(StandardTestDispatcher(testScheduler))
        activate(polling)
        polling.startPolling()
        advanceTimeBy(10)
        polling.stopPolling()

        assertEquals(500L, progressStore.rows[walletId to network.name]?.localSavedBlockNumber)
        polling.close()
    }

    @Test
    fun `close is terminal`() = runTest {
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueue("getScripts", LightClientFixtures.GET_SCRIPTS)

        val polling = makeService(StandardTestDispatcher(testScheduler))
        activate(polling)
        polling.close()
        polling.startPolling()
        advanceTimeBy(60_000)

        assertTrue(
            fake.callsTo("getTipHeader").isEmpty(),
            "a poll started after close must never reach the node",
        )
    }

    @Test
    fun `the tip flow starts at zero`() {
        assertEquals(0L, service.tipFlow.value)
    }

    /** `getScripts` answering that this wallet's lock has scanned to [block]. */
    private fun scriptsAt(block: Long): String =
        """[{"script":{"args":"$scriptArgs",""" +
            """"code_hash":"0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",""" +
            """"hash_type":"type"},"script_type":"lock","block_number":"0x${block.toString(16)}"}]"""
}
