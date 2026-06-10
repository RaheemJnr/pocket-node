package com.rjnr.pocketnode.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyMaterial
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class RecoveryStage {
    PIN_ENTRY,
    MNEMONIC_ENTRY,
    SUCCESS,
    ERROR
}

data class RecoveryUiState(
    val stage: RecoveryStage = RecoveryStage.PIN_ENTRY,
    val failedAttempts: Int = 0,
    val recoveredWallets: List<RecoveredWallet> = emptyList(),
    val failedWalletIds: List<String> = emptyList(),
    val error: String? = null
)

data class RecoveredWallet(
    val walletId: String,
    val material: KeyMaterial
)

@HiltViewModel
class RecoveryViewModel(
    private val backupManager: KeyBackupManager,
    private val pinManager: PinManager,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    /** Hilt entry point — backup decrypt runs on [Dispatchers.IO]. */
    @Inject
    constructor(
        backupManager: KeyBackupManager,
        pinManager: PinManager
    ) : this(backupManager, pinManager, Dispatchers.IO)

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState

    init {
        if (!backupManager.hasAnyBackups()) {
            _uiState.update { it.copy(stage = RecoveryStage.MNEMONIC_ENTRY) }
        }
    }

    /**
     * Attempts to recover wallets from their PIN-encrypted backup blobs.
     *
     * PIN attempts are gated through [PinManager] (#319) so they share the
     * same persistent, cross-session escalating lockout as the lock screen
     * (30s ... permanent at 10 failures). Previously this path decrypted
     * backups directly, throttled only by an in-memory counter that reset on
     * every screen recreate — an on-device attacker could brute-force the
     * 6-digit PIN indefinitely, bounded only by KDF cost.
     */
    fun attemptPinRecovery(pin: CharArray) {
        viewModelScope.launch {
            // Persistent lockout shared with the normal PIN gate.
            if (pinManager.isLockedOut()) {
                val secs = (pinManager.getLockoutRemainingMs() / 1000).coerceAtLeast(1)
                _uiState.update {
                    it.copy(error = "Too many attempts. Try again in ${secs}s.")
                }
                return@launch
            }

            // Verify against the stored PIN hash so failures escalate the SAME
            // lockout the lock screen uses. The degenerate "no PIN hash but
            // backups exist" case can't be brute-forced here (nothing to verify
            // against), so we fall through to a decrypt attempt in that case.
            if (pinManager.hasPin() && !pinManager.verifyPinFromChars(pin)) {
                // verifyPinFromChars already recorded the failed attempt and
                // advanced the persistent lockout/escalation counters.
                val newAttempts = _uiState.value.failedAttempts + 1
                val permanent = pinManager.isPermanentlyLocked()
                _uiState.update {
                    it.copy(
                        failedAttempts = newAttempts,
                        stage = if (permanent) RecoveryStage.MNEMONIC_ENTRY else RecoveryStage.PIN_ENTRY,
                        error = if (permanent) {
                            "Too many failed attempts. Please enter your recovery phrase."
                        } else if (pinManager.isLockedOut()) {
                            val secs = (pinManager.getLockoutRemainingMs() / 1000).coerceAtLeast(1)
                            "Incorrect PIN. Locked for ${secs}s."
                        } else {
                            "Incorrect PIN. ${pinManager.getRemainingAttempts()} attempts remaining."
                        }
                    )
                }
                return@launch
            }

            // PIN verified (or no hash to verify against) — decrypt off the main
            // thread (PBKDF2/Argon2 is CPU/memory-heavy).
            val recovered = mutableListOf<RecoveredWallet>()
            val failed = mutableListOf<String>()
            withContext(ioDispatcher) {
                for (id in backupManager.listBackupWalletIds()) {
                    val material = backupManager.readBackup(id, pin)
                    if (material != null) {
                        recovered.add(RecoveredWallet(id, material))
                    } else {
                        failed.add(id)
                    }
                }
            }

            if (recovered.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        stage = RecoveryStage.SUCCESS,
                        recoveredWallets = recovered,
                        failedWalletIds = failed
                    )
                }
            } else {
                // Verified PIN but nothing decrypted (corrupt blobs, or the
                // degenerate no-hash case with a wrong PIN). Fall back to the
                // mnemonic so the user isn't stuck.
                _uiState.update {
                    it.copy(
                        stage = RecoveryStage.MNEMONIC_ENTRY,
                        error = "Could not recover from PIN. Please enter your recovery phrase."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
