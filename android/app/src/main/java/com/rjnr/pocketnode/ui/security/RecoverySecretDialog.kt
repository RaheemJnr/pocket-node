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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

import javax.inject.Inject

/** Foreground-only native input. The caller owns and wipes the returned buffer. */
class RecoverySecretDialog @Inject constructor() {
    suspend fun request(activity: FragmentActivity, privateKey: Boolean): CharArray? = withContext(Dispatchers.Main.immediate) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext null
        suspendCancellableCoroutine { continuation ->
            val fields = List(1) {
                EditText(activity).apply {
                    hint = if (privateKey) "Private key (64 hex characters)" else "Recovery phrase"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    filters = arrayOf(InputFilter.LengthFilter(if (privateKey) 128 else 2048))
                    isSaveEnabled = false
                    setSaveFromParentEnabled(false)
                    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
            }
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; fields.forEach { addView(it) } }
            val dialog = AlertDialog.Builder(activity).setTitle(if (privateKey) "Recover with private key" else "Recover with recovery phrase").setMessage(if (privateKey) "This restores spending for this wallet only. It cannot reconstruct a lost seed or other derived accounts. Existing readable seeds are preserved." else "Enter your external recovery phrase. It must match the stored wallet address and derivation path.").setView(layout)
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
                val valid = first.any { !it.isWhitespace() }
                if (valid) finish(first)
                else {
                    first.fill('\u0000')
                    fields.last().error = "Enter your recovery secret"
                }
            }
        }
    }

}
