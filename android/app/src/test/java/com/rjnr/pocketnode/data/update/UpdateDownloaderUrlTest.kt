package com.rjnr.pocketnode.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #318: the update downloader must only fetch from HTTPS GitHub release-asset
 * hosts. Anything else is rejected before a byte is streamed to disk; the
 * signature gate is the backstop, not the first line of defense.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateDownloaderUrlTest {

    private lateinit var downloader: UpdateDownloader

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        downloader = UpdateDownloader(ctx)
    }

    @Test
    fun `accepts github release asset URLs over https`() {
        assertTrue(downloader.isAllowedApkUrl("https://github.com/RaheemJnr/pocket-node/releases/download/v1.7.4/app.apk"))
        assertTrue(downloader.isAllowedApkUrl("https://objects.githubusercontent.com/github-production-release-asset/x/y.apk"))
        assertTrue(downloader.isAllowedApkUrl("https://release-assets.githubusercontent.com/foo/app.apk"))
    }

    @Test
    fun `rejects non-https schemes`() {
        assertFalse(downloader.isAllowedApkUrl("http://github.com/RaheemJnr/pocket-node/releases/download/v1/app.apk"))
        assertFalse(downloader.isAllowedApkUrl("file:///data/local/tmp/evil.apk"))
    }

    @Test
    fun `rejects disallowed hosts`() {
        assertFalse(downloader.isAllowedApkUrl("https://evil.com/app.apk"))
        assertFalse(downloader.isAllowedApkUrl("https://github.com.evil.com/app.apk"))
        // Subdomain of an allowed host is NOT auto-allowed (exact host match).
        assertFalse(downloader.isAllowedApkUrl("https://gist.github.com/app.apk"))
    }

    @Test
    fun `rejects malformed input`() {
        assertFalse(downloader.isAllowedApkUrl(""))
        assertFalse(downloader.isAllowedApkUrl("not a url"))
        assertFalse(downloader.isAllowedApkUrl("https:///app.apk"))
    }
}
