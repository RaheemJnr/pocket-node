package com.rjnr.pocketnode.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

internal enum class PinChangeStep { CURRENT, NEW, CONFIRM }
internal data class PinChangeState(
    val step: PinChangeStep = PinChangeStep.CURRENT,
    val digits: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val needsUnlock: Boolean = false,
)

@HiltViewModel
class PinChangeViewModel @Inject constructor(private val pins: PinManager, private val auth: AuthManager) : ViewModel() {
    private val state = MutableStateFlow(PinChangeState())
    internal val uiState = state.asStateFlow()
    private var authorization: PinManager.ChangeAuthorization? = null
    private var candidate: CharArray? = null

    init {
        viewModelScope.onEachReauthLock {
            clearCandidate()
            state.value = PinChangeState(busy = state.value.busy,
                message = "Unlock and enter your current PIN to start again.")
        }
    }

    internal fun digit(digit: Char) {
        val current = state.value
        if (current.busy || current.needsUnlock || digit !in '0'..'9' || current.digits.length >= PinManager.PIN_LENGTH) return
        val digits = current.digits + digit
        state.value = current.copy(digits = digits, message = null)
        if (digits.length == PinManager.PIN_LENGTH) submit(digits)
    }

    internal fun deleteDigit() {
        if (!state.value.busy && !state.value.needsUnlock) state.update { it.copy(digits = it.digits.dropLast(1)) }
    }

    private fun submit(digits: String) {
        val lock = ReauthLockEvents.locks.value
        val generation = auth.sessionGeneration()
        when (state.value.step) {
            PinChangeStep.CURRENT -> {
                state.update { it.copy(busy = true, digits = "") }
                viewModelScope.launch {
                    val pin = digits.toCharArray()
                    val proof = try { withContext(Dispatchers.Default) { pins.authorizeChange(pin, auth, generation) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    finally { pin.fill('\u0000') }
                    if (lock != ReauthLockEvents.locks.value ||
                        (proof != null && !auth.isSessionCurrent(proof.generation)) ||
                        (proof == null && auth.sessionGeneration() != generation)
                    ) {
                        clearCandidate()
                        state.value = PinChangeState(message = "Unlock and start again.")
                    } else if (proof == null) {
                        state.value = PinChangeState(message = if (pins.isLockedOut())
                            "PIN attempts are locked. Wait or use Forgot PIN from the unlock screen."
                            else "Current PIN could not be verified. ${pins.getRemainingAttempts()} attempts remaining.")
                    } else {
                        authorization = proof
                        state.value = PinChangeState(step = PinChangeStep.NEW)
                    }
                }
            }
            PinChangeStep.NEW -> {
                candidate?.fill('\u0000')
                candidate = digits.toCharArray()
                state.value = PinChangeState(step = PinChangeStep.CONFIRM)
            }
            PinChangeStep.CONFIRM -> {
                val proof = authorization
                val pin = digits.toCharArray()
                if (proof == null || candidate?.contentEquals(pin) != true) {
                    pin.fill('\u0000')
                    state.update { it.copy(digits = "", message = "PINs do not match. Try again.") }
                    return
                }
                state.update { it.copy(busy = true, digits = "") }
                viewModelScope.launch {
                    val result = try { withContext(Dispatchers.Default) { pins.changePin(proof, pin) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { PinManager.ChangeResult.REJECTED }
                    finally { pin.fill('\u0000') }
                    clearCandidate()
                    // The claim advances generation once. Any further revocation invalidates UI
                    // completion too, even if it did not emit a navigation lock event.
                    if (lock != ReauthLockEvents.locks.value ||
                        (result != PinManager.ChangeResult.REJECTED && auth.sessionGeneration() != proof.generation + 1)
                    ) {
                        state.value = PinChangeState(message = "Change interrupted. Unlock and start again.")
                        return@launch
                    }
                    state.value = when (result) {
                        PinManager.ChangeResult.CHANGED -> PinChangeState(needsUnlock = true,
                            message = "PIN changed. Unlock with your new PIN.")
                        PinManager.ChangeResult.FAILED_RESTORED -> PinChangeState(needsUnlock = true,
                            message = "PIN change could not be saved. Your previous PIN was restored. Unlock and try again.")
                        PinManager.ChangeResult.FAILED_UNCERTAIN -> PinChangeState(needsUnlock = true,
                            message = "PIN storage could not be confirmed. Restart the app before unlocking; your previous or new PIN may be required.")
                        PinManager.ChangeResult.REJECTED -> PinChangeState(message = "Change was not authorized. Unlock and start again.")
                    }
                }
            }
        }
    }

    private fun clearCandidate() {
        candidate?.fill('\u0000')
        candidate = null
        authorization = null
    }

    override fun onCleared() { clearCandidate() }
}
