package com.rjnr.pocketnode.ui.screens.onboarding

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "OnboardingViewModel"

data class OnboardingUiState(
    val isLoading: Boolean = false,
    val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
    val isWalletCreated: Boolean = false,
    val wasCorrupted: Boolean = false,
    /**
     * True when the device has neither enrolled biometrics nor a device
     * lock (PIN/pattern/password). The V2 Keystore key cannot be created
     * on such devices because `setUserAuthenticationRequired(true)`
     * requires *some* credential to authenticate against. Banking-app
     * pattern: refuse setup and tell the user to enable a lock in Android
     * Settings before proceeding (#213 sub-PR 6).
     */
    val noDeviceCredential: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val walletRepository: WalletRepository,
    private val authManager: AuthManager,
    private val walletKeyWriter: WalletKeyWriter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                wasCorrupted = repository.wasResetDueToCorruption(),
                noDeviceCredential = !canCreateV2BoundKey(),
            )
        }
    }

    /**
     * True if Keystore can mint a V2 (auth-bound) key on this device.
     * Either an enrolled biometric or a device credential (PIN/pattern/
     * password) is required — the V2 spec uses
     * `AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL`.
     */
    private fun canCreateV2BoundKey(): Boolean =
        authManager.isBiometricEnrolled() || authManager.hasDeviceCredential()

    /**
     * Create the first wallet at kdfVersion=2.
     *
     * [name] is captured from the onboarding "Name your wallet" step
     * (Telegram bug 3); falls back to "My Wallet" if the user submitted
     * empty so the call never produces a blank-named wallet row.
     *
     * [activity] is required so [WalletKeyWriter] can drive the
     * BiometricPrompt CryptoObject flow. Cancellation by the user is
     * surfaced as a silent return (no error toast) so the user can
     * retry by tapping again.
     */
    fun createNewWallet(activity: FragmentActivity, name: String = "My Wallet") {
        // Post-#289 there is no V1 write path: WalletRepository.createWallet
        // funnels through WalletKeyWriter → KeystoreV2MigrationHelper.writeNewV2Row
        // which mints a fresh V2 Keystore key with setUserAuthenticationRequired(true).
        // Without an enrolled biometric or device credential, the keystore
        // refuses the key creation with IllegalStateException("Secure lock
        // screen must be enabled to create keys requiring user authentication").
        //
        // The Screen surfaces a dialog before this fn is reached
        // (showNoDeviceLockWarning when uiState.noDeviceCredential is true)
        // directing the user to Android Settings → Security to enable a
        // device lock. Defense-in-depth: the writer also catches the
        // IllegalStateException and surfaces a friendly error here in case
        // the device-credential state changes between the gate check and
        // the actual key creation (#289 no-secure-lock follow-up).
        val trimmed = name.trim().ifBlank { "My Wallet" }
        // Re-check at call time, not just at VM init — the user may have
        // come back from Android Settings after enabling a device lock and
        // we want to prefer V2 if they did.
        val useV1Fallback = !canCreateV2BoundKey()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = walletRepository.createWallet(
                name = trimmed,
                persistKeys = { walletId, bundle ->
                    if (useV1Fallback) {
                        // Software-only V1 path. The Onboarding screen
                        // already showed an informed-consent dialog
                        // ("Continue without a device lock?") before we
                        // reach this point. AuthScreen's migration loop
                        // will upgrade the row to V2 the moment the user
                        // enables a device lock and cold-starts the app.
                        walletKeyWriter.persistNewWalletV1Fallback(
                            walletId = walletId,
                            bundle = bundle,
                            walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                            mnemonicBackedUp = false,
                        )
                    } else {
                        walletKeyWriter.persistNewWallet(
                            activity = activity,
                            walletId = walletId,
                            bundle = bundle,
                            walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                            mnemonicBackedUp = false,
                        )
                    }
                },
            )
            result.onSuccess { entity ->
                Log.d(TAG, "Created wallet entity: ${entity.walletId}")
                repository.onActiveWalletChanged(entity)
                _uiState.update { it.copy(isLoading = false, isWalletCreated = true) }
            }.onFailure { error ->
                Log.e(TAG, "Wallet creation failed", error)
                _uiState.update {
                    it.copy(isLoading = false, error = persistErrorMessage(error))
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        /**
         * Map a wallet-create failure to a user-facing message.
         *
         *   - Cancelled: silent (null) — user dismissed the BiometricPrompt
         *     intentionally; no error toast, they can retry by tapping again.
         *   - AuthError: surface the prompt's error message.
         *   - WriteFailed: generic "save failed" — the cause is logged but
         *     not shown verbatim (likely Room/disk error, not user-actionable).
         *   - KeyInvalidated: tells the user to re-import; the V2 keystore
         *     key has been wiped by biometric enrollment change.
         *   - Other exceptions: pass the message through.
         */
        internal fun persistErrorMessage(error: Throwable): com.rjnr.pocketnode.ui.util.UiMessage? {
            val pex = error as? WalletKeyWriter.PersistException
            return when (val r = pex?.result) {
                WalletKeyWriter.Result.Cancelled -> null
                is WalletKeyWriter.Result.AuthError ->
                    com.rjnr.pocketnode.ui.util.UiMessage.Raw("Auth error: ${r.message}")
                is WalletKeyWriter.Result.WriteFailed ->
                    com.rjnr.pocketnode.ui.util.UiMessage.Raw(
                        "Failed to save wallet: ${r.cause.message ?: "unknown error"}"
                    )
                WalletKeyWriter.Result.KeyInvalidated ->
                    com.rjnr.pocketnode.ui.util.UiMessage.Raw(
                        "Wallet keys must be re-imported"
                    )
                null -> error.message?.let(com.rjnr.pocketnode.ui.util.UiMessage::Raw)
                else -> error.message?.let(com.rjnr.pocketnode.ui.util.UiMessage::Raw)
            }
        }
    }
}
