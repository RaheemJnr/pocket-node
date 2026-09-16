package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.time.FakeClock
import com.rjnr.pocketnode.data.gateway.FakeLightClientApi
import com.rjnr.pocketnode.data.gateway.LightClientFixtures
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.storage.EmptySubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.EmptyTransactionStore
import com.rjnr.pocketnode.data.storage.FakeSyncProgressStore
import com.rjnr.pocketnode.data.storage.InMemoryWalletRegistry
import com.rjnr.pocketnode.data.wallet.AddressUtils
import io.mockk.spyk
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The three tracker calls `registerWallet` makes after a successful
 * registration, and the order they have to happen in.
 *
 * It lives in `androidHostTest` rather than beside the rest of the suite
 * because the order is not observable from outside [SyncEngine]: `reset`,
 * `seedStartHeight` and `resetProgressState` leave the same visible state
 * whichever way round the first two run, and only the percentage of a later
 * poll would tell them apart. MockK, which the shared module has on the JVM
 * source set only, can see the calls directly.
 *
 * Why the order matters: `resetTracker` clears the baseline, so seeding before
 * it would be thrown away and the first poll would anchor the percentage to a
 * transient `syncedToBlock=0` reading during peer warm-up (#150).
 */
class SingleWalletSyncServiceEngineOrderTest {

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

    private val fake = FakeLightClientApi()
    private val preferences = FakeSyncPreferences()
    private val progressStore = FakeSyncProgressStore()
    private val registry = InMemoryWalletRegistry()
    private val clock = FakeClock()

    @Test
    fun `a successful registration resets the tracker, seeds it, then clears the derived state`() =
        runTest {
            val engine = spyk(SyncEngine(fake, preferences, json, NoopLogger, clock))
            val service = SingleWalletSyncService(
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
            service.setWallet(
                wallet = ActiveWallet(address, scriptArgs),
                walletId = "wallet-a",
                network = NetworkType.TESTNET,
                mainnetAddress = "",
                testnetAddress = address,
            )

            fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            fake.enqueueFlag("setScripts", true)

            service.registerWallet(SyncMode.RECENT, null) { true }

            val startBlock = LightClientFixtures.TIP_HEADER_NUMBER - 200_000L
            verifyOrder {
                engine.resetTracker()
                engine.seedStartHeight(startBlock)
                engine.resetProgressState()
            }
            service.close()
        }
}
