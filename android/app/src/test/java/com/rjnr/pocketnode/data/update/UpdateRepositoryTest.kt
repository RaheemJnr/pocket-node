package com.rjnr.pocketnode.data.update

import com.rjnr.pocketnode.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class UpdateRepositoryTest {

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
