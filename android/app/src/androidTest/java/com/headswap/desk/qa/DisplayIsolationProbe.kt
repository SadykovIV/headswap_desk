package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import com.headswap.desk.dex.*

/** Real power-button sleep on a disposable emulator. Always restores its physical screen. */
internal object DisplayIsolationProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        var ui: UiAutomation? = null
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui = automation
            automation.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY, "android.permission.WAKE_LOCK")
            val context = test.targetContext
            val manager = context.getSystemService(DisplayManager::class.java)
            val holds = DesktopDisplayWakeLocks(context)
            try {
                ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 2).use { images ->
                    val display = checkNotNull(manager.createVirtualDisplay("HeadSwap lock isolation probe",
                        640, 360, 128, images.surface,
                        DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, Build.VERSION.SDK_INT, true)))
                    try {
                        val actual = display.display
                        val group = DesktopDisplayIsolation.groupId(actual)
                        val independent = DesktopDisplayIsolation.independent(actual.flags, group)
                        result.putInt("sdk", Build.VERSION.SDK_INT)
                        result.putInt("displayGroup", group ?: -1)
                        result.putInt("displayFlags", actual.flags)
                        result.putBoolean("independent", independent)
                        holds.hold(actual.displayId)
                        shell(automation, "input keyevent KEYCODE_SLEEP")
                        SystemClock.sleep(2500)
                        result.putInt("phoneState", manager.getDisplay(0).state)
                        result.putInt("projectionState", manager.getDisplay(actual.displayId).state)
                        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
                        result.putBoolean("secureKeyguard", keyguard.isDeviceSecure)
                        result.putBoolean("phoneLocked", keyguard.isDeviceLocked)
                        if (keyguard.isDeviceSecure) check(keyguard.isDeviceLocked) { "Secure keyguard must be locked" }
                        if (independent) {
                            check(manager.getDisplay(0).state == Display.STATE_OFF) { "Physical screen must sleep" }
                            check(manager.getDisplay(actual.displayId).state == Display.STATE_ON) { "Independent display slept" }
                        } else result.putString("capability", "Framework did not isolate the display; no global lock override applied")
                    } finally { holds.close(); display.release() }
                }
            } finally { holds.close() }
        } catch (error: Throwable) { result.putString("error", android.util.Log.getStackTraceString(error)) }
        finally {
            ui?.let {
                runCatching { shell(it, "input keyevent KEYCODE_WAKEUP") }
                runCatching { shell(it, "wm dismiss-keyguard") }
                it.dropShellPermissionIdentity()
            }
        }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }

    private fun shell(ui: UiAutomation, command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
}
