package com.rjnr.pocketnode.ui.screens.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.crypto.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.MIGRATION_2_3
import com.rjnr.pocketnode.data.database.MIGRATION_3_4
import com.rjnr.pocketnode.data.database.MIGRATION_4_5
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * #431: covers `AddWalletViewModel`'s post-import sync-mode sheet against a
 * REAL [WalletRepository] (Room in-memory DB, same fixture as
 * `WalletRepositoryTest`), not a mocked one.
 *
 * `WalletRepository.importFromMnemonic` returns `kotlin.Result<WalletEntity>`,
 * and stubbing that return value on a MockK mock crashes when the suspend
 * continuation resumes (`ClassCastException: kotlin.Result cannot be cast to
 * WalletEntity`), the same MockK/inline-value-class limitation already
 * documented in `AddWalletViewModelTest` for `WalletRepository.createSubAccount`.
 * Running the import through the real repository sidesteps it entirely, since
 * there is no stubbed `Result` for MockK to reconstitute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddWalletViewModelImportSyncTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var keyManager: KeyManager
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var mnemonicManager: MnemonicManager
    private lateinit var walletRepository: WalletRepository
    private lateinit var gatewayRepository: GatewayRepository
    private lateinit var walletKeyReader: WalletKeyReader
    private lateinit var walletKeyWriter: WalletKeyWriter
    private lateinit var authManager: com.rjnr.pocketnode.data.auth.AuthManager
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val context = ApplicationProvider.getApplicationContext<Context>()
        // Room's generated suspend DAOs hop onto its own query/transaction
        // executor (a real background thread pool) independent of
        // `Dispatchers.setMain`. Since the ViewModel drives the import through
        // `viewModelScope.launch { }` + `advanceUntilIdle()` (not a directly
        // awaited suspend call, unlike WalletRepositoryTest), that real hop is
        // a race `advanceUntilIdle()` can't see, it only drains the Main test
        // dispatcher's queue. A same-thread executor makes Room's suspend
        // calls resolve synchronously so there is nothing left to race.
        val immediateExecutor = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .setQueryExecutor(immediateExecutor)
            .setTransactionExecutor(immediateExecutor)
            .build()
        mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager, NoopLogger)
        val encryptionManager = KeystoreEncryptionManager.createForTest()
        val migrationPrefs = context.getSharedPreferences("add_wallet_vm_test_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        keyManager.keyStoreMigrationHelper = KeyStoreMigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs, NoopLogger)
        walletPreferences = WalletPreferences(context, NoopLogger)
        walletRepository = WalletRepository(
            db.walletDao(), keyManager, walletPreferences, walletPreferences, mnemonicManager, db,
            db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(),
            db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(), db.keyMaterialDao(),
            db.subAccountCandidateDao(), SubAccountDiscovery(mnemonicManager, keyManager), walletPreferences, NoopLogger,
        )

        gatewayRepository = mockk(relaxed = true)
        every { gatewayRepository.syncProgress } returns MutableStateFlow(SyncProgress())

        walletKeyReader = mockk(relaxed = true)
        walletKeyWriter = mockk(relaxed = true)
        // Real WalletKeyWriter.Result.Success is a plain sealed-class object
        // (not a kotlin.Result), so stubbing it is safe.
        coEvery {
            walletKeyWriter.persistNewWalletV1Fallback(any(), any(), any(), any())
        } returns WalletKeyWriter.Result.Success

        authManager = mockk(relaxed = true)
        // No secure lock -> AddWalletViewModel routes persistWalletKeys through
        // the V1 fallback above instead of the Activity/BiometricPrompt path.
        every { authManager.isBiometricEnrolled() } returns false
        every { authManager.hasDeviceCredential() } returns false

        activity = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun newViewModel(
        gateway: GatewayRepository = gatewayRepository,
    ): AddWalletViewModel = AddWalletViewModel(
        savedStateHandle = SavedStateHandle(),
        walletRepository = walletRepository,
        gatewayRepository = gateway,
        mnemonicManager = mnemonicManager,
        walletKeyReader = walletKeyReader,
        walletKeyWriter = walletKeyWriter,
        authManager = authManager,
        logger = NoopLogger,
    )

    private fun kotlinx.coroutines.test.TestScope.advanceUntil(condition: () -> Boolean) {
        repeat(500) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private fun AddWalletViewModel.importValidMnemonic(name: String = "Restored") {
        val words = mnemonicManager.generateMnemonic(MnemonicManager.WordCount.TWELVE)
        updateName(name)
        words.forEachIndexed { index, word -> updateImportWord(index, word) }
        // consented=true: skip the no-lock consent dialog round trip, it is
        // covered elsewhere; this test is about the post-import sync sheet.
        importMnemonic(activity, consented = true)
    }

    @Test
    fun `successful mnemonic import shows the sync-mode sheet instead of navigating immediately`() = runTest {
        val vm = newViewModel()
        vm.importValidMnemonic()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showSyncModeDialog)
        assertNull(vm.uiState.value.createdWallet)
        assertNull(vm.uiState.value.error)
        coVerify(exactly = 1) { gatewayRepository.onActiveWalletChanged(any()) }
    }

    @Test
    fun `selecting a sync mode after import registers it and reveals createdWallet`() = runTest {
        val vm = newViewModel()
        vm.importValidMnemonic()
        advanceUntilIdle()
        val wallet = walletRepository.getActive()

        vm.onSyncModeSelected(SyncMode.RECENT, null)
        advanceUntilIdle()

        coVerify(exactly = 1) { gatewayRepository.resyncAccount(SyncMode.RECENT, null) }
        assertFalse(vm.uiState.value.showSyncModeDialog)
        assertEquals(wallet?.walletId, vm.uiState.value.createdWallet?.walletId)
    }

    @Test
    fun `selecting CUSTOM after import passes the block height`() = runTest {
        val vm = newViewModel()
        vm.importValidMnemonic()
        advanceUntilIdle()

        vm.onSyncModeSelected(SyncMode.CUSTOM, 12345L)
        advanceUntilIdle()

        coVerify(exactly = 1) { gatewayRepository.resyncAccount(SyncMode.CUSTOM, 12345L) }
        assertFalse(vm.uiState.value.showSyncModeDialog)
        assertTrue(vm.uiState.value.createdWallet != null)
    }

    @Test
    fun `dismissing the sync-mode sheet reveals createdWallet without a registration call`() = runTest {
        val vm = newViewModel()
        vm.importValidMnemonic()
        advanceUntilIdle()

        vm.skipSyncSelection()
        advanceUntilIdle()

        coVerify(exactly = 0) { gatewayRepository.resyncAccount(any(), any()) }
        assertFalse(vm.uiState.value.showSyncModeDialog)
        assertTrue(vm.uiState.value.createdWallet != null)
    }

    /**
     * Codex P2 on PR #532: resyncAccount returns its failure as a Result
     * rather than throwing, so the old try/catch treated a failed apply as
     * success and navigated away with the mode unapplied.
     *
     * MockK 1.13.16 cannot return a `Result.failure` from a suspend stub (the
     * inline-value-class boxing limitation described on this class: the
     * failure arrives double-boxed and reads as success), so the failure is
     * driven through a throw, which the ViewModel folds into the same
     * failure branch as a returned `Result.failure`.
     */
    @Test
    fun `a failed sync-mode apply keeps the sheet open and does not navigate`() = runTest {
        coEvery { gatewayRepository.resyncAccount(any(), any()) } throws
            IllegalStateException("Failed to set scripts")
        val vm = newViewModel()
        vm.importValidMnemonic()
        advanceUntilIdle()

        vm.onSyncModeSelected(SyncMode.FULL_HISTORY, null)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showSyncModeDialog)
        assertNull(vm.uiState.value.createdWallet)
        assertFalse(vm.uiState.value.isApplyingSyncChoice)
        assertTrue(vm.uiState.value.syncChoiceError != null)

        // The wallet stays pending: a retry that succeeds still navigates.
        coEvery { gatewayRepository.resyncAccount(any(), any()) } returns Result.success(Unit)
        vm.onSyncModeSelected(SyncMode.RECENT, null)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showSyncModeDialog)
        assertTrue(vm.uiState.value.createdWallet != null)
        assertNull(vm.uiState.value.syncChoiceError)
    }

    /**
     * Review S2: the same failure, but as a `Result.failure` the repository
     * actually RETURNS (not a throw into the catch backstop). A real
     * GatewayRepository under ACTIVE_ONLY whose node never becomes ready
     * returns `Result.failure("Node initialization failed")` from
     * resyncAccount, with no MockK boxing in between.
     */
    @Test
    fun `a returned Result failure keeps the sheet open and does not navigate`() = runTest {
        walletPreferences.setSyncStrategy(com.rjnr.pocketnode.core.prefs.SyncStrategy.ACTIVE_ONLY)
        val nodeLifecycle = mockk<com.rjnr.pocketnode.data.gateway.NodeLifecycle>(relaxed = true)
        coEvery { nodeLifecycle.awaitNodeReady() } returns false
        val syncEngine = mockk<com.rjnr.pocketnode.data.sync.SyncEngine>(relaxed = true)
        every { syncEngine.syncProgress } returns MutableStateFlow(SyncProgress())
        val realGateway = com.rjnr.pocketnode.data.gateway.testGatewayRepository(
            db = db,
            walletPreferences = walletPreferences,
            nodeLifecycle = nodeLifecycle,
            syncEngine = syncEngine,
        )
        // Sanity: the repository really returns a failure, not a throw.
        assertTrue(realGateway.resyncAccount(SyncMode.RECENT, null).isFailure)

        val vm = newViewModel(realGateway)
        vm.importValidMnemonic()
        // The real repository hops to Dispatchers.IO, which advanceUntilIdle
        // cannot see: keep draining the Main queue until the state settles.
        advanceUntil { vm.uiState.value.showSyncModeDialog }
        assertTrue(vm.uiState.value.showSyncModeDialog)

        vm.onSyncModeSelected(SyncMode.FULL_HISTORY, null)
        advanceUntil { !vm.uiState.value.isApplyingSyncChoice }

        assertTrue(vm.uiState.value.showSyncModeDialog)
        assertNull(vm.uiState.value.createdWallet)
        assertFalse(vm.uiState.value.isApplyingSyncChoice)
        assertTrue(vm.uiState.value.syncChoiceError != null)
    }

    /** Review N5: Apply with no pending wallet still resyncs and closes the sheet. */
    @Test
    fun `Apply with no pending wallet still resyncs and closes the sheet`() = runTest {
        coEvery { gatewayRepository.resyncAccount(any(), any()) } returns Result.success(Unit)
        val vm = newViewModel()

        vm.onSyncModeSelected(SyncMode.RECENT, null)
        advanceUntilIdle()

        coVerify(exactly = 1) { gatewayRepository.resyncAccount(SyncMode.RECENT, null) }
        assertFalse(vm.uiState.value.showSyncModeDialog)
        assertFalse(vm.uiState.value.isApplyingSyncChoice)
    }

    /**
     * Codex P2 on PR #532: a double tap on Apply launched two coroutines that
     * each consumed pendingImportedWallet; a later completion could publish
     * createdWallet = null over the first and strand navigation.
     */
    @Test
    fun `a double tap on Apply resyncs once and still reveals createdWallet`() = runTest {
        coEvery { gatewayRepository.resyncAccount(any(), any()) } returns Result.success(Unit)
        val vm = newViewModel()
        vm.importValidMnemonic()
        advanceUntilIdle()

        vm.onSyncModeSelected(SyncMode.RECENT, null)
        assertTrue(vm.uiState.value.isApplyingSyncChoice)
        vm.onSyncModeSelected(SyncMode.RECENT, null)
        // A dismiss while the apply is in flight is ignored as well.
        vm.skipSyncSelection()
        advanceUntilIdle()

        coVerify(exactly = 1) { gatewayRepository.resyncAccount(any(), any()) }
        assertFalse(vm.uiState.value.isApplyingSyncChoice)
        assertFalse(vm.uiState.value.showSyncModeDialog)
        assertTrue(vm.uiState.value.createdWallet != null)
    }

    /**
     * #431's actual bug: import must not silently keep the NEW_WALLET (tip)
     * default. This end-to-end path (real WalletRepository, real
     * WalletPreferences) confirms RECENT is what's on disk right after
     * import, before the user has touched the sheet at all.
     */
    @Test
    fun `sync mode is RECENT immediately after import, before the sheet is touched`() = runTest {
        val vm = newViewModel()
        vm.importValidMnemonic()
        advanceUntilIdle()

        val active = walletRepository.getActive()
        assertEquals(
            SyncMode.RECENT,
            walletPreferences.getSyncMode(com.rjnr.pocketnode.data.gateway.models.NetworkType.MAINNET, active?.walletId),
        )
    }
}
