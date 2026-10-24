package com.rjnr.pocketnode.ui.security

import android.app.AlertDialog
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rjnr.pocketnode.data.vault.VaultPrompt
import com.rjnr.pocketnode.data.vault.WalletPassphrasePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Native fields only. No Compose/SavedState, autofill, suggestions, or retained credential session. */
class WalletPassphraseDialog : VaultPrompt {
    override suspend fun request(activity: FragmentActivity, title: String, message: String, confirm: Boolean): CharArray? =
        credentials(activity, title, message, confirm, legacyPin = false)

    override suspend fun requestLegacyPin(activity: FragmentActivity, title: String, message: String): CharArray? =
        credentials(activity, title, "$message\nThis six-digit legacy app PIN only opens an existing old backup. It cannot protect the new wallet vault.", false, legacyPin = true)

    private suspend fun credentials(activity: FragmentActivity, title: String, message: String, confirm: Boolean, legacyPin: Boolean): CharArray? = withContext(Dispatchers.Main.immediate) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext null
        suspendCancellableCoroutine { continuation ->
            val fields = List(if (confirm) 2 else 1) { index ->
                EditText(activity).apply {
                    hint = if (index == 1) "Re-enter exactly" else if (legacyPin) "Legacy app PIN" else "Wallet passphrase"
                    inputType = if (legacyPin) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    filters = arrayOf(InputFilter.LengthFilter(if (legacyPin) 6 else WalletPassphrasePolicy.MAX_UTF16_UNITS))
                    isSaveEnabled = false
                    setSaveFromParentEnabled(false)
                    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
            }
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; fields.forEach { addView(it) } }
            val dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(message).setView(layout)
                .setPositiveButton("Continue", null).setNegativeButton("Cancel", null).create()
            var finished = false
            lateinit var observer: LifecycleEventObserver
            fun wipe() { fields.forEach { it.text?.clear(); it.clearFocus() } }
            fun finish(value: CharArray?) {
                if (finished) { value?.fill('\u0000'); return }
                finished = true
                wipe(); activity.lifecycle.removeObserver(observer); dialog.dismiss()
                if (continuation.isActive) continuation.resume(value) { _, owned, _ -> owned?.fill('\u0000') }
                else value?.fill('\u0000')
            }
            observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) finish(null)
            }
            activity.lifecycle.addObserver(observer)
            dialog.setOnCancelListener { finish(null) }
            dialog.setOnDismissListener { if (!finished) finish(null) }
            continuation.invokeOnCancellation { activity.runOnUiThread { finish(null) } }
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish(null) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun owned(field: EditText): CharArray = CharArray(field.length()) { field.text[it] }
                val first = owned(fields[0])
                val second = fields.getOrNull(1)?.let(::owned)
                val valid = if (legacyPin) first.size == 6 && first.all { it in '0'..'9' }
                    else WalletPassphrasePolicy.validate(first) && (second == null || first.contentEquals(second))
                second?.fill('\u0000')
                if (valid) finish(first)
                else {
                    first.fill('\u0000')
                    fields.last().error = if (legacyPin) "Enter six digits" else "Use at least 16 characters and match the confirmation exactly"
                }
            }
        }
    }

    override suspend fun choose(activity: FragmentActivity, title: String, message: String, positiveLabel: String, negativeLabel: String): Boolean? =
        choice(activity, title, message, positiveLabel, negativeLabel)

    override suspend fun notify(activity: FragmentActivity, title: String, message: String) {
        choice(activity, title, message, "OK", null)
    }

    private suspend fun choice(activity: FragmentActivity, title: String, message: String, positive: String, negative: String?): Boolean? = withContext(Dispatchers.Main.immediate) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext null
        suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton(positive, null)
                .apply { if (negative != null) setNegativeButton(negative, null) }.create()
            var finished = false
            lateinit var observer: LifecycleEventObserver
            fun finish(value: Boolean?) {
                if (finished) return
                finished = true; activity.lifecycle.removeObserver(observer); dialog.dismiss()
                if (continuation.isActive) continuation.resume(value)
            }
            observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) finish(null)
            }
            activity.lifecycle.addObserver(observer)
            dialog.setOnCancelListener { finish(null) }
            dialog.setOnDismissListener { if (!finished) finish(null) }
            continuation.invokeOnCancellation { activity.runOnUiThread { finish(null) } }
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { finish(true) }
            if (negative != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish(false) }
        }
    }
}
