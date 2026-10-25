package com.rjnr.pocketnode.ui.security

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class WalletSecretDialogTest {
    private val words = "abandon ability able about above absent absorb abstract absurd abuse access accident".split(" ")
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun android.app.Dialog.getButton(which: Int): android.widget.Button = findViewById(when (which) {
        AlertDialog.BUTTON_POSITIVE -> android.R.id.button1
        AlertDialog.BUTTON_NEGATIVE -> android.R.id.button2
        else -> android.R.id.button3
    })

    @Test fun `24 word reveal uses separate numbered cells and quiz waits for all answers`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val result = async { WalletSecretDialog().show(activity, (words + words).joinToString(" ").toCharArray(), true, true) }
            runCurrent()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            try {
                val cells = views(dialog.window!!.decorView).filterIsInstance<TextView>().filter { it.text.toString().matches(Regex("[0-9]+\\. [a-z]+")) }
                assertEquals("Each phrase word must have a numbered grid cell", 24, cells.size)
                dialog.findViewById<android.widget.Button>(android.R.id.button1).performClick()
                assertFalse("Verification requires all three choices", dialog.findViewById<android.widget.Button>(android.R.id.button1).isEnabled)
            } finally { dialog.cancel(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent(); result.await() }
        } finally { Dispatchers.resetMain() }
    }

    @Test @Config(sdk = [30], qualifiers = "w320dp-h240dp-land")
    fun `large text landscape keeps 24 word and quiz actions reachable inside secure scroll`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.8f }
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            val result = async { WalletSecretDialog().show(activity, (words + words).joinToString(" ").toCharArray(), true, true) }
            runCurrent()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            try {
                repeat(2) {
                    val decor = dialog.window!!.decorView
                    val density = activity.resources.displayMetrics.density
                    decor.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((240 * density).toInt(), View.MeasureSpec.EXACTLY))
                    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                    val scroll = views(decor).filterIsInstance<android.widget.ScrollView>().single()
                    val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    val bounds = android.graphics.Rect()
                    action.getDrawingRect(bounds); scroll.offsetDescendantRectToMyCoords(action, bounds)
                    scroll.scrollTo(0, bounds.bottom)
                    action.getDrawingRect(bounds); scroll.offsetDescendantRectToMyCoords(action, bounds)
                    // Descendant conversion yields scroll-content coordinates, not viewport coordinates.
                    bounds.offset(0, -scroll.scrollY)
                    assertTrue("Primary action bounds=$bounds scrollY=${scroll.scrollY} viewport=${scroll.height}", bounds.bottom <= scroll.height && bounds.top >= 0)
                    if (it == 0) action.performClick()
                }
                val choices = views(dialog.window!!.decorView).filterIsInstance<RadioButton>()
                assertEquals(12, choices.size)
                choices.forEach {
                    assertFalse(it.isSaveEnabled)
                    assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, it.importantForAutofill)
                    assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, it.importantForContentCapture)
                }
            } finally { dialog.cancel(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent(); result.await() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `quiz rejects missing and incorrect answers then verifies three random positions and clears views`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val secret = words.joinToString(" ").toCharArray()
            val result = async { WalletSecretDialog().show(activity, secret, true, true) }
            runCurrent()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertNotNull("Native reveal must open", dialog)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val revealed = views(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.contains("1. abandon") }
            assertFalse(revealed.isSaveEnabled)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val groups = views(dialog.window!!.decorView).filterIsInstance<RadioGroup>()
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertEquals(3, groups.size)
            assertEquals(3, groups.map { it.tag as Int }.distinct().size)
            assertEquals("", revealed.text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); runCurrent()
            assertFalse(result.isCompleted)
            groups.forEach { group ->
                val wrong = views(group).filterIsInstance<RadioButton>().first { it.text.toString() != words[group.tag as Int] }
                group.check(wrong.id)
            }
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); runCurrent()
            assertFalse(result.isCompleted)
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertTrue(groups.all { it.checkedRadioButtonId == -1 })
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.contains("incorrect") })
            val choices = groups.flatMap { views(it).filterIsInstance<RadioButton>() }
            groups.forEach { group ->
                group.check(views(group).filterIsInstance<RadioButton>().first { it.text.toString() == words[group.tag as Int] }.id)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); runCurrent()
            assertTrue(result.await()); assertFalse(dialog.isShowing)
            assertTrue(choices.all { it.text.isEmpty() }); assertTrue(secret.all { it == '\u0000' })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `raw key export copies only on explicit native action and uses sensitive expiring clipboard`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("public", "unrelated"))
            val result = async { WalletSecretDialog().show(activity, ("00".repeat(31) + "02").toCharArray(), false, false) }
            runCurrent()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            try {
                assertEquals("unrelated", clipboard.primaryClip!!.getItemAt(0).text.toString())
                val copy = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                assertNotNull("Existing raw-key copy must remain available", copy)
                copy.performClick()
                assertEquals("00".repeat(31) + "02", clipboard.primaryClip!!.getItemAt(0).text.toString())
                assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            } finally { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertFalse(result.await()) }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("public", "new unrelated"))
            advanceTimeBy(60_001); runCurrent()
            assertEquals("new unrelated", clipboard.primaryClip!!.getItemAt(0).text.toString())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `repeated mnemonic words still have three distinct wrong choices per challenge`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val result = async { WalletSecretDialog().show(activity, "abandon ".repeat(12).toCharArray(), true, true) }
            runCurrent()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val groups = views(dialog.window!!.decorView).filterIsInstance<RadioGroup>()
            try {
                assertEquals(3, groups.size)
                groups.forEach { assertEquals(4, views(it).filterIsInstance<RadioButton>().map { button -> button.text.toString() }.distinct().size) }
            } finally { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); result.await() }
        } finally { Dispatchers.resetMain() }
    }

    @Test @Config(sdk = [29])
    fun `Android 10 read only reveal closes without verifying a backup`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val secret = words.joinToString(" ").toCharArray()
            val result = async { WalletSecretDialog().show(activity, secret, true, false) }; runCurrent()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); runCurrent()
            assertFalse(result.await()); assertTrue(secret.all { it == '\u0000' })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `quiz cancellation back background and lock wipe choices without marking backup`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (mode in 0..5) {
                val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
                val secret = words.joinToString(" ").toCharArray()
                val result = async { WalletSecretDialog().show(controller.get(), secret, true, true) }; runCurrent()
                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                val choices = views(dialog.window!!.decorView).filterIsInstance<RadioButton>()
                choices.first().performClick()
                when (mode) {
                    0 -> controller.pause().stop()
                    1 -> result.cancel()
                    2 -> dialog.cancel()
                    3 -> com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents.onLocked()
                    4 -> dialog.onBackPressed()
                    else -> dialog.dismiss()
                }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent()
                if (mode == 1) assertTrue(result.isCancelled) else assertFalse(result.await())
                assertTrue(choices.all { it.text.isEmpty() }); assertTrue(secret.all { it == '\u0000' })
                assertFalse(dialog.isShowing)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `stop cancellation and dismissal wipe reveal and never complete backup`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (mode in 0..3) {
                val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
                val secret = words.joinToString(" ").toCharArray()
                val result = async { WalletSecretDialog().show(controller.get(), secret, true, true) }; runCurrent()
                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                assertNotNull(dialog)
                val textViews = views(dialog.window!!.decorView).filterIsInstance<TextView>().filter { it.text.contains("abandon") }
                when (mode) { 0 -> controller.pause().stop(); 1 -> result.cancel(); 2 -> dialog.cancel(); else -> com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents.onLocked() }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                runCurrent()
                if (mode != 1) {
                    val completed = result.isCompleted
                    if (!completed) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    runCurrent()
                    assertFalse(result.await())
                    assertTrue("Cancellation must finish the native operation", completed)
                } else assertTrue(result.isCancelled)
                assertFalse(dialog.isShowing); assertTrue(secret.all { it == '\u0000' })
                assertTrue(textViews.all { it.text.isEmpty() })
            }
        } finally { Dispatchers.resetMain() }
    }
}
