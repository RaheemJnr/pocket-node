package com.rjnr.pocketnode.ui.screens.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.KeyMaterial
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
    /**
     * Non-null when the user is being asked to confirm that adding or
     * removing a biometric enrollment will invalidate their V2 wallet
     * keys. The dialog explains the consequence and offers a
     * cancel/proceed choice (#213 sub-PR 6). When the user proceeds, the
     * wallet remains usable only via recovery-phrase re-import.
     */
    val biometricEnrollmentWarning: BiometricEnrollmentWarning? = null,
)

/**
 * State for the biometric-enrollment warning modal shown when the user
 * is about to enable biometric on a device that already has wallets.
 * The V2 Keystore key uses `setInvalidatedByBiometricEnrollment(true)`,
 * so any future change to enrolled biometrics wipes the key and the
 * wallet can only be recovered from its mnemonic.
 */
data class BiometricEnrollmentWarning(
    val enabling: Boolean,
)

@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val authManager: AuthManager,
    private val pinManager: PinManager,
    private val keyBackupManager: KeyBackupManager,
    private val keyManager: KeyManager,
    private val walletDao: WalletDao
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

    fun refreshState() {
        val biometricStatus = authManager.isBiometricAvailable()
        viewModelScope.launch {
            val walletCount = walletDao.count()
            _uiState.update {
                it.copy(
                    isBiometricAvailable = biometricStatus == AuthManager.BiometricStatus.AVAILABLE,
                    isBiometricEnabled = authManager.isBiometricEnabled(),
                    hasPin = pinManager.hasPin(),
                    canRemovePin = walletCount == 0 && !keyBackupManager.hasAnyBackups(),
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
        // The V2 Keystore key is configured with
        // setInvalidatedByBiometricEnrollment(true), so any later change
        // to enrolled biometrics will wipe the key. Warn the user once
        // here so they understand they MUST keep their recovery phrase
        // backed up; if they later add or remove a fingerprint, the
        // wallet becomes unreadable except via re-import (#213 sub-PR 6).
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
        // Mandatory PIN: refuse removal as long as any wallet exists so every wallet
        // always has a PIN-encrypted backup available for recovery.
        viewModelScope.launch {
            if (walletDao.count() > 0) {
                _uiState.update {
                    it.copy(error = UiMessage.Resource(R.string.vm_error_pin_required_remove_wallets))
                }
                return@launch
            }
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

    fun onPinCreated(pin: String) {
        authManager.setSessionPin(pin.toCharArray())
        viewModelScope.launch {
            try {
                val wallets = walletDao.getAll()
                for (wallet in wallets) {
                    // V2 wallets require a BiometricPrompt per read. Skip
                    // them silently here — the user will populate the
                    // backup when they next reveal their recovery phrase
                    // (which already prompts for biometric). Without this
                    // skip, the loop would crash on the first V2 wallet
                    // with V2KeyMaterialRequiresAuthException (#213 sub-PR 5).
                    val privateKey = try {
                        keyManager.getPrivateKeyForWallet(wallet.walletId) ?: continue
                    } catch (e: Exception) {
                        android.util.Log.i(
                            "SecuritySettingsVM",
                            "Skipping V2-protected wallet ${wallet.walletId} during PIN backup",
                        )
                        continue
                    }
                    val mnemonic = try {
                        keyManager.getMnemonicForWallet(wallet.walletId)
                    } catch (e: Exception) {
                        null
                    }
                    val material = KeyMaterial(
                        privateKey = privateKey.joinToString("") { "%02x".format(it) },
                        mnemonic = mnemonic?.joinToString(" "),
                        walletType = if (mnemonic != null) KeyManager.WALLET_TYPE_MNEMONIC else KeyManager.WALLET_TYPE_RAW_KEY,
                        mnemonicBackedUp = keyManager.hasMnemonicBackupForWallet(wallet.walletId)
                    )
                    keyBackupManager.writeBackup(wallet.walletId, material, pin.toCharArray())
                }
            } catch (e: Exception) {
                android.util.Log.w("SecuritySettingsVM", "Failed to write backups on PIN creation", e)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
