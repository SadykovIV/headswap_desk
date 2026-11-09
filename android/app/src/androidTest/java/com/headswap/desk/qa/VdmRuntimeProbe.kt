package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.*
import android.view.Display
import com.headswap.desk.dex.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Exercises the actual authenticated shell host, app gateway and delegated producer on an AVD. */
internal object VdmRuntimeProbe {
    private const val PIN = "582471"
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        val context = test.targetContext
        var ui: UiAutomation? = null
        var ownsPin = false
        var lease: DelegatedBootstrapState.Lease? = null
        var gateway: DelegatedInputGateway? = null
        var bound = false
        var service: IDexUserService? = null
        val ready = CountDownLatch(1)
        val consumers = mutableListOf<Frames>()
        val ids = mutableListOf<Int>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IDexUserService.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 37)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            check(!keyguard.isDeviceSecure) { "Refusing to change an existing credential" }
            val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui = automation
            automation.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY, DelegatedDesktopAccess.INJECT_EVENTS,
                DelegatedDesktopAccess.REAL_GET_TASKS, DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL,
                ShellDisplayIme.PERMISSION, "android.permission.DUMP", "android.permission.WAKE_LOCK")
            lease = DelegatedDesktopAccess.begin { }
            val activation = UUID.randomUUID().toString().replace("-", "")
            VdmHostLink.beginActivation(activation)
            bootstrap(automation, context, activation)
            val hostLifetime = checkNotNull(VdmHostLink.nativeHostLifetime)
            val nativeDeath = CountDownLatch(1)
            hostLifetime.linkToDeath({ nativeDeath.countDown() }, 0)
            val input = DelegatedInputGateway(context).also { gateway = it }
            val token = Binder()
            input.beginGeneration(1, token, true)
            val intent = Intent(context, DelegatedDesktopService::class.java)
                .setAction(DelegatedDesktopService.BIND_ACTION_PREFIX + "vdm-qa-" + SystemClock.uptimeMillis())
                .putExtras(Bundle().apply { putBinder(DelegatedInputGateway.EXTRA_BINDER, input) })
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            check(bound && ready.await(8, TimeUnit.SECONDS)) { "Producer did not bind" }
            val remote = checkNotNull(service)
            check(remote.protocolVersion() == DexUserServicePolicy.PROTOCOL_VERSION)
            val manager = context.getSystemService(DisplayManager::class.java)
            fun frames(w: Int, h: Int) = Frames(w, h).also(consumers::add)
            val parentFrames = frames(1280, 720)
            val parent = remote.startDesktopSized(parentFrames.reader.surface, token, 1280, 720, 128,
                DesktopModePolicy.ANDROID_DESKTOP).also(ids::add)
            await { parentFrames.count.get() > 0 }
            val group = checkNotNull(DesktopDisplayIsolation.groupId(manager.getDisplay(parent)))
            check(group > 0 && VdmHostLink.ownsDisplay(parent))
            val parentRead = checkNotNull(VdmHostLink.captureOwnedDisplay(parent))
            parentRead()
            check(VdmHostLink.captureOwnedDisplay(0) == null && VdmHostLink.captureOwnedDisplay(Int.MAX_VALUE) == null)
            result.putBoolean("guidanceCapabilityRejectsForeignDisplay", true)
            val producerLifetime = checkNotNull(input.nativeProducerLifetime)
            check(producerLifetime != service!!.asBinder()) { "Native lifetime must belong to host" }
            val childToken = Binder()
            val first = frames(640, 360)
            val child = remote.createChildDisplay(first.reader.surface, childToken, 640, 360, 128).also(ids::add)
            check(remote.childProducerLifetime(childToken) == producerLifetime)
            val childRead = checkNotNull(VdmHostLink.captureOwnedDisplay(child))
            childRead()
            result.putBoolean("guidanceCapabilityOwnsParentAndChild", true)
            remote.launchChildApp(childToken, context.packageName + ".test", VirtualDeviceLockFixture::class.java.name)
            await { first.count.get() > 3 && first.color(100, 250) == Color.CYAN }
            check(DesktopDisplayIsolation.groupId(manager.getDisplay(child)) == group)
            first.save(File(context.filesDir, "qa-vdm-before-lock.png"))
            // We proved there was no credential. Mark ownership before mutating so a lost reply
            // still enters cleanup; never change or guess a pre-existing device credential.
            ownsPin = true
            shell(automation, "locksettings set-pin $PIN")
            await { keyguard.isDeviceSecure }
            shell(automation, "input keyevent KEYCODE_SLEEP")
            await { keyguard.isDeviceLocked && manager.getDisplay(0).state == Display.STATE_OFF }
            val before = first.count.get(); val pts = first.timestamp.get()
            await { first.count.get() >= before + 5 && first.timestamp.get() > pts }
            check(manager.getDisplay(parent).state == Display.STATE_ON && manager.getDisplay(child).state == Display.STATE_ON)
            remote.injectChildTouch(childToken, 0, 100f, 250f)
            remote.injectChildTouch(childToken, 1, 100f, 250f)
            await { first.color(100, 250) == Color.MAGENTA }
            check(keyguard.isDeviceLocked && manager.getDisplay(0).state == Display.STATE_OFF)
            first.save(File(context.filesDir, "qa-vdm-locked-touch.png"))
            result.putBoolean("pinLockedLiveFramesAndTouch", true)
            result.putInt("group", group)
            result.putLong("lockedFrames", first.count.get() - before)

            // A window opened after the lock must join the existing device, never display group0.
            val secondToken = Binder()
            val second = frames(640, 360)
            val secondId = remote.createChildDisplay(second.reader.surface, secondToken, 640, 360, 128).also(ids::add)
            remote.launchChildApp(secondToken, context.packageName + ".test", VirtualDeviceLockFixture::class.java.name)
            await { second.count.get() > 3 && second.color(100, 250) == Color.CYAN }
            check(DesktopDisplayIsolation.groupId(manager.getDisplay(secondId)) == group)
            check(VdmHostLink.ownsDisplay(secondId) && keyguard.isDeviceLocked)
            parentRead(); childRead()
            val releasedRead = checkNotNull(VdmHostLink.captureOwnedDisplay(secondId))
            result.putBoolean("guidanceCapabilitySurvivesPhonePinLock", true)
            remote.resizeChildDisplay(childToken, 640, 360, 160)
            val metrics = android.util.DisplayMetrics()
            await {
                @Suppress("DEPRECATION") manager.getDisplay(child).getRealMetrics(metrics)
                metrics.densityDpi == 160
            }
            remote.closeChildDisplay(secondToken)
            await { manager.getDisplay(secondId) == null }
            check(!VdmHostLink.ownsDisplay(secondId) && VdmHostLink.ownsDisplay(child))
            check(runCatching(releasedRead).isFailure && VdmHostLink.captureOwnedDisplay(secondId) == null)
            result.putBoolean("guidanceCapabilityRetiresWithChild", true)
            result.putBoolean("newChildResizeAndReleaseWhileLocked", true)
            remote.stop()
            await { ids.all { manager.getDisplay(it) == null } }
            check(VdmHostLink.isReady) { "Normal close should retain reusable host" }
            result.putBoolean("nativeCloseAckAndNoLeakedDisplay", true)
            input.deactivate()
            val nextToken = Binder()
            input.beginGeneration(2, nextToken, true)
            val next = frames(1280, 720)
            val replacement = remote.startDesktopSized(next.reader.surface, nextToken, 1280, 720, 128,
                DesktopModePolicy.ANDROID_DESKTOP).also(ids::add)
            await { next.count.get() > 0 }
            check(runCatching(parentRead).isFailure && runCatching(childRead).isFailure)
            checkNotNull(VdmHostLink.captureOwnedDisplay(replacement)).invoke()
            result.putBoolean("guidanceCapabilityCannotTransferToNextSession", true)
            check(keyguard.isDeviceLocked && manager.getDisplay(0).state == Display.STATE_OFF &&
                DesktopDisplayIsolation.confirmedIndependence(manager.getDisplay(replacement)) == true)
            remote.stop()
            await { manager.getDisplay(replacement) == null }
            result.putBoolean("newParentSessionWhilePinLocked", true)
            VdmHostLink.endActivation()
            check(nativeDeath.await(5, TimeUnit.SECONDS)) { "Host did not terminate after activation end" }
            result.putBoolean("nativeDeathAfterActivationEnd", true)
            result.putInt("sdk", Build.VERSION.SDK_INT)
        } catch (error: Throwable) { result.putString("error", android.util.Log.getStackTraceString(error)) }
        finally {
            runCatching { service?.stop() }
            runCatching { VdmHostLink.endActivation() }
            if (bound) runCatching { context.unbindService(connection) }
            runCatching { gateway?.deactivate() }
            lease?.let { runCatching { DelegatedDesktopAccess.end(it) } }
            consumers.forEach { runCatching { it.close() } }
            ui?.let { automation ->
                if (ownsPin) {
                    runCatching { shell(automation, "locksettings clear --old $PIN") }
                    if (runCatching { await { !context.getSystemService(KeyguardManager::class.java).isDeviceSecure } }.isFailure)
                        result.putString("cleanupError", "Disposable test PIN cleanup failed")
                }
                runCatching { shell(automation, "input keyevent KEYCODE_WAKEUP") }
                runCatching { shell(automation, "wm dismiss-keyguard") }
                automation.dropShellPermissionIdentity()
            }
        }
        test.finish(if (result.containsKey("error") || result.containsKey("cleanupError")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }
    private fun bootstrap(ui: UiAutomation, context: Context, token: String) {
        val request = checkNotNull(VdmHostLink.requestLaunch())
        val apk = context.applicationInfo.sourceDir
        check(VdmHostBootstrapPolicy.validApk(apk) && apk.none(Char::isWhitespace))
        // UiAutomation executes argv directly; unlike an ADB shell it does not interpret VAR=.
        val output = shell(ui, "/system/bin/env CLASSPATH=$apk /system/bin/app_process / " +
            "${VdmHostBootstrapPolicy.ENTRY} ${Process.myUid()} ${Process.myPid()} $token ${request.nonce}")
        check(output.lineSequence().any { it.trim() == "HEADSWAP_VDM_READY" } && VdmHostLink.isReady) {
            "Production bootstrap failed: ${output.take(512)}"
        }
    }
    private fun shell(ui: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun await(timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "VDM runtime condition timed out" }; SystemClock.sleep(80) }
    }
    private class Frames(width: Int, height: Int) : AutoCloseable {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val count = AtomicLong(); val timestamp = AtomicLong()
        private val thread = HandlerThread("qa-vdm-frames").apply { start() }
        private var bitmap: Bitmap? = null
        init {
            reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                synchronized(this) { bitmap?.recycle(); bitmap = cropped }
                if (padded !== cropped) padded.recycle()
                timestamp.set(image.timestamp); count.incrementAndGet()
            } }, Handler(thread.looper))
        }
        @Synchronized fun color(x: Int, y: Int): Int? = bitmap?.getPixel(x, y)
        @Synchronized fun save(file: File) { file.outputStream().use { checkNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        override fun close() {
            reader.setOnImageAvailableListener(null, null); thread.quitSafely(); thread.join(2000)
            reader.close(); synchronized(this) { bitmap?.recycle(); bitmap = null }
        }
    }
}
