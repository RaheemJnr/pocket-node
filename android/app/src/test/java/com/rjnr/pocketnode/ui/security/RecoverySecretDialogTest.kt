package com.rjnr.pocketnode.ui.security

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class RecoverySecretDialogTest {
    private fun inputs(view: View): List<EditText> = when (view) {
        is EditText -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { inputs(view.getChildAt(it)) }
        else -> emptyList()
    }
    @Test fun `private key and whole phrase paste use secure unsaved native field cleared before delivery`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            for ((key, value) in listOf(true to "00".repeat(31) + "02", false to "abandon ".repeat(11) + "about")) {
                val result = async { RecoverySecretDialog().request(activity, key) }; runCurrent()
                val dialog = ShadowAlertDialog.getLatestAlertDialog(); val field = inputs(dialog.window!!.decorView).single()
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(field.isSaveEnabled); assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.importantForAutofill)
                field.setText(value); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); runCurrent()
                val owned = result.await()!!; assertArrayEquals(value.toCharArray(), owned); owned.fill('\u0000')
                assertEquals(0, field.text.length); assertFalse(dialog.isShowing)
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `cancel background and coroutine cancellation clear entered external secret`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (mode in 0..2) {
                val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
                val result = async { RecoverySecretDialog().request(controller.get(), true) }; runCurrent()
                val dialog = ShadowAlertDialog.getLatestAlertDialog(); val field = inputs(dialog.window!!.decorView).single()
                field.setText("ab".repeat(32))
                when (mode) {
                    0 -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    1 -> controller.pause().stop()
                    else -> result.cancel()
                }
                runCurrent(); if (mode < 2) assertNull(result.await()) else assertTrue(result.isCancelled)
                assertEquals(0, field.text.length); assertFalse(dialog.isShowing)
            }
        } finally { Dispatchers.resetMain() }
    }
}
