package com.rjnr.pocketnode.ui.security

import android.widget.GridLayout
import android.widget.Button
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.RadioButton
import android.widget.RadioGroup
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
                    if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    sensitiveViews.add(this)
                }
                val dialog = SecureWalletSheet(activity,
                    if (mnemonic) "Save your recovery phrase" else "Private key",
                    if (mnemonic) "Write these words down in order. Anyone with this phrase can spend your funds."
                    else "This key backs up this account only. Anyone with this key can spend its funds.",
                    if (positions.isNotEmpty()) "I’ve written it down" else "Close",
                    if (positions.isNotEmpty()) "Cancel" else null)
                val layout = dialog.body
                if (mnemonic) {
                    val grid = GridLayout(activity).apply { columnCount = 2; isSaveEnabled = false; setSaveFromParentEnabled(false) }
                    words.forEachIndexed { index, word ->
                        grid.addView(dialog.word("${index + 1}. $word").secure(), GridLayout.LayoutParams().apply {
                            width = 0; columnSpec = GridLayout.spec(index % 2, 1f); rowSpec = GridLayout.spec(index / 2)
                            setMargins(0, 0, (8 * activity.resources.displayMetrics.density).toInt(), (8 * activity.resources.displayMetrics.density).toInt())
                        })
                    }
                    layout.addView(grid)
                } else {
                    layout.addView(dialog.word(secret.concatToString()).secure())
                    layout.addView(dialog.secondary("Copy private key").apply { id = android.R.id.button3 })
                }
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
                if (!mnemonic) dialog.findViewById<Button>(android.R.id.button3).setOnClickListener {
                    com.rjnr.pocketnode.ui.util.SensitiveClipboard.copyWithTimeout(activity, secret.concatToString())
                    dialog.presentation("Private key", "This key backs up this account only. Copied: clipboard will clear in 60 seconds.")
                }
                dialog.negative?.setOnClickListener { finish(false) }
                dialog.positive.setOnClickListener {
                    if (positions.isEmpty()) {
                        finish(false)
                    } else if (!quiz) {
                        quiz = true
                        wipeViews(); layout.removeAllViews()
                        dialog.presentation("Check your backup", "Select the words you wrote down. Keep your phrase private.")
                        val progress = dialog.label("0 of 3 selected")
                        val error = dialog.label("", error = true).apply { visibility = View.GONE }
                        positions.forEach { position ->
                            val card = dialog.card()
                            card.addView(dialog.label("Word ${position + 1}"))
                            val group = BackupChoiceGroup(activity).apply {
                                tag = position; isSaveEnabled = false; setSaveFromParentEnabled(false)
                                if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                                if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                            }
                            val decoys = (words.filter { it != words[position] }.distinct() + Bip39WordList.WORDS)
                                .distinct().filter { it != words[position] }.shuffled(random).take(3)
                            (decoys + words[position]).shuffled(random).forEach { word ->
                                group.addView(dialog.choice(word).apply { id = View.generateViewId(); secure() })
                            }
                            groups.add(group); card.addView(group); layout.addView(card)
                            group.setOnCheckedChangeListener { _, _ ->
                                val selected = groups.count { it.checkedRadioButtonId != -1 }
                                progress.text = "$selected of 3 selected"
                                dialog.positive.isEnabled = selected == 3
                            }
                        }
                        layout.addView(progress); layout.addView(error)
                        error.tag = "backup-error"
                        dialog.positive.text = "Verify backup"
                        dialog.positive.isEnabled = false
                    } else {
                        val correct = groups.size == 3 && groups.all { group ->
                            group.findViewById<RadioButton>(group.checkedRadioButtonId)?.text?.toString() == words[group.tag as Int]
                        }
                        if (correct) finish(true)
                        else {
                            groups.forEach { it.clearCheck() }
                            layout.findViewWithTag<TextView>("backup-error").apply {
                                text = "Some words are incorrect. Please try again."; visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
    } finally { secret.fill('\u0000') }
}

/** Direct radio children preserve native mutual exclusion, with two columns at normal font sizes. */
private class BackupChoiceGroup(activity: FragmentActivity) : RadioGroup(activity) {
    private val columns get() = if (resources.configuration.fontScale > 1.3f) 1 else 2
    private val heights = mutableListOf<Int>()
    private val gap get() = (8 * resources.displayMetrics.density).toInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val count = columns
        heights.clear()
        for (start in 0 until childCount step count) {
            var height = 0
            for (index in start until minOf(start + count, childCount)) {
                getChildAt(index).measure(View.MeasureSpec.makeMeasureSpec((width - gap * (count - 1)) / count, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                height = maxOf(height, getChildAt(index).measuredHeight)
            }
            heights.add(height)
        }
        setMeasuredDimension(width, heights.sum() + gap * (heights.size - 1).coerceAtLeast(0))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val count = columns
        var y = 0
        heights.forEachIndexed { row, height ->
            for (column in 0 until count) {
                val index = row * count + column
                if (index < childCount) {
                    val cellWidth = (width - gap * (count - 1)) / count
                    val x = column * (cellWidth + gap)
                    getChildAt(index).layout(x, y, x + cellWidth, y + height)
                }
            }
            y += height + gap
        }
    }
}
