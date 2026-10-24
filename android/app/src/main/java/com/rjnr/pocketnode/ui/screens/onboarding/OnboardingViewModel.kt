package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.log.Logger
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
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val walletRepository: WalletRepository,
    private val authManager: AuthManager,
    private val walletKeyWriter: WalletKeyWriter,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                wasCorrupted = repository.wasResetDueToCorruption(),
            )
        }
    }

    /** Creates the first wallet with independent passphrase protection before reporting success. */
    fun createNewWallet(activity: FragmentActivity, name: String = "My Wallet") {
        if (_uiState.value.isLoading) return
        val trimmed = name.trim().ifBlank { "My Wallet" }
        viewModelScope.launch {
            try {
                com.rjnr.pocketnode.data.vault.VaultOperationSession.run(activity) owned@ {
                    _uiState.update { it.copy(isLoading = true, error = null) }
                    val result = walletRepository.createWallet(
                        name = trimmed,
                        persistKeys = { walletId, bundle ->

                                walletKeyWriter.persistCreatedWallet(
                                    activity = activity,
                                    walletId = walletId,
                                    bundle = bundle,
                                    walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                                    mnemonicBackedUp = false,
                                )

                        },
                    )
                    result.onSuccess { entity ->
                        logger.d(TAG, "Created wallet entity: ${entity.walletId}")
                        repository.onActiveWalletChanged(entity)
                        _uiState.update { it.copy(isLoading = false, isWalletCreated = true) }
                    }.onFailure { error ->
                        logger.e(TAG, "Wallet creation failed", error)
                        _uiState.update {
                            it.copy(isLoading = false, error = persistErrorMessage(error))
                        }
                    }

                }

            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoading = false) }
                throw cancelled
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
         *   - Cancelled: silent (null), user dismissed the wallet prompt
         *     intentionally; no error toast, they can retry by tapping again.
         *   - AuthError: surface the prompt's error message.
         *   - WriteFailed: generic "save failed", the cause is logged but
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
