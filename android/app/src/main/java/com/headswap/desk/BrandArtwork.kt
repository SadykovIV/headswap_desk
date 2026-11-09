package com.headswap.desk

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Shared, bounded software bitmap. Callers must not modify or recycle it. No Context is retained. */
object BrandArtwork {
    private const val MAX_SIDE = 512
    @Volatile private var cached: Bitmap? = null

    @JvmStatic
    fun bitmap(resources: Resources): Bitmap = cached ?: synchronized(this) {
        cached ?: decode(resources).also { cached = it }
    }

    private fun decode(resources: Resources): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(resources, R.drawable.headswap_brand, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Brand artwork cannot be decoded" }
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = 1
            inPreferredConfig = Bitmap.Config.ARGB_8888
            while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize * 2) >= MAX_SIDE) {
                inSampleSize *= 2
            }
        }
        val decoded = checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.headswap_brand, options))
        val side = maxOf(decoded.width, decoded.height)
        if (side <= MAX_SIDE) return decoded
        val ratio = MAX_SIDE.toFloat() / side
        return try {
            Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1), true)
        } finally { decoded.recycle() }
    }
}
