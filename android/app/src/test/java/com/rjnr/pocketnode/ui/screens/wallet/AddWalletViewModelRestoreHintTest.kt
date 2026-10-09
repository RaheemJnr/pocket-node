package com.rjnr.pocketnode.ui.screens.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
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
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #559 / #561 review F2: the Add Wallet import with a restore hint. The file is
 * picked on the import form before any secret exists (opening the picker can
 * start a re-auth lock), then verified against the freshly derived secret.
 * Real WalletRepository, same fixture as AddWalletViewModelImportSyncTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddWalletViewModelRestoreHintTest {

    private val testDispatcher = StandardTestDispatcher()
    private val phrase = List(11) { "abandon" } + "about"
    private val privateKey = ByteArray(32) { (it + 1).toByte() }
    private lateinit var db: AppDatabase
    private lateinit var prefs: WalletPreferences
    private lateinit var walletRepository: WalletRepository
    private lateinit var importer: RestoreHintImporter
    private val gatewayRepository = mockk<GatewayRepository>(relaxed = true)
    private val walletKeyWriter = mockk<WalletKeyWriter>()
    private val activity = mockk<FragmentActivity>(relaxed = true)

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
        prefs = WalletPreferences(context, NoopLogger)
        val mm = MnemonicManager()
        val keyManager = KeyManager(context, mm, NoopLogger)
        val migrationPrefs = context.getSharedPreferences("add_wallet_hint_test_migration", Context.MODE_PRIVATE)
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
        importer.computeDispatcher = Dispatchers.Unconfined
        coEvery { walletKeyWriter.persistNewWalletV1Fallback(any(), any(), any(), any()) } returns
            WalletKeyWriter.Result.Success
        every { gatewayRepository.syncProgress } returns MutableStateFlow(SyncProgress())
        every { gatewayRepository.currentNetwork } returns NetworkType.TESTNET
        coEvery { gatewayRepository.resyncAccount(any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun newViewModel() = AddWalletViewModel(
        savedStateHandle = SavedStateHandle(),
        walletRepository = walletRepository,
        gatewayRepository = gatewayRepository,
        mnemonicManager = MnemonicManager(),
        walletKeyReader = mockk(relaxed = true),
        walletKeyWriter = walletKeyWriter,
        authManager = mockk(relaxed = true),
        logger = NoopLogger,
        restoreHintImporter = importer,
    )

    private fun hint(secret: RestoreHintSecret, kind: String = RestoreHintKind.MNEMONIC) = RestoreHintCodec.seal(
        RestoreHintPayload(
            network = "TESTNET",
            createdAtMs = 0L,
            tipHeight = 19_000_000L,
            tipHash = "0x11",
            kind = kind,
            accounts = listOf(RestoreHintAccount(0, 18_000_000L, 17_500_000L, "CUSTOM")),
            discovery = RestoreHintDiscovery(found = if (kind == RestoreHintKind.MNEMONIC) listOf(13) else emptyList()),
        ),
        secret,
    )

    private fun TestScope.drainUntil(condition: () -> Boolean) {
        repeat(500) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private fun TestScope.hintResolved(vm: AddWalletViewModel) =
        drainUntil { vm.uiState.value.restoreHintPlan != null || vm.uiState.value.restoreHintError != null }

    @Test
    fun `a phrase import with a hint picked before a re-auth lock applies the hint's start`() = runTest {
        val vm = newViewModel()
        vm.onRestoreHintFilePicked(hint(RestoreHintSecret.fromMnemonic(phrase)))
        ReauthLockEvents.onLocked() // the picker stopped the activity
        advanceUntilIdle()
        vm.updateName("Restored")
        phrase.forEachIndexed { i, w -> vm.updateImportWord(i, w) }
        vm.importMnemonic(activity, consented = true)
        hintResolved(vm)

        assertEquals(17_499_000L, vm.uiState.value.restoreHintPlan?.startBlock)
        vm.confirmRestoreHint()
        drainUntil { vm.uiState.value.createdWallet != null }

        coVerify(exactly = 1) { gatewayRepository.resyncAccount(SyncMode.CUSTOM, 17_499_000L) }
        val walletId = vm.uiState.value.createdWallet!!.walletId
        val seeded = db.subAccountCandidateDao().getForParent(walletId).filter { it.accountIndex == 13 }
        assertEquals(listOf("PENDING"), seeded.map { it.state })
        assertFalse(prefs.hasCompletedInitialSync(walletId = walletId))
        assertFalse(vm.uiState.value.hasRestoreHintFile)
    }

    @Test
    fun `a re-auth lock while the secret is being derived discards it and the result`() = runTest {
        val vm = newViewModel()
        var derived: RestoreHintSecret? = null
        vm.deriveMnemonicHintSecret = { words ->
            RestoreHintSecret.fromMnemonic(words).also {
                derived = it
                ReauthLockEvents.onLocked()
            }
        }
        vm.onRestoreHintFilePicked(hint(RestoreHintSecret.fromMnemonic(phrase)))
        vm.updateName("Restored")
        phrase.forEachIndexed { i, w -> vm.updateImportWord(i, w) }
        vm.importMnemonic(activity, consented = true)
        drainUntil { derived != null && vm.uiState.value.showSyncModeDialog }
        advanceUntilIdle()

        assertNull(vm.uiState.value.restoreHintPlan)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { derived!!.copySeed() }
    }

    @Test
    fun `a private key import verifies the hint against the key`() = runTest {
        val vm = newViewModel()
        vm.onRestoreHintFilePicked(hint(RestoreHintSecret.fromPrivateKey(privateKey), RestoreHintKind.RAW_KEY))
        vm.updateName("Key")
        vm.updateImportPrivateKey(privateKey.joinToString("") { "%02x".format(it) })
        vm.importRawKey(activity, consented = true)
        hintResolved(vm)

        assertNotNull(vm.uiState.value.restoreHintPlan)
        assertEquals(17_499_000L, vm.uiState.value.restoreHintPlan!!.startBlock)
    }

    @Test
    fun `a hint from another phrase is rejected and the normal choices still work`() = runTest {
        val other = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")
        val vm = newViewModel()
        vm.onRestoreHintFilePicked(hint(RestoreHintSecret.fromMnemonic(other)))
        vm.updateName("Restored")
        phrase.forEachIndexed { i, w -> vm.updateImportWord(i, w) }
        vm.importMnemonic(activity, consented = true)
        hintResolved(vm)

        assertNull(vm.uiState.value.restoreHintPlan)
        assertEquals(RestoreHintImporter.Reason.NOT_THIS_WALLET, vm.uiState.value.restoreHintError)
        vm.onSyncModeSelected(SyncMode.RECENT, null)
        drainUntil { vm.uiState.value.createdWallet != null }
        coVerify(exactly = 1) { gatewayRepository.resyncAccount(SyncMode.RECENT, null) }
    }
}
