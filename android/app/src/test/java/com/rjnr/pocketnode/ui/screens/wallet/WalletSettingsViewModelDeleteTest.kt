package com.rjnr.pocketnode.ui.screens.wallet

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.SyncStrategy
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.NodeLifecycle
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.testGatewayRepository
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * #563: deleting a wallet from Wallet Settings must move the gateway (what
 * Home, Settings and sync registration read) onto the new active wallet at
 * once, through the normal switch path, registered once and without the
 * deleted wallet's script.
 *
 * Real [WalletRepository] and real [GatewayRepository] over one in-memory
 * Room DB and one real [WalletPreferences]; only the light-client side
 * ([SyncCoordinator], [NodeLifecycle]) and key storage are mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSettingsViewModelDeleteTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var keyManager: KeyManager
    private lateinit var walletRepository: WalletRepository
    private lateinit var syncCoordinator: SyncCoordinator
    private lateinit var gateway: GatewayRepository

    /** For each set the coordinator was asked for: was the deleted wallet still in Room? */
    private val deletedStillPresentAtRegistration = Collections.synchronizedList(mutableListOf<Boolean>())
    private var deletedId: String? = null

    private fun script(byte: String) = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + byte.repeat(20),
    )

    private fun entity(id: String, byte: String, active: Boolean, parent: String? = null, lastActiveAt: Long = 0L) =
        script(byte).let { s ->
            WalletEntity(
                walletId = id, name = id, type = KeyManager.WALLET_TYPE_MNEMONIC,
                derivationPath = "m/44'/309'/0'/0/0", parentWalletId = parent,
                accountIndex = if (parent == null) 0 else 1,
                mainnetAddress = AddressUtils.encode(s, NetworkType.MAINNET),
                testnetAddress = AddressUtils.encode(s, NetworkType.TESTNET),
                isActive = active, createdAt = 0L, lastActiveAt = lastActiveAt,
            )
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val immediate = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(immediate)
            .setTransactionExecutor(immediate)
            .build()
        walletPreferences = WalletPreferences(context, NoopLogger)
        walletPreferences.setSyncStrategy(SyncStrategy.ALL_WALLETS)

        val realKeyManager = KeyManager(context, MnemonicManager(), NoopLogger)
        keyManager = mockk(relaxed = true)
        coEvery { keyManager.hasWallet() } returns true
        every { keyManager.deriveWalletInfoFromEntity(any()) } answers {
            realKeyManager.deriveWalletInfoFromEntity(firstArg())
        }

        val mnemonicManager = MnemonicManager()
        walletRepository = WalletRepository(
            db.walletDao(), keyManager, walletPreferences, walletPreferences, mnemonicManager, db,
            db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(),
            db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(), db.keyMaterialDao(),
            db.subAccountCandidateDao(), SubAccountDiscovery(mnemonicManager, keyManager), walletPreferences, NoopLogger,
        )

        syncCoordinator = mockk(relaxed = true)
        every { syncCoordinator.registeredActiveWalletId } returns null
        coEvery { syncCoordinator.registerAllWalletScripts(any(), any()) } answers {
            val id = deletedId
            if (id != null) deletedStillPresentAtRegistration += runBlocking { db.walletDao().getById(id) } != null
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /** Seeds the wallets, makes [activeId] active and loads it into the gateway. */
    private fun seed(activeId: String, vararg wallets: WalletEntity) = runBlocking {
        wallets.forEach { db.walletDao().insert(it.copy(isActive = it.walletId == activeId)) }
        walletPreferences.setActiveWalletId(activeId)
        val nodeLifecycle = mockk<NodeLifecycle>(relaxed = true)
        every { nodeLifecycle.currentNetwork } returns NetworkType.TESTNET
        coEvery { nodeLifecycle.awaitNodeReady() } returns true
        val startupReachedNode = CompletableDeferred<Unit>()
        coEvery { nodeLifecycle.initializeNode(any(), any()) } answers { startupReachedNode.complete(Unit) }
        gateway = testGatewayRepository(
            db = db,
            walletPreferences = walletPreferences,
            nodeLifecycle = nodeLifecycle,
            syncCoordinator = syncCoordinator,
            keyManager = keyManager,
        )
        withTimeout(10_000) { startupReachedNode.await() }
        gateway.onActiveWalletChanged(db.walletDao().getById(activeId)!!)
        clearMocks(syncCoordinator, answers = false)
    }

    private fun newViewModel(walletId: String) = WalletSettingsViewModel(
        savedStateHandle = SavedStateHandle(mapOf("walletId" to walletId)),
        walletRepository = walletRepository,
        gatewayRepository = gateway,
        walletDao = db.walletDao(),
        keyManager = keyManager,
        pinManager = mockk(relaxed = true),
        daoCellDao = db.daoCellDao(),
        transactionDao = db.transactionDao(),
        appStatePreferences = walletPreferences,
        networkPreferences = walletPreferences,
        walletKeyReader = mockk(relaxed = true),
        walletKeyWriter = mockk(relaxed = true),
        keyMaterialDao = db.keyMaterialDao(),
        migrationHelper = mockk(relaxed = true),
        encryptionManager = mockk(relaxed = true),
        authManager = mockk(relaxed = true),
        logger = NoopLogger,
    )

    private fun deleteViaSettings(walletId: String) = runTest(testDispatcher) {
        deletedId = walletId
        val vm = newViewModel(walletId)
        advanceUntilIdle()
        vm.confirmDelete()
        advanceUntilIdle()
        assertTrue("delete reported done", vm.uiState.value.deleted)
    }

    private fun assertRegisteredOnlyAfterDelete() {
        val seen = deletedStillPresentAtRegistration.toList()
        assertTrue("a registration ran", seen.isNotEmpty())
        assertTrue("no registration saw the deleted wallet: $seen", seen.none { it })
    }

    @Test
    fun `deleting the active wallet moves the gateway onto the new active wallet`() {
        seed(
            "C",
            entity("A", "aa", false, lastActiveAt = 1L),
            entity("B", "bb", false, lastActiveAt = 2L),
            entity("C", "cc", true, lastActiveAt = 3L),
        )
        walletPreferences.setSyncMode(SyncMode.NEW_WALLET, walletId = "A")
        walletPreferences.setSyncMode(SyncMode.NEW_WALLET, walletId = "B")
        walletPreferences.setSyncMode(SyncMode.FULL_HISTORY, walletId = "C")
        assertEquals(script("cc"), gateway.walletInfo.value?.script)

        deleteViaSettings("C")

        val active = runBlocking { db.walletDao().getActive() }!!
        assertNotEquals("C", active.walletId)
        assertNull(runBlocking { db.walletDao().getById("C") })
        // What Home renders (address, balance owner) and what Settings' sync
        // options and a mode change read: the new active wallet, not C.
        assertEquals(active.testnetAddress, gateway.walletInfo.value?.testnetAddress)
        assertEquals(SyncMode.NEW_WALLET, gateway.getSavedSyncMode())
        // Registered only after the row was gone, so C's script is not in the
        // set. (That it lands exactly one set is pinned with the real
        // coordinator in RegistrationLockTest; this mock never reports a
        // landed set, so the #539 follow-up re-asks.)
        assertRegisteredOnlyAfterDelete()
    }

    @Test
    fun `deleting a non-active wallet keeps the active wallet and re-registers once without it`() {
        seed(
            "A",
            entity("A", "aa", true, lastActiveAt = 3L),
            entity("B", "bb", false, lastActiveAt = 2L),
        )
        val before = gateway.walletInfo.value

        deleteViaSettings("B")

        assertEquals("A", runBlocking { db.walletDao().getActive() }?.walletId)
        assertEquals(before, gateway.walletInfo.value)
        assertEquals(listOf(false), deletedStillPresentAtRegistration.toList())
    }

    @Test
    fun `deleting a non-active wallet under ACTIVE_ONLY does not touch registration`() {
        walletPreferences.setSyncStrategy(SyncStrategy.ACTIVE_ONLY)
        seed(
            "A",
            entity("A", "aa", true, lastActiveAt = 3L),
            entity("B", "bb", false, lastActiveAt = 2L),
        )
        val before = gateway.walletInfo.value

        deleteViaSettings("B")

        assertEquals(before, gateway.walletInfo.value)
        coVerify(exactly = 0) { syncCoordinator.registerAllWalletScripts(any(), any()) }
        coVerify(exactly = 0) { syncCoordinator.setScriptsAndRecord(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deleting the active sub-account moves the gateway onto a parent wallet`() {
        seed(
            "S",
            entity("A", "aa", false, lastActiveAt = 1L),
            entity("S", "dd", true, parent = "A", lastActiveAt = 3L),
        )
        assertEquals(script("dd"), gateway.walletInfo.value?.script)

        deleteViaSettings("S")

        assertEquals("A", runBlocking { db.walletDao().getActive() }?.walletId)
        assertEquals(script("aa"), gateway.walletInfo.value?.script)
        assertRegisteredOnlyAfterDelete()
    }
}
