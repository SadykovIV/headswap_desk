package com.headswap.desk.update

import com.headswap.desk.dex.DesktopPermissionList
import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import com.headswap.desk.dex.DesktopSetupViews

/** Phone-only component setup. It never grants access or starts the component on display zero. */
class ComponentPermissionsActivity : Activity() {
    private val ui by lazy { DesktopSetupViews(this) }
    override fun onCreate(state: Bundle?) { super.onCreate(state) }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val root = ui.column().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(24)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.background)
            isFillViewport = true
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }
        window.setDecorFitsSystemWindows(false)
        setContentView(scroll)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; root.addView(this) }
        ui.button(header, "Назад") { finish() }
        ui.text(root, "Разрешения DriveDeck", 25f, true)
        DesktopPermissionList(this, ui).drivedeck(ui.section(root))
    }
}
