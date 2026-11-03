package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.Presentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import com.headswap.desk.dex.DexSetupActivity
import com.headswap.desk.dex.PhoneScreenIdleController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Observe rendered pixels on our own two windows, without an accessory or a power command. */
internal object PhoneCurtainProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        var activity: Activity? = null
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var presentation: Presentation? = null
        var controller: PhoneScreenIdleController? = null
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Emulator only" }
            val ctx = test.targetContext
            val phone = test.startActivitySync(Intent(ctx, DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = phone
            val capture = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
            reader = capture
            val manager = ctx.getSystemService(DisplayManager::class.java)
            val projected = checkNotNull(manager.createVirtualDisplay("HeadSwap isolated test output", 320, 180, 160, capture.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
            display = projected
            lateinit var picture: View
            main(test) {
                check(phone.display?.displayId == Display.DEFAULT_DISPLAY && projected.display.displayId != Display.DEFAULT_DISPLAY)
                presentation = Presentation(phone, projected.display).also {
                    it.window!!.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    picture = View(it.context).apply { setBackgroundColor(Color.RED) }
                    it.setContentView(picture)
                    it.show()
                }
                controller = PhoneScreenIdleController(phone, isEnabled = { false }, isConnected = { projected.display.isValid })
            }
            awaitColor(capture, Color.RED)
            test.waitForIdleSync()
            main(test) {
                check(phone.hasWindowFocus()) { "Phone fixture is not focused" }
                controller!!.onStart()
                check(controller!!.showNow(manualCurtain = true) && controller!!.isCurtainVisible)
                picture.setBackgroundColor(Color.GREEN)
            }
            awaitColor(capture, Color.GREEN)
            check(phonePixel(phone) == Color.BLACK) { "Phone window did not turn black" }
            main(test) {
                controller!!.dismiss()
                check(!controller!!.isCurtainVisible)
                picture.setBackgroundColor(Color.BLUE)
            }
            awaitColor(capture, Color.BLUE)
            check(phonePixel(phone) != Color.BLACK) { "Phone window did not restore" }
            result.putString("phone", "primary window black during curtain, restored after dismissal")
            result.putString("projection", "independent virtual display kept rendering RED, GREEN while dimmed, BLUE after restore")
        } catch (error: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(error))
        } finally {
            main(test) { controller?.onStop(); presentation?.dismiss(); activity?.finish() }
            display?.release()
            reader?.close()
            test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }

    private fun awaitColor(reader: ImageReader, expected: Int) {
        val until = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < until) {
            reader.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val i = image.height / 2 * plane.rowStride + image.width / 2 * plane.pixelStride
                fun channel(offset: Int) = plane.buffer[i + offset].toInt() and 255
                if (Color.rgb(channel(0), channel(1), channel(2)) == expected) return
            }
            SystemClock.sleep(25)
        }
        error("Missing expected color on isolated secondary display")
    }

    private fun phonePixel(activity: Activity): Int {
        val view = activity.window.decorView
        val x = view.width / 2; val y = view.height / 2
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(activity.window, Rect(x, y, x + 1, y + 1), bitmap,
            { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
        check(done.await(3, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS)
        return bitmap.getPixel(0, 0)
    }

    private fun main(test: Instrumentation, action: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { failure = runCatching(action).exceptionOrNull() }
        failure?.let { throw it }
    }
}
