package com.rjnr.pocketnode.ui.security

import android.app.AlertDialog
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rjnr.pocketnode.ui.util.Bip39WordList
import com.rjnr.pocketnode.ui.screens.auth.onEachReauthLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.inject.Inject
import kotlin.coroutines.resume

/** Owns reveal and randomized backup verification for one authenticated operation.
 * Strings required by native text widgets are short-lived and never enter Compose or saved state.
 * Ownership of [show]'s input transfers to this dialog, including refusal and cancellation paths.
 */
class WalletSecretDialog @Inject constructor() {
    suspend fun show(activity: FragmentActivity, secret: CharArray, mnemonic: Boolean, verify: Boolean): Boolean = try {
        withContext(Dispatchers.Main.immediate) {
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext false
            suspendCancellableCoroutine { continuation ->
                val sensitiveViews = mutableListOf<TextView>()
                val words = if (mnemonic) secret.concatToString().trim().split(Regex("\\s+")).toMutableList() else mutableListOf()
                val random = SecureRandom()
                val positions = if (verify && words.size >= 3) words.indices.toList().shuffled(random).take(3).sorted() else emptyList()
                val groups = mutableListOf<RadioGroup>()
                var quiz = false
                var finished = false
                var lockJob: Job? = null
                fun TextView.secure(): TextView = apply {
                    isSaveEnabled = false
                    setSaveFromParentEnabled(false)
                    setTextIsSelectable(false)
                    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    sensitiveViews.add(this)
                }
                val layout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 16, 32, 16)
                    isSaveEnabled = false
                    setSaveFromParentEnabled(false)
                }
                val reveal = TextView(activity).secure().apply {
                    text = if (mnemonic) words.mapIndexed { i, word -> "${i + 1}. $word" }.joinToString("\n") else secret.concatToString()
                    textSize = 18f
                }
                layout.addView(reveal)
                val scroll = ScrollView(activity).apply { addView(layout); isSaveEnabled = false; setSaveFromParentEnabled(false) }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(if (mnemonic) "Recovery phrase" else "Private key")
                    .setMessage("Write this down privately. Anyone with this secret can spend your funds.")
                    .setView(scroll)
                    .setPositiveButton(if (positions.isNotEmpty()) "Verify backup" else "Close", null)
                    .setNegativeButton("Cancel", null)
                    .apply { if (!mnemonic) setNeutralButton("Copy private key", null) }.create()
                lateinit var observer: LifecycleEventObserver
                fun wipeViews() { sensitiveViews.forEach { it.text = "" }; sensitiveViews.clear() }
                fun finish(verified: Boolean) {
                    if (finished) return
                    finished = true
                    lockJob?.cancel()
                    wipeViews(); words.clear(); secret.fill('\u0000'); groups.forEach { it.clearCheck(); it.removeAllViews() }; groups.clear()
                    layout.removeAllViews()
                    activity.lifecycle.removeObserver(observer)
                    dialog.dismiss()
                    if (continuation.isActive) continuation.resume(verified)
                }
                observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) finish(false)
                }
                activity.lifecycle.addObserver(observer)
                lockJob = this@withContext.onEachReauthLock { finish(false) }
                dialog.setOnCancelListener { finish(false) }
                dialog.setOnDismissListener { finish(false) }
                continuation.invokeOnCancellation { activity.runOnUiThread { finish(false) } }
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.show()
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                if (!mnemonic) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    com.rjnr.pocketnode.ui.util.SensitiveClipboard.copyWithTimeout(activity, secret.concatToString())
                    dialog.setMessage("Private key copied. Clipboard will clear in 60 seconds.")
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish(false) }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (positions.isEmpty()) {
                        finish(false)
                    } else if (!quiz) {
                        quiz = true
                        wipeViews(); layout.removeAllViews()
                        positions.forEach { position ->
                            layout.addView(TextView(activity).apply { text = "Word ${position + 1}" })
                            val group = RadioGroup(activity).apply { tag = position; isSaveEnabled = false; setSaveFromParentEnabled(false) }
                            // Preserve shuffled multi-choice verification, including nontrivial decoys for repeated-word phrases.
                            val decoys = (words.filter { it != words[position] }.distinct() + Bip39WordList.WORDS)
                                .distinct().filter { it != words[position] }.shuffled(random).take(3)
                            (decoys + words[position]).shuffled(random).forEach { word ->
                                group.addView(RadioButton(activity).apply { id = View.generateViewId(); text = word; secure() })
                            }
                            groups.add(group); layout.addView(group)
                        }
                        dialog.setTitle("Verify your backup")
                        dialog.setMessage("Select the words you wrote down.")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "Verify"
                    } else {
                        val correct = groups.size == 3 && groups.all { group ->
                            group.findViewById<RadioButton>(group.checkedRadioButtonId)?.text?.toString() == words[group.tag as Int]
                        }
                        if (correct) finish(true)
                        else {
                            groups.forEach { it.clearCheck() }
                            dialog.setMessage("Some words are incorrect. Please try again.")
                        }
                    }
                }
            }
        }
    } finally { secret.fill('\u0000') }
}
