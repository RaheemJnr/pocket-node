package com.rjnr.pocketnode.ui.screens.wallet

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.log.NoopLogger
import androidx.lifecycle.SavedStateHandle
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Public orchestration checks: actual seed capability, cancellation and selected-parent races.
 * Durable cryptography and atomic persistence are covered by the real repository/vault fixtures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddWalletViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var walletRepository: WalletRepository
    private lateinit var gatewayRepository: GatewayRepository
    private lateinit var mnemonicManager: MnemonicManager
    private lateinit var walletKeyReader: WalletKeyReader
    private lateinit var walletKeyWriter: WalletKeyWriter
    private lateinit var authManager: com.rjnr.pocketnode.data.auth.AuthManager
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        walletRepository = mockk(relaxed = true)
        // No parent wallets needed for init flow (the VM filters by type
        // before exposing them; we just need it to return empty).
        coEvery { walletRepository.getAll() } returns emptyList()
        gatewayRepository = mockk(relaxed = true)
        io.mockk.every { gatewayRepository.syncProgress } returns
            MutableStateFlow(SyncProgress())
        mnemonicManager = mockk(relaxed = true)
        walletKeyReader = mockk(relaxed = true)
        walletKeyWriter = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        // Device state must not authorize secret extraction or weaker persistence.
        io.mockk.every { authManager.isBiometricEnrolled() } returns true
        io.mockk.every { authManager.hasDeviceCredential() } returns true
        activity = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): AddWalletViewModel = AddWalletViewModel(
        savedStateHandle = SavedStateHandle(),
        walletRepository = walletRepository,
        gatewayRepository = gatewayRepository,
        mnemonicManager = mnemonicManager,
        walletKeyReader = walletKeyReader,
        walletKeyWriter = walletKeyWriter,
        authManager = authManager,
        logger = NoopLogger,
        restoreHintImporter = mockk(relaxed = true),
        importOperations = mockk(relaxed = true),
    )

    @Test
    fun `parent selection changed during authentication refuses derivation and clears key`() = runTest {
        val parent = com.rjnr.pocketnode.data.database.entity.WalletEntity("parent-id", "Parent", "mnemonic", null, null)
        coEvery { walletRepository.getById("parent-id") } returns parent
        val key = ByteArray(32) { 8 }
        val vm = newViewModel(); advanceUntilIdle(); vm.selectParent("parent-id"); vm.updateName("Child")
        coEvery { walletKeyReader.readKeyMaterial(any(), eq("parent-id"), any(), any()) } coAnswers {
            vm.selectParent("different")
            WalletKeyReader.MaterialResult.Success(key, "abandon ".repeat(11) + "about", "mnemonic", false)
        }
        vm.createSubAccount(activity); advanceUntilIdle()
        coVerify(exactly = 0) { walletRepository.createSubAccount(any(), any(), any(), any(), any(), any()) }
        assertArrayEquals(ByteArray(32), key)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `mnemonic origin without protected seed refuses derivation and clears unused key`() = runTest {
        val key = ByteArray(32) { 7 }
        coEvery { walletKeyReader.readKeyMaterial(any(), eq("parent-id"), any(), any()) } returns
            WalletKeyReader.MaterialResult.Success(key, null, "mnemonic", false)
        val vm = newViewModel()
        vm.selectParent("parent-id"); vm.updateName("sub"); vm.createSubAccount(activity)
        advanceUntilIdle()
        assertTrue(key.all { it == 0.toByte() })
        assertNull(vm.uiState.value.createdWallet)
        assertFalse(vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `a re-auth lock preserves the public wallet name`() = runTest {
        val vm = newViewModel(); advanceUntilIdle(); vm.updateName("Savings")
        com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents.onLocked(); advanceUntilIdle()
        assertEquals("Savings", vm.uiState.value.name)
    }

    @Test
    fun `Cancelled BiometricPrompt during sub-account read does not pollute Room`() = runTest {
        coEvery {
            walletKeyReader.readKeyMaterial(any(), eq("parent-id"), any(), any())
        } returns WalletKeyReader.MaterialResult.Cancelled

        val vm = newViewModel()
        vm.selectParent("parent-id")
        vm.updateName("sub")
        vm.createSubAccount(activity)
        advanceUntilIdle()

        // The writer must not be invoked, the repo `createSubAccount` must not be
        // called, and there must be no active-wallet registration.
        coVerify(exactly = 0) {
            walletKeyWriter.persistCreatedWallet(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            walletRepository.createSubAccount(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { gatewayRepository.onActiveWalletChanged(any()) }
        assertNull(vm.uiState.value.createdWallet)
        assertNull(vm.uiState.value.error)  // Cancel is silent
        assertFalse(vm.uiState.value.isLoading)
    }

    // Suspend kotlin.Result repository methods cannot be reliably stubbed by this
    // MockK version. Real repository tests cover derivation/persistence, and
    // WalletVaultWriterIntegrationTest covers atomic writer protection and parent races.

    // #431: the post-import sync-mode sheet (showSyncModeDialog,
    // onSyncModeSelected, skipSyncSelection) is covered in
    // AddWalletViewModelImportSyncTest, which runs the import through a real
    // WalletRepository instead of a mocked one, mocking
    // `importFromMnemonic`'s `kotlin.Result<WalletEntity>` return hits the
    // exact same MockK inline-value-class crash documented above.
}
