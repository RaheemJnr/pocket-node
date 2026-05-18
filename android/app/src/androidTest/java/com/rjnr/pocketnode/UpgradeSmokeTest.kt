package com.rjnr.pocketnode

import android.content.ClipData
import android.content.ClipboardManager
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

private const val PKG = "com.rjnr.pocketnode"
private const val LAUNCH_TIMEOUT_MS = 10_000L
private const val ONBOARDING_TIMEOUT_MS = 15_000L
private const val POST_UPGRADE_HOME_TIMEOUT_MS = 30_000L

// Standard BIP39 dev mnemonic. 11×"abandon" + "about" is a valid checksum.
// Hardcoded so the seed is deterministic across runs.
private const val TEST_MNEMONIC =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

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
     * Capture screenshot + window dump BEFORE instrumentation cleanup tears
     * the app down. The workflow's post-instrument trap fires too late —
     * by then `am instrument` has force-stopped the app and the screen is
     * back to the launcher.
     *
     * Files land in the *instrumentation* app's external cache
     * (/sdcard/Android/data/com.rjnr.pocketnode.test/cache/) which is
     * writable without WRITE_EXTERNAL_STORAGE on scoped-storage APIs and
     * adb-pullable from the host runner.
     */
    @Rule
    @JvmField
    val failureCapture: TestWatcher = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            val tag = description.methodName
            // Instrumentation runs inside the TARGET app's process (com.rjnr.pocketnode),
            // so the calling-package check on scoped-storage paths matches the target,
            // not the test app. Use targetContext so the path is permitted.
            val outDir = InstrumentationRegistry.getInstrumentation()
                .targetContext
                .externalCacheDir
                ?: return
            outDir.mkdirs()
            try {
                device.takeScreenshot(File(outDir, "fail-$tag.png"))
            } catch (_: Throwable) { /* best-effort */ }
            try {
                FileOutputStream(File(outDir, "fail-$tag.xml")).use { out ->
                    device.dumpWindowHierarchy(out)
                }
            } catch (_: Throwable) { /* best-effort */ }
        }
    }

    /**
     * Run against the previous-main APK before the upgrade install.
     * Walks Recover-from-Seed-Phrase with a hardcoded BIP39 dev mnemonic so the
     * seed is deterministic and the test can skip the random-mnemonic verify
     * step of the create-new flow. After import + PIN setup the home-balance-row
     * resource-id must be visible — that means real key material was written
     * and Room/EncryptedSharedPrefs hold prior-version state for the upgrade
     * migration to chew on.
     */
    @Test
    fun seedFreshWallet() {
        // Seed clipboard before launching so the Paste button on the import
        // screen sees TEST_MNEMONIC.
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("upgrade-smoke", TEST_MNEMONIC))

        launchApp()

        assertTrue(
            "Onboarding recover tile not found — prev APK is broken, not this PR",
            clickByRes("onboarding-recover", ONBOARDING_TIMEOUT_MS)
        )

        assertTrue(
            "Import paste button not found",
            clickByRes("import-paste", ONBOARDING_TIMEOUT_MS)
        )

        // Let the 12 fields populate from the clipboard paste.
        device.waitForIdle(1_500L)

        assertTrue(
            "Import submit button not found",
            clickByRes("import-submit", ONBOARDING_TIMEOUT_MS)
        )

        // Import → on mainnet the SyncOptionsSheet pops up with default mode
        // RECENT pre-selected. Apply it to dismiss + trigger nav. On testnet
        // it doesn't appear; clickByRes returns false and we fall through.
        clickByRes("sync-sheet-apply", ONBOARDING_TIMEOUT_MS)

        // After import + sync-selection the app shows the PIN intro.
        assertTrue(
            "PIN intro continue button not found",
            clickByRes("pin-intro-continue", ONBOARDING_TIMEOUT_MS)
        )

        // Two passes (SETUP + CONFIRM). Each pass enters six 1s; the screen
        // auto-submits on the 6th digit and advances to the next phase.
        repeat(2) { pass ->
            if (pass == 1) {
                // Defensive: let SETUP→CONFIRM recomposition settle.
                device.waitForIdle(2_000L)
            }
            repeat(6) { i ->
                assertTrue(
                    "PIN digit '1' not clickable at pass=$pass digit=$i",
                    clickByRes("pin-keypad-1")
                )
            }
        }

        val balanceRow = device.wait(
            Until.findObject(By.res("home-balance-row")),
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

        // Post-upgrade the wallet is PIN-locked behind AuthScreen, which shows
        // a biometric prompt + "Use PIN" fallback. On a non-enrolled emulator
        // only "Use PIN" appears; tap it to reach the keypad.
        clickByRes("auth-use-pin", 10_000L)

        // PIN entry (1×6, auto-submits on the 6th digit).
        if (device.wait(Until.hasObject(By.res("pin-keypad-1")), 10_000L)) {
            repeat(6) {
                assertTrue(
                    "PIN digit '1' not clickable on post-upgrade unlock",
                    clickByRes("pin-keypad-1")
                )
            }
        }

        val balanceRow = device.wait(
            Until.findObject(By.res("home-balance-row")),
            POST_UPGRADE_HOME_TIMEOUT_MS
        )
        assertNotNull(
            "Home balance row not visible within ${POST_UPGRADE_HOME_TIMEOUT_MS}ms after upgrade",
            balanceRow
        )
    }

    /**
     * Click a node by resource-id, retrying on StaleObjectException. Compose
     * recompositions during animations / state changes invalidate UiObject2
     * references between `findObject` and `.click()`; retrying with a fresh
     * lookup handles this without forcing slow `waitForIdle` everywhere.
     */
    private fun clickByRes(res: String, timeoutMs: Long = 8_000L, attempts: Int = 6): Boolean {
        if (!device.wait(Until.hasObject(By.res(res)), timeoutMs)) return false
        repeat(attempts) {
            try {
                val node = device.findObject(By.res(res)) ?: return@repeat
                node.click()
                return true
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                device.waitForIdle(300L)
            }
        }
        return false
    }

    private fun launchApp() {
        device.pressHome()
        val launcherPkg = device.launcherPackageName
        assertNotNull("UiDevice.launcherPackageName is null — emulator image broken?", launcherPkg)
        device.wait(Until.hasObject(By.pkg(launcherPkg).depth(0)), LAUNCH_TIMEOUT_MS)

        // NB: don't `am force-stop $PKG` here — instrumentation runs inside the
        // target app's process, so force-stopping it kills the test itself.
        // The workflow handles cold-start between instrument calls externally.
        val intent = ctx.packageManager.getLaunchIntentForPackage(PKG)!!.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        ctx.startActivity(intent)
        assertTrue(
            "App package $PKG didn't appear within $LAUNCH_TIMEOUT_MS ms",
            device.wait(Until.hasObject(By.pkg(PKG).depth(0)), LAUNCH_TIMEOUT_MS)
        )
    }
}
