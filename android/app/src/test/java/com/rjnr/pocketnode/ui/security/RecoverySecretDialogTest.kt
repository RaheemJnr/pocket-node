package com.rjnr.pocketnode.ui.security

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Button
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

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
                val dialog = ShadowDialog.getLatestDialog(); val field = inputs(dialog.window!!.decorView).single()
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(field.isSaveEnabled); assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.importantForAutofill)
                field.setText(value); dialog.findViewById<Button>(android.R.id.button1).performClick(); runCurrent()
                val owned = result.await()!!; assertArrayEquals(value.toCharArray(), owned); owned.fill('\u0000')
                assertEquals(0, field.text.length); assertFalse(dialog.isShowing)
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `native import accepts owned phrase and key input without retaining text`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            for (privateKey in listOf(false, true)) {
                val result = async { RecoverySecretDialog().requestImport(activity, privateKey) }; runCurrent()
                assertFalse("Import must wait for native user input", result.isCompleted)
                val dialog = ShadowDialog.getLatestDialog()
                val field = inputs(dialog.window!!.decorView).single()
                assertFalse(field.isSaveEnabled)
                field.setText(if (privateKey) "00".repeat(31) + "02" else "abandon ".repeat(11) + "about")
                dialog.findViewById<Button>(android.R.id.button1).performClick(); runCurrent()
                val owned = result.await()!!
                assertTrue(owned.isNotEmpty()); owned.fill('\u0000')
                assertEquals(0, field.text.length)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `reauthentication clears native typed import and cancels delivery`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val result = async { RecoverySecretDialog().requestImport(activity, false) }; runCurrent()
            assertFalse("Import must wait for native user input", result.isCompleted)
            val dialog = ShadowDialog.getLatestDialog()
            val field = inputs(dialog.window!!.decorView).single()
            field.setText("abandon ability")
            com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents.onLocked(); runCurrent()
            val cancelled = result.isCompleted
            if (!cancelled) dialog.findViewById<Button>(android.R.id.button2).performClick()
            runCurrent(); assertNull(result.await())
            assertTrue("Lock must cancel native input", cancelled)
            assertEquals(0, field.text.length); assertFalse(dialog.isShowing)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `cancel background and coroutine cancellation clear entered external secret`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (mode in 0..2) {
                val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
                val result = async { RecoverySecretDialog().request(controller.get(), true) }; runCurrent()
                val dialog = ShadowDialog.getLatestDialog(); val field = inputs(dialog.window!!.decorView).single()
                field.setText("ab".repeat(32))
                when (mode) {
                    0 -> dialog.findViewById<Button>(android.R.id.button2).performClick()
                    1 -> controller.pause().stop()
                    else -> result.cancel()
                }
                runCurrent(); if (mode < 2) assertNull(result.await()) else assertTrue(result.isCancelled)
                assertEquals(0, field.text.length); assertFalse(dialog.isShowing)
            }
        } finally { Dispatchers.resetMain() }
    }
}
