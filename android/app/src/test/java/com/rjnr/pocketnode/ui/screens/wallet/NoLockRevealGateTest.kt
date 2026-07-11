package com.rjnr.pocketnode.ui.screens.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2: on a no-lock device (no biometric, no device credential) revealing the
 * seed / private key of a V1 wallet must go through the app PIN, not silently
 * unlock. These lock in [WalletSettingsViewModel.noLockRevealNeedsPin]: require
 * the PIN when one is set and the session has not already been unlocked; with
 * no PIN there is nothing to gate with.
 */
class NoLockRevealGateTest {

    @Test
    fun `pin set and not yet unlocked requires the pin`() {
        assertTrue(WalletSettingsViewModel.noLockRevealNeedsPin(hasPin = true, alreadyUnlocked = false))
    }

    @Test
    fun `already unlocked this session does not re-prompt`() {
        assertFalse(WalletSettingsViewModel.noLockRevealNeedsPin(hasPin = true, alreadyUnlocked = true))
    }

    @Test
    fun `no pin means nothing to gate with, reveal proceeds`() {
        assertFalse(WalletSettingsViewModel.noLockRevealNeedsPin(hasPin = false, alreadyUnlocked = false))
    }

    @Test
    fun `no pin and already unlocked also proceeds`() {
        assertFalse(WalletSettingsViewModel.noLockRevealNeedsPin(hasPin = false, alreadyUnlocked = true))
    }
}
