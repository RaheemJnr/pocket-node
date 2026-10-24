package com.rjnr.pocketnode.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class SensitiveClipboardTest {
    @Test fun `explicit copy is sensitive expires and preserves later unrelated clipboard`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            SensitiveClipboard.copyWithTimeout(context, "public fixture key")
            assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            assertEquals("public fixture key", clipboard.primaryClip!!.getItemAt(0).text.toString())
            advanceTimeBy(SensitiveClipboard.CLEAR_DELAY_MS); runCurrent()
            assertEquals("", clipboard.primaryClip!!.getItemAt(0).text.toString())
            SensitiveClipboard.copyWithTimeout(context, "public fixture key")
            clipboard.setPrimaryClip(ClipData.newPlainText("", "unrelated text"))
            advanceTimeBy(SensitiveClipboard.CLEAR_DELAY_MS); runCurrent()
            assertEquals("unrelated text", clipboard.primaryClip!!.getItemAt(0).text.toString())
        } finally { Dispatchers.resetMain() }
    }
}
