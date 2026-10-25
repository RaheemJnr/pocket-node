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
class WalletPassphraseDialogTest {
    private fun allViews(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()

    @Test fun `all credential kinds start masked and reveal toggles preserve input flags`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = SecureWalletSheet(activity, "Public fixture", "", "Continue")
        try {
            listOf("Wallet passphrase", "Confirm passphrase", "Old backup PIN", "Recovery phrase", "Private key").forEach { label ->
                val field = sheet.credential(label, 1024, label == "Old backup PIN")
                field.setText(if (label == "Old backup PIN") "123456" else "public fixture only")
                val originalType = field.inputType
                assertTrue("$label must start masked", field.transformationMethod is android.text.method.PasswordTransformationMethod)
                val masked = field.transformationMethod.getTransformation(field.text, field).toString()
                assertNotEquals(field.text.toString(), masked)
                allViews(sheet.window!!.decorView).single { it.contentDescription == "Show $label" }.performClick()
                assertNull(field.transformationMethod)
                assertEquals(originalType, field.inputType)
                allViews(sheet.window!!.decorView).single { it.contentDescription == "Hide $label" }.performClick()
                assertTrue(field.transformationMethod is android.text.method.PasswordTransformationMethod)
                assertEquals(originalType, field.inputType)
                field.text.clear()
            }
        } finally { sheet.dismiss() }
    }

    @Test fun `revealing a field preserves secure input flags and reauth lock cancels it`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val result = async { WalletPassphraseDialog().request(activity, "Unlock", "Passphrase", false) }
            runCurrent()
            val dialog = ShadowDialog.getLatestDialog()
            val field = inputs(dialog.window!!.decorView).single()
            val inputType = field.inputType
            field.setText("public fixture phrase only")
            allViews(dialog.window!!.decorView).single { it.contentDescription == "Show Wallet passphrase" }.performClick()
            assertNull(field.transformationMethod)
            assertEquals(inputType, field.inputType)
            assertFalse(field.isSaveEnabled)
            com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents.onLocked(); runCurrent()
            val cancelledByLock = result.isCompleted
            if (!cancelledByLock) { result.cancel(); runCurrent() }
            assertTrue("Reauthentication must dismiss a revealed wallet credential", cancelledByLock)
            assertNull(result.await()); assertEquals(0, field.length()); assertFalse(dialog.isShowing)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `explicit negative consent is false but cancellation is null`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val rejected = async { WalletPassphraseDialog().choose(activity, "Choice", "Consequences", "Proceed", "Preserve") }
            runCurrent(); ShadowDialog.getLatestDialog().findViewById<Button>(android.R.id.button2).performClick(); runCurrent()
            assertEquals(false, rejected.await())
            val dismissed = async { WalletPassphraseDialog().choose(activity, "Choice", "Consequences", "Proceed", "Preserve") }
            runCurrent(); ShadowDialog.getLatestDialog().dismiss(); runCurrent()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent()
            assertNull(dismissed.await())
        } finally { Dispatchers.resetMain() }
    }
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
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog); if (dialog is AlertDialog) { result.cancel(); runCurrent() }; assertFalse("Wallet prompts use a modal sheet", dialog is AlertDialog)
            val fields = inputs(dialog.window!!.decorView)
            assertEquals(2, fields.size)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            fields.forEach { assertFalse(it.isSaveEnabled); assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, it.importantForAutofill) }
            fields[0].setText("  exact long wallet phrase  "); fields[1].setText("exact long wallet phrase")
            dialog.findViewById<Button>(android.R.id.button1).performClick(); runCurrent(); assertFalse(result.isCompleted)
            fields[1].setText("  exact long wallet phrase  ")
            dialog.findViewById<Button>(android.R.id.button1).performClick(); runCurrent()
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
            runCurrent(); val dialog = ShadowDialog.getLatestDialog(); assertNotNull(dialog); if (dialog is AlertDialog) { result.cancel(); runCurrent() }; assertFalse("Wallet prompts use a modal sheet", dialog is AlertDialog)
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
            runCurrent(); val dialog = ShadowDialog.getLatestDialog()
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
            runCurrent(); val dialog = ShadowDialog.getLatestDialog(); assertNotNull(dialog); if (dialog is AlertDialog) { result.cancel(); runCurrent() }; assertFalse("Wallet prompts use a modal sheet", dialog is AlertDialog)
            val field = inputs(dialog.window!!.decorView).single()
            field.setText("123"); dialog.findViewById<Button>(android.R.id.button1).performClick(); runCurrent(); assertFalse(result.isCompleted)
            field.setText("123456"); dialog.findViewById<Button>(android.R.id.button1).performClick(); runCurrent()
            assertArrayEquals("123456".toCharArray(), result.await()); assertEquals(0, field.text.length)
        } finally { Dispatchers.resetMain() }
    }
}
