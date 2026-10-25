package com.rjnr.pocketnode.ui.security

import android.view.WindowManager
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rjnr.pocketnode.data.vault.VaultPrompt
import com.rjnr.pocketnode.data.vault.WalletPassphrasePolicy
import com.rjnr.pocketnode.ui.screens.auth.onEachReauthLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Native fields only. No Compose/SavedState, autofill, suggestions, or retained credential session. */
class WalletPassphraseDialog : VaultPrompt {
    override suspend fun request(activity: FragmentActivity, title: String, message: String, confirm: Boolean): CharArray? =
        credentials(activity, title, message, confirm, legacyPin = false)

    override suspend fun requestLegacyPin(activity: FragmentActivity, title: String, message: String): CharArray? =
        credentials(activity, title, "$message\nThis six-digit app PIN is only for legacy wallets and backups. It cannot protect the new wallet vault.", false, legacyPin = true)

    private suspend fun credentials(activity: FragmentActivity, title: String, message: String, confirm: Boolean, legacyPin: Boolean): CharArray? = withContext(Dispatchers.Main.immediate) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext null
        suspendCancellableCoroutine { continuation ->
            val dialog = SecureWalletSheet(activity, title, message,
                if (legacyPin) "Unlock backup" else if (confirm) "Set wallet passphrase" else "Unlock")
            val fields = List(if (confirm) 2 else 1) { index ->
                dialog.credential(if (index == 1) "Confirm passphrase" else if (legacyPin) "Old backup PIN" else "Wallet passphrase",
                    if (legacyPin) 6 else WalletPassphrasePolicy.MAX_UTF16_UNITS, legacyPin)
            }
            if (confirm) dialog.education("Keep an external recovery backup",
                "Your wallet passphrase protects this installation. Keep your recovery phrase or private key for uninstall, cleared app data or device loss. Before removing your device lock, finish wallet protection and verify your backup.")
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
                val second = fields.getOrNull(1)?.let(::owned)
                val policyValid = if (legacyPin) true else WalletPassphrasePolicy.validate(first)
                val valid = if (legacyPin) first.size == 6 && first.all { it in '0'..'9' }
                    else policyValid && (second == null || first.contentEquals(second))
                second?.fill('\u0000')
                if (valid) finish(first)
                else {
                    first.fill('\u0000')
                    when {
                        legacyPin -> dialog.showError(fields[0], "Enter the six-digit PIN used for this backup")
                        !policyValid -> dialog.showError(fields[0], "Use at least 16 characters")
                        else -> dialog.showError(fields.last(), "Passphrases must match exactly")
                    }
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
            val dialog = SecureWalletSheet(activity, title, message, positive, negative)
            var finished = false
            var lockJob: Job? = null
            lateinit var observer: LifecycleEventObserver
            fun finish(value: Boolean?) {
                if (finished) return
                finished = true; lockJob?.cancel(); activity.lifecycle.removeObserver(observer); dialog.dismiss()
                if (continuation.isActive) continuation.resume(value)
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
            dialog.positive.setOnClickListener { finish(true) }
            if (negative != null) dialog.negative!!.setOnClickListener { finish(false) }
        }
    }
}
