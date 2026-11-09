package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.Presentation
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.View
import com.headswap.desk.aa.AaTranscoder
import com.headswap.desk.aa.TranscodeOutput
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real EGL -> production native GL relay -> MediaCodec, plus the platform VD producer clock. */
internal object SurfaceTimingProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        var encoder: AaTranscoder? = null
        var producer: Producer? = null
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            val output = LinkedBlockingQueue<Long>()
            val error = AtomicReference<String?>()
            val target = object : TranscodeOutput {
                override fun onEncoderConfig(csd: ByteArray) = Unit
                override fun onEncoderFrame(data: ByteArray, key: Boolean) = Unit
                override fun onEncoderFrameAt(data: ByteArray, key: Boolean, presentationUs: Long) { output.offer(presentationUs) }
                override fun onTranscodeError(msg: String) { error.set(msg) }
                override fun onNeedAaKeyFrame() = Unit
            }
            // A one-second input gate makes the pending-fresh-frame case observable even on
            // a slow emulator. Automatic codec repeats are postponed; GL idle redraws stay active.
            val codec = AaTranscoder(640, 480, 1, 500_000, target, tuning = "repeat=10000000",
                srcWidth = 640, srcHeight = 480, directSurface = true)
            encoder = codec
            codec.start()
            val egl = Producer(checkNotNull(codec.surfaceInput).surface)
            producer = egl
            val firstSentAt = System.nanoTime()
            val firstCapture = firstSentAt - 200_000_000L
            egl.draw(firstCapture, 0.8f, 0.1f, 0.1f)
            val first = take(output, error)
            check(kotlin.math.abs(first - firstCapture / 1000) <= 1) {
                "Fresh frame was restamped: source=${firstCapture / 1000}, encoder=$first"
            }
            check(System.nanoTime() - firstSentAt < 900_000_000L) {
                "Fixture encoder startup exceeded gate window; gated-frame result is inconclusive"
            }
            val secondCapture = System.nanoTime() - 100_000_000L
            egl.draw(secondCapture, 0.1f, 0.8f, 0.1f)
            val second = take(output, error)
            check(kotlin.math.abs(second - secondCapture / 1000) <= 1) {
                "Pending gated frame lost its source PTS: source=${secondCapture / 1000}, encoder=$second"
            }
            val repeated = take(output, error)
            check(first < second && second < repeated) { "Encoder timestamps are not strictly monotonic" }
            check(repeated - second > 500_000) { "Idle redraw reused stale capture time" }
            result.putString("eglToCodec", "PASS capture delay=200ms; pending gated source PTS preserved; idle repeat monotonic")
            result.putLong("firstCaptureUs", first)
            result.putLong("gatedCaptureUs", second)
            result.putLong("idleRepeatUs", repeated)
            egl.close(); producer = null
            codec.stop(); encoder = null
            result.putString("virtualDisplayClock", verifyVirtualDisplayClock(test))
        } catch (failure: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(failure))
        } finally {
            runCatching { producer?.close() }
            runCatching { encoder?.stop() }
            test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }

    private fun take(queue: LinkedBlockingQueue<Long>, error: AtomicReference<String?>): Long =
        checkNotNull(queue.poll(5, TimeUnit.SECONDS)) { error.get() ?: "No encoded frame in five seconds" }

    private fun verifyVirtualDisplayClock(test: Instrumentation): String {
        val context = test.targetContext
        val images = ImageReader.newInstance(320, 192, PixelFormat.RGBA_8888, 3)
        var presentation: Presentation? = null
        val display = checkNotNull(context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "HeadSwap source clock probe", 320, 192, 160, images.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
        try {
            test.runOnMainSync {
                presentation = Presentation(context, display.display).also {
                    it.setContentView(View(it.context).apply { setBackgroundColor(Color.GREEN) })
                    it.show()
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                images.acquireLatestImage()?.use { frame ->
                    val now = System.nanoTime()
                    check(frame.timestamp > 0 && frame.timestamp <= now + 20_000_000L && now - frame.timestamp < 2_000_000_000L) {
                        "Virtual display source is outside MONOTONIC clock: deltaNs=${now - frame.timestamp}"
                    }
                    return "PASS private VD producer timestamp within MONOTONIC; ageMs=${(now - frame.timestamp) / 1_000_000}"
                }
                SystemClock.sleep(20)
            }
            error("No virtual-display frame")
        } finally {
            test.runOnMainSync { presentation?.dismiss() }
            display.release()
            images.close()
        }
    }

    private class Producer(surface: Surface) : AutoCloseable {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val window: android.opengl.EGLSurface
        init {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, window, window, context))
        }
        fun draw(presentationNs: Long, red: Float, green: Float, blue: Float) {
            GLES20.glViewport(0, 0, 640, 480)
            GLES20.glClearColor(red, green, blue, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            check(EGLExt.eglPresentationTimeANDROID(display, window, presentationNs))
            check(EGL14.eglSwapBuffers(display, window))
        }
        override fun close() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
