package com.rjnr.pocketnode.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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
private const val DOWNLOAD_BUFFER_BYTES = 16 * 1024

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

            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                APK_FILE_NAME
            )
            apkFile.parentFile?.mkdirs()

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
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buf, 0, buf.size)
                            if (read <= 0) break
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
     * download. Launches the system package installer.
     */
    fun installNow() {
        if (_state.value !is DownloadState.ReadyToInstall) return
        _state.value = DownloadState.Installing
        launchSystemInstaller()
    }

    /**
     * Cancel an in-progress download. Removes any partial APK file.
     */
    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        lastApkUrl = null
        lastTotalBytesHint = -1L
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
        if (apkFile.exists()) apkFile.delete()
        _state.value = DownloadState.Idle
    }

    private fun launchSystemInstaller() {
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
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
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
        if (apkFile.exists()) {
            val deleted = apkFile.delete()
            Log.d(TAG, "cleanupStaleApk: deleted=$deleted size=${apkFile.length()}")
        }
    }
}
