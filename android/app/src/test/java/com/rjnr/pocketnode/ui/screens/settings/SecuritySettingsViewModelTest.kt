package com.rjnr.pocketnode.ui.screens.settings

import androidx.lifecycle.SavedStateHandle
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SecuritySettingsViewModel.applyAuthBeforeSend] PIN-gate
 * routing introduced by #292. The toggle no longer applies immediately
 * from the Switch; both ENABLE and DISABLE route through
 * [PinEntryScreen] via [SecuritySettingsViewModel.setPendingAction] +
 * [SecuritySettingsViewModel.executePendingAction].
 *
 * The Switch handler stages the pending action and navigates to PIN
 * verification; only after the user enters the correct PIN does the
 * navigation chain call [SecuritySettingsViewModel.executePendingAction]
 * which invokes the real `applyAuthBeforeSend(enabled)` setter.
 *
 * Pre-#292 a local attacker with a brief unlocked-session window could
 * flip the Switch off and immediately send a transaction without re-auth.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecuritySettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var authManager: AuthManager
    private lateinit var pinManager: PinManager
    private lateinit var keyBackupManager: KeyBackupManager
    private lateinit var keyManager: KeyManager
    private lateinit var walletDao: WalletDao

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        savedStateHandle = SavedStateHandle()
        authManager = mockk(relaxed = true)
        pinManager = mockk(relaxed = true)
        keyBackupManager = mockk(relaxed = true)
        keyManager = mockk(relaxed = true)
        walletDao = mockk(relaxed = true)

        every { authManager.isBiometricAvailable() } returns AuthManager.BiometricStatus.AVAILABLE
        every { authManager.isBiometricEnabled() } returns false
        every { authManager.isAuthBeforeSendEnabled() } returns false
        every { pinManager.hasPin() } returns true
        coEvery { walletDao.count() } returns 0
        every { keyBackupManager.hasAnyBackups() } returns false
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun newViewModel() = SecuritySettingsViewModel(
        savedStateHandle = savedStateHandle,
        authManager = authManager,
        pinManager = pinManager,
        keyBackupManager = keyBackupManager,
        keyManager = keyManager,
        walletDao = walletDao,
    )

    @Test
    fun `setPendingAction does not apply auth-before-send until executePendingAction runs`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setPendingAction(PendingSecurityAction.ENABLE_AUTH_BEFORE_SEND)
        advanceUntilIdle()

        // Pending action staged in SavedStateHandle. The Switch's onCheckedChange
        // staged it and then navigated to PinEntryScreen. The setter MUST NOT
        // fire yet — the user hasn't verified PIN.
        verify(exactly = 0) { authManager.setAuthBeforeSendEnabled(any()) }
        assertEquals(false, vm.uiState.value.isAuthBeforeSendEnabled)
    }

    @Test
    fun `executePendingAction ENABLE_AUTH_BEFORE_SEND applies and updates UI state`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setPendingAction(PendingSecurityAction.ENABLE_AUTH_BEFORE_SEND)
        vm.executePendingAction()
        advanceUntilIdle()

        verify(exactly = 1) { authManager.setAuthBeforeSendEnabled(true) }
        assertEquals(true, vm.uiState.value.isAuthBeforeSendEnabled)
    }

    @Test
    fun `executePendingAction DISABLE_AUTH_BEFORE_SEND applies and updates UI state`() = runTest {
        // Pre-condition: auth-before-send already on.
        every { authManager.isAuthBeforeSendEnabled() } returns true
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setPendingAction(PendingSecurityAction.DISABLE_AUTH_BEFORE_SEND)
        vm.executePendingAction()
        advanceUntilIdle()

        verify(exactly = 1) { authManager.setAuthBeforeSendEnabled(false) }
        assertEquals(false, vm.uiState.value.isAuthBeforeSendEnabled)
    }

    @Test
    fun `executePendingAction without setPendingAction is a no-op`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.executePendingAction()
        advanceUntilIdle()

        verify(exactly = 0) { authManager.setAuthBeforeSendEnabled(any()) }
    }

    @Test
    fun `executePendingAction ENABLE without PIN surfaces error and does not apply`() = runTest {
        // Defensive: if the Switch wiring drifts and ENABLE is staged without a PIN,
        // applyAuthBeforeSend must refuse rather than enable a gate that requires
        // PIN verification to operate.
        every { pinManager.hasPin() } returns false
        val vm = newViewModel()
        advanceUntilIdle()

        vm.setPendingAction(PendingSecurityAction.ENABLE_AUTH_BEFORE_SEND)
        vm.executePendingAction()
        advanceUntilIdle()

        verify(exactly = 0) { authManager.setAuthBeforeSendEnabled(any()) }
        assertEquals(false, vm.uiState.value.isAuthBeforeSendEnabled)
        assertNotNull(vm.uiState.value.error)
    }
}
