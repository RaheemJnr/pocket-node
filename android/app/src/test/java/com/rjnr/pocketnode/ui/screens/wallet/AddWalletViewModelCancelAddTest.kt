package com.rjnr.pocketnode.ui.screens.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.crypto.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.models.NetworkType
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
import com.rjnr.pocketnode.ui.util.UiMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #563: Cancel on the post-import sync sheet of Add Wallet removes the wallet
 * the import just added (its rows and keys, nothing of any other wallet) and
 * hands the app back to the previously active wallet. Real [WalletRepository]
 * over in-memory Room with real key_material rows; the gateway is mocked
 * (its switch on a deletion is covered by WalletSettingsViewModelDeleteTest
 * and RegistrationLockTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddWalletViewModelCancelAddTest {
    private val nativeInput = com.rjnr.pocketnode.ui.security.NativeImportFixture()

    private val testDispatcher = StandardTestDispatcher()
    private val phrase = List(11) { "abandon" } + "about"
    private lateinit var db: AppDatabase
    private lateinit var prefs: WalletPreferences
    private lateinit var keyManager: KeyManager
    private lateinit var walletRepository: WalletRepository
    private lateinit var importer: RestoreHintImporter
    private lateinit var v2Helper: KeystoreV2MigrationHelper
    private lateinit var encryptionManager: KeystoreEncryptionManager
    private val gatewayRepository = mockk<GatewayRepository>(relaxed = true)
    private val walletKeyWriter = mockk<WalletKeyWriter>()
    private val activity = org.robolectric.Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    private suspend fun persist(walletId: WalletRepository.Creation, bundle: WalletKeyBundle, type: String): WalletKeyWriter.Result =
        com.rjnr.pocketnode.data.wallet.persistVerifiedFixture(db, walletId, bundle)

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
        keyManager = KeyManager(context, mm, NoopLogger)
        encryptionManager = KeystoreEncryptionManager.createForTest()
        val migrationPrefs = context.getSharedPreferences("add_wallet_cancel_test_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        keyManager.keyStoreMigrationHelper = KeyStoreMigrationHelper(
            db.keyMaterialDao(), encryptionManager, migrationPrefs, NoopLogger, database = db)
        v2Helper = KeystoreV2MigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs, logger = NoopLogger, database = db)
        val discovery = SubAccountDiscovery(mm, keyManager)
        walletRepository = WalletRepository(
            db.walletDao(), keyManager, prefs, prefs, mm, db,
            db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(),
            db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(), db.keyMaterialDao(),
            db.subAccountCandidateDao(), discovery, prefs, NoopLogger,
        )
        importer = RestoreHintImporter(db.subAccountCandidateDao(), discovery, prefs, NoopLogger)
        importer.computeDispatcher = Dispatchers.Unconfined
        coEvery { walletKeyWriter.persistCreatedWallet(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            persist(secondArg(), thirdArg(), arg(3))
        }
        every { gatewayRepository.syncProgress } returns MutableStateFlow(SyncProgress())
        every { gatewayRepository.currentNetwork } returns NetworkType.TESTNET
        coEvery { gatewayRepository.resyncAccount(any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun newViewModel(repo: WalletRepository = walletRepository) = AddWalletViewModel(
        savedStateHandle = SavedStateHandle(),
        walletRepository = repo,
        gatewayRepository = gatewayRepository,
        mnemonicManager = MnemonicManager(),
        walletKeyReader = mockk(relaxed = true),
        walletKeyWriter = walletKeyWriter,
        authManager = mockk(relaxed = true),
        logger = NoopLogger,
        restoreHintImporter = importer,
        importOperations = nativeInput.operations(walletRepository, walletKeyWriter, MnemonicManager(), importer),
    )

    /** Two existing wallets with keys and a sync_progress row each; [first] stays active. */
    private suspend fun seedExisting(): Pair<String, String> {
        val second = walletRepository.createWallet(
            name = "Second",
            persistKeys = { id, bundle -> persist(id, bundle, KeyManager.WALLET_TYPE_MNEMONIC) },
        ).getOrThrow().walletId
        val first = walletRepository.createWallet(
            name = "First",
            persistKeys = { id, bundle -> persist(id, bundle, KeyManager.WALLET_TYPE_MNEMONIC) },
        ).getOrThrow().walletId
        for (id in listOf(first, second)) {
            db.syncProgressDao().upsert(SyncProgressEntity(id, NetworkType.TESTNET.name, 1L, 1L, 0L))
        }
        assertEquals(first, walletRepository.getActive()?.walletId)
        return first to second
    }

    private fun TestScope.drainUntil(condition: () -> Boolean) {
        repeat(500) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private fun AddWalletViewModel.importPhrase() {
        updateName("Restored")
        phrase.forEachIndexed { i, w -> nativeInput.word(i, w) }
        importMnemonic(activity)
    }

    private suspend fun assertOnlyAddedWalletRemoved(first: String, second: String, added: String) {
        assertNull(db.walletDao().getById(added))
        assertNull(db.keyMaterialDao().getByWalletId(added))
        assertTrue(db.subAccountCandidateDao().getForParent(added).isEmpty())
        assertNull(db.syncProgressDao().get(added, NetworkType.TESTNET.name))
        for (id in listOf(first, second)) {
            assertNotNull(db.walletDao().getById(id))
            assertNotNull(db.keyMaterialDao().getByWalletId(id))
            assertNotNull(db.syncProgressDao().get(id, NetworkType.TESTNET.name))
        }
        assertEquals(first, walletRepository.getActive()?.walletId)
        assertEquals(first, prefs.getActiveWalletId())
        assertEquals(2, db.walletDao().count())
    }

    @Test
    fun `cancel joins pending registration before rollback and never adopts the removed wallet late`() = runTest {
        val (first, second) = seedExisting()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var started = false
        var cancelled = false
        var lateAdoption = false
        coEvery { gatewayRepository.onActiveWalletChanged(any()) } coAnswers {
            started = true
            try { release.await(); lateAdoption = true } finally { cancelled = true }
        }
        val vm = newViewModel(); vm.importPhrase(); drainUntil { started }
        try {
            assertTrue(vm.uiState.value.showSyncModeDialog)
            val added = walletRepository.getActive()!!.walletId
            vm.cancelAddedWallet(); drainUntil { !vm.uiState.value.showSyncModeDialog }
            assertTrue(cancelled)
            release.complete(Unit); advanceUntilIdle()
            assertFalse(lateAdoption)
            assertOnlyAddedWalletRemoved(first, second, added)
            coVerify(exactly = 1) { gatewayRepository.onWalletDeleted(added) }
        } finally { release.complete(Unit); advanceUntilIdle() }
    }

    @Test
    fun `cancel removes only the added wallet and restores the previously active one`() = runTest {
        val (first, second) = seedExisting()
        val vm = newViewModel()
        vm.importPhrase()
        drainUntil { vm.uiState.value.showSyncModeDialog }
        val added = walletRepository.getActive()!!.walletId
        assertTrue(added != first)
        assertTrue(vm.uiState.value.cancelRemovesWallet)
        // The import recorded discovery slots and the poll a progress row for it.
        assertTrue(db.subAccountCandidateDao().getForParent(added).isNotEmpty())
        db.syncProgressDao().upsert(SyncProgressEntity(added, NetworkType.TESTNET.name, 1L, 1L, 0L))

        vm.cancelAddedWallet()
        drainUntil { !vm.uiState.value.showSyncModeDialog }

        assertOnlyAddedWalletRemoved(first, second, added)
        coVerify(exactly = 1) { gatewayRepository.onWalletDeleted(added) }
        coVerify(exactly = 0) { gatewayRepository.resyncAccount(any(), any()) }
        assertNull("stays on Add Wallet, no navigation", vm.uiState.value.createdWallet)
        assertNull(vm.uiState.value.syncChoiceError)
        assertFalse(vm.uiState.value.cancelRemovesWallet)
        // F6: the abandoned add leaves nothing the user typed behind.
        assertTrue(nativeInput.issued.all { chars -> chars.all { it == '\u0000' } })
        assertEquals("", vm.uiState.value.name)
    }

    /** #563 review F6: a cancelled private-key add clears the typed key and the picked hint file. */
    @Test
    fun `cancel after a private key import clears the typed key and the picked hint`() = runTest {
        seedExisting()
        val vm = newViewModel()
        vm.onRestoreHintFilePicked("{}")
        vm.updateName("Key")
        nativeInput.key(ByteArray(32) { (it + 1).toByte() }.joinToString("") { "%02x".format(it) })
        vm.importRawKey(activity)
        drainUntil { vm.uiState.value.showSyncModeDialog }
        assertTrue(vm.uiState.value.cancelRemovesWallet)

        vm.cancelAddedWallet()
        drainUntil { !vm.uiState.value.showSyncModeDialog }

        assertTrue(nativeInput.issued.all { chars -> chars.all { it == '\u0000' } })
        assertEquals("", vm.uiState.value.name)
        assertFalse(vm.uiState.value.hasRestoreHintFile)
        assertNull(vm.uiState.value.restoreHintError)
        assertEquals(2, db.walletDao().count())
    }

    /**
     * #563 review F2: leaving the screen (viewModelScope cancelled) right
     * after the rollback committed must still switch the gateway back.
     */
    @Test
    fun `leaving the screen right after the rollback commits still switches the gateway back`() = runTest {
        val (first, second) = seedExisting()
        lateinit var vm: AddWalletViewModel
        val spy = spyk(walletRepository)
        coEvery { spy.rollbackAddedWallet(any(), any()) } coAnswers {
            val result = callOriginal()
            vm.viewModelScope.cancel()
            // Committed; cancellation lands at the next suspension point.
            kotlinx.coroutines.yield()
            result
        }
        vm = newViewModel(spy)
        vm.importPhrase()
        drainUntil { vm.uiState.value.showSyncModeDialog }
        val added = walletRepository.getActive()!!.walletId

        vm.cancelAddedWallet()
        advanceUntilIdle()

        assertOnlyAddedWalletRemoved(first, second, added)
        coVerify(exactly = 1) { gatewayRepository.onWalletDeleted(added) }
    }

    @Test
    fun `cancel after a confirmed restore hint whose resync failed also rolls back`() = runTest {
        val (first, second) = seedExisting()
        coEvery { gatewayRepository.resyncAccount(any(), any()) } throws IllegalStateException("refused")
        val vm = newViewModel()
        vm.onRestoreHintFilePicked(
            RestoreHintCodec.seal(
                RestoreHintPayload(
                    network = "TESTNET", createdAtMs = 0L, tipHeight = 19_000_000L, tipHash = "0x11",
                    kind = RestoreHintKind.MNEMONIC,
                    accounts = listOf(RestoreHintAccount(0, 18_000_000L, 17_500_000L, "CUSTOM")),
                    discovery = RestoreHintDiscovery(found = listOf(13)),
                ),
                RestoreHintSecret.fromMnemonic(phrase),
            )
        )
        vm.importPhrase()
        drainUntil { vm.uiState.value.restoreHintPlan != null }
        val added = walletRepository.getActive()!!.walletId
        vm.confirmRestoreHint()
        drainUntil { vm.uiState.value.syncChoiceError != null }
        // The hint seeded its discovery slot before the resync was refused.
        assertTrue(db.subAccountCandidateDao().getForParent(added).any { it.accountIndex == 13 })
        assertTrue(vm.uiState.value.cancelRemovesWallet)

        vm.cancelAddedWallet()
        drainUntil { !vm.uiState.value.showSyncModeDialog }

        assertOnlyAddedWalletRemoved(first, second, added)
        coVerify(exactly = 1) { gatewayRepository.onWalletDeleted(added) }
        assertNull(vm.uiState.value.createdWallet)
        assertFalse(vm.uiState.value.hasRestoreHintFile)
        assertNull(vm.uiState.value.restoreHintPlan)
    }

    @Test
    fun `a failed rollback keeps the wallet and says so, and a second Cancel keeps it`() = runTest {
        val (first, _) = seedExisting()
        val spy = spyk(walletRepository)
        coEvery { spy.rollbackAddedWallet(any(), any()) } throws IllegalStateException("disk full")
        val vm = newViewModel(spy)
        vm.importPhrase()
        drainUntil { vm.uiState.value.showSyncModeDialog }
        val added = walletRepository.getActive()!!.walletId

        vm.cancelAddedWallet()
        drainUntil { vm.uiState.value.syncChoiceError != null }

        assertTrue("sheet stays open with the message", vm.uiState.value.showSyncModeDialog)
        assertEquals(
            UiMessage.Resource(R.string.add_wallet_rollback_failed),
            vm.uiState.value.syncChoiceError,
        )
        assertFalse("Cancel no longer removes", vm.uiState.value.cancelRemovesWallet)
        assertFalse(vm.uiState.value.isApplyingSyncChoice)
        assertNotNull(db.walletDao().getById(added))
        assertEquals(added, walletRepository.getActive()?.walletId)
        coVerify(exactly = 0) { gatewayRepository.onWalletDeleted(any()) }

        vm.cancelAddedWallet()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showSyncModeDialog)
        assertEquals(added, vm.uiState.value.createdWallet?.walletId)
        coVerify(exactly = 1) { spy.rollbackAddedWallet(any(), any()) }
        assertTrue(first != added)
    }

    @Test
    fun `with no previous wallet Cancel keeps the wallet as before`() = runTest {
        val vm = newViewModel()
        vm.importPhrase()
        drainUntil { vm.uiState.value.showSyncModeDialog }
        assertFalse(vm.uiState.value.cancelRemovesWallet)

        vm.cancelAddedWallet()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.createdWallet)
        assertEquals(1, db.walletDao().count())
        coVerify(exactly = 0) { gatewayRepository.onWalletDeleted(any()) }
    }
}
