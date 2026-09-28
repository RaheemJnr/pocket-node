package com.rjnr.pocketnode.ui.screens.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * State holder for the re-auth lock (#524).
 *
 * Before #524, backgrounding the app and returning navigated to `Screen.Auth`
 * with `popUpTo(Screen.Main) { inclusive = true }`, throwing away the back
 * stack; a successful unlock then landed on Home. The lock is now drawn by
 * [ReauthGateHost] in place of the nav graph, so the back stack is never
 * touched.
 *
 * The state lives in the Activity (plain fields mirrored into its saved
 * instance state), not in composition, so `onStop()` and
 * `onSaveInstanceState()` can lock it before the first frame has run.
 *
 * Every [lock] starts a new session. An unlock is only accepted for the
 * session it was earned in, so a success that completes late (for example a
 * migration prompt finishing after the app went to the background again)
 * can never open a later lock.
 */
class ReauthGate(
    private val lockedState: MutableState<Boolean>,
    private val sessionState: MutableIntState,
) {
    val locked: Boolean get() = lockedState.value
    val session: Int get() = sessionState.intValue

    /** Arm the gate and start a new lock session. */
    fun lock() {
        sessionState.intValue += 1
        lockedState.value = true
    }

    /** Restore the state saved by the previous activity instance. */
    fun restore(session: Int, locked: Boolean) {
        sessionState.intValue = session
        lockedState.value = locked
    }

    /**
     * Clear the gate after a genuine success earned in [session]. Returns
     * false, and stays locked, when [session] is not the current one.
     */
    fun unlock(session: Int): Boolean {
        if (!lockedState.value || session != sessionState.intValue) return false
        lockedState.value = false
        return true
    }
}

/**
 * Renders [content] while unlocked and only [overlay] while locked.
 *
 * While locked the content is not composed at all: every dialog, bottom sheet
 * and popup it opened (each a separate window above anything drawn here) is
 * closed, focus and the IME go with it, and none of it is reachable by touch,
 * keyboard or accessibility. [content] is wrapped in a SaveableStateProvider
 * so its rememberSaveable state (a typed Send amount, the nav host's own
 * per-destination state) comes back intact on unlock; the NavController and
 * its back stack entry ViewModels are hoisted above this call and survive on
 * their own.
 */
@Composable
fun ReauthGateHost(
    locked: Boolean,
    content: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    if (locked) {
        val keyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { keyboardController?.hide() }
        overlay()
    } else {
        holder.SaveableStateProvider(CONTENT_KEY) { content() }
    }
}

private const val CONTENT_KEY = "nav"
