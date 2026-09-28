package com.rjnr.pocketnode

import com.rjnr.pocketnode.ui.navigation.Screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The #524 lock policy MainActivity applies in onStop, onSaveInstanceState and onCreate. */
class MainActivityReauthPolicyTest {

    @Test
    fun `backgrounding on the Auth or PinEntry route does not lock (S2)`() {
        assertTrue(MainActivity.routeSkipsLock(Screen.Auth.route))
        assertTrue(MainActivity.routeSkipsLock(Screen.PinEntry.route))
    }

    @Test
    fun `backgrounding on any other route locks`() {
        assertFalse(MainActivity.routeSkipsLock(Screen.Main.route))
        assertFalse(MainActivity.routeSkipsLock(Screen.Send.route))
        assertFalse(MainActivity.routeSkipsLock(Screen.ForgotPin.route))
        assertFalse(MainActivity.routeSkipsLock(null))
    }

    @Test
    fun `restore without an unlock in this process starts locked (S3)`() {
        assertTrue(startLocked(savedLocked = false, unlockedThisProcess = false))
    }

    @Test
    fun `restore of a saved lock stays locked even after an unlock in this process`() {
        assertTrue(startLocked(savedLocked = true, unlockedThisProcess = true))
    }

    @Test
    fun `restore in an unlocked process with no saved lock starts unlocked`() {
        assertFalse(startLocked(savedLocked = false, unlockedThisProcess = true))
    }

    @Test
    fun `restore onto the cold-start Auth route does not ask twice`() {
        assertFalse(startLocked(savedLocked = false, savedOnAuthRoute = true, unlockedThisProcess = false))
    }

    @Test
    fun `no lock without a wallet and a PIN`() {
        assertFalse(startLocked(savedLocked = true, unlockedThisProcess = false, hasPin = false))
        assertFalse(startLocked(savedLocked = true, unlockedThisProcess = false, hasWallet = false))
    }

    private fun startLocked(
        savedLocked: Boolean,
        unlockedThisProcess: Boolean,
        savedOnAuthRoute: Boolean = false,
        hasWallet: Boolean = true,
        hasPin: Boolean = true,
    ) = MainActivity.shouldStartLocked(
        savedLocked = savedLocked,
        savedOnAuthRoute = savedOnAuthRoute,
        unlockedThisProcess = unlockedThisProcess,
        hasWallet = hasWallet,
        hasPin = hasPin,
    )
}
