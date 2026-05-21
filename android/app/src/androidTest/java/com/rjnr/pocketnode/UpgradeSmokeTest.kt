package com.rjnr.pocketnode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
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
            clickButton("onboarding-recover", "Recover from Seed Phrase", ONBOARDING_TIMEOUT_MS)
        )

        assertTrue(
            "Import paste button not found",
            clickButton("import-paste", "Paste from Clipboard", ONBOARDING_TIMEOUT_MS)
        )

        // Let the 12 fields populate from the clipboard paste.
        device.waitForIdle(1_500L)

        assertTrue(
            "Import submit button not found",
            clickButton("import-submit", "Import Wallet", ONBOARDING_TIMEOUT_MS)
        )

        // Import → on mainnet the SyncOptionsSheet pops up with default mode
        // RECENT pre-selected. Apply it to dismiss + trigger nav. On testnet
        // it doesn't appear; clickButton returns false and we fall through.
        clickButton("sync-sheet-apply", "Apply", ONBOARDING_TIMEOUT_MS)

        // After import + sync-selection the app shows the PIN intro.
        assertTrue(
            "PIN intro continue button not found",
            clickButton("pin-intro-continue", "Create PIN", ONBOARDING_TIMEOUT_MS)
        )

        // SETUP phase: tap digit 1 until the SETUP title disappears. This
        // is the only reliable strategy on slow CI x86_64 emulators: a
        // fixed `repeat(6)` loop counts dispatched taps, but a single tap
        // can be silently swallowed during a keypad recomposition (the
        // accessibility nodes are briefly stripped between dot-indicator
        // updates). With a fixed loop, 6 dispatched taps can result in
        // 5 registered digits → auto-submit never fires → "Confirm PIN"
        // never appears → flake. Polling for title disappearance after
        // each tap recovers from up to (maxTaps - PIN_LENGTH) swallowed
        // taps. Over-tapping past 6 is safe: PinViewModel.onDigitEntered
        // guards on enteredDigits.length >= PIN_LENGTH.
        assertTrue(
            "SETUP phase didn't advance — keypad input swallowed during recomposition?",
            tapDigit1UntilTitleChanges(from = "Create PIN", maxTaps = 12)
        )

        // Phase-transition sync: confirm we actually landed on CONFIRM and
        // not some other screen (network switch, error dialog, etc).
        assertTrue(
            "SETUP→CONFIRM transition didn't complete — was a SETUP click missed?",
            device.wait(Until.hasObject(By.text("Confirm PIN").pkg(PKG)), 10_000L)
        )
        device.waitForIdle(500L)

        // CONFIRM phase: same polling strategy. The success signal is the
        // CONFIRM title disappearing as navigation moves the user to Home.
        // Argon2id verify takes ~300-600 ms during which the keypad is
        // gated (isVerifying = true); extra taps in that window no-op.
        assertTrue(
            "CONFIRM phase didn't complete — keypad input swallowed or PIN mismatch?",
            tapDigit1UntilTitleChanges(from = "Confirm PIN", maxTaps = 12)
        )

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
     *
     * If the node isn't initially visible the helper looks for the nearest
     * scrollable ancestor and scrolls down trying to bring it into view.
     * Small CI emulator viewports often put long screens' primary buttons
     * below the fold; Compose only emits accessibility nodes for visible
     * content, so off-screen testTags would otherwise be invisible to UA.
     */
    /**
     * Click a button identified by resource-id OR visible text. Compose's
     * `testTagsAsResourceId` is unreliable on this codebase under CI's smaller
     * viewport — some merged buttons emit the tag, others don't. Visible
     * button text always survives the merge, so it's the safer primary
     * selector with resource-id as a fast-path.
     */
    private fun clickButton(res: String, text: String, timeoutMs: Long = 8_000L): Boolean {
        if (clickByRes(res, timeoutMs, attempts = 3)) return true
        // Text fallback. Package-scoped so we don't match status-bar / system UI.
        repeat(4) {
            if (!device.wait(Until.hasObject(By.text(text).pkg(PKG)), 3_000L)) {
                val scrollable = device.findObject(By.scrollable(true))
                if (scrollable != null) {
                    try {
                        scrollable.scrollUntil(Direction.DOWN, Until.findObject(By.text(text).pkg(PKG)))
                    } catch (_: Throwable) { /* best-effort */ }
                }
            }
            try {
                // Compose merged button: the clickable node is the parent of the
                // Text. Find the clickable ancestor explicitly via hasDescendant
                // — clicking the Text node itself doesn't trigger the Button's
                // onClick in Compose, so direct text-click was racy.
                val node = device.findObject(
                    By.clickable(true).pkg(PKG).hasDescendant(By.text(text))
                ) ?: device.findObject(By.text(text).pkg(PKG).clickable(true))
                    ?: device.findObject(By.text(text).pkg(PKG))
                    ?: return@repeat
                node.click()
                return true
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                device.waitForIdle(200L)
            }
        }
        return false
    }

    /** PIN digit "1" is a special case — same fallback strategy as clickButton. */
    private fun clickDigit1(timeoutMs: Long = 8_000L): Boolean =
        clickButton("pin-keypad-1", "1", timeoutMs)

    /**
     * Drive PIN entry by tapping digit "1" until the named title text
     * disappears, indicating the phase has advanced.
     *
     * Why this shape: the keypad recomposes on every dot-indicator update.
     * During recomposition the accessibility tree briefly drops the
     * `pin-keypad-1` node, so a tap dispatched in that window is silently
     * swallowed even though `clickButton` returns true. A fixed
     * `repeat(PIN_LENGTH)` loop counts dispatched taps, not registered
     * digits, so one swallow per session means auto-submit never fires.
     *
     * Polling the screen's title for disappearance gives an authoritative
     * signal that the underlying state machine actually progressed.
     * "Create PIN" is shown only in SETUP mode; "Confirm PIN" only in
     * CONFIRM mode (see PinViewModel.kt). The title swaps the instant the
     * 6th digit is registered.
     *
     * Over-tapping past PIN_LENGTH is safe by construction:
     * PinViewModel.onDigitEntered guards on
     * `enteredDigits.length >= PIN_LENGTH` and returns early, so any tap
     * that lands after the buffer is full is a no-op. Likewise, the
     * Argon2id verify in CONFIRM sets `isVerifying = true` which gates
     * the same handler.
     */
    private fun tapDigit1UntilTitleChanges(
        from: String,
        maxTaps: Int = 12,
        perTapDelayMs: Long = 250L,
        // CI x86_64 emulator has no hardware crypto, so Argon2id with
        // 64 MB memory cost (the production parameter — see
        // PinManager.kt) can take 60+ seconds. The previous 30 s bound
        // caught the failure-time ui-dump showing the CONFIRM screen
        // with all `pin-keypad-*` nodes `enabled="false"`, i.e. mid-
        // verify. 90 s is conservative but cheaper than another rerun
        // cycle to discover the actual ceiling.
        postTapsTimeoutMs: Long = 90_000L,
    ): Boolean {
        // Phase 1: tap until either the title disappears (entry succeeded
        // and the screen transitioned) or we've dispatched maxTaps. Early
        // termination matters in SETUP: if 6 of our 8 taps register, the
        // auto-submit fires at digit 6 and we can stop short of maxTaps.
        repeat(maxTaps) {
            if (!device.hasObject(By.text(from).pkg(PKG))) return true
            clickDigit1(timeoutMs = 2_000L)
            device.waitForIdle(perTapDelayMs)
        }

        // Phase 2: stop tapping and wait for the screen to transition off
        // the named title. On CONFIRM this covers the Argon2id verify
        // which can take many seconds on a slow x86_64 emulator (no
        // hardware crypto), plus the navigation animation away from
        // PinEntryScreen. Without this generous wait the test races the
        // KDF and reports a false negative — the failure mode that the
        // first version of this fix hit.
        val deadline = System.currentTimeMillis() + postTapsTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!device.hasObject(By.text(from).pkg(PKG))) return true
            Thread.sleep(500L)
        }
        return false
    }

    private fun clickByRes(res: String, timeoutMs: Long = 8_000L, attempts: Int = 6): Boolean {
        if (!device.wait(Until.hasObject(By.res(res)), timeoutMs)) {
            // Try scrolling: maybe the node is below the fold and Compose
            // hasn't emitted accessibility for it yet.
            val scrollable = device.findObject(By.scrollable(true))
            if (scrollable != null) {
                try {
                    scrollable.scrollUntil(Direction.DOWN, Until.findObject(By.res(res)))
                } catch (_: Throwable) {
                    /* best-effort */
                }
            }
            if (!device.wait(Until.hasObject(By.res(res)), 3_000L)) return false
        }
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
