package com.rjnr.pocketnode.ui.screens.onboarding

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.restorehint.RestoreHintAccount
import com.rjnr.pocketnode.data.restorehint.RestoreHintCodec
import com.rjnr.pocketnode.data.restorehint.RestoreHintDiscovery
import com.rjnr.pocketnode.data.restorehint.RestoreHintImporter
import com.rjnr.pocketnode.data.restorehint.RestoreHintKind
import com.rjnr.pocketnode.data.restorehint.RestoreHintPayload
import com.rjnr.pocketnode.data.restorehint.RestoreHintSecret
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
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
import kotlinx.coroutines.test.TestScope
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
 * #559: a restore hint in the onboarding import. A verified hint applies its
 * start through the ordinary resync and marks no safety flag; a wrong file
 * changes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MnemonicImportViewModelRestoreHintTest {

    private val testDispatcher = StandardTestDispatcher()
    private val words = List(11) { "abandon" } + "about"
    private lateinit var db: AppDatabase
    private lateinit var prefs: WalletPreferences
    private lateinit var importer: RestoreHintImporter
    private lateinit var walletRepository: WalletRepository
    private val repository = mockk<GatewayRepository>(relaxed = true)
    private val walletKeyWriter = mockk<WalletKeyWriter>()
    private val authManager = mockk<AuthManager>(relaxed = true)
    private val activity = mockk<FragmentActivity>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A real WalletRepository: MockK cannot stub its kotlin.Result returns
        // (see AddWalletViewModelImportSyncTest). Same-thread Room executors so
        // advanceUntilIdle sees every hop.
        val immediate = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(immediate)
            .setTransactionExecutor(immediate)
            .build()
        prefs = WalletPreferences(context, NoopLogger)
        val mm = MnemonicManager()
        val keyManager = KeyManager(context, mm, NoopLogger)
        val migrationPrefs = context.getSharedPreferences("restore_hint_vm_test_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        keyManager.keyStoreMigrationHelper = KeyStoreMigrationHelper(
            db.keyMaterialDao(), KeystoreEncryptionManager.createForTest(), migrationPrefs, NoopLogger,
        )
        val discovery = SubAccountDiscovery(mm, keyManager)
        walletRepository = WalletRepository(
            db.walletDao(), keyManager, prefs, prefs, mm, db,
            db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(),
            db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(), db.keyMaterialDao(),
            db.subAccountCandidateDao(), discovery, prefs, NoopLogger,
        )
        importer = RestoreHintImporter(db.subAccountCandidateDao(), discovery, prefs, NoopLogger)
        coEvery { walletKeyWriter.persistNewWalletV1Fallback(any(), any(), any(), any()) } returns
            WalletKeyWriter.Result.Success
        every { authManager.isBiometricEnrolled() } returns false
        every { authManager.hasDeviceCredential() } returns false
        every { repository.syncProgress } returns MutableStateFlow(SyncProgress())
        every { repository.currentNetwork } returns NetworkType.TESTNET
        coEvery { repository.resyncAccount(any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun newViewModel() = MnemonicImportViewModel(
        repository = repository,
        mnemonicManager = MnemonicManager(),
        walletRepository = walletRepository,
        walletKeyWriter = walletKeyWriter,
        authManager = authManager,
        logger = NoopLogger,
        restoreHintImporter = importer,
    )

    private fun hintFile(phrase: List<String> = words, network: NetworkType = NetworkType.TESTNET) =
        RestoreHintCodec.seal(
            RestoreHintPayload(
                network = network.name,
                createdAtMs = 0L,
                tipHeight = 19_000_000L,
                tipHash = "0x11",
                kind = RestoreHintKind.MNEMONIC,
                accounts = listOf(RestoreHintAccount(0, 18_000_000L, 17_500_000L, "CUSTOM")),
                discovery = RestoreHintDiscovery(found = listOf(14), highestScanned = 14),
            ),
            RestoreHintSecret.fromMnemonic(phrase),
        )

    /** The seed is derived on Dispatchers.Default, which advanceUntilIdle cannot see. */
    private fun TestScope.drainUntil(condition: () -> Boolean) {
        for (attempt in 0 until 500) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private fun TestScope.importedViewModel(): MnemonicImportViewModel {
        val vm = newViewModel()
        vm.pasteMnemonic(words.joinToString(" "))
        vm.importMnemonic(activity)
        drainUntil { vm.uiState.value.canUseRestoreHint }
        assertTrue(vm.uiState.value.showSyncModeDialog)
        assertTrue(vm.uiState.value.canUseRestoreHint)
        return vm
    }

    @Test
    fun `a verified hint applies its custom start through the ordinary resync`() = runTest {
        val vm = importedViewModel()

        vm.onRestoreHintPicked(hintFile())
        val plan = vm.uiState.value.restoreHintPlan!!
        assertEquals(17_499_000L, plan.startBlock)
        assertEquals(18_000_000L, plan.sourceCoverageStart)

        vm.confirmRestoreHint()
        drainUntil { vm.uiState.value.importSuccess }

        coVerify(exactly = 1) { repository.resyncAccount(SyncMode.CUSTOM, 17_499_000L) }
        assertTrue(vm.uiState.value.importSuccess)
        val walletId = db.walletDao().getActive()!!.walletId
        // Discovery seeded index 14 (beyond the import window) as an ordinary
        // PENDING candidate; nothing is claimed FOUND.
        val accountAxis = db.subAccountCandidateDao().getForParent(walletId).filter { it.accountIndex > 0 }
        assertEquals((1..10).toList() + 14, accountAxis.map { it.accountIndex })
        assertTrue(accountAxis.all { it.state == "PENDING" })
        // No safety flag set by the hint path.
        assertFalse(prefs.hasCompletedInitialSync(walletId = walletId))
        assertFalse(prefs.isZeroCellRescanDone(walletId))
        // The held secret is released once the sheet resolves.
        assertFalse(vm.uiState.value.canUseRestoreHint)
    }

    @Test
    fun `a hint from another phrase is rejected and changes nothing`() = runTest {
        val vm = importedViewModel()
        val other = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

        vm.onRestoreHintPicked(hintFile(phrase = other))
        advanceUntilIdle()

        assertNull(vm.uiState.value.restoreHintPlan)
        assertEquals(RestoreHintImporter.Reason.NOT_THIS_WALLET, vm.uiState.value.restoreHintError)
        assertTrue(vm.uiState.value.showSyncModeDialog)
        coVerify(exactly = 0) { repository.resyncAccount(any(), any()) }
        val walletId = db.walletDao().getActive()!!.walletId
        assertEquals(
            (1..10).toList(),
            db.subAccountCandidateDao().getForParent(walletId).filter { it.accountIndex > 0 }.map { it.accountIndex },
        )
    }

    @Test
    fun `other network and unreadable files get their own messages`() = runTest {
        val vm = importedViewModel()
        vm.onRestoreHintPicked(hintFile(network = NetworkType.MAINNET))
        assertEquals(RestoreHintImporter.Reason.OTHER_NETWORK, vm.uiState.value.restoreHintError)
        vm.onRestoreHintPicked(null)
        assertEquals(RestoreHintImporter.Reason.UNREADABLE, vm.uiState.value.restoreHintError)
        // A normal pick still works afterwards.
        vm.onSyncModeSelected(SyncMode.RECENT, null)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.resyncAccount(SyncMode.RECENT, null) }
        assertNull(vm.uiState.value.restoreHintError)
    }
}
