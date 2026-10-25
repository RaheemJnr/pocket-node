package com.rjnr.pocketnode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rjnr.pocketnode.ui.security.WalletPassphraseDialog
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in empty-input layout regression test. Run on a disposable emulator; never reads or changes wallet secrets.
 * Exercise with keyboard=true, 150% system font scale and landscape. captureName optionally identifies visual evidence. */
@RunWith(AndroidJUnit4::class)
class WalletProtectionLayoutTest {
    @Test fun blankSheetsRemainUsable() {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("walletUiLayout") == "true")
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 30)
        val out = File(ins.targetContext.getExternalFilesDir(null), "wallet-ui-polish").apply { mkdirs() }
        val keyboard = InstrumentationRegistry.getArguments().getString("keyboard") == "true"
        val suffix = InstrumentationRegistry.getArguments().getString("captureName") ?: "normal"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var host: MainActivity? = null
            scenario.onActivity { host = it }
            ins.waitForIdleSync()
            Thread.sleep(2500)
            val activity = requireNotNull(host)
            val originalTheme = activity.walletPreferences.getThemeMode()
            val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
            ins.runOnMainSync { activity.walletPreferences.setThemeMode(if (dark) com.rjnr.pocketnode.core.prefs.ThemeMode.DARK else com.rjnr.pocketnode.core.prefs.ThemeMode.LIGHT) }
            try { for (confirm in listOf(true, false)) {
                var job: Job? = null
                ins.runOnMainSync { job = CoroutineScope(Dispatchers.Main.immediate).launch {
                    WalletPassphraseDialog().request(activity,
                        if (confirm) "Set wallet passphrase" else "Wallet passphrase",
                        if (confirm) "Create an independent wallet passphrase of at least 16 characters. Spaces are welcome. This is separate from your app PIN, Android PIN and recovery phrase."
                        else "Enter your wallet passphrase to continue. This is separate from your app PIN and Android PIN.", confirm)?.fill('\u0000')
                } }
                ins.waitForIdleSync(); Thread.sleep(250)
                fun root() = WindowInspector.getGlobalWindowViews().last { it.findViewById<Button>(android.R.id.button1) != null }
                fun fields(v: View): List<EditText> = if (v is EditText) listOf(v) else if (v is ViewGroup) (0 until v.childCount).flatMap { fields(v.getChildAt(it)) } else emptyList()
                if (keyboard) {
                    var shown = false
                    for (attempt in 0 until 30) {
                        ins.runOnMainSync {
                            val view = root()
                            shown = view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                            if (!shown && view.hasWindowFocus()) {
                                val input = fields(view).last(); input.requestFocus()
                                activity.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                        if (shown) break
                        Thread.sleep(100); ins.waitForIdleSync()
                    }
                    check(shown) { "Keyboard did not become visible" }
                }
                var inputs = emptyList<EditText>()
                ins.runOnMainSync {
                    inputs = fields(root())
                    if (keyboard) check(root().rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) { "Keyboard was not visible" }
                }
                for (input in inputs) {
                    ins.runOnMainSync { input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height), true) }
                    ins.waitForIdleSync()
                    ins.runOnMainSync {
                        val visibleInput = Rect()
                        check(input.getGlobalVisibleRect(visibleInput) && visibleInput.height() == input.height) { "Credential field clipped" }
                    }
                }
                for (id in listOf(android.R.id.button1, android.R.id.button2)) {
                    ins.runOnMainSync {
                        val action = root().findViewById<Button>(id)
                        action.requestRectangleOnScreen(Rect(0, 0, action.width, action.height), true)
                    }
                    ins.waitForIdleSync()
                    ins.runOnMainSync {
                        val action = root().findViewById<Button>(id); val visibleAction = Rect()
                        check(action.getGlobalVisibleRect(visibleAction) && visibleAction.height() == action.height) { "Action clipped" }
                    }
                }
                ins.runOnMainSync {
                    val action = root().findViewById<Button>(android.R.id.button1)
                    action.requestRectangleOnScreen(Rect(0, 0, action.width, action.height), true)
                }
                ins.waitForIdleSync()
                ins.runOnMainSync {
                    val view = root()
                    val action = view.findViewById<Button>(android.R.id.button1)
                    val visible = Rect()
                    check(action.getGlobalVisibleRect(visible) && visible.height() == action.height) { "Primary action clipped" }
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    File(out, "${if (confirm) "setup" else "unlock"}-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    val geometry = fields(view).map { field -> val rect = Rect(); field.getGlobalVisibleRect(rect); "field=${field.width}x${field.height}, visible=$rect" }
                    File(out, "${if (confirm) "setup" else "unlock"}-$suffix.txt").writeText("dialog=${view.width}x${view.height}; ime=${view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())}; primary=$visible; " + geometry.joinToString("; "))
                    job?.cancel()
                }
                ins.waitForIdleSync()
            } } finally { ins.runOnMainSync { activity.walletPreferences.setThemeMode(originalTheme) } }
        }
    }
}
