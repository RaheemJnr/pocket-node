package com.rjnr.pocketnode.ui.screens.wallet

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.ui.security.WalletBackupOperations
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletSettingsViewModelLockTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = mockk<WalletBackupOperations>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun model() = WalletSettingsViewModel(
        savedStateHandle = SavedStateHandle(mapOf("walletId" to "chosen")),
        walletRepository = mockk(relaxed = true), gatewayRepository = mockk(relaxed = true), walletDao = mockk(relaxed = true),
        keyManager = mockk(relaxed = true), daoCellDao = mockk(relaxed = true), transactionDao = mockk(relaxed = true),
        appStatePreferences = mockk(relaxed = true), networkPreferences = mockk(relaxed = true),
        walletKeyReader = mockk(), walletKeyWriter = mockk(), authManager = mockk(relaxed = true), logger = NoopLogger,
        backupOperations = operations,
    )
    @Test fun `PIN callback and Activity free reveal cannot authenticate or release secrets`() = runTest {
        val vm = model(); advanceUntilIdle(); vm.onPinVerified(); vm.loadSensitiveData(); advanceUntilIdle()
        coVerify(exactly = 0) { operations.show(any(), any(), any()) }
        assertNull(vm.uiState.value.hasMnemonic)
        assertNotNull(vm.uiState.value.error)
    }
    @Test fun `foreground reveal reports actual capability without returning mnemonic or private key`() = runTest {
        val activity = mockk<FragmentActivity>()
        coEvery { operations.show(activity, "chosen", false) } returns WalletBackupOperations.Outcome.Shown(false, false)
        val vm = model(); advanceUntilIdle(); vm.loadSensitiveData(activity); advanceUntilIdle()
        assertEquals(false, vm.uiState.value.hasMnemonic)
        assertFalse(vm.hasMnemonic())
    }
    @Test fun `reauthentication lock cancels pending native reveal`() = runTest {
        val activity = mockk<FragmentActivity>()
        var cancelled = false
        coEvery { operations.show(activity, "chosen", false) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val vm = model(); advanceUntilIdle(); vm.loadSensitiveData(activity); runCurrent()
        ReauthLockEvents.onLocked(); advanceUntilIdle()
        assertTrue(cancelled); assertNull(vm.uiState.value.hasMnemonic)
    }
}
