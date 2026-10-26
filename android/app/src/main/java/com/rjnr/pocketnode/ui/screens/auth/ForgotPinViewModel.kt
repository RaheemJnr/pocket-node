package com.rjnr.pocketnode.ui.screens.auth

import com.rjnr.pocketnode.core.log.Logger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.LocalWalletReset
import com.rjnr.pocketnode.util.AppRestarter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ForgotPinViewModel"

/**
 * Destructive recovery for a user who has forgotten their PIN.
 *
 * Wipes every wallet, every cache, the PIN itself, and then restarts
 * the process so the embedded light client comes back up clean and
 * the user lands on the onboarding flow with a fresh slate.
 *
 * The user's funds are unaffected — they live on-chain. Only the
 * device-local key material is destroyed. The user re-enters their
 * 12-word recovery phrase on the onboarding flow to restore access.
 */
@HiltViewModel
class ForgotPinViewModel @Inject constructor(
    private val appRestarter: AppRestarter,
    private val localReset: LocalWalletReset,
    private val logger: Logger,
) : ViewModel() {

    data class UiState(
        val isResetting: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Confirmed reset. Wipes local state in this order:
     *
     *   1. Wallets + per-wallet caches + key material
     *   2. Room caches (header / DAO)
     *   3. PIN (force=true since the wallet's encrypted backup is also gone)
     *   4. Process restart so the JNI light client re-initialises cleanly
     *
     * Step 4 never returns; the launching activity is started before
     * killProcess so the OS visibly relaunches the app onto the
     * onboarding entry point.
     */
    fun executeReset() {
        if (_uiState.value.isResetting) return
        _uiState.update { it.copy(isResetting = true, error = null) }

        viewModelScope.launch {
            try {
                localReset.removeAllWalletsAndPin()
                logger.d(TAG, "factoryReset complete; restarting process")

                // ProcessPhoenix-style restart mirroring switchNetwork in
                // GatewayRepository. JNI state cannot be re-initialised
                // in the same process lifetime, and even if it could the
                // sync polling loop has stale references to wallets that
                // no longer exist — a process restart is the cleanest
                // way to land the user on a deterministic blank state.
                appRestarter.restart()
            } catch (e: Throwable) {
                logger.e(TAG, "factoryReset failed", e)
                _uiState.update {
                    it.copy(
                        isResetting = false,
                        error = e.message ?: "Reset failed. Try again.",
                    )
                }
            }
        }
    }
}
