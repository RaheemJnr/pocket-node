package com.rjnr.pocketnode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val PKG = "com.rjnr.pocketnode"
private const val LAUNCH_TIMEOUT_MS = 10_000L
private const val ONBOARDING_TIMEOUT_MS = 15_000L
private const val POST_UPGRADE_HOME_TIMEOUT_MS = 30_000L

@RunWith(AndroidJUnit4::class)
class UpgradeSmokeTest {

    private lateinit var device: UiDevice
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx = ApplicationProvider.getApplicationContext()
    }

    /**
     * Run against the previous-main APK before the upgrade install.
     * Walks onboarding (Create New Wallet → PIN intro → setup PIN → confirm PIN)
     * and asserts the home-balance-row resource-id is visible. That means real
     * key material was written and Room/EncryptedSharedPrefs hold prior-version
     * state for the upgrade migration to chew on.
     */
    @Test
    fun seedFreshWallet() {
        launchApp()

        val createTile = device.wait(
            Until.findObject(By.res(PKG, "onboarding-create-new")),
            ONBOARDING_TIMEOUT_MS
        )
        assertNotNull(
            "Onboarding create-new tile not found — prev APK is broken, not this PR",
            createTile
        )
        createTile.click()

        // After wallet creation completes the app shows the PIN intro.
        val pinIntro = device.wait(
            Until.findObject(By.res(PKG, "pin-intro-continue")),
            ONBOARDING_TIMEOUT_MS
        )
        assertNotNull("PIN intro continue button not found", pinIntro)
        pinIntro.click()

        // Two passes (SETUP + CONFIRM). Each pass enters six 1s; the screen
        // auto-submits on the 6th digit and advances to the next phase.
        repeat(2) {
            repeat(6) {
                val digit = device.wait(
                    Until.findObject(By.res(PKG, "pin-keypad-1")),
                    5_000L
                )
                assertNotNull("PIN digit '1' not visible (resource-id pin-keypad-1)", digit)
                digit.click()
            }
        }

        val balanceRow = device.wait(
            Until.findObject(By.res(PKG, "home-balance-row")),
            ONBOARDING_TIMEOUT_MS
        )
        assertNotNull("Home balance row not found after onboarding", balanceRow)
    }

    /**
     * Run against the PR-head APK after the install -r upgrade.
     * Just asserts Home renders. If migration crashes or JNI init fails,
     * the row never appears.
     */
    @Test
    fun assertHomeAfterUpgrade() {
        launchApp()

        val balanceRow = device.wait(
            Until.findObject(By.res(PKG, "home-balance-row")),
            POST_UPGRADE_HOME_TIMEOUT_MS
        )
        assertNotNull(
            "Home balance row not visible within ${POST_UPGRADE_HOME_TIMEOUT_MS}ms after upgrade",
            balanceRow
        )
    }

    private fun launchApp() {
        device.pressHome()
        val launcherPkg = device.launcherPackageName
        assertNotNull(launcherPkg)
        device.wait(Until.hasObject(By.pkg(launcherPkg).depth(0)), LAUNCH_TIMEOUT_MS)

        val intent = ctx.packageManager.getLaunchIntentForPackage(PKG)!!.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        ctx.startActivity(intent)
        assertTrue(
            "App package $PKG didn't appear within $LAUNCH_TIMEOUT_MS ms",
            device.wait(Until.hasObject(By.pkg(PKG).depth(0)), LAUNCH_TIMEOUT_MS)
        )
    }
}
