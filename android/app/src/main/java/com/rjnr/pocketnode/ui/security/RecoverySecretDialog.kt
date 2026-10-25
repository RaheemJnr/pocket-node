package com.rjnr.pocketnode.ui.security

import android.view.WindowManager
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rjnr.pocketnode.ui.screens.auth.onEachReauthLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

import javax.inject.Inject

/** Foreground-only native input. The caller owns and wipes the returned buffer. */
class RecoverySecretDialog @Inject constructor() {
    suspend fun requestImport(activity: FragmentActivity, privateKey: Boolean): CharArray? = request(activity, privateKey, true)
    suspend fun request(activity: FragmentActivity, privateKey: Boolean): CharArray? = request(activity, privateKey, false)

    private suspend fun request(activity: FragmentActivity, privateKey: Boolean, importing: Boolean): CharArray? = withContext(Dispatchers.Main.immediate) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext null
        suspendCancellableCoroutine { continuation ->
            val dialog = SecureWalletSheet(activity,
                if (importing) { if (privateKey) "Import private key" else "Import recovery phrase" }
                else if (privateKey) "Recover with private key" else "Recover with recovery phrase",
                if (importing) "Enter or paste your recovery material. Your independent wallet passphrase will protect the imported wallet."
                else if (privateKey) "This restores spending for this wallet only. It cannot reconstruct a lost seed or other derived accounts. Existing readable seeds are preserved."
                else "Enter your external recovery phrase. It must match the stored wallet address and derivation path.",
                if (importing) "Import wallet" else "Verify recovery material")
            val fields = listOf(dialog.credential(if (privateKey) "Private key (64 hex characters)" else "Recovery phrase",
                if (privateKey) 128 else 2048))
            dialog.education("Keep your recovery material private", "Never share these words or keys with anyone, including support.")
            var finished = false
            var lockJob: Job? = null
            lateinit var observer: LifecycleEventObserver
            fun wipe() { fields.forEach { it.text?.clear(); it.clearFocus() } }
            fun finish(value: CharArray?) {
                if (finished) { value?.fill('\u0000'); return }
                finished = true
                lockJob?.cancel()
                wipe(); activity.lifecycle.removeObserver(observer); dialog.dismiss()
                if (continuation.isActive) continuation.resume(value) { _, owned, _ -> owned?.fill('\u0000') }
                else value?.fill('\u0000')
            }
            observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) finish(null)
            }
            activity.lifecycle.addObserver(observer)
            lockJob = this@withContext.onEachReauthLock { finish(null) }
            dialog.setOnCancelListener { finish(null) }
            dialog.setOnDismissListener { if (!finished) finish(null) }
            continuation.invokeOnCancellation { activity.runOnUiThread { finish(null) } }
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.negative!!.setOnClickListener { finish(null) }
            dialog.positive.setOnClickListener {
                fun owned(field: EditText): CharArray = CharArray(field.length()) { field.text[it] }
                val first = owned(fields[0])
                val valid = first.any { !it.isWhitespace() }
                if (valid) finish(first)
                else {
                    first.fill('\u0000')
                    dialog.showError(fields.last(), "Enter your recovery secret")
                }
            }
        }
    }

}
