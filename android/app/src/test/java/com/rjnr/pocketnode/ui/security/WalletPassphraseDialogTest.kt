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
class WalletPassphraseDialogTest {
    private fun inputs(view: View): List<EditText> = when (view) {
        is EditText -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { inputs(view.getChildAt(it)) }
        else -> emptyList()
    }
    @Test fun `setup demands exact confirmation and clears fields before returning owned buffer`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val result = async { WalletPassphraseDialog().request(activity, "Setup", "Wallet passphrase", true) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            val fields = inputs(dialog.window!!.decorView)
            assertEquals(2, fields.size)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            fields.forEach { assertFalse(it.isSaveEnabled); assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, it.importantForAutofill) }
            fields[0].setText("  exact long wallet phrase  "); fields[1].setText("exact long wallet phrase")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); runCurrent(); assertFalse(result.isCompleted)
            fields[1].setText("  exact long wallet phrase  ")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); runCurrent()
            val owned = result.await()!!
            assertEquals("  exact long wallet phrase  ", owned.concatToString())
            fields.forEach { assertEquals(0, it.text.length) }; owned.fill('\u0000')
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `background cancels and wipes native credential fields`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val result = async { WalletPassphraseDialog().request(controller.get(), "Unlock", "Passphrase", false) }
            runCurrent(); val dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog)
            val fields = inputs(dialog.window!!.decorView); fields.single().setText("a secret entered in UI")
            controller.pause().stop(); runCurrent()
            assertNull(result.await()); fields.forEach { assertEquals(0, it.text.length) }; assertFalse(dialog.isShowing)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `coroutine cancellation dismisses and wipes input`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val result = async { WalletPassphraseDialog().request(activity, "Unlock", "Passphrase", false) }
            runCurrent(); val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val field = inputs(dialog.window!!.decorView).single(); field.setText("a secret entered in UI")
            result.cancel(); runCurrent()
            assertTrue(result.isCancelled); assertEquals(0, field.text.length); assertFalse(dialog.isShowing)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `legacy prompt accepts only six digits and cannot be reused as passphrase setup`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val result = async { WalletPassphraseDialog().requestLegacyPin(activity, "Legacy backup", "Old app PIN only") }
            runCurrent(); val dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog)
            val field = inputs(dialog.window!!.decorView).single()
            field.setText("123"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); runCurrent(); assertFalse(result.isCompleted)
            field.setText("123456"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); runCurrent()
            assertArrayEquals("123456".toCharArray(), result.await()); assertEquals(0, field.text.length)
        } finally { Dispatchers.resetMain() }
    }
}
