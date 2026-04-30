package com.rjnr.pocketnode.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Open [url] in a Chrome Custom Tab when one is available, falling back to
 * [Intent.ACTION_VIEW] otherwise.
 *
 * Why Custom Tabs over `ACTION_VIEW`:
 * - The Custom Tab runs inside Pocket Node's task instead of starting Chrome's
 *   own task. The OS treats the round-trip as part of Pocket Node, so the auth
 *   gate does not re-engage on return and the user is not bounced through PIN
 *   re-entry.
 * - Process death by aggressive memory managers (Tecno / Infinix / MIUI) is
 *   much less likely because the Custom Tab and host app share a task.
 * - Browser back arrow drops the user straight back where they were.
 *
 * Returns `true` if either path launched successfully, `false` if neither did
 * (rare: a stripped ROM with no browser at all). Callers can surface a
 * snackbar in that case.
 *
 * Issue: #138
 */
fun openInBrowser(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    return try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .build()
            .launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        // No Custom Tabs provider AND no fallback browser — Custom Tabs lib
        // resolves through ACTION_VIEW under the hood when no provider exists,
        // so reaching here means the device truly has no web browser.
        false
    } catch (_: Throwable) {
        // Defensive: if the Custom Tabs launch crashes for any other reason
        // (e.g. a misconfigured browser), try plain ACTION_VIEW one more time.
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
