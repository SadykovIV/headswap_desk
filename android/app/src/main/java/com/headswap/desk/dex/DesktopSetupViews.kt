package com.headswap.desk.dex

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.Gravity
import android.widget.Button
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
