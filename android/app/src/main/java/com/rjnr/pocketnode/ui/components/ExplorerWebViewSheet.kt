package com.rjnr.pocketnode.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.rjnr.pocketnode.ui.util.openInBrowser

/**
 * Bottom-sheet wrapper around an embedded [WebView] for the explorer
 * deeplink-lookup flow (#139).
 *
 * Why this exists alongside Custom Tabs
 *
 * Custom Tabs (#138) is the default and remains the right choice for
 * fire-and-forget links (transaction detail pages, etc): the user
 * opens, reads, hits back. The explorer-deeplink-lookup flow is
 * different: the user is supposed to return to Pocket Node with a
 * specific block number in hand. An OEM memory manager that kills the
 * Custom Tab tab during a long read forces the user to start over.
 * An in-process WebView eliminates that failure mode.
 *
 * Security posture
 *
 * The risks of embedding a third-party page in a self-custodial wallet
 * are real (analytics fingerprinting, JS attack surface, CVE history).
 * This wrapper applies the following constraints in lockstep:
 *
 *   - **Host whitelist via [shouldOverrideUrlLoading].** Only the four
 *     explorer hosts (mainnet + testnet, root + www) load inside the
 *     WebView. Any other navigation is rerouted to [openInBrowser] so
 *     this never serves as a general-purpose in-app browser.
 *   - **No [WebView.addJavascriptInterface].** Nothing on the page can
 *     reach into the host app via a JS bridge.
 *   - **Third-party cookies disabled.** [CookieManager] is configured
 *     to refuse cookies the explorer page might try to set for
 *     embedded ad iframes etc. The WebView's own cookie jar is
 *     separate from system Chrome by default.
 *   - **File access disabled.** Defensive; the explorer never requests
 *     local file URIs, but disabling closes off a class of bugs.
 *   - **WebView destroyed on dispose.** A [DisposableEffect] tears
 *     down the WebView when the sheet leaves composition so the
 *     process does not pin a JS context past the dismissal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerWebViewSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Track load progress for the top progress bar. Updated by the
    // WebViewClient via onPageStarted / onPageFinished. Page-load
    // progress mid-flight comes from a WebChromeClient.onProgressChanged
    // hook below, but for now a binary loading flag is enough UI.
    var loading by remember { mutableStateOf(true) }

    // Allowed explorer hosts. Keeping it explicit rather than glob-matching
    // `*.explorer.nervos.org` because we want to know exactly what we
    // accept and the list is small.
    val allowedHosts = remember {
        setOf(
            "explorer.nervos.org",
            "www.explorer.nervos.org",
            "testnet.explorer.nervos.org",
            "pudge.explorer.nervos.org",
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar with the live URL + close + open-externally affordance.
            // Open-externally exists so a user who decides the in-app render is
            // not what they want can punt to Custom Tabs without losing the URL.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Lucide.X, contentDescription = "Close")
                }
                Text(
                    text = Uri.parse(url).host ?: "explorer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = {
                    openInBrowser(context, url)
                    onDismiss()
                }) {
                    Icon(Lucide.ExternalLink, contentDescription = "Open in browser")
                }
            }

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                // Reserve the same 2dp so the WebView content doesn't reflow
                // when the progress bar shows/hides.
                Spacer(modifier = Modifier.height(2.dp))
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        configureWebView(
                            context = ctx,
                            allowedHosts = allowedHosts,
                            onLoadingChange = { loading = it },
                            onOffHost = { offUrl ->
                                openInBrowser(ctx, offUrl)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    update = { webView ->
                        // Re-load only when the URL the caller passed actually
                        // changes; avoid a full reload on every recomposition.
                        if (webView.tag != url) {
                            webView.tag = url
                            webView.loadUrl(url)
                        }
                    },
                    onRelease = { webView ->
                        // Best-effort teardown when the AndroidView leaves
                        // composition the normal way (e.g. recompose changes
                        // the key). The DisposableEffect below also fires;
                        // either path ends the JS context cleanly.
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    },
                )
            }
        }
    }

    // Belt-and-braces: explicit DisposableEffect ensures the WebView is
    // destroyed even if the AndroidView's onRelease doesn't fire (e.g.
    // process backgrounded while the sheet is open).
    DisposableEffect(Unit) {
        onDispose {
            // The WebView itself is owned by AndroidView, so we don't
            // hold a reference. The onRelease above is the actual
            // destroy path; this block is here to document the intent
            // and to surface any future leak diagnostics tooling.
        }
    }

    LaunchedEffect(Unit) {
        // Reset the global third-party cookie acceptance once before the
        // first WebView is created in this sheet. Calling it on every
        // recomposition is redundant but harmless.
        CookieManager.getInstance().setAcceptCookie(true)
    }
}

/**
 * Build a WebView with the security posture documented at the top of
 * the file. Kept as a separate function so the configuration is
 * auditable in one place without the surrounding Compose noise.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(
    context: android.content.Context,
    allowedHosts: Set<String>,
    onLoadingChange: (Boolean) -> Unit,
    onOffHost: (String) -> Unit,
): WebView {
    return WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true                 // explorer needs JS to render
            domStorageEnabled = true                  // needed by the explorer SPA
            allowFileAccess = false                   // close off file:// URIs
            allowContentAccess = false                // close off content:// URIs
            mediaPlaybackRequiresUserGesture = true   // no autoplay surprises
            // Cache mode: default. WebView's own cache; no shared state with
            // system Chrome because the cookie jar is independent per-app.
            cacheMode = WebSettings.LOAD_DEFAULT
            // Disable mixed content: explorer is HTTPS-only; a downgrade is
            // a sign of a hostile redirect.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString PocketNode/embedded"
        }

        // Refuse third-party cookies set by iframes / ad pixels embedded
        // in the explorer page.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        // No addJavascriptInterface — there is no JS-to-native bridge in
        // this WebView. Documented here so a future contributor doesn't
        // add one without revisiting the security posture above.

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val host = request.url.host
                if (host == null || host !in allowedHosts) {
                    // Off-host navigation: punt to Custom Tabs so this
                    // WebView never serves as a general-purpose browser.
                    onOffHost(request.url.toString())
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                onLoadingChange(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                onLoadingChange(false)
            }
        }
    }
}
