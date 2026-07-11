package com.rjnr.pocketnode.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #370: after an overwrite install / version upgrade, the PIN failed-attempt
 * counter is reset. The decision is a pure comparison of the last-seen
 * versionCode against the current one; these lock in that it fires only on a
 * genuine upgrade — never on a fresh install, a re-launch at the same version,
 * or a downgrade.
 */
class PinUpgradeResetTest {

    @Test
    fun `fresh install (no last-seen version) does not reset`() {
        // Default 0 means we have never recorded a version -> nothing to reset.
        assertFalse(PinManager.shouldResetAttemptsForUpgrade(lastSeen = 0, current = 20))
    }

    @Test
    fun `an upgrade resets`() {
        assertTrue(PinManager.shouldResetAttemptsForUpgrade(lastSeen = 19, current = 20))
    }

    @Test
    fun `a multi-version jump resets`() {
        assertTrue(PinManager.shouldResetAttemptsForUpgrade(lastSeen = 12, current = 20))
    }

    @Test
    fun `same version (ordinary relaunch) does not reset`() {
        assertFalse(PinManager.shouldResetAttemptsForUpgrade(lastSeen = 20, current = 20))
    }

    @Test
    fun `a downgrade does not reset`() {
        assertFalse(PinManager.shouldResetAttemptsForUpgrade(lastSeen = 21, current = 20))
    }
}
