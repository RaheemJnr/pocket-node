package com.rjnr.pocketnode.ui.security

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.core.prefs.ThemeMode

/** Only non-secret theme information crosses this boundary. */
interface WalletSheetThemeOwner { val walletSheetThemeMode: ThemeMode }

/** Native modal presentation. Credentials never enter Compose or saved instance state. */
internal class SecureWalletSheet(
    private val host: FragmentActivity,
    title: String,
    message: String,
    positiveLabel: String,
    negativeLabel: String? = "Cancel",
) : Dialog(host) {
    private val dark = when ((host as? WalletSheetThemeOwner)?.walletSheetThemeMode ?: ThemeMode.SYSTEM) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private val surface = Color.parseColor(if (dark) "#1A1A1A" else "#FFFFFF")
    private val ink = Color.parseColor(if (dark) "#FFFFFF" else "#202735")
    private val muted = Color.parseColor(if (dark) "#A0A0A0" else "#687482")
    private val accent = Color.parseColor(if (dark) "#1DD781" else "#1976D2")
    private val outline = Color.parseColor(if (dark) "#404040" else "#DCE3EB")
    private val errorColor = Color.parseColor(if (dark) "#FFB4AB" else "#B3261E")
    private val regular = runCatching { ResourcesCompat.getFont(host, R.font.inter_regular) }.getOrNull() ?: Typeface.DEFAULT
    private val semibold = runCatching { ResourcesCompat.getFont(host, R.font.inter_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val errors = mutableMapOf<EditText, TextView>()
    private val heading: TextView
    private val description: TextView
    val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    val positive: Button
    val negative: Button?

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val root = object : LinearLayout(host) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = View.MeasureSpec.getSize(heightMeasureSpec)
                val maximum = (host.resources.displayMetrics.heightPixels * .9f).toInt()
                val bound = if (available > 0) minOf(available, maximum) else maximum
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(bound, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = shape(surface, 28f)
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val handle = View(host).apply { background = shape(outline, 4f) }
        root.addView(handle, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        // Actions must scroll too: a landscape IME and large text can leave less
        // vertical room than a fixed footer needs, otherwise fields/buttons clip.
        val scrollBody = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(content)
        }
        val scroll = ScrollView(host).apply { isFillViewport = false; addView(scrollBody) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        heading = text(title, 23f, ink, true).apply { accessibilityHeadingCompat() }
        description = text(message, 14f, muted).apply { setPadding(0, dp(10), 0, dp(12)); visibility = if (message.isBlank()) View.GONE else View.VISIBLE }
        content.addView(heading)
        content.addView(description)
        content.addView(body)
        positive = button(positiveLabel, true).apply { id = android.R.id.button1 }
        negative = negativeLabel?.let { button(it, false).apply { id = android.R.id.button2 } }
        scrollBody.addView(positive, fullWidth().apply { topMargin = dp(14) })
        negative?.let { scrollBody.addView(it, fullWidth().apply { topMargin = dp(4) }) }
        setContentView(root)
        setCanceledOnTouchOutside(true)
    }

    override fun show() {
        super.show()
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setLayout(minOf(host.resources.displayMetrics.widthPixels, dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun presentation(title: String, message: String) { heading.text = title; description.text = message }
    fun label(value: String, error: Boolean = false) = text(value, 14f, if (error) errorColor else muted).apply {
        setPadding(0, dp(8), 0, dp(8))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    fun word(value: String) = text(value, 15f, ink, true).apply {
        background = shape(surface, 12f, outline)
        setPadding(dp(12), dp(14), dp(12), dp(14))
    }
    fun choice(value: String) = RadioButton(host).apply {
        text = value; textSize = 14f; typeface = regular; setTextColor(ink)
        buttonTintList = android.content.res.ColorStateList.valueOf(accent)
        minHeight = dp(48); setPadding(dp(6), dp(8), dp(6), dp(8))
        background = shape(surface, 10f, outline)
        setOnCheckedChangeListener { _, checked -> background = shape(surface, 10f, if (checked) accent else outline) }
    }
    fun card() = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(Color.parseColor(if (dark) "#242424" else "#F5F6FA"), 16f, outline)
        setPadding(dp(12), dp(4), dp(12), dp(12))
        layoutParams = fullWidth().apply { topMargin = dp(10) }
    }
    fun secondary(label: String) = button(label, false)

    fun credential(label: String, maximum: Int, numeric: Boolean = false): EditText {
        val field = EditText(host).apply {
            id = View.generateViewId()
            hint = if (numeric) "Enter six-digit PIN" else label
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(maximum))
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            setTextColor(ink); setHintTextColor(muted); textSize = 15f; typeface = regular
            background = null
            setPadding(dp(12), dp(12), dp(4), dp(12))
            minHeight = dp(52)
            setSingleLine(true)
            // Single-line mode replaces the transformation; apply masking last.
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        content.addView(text(label, 13f, ink, true).apply { labelFor = field.id; setPadding(0, dp(14), 0, dp(8)) })
        val row = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL; background = shape(surface, 12f, outline) }
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageButton(host).apply {
            contentDescription = "Show $label"
            setImageResource(R.drawable.ic_wallet_visibility)
            imageTintList = android.content.res.ColorStateList.valueOf(muted)
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                val selection = field.selectionEnd.coerceAtLeast(0)
                val reveal = field.transformationMethod != null
                field.transformationMethod = if (reveal) null else PasswordTransformationMethod.getInstance()
                field.setSelection(minOf(selection, field.length()))
                contentDescription = "${if (reveal) "Hide" else "Show"} $label"
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(row, fullWidth())
        val error = text("", 12f, errorColor).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        errors[field] = error
        content.addView(error, fullWidth())
        return field
    }

    fun showError(field: EditText, message: String) {
        errors.values.forEach { it.text = ""; it.visibility = View.GONE }
        errors[field]?.apply { text = message; visibility = View.VISIBLE }
        field.requestFocus()
    }

    fun education(title: String, message: String) {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Color.parseColor(if (dark) "#193A2B" else "#EDF4FD"), 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text(title, 13f, ink, true)); addView(text(message, 12f, muted).apply { setPadding(0, dp(5), 0, 0) })
        }
        content.addView(card, fullWidth().apply { topMargin = dp(16) })
    }

    private fun button(label: String, primary: Boolean) = Button(host).apply {
        text = label; isAllCaps = false; textSize = 14f; typeface = semibold
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        val enabledInk = if (primary) { if (dark) Color.BLACK else Color.WHITE } else accent
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, enabledInk)))
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(outline, 12f))
            addState(intArrayOf(), shape(if (primary) accent else surface, 12f))
        }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(host).apply {
        text = value; textSize = size; setTextColor(color); typeface = if (bold) semibold else regular
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun shape(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun View.accessibilityHeadingCompat() { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * host.resources.displayMetrics.density).toInt()
}
