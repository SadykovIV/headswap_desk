package com.headswap.desk.dex

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import com.headswap.desk.AppLog

/** Migration only. Older versions wrote this marker before overriding display 0 through DMS.
 * New sessions use SurfaceFlinger so that the internal touchscreen stays available while blank. */
@SuppressLint("PrivateApi", "BlockedPrivateApi")
internal object AndroidPanelPower {
    const val PERMISSION = "android.permission.MANAGE_DISPLAYS"
    private const val STORE = "phone_panel_power_recovery"

    /** Check both identities before requesting a grant; never use an old boot's display ID. */
    fun pending(context: Context): Long? {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.contains("display")) return null
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0) return null
        if (prefs.getInt("boot", -1) != boot) {
            prefs.edit().clear().apply()
            return null
        }
        val stored = prefs.getLong("display", -1)
        return stored.takeIf { it != -1L && it == physicalPanelId(primaryPanel(context)) }
    }

    /** Caller supplies MANAGE_DISPLAYS only during this reset. STATE_UNKNOWN returns the panel to
     * Android's policy even if it is now asleep. A failed reset retains the marker for retry. */
    fun recover(context: Context, expected: Long) {
        if (pending(context) != expected) return
        val reset = Class.forName("android.hardware.display.IDisplayManager").getMethod("requestDisplayPower",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        check(reset.returnType == Boolean::class.javaPrimitiveType) { "Нет совместимой службы восстановления панели" }
        val changed = reset.invoke(systemDisplayManager(), Display.DEFAULT_DISPLAY, Display.STATE_UNKNOWN) == true
        if (!changed) {
            // DMS returns false if the panel already matches the power manager's state; no forced ON.
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val interactive = context.getSystemService(PowerManager::class.java).isInteractive
            check(display?.state == Display.STATE_ON ||
                !interactive && display?.state in setOf(Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND)) {
                "Android не подтвердил сброс прежнего запроса питания панели"
            }
        }
        if (pending(context) == expected) {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply()
            AppLog.i("DESKTOP PANEL: reset interrupted legacy display power request", false)
        }
    }
}
