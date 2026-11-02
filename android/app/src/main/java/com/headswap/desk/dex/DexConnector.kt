package com.headswap.desk.dex

import android.content.Context
import android.os.Build
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream
import com.headswap.desk.AppLog
import com.headswap.desk.aa.AaBridgeCore
import com.headswap.desk.aa.AaLifecycle
import com.headswap.desk.aa.AaPeer
import com.headswap.desk.aa.AaState
import com.headswap.desk.aa.SpsCrop
import com.headswap.desk.aa.TouchPoint
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okio.source

/** Experimental Samsung desktop -> scrcpy H.264 -> existing HeadSwap CarLife pipeline. */
class DexConnector(context: Context, private val core: AaBridgeCore) : AaLifecycle, AaPeer {
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val resources = CopyOnWriteArrayList<AutoCloseable>()
    private val controlQueue = LinkedBlockingQueue<ByteArray>(128)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dex-watchdog").apply { isDaemon = true } }
    private val lastVideoAt = AtomicLong(System.nanoTime())
    private val lastResetAt = AtomicLong(0)
    private val displayReady = CountDownLatch(1)
    private val touch = DexTouchMapper(core.videoMode.aaW, core.videoMode.aaH)
    @Volatile private var worker: Thread? = null
    @Volatile private var controlReady = false
    @Volatile private var streaming = false
    @Volatile private var displayId = -1
    @Volatile private var failure: String? = null

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        if (stopping.get()) { finished.countDown(); return }
        DexState.error = null
        DexState.running = false
        DexState.displayId = -1
        worker = Thread({ run() }, "dex-connector").apply { isDaemon = true; start() }
    }

    override fun stop(onDone: () -> Unit) {
        stopping.set(true)
        closeResources()
        worker?.interrupt()
        if (!started.get()) finished.countDown()
        Thread({
            try { finished.await(20, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            onDone()
        }, "dex-stop").apply { isDaemon = true; start() }
    }

    private fun run() {
        var established = false
        try {
            check(Build.MANUFACTURER.equals("samsung", ignoreCase = true) && Build.VERSION.SDK_INT >= 36) {
                "Этот прототип рассчитан на Samsung с One UI 8/9 (Android 16+)."
            }
            check(core.videoMode.marginW == 0 && core.videoMode.marginH == 0) { "Для DeX нужен видеорежим без полей Android Auto" }
            core.onAaConnecting()
            core.peer = this
            setStatus("Подключение к беспроводной отладке…")
            val startAt = System.nanoTime()
            scheduler.scheduleWithFixedDelay({
                if (stopping.get()) return@scheduleWithFixedDelay
                try {
                    core.tick()
                    core.desktopVideoError?.let { fail(it); return@scheduleWithFixedDelay }
                    val silent = System.nanoTime() - if (streaming) lastVideoAt.get() else startAt
                    if (silent > TimeUnit.SECONDS.toNanos(if (streaming) 15 else 35)) {
                        fail("DeX не передаёт видео. Проверьте беспроводную отладку и перезапустите мост.")
                    }
                } catch (e: Exception) { fail(e.message ?: "Ошибка контроля DeX") }
            }, 500, 500, TimeUnit.MILLISECONDS)
            val prefs = DexPrefs(context)
            val adb = own(DexAccess.connect(context, prefs.connectPort))
            DexAccess.verifyShell(adb, prefs.host, prefs.connectPort)
            ensureActive()

            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash == SERVER_SHA256) { "Неверная контрольная сумма scrcpy-server" }
            // Fixed destination in shell's private directory, no downloaded code at runtime.
            bytes.inputStream().source().use { adb.push(it, REMOTE, 0x180, 0L) } // 0600
            ensureActive()
            val scid = SecureRandom().nextInt(Int.MAX_VALUE).toString(16).padStart(8, '0')
            val mode = core.videoMode
            val density = core.densityOverride
            DexUserServicePolicy.requireDensity(density)
            require(mode.aaW in 320..4096 && mode.aaH in 240..2160)
            val command = "CLASSPATH=$REMOTE app_process / com.genymobile.scrcpy.Server ${ScrcpyProtocol.VERSION} " +
                "scid=$scid log_level=info audio=false control=true video=true video_codec=h264 " +
                "tunnel_forward=true send_device_meta=false send_dummy_byte=false " +
                "new_display=${mode.aaW}x${mode.aaH}/$density display_ime_policy=local " +
                "video_bit_rate=${DexVideoGeometry.SOURCE_BITRATE} max_fps=30 " +
                "video_codec_options=i-frame-interval=1,profile=1,max-bframes=0 " +
                "clipboard_autosync=false power_on=false cleanup=true vd_destroy_content=true"
            setStatus("Создание экрана DeX…")
            val logStream = own(adb.open("shell:$command"))
            Thread({ readServerLog(logStream) }, "dex-server-log").apply { isDaemon = true; start() }
            val endpoint = "localabstract:scrcpy_$scid"
            val video = connectStream(adb, endpoint)
            val control = connectStream(adb, endpoint)
            check(displayReady.await(12, TimeUnit.SECONDS)) { "scrcpy не сообщил ID виртуального дисплея" }
            ensureActive()
            check(displayId > 0) { "Нельзя использовать основной экран вместо DeX" }
            // One UI 8.5/9 may default new displays to fullscreen. This only changes our display.
            val freeform = adb.shell("wm set-display-windowing-mode -d $displayId 5")
            check(freeform.exitCode == 0) { "Прошивка не разрешила оконный режим DeX: ${freeform.allOutput.take(250)}" }
            val checkMode = adb.shell("wm get-display-windowing-mode -d $displayId")
            check(checkMode.exitCode == 0 && (checkMode.allOutput.contains("freeform", true) || checkMode.allOutput.trim() == "5")) {
                "Не удалось подтвердить оконный режим: ${checkMode.allOutput.take(250)}"
            }
            // One UI 9 can leave a new decorated display empty until its desktop HOME
            // is launched explicitly. Target only the display created by this session.
            setStatus("Открываю рабочий стол DeX…")
            val desktopHome = adb.shell("am start --display $displayId -a android.intent.action.MAIN " +
                "-c android.intent.category.SECONDARY_HOME -n $DESKTOP_HOME")
            check(desktopHome.exitCode == 0 && desktopHome.allOutput.lineSequence().none {
                it.trimStart().startsWith("Error", ignoreCase = true) || it.contains("Exception")
            }) { "Не удалось открыть рабочий стол Samsung DeX: ${desktopHome.allOutput.take(250)}" }
            startControl(control)
            established = true
            val reader = ScrcpyProtocol.VideoReader(video.source.inputStream())
            reader.readCodec()
            val geometry = DexVideoGeometry()
            AppLog.i("DEX geometry sourceBitrate=${DexVideoGeometry.SOURCE_BITRATE} carlifeBitrateCeiling=${mode.bitrate} density=$density", ui = false)
            fun logSourceGeometry(infos: List<SpsCrop.Info>) {
                for (info in infos) AppLog.i("DEX geometry stage=source-sps display=$displayId coded=${info.codedW}x${info.codedH} visible=${info.visibleW}x${info.visibleH} crop=${info.left},${info.right},${info.top},${info.bottom} native720=true", ui = false)
            }
            while (!stopping.get()) {
                core.desktopVideoError?.let { throw IOException(it) }
                when (val packet = reader.next()) {
                    is ScrcpyProtocol.Packet.Session -> {
                        geometry.acceptSession(packet.width, packet.height)
                        AppLog.i("DEX geometry stage=scrcpy-session display=$displayId size=${packet.width}x${packet.height} expected=${DexVideoGeometry.WIDTH}x${DexVideoGeometry.HEIGHT}", ui = false)
                    }
                    is ScrcpyProtocol.Packet.Video -> {
                        lastVideoAt.set(System.nanoTime())
                        if (packet.config) {
                            logSourceGeometry(geometry.acceptConfig(packet.bytes))
                            core.onCodecConfig(packet.bytes)
                        } else {
                            logSourceGeometry(geometry.beforeFrame(packet.bytes))
                            core.onFrame(packet.bytes, packet.ptsUs)
                            core.desktopVideoError?.let { throw IOException(it) }
                            if (!streaming) {
                                streaming = true
                                DexState.running = true
                                DexState.displayId = displayId
                                setStatus("DeX → CarLife · ${mode.aaW}×${mode.aaH} · звук через Bluetooth", placeholder = false)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopping.get()) {
                failure = e.message ?: e.javaClass.simpleName
                AppLog.e("DeX: $failure", e)
            }
        } finally {
            stopping.set(true)
            closeResources()
            controlReady = false
            controlQueue.clear()
            scheduler.shutdownNow()
            if (core.peer === this) core.peer = null
            core.onAaEnded(established)
            DexState.running = false
            DexState.displayId = -1
            DexState.error = failure
            setStatus(failure?.let { "DeX: $it. Нажмите «Остановить DeX», дождитесь остановки и подключите USB CarLife снова." } ?: "DeX остановлен")
            finished.countDown()
        }
    }

    private fun setStatus(text: String, placeholder: Boolean = true) {
        DexState.status = text
        AaState.status = text
        if (placeholder) core.showStatus(text)
        AppLog.i("DeX: $text")
    }

    private fun readServerLog(stream: AdbStream) {
        try {
            while (!stopping.get()) {
                val line = stream.source.readUtf8LineStrict(8192)
                val found = DISPLAY_ID.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (found != null && found > 0) { displayId = found; displayReady.countDown() }
                AppLog.i("scrcpy: ${line.take(1000)}", ui = false)
            }
        } catch (e: Exception) {
            if (!stopping.get()) fail("Процесс DeX завершился: ${e.message.orEmpty().take(200)}")
        }
    }

    private fun connectStream(adb: Kadb, endpoint: String): AdbStream {
        var error: Exception? = null
        repeat(30) {
            ensureActive()
            try { return own(adb.open(endpoint)) } catch (e: Exception) { error = e }
            Thread.sleep(200)
        }
        throw IOException("Не удалось подключиться к scrcpy на телефоне", error)
    }

    private fun startControl(stream: AdbStream) {
        controlReady = true
        Thread({
            try {
                while (!stopping.get()) {
                    val packet = controlQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    stream.sink.write(packet)
                    stream.sink.flush()
                }
            } catch (e: Exception) { if (!stopping.get()) fail("Потерян канал управления DeX") }
        }, "dex-input").apply { isDaemon = true; start() }
        // Even with clipboard disabled, drain server messages so its sender never blocks.
        Thread({
            try { while (!stopping.get()) stream.source.readByte() }
            catch (e: Exception) { if (!stopping.get()) fail("Канал управления DeX закрылся") }
        }, "dex-control-drain").apply { isDaemon = true; start() }
    }

    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        if (!controlReady || stopping.get()) return
        touch.encode(action, actionIndex, points).forEach(::enqueue)
    }

    override fun sendKey(keycode: Int) {
        if (keycode !in 0..1000) return
        enqueue(ScrcpyProtocol.key(0, keycode))
        enqueue(ScrcpyProtocol.key(1, keycode))
    }

    override fun requestKeyFrame() {
        if (!controlReady || stopping.get()) return
        val now = System.nanoTime()
        val previous = lastResetAt.get()
        // scrcpy RESET_VIDEO restarts the encoder; periodic IDRs already arrive every second.
        if (now - previous > TimeUnit.SECONDS.toNanos(2) && lastResetAt.compareAndSet(previous, now)) enqueue(byteArrayOf(17))
    }

    private fun enqueue(packet: ByteArray) {
        if (!controlReady || stopping.get()) return
        if (!controlQueue.offer(packet)) fail("Очередь управления DeX переполнена; соединение остановлено")
    }

    private fun ensureActive() { check(!stopping.get()) { "DeX остановлен" } }

    private fun <T : AutoCloseable> own(resource: T): T {
        resources.add(resource)
        if (stopping.get()) { resource.close(); resources.remove(resource); throw IOException("DeX остановлен") }
        return resource
    }

    private fun fail(message: String) {
        if (stopping.compareAndSet(false, true)) {
            failure = message
            closeResources()
            worker?.interrupt()
        }
    }

    private fun closeResources() {
        resources.reversed().forEach { runCatching { it.close() } }
        resources.clear()
    }

    companion object {
        const val SERVER_SHA256 = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae"
        private const val ASSET = "dex/scrcpy-server-v4.1"
        private const val REMOTE = "/data/local/tmp/com.headswap.desk-scrcpy-4.1.jar"
        private const val DESKTOP_HOME = "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher"
        private val DISPLAY_ID = Regex("New display: .*\\(id=(\\d+)\\)")
    }
}
