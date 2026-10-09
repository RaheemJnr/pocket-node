package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.time.FakeClock
import com.rjnr.pocketnode.data.gateway.FakeLightClientApi
import com.rjnr.pocketnode.data.gateway.LightClientFixtures
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.storage.EmptySubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.EmptyTransactionStore
import com.rjnr.pocketnode.data.storage.FakeSyncProgressStore
import com.rjnr.pocketnode.data.storage.InMemoryWalletRegistry
import com.rjnr.pocketnode.data.storage.SyncProgressRecord
import com.rjnr.pocketnode.data.storage.SyncProgressStore
import com.rjnr.pocketnode.data.wallet.AddressUtils
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS registration and the sync poll's progress write against the
 * coordinator's registration lock: the #539 race class, on the iOS driver.
 *
 * Every bridge read and the coordinator's own hops run on
 * [Dispatchers.Unconfined], so a registration runs straight through until it
 * really suspends, and the only place one can is the registration lock. Each
 * race is staged by a store hook that starts the competing call, UNDISPATCHED,
 * at the exact point the reviewer found the gap: before the fix the competitor
 * finishes inside the gap, after it the competitor queues on the lock.
 */
class SingleWalletSyncServiceRegistrationLockTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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
    private val key = walletId to network.name

    private val fake = FakeLightClientApi()
    private val preferences = FakeSyncPreferences()
    private val rows = FakeSyncProgressStore()
    private val store = HookedProgressStore(rows)
    private val registry = InMemoryWalletRegistry()
    private val clock = FakeClock()

    private val coordinator = SyncCoordinator(
        walletRegistry = registry,
        syncProgressStore = store,
        subAccountCandidateStore = EmptySubAccountCandidateStore,
        transactionStore = EmptyTransactionStore,
        lightClient = fake,
        syncPreferences = preferences,
        json = json,
        logger = NoopLogger,
        clock = clock,
        queryContext = Dispatchers.Unconfined,
    )

    private val service = SingleWalletSyncService(
        coordinator = coordinator,
        engine = SyncEngine(fake, preferences, json, NoopLogger, clock, Dispatchers.Unconfined),
        syncProgressStore = store,
        walletRegistry = registry,
        syncPreferences = preferences,
        logger = NoopLogger,
        clock = clock,
    )

    @AfterTest
    fun tearDown() {
        service.close()
    }

    /**
     * [FakeSyncProgressStore] with one-shot hooks that run at the top of a
     * call, before the real read or write: the window a concurrent caller
     * would land in.
     */
    private class HookedProgressStore(
        private val inner: FakeSyncProgressStore,
    ) : SyncProgressStore by inner {
        var beforeGetAll: (suspend () -> Unit)? = null
        var beforeUpsert: (suspend () -> Unit)? = null
        var failUpdateLightStart = false

        override suspend fun getAllForNetwork(network: String): List<SyncProgressRecord> {
            beforeGetAll?.let { beforeGetAll = null; it() }
            return inner.getAllForNetwork(network)
        }

        override suspend fun upsert(record: SyncProgressRecord) {
            beforeUpsert?.let { beforeUpsert = null; it() }
            inner.upsert(record)
        }

        override suspend fun updateLightStart(
            walletId: String,
            network: String,
            lightStart: Long,
            ts: Long,
        ): Int {
            if (failUpdateLightStart) {
                failUpdateLightStart = false
                throw IllegalStateException("disk full")
            }
            return inner.updateLightStart(walletId, network, lightStart, ts)
        }
    }

    private fun activate() {
        service.setWallet(
            wallet = ActiveWallet(address, scriptArgs),
            walletId = walletId,
            network = network,
            mainnetAddress = "",
            testnetAddress = address,
        )
    }

    /** A node that answers the tip and accepts every set, and the wallet on it. */
    private suspend fun registerRecent() {
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueueFlag("setScripts", true)
        activate()
        assertTrue(service.registerWallet(SyncMode.RECENT, null) { true })
        assertEquals(tip - 200_000L, rows.rows[key]?.localSavedBlockNumber)
    }

    private fun lastRegisteredBlockHex(): String =
        json.decodeFromString<List<JniScriptStatus>>(
            fake.callsTo("setScripts").last().args[0] as String
        ).single().blockNumber

    /** Start [block] now, on this test's scheduler, running until it first suspends. */
    private fun TestScope.startNow(block: suspend () -> Unit): Job =
        launch(start = CoroutineStart.UNDISPATCHED) { block() }

    @Test
    fun `a poll that read before a mode change cannot write over the reset row`() = runTest {
        registerRecent()
        // The poll in flight: epoch read before the chain read, block from the
        // RECENT range.
        val epochDuringPoll = service.registrationEpoch
        val staleBlock = tip - 100_000L

        // The registration lands while the poll is between its epoch check
        // and its write (the store read in between suspends on a real disk).
        var registration: Job? = null
        store.beforeGetAll = {
            registration = startNow {
                assertTrue(service.registerWallet(SyncMode.FULL_HISTORY, null) { true })
            }
        }
        service.recordProgress(staleBlock, epochDuringPoll)
        assertNotNull(registration).join()

        val row = rows.rows[key]
        assertEquals(0L, row?.localSavedBlockNumber, "the All history reset stands")
        assertEquals(0L, row?.lightStartBlockNumber)
        assertEquals(SyncMode.FULL_HISTORY, preferences.getSyncModeOrNull(walletId = walletId))
    }

    @Test
    fun `a re-registration queued behind a mode change registers the new mode`() = runTest {
        registerRecent()

        // Activation's re-register reaches the lock while the mode change is
        // still writing its inputs.
        var reregistration: Job? = null
        store.beforeUpsert = {
            reregistration = startNow { service.reregisterFromSavedProgress { true } }
        }
        assertTrue(service.registerWallet(SyncMode.FULL_HISTORY, null) { true })
        assertNotNull(reregistration).join()

        assertEquals(SyncMode.FULL_HISTORY, preferences.getSyncModeOrNull(walletId = walletId))
        assertEquals(
            "0x0",
            lastRegisteredBlockHex(),
            "the light client's last set must be the mode the prefs say, not RECENT's block",
        )
        assertEquals(0L, rows.rows[key]?.localSavedBlockNumber)
    }

    @Test
    fun `the poll skips its write while a registration holds the lock`() = runTest {
        registerRecent()
        val epoch = service.registrationEpoch
        val block = tip - 100_000L

        coordinator.withRegistrationLock { service.recordProgress(block, epoch) }
        assertEquals(tip - 200_000L, rows.rows[key]?.localSavedBlockNumber, "skipped, not queued")

        // Control: the same write with the lock free lands.
        service.recordProgress(block, epoch)
        assertEquals(block, rows.rows[key]?.localSavedBlockNumber)
    }

    @Test
    fun `a registration records the wallet it was computed for`() = runTest {
        assertNull(coordinator.registeredActiveWalletId)

        registerRecent()

        assertEquals(walletId, coordinator.registeredActiveWalletId)
    }

    @Test
    fun `a set that landed keeps its reset even when the bookkeeping after it fails`() = runTest {
        registerRecent()

        store.failUpdateLightStart = true
        assertFailsWith<IllegalStateException> {
            service.registerWallet(SyncMode.FULL_HISTORY, null) { true }
        }

        // The light client took FULL_HISTORY, so the row and the prefs must
        // say so too, or the next launch resumes RECENT.
        assertEquals("0x0", lastRegisteredBlockHex())
        assertEquals(0L, rows.rows[key]?.localSavedBlockNumber)
        assertEquals(0L, rows.rows[key]?.lightStartBlockNumber)
        assertEquals(SyncMode.FULL_HISTORY, preferences.getSyncModeOrNull(walletId = walletId))
    }

    @Test
    fun `a refused set writes nothing under the lock`() = runTest {
        registerRecent()
        val epochBefore = service.registrationEpoch
        // A node that answers the tip and refuses the set, behind its own
        // coordinator so the queue above is not disturbed.
        val refusingFake = FakeLightClientApi()
            .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueueFlag("setScripts", false)
        val refusing = SingleWalletSyncService(
            coordinator = SyncCoordinator(
                walletRegistry = registry,
                syncProgressStore = store,
                subAccountCandidateStore = EmptySubAccountCandidateStore,
                transactionStore = EmptyTransactionStore,
                lightClient = refusingFake,
                syncPreferences = preferences,
                json = json,
                logger = NoopLogger,
                clock = clock,
                queryContext = Dispatchers.Unconfined,
            ),
            engine = SyncEngine(refusingFake, preferences, json, NoopLogger, clock, Dispatchers.Unconfined),
            syncProgressStore = store,
            walletRegistry = registry,
            syncPreferences = preferences,
            logger = NoopLogger,
            clock = clock,
        )
        refusing.setWallet(ActiveWallet(address, scriptArgs), walletId, network, "", address)

        assertFalse(refusing.registerWallet(SyncMode.FULL_HISTORY, null) { true })

        assertEquals(1, refusingFake.callsTo("setScripts").size, "the set was asked for")
        assertEquals(tip - 200_000L, rows.rows[key]?.localSavedBlockNumber, "RECENT's progress kept")
        assertEquals(SyncMode.RECENT, preferences.getSyncModeOrNull(walletId = walletId))
        assertEquals(0L, refusing.registrationEpoch, "no epoch bump for a refused set")
        assertEquals(epochBefore, service.registrationEpoch)
        refusing.close()
    }
}
