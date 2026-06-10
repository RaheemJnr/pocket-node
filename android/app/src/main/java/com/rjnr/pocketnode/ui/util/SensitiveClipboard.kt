package com.rjnr.pocketnode.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Copies secret material (private keys, mnemonics) to the clipboard with a
 * timed auto-clear that survives navigation.
 *
 * The clear job runs on a process-lifetime scope, NOT a composable's
 * `rememberCoroutineScope` — a composable scope is cancelled when the user
 * leaves the screen, which is exactly the common "copy key, navigate away to
 * paste it" flow, and the key would then sit in the clipboard indefinitely
 * (#317 Codex review).
 *
 * Also marks the clip sensitive (`android.content.extra.IS_SENSITIVE`) so
 * Android 13+ suppresses the clipboard preview overlay.
 */
object SensitiveClipboard {

    const val CLEAR_DELAY_MS = 60_000L

    // Process-lifetime scope: lives until the process dies. If the process is
    // killed before the delay fires the clear is lost — acceptable residual
    // shared by any in-process timer; the system clipboard outlives us anyway.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun copyWithTimeout(context: Context, secret: String) {
        val cm = context.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("", secret).apply {
            description.extras = PersistableBundle().apply {
                // ClipDescription.EXTRA_IS_SENSITIVE is API 33; the string
                // literal is the documented back-compat form.
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)

        scope.launch {
            delay(CLEAR_DELAY_MS)
            runCatching {
                // Only clear if the clipboard still holds our secret — don't
                // blow away something the user copied in the meantime.
                val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == secret) {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }
}
