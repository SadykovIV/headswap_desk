package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import com.headswap.desk.dex.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Raw input -> app rendering -> ImageReader. Intentionally excludes CarLife/H.264 transport. */
internal object VdmLatencyProbe {
    private const val PIN = "617402"
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        var ui: UiAutomation? = null
        var scene: VdmFunctionalProbe.Scene? = null
        var ownsPin = false
        val windows = JSONArray()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 37)
            val keyguard = test.targetContext.getSystemService(KeyguardManager::class.java)
            check(!keyguard.isDeviceSecure) { "Refusing to alter an existing credential" }
            val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui = automation
            automation.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY, DelegatedDesktopAccess.INJECT_EVENTS,
                DelegatedDesktopAccess.REAL_GET_TASKS, DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL,
                ShellDisplayIme.PERMISSION, "android.permission.DUMP", "android.permission.WAKE_LOCK")
            val active = VdmFunctionalProbe.Scene(test, automation).also { scene = it }
            active.start()
            val (token, child) = active.child(640, 360)
            active.remote.launchChildApp(token, active.fixturePackage, VdmLatencyFixture::class.java.name)
            val frames = active.frames.last()
            await(12_000) {
                frames.color(16, 16) == 0xff000000.toInt() &&
                    shell(automation, "dumpsys input").lineSequence().any {
                        it.contains(VdmLatencyFixture::class.java.name) && it.contains("displayId=$child") && !it.contains("NOT_VISIBLE")
                    }
            }
            var sequence = 0
            fun measure(label: String) {
                val samples = JSONArray()
                repeat(12) {
                    sequence++
                    val begin = SystemClock.elapsedRealtimeNanos()
                    active.remote.injectChildTouch(token, 0, 200f, 200f)
                    active.remote.injectChildTouch(token, 1, 200f, 200f)
                    val rpc = SystemClock.elapsedRealtimeNanos()
                    await(2500) { (frames.color(16, 16)?.and(0xffffff)) == sequence }
                    val rendered = SystemClock.elapsedRealtimeNanos()
                    samples.put(JSONObject().put("sequence", sequence)
                        .put("rpcMs", (rpc-begin)/1_000_000.0)
                        .put("rawFrameMs", (rendered-begin)/1_000_000.0)
                        .put("frameworkDispatchMs", frames.color(80, 16)?.and(0xffffff)))
                    SystemClock.sleep(80)
                }
                val power = active.context.getSystemService(PowerManager::class.java)
                windows.put(JSONObject().put("phase", label).put("samples", samples)
                    .put("deviceIdle", power.isDeviceIdleMode).put("powerSave", power.isPowerSaveMode)
                    .put("phoneState", active.displays.getDisplay(0).state)
                    .put("childState", active.displays.getDisplay(child).state))
                result.putString("latestPhase", label)
                test.sendStatus(0, Bundle().apply { putString("latencyPhase", label) })
                File(active.context.filesDir, "qa-vdm-latency.json").writeText(windows.toString(2))
            }
            measure("awake")
            ownsPin = true
            check(shell(automation, "locksettings set-pin $PIN").contains("Pin set"))
            shell(automation, "input keyevent KEYCODE_SLEEP")
            await(5000) { keyguard.isDeviceLocked && active.displays.getDisplay(0).state == Display.STATE_OFF }
            val lockedAt = SystemClock.elapsedRealtime()
            for (seconds in listOf(0, 30, 60, 120, 180)) {
                val target = lockedAt + seconds * 1000
                while (SystemClock.elapsedRealtime() < target) SystemClock.sleep((target-SystemClock.elapsedRealtime()).coerceIn(1, 250))
                check(keyguard.isDeviceLocked && active.displays.getDisplay(0).state == Display.STATE_OFF)
                check(active.displays.getDisplay(child).state == Display.STATE_ON)
                measure("locked${seconds}s")
            }
            result.putString("measurements", windows.toString())
            result.putBoolean("threeMinutesSecureLockedRawInputCompleted", true)
            result.putString("scope", "raw child input and compositor only; no H264/CarLife or connector queue claim")
        } catch (error: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(error))
            result.putString("measurements", windows.toString())
        } finally {
            runCatching { scene?.close() }.onFailure { result.putString("cleanupError", it.toString()) }
            ui?.let { automation ->
                if (ownsPin) {
                    val cleared = runCatching { shell(automation, "locksettings clear --old $PIN") }.getOrDefault("")
                    if (!cleared.contains("Lock credential cleared")) result.putString("cleanupError", "Disposable PIN not cleared")
                }
                runCatching { shell(automation, "input keyevent KEYCODE_WAKEUP") }
                runCatching { shell(automation, "wm dismiss-keyguard") }
                automation.dropShellPermissionIdentity()
            }
        }
        test.finish(if (result.containsKey("error") || result.containsKey("cleanupError")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }
    private fun shell(ui: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun await(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Raw input/frame condition timed out" }; SystemClock.sleep(5) }
    }
}
