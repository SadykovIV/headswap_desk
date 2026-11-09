package com.headswap.desk.dex

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import com.headswap.desk.AppLog

/** These are Display flags, not the similarly named VirtualDisplay flags. */
internal object DesktopDisplayIsolation {
    const val OWN_GROUP = 1 shl 8
    const val ALWAYS_UNLOCKED = 1 shl 9

    fun independent(flags: Int, group: Int?): Boolean = flags and ALWAYS_UNLOCKED != 0 &&
        (group?.let { it > 0 } ?: (flags and OWN_GROUP != 0))

    fun groupId(display: Display): Int? = runCatching {
        val type = Class.forName("android.view.DisplayInfo")
        val info = type.getConstructor().newInstance()
        val valid = Display::class.java.getMethod("getDisplayInfo", type).invoke(display, info)
        if (valid == false) null else type.getField("displayGroupId").getInt(info)
    }.getOrNull()

    fun ownerUid(display: Display): Int? = runCatching {
        val type = Class.forName("android.view.DisplayInfo")
        val info = type.getConstructor().newInstance()
        if (Display::class.java.getMethod("getDisplayInfo", type).invoke(display, info) == false) null
        else type.getField("ownerUid").getInt(info)
    }.getOrNull()

    fun confirmedIndependence(display: Display): Boolean? {
        if (display.displayId <= 0 || !display.isValid || display.flags and ALWAYS_UNLOCKED == 0) return false
        val group = groupId(display) ?: return null
        return independent(display.flags, group)
    }

    fun independent(display: Display): Boolean = display.displayId > 0 && display.isValid &&
        independent(display.flags, groupId(display))
}

/** A display-scoped lock must never hold the phone's group awake. Released with its input lease. */
internal class DesktopDisplayWakeLocks(private val context: Context) {
    private val locks = HashMap<Int, PowerManager.WakeLock>()

    fun hold(displayId: Int) {
        release(displayId)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        val group = DesktopDisplayIsolation.groupId(display)
        val independent = DesktopDisplayIsolation.independent(display.flags, group)
        AppLog.i("DESKTOP LOCK: display=$displayId group=$group independent=$independent flags=0x${display.flags.toString(16)}", false)
        // Unknown group is not enough to risk a display0 lock, even when the flags claim independence.
        if (displayId <= 0 || !independent || group == null || group <= 0) return
        try {
            val power = context.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val lock = PowerManager::class.java.getMethod("newWakeLock", Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType).invoke(power,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "headswap-desk:display:$displayId", displayId) as PowerManager.WakeLock
            lock.setReferenceCounted(false)
            lock.acquire()
            locks[displayId] = lock
        } catch (error: Exception) {
            AppLog.i("DESKTOP LOCK: display=$displayId scoped wake lock unavailable (${error.javaClass.simpleName})", false)
        }
    }

    fun retain(alive: Set<Int>) { locks.keys.toList().filterNot(alive::contains).forEach(::release) }
    fun release(id: Int) { locks.remove(id)?.let { runCatching { if (it.isHeld) it.release() } } }
    fun close() { locks.keys.toList().forEach(::release) }
}
