package com.rjnr.pocketnode.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [UpdateDownloader.verifyApkSignature] introduced by #293.
 *
 * Full positive and negative paths (a real signing cert mismatch)
 * require fixtures signed with two different keys, which we don't
 * carry in the test resources. These tests instead pin the
 * input-handling boundaries:
 *
 *  - missing APK file → [SignatureCheck.ApkMissing]
 *  - empty cert constant → [SignatureCheck.DisabledNoConstant]
 *
 * The "actual signing cert vs expected SHA-256" branch is exercised
 * end-to-end by the manual smoke in the PR.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateDownloaderSignatureTest {

    private lateinit var ctx: Context
    private lateinit var downloader: UpdateDownloader

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        downloader = UpdateDownloader(ctx)
    }

    @After
    fun tearDown() {
        // No persistent state to tear down.
    }

    @Test
    fun `verifyApkSignature returns ApkMissing when file does not exist`() {
        val nonexistent = File(ctx.cacheDir, "definitely-not-here.apk")
        if (nonexistent.exists()) nonexistent.delete()

        val result = downloader.verifyApkSignature(nonexistent)
        assertEquals(UpdateDownloader.SignatureCheck.ApkMissing, result)
        assertEquals("apk-missing", result.code)
    }

    @Test
    fun `verifyApkSignature returns DisabledNoConstant when BuildConfig sha is blank`() {
        // BuildConfig.RELEASE_CERT_SHA256 is sourced from an env var at
        // build time. In the test runtime it's the empty string for
        // debug builds — so any present APK file triggers the
        // disabled-no-constant branch rather than reading signatures.
        val placeholder = File(ctx.cacheDir, "placeholder.apk").apply {
            parentFile?.mkdirs()
            writeText("not actually an apk; we only need the file to exist")
        }
        try {
            val result = downloader.verifyApkSignature(placeholder)
            assertTrue(
                "Expected DisabledNoConstant when BuildConfig sha is blank, got $result",
                result is UpdateDownloader.SignatureCheck.DisabledNoConstant
            )
        } finally {
            placeholder.delete()
        }
    }
}
