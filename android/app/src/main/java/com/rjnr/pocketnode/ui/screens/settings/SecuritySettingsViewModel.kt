package com.rjnr.pocketnode.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.wallet.WalletUnlockPreference
import com.rjnr.pocketnode.data.vault.VaultReadPreference
import com.rjnr.pocketnode.data.vault.VaultDevicePromptMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PendingSecurityAction {
    REMOVE_PIN,
    ENABLE_BIOMETRIC,
    DISABLE_BIOMETRIC,
    // Both enable and disable need a PIN gate. Disabling auth-before-send
    // is itself a security state change: a local attacker with a brief
    // unlocked-session window must not be able to turn off the
    // per-operation gate that protects transaction signing (#292).
    ENABLE_AUTH_BEFORE_SEND,
    DISABLE_AUTH_BEFORE_SEND,
}

data class SecuritySettingsUiState(
    val isBiometricAvailable: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val hasPin: Boolean = false,
    val canRemovePin: Boolean = false,
    val biometricStatusText: String = "",
    val isAuthBeforeSendEnabled: Boolean = false,
    val error: UiMessage? = null,
    val unlockPreference: VaultReadPreference = VaultReadPreference.DEFAULT,
    val vaultBusy: Boolean = false,
    val vaultMessage: String? = null,
    val vaultWallets: List<WalletSecurityStatus> = emptyList(),
    /** Dashboard biometric preference confirmation; independent vault fallback is explained. */
    val biometricEnrollmentWarning: BiometricEnrollmentWarning? = null,
)

/** Confirmation for changing dashboard biometric convenience. */
data class BiometricEnrollmentWarning(
    val enabling: Boolean,
)

@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val authManager: AuthManager,
    private val pinManager: PinManager,
    private val keyBackupManager: KeyBackupManager,
    private val walletDao: WalletDao,
    private val unlockPreference: WalletUnlockPreference,
    private val vaultOperations: SecurityVaultOperations,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecuritySettingsUiState())
    val uiState: StateFlow<SecuritySettingsUiState> = _uiState.asStateFlow()

    private companion object {
        const val KEY_PENDING_ACTION = "pending_action"
    }

    fun setPendingAction(action: PendingSecurityAction) {
        savedStateHandle[KEY_PENDING_ACTION] = action.name
    }

    fun executePendingAction() {
        val action = savedStateHandle.get<String>(KEY_PENDING_ACTION)?.let {
            runCatching { PendingSecurityAction.valueOf(it) }.getOrNull()
        }
        when (action) {
            PendingSecurityAction.REMOVE_PIN -> removePin()
            PendingSecurityAction.ENABLE_BIOMETRIC -> toggleBiometric(true)
            PendingSecurityAction.DISABLE_BIOMETRIC -> toggleBiometric(false)
            PendingSecurityAction.ENABLE_AUTH_BEFORE_SEND -> applyAuthBeforeSend(true)
            PendingSecurityAction.DISABLE_AUTH_BEFORE_SEND -> applyAuthBeforeSend(false)
            null -> {}
        }
        savedStateHandle.remove<String>(KEY_PENDING_ACTION)
    }

    init {
        refreshState()
    }

    private var vaultJob: Job? = null

    fun selectUnlockPreference(value: VaultReadPreference) {
        unlockPreference.set(value)
        _uiState.update { it.copy(unlockPreference = value) }
    }
    fun changePassphrase(activity: FragmentActivity) = maintain { vaultOperations.change(activity) }
    fun resetPassphrase(activity: FragmentActivity, mode: VaultDevicePromptMode? = null) = maintain { vaultOperations.reset(activity, mode) }
    fun enrollDevice(activity: FragmentActivity, id: String, mode: VaultDevicePromptMode? = null) = maintain { vaultOperations.enroll(activity, id, mode) }
    fun cancelVaultOperation() { vaultJob?.cancel() }
    private fun maintain(action: suspend () -> VaultSettingsResult) {
        if (vaultJob?.isActive == true) return
        _uiState.update { it.copy(vaultBusy = true, vaultMessage = null) }
        vaultJob = viewModelScope.launch {
            try {
                val result = action()
                _uiState.update { it.copy(vaultMessage = result.message, vaultWallets = result.wallets.ifEmpty { it.vaultWallets }) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(vaultMessage = "Cancelled. Saved protection is preserved.") }
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(vaultMessage = "Operation did not complete. Saved protection is preserved.") }
            } finally { _uiState.update { it.copy(vaultBusy = false) } }
        }
    }

    fun refreshState() {
        val biometricStatus = authManager.isBiometricAvailable()
        viewModelScope.launch {
            val statuses = vaultOperations.walletNames()
            _uiState.update {
                it.copy(
                    isBiometricAvailable = biometricStatus == AuthManager.BiometricStatus.AVAILABLE,
                    isBiometricEnabled = authManager.isBiometricEnabled(),
                    hasPin = pinManager.hasPin(),
                    canRemovePin = !keyBackupManager.hasAnyBackups(),
                    unlockPreference = unlockPreference.get(),
                    vaultWallets = statuses,
                    biometricStatusText = when (biometricStatus) {
                        AuthManager.BiometricStatus.AVAILABLE -> "Fingerprint hardware available"
                        AuthManager.BiometricStatus.NO_HARDWARE -> "No biometric hardware detected"
                        AuthManager.BiometricStatus.NOT_ENROLLED -> "No fingerprints enrolled in device settings"
                        AuthManager.BiometricStatus.UNAVAILABLE -> "Biometric authentication unavailable"
                    },
                    isAuthBeforeSendEnabled = authManager.isAuthBeforeSendEnabled(),
                    error = null
                )
            }
        }
    }

    /**
     * Apply the auth-before-send preference. PRIVATE — invoked only via
     * [executePendingAction] after the user verifies their PIN in
     * `PinEntryScreen`. The UI must NOT call this directly; the Switch
     * `onCheckedChange` handler routes through [setPendingAction] +
     * navigate-to-PIN-verify to enforce the gate (#292).
     *
     * The "no PIN → refuse to enable" pre-check is performed at the screen
     * layer (the Switch is disabled when `hasPin == false`) and again here
     * defensively in case the screen wiring drifts.
     */
    private fun applyAuthBeforeSend(enabled: Boolean) {
        if (enabled && !pinManager.hasPin()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_set_pin_first_send)) }
            return
        }
        authManager.setAuthBeforeSendEnabled(enabled)
        _uiState.update { it.copy(isAuthBeforeSendEnabled = enabled, error = null) }
    }

    private fun toggleBiometric(enabled: Boolean) {
        if (enabled && !pinManager.hasPin()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.vm_error_set_pin_first_biometric)) }
            return
        }
        // Explain the distinction between optional device access and independent vault protection.
        viewModelScope.launch {
            if (walletDao.count() > 0) {
                _uiState.update {
                    it.copy(biometricEnrollmentWarning = BiometricEnrollmentWarning(enabled))
                }
            } else {
                applyBiometricToggle(enabled)
            }
        }
    }

    /** Called from the warning dialog when the user accepts the trade-off. */
    fun confirmBiometricEnrollmentWarning() {
        val warning = _uiState.value.biometricEnrollmentWarning ?: return
        applyBiometricToggle(warning.enabling)
        _uiState.update { it.copy(biometricEnrollmentWarning = null) }
    }

    fun dismissBiometricEnrollmentWarning() {
        _uiState.update { it.copy(biometricEnrollmentWarning = null) }
    }

    private fun applyBiometricToggle(enabled: Boolean) {
        authManager.setBiometricEnabled(enabled)
        _uiState.update { it.copy(isBiometricEnabled = enabled, error = null) }
    }

    private fun removePin() {
        // Preserve access to old backup credentials until authenticated cleanup finishes.
        viewModelScope.launch {
            if (keyBackupManager.hasAnyBackups()) {
                _uiState.update {
                    it.copy(error = UiMessage.Resource(R.string.vm_error_pin_required_backups))
                }
                return@launch
            }
            pinManager.removePin()
            authManager.setBiometricEnabled(false)
            authManager.setAuthBeforeSendEnabled(false)
            _uiState.update {
                it.copy(hasPin = false, isBiometricEnabled = false, isAuthBeforeSendEnabled = false, error = null)
            }
        }
    }

    /** Dashboard PIN setup does not create a weaker copy of any wallet secret. */
    fun onPinCreated() { refreshState() }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
