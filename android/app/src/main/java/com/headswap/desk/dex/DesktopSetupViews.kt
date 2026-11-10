package com.headswap.desk.dex

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Shared spacing and controls for the phone's connection and settings screens. */
internal class DesktopSetupViews(private val context: Context) {
    val background = Color.rgb(16, 21, 30)
    private val cardColor = Color.rgb(28, 36, 49)
    private val primary = Color.rgb(238, 243, 250)
    private val secondary = Color.rgb(183, 197, 214)

    fun dp(value: Int) = (context.resources.displayMetrics.density * value + 0.5f).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun brand(parent: LinearLayout, size: Int = 56) = ImageView(context).apply {
        setImageBitmap(com.headswap.desk.BrandArtwork.bitmap(resources))
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        parent.addView(this, LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(10) })
    }

    fun section(parent: LinearLayout, heading: String? = null): LinearLayout = column().apply {
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(14).toFloat()
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (heading != null) text(this, heading, 18f, true)
    }

    fun group(parent: LinearLayout): LinearLayout = column().apply {
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun text(parent: LinearLayout, value: String, size: Float = 14f, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (bold) primary else secondary)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(4))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun button(parent: LinearLayout, label: String, action: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        minHeight = dp(48)
        setSingleLine(false)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.rgb(36, 45, 58), Color.rgb(46, 60, 78))))
        }
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), shape, null)
        setTextColor(primary)
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(2) })
    }

    class HelpRow(val view: LinearLayout, val info: Button)

    /** A separate accessible target: disabling the setting must never disable its explanation. */
    fun help(control: View, title: String, message: () -> String): HelpRow {
        val parent = control.parent as LinearLayout
        val position = parent.indexOfChild(control)
        val originalParams = control.layoutParams
        parent.removeViewAt(position)
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        parent.addView(row, position, originalParams)
        row.addView(control, LinearLayout.LayoutParams(0, -2, 1f))
        val info = button(row, "ⓘ") {
            AlertDialog.Builder(context).setTitle(title).setMessage(message())
                .setPositiveButton("Понятно", null).show()
        }.apply {
            contentDescription = "Справка: $title"
            tooltipText = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(45, 255, 255, 255)), null, null)
            setTextColor(secondary)
            includeFontPadding = false
            minWidth = 0
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) }
        }
        return HelpRow(row, info)
    }

    class NavigationRow(val view: View, private val summary: TextView) {
        fun updateSummary(value: String) {
            if (summary.text.toString() != value) summary.text = value
            summary.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
            if (view.stateDescription?.toString().orEmpty() != value) view.stateDescription = value.takeIf { it.isNotEmpty() }
        }
    }

    /** One accessible target with a wrapping title and status, rather than a stack of large buttons. */
    fun navigation(parent: LinearLayout, label: String, summary: String = "", action: () -> Unit): NavigationRow {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = label
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(45, 255, 255, 255)),
                GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(10).toFloat() }, null)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
            setOnClickListener { action() }
        }
        val copy = column().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        text(copy, label, 16f, true)
        val detail = text(copy, summary, 13f)
        row.addView(TextView(context).apply {
            text = "›"; textSize = 24f; setTextColor(secondary)
            setPadding(dp(10), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-2, -2))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return NavigationRow(row, detail).also { it.updateSummary(summary) }
    }

    class PermissionRow(private val row: NavigationRow, private val mark: TextView) {
        val view: View get() = row.view
        fun update(granted: Boolean?, detail: String) {
            mark.text = when (granted) { true -> "✓"; false -> "✕"; null -> "?" }
            mark.setTextColor(when (granted) { true -> Color.rgb(106, 230, 157); false -> Color.rgb(255, 119, 119); null -> Color.LTGRAY })
            row.updateSummary(detail)
            view.stateDescription = (when (granted) { true -> "Доступ есть. "; false -> "Нет доступа. "; null -> "Проверьте в Android. " }) + detail
        }
    }

    /** Read-only permission state; tapping the row opens the real Android permission flow. */
    fun permission(parent: LinearLayout, label: String, action: () -> Unit): PermissionRow {
        val row = navigation(parent, label, action = action)
        val mark = TextView(context).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        (row.view as LinearLayout).addView(mark, 0, LinearLayout.LayoutParams(dp(36), -2).apply { marginEnd = dp(8) })
        return PermissionRow(row, mark)
    }

    class Toggle(val row: LinearLayout, val control: Switch) {
        fun setEnabled(enabled: Boolean) {
            control.isEnabled = enabled
            row.isEnabled = enabled
            row.alpha = if (enabled) 1f else 0.6f
        }
    }

    fun toggle(parent: LinearLayout, label: String, checked: Boolean, change: (Boolean) -> Unit): Toggle {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
        }
        val title = TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(primary)
            setPaddingRelative(0, dp(6), dp(12), dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val control = Switch(context).apply {
            contentDescription = label
            showText = false
            minWidth = dp(48)
            minHeight = dp(48)
            gravity = Gravity.CENTER
            thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.rgb(106, 230, 157), Color.rgb(190, 201, 215))
            )
            trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.rgb(49, 118, 82), Color.rgb(96, 108, 123))
            )
            isChecked = checked
            setOnCheckedChangeListener { _, on -> change(on) }
        }
        row.addView(control, LinearLayout.LayoutParams(-2, -2))
        row.setOnClickListener { if (control.isEnabled) control.performClick() }
        row.isFocusable = false
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return Toggle(row, control)
    }
}
