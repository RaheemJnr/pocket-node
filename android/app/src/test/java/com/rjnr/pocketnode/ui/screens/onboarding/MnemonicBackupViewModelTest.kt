package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.security.WalletBackupOperations
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MnemonicBackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val wallets = mockk<WalletRepository>()
    private val operations = mockk<WalletBackupOperations>()
    private val activity = mockk<FragmentActivity>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun model(id: String? = "chosen", onboarding: Boolean = false) = MnemonicBackupViewModel(
        SavedStateHandle(mapOf("walletId" to id, "onboarding" to onboarding)), wallets, operations)

    @Test fun `onboarding and existing wallet routes never eagerly read or bypass authentication`() = runTest {
        for (onboarding in listOf(false, true)) {
            val vm = model(onboarding = onboarding); advanceUntilIdle()
            assertEquals(1, vm.uiState.value.currentStep)
            coVerify(exactly = 0) { operations.show(any(), any(), any()) }
        }
    }
    @Test fun `named wallet remains scoped even when a different wallet is active`() = runTest {
        coEvery { operations.show(activity, "chosen", true) } returns WalletBackupOperations.Outcome.Shown(true, true)
        val vm = model(); vm.backup(activity); advanceUntilIdle()
        assertEquals(3, vm.uiState.value.currentStep)
        coVerify(exactly = 0) { wallets.getActive() }
    }
    @Test fun `active wallet route captures one target before starting authenticated backup`() = runTest {
        val wallet = mockk<WalletEntity>(); every { wallet.walletId } returns "active"
        coEvery { wallets.getActive() } returns wallet
        coEvery { operations.show(activity, "active", true) } returns WalletBackupOperations.Outcome.Shown(true, true)
        val vm = model(id = null); vm.backup(activity); advanceUntilIdle()
        assertEquals(3, vm.uiState.value.currentStep)
    }
    @Test fun `cancel failed authentication and raw key display cannot complete mnemonic backup`() = runTest {
        for (outcome in listOf(WalletBackupOperations.Outcome.Cancelled, WalletBackupOperations.Outcome.Failed("Retry"), WalletBackupOperations.Outcome.Shown(false, false))) {
            coEvery { operations.show(activity, "chosen", true) } returns outcome
            val vm = model(); vm.backup(activity); advanceUntilIdle()
            assertEquals(1, vm.uiState.value.currentStep); assertFalse(vm.uiState.value.isLoading)
        }
    }
    @Test fun `leaving screen cancels pending native operation and does not mark success`() = runTest {
        coEvery { operations.show(activity, "chosen", true) } coAnswers { awaitCancellation() }
        val vm = model(); vm.backup(activity); runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        vm.cancel(); advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading); assertEquals(1, vm.uiState.value.currentStep)
    }
}
