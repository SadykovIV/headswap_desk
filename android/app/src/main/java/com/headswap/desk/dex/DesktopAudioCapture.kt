package com.headswap.desk.dex

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.headswap.desk.AppLog
import com.headswap.desk.aa.AaBridgeCore
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

object DesktopAudioAccess {
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(DesktopAudioPolicy.PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(DesktopAudioPolicy.ROUTING_PERMISSION) == PackageManager.PERMISSION_GRANTED
}

object DesktopAudioState {
    @Volatile var status = "Звук по USB выключен"
    @Volatile var running = false
    @Volatile var error: String? = null
    @Volatile var pcmBytes = 0L
    @Volatile var nonSilentBuffers = 0L
}

/** Main-process owner. PCM exists only in memory; the existing CarLife queue enforces backpressure. */
class DesktopAudioCapture(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var core: AaBridgeCore? = null
    private var usb = false
    private var canCaptureOutput: (() -> Boolean)? = null
    private var closed = false
    private var failed = false
    private var worker: Capture? = null
    private val sessions = DesktopAudioSessionGate()
    private var generation = 0L
    private class Capture(val core: AaBridgeCore, val generation: Long) {
        val cancelled = AtomicBoolean()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            reconcile()
            main.postDelayed(this, 250)
        }
    }

    /** Called only for a live transport, never for the desktop's disconnected grace period. */
    fun attach(value: AaBridgeCore, isUsb: Boolean, canCapture: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        detach()
        generation = sessions.next()
        core = value
        usb = isUsb
        canCaptureOutput = canCapture
        failed = false
        main.removeCallbacks(tick)
        tick.run()
    }

    fun detach() {
        check(Looper.myLooper() == Looper.getMainLooper())
        main.removeCallbacks(tick)
        worker?.cancelled?.set(true)
        sessions.next() // old callbacks must not PAUSE/STOP a newly attached CarLife link
        core?.usbAudioEnabled = false
        core = null
        canCaptureOutput = null
        usb = false
        DesktopAudioState.running = false
        DesktopAudioState.status = if (DexPrefs(context).usbPlaybackAudio) "Звук: ожидаю подключение CarLife по USB" else "Звук по USB выключен"
    }

    fun close() { detach(); closed = true }

    private fun reconcile() {
        val current = core ?: return
        val prefs = DexPrefs(context)
        val enabled = prefs.usbPlaybackAudio
        val delegated = prefs.useDelegated
        val ready = DelegatedDesktopAccess.isReady(context)
        val permission = DesktopAudioAccess.hasPermission(context)
        // Enable the CarLife sink only after an explicit setting + scoped activation.
        current.usbAudioEnabled = enabled && delegated && ready && permission && usb
        val outputFresh = runCatching { canCaptureOutput?.invoke() == true }.getOrDefault(false)
        val permitted = outputFresh && DesktopAudioPolicy.mayCapture(enabled, delegated, ready, permission,
            usb, current.isCarlifeStarted && current.hasCarlifeLink, current.audioViaUsb)
        if (!permitted) {
            if (!outputFresh && worker?.cancelled?.compareAndSet(false, true) == true)
                AppLog.i("DESKTOP AUDIO: USB write lease expired; releasing playback capture", false)
            worker?.cancelled?.set(true)
            failed = false // a user setting/access/session transition allows a fresh attempt
            DesktopAudioState.running = false
            DesktopAudioState.status = when {
                !enabled -> "Звук по USB выключен"
                !delegated -> "Звук по USB доступен в режиме «Доступ с компьютера»"
                !ready -> "Для звука активируйте доступ HeadSwap"
                !permission -> "Для звука повторно активируйте доступ с параметром --audio"
                !usb -> "Звук: ожидаю подключение CarLife по USB"
                !current.isCarlifeStarted -> "Звук: ожидаю запуск CarLife"
                !current.audioViaUsb -> "Магнитола запросила звук по Bluetooth"
                !outputFresh -> "Звук приостановлен: магнитола не принимает USB"
                else -> "Звук: соединение закрыто"
            }
        } else if (worker == null && !failed) {
            val capture = Capture(current, generation)
            worker = capture
            DesktopAudioState.error = null
            DesktopAudioState.pcmBytes = 0
            DesktopAudioState.nonSilentBuffers = 0
            DesktopAudioState.status = "Подключаю звук по USB…"
            Thread({ runCapture(capture) }, "headswap-playback-audio").start()
        }
    }

    private fun updateSignalStatus(capture: Capture, hasSignal: Boolean) {
        main.post {
            if (worker === capture && !capture.cancelled.get()) {
                DesktopAudioState.status = if (hasSignal) "Звук передаётся по USB"
                    else "USB-звук подключён. Ожидаю музыку или подсказку"
            }
        }
    }

    private fun runCapture(capture: Capture) {
        var mix: RegisteredPlaybackMix? = null
        var started = false
        var failure: Throwable? = null
        try {
            // AudioService propagates new delegated grants to its native permission cache asynchronously.
            for (attempt in 0..2) {
                if (capture.cancelled.get()) return
                try { mix = RegisteredPlaybackMix.open(context); break }
                catch (error: Exception) { if (attempt == 2) throw error; Thread.sleep(150) }
            }
            val audio = checkNotNull(mix).record
            if (capture.cancelled.get()) return
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Android не запустил передачу звука" }
            started = true
            sessions.runIfCurrent(capture.generation) {
                if (!capture.cancelled.get()) capture.core.onAudioStart(DesktopAudioPolicy.MEDIA_CHANNEL)
            }
            main.post { if (worker === capture && !capture.cancelled.get()) {
                DesktopAudioState.running = true
                DesktopAudioState.status = "USB-звук подключён. Ожидаю музыку или подсказку"
            } }
            AppLog.i("DESKTOP AUDIO: registered AudioMix LOOP_BACK media/navigation; PCM 48000 Hz stereo16 started")
            var nextReport = SystemClock.elapsedRealtime() + 10_000L
            var intervalPeak = 0
            var reportedSignal = false
            var intervalHadSignal = false
            val input = ByteArray(DesktopAudioPolicy.READ_BYTES)
            val frames = DesktopPcmFrames()
            while (!capture.cancelled.get()) {
                val count = audio.read(input, 0, input.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Ошибка чтения звука Android: $count" }
                val now = SystemClock.elapsedRealtime()
                if (now >= nextReport) {
                    AppLog.i("DESKTOP AUDIO: PCM bytes=${DesktopAudioState.pcmBytes} " +
                        "non-silent buffers=${DesktopAudioState.nonSilentBuffers} peak10s=$intervalPeak", false)
                    if (intervalPeak == 0 && intervalHadSignal) updateSignalStatus(capture, hasSignal = false)
                    intervalHadSignal = intervalPeak > 0
                    intervalPeak = 0
                    nextReport = now + 10_000L
                }
                if (count == 0) { Thread.sleep(5); continue }
                val pcm = frames.append(input, count)
                if (pcm.isEmpty() || capture.cancelled.get()) continue
                DesktopAudioState.pcmBytes += pcm.size
                val peak = DesktopPcmFrames.peak(pcm)
                if (peak > 0) DesktopAudioState.nonSilentBuffers++
                if (peak > 0 && !reportedSignal) {
                    reportedSignal = true
                    AppLog.i("DESKTOP AUDIO: first non-silent PCM peak=$peak", false)
                }
                if (peak > 0 && !intervalHadSignal) {
                    intervalHadSignal = true
                    updateSignalStatus(capture, hasSignal = true)
                }
                intervalPeak = maxOf(intervalPeak, peak)
                sessions.runIfCurrent(capture.generation) {
                    if (!capture.cancelled.get()) capture.core.onAudioData(DesktopAudioPolicy.MEDIA_CHANNEL, pcm,
                        SystemClock.elapsedRealtimeNanos() / 1000)
                }
            }
        } catch (error: Exception) {
            if (!capture.cancelled.get()) {
                failure = (error as? InvocationTargetException)?.targetException ?: error
                AppLog.e("DESKTOP AUDIO: playback unavailable; desktop remains active", failure)
            }
        } finally {
            // Stop rerouting output before notifying CarLife; never retain capture during USB grace.
            if (started) runCatching { mix?.record?.stop() }
            runCatching { mix?.close() }.onFailure { AppLog.e("DESKTOP AUDIO: unable to release playback route", it) }
            if (started) runCatching { sessions.runIfCurrent(capture.generation) {
                capture.core.onAudioStop(DesktopAudioPolicy.MEDIA_CHANNEL)
            } }
            val error = failure
            main.post {
                if (worker === capture) {
                    worker = null
                    DesktopAudioState.running = false
                    if (error != null && core === capture.core && !capture.cancelled.get()) {
                        failed = true
                        DesktopAudioState.error = "${error.javaClass.simpleName}: ${error.message}"
                        DesktopAudioState.status = "Android не открыл USB-звук. Повторите активацию с включённым звуком и подключитесь снова."
                    }
                }
            }
            AppLog.i("DESKTOP AUDIO: stopped; PCM bytes=${DesktopAudioState.pcmBytes}, non-silent buffers=${DesktopAudioState.nonSilentBuffers}", false)
        }
    }
}
