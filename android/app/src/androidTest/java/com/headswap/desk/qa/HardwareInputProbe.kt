package com.headswap.desk.qa

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.*
import com.headswap.desk.dex.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object HardwareInputProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        var ui: UiAutomation? = null
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui = automation
            automation.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.INJECT_EVENTS, DelegatedDesktopAccess.REAL_GET_TASKS,
                DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL, ShellDisplayIme.PERMISSION, "android.permission.DUMP")
            val context = test.targetContext
            val lease = DelegatedDesktopAccess.begin {}
            val gateway = DelegatedInputGateway(context)
            val token = Binder()
            gateway.beginGeneration(1, token, true)
            val ready = CountDownLatch(1)
            var remote: IDexUserService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = IDexUserService.Stub.asInterface(binder); ready.countDown() }
                override fun onServiceDisconnected(name: ComponentName) { remote = null }
            }
            var bound = false
            try {
                val intent = Intent(context, DelegatedDesktopService::class.java)
                    .setAction(DelegatedDesktopService.BIND_ACTION_PREFIX + "input-probe-" + SystemClock.uptimeMillis())
                intent.putExtras(Bundle().apply { putBinder(DelegatedInputGateway.EXTRA_BINDER, gateway) })
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound && ready.await(8, TimeUnit.SECONDS)) { "Producer did not bind" }
                val service = checkNotNull(remote)
                ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).use { pixels ->
                    val drain = HandlerThread("qa-input-images").apply { start() }
                    pixels.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(drain.looper))
                    try {
                        val parent = service.startDesktopSized(pixels.surface, token, 640, 480, 128, DesktopModePolicy.ANDROID_DESKTOP)
                        DexState.displayId = parent; DexState.running = true
                        shell(automation, "am start -W --display $parent -f 0x10000000 -n com.headswap.desk.test/com.headswap.desk.qa.HardwareInputFixture")
                        await { top(context, parent) == "com.headswap.desk.qa.HardwareInputFixture" }
                        service.injectKey(0, 4); service.injectKey(1, 4)
                        await { shell(automation, "run-as com.headswap.desk.test cat files/hardware-$parent.txt").contains("up=4") }
                        check(top(context, parent) == "com.headswap.desk.qa.HardwareInputFixture")
                        service.injectKey(0, 4); service.injectKey(1, 4)
                        await { top(context, parent) == DesktopModePolicy.ANDROID_HOME_CLASS }
                        result.putBoolean("doubleBackHome", true)

                        ImageReader.newInstance(400, 300, PixelFormat.RGBA_8888, 3).use { childPixels ->
                            childPixels.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(drain.looper))
                            val childToken = Binder()
                            val child = service.createChildDisplay(childPixels.surface, childToken, 400, 300, 128)
                            try {
                                service.launchChildApp(childToken, "com.headswap.desk.test", "com.headswap.desk.qa.HardwareInputFixture")
                                await { top(context, child) == "com.headswap.desk.qa.HardwareInputFixture" }
                                service.injectChildTouch(childToken, 0, 100f, 100f)
                                service.injectChildTouch(childToken, 1, 100f, 100f)
                                result.putInt("child", child)
                                result.putInt("focused", gateway.focusedDisplay(parent, token))
                                await { gateway.focusedDisplay(parent, token) == child }
                                for (key in DesktopRotaryInput.keys(2) + listOf(23)) { service.injectKey(0, key); service.injectKey(1, key) }
                                await {
                                    val events = shell(automation, "run-as com.headswap.desk.test cat files/hardware-$child.txt")
                                    events.split("up=20").size == 3 && events.contains("up=23")
                                }
                                result.putBoolean("rotaryAndOkOnFocusedChild", true)
                            } finally { service.closeChildDisplay(childToken) }
                        }
                    } finally { service.stop(); drain.quitSafely() }
                }
            } finally {
                if (bound) context.unbindService(connection)
                gateway.deactivate(); DelegatedDesktopAccess.end(lease)
                DexState.displayId = -1; DexState.running = false
            }
        } catch (error: Throwable) { result.putString("error", android.util.Log.getStackTraceString(error)) }
        finally { ui?.dropShellPermissionIdentity() }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }

    @Suppress("DEPRECATION")
    private fun top(context: Context, display: Int): String? = context.getSystemService(ActivityManager::class.java)
        .getRunningTasks(100).firstOrNull { FrameworkTaskIdentityReader.read(it)?.displayId == display }?.topActivity?.className

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 3500
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out waiting for owned input window" }; SystemClock.sleep(30) }
    }

    private fun shell(ui: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { String(it.readBytes()) }
}
