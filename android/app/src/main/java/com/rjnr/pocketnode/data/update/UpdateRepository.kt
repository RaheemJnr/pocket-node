package com.rjnr.pocketnode.data.update

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateRepository"
// Auto-update polls the actual project repo; v1.5.0 + v1.5.1 shipped with a
// stale fork URL (`AgustaRC/ckb-wallet-gateway`) that 404s, so the in-app
// "new version available" notification never fired. Users on those releases
// must manually grab v1.5.2 once; auto-update works from v1.5.2 onward.
private const val GITHUB_API_URL =
    "https://api.github.com/repos/RaheemJnr/pocket-node/releases/latest"

@Serializable
internal data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0
)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String = "",
    val assets: List<GitHubReleaseAsset> = emptyList()
)

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val apkDownloadUrl: String? = null,
    val fileSize: Long = 0
)

@Singleton
class UpdateRepository @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?> = runCatching {
        val response = httpClient.get(GITHUB_API_URL) {
            header("Accept", "application/vnd.github.v3+json")
        }
        val body = response.bodyAsText()
        val release = json.decodeFromString<GitHubRelease>(body)
        val latestVersion = release.tagName.removePrefix("v")

        if (!isNewer(currentVersion, latestVersion)) {
            return@runCatching null
        }

        val apkAsset = findApkAsset(release.assets)

        UpdateInfo(
            latestVersion = latestVersion,
            downloadUrl = release.htmlUrl,
            releaseNotes = release.body,
            apkDownloadUrl = apkAsset?.browserDownloadUrl,
            fileSize = apkAsset?.size ?: 0
        )
    }.onFailure { error ->
        Log.w(TAG, "Update check failed: ${error.message}")
    }

    companion object {
        /**
         * Returns true if [latest] is a newer version than [current].
         * Compares dot-separated integer segments left to right.
         */
        internal fun isNewer(current: String, latest: String): Boolean {
            val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

            if (currentParts.isEmpty() || latestParts.isEmpty()) return false

            val maxLen = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until maxLen) {
                val c = currentParts.getOrElse(i) { 0 }
                val l = latestParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }

        /**
         * Finds the first asset whose name ends with ".apk" (case-insensitive).
         */
        internal fun findApkAsset(assets: List<GitHubReleaseAsset>): GitHubReleaseAsset? {
            return assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        }
    }
}
