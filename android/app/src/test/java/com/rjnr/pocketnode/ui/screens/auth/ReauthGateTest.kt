package com.rjnr.pocketnode.ui.screens.auth

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure state-machine tests for the #524 reauth overlay gate. No Robolectric
 * or Compose UI test rule needed: androidx.compose.runtime's MutableState
 * works as plain Kotlin on the JVM.
 */
class ReauthGateTest {

    private lateinit var gate: ReauthGate

    @Before
    fun setUp() {
        gate = ReauthGate(mutableStateOf(false), mutableStateOf(false))
    }

    @Test
    fun `starts unlocked and on the biometric level`() {
        assertFalse(gate.locked)
        assertFalse(gate.showingPinFallback)
    }

    @Test
    fun `lock arms the gate without touching the pin fallback level`() {
        gate.lock()
        assertTrue(gate.locked)
        assertFalse(gate.showingPinFallback)
    }

    @Test
    fun `showPinFallback moves to the nested pin level while still locked`() {
        gate.lock()
        gate.showPinFallback()
        assertTrue(gate.locked)
        assertTrue(gate.showingPinFallback)
    }

    @Test
    fun `unlock clears both the lock and the pin fallback level`() {
        gate.lock()
        gate.showPinFallback()
        gate.unlock()
        assertFalse(gate.locked)
        assertFalse(gate.showingPinFallback)
    }

    @Test
    fun `back on the pin fallback level returns to the biometric level and stays locked`() {
        gate.lock()
        gate.showPinFallback()

        val action = gate.onBackPressed()

        assertEquals(ReauthGate.BackAction.HandledWithinGate, action)
        assertTrue(gate.locked)
        assertFalse(gate.showingPinFallback)
    }

    @Test
    fun `back on the biometric level has nothing left to pop within the gate`() {
        gate.lock()

        val action = gate.onBackPressed()

        // The caller (MainActivity) maps LeaveGate to finish() rather than
        // dismissing the overlay: this assertion is the contract, the gate
        // itself must still report locked afterwards.
        assertEquals(ReauthGate.BackAction.LeaveGate, action)
        assertTrue(gate.locked)
    }

    @Test
    fun `a second lock after unlock re-arms cleanly`() {
        gate.lock()
        gate.showPinFallback()
        gate.unlock()

        gate.lock()

        assertTrue(gate.locked)
        assertFalse(gate.showingPinFallback)
    }
}
