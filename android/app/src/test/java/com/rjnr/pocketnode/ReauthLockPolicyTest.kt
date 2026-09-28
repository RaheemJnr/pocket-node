package com.rjnr.pocketnode

import android.os.Bundle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.rjnr.pocketnode.ui.navigation.Screen
import com.rjnr.pocketnode.ui.screens.auth.ReauthGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The #524 lock decisions MainActivity delegates from onCreate, onStop and
 * onSaveInstanceState. Each "Activity" below is a fresh gate + policy
 * sharing one [ProcessUnlockState], the way a recreated MainActivity shares
 * its process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReauthLockPolicyTest {

    private val process = ProcessUnlockState()
    private var newLocks = 0
    private var secretsCleared = 0

    private inner class Activity(proc: ProcessUnlockState = process) {
        val gate = ReauthGate(mutableStateOf(false), mutableIntStateOf(0))
        val policy = ReauthLockPolicy(
            gate = gate,
            process = proc,
            onNewLock = { newLocks++ },
            clearSessionSecrets = { secretsCleared++ },
        )

        fun save(routes: List<String>, changingConfig: Boolean = false): Bundle =
            Bundle().also {
                policy.onSaveInstanceState(it, routes, hasWallet = true, hasPin = true, changingConfig)
            }

        /**
         * The real teardown order on API 28+: onStop, then
         * onSaveInstanceState, both seeing the same isChangingConfigurations.
         */
        fun stopAndSave(routes: List<String>, changingConfig: Boolean): Bundle {
            policy.onStop(routes, hasWallet = true, hasPin = true, isChangingConfigurations = changingConfig)
            return save(routes, changingConfig)
        }

        fun restoreFrom(saved: Bundle, proc: ProcessUnlockState = process): Activity =
            Activity(proc).also { it.policy.onRestore(saved, hasWallet = true, hasPin = true) }
    }

    private val main = listOf(Screen.Main.route)
    private val sendPin = listOf(Screen.Main.route, Screen.Send.route, Screen.PinEntry.route)

    // --- onStop (NEW-1, S2, N-c) ---

    @Test
    fun `onStop on an ordinary screen locks`() {
        val a = Activity()
        a.policy.onStop(main, hasWallet = true, hasPin = true, isChangingConfigurations = false)
        assertTrue(a.gate.locked)
        assertEquals(1, newLocks)
    }

    @Test
    fun `onStop on a PinEntry that confirms a Send locks (NEW-1)`() {
        val a = Activity()
        a.policy.onStop(sendPin, hasWallet = true, hasPin = true, isChangingConfigurations = false)
        assertTrue(a.gate.locked)
    }

    @Test
    fun `onStop in the cold-start flow does not lock (S2)`() {
        val a = Activity()
        a.policy.onStop(listOf(Screen.Auth.route), hasWallet = true, hasPin = true, isChangingConfigurations = false)
        a.policy.onStop(
            listOf(Screen.Auth.route, Screen.PinEntry.route),
            hasWallet = true, hasPin = true, isChangingConfigurations = false,
        )
        assertFalse(a.gate.locked)
    }

    @Test
    fun `onStop on the cold-start Forgot PIN does not lock (N-c)`() {
        val a = Activity()
        a.policy.onStop(
            listOf(Screen.Auth.route, Screen.PinEntry.route, Screen.ForgotPin.route),
            hasWallet = true, hasPin = true, isChangingConfigurations = false,
        )
        assertFalse(a.gate.locked)
    }

    @Test
    fun `onStop without a wallet or a PIN does not lock`() {
        val a = Activity()
        a.policy.onStop(main, hasWallet = false, hasPin = true, isChangingConfigurations = false)
        a.policy.onStop(main, hasWallet = true, hasPin = false, isChangingConfigurations = false)
        assertFalse(a.gate.locked)
    }

    // --- onSaveInstanceState (S3) ---

    @Test
    fun `saving state for the background locks, before onStop runs (API 26-27)`() {
        val a = Activity()
        a.save(main)
        assertTrue(a.gate.locked)
    }

    @Test
    fun `saving state for a configuration change does not lock`() {
        val a = Activity()
        process.unlocked = true
        a.save(main, changingConfig = true)
        assertFalse(a.gate.locked)
    }

    @Test
    fun `saving state in the cold-start flow does not lock`() {
        val a = Activity()
        a.save(listOf(Screen.Auth.route, Screen.PinEntry.route))
        assertFalse(a.gate.locked)
    }

    // --- restore (S3) ---

    @Test
    fun `a saved lock restores locked with its session, even in an unlocked process`() {
        val a = Activity()
        a.policy.onStop(main, hasWallet = true, hasPin = true, isChangingConfigurations = false)
        process.unlocked = true
        val saved = a.save(main)
        val locksBefore = newLocks

        val b = Activity()
        b.policy.onRestore(saved, hasWallet = true, hasPin = true)

        assertTrue(b.gate.locked)
        assertEquals(a.gate.session, b.gate.session)
        assertEquals(locksBefore, newLocks)
    }

    @Test
    fun `an unlocked save restored in a new process that was never unlocked starts locked`() {
        val a = Activity()
        val saved = a.save(main, changingConfig = true)

        // Process death: a new process with its own token, never unlocked.
        val b = a.restoreFrom(saved, proc = ProcessUnlockState())

        assertTrue(b.gate.locked)
    }

    @Test
    fun `an unlock sets the process flag, so a later rotation restores unlocked`() {
        val a = Activity()
        a.policy.onStop(main, hasWallet = true, hasPin = true, isChangingConfigurations = false)
        a.policy.onUnlocked(a.gate.session)
        assertFalse(a.gate.locked)
        assertTrue(process.unlocked)

        val b = a.restoreFrom(a.stopAndSave(main, changingConfig = true))

        assertFalse(b.gate.locked)
    }

    // --- configuration changes (R3-1), in the real lifecycle order ---

    @Test
    fun `a configuration change while unlocked stays unlocked in the same session`() {
        process.unlocked = true
        val a = Activity()
        val session = a.gate.session

        val b = a.restoreFrom(a.stopAndSave(main, changingConfig = true))

        assertFalse(a.gate.locked)
        assertFalse(b.gate.locked)
        assertEquals(session, b.gate.session)
        assertEquals(0, newLocks)
    }

    @Test
    fun `a configuration change on the lock screen keeps the same session and its store`() {
        process.unlocked = true
        val a = Activity()
        a.policy.onStop(main, hasWallet = true, hasPin = true, isChangingConfigurations = false)
        val session = a.gate.session
        val locksBefore = newLocks

        val b = a.restoreFrom(a.stopAndSave(main, changingConfig = true))

        assertTrue(b.gate.locked)
        assertEquals(session, b.gate.session)
        // No new lock: the session's ViewModel store was not cleared.
        assertEquals(locksBefore, newLocks)
    }

    @Test
    fun `a real trip to the background still locks, in the same order`() {
        process.unlocked = true
        val a = Activity()

        val b = a.restoreFrom(a.stopAndSave(main, changingConfig = false))

        assertTrue(a.gate.locked)
        assertTrue(b.gate.locked)
        assertEquals(1, newLocks)
    }

    @Test
    fun `a cold-start Auth unlock sets the process flag`() {
        Activity().policy.onColdStartAuthUnlocked()
        assertTrue(process.unlocked)
    }

    @Test
    fun `a cold start on onboarding or first PIN setup counts as unlocked`() {
        Activity().policy.onColdStart(Screen.Auth.route)
        assertFalse(process.unlocked)
        Activity().policy.onColdStart(Screen.Onboarding.route)
        assertTrue(process.unlocked)
        process.unlocked = false
        Activity().policy.onColdStart(Screen.InitialPinSetup.route)
        assertTrue(process.unlocked)
    }

    @Test
    fun `restore of a cold-start flow save does not lock (no double ask)`() {
        val a = Activity()
        val saved = a.save(listOf(Screen.Auth.route))

        val b = Activity()
        b.policy.onRestore(saved, hasWallet = true, hasPin = true)

        assertFalse(b.gate.locked)
    }

    @Test
    fun `restore without a wallet or a PIN does not lock`() {
        val saved = Activity().save(main)
        val b = Activity()
        b.policy.onRestore(saved, hasWallet = true, hasPin = false)
        assertFalse(b.gate.locked)
    }

    // --- a start that was never unlocked, e.g. Recovery (Codex finding) ---

    private val recovery = listOf(Screen.Recovery.route)

    @Test
    fun `a configuration change on the Recovery start stays unlocked and on Recovery`() {
        val a = Activity()
        a.policy.onColdStart(Screen.Recovery.route)
        assertFalse(process.unlocked)

        val b = a.restoreFrom(a.stopAndSave(recovery, changingConfig = true))

        assertFalse(b.gate.locked)
        assertEquals(0, newLocks)
        // Not marked unlocked: only the configuration change was let through.
        assertFalse(process.unlocked)
    }

    @Test
    fun `a real trip to the background from the Recovery start locks, as on main`() {
        val a = Activity()
        a.policy.onColdStart(Screen.Recovery.route)

        val b = a.restoreFrom(a.stopAndSave(recovery, changingConfig = false))

        assertTrue(a.gate.locked)
        assertTrue(b.gate.locked)
    }

    @Test
    fun `a configuration-change save restored in another process locks`() {
        val saved = Activity().stopAndSave(recovery, changingConfig = true)

        val b = Activity().restoreFrom(saved, proc = ProcessUnlockState())

        assertTrue(b.gate.locked)
    }

    @Test
    fun `a configuration-change save while locked restores locked in the same session`() {
        val a = Activity()
        a.policy.onStop(recovery, hasWallet = true, hasPin = true, isChangingConfigurations = false)
        val session = a.gate.session
        val locksBefore = newLocks

        val b = a.restoreFrom(a.stopAndSave(recovery, changingConfig = true))

        assertTrue(b.gate.locked)
        assertEquals(session, b.gate.session)
        assertEquals(locksBefore, newLocks)
    }

    // --- stale successes (B1, N-b) ---

    @Test
    fun `a success from an older session keeps the lock and clears the session PIN`() {
        val a = Activity()
        a.policy.onStop(main, hasWallet = true, hasPin = true, isChangingConfigurations = false)
        val first = a.gate.session
        a.policy.onStop(main, hasWallet = true, hasPin = true, isChangingConfigurations = false)

        a.policy.onUnlocked(first)

        assertTrue(a.gate.locked)
        assertFalse(process.unlocked)
        assertEquals(1, secretsCleared)
    }

    @Test
    fun `a duplicate success after a genuine unlock does not clear the session PIN`() {
        val a = Activity()
        a.policy.onStop(main, hasWallet = true, hasPin = true, isChangingConfigurations = false)
        a.policy.onUnlocked(a.gate.session)
        a.policy.onUnlocked(a.gate.session)
        assertEquals(0, secretsCleared)
    }
}
