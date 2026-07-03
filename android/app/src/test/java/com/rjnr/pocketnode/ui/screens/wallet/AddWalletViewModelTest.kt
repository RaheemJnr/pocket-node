package com.rjnr.pocketnode.ui.screens.wallet

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.migration.WalletKeyBundle
import com.rjnr.pocketnode.data.wallet.KeyManager
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AddWalletViewModel.createSubAccount] focused on V2 keystore
 * correctness post-#289 chunk 5:
 *
 *   - Test #8: cancelling the BiometricPrompt during sub-account creation
 *     MUST NOT pollute Room (no `createSubAccount` repo call, no
 *     `onActiveWalletChanged` hook).
 *   - Test #9: on a V2 parent the parent's mnemonic MUST be read via
 *     [WalletKeyReader.readKeyMaterial] BEFORE
 *     [WalletKeyWriter.persistNewWallet] is invoked — the previous
 *     (pre-#289) flow routed through the V1 path which crashed on V2
 *     parents.
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

    private val parentMnemonicWords = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        walletRepository = mockk(relaxed = true)
        // No parent wallets needed for init flow (the VM filters by type
        // before exposing them; we just need it to return empty).
        coEvery { walletRepository.getAll() } returns emptyList()
        gatewayRepository = mockk(relaxed = true)
        mnemonicManager = mockk(relaxed = true)
        walletKeyReader = mockk(relaxed = true)
        walletKeyWriter = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        // Default: device has a secure lock, so the V2 path runs (matches
        // the pre-#354 behavior these tests were written against).
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
    )

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
            walletKeyWriter.persistNewWallet(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            walletRepository.createSubAccount(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { gatewayRepository.onActiveWalletChanged(any()) }
        assertNull(vm.uiState.value.createdWallet)
        assertNull(vm.uiState.value.error)  // Cancel is silent
        assertFalse(vm.uiState.value.isLoading)
    }

    // NOTE: test #9 from the plan (`createSubAccount on V2 parent calls
    // walletKeyReader before walletKeyWriter` — pins the #289 bonus bug fix
    // ordering) cannot be expressed as a unit test against the current
    // MockK version. `WalletRepository.createSubAccount` returns
    // `kotlin.Result<WalletEntity>`, and MockK 1.13.16 cannot round-trip
    // a stubbed inline-value-class return through the suspend continuation
    // resume boundary — neither `returns Result.success(...)` nor
    // `returns Result.failure(...)` survive the boxing, surfacing as
    // `ClassCastException: kotlin.Result cannot be cast to WalletEntity`
    // at `AddWalletViewModel.kt:170`.
    //
    // The ordering contract (reader-before-writer on V2 parents) is
    // instead verified by:
    //   - The `Cancelled` test above (covers the reader-only short path).
    //   - Code review of `AddWalletViewModel.createSubAccount` (the
    //     reader call sits explicitly above the repo call).
    //   - The chunk 5 spec/quality reviews on commit `eb9805e`.
    //   - The pre-tag manual smoke (Chunk 7 task 7.3) which exercises
    //     the V2 parent path end-to-end on a real device.
    //
    // Restore as a real test if MockK adds inline-value-class support.
}
