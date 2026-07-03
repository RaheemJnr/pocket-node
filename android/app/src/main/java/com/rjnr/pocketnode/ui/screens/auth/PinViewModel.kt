package com.rjnr.pocketnode.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class PinMode { SETUP, CONFIRM, VERIFY }

data class PinUiState(
    val mode: PinMode = PinMode.VERIFY,
    val enteredDigits: String = "",
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val isLockedOut: Boolean = false,
    val lockoutRemainingSeconds: Int = 0,
    val remainingAttempts: Int = PinManager.MAX_ATTEMPTS,
    /** True at the permanent-lockout threshold (10+ failures): no countdown, recovery only. */
    val isPermanentlyLocked: Boolean = false,
    /**
     * Drives the recovery popup that points the user at "reset and restore
     * from seed" once they run out of attempts. Set when attempts hit zero or
     * on permanent lock, so the escape hatch is impossible to miss (#373).
     */
    val showRecoveryDialog: Boolean = false,
    val pinComplete: Boolean = false,
    /**
     * True while the Argon2id KDF is computing the PIN hash. The PIN entry
     * surface shows a spinner during this window (~300-600 ms on a real
     * device). Used to block further keypad input while a verify is in flight.
     */
    val isVerifying: Boolean = false,
    val title: String = "Enter PIN",
    val subtitle: String? = null
)

@HiltViewModel
class PinViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PinUiState())
    val uiState: StateFlow<PinUiState> = _uiState.asStateFlow()

    private var setupPin: String? = null
    private var lockoutTimerJob: Job? = null

    fun setMode(mode: PinMode) {
        val (title, subtitle) = when (mode) {
            PinMode.SETUP -> "Create PIN" to "Choose a 6-digit PIN"
            PinMode.CONFIRM -> "Confirm PIN" to "Re-enter your PIN"
            PinMode.VERIFY -> "Enter PIN" to null
        }
        _uiState.update {
            it.copy(
                mode = mode,
                title = title,
                subtitle = subtitle,
                enteredDigits = "",
                pinComplete = false,
                isError = false,
                errorMessage = null
            )
        }
        if (mode == PinMode.VERIFY) {
            refreshLockoutState()
        }
    }

    fun setSetupPin(pin: String) {
        setupPin = pin
    }

    fun getEnteredPin(): String = _uiState.value.enteredDigits

    fun consumePinComplete() {
        _uiState.update { it.copy(pinComplete = false) }
    }

    fun onDigitEntered(digit: Char) {
        val current = _uiState.value
        if (current.isLockedOut || current.isVerifying ||
            current.enteredDigits.length >= PinManager.PIN_LENGTH
        ) return

        val newDigits = current.enteredDigits + digit
        _uiState.update { it.copy(enteredDigits = newDigits, isError = false, errorMessage = null) }

        if (newDigits.length == PinManager.PIN_LENGTH) {
            handlePinSubmit(newDigits)
        }
    }

    fun onDeleteDigit() {
        val current = _uiState.value
        if (current.isVerifying || current.enteredDigits.isEmpty()) return
        _uiState.update { it.copy(enteredDigits = current.enteredDigits.dropLast(1)) }
    }

    private fun handlePinSubmit(pin: String) {
        val mode = _uiState.value.mode
        when (mode) {
            PinMode.SETUP -> {
                _uiState.update { it.copy(pinComplete = true) }
            }

            PinMode.CONFIRM -> {
                if (pin != setupPin) {
                    showError("PINs don't match. Try again.")
                    return
                }
                _uiState.update { it.copy(isVerifying = true) }
                viewModelScope.launch {
                    // Argon2id KDF takes ~300-600 ms on a real device. Run off
                    // the main thread so the keypad doesn't freeze.
                    withContext(Dispatchers.Default) { pinManager.setPin(pin) }
                    // Seed the session PIN so KeyManager.writeBackupIfPinAvailable can
                    // immediately encrypt KeyBackupManager material for any future
                    // wallet create/import / mnemonic-backed-up writes.
                    authManager.setSessionPin(pin.toCharArray())
                    _uiState.update { it.copy(isVerifying = false, pinComplete = true) }
                }
            }

            PinMode.VERIFY -> {
                _uiState.update { it.copy(isVerifying = true) }
                viewModelScope.launch {
                    val ok = withContext(Dispatchers.Default) { pinManager.verifyPin(pin) }
                    if (ok) {
                        authManager.setSessionPin(pin.toCharArray())
                        _uiState.update { it.copy(isVerifying = false, pinComplete = true) }
                        return@launch
                    }

                    _uiState.update { it.copy(isVerifying = false) }
                    val remaining = pinManager.getRemainingAttempts()
                    // Surface the recovery popup the moment attempts run out, so
                    // the user is never staring at a keypad with no way forward.
                    val outOfAttempts = remaining == 0 || pinManager.isPermanentlyLocked()
                    if (pinManager.isLockedOut()) {
                        if (outOfAttempts) {
                            _uiState.update { it.copy(showRecoveryDialog = true) }
                        }
                        startLockoutTimer()
                    } else {
                        _uiState.update {
                            it.copy(
                                isError = true,
                                errorMessage = "Wrong PIN. $remaining attempts remaining.",
                                enteredDigits = "",
                                remainingAttempts = remaining,
                                showRecoveryDialog = it.showRecoveryDialog || outOfAttempts
                            )
                        }
                        delay(500)
                        _uiState.update { it.copy(isError = false) }
                    }
                }
            }
        }
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(isError = true, errorMessage = message, enteredDigits = "") }
        viewModelScope.launch {
            delay(500)
            _uiState.update { it.copy(isError = false) }
        }
    }

    private fun refreshLockoutState() {
        if (pinManager.isLockedOut()) {
            startLockoutTimer()
        } else {
            val remaining = pinManager.getRemainingAttempts()
            _uiState.update {
                it.copy(
                    isLockedOut = false,
                    isPermanentlyLocked = pinManager.isPermanentlyLocked(),
                    lockoutRemainingSeconds = 0,
                    remainingAttempts = remaining,
                    // Re-open the recovery prompt for a user who returns to the
                    // screen already out of attempts.
                    showRecoveryDialog = it.showRecoveryDialog ||
                        remaining == 0 || pinManager.isPermanentlyLocked()
                )
            }
        }
    }

    private fun startLockoutTimer() {
        lockoutTimerJob?.cancel()
        val permanent = pinManager.isPermanentlyLocked()
        _uiState.update {
            it.copy(
                isLockedOut = true,
                isPermanentlyLocked = permanent,
                enteredDigits = "",
                errorMessage = null,
                showRecoveryDialog = it.showRecoveryDialog || permanent
            )
        }
        if (permanent) {
            // No countdown: the lockout never expires. Recovery (reset +
            // restore from seed) is the only path forward.
            return
        }
        lockoutTimerJob = viewModelScope.launch {
            while (pinManager.isLockedOut()) {
                val remainingMs = pinManager.getLockoutRemainingMs()
                _uiState.update {
                    it.copy(lockoutRemainingSeconds = ((remainingMs + 999) / 1000).toInt())
                }
                delay(1000)
            }
            _uiState.update {
                it.copy(
                    isLockedOut = false,
                    lockoutRemainingSeconds = 0,
                    remainingAttempts = pinManager.getRemainingAttempts()
                )
            }
        }
    }

    fun dismissRecoveryDialog() {
        _uiState.update { it.copy(showRecoveryDialog = false) }
    }
}
