package com.rjnr.pocketnode.ui.screens.auth

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** State tests for the #524 re-auth gate: sessions and stale unlocks. */
class ReauthGateTest {

    private lateinit var gate: ReauthGate

    @Before
    fun setUp() {
        gate = ReauthGate(mutableStateOf(false), mutableIntStateOf(0))
    }

    @Test
    fun `starts unlocked`() {
        assertFalse(gate.locked)
    }

    @Test
    fun `lock arms the gate and starts a new session`() {
        gate.lock()
        assertTrue(gate.locked)
        assertEquals(1, gate.session)
    }

    @Test
    fun `unlock with the current session clears the gate`() {
        gate.lock()
        assertTrue(gate.unlock(gate.session))
        assertFalse(gate.locked)
    }

    @Test
    fun `a success earned in an earlier session cannot open a later lock`() {
        gate.lock()
        val earlier = gate.session
        gate.lock()

        assertFalse(gate.unlock(earlier))
        assertTrue(gate.locked)
    }

    @Test
    fun `locking again while locked starts a new session`() {
        gate.lock()
        val first = gate.session
        gate.lock()
        assertTrue(gate.session > first)
        assertTrue(gate.locked)
    }

    @Test
    fun `unlock while not locked reports false`() {
        assertFalse(gate.unlock(gate.session))
    }

    @Test
    fun `restore keeps the saved session and lock`() {
        gate.restore(session = 7, locked = true)
        assertTrue(gate.locked)
        assertEquals(7, gate.session)
        assertTrue(gate.unlock(7))
    }
}
