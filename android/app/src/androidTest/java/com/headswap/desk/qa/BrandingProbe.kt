package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import com.headswap.desk.BrandArtwork
import com.headswap.desk.VideoSource
import java.io.File

/** Render the actual supplied resource through the same snapshot path used by the service. */
internal object BrandingProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        try {
            val context = test.targetContext
            val artwork = BrandArtwork.bitmap(context.resources)
            check(artwork === BrandArtwork.bitmap(context.resources)) { "Artwork was decoded twice" }
            check(maxOf(artwork.width, artwork.height) <= 512 && !artwork.isRecycled)
            val listener = object : VideoSource.Listener {
                override fun onConfig(csd: ByteArray) = Unit
                override fun onFrame(data: ByteArray, key: Boolean) = Unit
                override fun onError(msg: String, t: Throwable?) = error(msg)
            }
            for ((width, height) in listOf(1280 to 720, 1024 to 768, 640 to 480)) {
                val source = VideoSource(width, height, 15, 4_000_000, listener, brandArtwork = artwork)
                source.banner = "Подключение к рабочему столу. Разблокируйте телефон и дождитесь изображения."
                val first = checkNotNull(source.snapshot())
                val second = checkNotNull(source.snapshot())
                try {
                    check(first.width == width && first.height == height && first.sameAs(second))
                    var blue = 0; var green = 0; var title = 0; var status = 0
                    for (y in 0 until height step 2) for (x in 0 until width step 2) {
                        val color = first.getPixel(x, y)
                        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
                        if (y < height * .58) {
                            if (b > 150 && b > r * 1.5) blue++
                            if (g > 150 && g > r * 1.5 && g > b * 1.5) green++
                        }
                        if (r > 170 && g > 170 && b > 170) {
                            if (y in (height * .58).toInt()..(height * .68).toInt()) title++
                            if (y > height * .73) status++
                        }
                    }
                    check(blue > 100 && green > 50 && title > 40 && status > 40) {
                        "Missing branded content $width x $height: blue=$blue green=$green title=$title status=$status"
                    }
                    File(context.filesDir, "brand-placeholder-${width}x$height.png").outputStream().use {
                        check(first.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    result.putString("placeholder_${width}x$height", "PASS image/title/status; deterministic snapshot")
                } finally { first.recycle(); second.recycle() }
            }
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).let { icon ->
                try {
                    val drawable = context.packageManager.getApplicationIcon(context.packageName)
                    drawable.setBounds(0, 0, icon.width, icon.height)
                    drawable.draw(Canvas(icon))
                    File(context.filesDir, "brand-adaptive-icon.png").outputStream().use {
                        check(icon.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { icon.recycle() }
            }
            check(!artwork.isRecycled)
            result.putString("artwork", "cached ${artwork.width}x${artwork.height}; resource retained")
        } catch (error: Throwable) { result.putString("error", android.util.Log.getStackTraceString(error)) }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }
}
