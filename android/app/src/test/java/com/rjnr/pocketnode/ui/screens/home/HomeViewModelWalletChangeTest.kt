package com.rjnr.pocketnode.ui.screens.home

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.SyncStrategy
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.LightClientReadOnly
import com.rjnr.pocketnode.data.gateway.NodeLifecycle
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.testGatewayRepository
import com.rjnr.pocketnode.data.price.PriceRepository
import com.rjnr.pocketnode.data.update.UpdateRepository
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * #563 review F5: a wallet switch refreshes Home once, whether Home started
 * it (switchWallet, whose walletInfo change the external-change collector
 * also sees) or something else did (Wallet Manager, a delete).
 *
 * Real [GatewayRepository] and [WalletRepository] over in-memory Room; a
 * refresh is counted by its peer read on Home's own light-client handle.
 * The test dispatcher only runs what is queued now (runCurrent): Home has
 * endless timers, so virtual time never advances.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeViewModelWalletChangeTest {

    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)
    private lateinit var db: AppDatabase
    private lateinit var prefs: WalletPreferences
    private lateinit var gateway: GatewayRepository
    private lateinit var walletRepository: WalletRepository
    private val refreshes = AtomicInteger()

    private fun script(byte: String) = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + byte.repeat(20),
    )

    private fun entity(id: String, byte: String, active: Boolean) = script(byte).let { s ->
        WalletEntity(
            walletId = id, name = id, type = KeyManager.WALLET_TYPE_MNEMONIC,
            derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null, accountIndex = 0,
            mainnetAddress = AddressUtils.encode(s, NetworkType.MAINNET),
            testnetAddress = AddressUtils.encode(s, NetworkType.TESTNET),
            isActive = active, createdAt = 0L, lastActiveAt = 0L,
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = WalletPreferences(context, NoopLogger)
        prefs.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        prefs.setPriceServiceEnabled(false)
        prefs.setUpdateServiceEnabled(false)
        runBlocking {
            db.walletDao().insert(entity("A", "aa", true))
            db.walletDao().insert(entity("B", "bb", false))
        }
        prefs.setActiveWalletId("A")
        prefs.setSyncMode(SyncMode.RECENT, walletId = "A")
        prefs.setSyncMode(SyncMode.FULL_HISTORY, walletId = "B")

        val realKeyManager = KeyManager(context, MnemonicManager(), NoopLogger)
        val keyManager = mockk<KeyManager>(relaxed = true)
        coEvery { keyManager.hasWallet() } returns true
        every { keyManager.deriveWalletInfoFromEntity(any()) } answers {
            realKeyManager.deriveWalletInfoFromEntity(firstArg())
        }
        val nodeLifecycle = mockk<NodeLifecycle>(relaxed = true)
        every { nodeLifecycle.currentNetwork } returns NetworkType.TESTNET
        every { nodeLifecycle.network } returns MutableStateFlow(NetworkType.TESTNET)
        every { nodeLifecycle.isSwitchingNetwork } returns MutableStateFlow(false)
        coEvery { nodeLifecycle.awaitNodeReady() } returns true
        val startupReachedNode = CompletableDeferred<Unit>()
        coEvery { nodeLifecycle.initializeNode(any(), any()) } answers { startupReachedNode.complete(Unit) }
        val coordinator = mockk<SyncCoordinator>(relaxed = true)
        every { coordinator.registeredActiveWalletId } returns null
        val syncEngine = mockk<SyncEngine>(relaxed = true)
        every { syncEngine.syncProgress } returns MutableStateFlow(SyncProgress())
        gateway = testGatewayRepository(
            db = db, walletPreferences = prefs, nodeLifecycle = nodeLifecycle, keyManager = keyManager,
            syncCoordinator = coordinator, syncEngine = syncEngine,
            // A real reader over relaxed stores: its reads fail into a real
            // Result.failure, which a MockK stub cannot return.
            ledgerReader = LedgerReader(
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), prefs, prefs, Json, NoopLogger,
            ),
        )
        runBlocking { withTimeout(10_000) { startupReachedNode.await() } }

        val mm = MnemonicManager()
        walletRepository = WalletRepository(
            db.walletDao(), keyManager, prefs, prefs, mm, db,
            db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(),
            db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(), db.keyMaterialDao(),
            db.subAccountCandidateDao(), SubAccountDiscovery(mm, keyManager), prefs, NoopLogger,
        )
    }

    @After
    fun tearDown() {
        // Home's coroutines run outside runTest here; an exception they threw
        // would otherwise surface in whichever runTest test runs next in this
        // JVM. An empty runTest reports it against this test instead. Main is
        // reset first: runTest would otherwise drive Home's endless timers.
        Dispatchers.resetMain()
        kotlinx.coroutines.test.runTest { }
        db.close()
    }

    private fun newViewModel(): HomeViewModel {
        val homeLight = mockk<LightClientReadOnly>(relaxed = true)
        coEvery { homeLight.getPeers() } coAnswers {
            refreshes.incrementAndGet()
            null
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        return HomeViewModel(
            repository = gateway,
            priceRepository = PriceRepository(mockk(relaxed = true), Json, context, NoopLogger, prefs),
            walletRepository = walletRepository,
            // Real repositories with their services off: they answer with a
            // real Result.failure, which a MockK stub cannot return (#563).
            updateRepository = UpdateRepository(mockk(relaxed = true), Json, NoopLogger, prefs),
            updateDownloader = mockk(relaxed = true),
            pinManager = mockk(relaxed = true),
            authManager = mockk(relaxed = true),
            cacheManager = mockk(relaxed = true),
            lightClient = homeLight,
            walletPreferences = prefs,
            seedPhraseAuthorizer = mockk(relaxed = true),
            keyMaterialDao = db.keyMaterialDao(),
            pendingBroadcastDao = db.pendingBroadcastDao(),
            savedStateHandle = SavedStateHandle(),
            logger = NoopLogger,
        )
    }

    /** Runs Main's queue (never advancing virtual time) until [condition], then lets it settle. */
    private fun settle(condition: () -> Boolean = { true }) {
        repeat(300) {
            scheduler.runCurrent()
            if (condition()) return@repeat
            Thread.sleep(10)
        }
        assertTrue("condition not reached", condition())
        // Let any further queued work (a second refresh) run too.
        repeat(50) {
            scheduler.runCurrent()
            Thread.sleep(10)
        }
    }

    private fun HomeViewModel.started() = settle { uiState.value.walletInfo != null && refreshes.get() > 0 }

    private fun broadcast(hash: String, walletId: String) = runBlocking {
        db.pendingBroadcastDao().insert(
            PendingBroadcastEntity(
                txHash = hash, walletId = walletId, network = NetworkType.TESTNET.name,
                signedTxJson = "{}", reservedInputs = "[]", state = "BROADCAST",
                submittedAtTipBlock = 1L, nullCount = 0, createdAt = 0L, lastCheckedAt = 0L,
            )
        )
    }

    private fun HomeViewModel.broadcastHashes() = uiState.value.broadcastStates.keys

    /**
     * Codex P2 on #565: the broadcast rows were keyed on the network only and
     * read the active wallet id once, so after a wallet change Home kept the
     * old wallet's in-flight sends and never showed the new wallet's.
     */
    @Test
    fun `after Home's own switch the new wallet's broadcasts show and the old one's do not`() {
        broadcast("0xa1", "A")
        broadcast("0xb1", "B")
        val vm = newViewModel()
        vm.started()
        settle { vm.broadcastHashes() == setOf("0xa1") }

        vm.switchWallet("B")

        settle { vm.broadcastHashes() == setOf("0xb1") }
    }

    @Test
    fun `after a switch made elsewhere the new wallet's broadcasts show and the old one's do not`() {
        broadcast("0xa1", "A")
        broadcast("0xb1", "B")
        val vm = newViewModel()
        vm.started()
        settle { vm.broadcastHashes() == setOf("0xa1") }

        runBlocking {
            walletRepository.switchActiveWallet("B")
            gateway.onActiveWalletChanged(db.walletDao().getById("B")!!)
        }

        settle { vm.broadcastHashes() == setOf("0xb1") }
    }

    @Test
    fun `switching wallets from Home refreshes once`() {
        val vm = newViewModel()
        vm.started()
        val before = refreshes.get()

        vm.switchWallet("B")
        settle { !vm.uiState.value.isSwitchingWallet && vm.uiState.value.walletInfo?.script == script("bb") }

        assertEquals(1, refreshes.get() - before)
        assertEquals(SyncMode.FULL_HISTORY, vm.uiState.value.currentSyncMode)
    }

    @Test
    fun `a switch made elsewhere refreshes once and updates the sync mode`() {
        val vm = newViewModel()
        vm.started()
        val before = refreshes.get()

        runBlocking {
            walletRepository.switchActiveWallet("B")
            gateway.onActiveWalletChanged(db.walletDao().getById("B")!!)
        }
        settle { vm.uiState.value.walletInfo?.script == script("bb") && refreshes.get() > before }

        assertEquals(1, refreshes.get() - before)
        assertEquals(SyncMode.FULL_HISTORY, vm.uiState.value.currentSyncMode)
    }
}
