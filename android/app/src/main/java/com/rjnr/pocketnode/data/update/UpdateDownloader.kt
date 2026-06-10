package com.rjnr.pocketnode.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateDownloader"
private const val APK_FILE_NAME = "pocket-node-update.apk"
private const val APK_SUBDIR = "updates"
private const val DOWNLOAD_BUFFER_BYTES = 16 * 1024

// Hard ceiling on a downloaded APK regardless of the server-advertised size,
// so a hostile/compromised endpoint can't fill the data partition and break
// the light-client store / Room DB (#318). The real APK is ~40 MB.
private const val MAX_APK_BYTES = 150L * 1024 * 1024

// Hosts the GitHub release asset URL (and its redirect target) may resolve to.
// browser_download_url lives on github.com; it 302s to the githubusercontent
// CDN. Anything else is rejected before a single byte is streamed to disk.
private val ALLOWED_APK_HOSTS = setOf(
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)

/**
 * Visible states for the auto-update download flow. HomeScreen + the
 * persistent banner above the bottom nav observe this StateFlow and render
 * Telegram-style progress in place of the previous silent-system-download
 * experience.
 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()

    /**
     * Download finished and the APK is on disk. Install has NOT been
     * triggered yet — the user has to tap the banner. This is the Telegram
     * pattern: download silently, surface an "Update" CTA, only fire the
     * system installer on explicit tap.
     */
    data object ReadyToInstall : DownloadState()

    /** Brief state right after the user taps install while the package installer foregrounds. */
    data object Installing : DownloadState()

    data class Failed(val reason: String) : DownloadState()
}

@Singleton
class UpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Standalone scope, not tied to a single ViewModel. A download started
    // from HomeScreen needs to keep running while the user navigates around.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    /**
     * Dedicated client with a generous socket timeout. The shared HttpClient
     * provided by AppModule uses 10s read/socket timeouts which are fine for
     * tiny JSON polls but trip on a 39 MB APK over GitHub → S3 redirects.
     * This client lives for the app lifetime; we install only the timeout
     * plugin and nothing else (no JSON, no content negotiation).
     */
    private val httpClient: HttpClient by lazy {
        HttpClient(Android) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 10 * 60_000  // 10 min total budget
                socketTimeoutMillis = 60_000        // 1 min of idle is OK
            }
            followRedirects = true
            expectSuccess = false
        }
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    // Cached download arguments so a Failed → Retry tap can restart the
    // same download without forcing the user back through the version
    // dialog. Cleared after a successful queue and on resetState.
    private var lastApkUrl: String? = null
    private var lastTotalBytesHint: Long = -1L

    init {
        // Reclaim disk on process start. After a successful install the new
        // app process boots, this init runs, and the stale ~39 MB APK we left
        // in externalFilesDir gets dropped. Failed/cancelled installs that
        // happened in the previous process are also collected here.
        cleanupStaleApk()
    }

    /** True if the user has granted "install from unknown sources" for Pocket Node. */
    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings intent that opens the install-from-unknown-sources screen for this app. */
    fun getInstallPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )

    fun resetState() {
        _state.value = DownloadState.Idle
        lastApkUrl = null
        lastTotalBytesHint = -1L
    }

    /**
     * Re-run the last attempted download. Called from the banner's "Tap
     * to retry" affordance on the Failed state. Bails silently if no
     * download has ever been queued (no URL cached).
     */
    fun retry() {
        val url = lastApkUrl ?: return
        downloadAndInstall(apkUrl = url, totalBytesHint = lastTotalBytesHint)
    }

    /**
     * Stream an APK from [apkUrl] using Ktor. Emits [DownloadState.Downloading]
     * on every read tick so the banner can update in lockstep. Does not auto-
     * launch the installer: when bytes are fully on disk we transition to
     * [DownloadState.ReadyToInstall] and wait for the user to tap the banner.
     *
     * Replaces the prior DownloadManager-based implementation that froze at
     * ~87-95% on emulator + real handsets and gave no programmatic visibility
     * into why. Ktor streams chunk-by-chunk on our scope; cancellation is
     * trivial via Job.cancel.
     */
    fun downloadAndInstall(apkUrl: String, totalBytesHint: Long = -1L) {
        // Reject anything that isn't an HTTPS GitHub release URL before we
        // queue a download (#318). The signature gate at install is the last
        // line of defense, but there's no reason to stream attacker-chosen
        // bytes to disk first.
        if (!isAllowedApkUrl(apkUrl)) {
            Log.e(TAG, "Refusing update download from disallowed URL host")
            _state.value = DownloadState.Failed("Update download blocked: untrusted source.")
            return
        }

        // Remember args for Retry. Set before the launch so a quick
        // Failed→Retry cycle finds the URL even if the job's catch block
        // has not run yet.
        lastApkUrl = apkUrl
        lastTotalBytesHint = totalBytesHint

        // Capture and replace the previous job atomically. The new job
        // awaits the old one's cancellation before touching the APK path,
        // so the old coroutine's CancellationException handler cannot
        // delete the file the new run wrote (race noted in PR #175 review).
        val previousJob = downloadJob
        val newJob = scope.launch {
            previousJob?.cancelAndJoin()
            cleanupStaleApk()
            _state.value = DownloadState.Downloading(0L, totalBytesHint.coerceAtLeast(0L))

            val apkFile = apkFile()
            apkFile.parentFile?.mkdirs()

            // Effective byte ceiling: trust the advertised size (+20% slack)
            // when it's plausible, else the hard cap. Either way never exceed
            // MAX_APK_BYTES so a lying Content-Length can't fill the disk.
            val sizeCap = if (totalBytesHint in 1 until MAX_APK_BYTES) {
                (totalBytesHint + totalBytesHint / 5).coerceAtMost(MAX_APK_BYTES)
            } else {
                MAX_APK_BYTES
            }

            // Free-space guard: refuse if we don't have room for the APK plus
            // headroom, rather than half-writing and corrupting storage.
            val free = runCatching {
                val stat = StatFs(apkFile.parentFile?.absolutePath ?: context.filesDir.absolutePath)
                stat.availableBytes
            }.getOrDefault(Long.MAX_VALUE)
            if (free < sizeCap + 32L * 1024 * 1024) {
                _state.value = DownloadState.Failed("Not enough free space to download the update.")
                return@launch
            }

            try {
                httpClient.prepareGet(apkUrl) {
                    // onDownload fires on every chunk Ktor reads from the
                    // network. contentLength may be null for chunked
                    // transfer-encoded responses; in that case we keep
                    // showing an indeterminate bar and just count bytes.
                    onDownload { bytesSoFar, contentLength ->
                        val total = contentLength ?: totalBytesHint
                        _state.value = DownloadState.Downloading(
                            bytesDownloaded = bytesSoFar,
                            totalBytes = total.coerceAtLeast(0L),
                        )
                    }
                }.execute { response ->
                    // expectSuccess = false on this client, so non-2xx
                    // responses arrive here as plain HttpResponse. Without
                    // this check, a 404/403 HTML error page would be
                    // written to apkFile and we would mark it ReadyToInstall
                    // — surfacing a malformed APK to the system installer.
                    val status = response.status.value
                    if (status !in 200..299) {
                        throw IOException("Update download failed with HTTP $status")
                    }
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    apkFile.outputStream().use { out ->
                        val buf = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var written = 0L
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buf, 0, buf.size)
                            if (read <= 0) break
                            written += read
                            // Abort the moment the stream exceeds the ceiling —
                            // don't let an endless/oversized body fill the disk.
                            if (written > sizeCap) {
                                throw IOException("Update exceeds maximum size ($sizeCap bytes)")
                            }
                            out.write(buf, 0, read)
                        }
                        out.flush()
                    }
                }
                Log.d(TAG, "Download complete: ${apkFile.length()} bytes")
                _state.value = DownloadState.ReadyToInstall
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled")
                if (apkFile.exists()) apkFile.delete()
                // Do not touch _state here. If a newer download has already
                // started, it owns the state; resetting to Idle would clear
                // the replacement banner. The fresh job sets Downloading at
                // its start, overwriting any stale Idle anyway.
                throw e
            } catch (e: HttpRequestTimeoutException) {
                Log.w(TAG, "Download timed out", e)
                if (apkFile.exists()) apkFile.delete()
                _state.value = DownloadState.Failed("Download timed out. Tap retry.")
            } catch (e: Exception) {
                Log.w(TAG, "Download failed", e)
                if (apkFile.exists()) apkFile.delete()
                _state.value = DownloadState.Failed(e.message ?: "Download failed")
            }
        }
        downloadJob = newJob
    }

    /**
     * Called from the banner when the user taps Update on a finished
     * download. Verifies the APK was signed with our release certificate
     * (#293) and only then launches the system package installer.
     *
     * The signature check is gated on a non-empty
     * [com.rjnr.pocketnode.BuildConfig.RELEASE_CERT_SHA256] build-time
     * constant. Debug builds without the env var skip the check (they
     * can't easily produce signed APKs anyway). Release builds with the
     * env var configured reject any APK whose signing-cert SHA-256 does
     * not match — even if the download came from a TLS-pinned URL, this
     * is the last line of defense against URL substitution and against
     * a compromised GitHub release channel pushing an APK signed with a
     * different key.
     */
    fun installNow() {
        if (_state.value !is DownloadState.ReadyToInstall) return
        val apkFile = apkFile()
        val verification = verifyApkSignature(apkFile)
        if (verification != SignatureCheck.Ok) {
            Log.e(TAG, "APK signature verification failed: $verification")
            _state.value = DownloadState.Failed(
                "Update file failed signature check. Re-download from pocket-node.com or " +
                    "github.com/RaheemJnr/pocket-node/releases. (${verification.code})"
            )
            return
        }
        _state.value = DownloadState.Installing
        launchSystemInstaller()
    }

    /** Result of [verifyApkSignature]. The `code` is a short tag included in the user-facing error message. */
    internal sealed class SignatureCheck(val code: String) {
        object Ok : SignatureCheck("ok")
        object DisabledNoConstant : SignatureCheck("ok-skipped")
        object ApkMissing : SignatureCheck("apk-missing")
        object NoSigners : SignatureCheck("no-signers")
        data class Mismatch(val expected: String, val actual: String) : SignatureCheck("sha-mismatch")
        data class ReadError(val reason: String) : SignatureCheck("read-error")
    }

    /**
     * Read the downloaded APK's signing certificate and compare its
     * SHA-256 against [com.rjnr.pocketnode.BuildConfig.RELEASE_CERT_SHA256].
     * Returns [SignatureCheck.Ok] when the expected SHA-256 is configured
     * AND matches the APK's, or when the expected SHA-256 is empty (debug
     * convenience; the check intentionally degrades to a no-op in dev).
     *
     * Uses the API 28+ `signingInfo` field; on older Android versions
     * falls back to the deprecated `signatures` array. minSdk is 26, so
     * both code paths must compile and run.
     */
    internal fun verifyApkSignature(apkFile: File): SignatureCheck {
        if (!apkFile.exists()) return SignatureCheck.ApkMissing
        val expected = com.rjnr.pocketnode.BuildConfig.RELEASE_CERT_SHA256
            .replace(":", "")
            .replace(" ", "")
            .lowercase()
        if (expected.isEmpty()) {
            Log.w(TAG, "RELEASE_CERT_SHA256 not set; skipping APK signature verification (debug build?)")
            return SignatureCheck.DisabledNoConstant
        }
        val certs: Array<android.content.pm.Signature> = try {
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                android.content.pm.PackageManager.GET_SIGNATURES
            }
            val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return SignatureCheck.ReadError("getPackageArchiveInfo returned null")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures ?: emptyArray()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read APK signatures", e)
            return SignatureCheck.ReadError(e.javaClass.simpleName)
        }
        if (certs.isEmpty()) return SignatureCheck.NoSigners
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(certs[0].toByteArray()).joinToString("") { "%02x".format(it) }
        return if (actual.equals(expected, ignoreCase = true)) {
            SignatureCheck.Ok
        } else {
            SignatureCheck.Mismatch(expected = expected, actual = actual)
        }
    }

    /**
     * Cancel an in-progress download. Removes any partial APK file.
     */
    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        lastApkUrl = null
        lastTotalBytesHint = -1L
        val apkFile = apkFile()
        if (apkFile.exists()) apkFile.delete()
        _state.value = DownloadState.Idle
    }

    private fun launchSystemInstaller() {
        val apkFile = apkFile()
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found at ${apkFile.absolutePath}")
            _state.value = DownloadState.Failed("APK file not found after download")
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    /**
     * Drop any in-progress job and partial APK without changing the public
     * state. Called before a fresh [downloadAndInstall] to keep the
     * singleton's invariants clean across retries.
     */
    fun cleanup() {
        downloadJob?.cancel()
        downloadJob = null
        cleanupStaleApk()
    }

    /**
     * Called when the activity resumes after we sent the user to the
     * system installer. If the state is still [DownloadState.Installing]
     * the user has come back without completing install (cancelled or
     * fatal error like the signing-mismatch screen). Clear state and
     * delete the on-disk APK so we are not holding ~39 MB indefinitely.
     */
    fun onInstallerReturned() {
        if (_state.value !is DownloadState.Installing) return
        _state.value = DownloadState.Idle
        cleanupStaleApk()
    }

    private fun cleanupStaleApk() {
        val apkFile = apkFile()
        if (apkFile.exists()) {
            val deleted = apkFile.delete()
            Log.d(TAG, "cleanupStaleApk: deleted=$deleted size=${apkFile.length()}")
        }
    }

    /**
     * The staged-update APK path. Internal storage ([Context.filesDir]) — NOT
     * external app storage — so no other app can write or swap the file
     * between our signature check and the system installer's read (#318 TOCTOU,
     * exploitable on API 26-28 where any WRITE_EXTERNAL_STORAGE holder can
     * write inside Android/data/<pkg>/). Served to the installer via the
     * `<files-path>` FileProvider entry.
     */
    private fun apkFile(): File = File(File(context.filesDir, APK_SUBDIR), APK_FILE_NAME)

    /** True iff [url] is an HTTPS URL on the GitHub release-asset host allowlist (#318). */
    @androidx.annotation.VisibleForTesting
    internal fun isAllowedApkUrl(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase()
        val host = parsed.host?.lowercase()
        return scheme == "https" && host != null && host in ALLOWED_APK_HOSTS
    }
}
