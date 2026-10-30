package com.rjnr.pocketnode

import android.os.Bundle
import java.util.UUID
import com.rjnr.pocketnode.ui.navigation.Screen
import com.rjnr.pocketnode.ui.screens.auth.ReauthGate

/**
 * Whether this process has passed a lock (#524), plus a random [token] that
 * identifies this process. One instance per process.
 */
class ProcessUnlockState(val token: String = UUID.randomUUID().toString()) {
    @Volatile
    var unlocked = false
}

/**
 * Activity-owned authentication gate, independent of navigation routes and saved route markers.
 * Only an unlock in the current lock session authorizes content. A configuration restoration
 * may keep that live authorization when its process token matches; process death cannot.
 */
class ReauthLockPolicy(
    private val gate: ReauthGate,
    private val process: ProcessUnlockState,
    private val onNewLock: () -> Unit = {},
    private val clearSessionSecrets: () -> Unit = {},
) {
    private var wasStopped = false

    /** Only a real background return needs this re-check; initial startup keeps its router. */
    fun onStart(hasPin: Boolean) {
        if (wasStopped && hasPin && !gate.locked) lock()
        wasStopped = false
    }

    /** Fresh Auth entry is gated before Navigation can attach or restore a destination. */
    fun onColdStart(startRoute: String) {
        process.unlocked = startRoute == Screen.Onboarding.route || startRoute == Screen.InitialPinSetup.route
        if (startRoute == Screen.Auth.route) lock()
    }

    /** Saved navigation and saved cold-start markers never carry authorization. */
    fun onRestore(savedState: Bundle, hasWallet: Boolean, hasPin: Boolean) {
        if (!hasPin) return
        val savedLocked = savedState.getBoolean(KEY_LOCKED)
        val session = savedState.getInt(KEY_SESSION)
        if (savedLocked) {
            gate.restore(session = session, locked = true)
        } else {
            gate.restore(session = session, locked = false)
            val sameProcessConfigChange =
                savedState.getString(KEY_CONFIG_CHANGE_TOKEN) == process.token
            if (!process.unlocked || !sameProcessConfigChange) {
                lock()
            }
        }
    }

    /**
     * A configuration change (rotation, dark mode, locale, resize) also runs
     * onStop; it is not a trip to the background, so it neither locks nor
     * starts a new session (which would reset the lock screen and prompt
     * twice).
     */
    fun onStop(
        backStackRoutes: List<String>,
        hasWallet: Boolean,
        hasPin: Boolean,
        isChangingConfigurations: Boolean,
    ) {
        if (isChangingConfigurations) return
        wasStopped = true
        if (hasPin) lock() else {
            // Revoke pending PIN work without cancelling unrelated onboarding storage work.
            process.unlocked = false
            clearSessionSecrets()
        }
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
        // Already locked (onStop ran first, API 28+): keep that session.
        if (!isChangingConfigurations && !gate.locked && shouldLock(backStackRoutes, hasWallet, hasPin)) {
            lock()
        }
        outState.putBoolean(KEY_LOCKED, gate.locked)
        outState.putInt(KEY_SESSION, gate.session)
        // Only a configuration change carries the token: a save for the
        // background never does, and a new process has a new token, so a
        // restore after process death still locks.
        if (isChangingConfigurations) outState.putString(KEY_CONFIG_CHANGE_TOKEN, process.token)
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

    /** A claimed app-PIN change always requires fresh authentication. */
    fun onPinChanged() = lock()

    /** The cold-start Auth route unlocked. */
    fun onColdStartAuthUnlocked() {
        process.unlocked = true
    }

    private fun shouldLock(backStackRoutes: List<String>, hasWallet: Boolean, hasPin: Boolean) =
        hasPin

    private fun lock() {
        process.unlocked = false
        gate.lock()
        clearSessionSecrets()
        onNewLock()
    }

    companion object {
        private const val KEY_LOCKED = "reauth_locked"
        private const val KEY_SESSION = "reauth_session"
        private const val KEY_CONFIG_CHANGE_TOKEN = "reauth_config_change_token"
    }
}
