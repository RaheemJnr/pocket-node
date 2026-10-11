package com.rjnr.pocketnode.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.gateway.LocalWalletReset
import com.rjnr.pocketnode.util.AppRestarter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    /** The app PIN, asked before the phrase restore opens. */
    PHRASE_PIN_ENTRY,
    SUCCESS,
    ERROR
}

data class RecoveryUiState(
    val stage: RecoveryStage = RecoveryStage.PIN_ENTRY,
    val failedAttempts: Int = 0,
    val recoveredWallets: List<RecoveredWallet> = emptyList(),
    val failedWalletIds: List<String> = emptyList(),
    val error: String? = null,
    /** Where Cancel on [RecoveryStage.PHRASE_PIN_ENTRY] goes back to. */
    val stageBeforePhrasePin: RecoveryStage? = null,
    /** The PIN cannot be verified: confirm removing the wallets first. */
    val showStartFreshConfirm: Boolean = false,
    val isStartingFresh: Boolean = false,
    val startFreshFailed: Boolean = false,
    /** One-shot: the phrase restore may open now (see [RecoveryViewModel.onPhraseRestoreOpened]). */
    val phraseRestoreReady: Boolean = false,
)

data class RecoveredWallet(
    val walletId: String,
    val material: KeyMaterial
)

@HiltViewModel
class RecoveryViewModel(
    private val backupManager: KeyBackupManager,
    private val pinManager: PinManager,
    private val authManager: AuthManager,
    private val localWalletReset: LocalWalletReset,
    private val appRestarter: AppRestarter,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    /** Hilt entry point — backup decrypt runs on [Dispatchers.IO]. */
    @Inject
    constructor(
        backupManager: KeyBackupManager,
        pinManager: PinManager,
        authManager: AuthManager,
        localWalletReset: LocalWalletReset,
        appRestarter: AppRestarter,
    ) : this(backupManager, pinManager, authManager, localWalletReset, appRestarter, Dispatchers.IO)

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState

    /** The stored app PIN was entered correctly on this screen. */
    private var pinVerified = false

    private enum class PinCheck { VERIFIED, NO_PIN, REFUSED }

    private enum class PhraseGate { OPEN, ASK_PIN, START_FRESH, READ_ERROR }

    init {
        if (!backupManager.hasAnyBackups()) {
            _uiState.update { it.copy(stage = RecoveryStage.MNEMONIC_ENTRY) }
        }
    }

    /**
     * Attempts to recover wallets from their PIN-encrypted backup blobs,
     * behind the shared [checkPin].
     */
    fun attemptPinRecovery(pin: CharArray) {
        viewModelScope.launch {
            // Off the main thread: the PIN hash is Argon2id.
            val check = withContext(ioDispatcher) { checkPin(pin, stayOn = RecoveryStage.PIN_ENTRY) }
            if (check == PinCheck.REFUSED) return@launch
            // Same as a lock-screen unlock: the session PIN lets the restored
            // wallets write their PIN-encrypted backups.
            if (check == PinCheck.VERIFIED) authManager.setSessionPin(pin)

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

    /**
     * The one PIN check on this screen, shared by the backup restore and the
     * phrase restore. Attempts are gated through [PinManager] (#319) so they
     * share the same persistent, cross-session escalating lockout as the lock
     * screen (30s ... permanent at 10 failures). Previously the backup path
     * decrypted directly, throttled only by an in-memory counter that reset on
     * every screen recreate.
     *
     * On a refusal the error is already in the state; a wrong PIN stays on
     * [stayOn] unless it was the one that made the lockout permanent. A PIN
     * store that cannot be read refuses with a retry message.
     */
    private fun checkPin(pin: CharArray, stayOn: RecoveryStage): PinCheck =
        try {
            verifyPinOrRefuse(pin, stayOn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(stage = stayOn, error = PIN_READ_ERROR) }
            PinCheck.REFUSED
        }

    private fun verifyPinOrRefuse(pin: CharArray, stayOn: RecoveryStage): PinCheck {
        // Persistent lockout shared with the normal PIN gate.
        if (pinManager.isLockedOut()) {
            val secs = (pinManager.getLockoutRemainingMs() / 1000).coerceAtLeast(1)
            _uiState.update {
                it.copy(error = "Too many attempts. Try again in ${secs}s.")
            }
            return PinCheck.REFUSED
        }

        // Verify against the stored PIN hash so failures escalate the SAME
        // lockout the lock screen uses. The degenerate "no PIN hash but
        // backups exist" case can't be brute-forced here (nothing to verify
        // against), so the backup path falls through to a decrypt attempt.
        if (!pinManager.hasPin()) return PinCheck.NO_PIN
        if (!pinManager.verifyPinFromChars(pin)) {
            // verifyPinFromChars already recorded the failed attempt and
            // advanced the persistent lockout/escalation counters.
            val newAttempts = _uiState.value.failedAttempts + 1
            val permanent = pinManager.isPermanentlyLocked()
            _uiState.update {
                it.copy(
                    failedAttempts = newAttempts,
                    stage = if (permanent) RecoveryStage.MNEMONIC_ENTRY else stayOn,
                    stageBeforePhrasePin = if (permanent) null else it.stageBeforePhrasePin,
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
            return PinCheck.REFUSED
        }
        pinVerified = true
        return PinCheck.VERIFIED
    }

    /**
     * "Restore from phrase". The phrase restore keeps the wallets already in
     * the app, so it opens only once the app PIN is verified here (or when no
     * PIN is set). Only when the PIN is permanently locked does it offer to
     * start fresh instead. A PIN store that cannot be read is a retry, never
     * a reason to offer the wipe.
     */
    fun requestPhraseRestore() {
        val state = _uiState.value
        if (state.isStartingFresh || state.phraseRestoreReady) return
        when (phraseGate()) {
            PhraseGate.OPEN -> _uiState.update { it.copy(phraseRestoreReady = true) }
            PhraseGate.ASK_PIN -> _uiState.update {
                if (it.stage == RecoveryStage.PHRASE_PIN_ENTRY) it else it.copy(
                    stage = RecoveryStage.PHRASE_PIN_ENTRY,
                    stageBeforePhrasePin = it.stage,
                    error = null,
                )
            }
            PhraseGate.START_FRESH -> _uiState.update {
                it.copy(showStartFreshConfirm = true, startFreshFailed = false)
            }
            PhraseGate.READ_ERROR -> _uiState.update {
                it.copy(
                    stage = RecoveryStage.PHRASE_PIN_ENTRY,
                    stageBeforePhrasePin = if (it.stage == RecoveryStage.PHRASE_PIN_ENTRY) {
                        it.stageBeforePhrasePin
                    } else {
                        it.stage
                    },
                    error = PIN_READ_ERROR,
                )
            }
        }
    }

    private fun phraseGate(): PhraseGate {
        if (pinVerified) return PhraseGate.OPEN
        val hasPin = runCatching { pinManager.hasPin() }.getOrElse { return PhraseGate.READ_ERROR }
        if (!hasPin) return PhraseGate.OPEN
        val permanent = runCatching { pinManager.isPermanentlyLocked() }
            .getOrElse { return PhraseGate.READ_ERROR }
        return if (permanent) PhraseGate.START_FRESH else PhraseGate.ASK_PIN
    }

    /** The PIN entered on [RecoveryStage.PHRASE_PIN_ENTRY]. */
    fun attemptPhrasePin(pin: CharArray) {
        viewModelScope.launch {
            if (_uiState.value.stage != RecoveryStage.PHRASE_PIN_ENTRY) return@launch
            val check = withContext(ioDispatcher) {
                checkPin(pin, stayOn = RecoveryStage.PHRASE_PIN_ENTRY)
            }
            if (check == PinCheck.REFUSED) return@launch
            // Same as a lock-screen unlock: the session PIN lets the restored
            // wallet write its PIN-encrypted backup.
            if (check == PinCheck.VERIFIED) authManager.setSessionPin(pin)
            _uiState.update {
                it.copy(
                    stage = it.stageBeforePhrasePin ?: RecoveryStage.MNEMONIC_ENTRY,
                    stageBeforePhrasePin = null,
                    error = null,
                    phraseRestoreReady = true,
                )
            }
        }
    }

    fun cancelPhrasePin() {
        _uiState.update {
            if (it.stage != RecoveryStage.PHRASE_PIN_ENTRY) it else it.copy(
                stage = it.stageBeforePhrasePin ?: RecoveryStage.MNEMONIC_ENTRY,
                stageBeforePhrasePin = null,
                error = null,
            )
        }
    }

    /**
     * "Remove and continue": remove every wallet, key and the PIN, then
     * restart the app so it comes back up as a fresh install.
     */
    fun confirmStartFresh() {
        val state = _uiState.value
        if (!state.showStartFreshConfirm || state.isStartingFresh) return
        _uiState.update { it.copy(isStartingFresh = true, startFreshFailed = false) }
        viewModelScope.launch {
            val ok = try {
                withContext(ioDispatcher) { localWalletReset.removeAllWalletsAndPin() }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (ok) {
                pinVerified = false
                authManager.clearSession()
                // The light client, the sync loop and the start-up route all
                // still describe the removed wallets: start a clean process.
                // Stays in progress until the process goes away.
                appRestarter.restart()
                return@launch
            }
            _uiState.update { it.copy(isStartingFresh = false, startFreshFailed = true) }
        }
    }

    fun dismissStartFresh() {
        _uiState.update {
            if (it.isStartingFresh) it else it.copy(showStartFreshConfirm = false, startFreshFailed = false)
        }
    }

    /** The screen has opened the phrase restore. */
    fun onPhraseRestoreOpened() {
        _uiState.update { it.copy(phraseRestoreReady = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private companion object {
        const val PIN_READ_ERROR = "Could not check your PIN. Try again."
    }
}
