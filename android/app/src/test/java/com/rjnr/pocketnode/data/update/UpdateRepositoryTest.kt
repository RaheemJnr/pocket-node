package com.rjnr.pocketnode.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ckb_wallet_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun newWalletPreferences(): WalletPreferences = WalletPreferences(context, NoopLogger)

    // --- Network-services gating (#531) ---

    @Test
    fun `makes no HTTP request and fails with a distinct exception when update checks are off`() = runTest {
        val prefs = newWalletPreferences()
        prefs.setUpdateServiceEnabled(false)
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val repository = UpdateRepository(HttpClient(engine), Json { ignoreUnknownKeys = true }, NoopLogger, prefs)

        val result = repository.checkForUpdate("1.0.0")

        assertEquals("no network request should be made", 0, requestCount)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UpdateServiceDisabledException)
    }

    @Test
    fun `checks GitHub when update checks are on`() = runTest {
        val prefs = newWalletPreferences()
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            respond(
                content = """{"tag_name":"v1.5.0","html_url":"https://example.com/release","body":"notes","assets":[]}""",
                status = HttpStatusCode.OK,
            )
        }
        val repository = UpdateRepository(HttpClient(engine), Json { ignoreUnknownKeys = true }, NoopLogger, prefs)

        val result = repository.checkForUpdate("1.0.0")

        assertEquals(1, requestCount)
        assertTrue(result.isSuccess)
        assertEquals("1.5.0", result.getOrThrow()?.latestVersion)
    }

    // --- isNewer ---

    @Test
    fun `isNewer returns true when latest is newer`() {
        assertTrue(UpdateRepository.isNewer("1.4.0", "1.5.0"))
    }

    @Test
    fun `isNewer returns false when versions are the same`() {
        assertFalse(UpdateRepository.isNewer("1.5.0", "1.5.0"))
    }

    @Test
    fun `isNewer returns false when current is newer`() {
        assertFalse(UpdateRepository.isNewer("2.0.0", "1.5.0"))
    }

    @Test
    fun `isNewer handles different length versions`() {
        assertTrue(UpdateRepository.isNewer("1.5", "1.5.1"))
        assertFalse(UpdateRepository.isNewer("1.5.1", "1.5"))
    }

    @Test
    fun `isNewer returns false for malformed versions`() {
        assertFalse(UpdateRepository.isNewer("abc", "1.5.0"))
        assertFalse(UpdateRepository.isNewer("1.5.0", "xyz"))
        assertFalse(UpdateRepository.isNewer("", ""))
    }

    @Test
    fun `isNewer parses pre-release suffixes by leading digits (#321)`() {
        // "1.7.3-rc1" must read as [1,7,3], not [1,7] — so it is newer than
        // 1.7.2 and equal to (not newer than) 1.7.3.
        assertTrue(UpdateRepository.isNewer("1.7.2", "1.7.3-rc1"))
        assertFalse(UpdateRepository.isNewer("1.7.3-rc1", "1.7.3"))
        assertFalse(UpdateRepository.isNewer("1.7.3", "1.7.3-rc1"))
    }

    // --- findApkAsset ---

    @Test
    fun `findApkAsset returns matching APK asset`() {
        val assets = listOf(
            GitHubReleaseAsset(name = "release-notes.txt", browserDownloadUrl = "https://example.com/notes.txt", size = 100),
            GitHubReleaseAsset(name = "pocket-node-v1.5.0.apk", browserDownloadUrl = "https://example.com/app.apk", size = 50_000_000)
        )
        val result = UpdateRepository.findApkAsset(assets)
        assertNotNull(result)
        assertEquals("pocket-node-v1.5.0.apk", result!!.name)
        assertEquals(50_000_000L, result.size)
    }

    @Test
    fun `findApkAsset returns null when no APK in list`() {
        val assets = listOf(
            GitHubReleaseAsset(name = "source.zip", browserDownloadUrl = "https://example.com/source.zip", size = 1000),
            GitHubReleaseAsset(name = "checksums.txt", browserDownloadUrl = "https://example.com/checksums.txt", size = 200)
        )
        val result = UpdateRepository.findApkAsset(assets)
        assertNull(result)
    }

    @Test
    fun `findApkAsset returns null for empty list`() {
        val result = UpdateRepository.findApkAsset(emptyList())
        assertNull(result)
    }

    // --- BuildConfig.RELEASES_API_URL self-check (#142B) ---
    // Guards against a future typo or rebase mishap reintroducing the
    // v1.5.0/v1.5.1 regression where the auto-update poll targeted the wrong
    // GitHub repo. Build-time string lives in app/build.gradle.kts; if either
    // diverges, this test fails.

    @Test
    fun `RELEASES_API_URL targets the canonical project repo`() {
        assertEquals(
            "https://api.github.com/repos/RaheemJnr/pocket-node/releases/latest",
            BuildConfig.RELEASES_API_URL
        )
    }

    @Test
    fun `RELEASES_API_URL is a GitHub releases-latest endpoint`() {
        assertTrue(
            "RELEASES_API_URL must point at GitHub's releases-latest endpoint",
            BuildConfig.RELEASES_API_URL.startsWith("https://api.github.com/repos/")
        )
        assertTrue(
            "RELEASES_API_URL must end with /releases/latest",
            BuildConfig.RELEASES_API_URL.endsWith("/releases/latest")
        )
    }
}
