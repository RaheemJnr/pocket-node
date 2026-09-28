package com.rjnr.pocketnode

import android.os.Bundle
import com.rjnr.pocketnode.ui.navigation.Screen
import com.rjnr.pocketnode.ui.screens.auth.ReauthGate

/** Whether this process has passed a lock (#524). One instance per process. */
class ProcessUnlockState {
    @Volatile
    var unlocked = false
}

/**
 * When MainActivity locks the re-auth gate (#524). MainActivity only
 * delegates its lifecycle hooks here, so every decision is testable.
 *
 * The one place that does not lock is the cold-start flow: while
 * `Screen.Auth` is anywhere on the back stack (Auth itself, or the PinEntry
 * and ForgotPin screens reached from it) the user has not got past the
 * start-up lock yet, and a successful unlock pops it. Every other screen,
 * including a PinEntry that confirms a Send or reveals a backup, locks:
 * system back from it would otherwise return to the unlocked screen.
 *
 * [onNewLock] runs whenever a new lock session starts (to drop the previous
 * session's ViewModels at once); [clearSessionSecrets] runs when a success
 * arrives for a session that is no longer current.
 */
class ReauthLockPolicy(
    private val gate: ReauthGate,
    private val process: ProcessUnlockState,
    private val onNewLock: () -> Unit = {},
    private val clearSessionSecrets: () -> Unit = {},
) {
    /** Fresh start: onboarding and first PIN setup have nothing to unlock. */
    fun onColdStart(startRoute: String) {
        if (startRoute == Screen.Onboarding.route || startRoute == Screen.InitialPinSetup.route) {
            process.unlocked = true
        }
    }

    /**
     * Recreated activity: a saved lock is kept with its session (so a
     * rotation keeps the lock screen's state); a process that has not been
     * unlocked starts a new lock unless it was saved in the cold-start flow.
     */
    fun onRestore(savedState: Bundle, hasWallet: Boolean, hasPin: Boolean) {
        if (!hasWallet || !hasPin) return
        val savedLocked = savedState.getBoolean(KEY_LOCKED)
        val session = savedState.getInt(KEY_SESSION)
        if (savedLocked) {
            gate.restore(session = session, locked = true)
        } else {
            gate.restore(session = session, locked = false)
            if (!process.unlocked && !savedState.getBoolean(KEY_COLD_START_FLOW)) lock()
        }
    }

    fun onStop(backStackRoutes: List<String>, hasWallet: Boolean, hasPin: Boolean) {
        if (shouldLock(backStackRoutes, hasWallet, hasPin)) lock()
    }

    /**
     * API 26-27 save state before onStop, so lock here too: a process killed
     * in the background must not restore unlocked.
     */
    fun onSaveInstanceState(
        outState: Bundle,
        backStackRoutes: List<String>,
        hasWallet: Boolean,
        hasPin: Boolean,
        isChangingConfigurations: Boolean,
    ) {
        if (!isChangingConfigurations && shouldLock(backStackRoutes, hasWallet, hasPin)) lock()
        outState.putBoolean(KEY_LOCKED, gate.locked)
        outState.putInt(KEY_SESSION, gate.session)
        outState.putBoolean(KEY_COLD_START_FLOW, isColdStartFlow(backStackRoutes))
    }

    /** A biometric or PIN success earned in [session]. */
    fun onUnlocked(session: Int) {
        if (gate.unlock(session)) {
            process.unlocked = true
        } else if (gate.locked) {
            // A late success from an older session (e.g. a PIN verify that
            // finished after the app went to the background): drop the
            // session PIN it set, the gate stays locked.
            clearSessionSecrets()
        }
    }

    /** The cold-start Auth route unlocked. */
    fun onColdStartAuthUnlocked() {
        process.unlocked = true
    }

    private fun shouldLock(backStackRoutes: List<String>, hasWallet: Boolean, hasPin: Boolean) =
        hasWallet && hasPin && !isColdStartFlow(backStackRoutes)

    private fun lock() {
        gate.lock()
        onNewLock()
    }

    companion object {
        private const val KEY_LOCKED = "reauth_locked"
        private const val KEY_SESSION = "reauth_session"
        private const val KEY_COLD_START_FLOW = "reauth_cold_start_flow"

        fun isColdStartFlow(backStackRoutes: List<String>): Boolean =
            Screen.Auth.route in backStackRoutes
    }
}
