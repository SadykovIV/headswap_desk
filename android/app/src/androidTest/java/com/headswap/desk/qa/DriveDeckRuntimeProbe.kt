package com.headswap.desk.qa

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import com.headswap.desk.desktop.DriveDeckFullscreenActivity
import android.media.ImageReader
import android.os.*
import com.headswap.desk.CarLifeState
import com.headswap.desk.dex.*
import com.headswap.desk.embed.EmbeddedSessionRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Disposable emulator only. The caller supplies companion prefs with Settings + Clock in the split page. */
internal object DriveDeckRuntimeProbe {
    @JvmStatic @JvmOverloads fun run(test: Instrumentation, fullscreen: Boolean = false) {
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
            val clockPackage = listOf("com.google.android.deskclock", "com.android.deskclock")
                .firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
                ?: error("Install a clock fixture package before this probe")
            val lease = DelegatedDesktopAccess.begin {}
            val gateway = DelegatedInputGateway(context)
            val token = Binder()
            gateway.beginGeneration(1, token, true)
            val owner = Any()
            val calls = Executors.newSingleThreadExecutor()
            val ready = CountDownLatch(1)
            var remote: IDexUserService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = IDexUserService.Stub.asInterface(binder); ready.countDown() }
                override fun onServiceDisconnected(name: ComponentName) { remote = null }
            }
            var bound = false
            try {
                val intent = Intent(context, DelegatedDesktopService::class.java)
                    .setAction(DelegatedDesktopService.BIND_ACTION_PREFIX + "companion-probe-" + SystemClock.uptimeMillis())
                intent.putExtras(Bundle().apply { putBinder(DelegatedInputGateway.EXTRA_BINDER, gateway) })
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound && ready.await(8, TimeUnit.SECONDS)) { "Producer did not bind" }
                val service = checkNotNull(remote)
                ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3).use { pixels ->
                    val drain = HandlerThread("qa-companion-images").apply { start() }
                    val latest = AtomicReference<Bitmap?>()
                    pixels.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.use { image ->
                        val plane = image.planes[0]
                        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        synchronized(latest) { latest.getAndSet(cropped)?.recycle() }
                        if (padded !== cropped) padded.recycle()
                    } }, Handler(drain.looper))
                    try {
                        val parent = service.startDesktopSized(pixels.surface, token, 1280, 720, 128, DesktopModePolicy.DRIVEDECK)
                        EmbeddedSessionRegistry.activate(owner, service, parent, calls, DesktopModePolicy.DRIVEDECK, 128, false)
                        DexState.displayId = parent; DexState.running = true; CarLifeState.running = true
                        await(12000) { tasks(context).any { it.first == parent && it.second == DesktopModePolicy.DRIVEDECK_PACKAGE } }
                        // Re-enter after registry activation: verifies companion resume/create acknowledgement.
                        service.showDesktop()
                        await(16000) { childPackages(context, parent).containsAll(setOf("com.android.settings", clockPackage)) }
                        result.putString("splitPackages", childPackages(context, parent).sorted().joinToString())
                        result.putInt("parentDisplay", parent)
                        save(latest, File(context.filesDir, "qa-drivedeck-split.png"))
                        service.showDesktop()
                        await(5000) { childPackages(context, parent).containsAll(setOf("com.android.settings", clockPackage)) }
                        result.putBoolean("homeRetainsSplit", true)
                        if (fullscreen) {
                            val monitor = test.addMonitor(DriveDeckFullscreenActivity::class.java.name, null, false)
                            try {
                                val settings = checkNotNull(context.packageManager.getLaunchIntentForPackage("com.android.settings")?.component)
                                test.runOnMainSync {
                                    context.startActivity(Intent(context, DriveDeckFullscreenActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .putExtra("component", settings.flattenToString())
                                        .putExtra("back", true).putExtra("home", true),
                                        ActivityOptions.makeBasic().setLaunchDisplayId(parent).toBundle())
                                }
                                val fullscreenActivity = checkNotNull(monitor.waitForActivityWithTimeout(10000)) { "Fullscreen host did not launch" }
                                await(20000) { topClass(context, parent) == DriveDeckFullscreenActivity::class.java.name && populatedSettingsFrame(latest) }
                                // Discard a single transient frame; require stable populated content before preserving evidence.
                                SystemClock.sleep(1000)
                                save(latest, File(context.filesDir, "qa-drivedeck-fullscreen-diagnostic.png"))
                                check(populatedSettingsFrame(latest)) { "Settings frame is not populated" }
                                save(latest, File(context.filesDir, "qa-drivedeck-fullscreen-proven.png"))
                                val point = IntArray(2)
                                test.runOnMainSync {
                                    val button = checkNotNull(findHome(fullscreenActivity.window.decorView)) { "Floating Home button missing" }
                                    check(button.isShown && button.width > 0 && button.height > 0)
                                    button.getLocationOnScreen(point)
                                    point[0] += button.width / 2; point[1] += button.height / 2
                                }
                                // This is the actual HU input path, not performClick() or direct goHome().
                                service.injectTouchPoints(0, 0, intArrayOf(0), floatArrayOf(point[0].toFloat()), floatArrayOf(point[1].toFloat()))
                                SystemClock.sleep(60)
                                service.injectTouchPoints(1, 0, intArrayOf(0), floatArrayOf(point[0].toFloat()), floatArrayOf(point[1].toFloat()))
                                await(15000) { topClass(context, parent) == DesktopModePolicy.DRIVEDECK_HOME_CLASS &&
                                    childPackages(context, parent).containsAll(setOf("com.android.settings", clockPackage)) }
                                SystemClock.sleep(2000)
                                save(latest, File(context.filesDir, "qa-drivedeck-home-proven.png"))
                                result.putString("fullscreenHome", "rendered Settings; one parent DOWN/UP on floating Home restored DriveDeck")
                            } finally { test.removeMonitor(monitor) }
                        } else {
                            // Keep the disposable scene briefly for external UI clicks/screenshot inspection.
                            test.sendStatus(0, Bundle().apply { putString("scene", "DriveDeck split ready"); putInt("display", parent) })
                            repeat(40) {
                                SystemClock.sleep(1000)
                                save(latest, File(context.filesDir, "qa-drivedeck-live.png"))
                            }
                            save(latest, File(context.filesDir, "qa-drivedeck-final.png"))
                        }
                        check(tasks(context).none { it.first == 0 && it.second == DesktopModePolicy.DRIVEDECK_PACKAGE })
                        result.putBoolean("phoneHomeUnchanged", true)
                    } finally {
                        EmbeddedSessionRegistry.deactivate(owner)
                        service.stop()
                        EmbeddedSessionRegistry.producerStopped(owner)
                        pixels.setOnImageAvailableListener(null, null)
                        drain.quitSafely(); drain.join(2000)
                        latest.getAndSet(null)?.recycle()
                    }
                }
            } finally {
                if (bound) context.unbindService(connection)
                calls.shutdownNow(); gateway.deactivate(); DelegatedDesktopAccess.end(lease)
                DexState.displayId = -1; DexState.running = false; CarLifeState.running = false
            }
        } catch (error: Throwable) { result.putString("error", android.util.Log.getStackTraceString(error)) }
        finally { ui?.dropShellPermissionIdentity() }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }
    @Suppress("DEPRECATION") private fun tasks(context: Context): List<Pair<Int, String>> =
        context.getSystemService(ActivityManager::class.java).getRunningTasks(100).mapNotNull { task ->
            val id = FrameworkTaskIdentityReader.read(task)?.displayId ?: return@mapNotNull null
            id to (task.topActivity?.packageName ?: "")
        }
    @Suppress("DEPRECATION") private fun topClass(context: Context, display: Int): String? =
        context.getSystemService(ActivityManager::class.java).getRunningTasks(100)
            .firstOrNull { FrameworkTaskIdentityReader.read(it)?.displayId == display }?.topActivity?.className
    private fun findHome(view: View): View? {
        if (view.contentDescription == "Домой в DriveDeck") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findHome(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun populatedSettingsFrame(latest: AtomicReference<Bitmap?>): Boolean = synchronized(latest) {
        val frame = latest.get() ?: return@synchronized false
        var dark = 0; var light = 0; var rightLight = 0
        // Exclude floating controls: a white Activity placeholder with only buttons is not evidence.
        for (y in 60 until frame.height - 80 step 4) for (x in 80 until frame.width - 40 step 4) {
            val pixel = frame.getPixel(x, y)
            val value = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            if (value < 120) dark++
            if (value > 210) { light++; if (x > frame.width / 2) rightLight++ }
        }
        dark > 250 && light > 5000 && rightLight > 10000
    }
    private fun childPackages(context: Context, parent: Int) = tasks(context).filter { it.first > 0 && it.first != parent }.map { it.second }.toSet()
    private fun await(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out waiting for companion windows" }; SystemClock.sleep(100) }
    }
    private fun save(latest: AtomicReference<Bitmap?>, file: File) {
        // Decoder callback owns recycling; take a copy on its handler by retaining current bitmap briefly.
        synchronized(latest) {
            val image = latest.get() ?: error("No companion frame")
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
