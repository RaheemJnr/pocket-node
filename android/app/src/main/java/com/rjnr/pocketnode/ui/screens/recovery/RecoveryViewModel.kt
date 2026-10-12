package com.rjnr.pocketnode.ui.screens.recovery

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.gateway.LocalWalletReset
import com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer
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
import java.lang.ref.WeakReference
import javax.inject.Inject

enum class RecoveryStage {
    PIN_ENTRY,
    MNEMONIC_ENTRY,
    /** The app PIN, asked before the phrase restore opens. */
    PHRASE_PIN_ENTRY,
    /** Backups decrypted; their keys are being written back and checked. */
    RESTORING,
    /** Some decrypted keys could not be written back; see [RecoveryUiState.error]. */
    RESTORE_FAILED,
    SUCCESS,
    ERROR
}

data class RecoveryUiState(
    val stage: RecoveryStage = RecoveryStage.PIN_ENTRY,
    val failedAttempts: Int = 0,
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
    /** On [RecoveryStage.RESTORE_FAILED]: Try again can still finish the restore. */
    val canRetryRestore: Boolean = false,
    /**
     * The phone has no screen lock: ask before storing the restored keys
     * with the software-only key (same dialog as wallet import).
     */
    val showNoLockConsent: Boolean = false,
    /** On [RecoveryStage.RESTORE_FAILED]: wallets on this phone still without verified keys. */
    val missingWalletNames: List<String> = emptyList(),
    /** On [RecoveryStage.SUCCESS]: wallets whose keys were written back and verified. */
    val restoredWalletCount: Int = 0,
)

data class RecoveredWallet(
    val walletId: String,
    val material: KeyMaterial
) {
    /** Never prints the key material. */
    override fun toString(): String = "RecoveredWallet(walletId=$walletId)"
}

@HiltViewModel
class RecoveryViewModel(
    private val backupManager: KeyBackupManager,
    private val pinManager: PinManager,
    private val authManager: AuthManager,
    private val localWalletReset: LocalWalletReset,
    private val appRestarter: AppRestarter,
    private val keyRestorer: RecoveredKeyRestorer,
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
        keyRestorer: RecoveredKeyRestorer,
    ) : this(backupManager, pinManager, authManager, localWalletReset, appRestarter, keyRestorer, Dispatchers.IO)

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState

    /** The stored app PIN was entered correctly on this screen. */
    private var pinVerified = false

    /** Wallets whose recovered keys were stored and verified on this screen. */
    private val restoredWalletIds = mutableSetOf<String>()

    /**
     * Decrypted backups, by wallet id. Kept only while an attempt runs (and
     * while its no-lock dialog is open), never in UI state; each entry is
     * dropped once its wallet is restored or its backup does not match.
     */
    private val decrypted = mutableMapOf<String, KeyMaterial>()

    private var restoreInProgress = false

    private enum class PinCheck { VERIFIED, NO_PIN, REFUSED }

    /**
     * What [checkPin] found, and on a refusal the UI change to show it. The
     * caller applies [refusal] (or drops it when the request it belongs to
     * was cancelled meanwhile).
     */
    private class PinResult(
        val check: PinCheck,
        val refusal: ((RecoveryUiState) -> RecoveryUiState)? = null,
    )

    /**
     * Bumped by every phrase-PIN attempt and by [cancelPhrasePin]. A verify
     * that finishes under an older value belongs to a cancelled or replaced
     * request, and its result is dropped. Read and written on the main thread.
     */
    private var phrasePinRequest = 0

    private enum class PhraseGate { OPEN, ASK_PIN, START_FRESH, READ_ERROR }

    init {
        // This screen means the key store needs recovery: keep offering it at
        // every launch until every wallet has verified keys.
        keyRestorer.markIncomplete()
        if (!backupManager.hasAnyBackups()) {
            _uiState.update { it.copy(stage = RecoveryStage.MNEMONIC_ENTRY) }
        }
    }

    /**
     * Attempts to recover wallets from their PIN-encrypted backup blobs,
     * behind the shared [checkPin], then writes the recovered keys back
     * through [RecoveredKeyRestorer]. [activity] drives the screen-lock
     * prompts of the normal key store; only a weak reference is kept.
     */
    fun attemptPinRecovery(pin: CharArray, activity: FragmentActivity) {
        if (restoreInProgress) return
        viewModelScope.launch {
            // Off the main thread: the PIN hash is Argon2id.
            val result = withContext(ioDispatcher) { checkPin(pin, stayOn = RecoveryStage.PIN_ENTRY) }
            result.refusal?.let { refusal ->
                _uiState.update(refusal)
                return@launch
            }
            if (result.check == PinCheck.VERIFIED) {
                pinVerified = true
                // Same as a lock-screen unlock: the session PIN lets the
                // restored wallets write their PIN-encrypted backups.
                authManager.setSessionPin(pin)
            }

            // PIN verified (or no hash to verify against): decrypt off the
            // main thread (PBKDF2/Argon2 is CPU/memory-heavy).
            val recovered = mutableMapOf<String, KeyMaterial>()
            val failed = mutableListOf<String>()
            withContext(ioDispatcher) {
                for (id in backupManager.listBackupWalletIds()) {
                    val material = backupManager.readBackup(id, pin)
                    if (material != null) recovered[id] = material else failed.add(id)
                }
            }
            decrypted.clear()
            decrypted.putAll(recovered)
            _uiState.update { it.copy(failedWalletIds = failed, error = null) }
            restoreRecoveredKeys(activity, noLockConsented = false)
        }
    }

    /**
     * Try again on [RecoveryStage.RESTORE_FAILED]. The decrypted backups were
     * dropped when the attempt ended, so this goes back to the PIN.
     */
    fun retryRestore() {
        val state = _uiState.value
        if (state.stage != RecoveryStage.RESTORE_FAILED || !state.canRetryRestore) return
        _uiState.update { it.copy(stage = RecoveryStage.PIN_ENTRY, error = null, canRetryRestore = false) }
    }

    /** "Continue anyway" on the no-lock dialog: store the keys at V1. */
    fun confirmNoLockConsent(activity: FragmentActivity) {
        if (!_uiState.value.showNoLockConsent) return
        _uiState.update { it.copy(showNoLockConsent = false) }
        viewModelScope.launch { restoreRecoveredKeys(activity, noLockConsented = true) }
    }

    /** "Cancel" on the no-lock dialog: nothing is written, the backups stay. */
    fun dismissNoLockConsent() {
        if (!_uiState.value.showNoLockConsent) return
        decrypted.clear()
        _uiState.update {
            it.copy(
                showNoLockConsent = false,
                stage = RecoveryStage.RESTORE_FAILED,
                error = RESTORE_NO_LOCK_DECLINED,
                canRetryRestore = true,
            )
        }
    }

    /**
     * Brings every wallet row to verified keys: writes the decrypted backups
     * of wallets without keys, and reads back rows written earlier but not
     * verified. Only when [RecoveredKeyRestorer.completeIfDone] holds (every
     * wallet row has verified keys) is the corruption state cleared and the
     * screen left ([RecoveryStage.SUCCESS]). Otherwise it stays on
     * [RecoveryStage.RESTORE_FAILED] naming the wallets still missing, and
     * the decrypted backups are dropped. Backup files are never removed here.
     *
     * On a phone with no screen lock the keys would be stored with the
     * software-only key, so the no-lock dialog is asked first, once per
     * attempt; until it is accepted nothing is written.
     */
    private suspend fun restoreRecoveredKeys(activity: FragmentActivity, noLockConsented: Boolean) {
        if (restoreInProgress) return
        restoreInProgress = true
        val activityRef = WeakReference(activity)
        try {
            _uiState.update { it.copy(stage = RecoveryStage.RESTORING, error = null, canRetryRestore = false) }
            val missing = keyRestorer.missingWallets()
            if (!noLockConsented && missing.any { it.walletId in decrypted } && keyRestorer.needsNoLockConsent()) {
                _uiState.update { it.copy(showNoLockConsent = true) }
                return
            }
            var mismatch = false
            var retryable = false
            var needsConsent = false
            for (wallet in missing) {
                val current = activityRef.get()
                if (current == null || current.isFinishing || current.isDestroyed) {
                    // The screen went away: stop before the next prompt.
                    retryable = true
                    break
                }
                val outcome = try {
                    keyRestorer.restore(current, wallet.walletId, decrypted[wallet.walletId], allowV1 = noLockConsented)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RecoveredKeyRestorer.Outcome.Failed("unexpected error")
                }
                when (outcome) {
                    RecoveredKeyRestorer.Outcome.Restored -> {
                        decrypted.remove(wallet.walletId)
                        restoredWalletIds.add(wallet.walletId)
                    }
                    RecoveredKeyRestorer.Outcome.AddressMismatch -> {
                        decrypted.remove(wallet.walletId)
                        mismatch = true
                    }
                    // The screen lock went away mid-restore: ask, then retry.
                    RecoveredKeyRestorer.Outcome.NeedsNoLockConsent -> needsConsent = true
                    RecoveredKeyRestorer.Outcome.Cancelled,
                    is RecoveredKeyRestorer.Outcome.Failed -> retryable = true
                    RecoveredKeyRestorer.Outcome.NoBackup,
                    RecoveredKeyRestorer.Outcome.NoWalletRow -> Unit
                }
            }

            if (needsConsent && !mismatch && !retryable) {
                _uiState.update { it.copy(showNoLockConsent = true) }
                return
            }
            val walletIds = keyRestorer.walletIds()
            // Decrypted backups with no wallet row on this phone; never written.
            val skipped = decrypted.keys.filter { it !in walletIds }
            if (keyRestorer.completeIfDone()) {
                decrypted.clear()
                _uiState.update {
                    it.copy(
                        stage = RecoveryStage.SUCCESS,
                        restoredWalletCount = restoredWalletIds.size,
                        missingWalletNames = emptyList(),
                        failedWalletIds = (it.failedWalletIds + skipped).distinct(),
                        error = null,
                    )
                }
                return
            }
            decrypted.clear()
            val stillMissing = keyRestorer.missingWallets()
            val error = when {
                walletIds.isEmpty() -> RESTORE_NO_WALLET_ERROR
                mismatch -> RESTORE_MISMATCH_ERROR
                retryable || needsConsent -> RESTORE_RETRY_ERROR
                else -> RESTORE_MISSING_ERROR
            }
            _uiState.update {
                it.copy(
                    stage = RecoveryStage.RESTORE_FAILED,
                    error = error,
                    canRetryRestore = retryable || needsConsent,
                    missingWalletNames = stillMissing.map { w -> w.name },
                )
            }
        } finally {
            restoreInProgress = false
        }
    }

    override fun onCleared() {
        decrypted.clear()
        super.onCleared()
    }

    /**
     * The one PIN check on this screen, shared by the backup restore and the
     * phrase restore. Attempts are gated through [PinManager] (#319) so they
     * share the same persistent, cross-session escalating lockout as the lock
     * screen (30s ... permanent at 10 failures). Previously the backup path
     * decrypted directly, throttled only by an in-memory counter that reset on
     * every screen recreate.
     *
     * Touches no UI state itself: a refusal comes back as [PinResult.refusal].
     * A wrong PIN stays on [stayOn] unless it was the one that made the
     * lockout permanent. A PIN store that cannot be read refuses with a retry
     * message. A wrong PIN is counted inside [PinManager.verifyPinFromChars]
     * whatever the caller then does with the result.
     */
    private fun checkPin(pin: CharArray, stayOn: RecoveryStage): PinResult =
        try {
            verifyPinOrRefuse(pin, stayOn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PinResult(PinCheck.REFUSED) { it.copy(stage = stayOn, error = PIN_READ_ERROR) }
        }

    private fun verifyPinOrRefuse(pin: CharArray, stayOn: RecoveryStage): PinResult {
        // Persistent lockout shared with the normal PIN gate.
        if (pinManager.isLockedOut()) {
            val secs = (pinManager.getLockoutRemainingMs() / 1000).coerceAtLeast(1)
            return PinResult(PinCheck.REFUSED) { it.copy(error = "Too many attempts. Try again in ${secs}s.") }
        }

        // Verify against the stored PIN hash so failures escalate the SAME
        // lockout the lock screen uses. The degenerate "no PIN hash but
        // backups exist" case can't be brute-forced here (nothing to verify
        // against), so the backup path falls through to a decrypt attempt.
        if (!pinManager.hasPin()) return PinResult(PinCheck.NO_PIN)
        if (!pinManager.verifyPinFromChars(pin)) {
            // verifyPinFromChars already recorded the failed attempt and
            // advanced the persistent lockout/escalation counters.
            val permanent = pinManager.isPermanentlyLocked()
            val error = if (permanent) {
                "Too many failed attempts. Please enter your recovery phrase."
            } else if (pinManager.isLockedOut()) {
                val secs = (pinManager.getLockoutRemainingMs() / 1000).coerceAtLeast(1)
                "Incorrect PIN. Locked for ${secs}s."
            } else {
                "Incorrect PIN. ${pinManager.getRemainingAttempts()} attempts remaining."
            }
            return PinResult(PinCheck.REFUSED) {
                it.copy(
                    failedAttempts = it.failedAttempts + 1,
                    stage = if (permanent) RecoveryStage.MNEMONIC_ENTRY else stayOn,
                    stageBeforePhrasePin = if (permanent) null else it.stageBeforePhrasePin,
                    error = error,
                )
            }
        }
        return PinResult(PinCheck.VERIFIED)
    }

    /**
     * "Restore from phrase". The phrase restore keeps the wallets already in
     * the app, so it opens only once the app PIN is verified here (or when no
     * PIN is set). Only when the PIN is permanently locked does it offer to
     * start fresh instead. A PIN store that cannot be read is a retry, never
     * a reason to offer the wipe.
     */
    fun requestPhraseRestore() {
        decrypted.clear()
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

    /**
     * The PIN entered on [RecoveryStage.PHRASE_PIN_ENTRY]. If Cancel (or a
     * newer attempt) comes while the PIN is being checked, the result is
     * dropped: nothing opens, no session PIN, no stage or error change.
     */
    fun attemptPhrasePin(pin: CharArray) {
        if (_uiState.value.stage != RecoveryStage.PHRASE_PIN_ENTRY) return
        val request = ++phrasePinRequest
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                checkPin(pin, stayOn = RecoveryStage.PHRASE_PIN_ENTRY)
            }
            if (request != phrasePinRequest || _uiState.value.stage != RecoveryStage.PHRASE_PIN_ENTRY) {
                return@launch
            }
            result.refusal?.let { refusal ->
                _uiState.update(refusal)
                return@launch
            }
            if (result.check == PinCheck.VERIFIED) {
                pinVerified = true
                // Same as a lock-screen unlock: the session PIN lets the
                // restored wallet write its PIN-encrypted backup.
                authManager.setSessionPin(pin)
            }
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
        // Drops the result of a PIN check still running for this step.
        phrasePinRequest++
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
        const val RESTORE_MISMATCH_ERROR =
            "A backup does not match its wallet, so it was not used. Your backup files are kept. " +
                "Restore that wallet with its recovery phrase."
        const val RESTORE_RETRY_ERROR =
            "Some wallet keys could not be saved yet. Your backup files are kept. Try again."
        const val RESTORE_NO_LOCK_DECLINED =
            "Nothing was saved. Set a screen lock on this phone, then tap Try again. " +
                "Your backup files are kept."
        const val RESTORE_MISSING_ERROR =
            "Some wallets on this phone still have no keys and no backup that could be opened. " +
                "Restore them with their recovery phrase."
        const val RESTORE_NO_WALLET_ERROR =
            "These backups do not belong to a wallet on this phone. Your backup files are kept. " +
                "Restore with your recovery phrase."
    }
}
