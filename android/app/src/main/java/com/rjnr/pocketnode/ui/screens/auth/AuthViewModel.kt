package com.rjnr.pocketnode.ui.screens.auth

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.migration.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.migration.KeystoreV2MigrationRunner
import com.rjnr.pocketnode.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val showBiometricButton: Boolean = false,
    val showPinFallback: Boolean = true,
    val authSuccess: Boolean = false,
    val error: UiMessage? = null,
    /** True while the V2 wallet migration is running per-wallet prompts. */
    val isMigrating: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val pinManager: PinManager,
    private val migrationRunner: KeystoreV2MigrationRunner,
    private val migrationHelper: KeystoreV2MigrationHelper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        val biometricReady = authManager.isBiometricEnrolled() && authManager.isBiometricEnabled()
        _uiState.update {
            it.copy(
                showBiometricButton = biometricReady,
                showPinFallback = pinManager.hasPin()
            )
        }
    }

    fun shouldAutoTriggerBiometric(): Boolean = _uiState.value.showBiometricButton

    fun onBiometricSuccess() {
        _uiState.update { it.copy(authSuccess = true) }
    }

    fun onBiometricFailed(errorMessage: String) {
        _uiState.update { it.copy(error = UiMessage.Raw(errorMessage)) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Runs the v1.6.x → v1.7.0 Keystore V2 migration if any V1 wallets are
     * still pending. Called from [AuthScreen] right after the user finishes
     * the unlock prompt — the user is already in an authentication mindset,
     * which is the right moment to prompt for the per-wallet re-encrypt
     * (#213 sub-PR 5).
     *
     * No-op when migration is already complete or no V1 wallets exist.
     * Surfaces the runner's [KeystoreV2MigrationRunner.Outcome] so the UI
     * can decide how to recover: a Cancelled outcome leaves the app
     * usable on V1 wallets but with V2-only call sites still gated;
     * subsequent app starts will re-prompt until the user completes.
     */
    fun runMigrationIfNeeded(activity: FragmentActivity, onComplete: () -> Unit) {
        viewModelScope.launch {
            if (migrationHelper.isMigrationComplete()) {
                onComplete()
                return@launch
            }
            val pending = migrationHelper.pendingWalletIds()
            if (pending.isEmpty()) {
                // Empty DB (fresh install on v1.7.0) — finalize to mark
                // the migration complete so future starts don't re-check.
                migrationHelper.finalize()
                onComplete()
                return@launch
            }
            _uiState.update { it.copy(isMigrating = true) }
            try {
                val outcome = migrationRunner.runMigration(activity)
                when (outcome) {
                    is KeystoreV2MigrationRunner.Outcome.Completed,
                    is KeystoreV2MigrationRunner.Outcome.NothingToDo -> Unit
                    is KeystoreV2MigrationRunner.Outcome.Cancelled -> {
                        _uiState.update {
                            it.copy(error = UiMessage.Resource(R.string.vm_error_migration_incomplete))
                        }
                    }
                    is KeystoreV2MigrationRunner.Outcome.Failed -> {
                        Log.e(TAG, "Migration failed: ${outcome.reason}")
                        _uiState.update {
                            it.copy(error = UiMessage.Resource(R.string.vm_error_migration_failed, listOf(outcome.reason)))
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(isMigrating = false) }
                onComplete()
            }
        }
    }

    companion object {
        private const val TAG = "AuthViewModel"
    }
}
