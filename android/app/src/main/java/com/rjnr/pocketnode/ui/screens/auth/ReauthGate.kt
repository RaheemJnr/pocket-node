package com.rjnr.pocketnode.ui.screens.auth

import androidx.compose.runtime.MutableState

/**
 * State holder for the re-auth lock overlay (#524).
 *
 * Before this fix, backgrounding the app and returning navigated to
 * `Screen.Auth` with `popUpTo(Screen.Main) { inclusive = true }`, throwing
 * away the back stack; a successful unlock then landed on Home regardless of
 * where the user had been. The fix draws the lock as a Box overlay above
 * `CkbNavGraph` in MainActivity instead of a navigation destination, so the
 * gate never touches a `NavController`: [ReauthGate]'s public API has no
 * navigation dependency at all, by construction.
 *
 * [locked] and [showingPinFallback] are backed by caller-supplied
 * [MutableState] (typically `rememberSaveable`-created in MainActivity) so
 * this class stays a thin behaviour wrapper: MainActivity owns where the
 * state lives (and therefore whether/how it survives configuration change or
 * process death), this class only owns the transitions.
 */
class ReauthGate(
    private val lockedState: MutableState<Boolean>,
    private val pinFallbackState: MutableState<Boolean>,
) {
    val locked: Boolean get() = lockedState.value
    val showingPinFallback: Boolean get() = pinFallbackState.value

    /** Called from `onStop()` when a wallet and a PIN exist: arm the gate. */
    fun lock() {
        lockedState.value = true
    }

    /** Called on a successful biometric or PIN unlock: clear the gate. */
    fun unlock() {
        lockedState.value = false
        pinFallbackState.value = false
    }

    /** The user tapped "Use PIN" on the biometric level of the gate. */
    fun showPinFallback() {
        pinFallbackState.value = true
    }

    sealed class BackAction {
        /** Consumed within the gate; the overlay stays up. */
        object HandledWithinGate : BackAction()

        /**
         * Nothing left to pop within the gate. The caller must NOT dismiss
         * the overlay here: MainActivity maps this to finishing the
         * activity, matching the pre-#524 behaviour where Auth, as the back
         * stack's sole entry after the old popUpTo(Main), had nothing to pop
         * to either.
         */
        object LeaveGate : BackAction()
    }

    /**
     * What system back should do while [locked] is true. Mirrors the old
     * two-level navigation behaviour: back from the nested PIN-entry level
     * returns to the biometric level (like popping PinEntry back to Auth);
     * back from the biometric level has nothing left within the gate to pop.
     */
    fun onBackPressed(): BackAction {
        if (pinFallbackState.value) {
            pinFallbackState.value = false
            return BackAction.HandledWithinGate
        }
        return BackAction.LeaveGate
    }
}
