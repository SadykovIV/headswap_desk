package com.headswap.desk.qa

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.ImageReader
import android.os.*
import android.view.Display
import android.view.KeyEvent
import com.headswap.desk.dex.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Actual production VDM gateway, external-UID apps, local IME and registered playback capture. */
internal object VdmFunctionalProbe {
    @JvmStatic fun run(test: Instrumentation, audio: Boolean) {
        val result = Bundle()
        var ui: UiAutomation? = null
        var scene: Scene? = null
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 37)
            val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui = automation
            automation.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY, DelegatedDesktopAccess.INJECT_EVENTS,
                DelegatedDesktopAccess.REAL_GET_TASKS, DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL,
                ShellDisplayIme.PERMISSION, "android.permission.DUMP", "android.permission.WAKE_LOCK",
                "android.permission.CAPTURE_AUDIO_OUTPUT", "android.permission.MODIFY_AUDIO_ROUTING")
            val active = Scene(test, automation).also { scene = it }
            active.start()
            if (audio) verifyAudio(active, result) else verifyIme(active, result)
        } catch (error: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(error))
            scene?.let { active ->
                runCatching { active.frames.lastOrNull()?.save(File(active.context.filesDir, "qa-vdm-functional-failed.png")) }
                runCatching { File(active.context.filesDir, "qa-vdm-functional-inputmethod.txt")
                    .writeText(shell(active.ui, "dumpsys input_method")) }
                runCatching { result.putString("fixture", active.fixture()) }
                runCatching { File(active.context.filesDir, "qa-vdm-functional-input.txt")
                    .writeText(shell(active.ui, "dumpsys input")) }
                runCatching { File(active.context.filesDir, "qa-vdm-functional-window.txt")
                    .writeText(shell(active.ui, "dumpsys window windows")) }
            }
        } finally {
            runCatching { scene?.close() }.onFailure { result.putString("cleanupError", it.toString()) }
            ui?.let {
                runCatching { shell(it, "input keyevent KEYCODE_WAKEUP") }
                runCatching { shell(it, "wm dismiss-keyguard") }
                it.dropShellPermissionIdentity()
            }
        }
        test.finish(if (result.containsKey("error") || result.containsKey("cleanupError")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }

    private fun verifyIme(scene: Scene, result: Bundle) {
        val (token, child) = scene.child(1280, 720)
        scene.remote.launchChildApp(token, scene.fixturePackage, VdmImeFixture::class.java.name)
        await {
            val state = scene.fixture()
            state.contains("display=$child ") && Regex("width=([1-9][0-9]*)").containsMatchIn(state) &&
                shell(scene.ui, "dumpsys input").lineSequence().any {
                    it.contains(VdmImeFixture::class.java.name) && it.contains("displayId=$child") && !it.contains("NOT_VISIBLE")
                }
        }
        check(scene.gateway.childImeVisibility(child, token) == 0)
        shell(scene.ui, "input keyevent KEYCODE_SLEEP")
        await { scene.displays.getDisplay(0).state == Display.STATE_OFF }
        check(scene.displays.getDisplay(child).state == Display.STATE_ON)
        val state = scene.fixture()
        val x = Regex(" x=(\\d+)").find(state)!!.groupValues[1].toFloat()
        val y = Regex(" y=(\\d+)").find(state)!!.groupValues[1].toFloat()
        scene.remote.injectChildTouch(token, KeyEvent.ACTION_DOWN, x, y)
        scene.remote.injectChildTouch(token, KeyEvent.ACTION_UP, x, y)
        await { scene.gateway.childImeVisibility(child, token) == 1 && scene.fixture().contains("ime=true") }
        check(scene.displays.getDisplay(0).state == Display.STATE_OFF)
        // Insets/WM visibility can precede the animation's first compositor frame.
        // The fixture is solid green below the input; the actual IME must change that region.
        await { scene.frames.last().changedLowerRegion() > 250 && scene.gateway.childImeVisibility(child, token) == 1 }
        scene.frames.last().save(File(scene.context.filesDir, "qa-vdm-ime-visible.png"))
        result.putBoolean("localImeVisibleWithPhoneOff", true)
        scene.remote.injectKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        scene.remote.injectKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)
        await { scene.fixture().contains("text=a ") }
        scene.remote.injectKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        scene.remote.injectKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        await { scene.gateway.childImeVisibility(child, token) == 0 && scene.fixture().contains("ime=false") }
        check(scene.displays.getDisplay(0).state == Display.STATE_OFF)
        check(scene.onDisplay(child, VdmImeFixture::class.java.name)) { "Back closed the text activity" }
        check(scene.fixture().contains("text=a "))
        scene.frames.last().save(File(scene.context.filesDir, "qa-vdm-ime-hidden.png"))
        result.putBoolean("textEnteredAndSingleBackKeepsField", true)
        result.putInt("child", child)
    }

    private fun verifyAudio(scene: Scene, result: Bundle) {
        val (_, child) = scene.child(640, 360)
        val uid = scene.context.packageManager.getApplicationInfo(scene.fixturePackage, 0).uid
        check(uid != Process.myUid()) { "Audio fixture must have a different UID" }
        shell(scene.ui, "input keyevent KEYCODE_SLEEP")
        await { scene.displays.getDisplay(0).state == Display.STATE_OFF }
        // Start with the excluded usage, so previous fixture playback cannot make it pass.
        for (usage in intArrayOf(AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.USAGE_ASSISTANT)) {
            RegisteredPlaybackMix.open(scene.context).use { mix ->
                val record = mix.record
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                val launch = shell(scene.ui, "am start -W --display $child -f 0x10008000 -n " +
                    "${scene.fixturePackage}/${MixToneActivity::class.java.name} --ei usage $usage")
                check("Status: ok" in launch) { "Tone did not start: $launch" }
                await { scene.onDisplay(child, MixToneActivity::class.java.name) }
                val frequency = if (usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) 1500 else 1000
                val pcm = ShortArray(1920)
                var samples = 0; var peak = 0; var matching = 0
                val until = SystemClock.elapsedRealtime() + 3200
                while (SystemClock.elapsedRealtime() < until) {
                    val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "PCM read failed $count" }
                    val frames = count / 2
                    var real = 0.0; var imaginary = 0.0; var energy = 0.0
                    for (index in 0 until frames) {
                        val value = pcm[index * 2].toInt()
                        peak = max(peak, abs(value))
                        val angle = 2 * PI * index * frequency / 48000
                        real += value * cos(angle); imaginary += value * sin(angle)
                        energy += value.toDouble() * value
                    }
                    if (frames >= 192 && energy > frames * 64 &&
                        2 * (real * real + imaginary * imaginary) > .7 * frames * energy) matching++
                    samples += frames
                    SystemClock.sleep(8)
                }
                val value = "frames=$samples peak=$peak frequencyHz=$frequency matchingWindows=$matching display=$child"
                result.putString("usage_$usage", value)
                check(samples >= 24000 && if (usage == AudioAttributes.USAGE_ALARM) matching == 0 else matching >= 3) {
                    "Unexpected captured VDM playback: $value"
                }
                check(scene.displays.getDisplay(0).state == Display.STATE_OFF) { "Playback woke the phone" }
                record.stop()
            }
        }
        result.putBoolean("externalUidPlaybackFromVdmChild", true)
        result.putBoolean("phoneRemainsOffDuringPlayback", true)
    }

    internal class Scene(val test: Instrumentation, val ui: UiAutomation) : AutoCloseable {
        val context = test.targetContext
        val fixturePackage = test.context.packageName
        val displays = context.getSystemService(DisplayManager::class.java)
        val gateway = DelegatedInputGateway(context)
        val frames = mutableListOf<Frames>()
        lateinit var remote: IDexUserService
        private var lease: DelegatedBootstrapState.Lease? = null
        private var host: AutoCloseable? = null
        private var bound = false
        private val ready = CountDownLatch(1)
        private var group = -1
        var parent = -1
            private set
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = IDexUserService.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        fun start() {
            lease = DelegatedDesktopAccess.begin { }
            host = VdmProbeHost.start(test, ui)
            val token = Binder()
            gateway.beginGeneration(1, token, true)
            val intent = Intent(context, DelegatedDesktopService::class.java)
                .setAction(DelegatedDesktopService.BIND_ACTION_PREFIX + "vdm-functional-" + SystemClock.uptimeMillis())
                .putExtras(Bundle().apply { putBinder(DelegatedInputGateway.EXTRA_BINDER, gateway) })
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            check(bound && ready.await(8, TimeUnit.SECONDS))
            val parentPixels = Frames(1280, 720).also(frames::add)
            parent = remote.startDesktopSized(parentPixels.reader.surface, token, 1280, 720, 128,
                DesktopModePolicy.ANDROID_DESKTOP)
            group = checkNotNull(DesktopDisplayIsolation.groupId(displays.getDisplay(parent)))
            check(group > 0 && (Build.VERSION.SDK_INT < 37 || VdmHostLink.ownsDisplay(parent)))
        }
        fun child(w: Int, h: Int): Pair<IBinder, Int> {
            val token = Binder()
            val pixels = Frames(w, h).also(frames::add)
            val id = remote.createChildDisplay(pixels.reader.surface, token, w, h, 128)
            val display = checkNotNull(displays.getDisplay(id))
            if (Build.VERSION.SDK_INT >= 37) {
                check(DesktopDisplayIsolation.groupId(display) == group && VdmHostLink.ownsDisplay(id))
            } else {
                // LOCAL owns independent groups per display; only VDM guarantees one family group.
                check(DesktopDisplayIsolation.confirmedIndependence(display) == true)
            }
            return token to id
        }
        @Suppress("DEPRECATION") fun onDisplay(id: Int, name: String): Boolean =
            context.getSystemService(ActivityManager::class.java).getRunningTasks(100).any {
                val display = it.javaClass.getField("displayId").getInt(it)
                display == id && it.topActivity?.className == name
            }
        fun fixture(): String = shell(ui, "run-as $fixturePackage cat files/vdm-ime.txt")
        override fun close() {
            val stopped = runCatching { if (::remote.isInitialized) remote.stop() }.isSuccess
            val native = VdmHostLink.nativeHostLifetime
            host?.close()
            if (!stopped && native?.isBinderAlive == true) await { !native.isBinderAlive }
            if (bound) context.unbindService(connection)
            gateway.deactivate()
            lease?.let(DelegatedDesktopAccess::end)
            frames.forEach(Frames::close)
        }
    }

    internal class Frames(w: Int, h: Int) : AutoCloseable {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        private val thread = HandlerThread("qa-vdm-functional-images").apply { start() }
        private var bitmap: Bitmap? = null
        init {
            reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val crop = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                synchronized(this) { bitmap?.recycle(); bitmap = crop }
                if (padded !== crop) padded.recycle()
            } }, Handler(thread.looper))
        }
        @Synchronized fun color(x: Int, y: Int): Int? = bitmap?.getPixel(x, y)
        @Synchronized fun changedLowerRegion(): Int {
            val frame = bitmap ?: return 0
            var changed = 0
            for (y in frame.height / 2 until frame.height - 64 step 8)
                for (x in 8 until frame.width - 8 step 8)
                    if ((frame.getPixel(x, y) and 0xffffff) != 0x164934) changed++
            return changed
        }
        @Synchronized fun save(file: File) { file.outputStream().use { checkNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        override fun close() {
            reader.setOnImageAvailableListener(null, null); thread.quitSafely(); thread.join(2000)
            reader.close(); synchronized(this) { bitmap?.recycle(); bitmap = null }
        }
    }
    private fun shell(ui: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "VDM functional condition timed out" }; SystemClock.sleep(80) }
    }
}
