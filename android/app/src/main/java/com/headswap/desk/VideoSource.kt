package com.headswap.desk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface

/**
 * H.264-энкодер (Surface input) и рисовалка заглушки моста (текст, пока AA не подключился).
 * Кадры отдаются в Annex-B с 4-байтными стартовыми кодами; codec config приходит отдельно (onConfig).
 */
class VideoSource(
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val listener: Listener,
    /** Видимая часть кадра (SPS-кроп, h529): надпись рисуется только в ней, вне её фон. null = весь кадр. */
    private val area: Rect? = null,
    /** Энкодер в формате Baidu (Mobis CCNC, h242): VBR, level 256, повтор кадра 100 мс. */
    private val baidu: Boolean = false,
    /** Shared read-only image supplied by the application; this encoder never recycles it. */
    private val brandArtwork: Bitmap? = null,
) {
    interface Listener {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, key: Boolean)
        fun onError(msg: String, t: Throwable?)
    }

    @Volatile var framesRendered = 0L
        private set

    /** Текст состояния под изображением приложения вместо ещё не готового рабочего стола. */
    @Volatile var banner: String? = null

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null
    private var renderThread: Thread? = null
    var encoderName: String = "?"
        private set

    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    @Synchronized
    fun start() {
        if (running) return
        val c = MediaCodec.createEncoderByType(MIME)
        encoderName = c.name
        var s: Surface? = null
        try {
            if (baidu) {
                // формат Baidu (h242): с level 256, при отказе без него, дальше обычная лесенка
                var done = false
                for (withLevel in listOf(true, false)) {
                    try {
                        c.configure(formatBaidu(withLevel), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        AppLog.i("заглушка: энкодер в формате Baidu ($width x $height ${fps}fps ${bitrate / 1000}kbps VBR${if (withLevel) ", level 256" else ", без level"})")
                        done = true
                        break
                    } catch (e: Exception) {
                        AppLog.e("configure в формате Baidu (${if (withLevel) "с level 256" else "без level"}) не прошёл", e)
                        c.reset()
                    }
                }
                if (!done) configureFallback(c)
            } else {
                configureFallback(c)
            }
            s = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            // codec ещё null: stop() вышел бы сразу, аппаратный энкодер освобождаем здесь
            try {
                s?.release()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw e
        }
        surface = s
        codec = c
        running = true
        drainThread = Thread({ drainLoop(c) }, "video-enc-drain").also { it.start() }
        renderThread = Thread({ renderLoop() }, "video-render").also { it.start() }
    }

    private fun configureFallback(c: MediaCodec) {
        try {
            c.configure(format(withExtras = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            AppLog.e("configure с profile/CBR не прошёл, пробую минимальный формат", e)
            c.reset()
            c.configure(format(withExtras = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    /** Как Baidu MediaFormatUtils :41-60: Baseline, VBR, level 256 (AVCLevel3), I-frame 1 с, повтор кадра 100 мс. */
    private fun formatBaidu(withLevel: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (withLevel) f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3)
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        return f
    }

    private fun format(withExtras: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (withExtras) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        return f
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            AppLog.e("requestKeyFrame", e)
        }
    }

    /** Битрейт на лету (воздух: политика битрейта ведёт и заглушку). false = кодека нет или параметр не принят. */
    fun setBitrate(bps: Int): Boolean {
        val c = codec ?: return false
        return try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
            true
        } catch (e: Exception) {
            AppLog.e("битрейт заглушки", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running && codec == null) return
        running = false
        renderThread?.join(1000)
        drainThread?.join(1000)
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        codec = null
        surface = null
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 50_000)
                when {
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val arr = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(arr)
                            val data = AnnexB.normalize(arr)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                listener.onConfig(data)
                            } else {
                                listener.onFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                        c.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        AppLog.i("энкодер: формат вывода $encoderName: ${c.outputFormat}", ui = false)
                }
            }
        } catch (e: Exception) {
            if (running) listener.onError("drain энкодера", e)
        }
    }

    private fun renderLoop() {
        val s = surface ?: return
        val frameMs = 1000L / fps
        var next = SystemClock.elapsedRealtime()
        try {
            while (running) {
                // программный Canvas: lockHardwareCanvas на Surface энкодера валит media.codec на Xiaomi с vndk v30
                // (Redmi 9T/9S, POCO X3 Pro: SIGSEGV в FenceTime::Snapshot::getFlattenedSize, за ним abort нашего процесса)
                val canvas = s.lockCanvas(null)
                try {
                    draw(canvas)
                } finally {
                    s.unlockCanvasAndPost(canvas)
                }
                framesRendered++
                next += frameMs
                val d = next - SystemClock.elapsedRealtime()
                if (d > 0) Thread.sleep(d) else next = SystemClock.elapsedRealtime()
            }
        } catch (e: Exception) {
            if (running) listener.onError("рендер на Surface энкодера", e)
        }
    }

    /** Текущий кадр заглушки в Bitmap: с него транскодер начинает зум-растворение в картинку AA. Любая нить. */
    fun snapshot(): Bitmap? {
        val msg = banner ?: return null
        val w = area?.width() ?: width
        val h = area?.height() ?: height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawBanner(Canvas(bmp), w, h, msg, Paint(text), Paint(image))
        return bmp
    }

    private fun drawBanner(c: Canvas, msg: String) {
        val a = area ?: return drawBanner(c, width, height, msg, text, image)
        c.drawColor(BG)
        c.save()
        c.translate(a.left.toFloat(), a.top.toFloat())
        c.clipRect(0, 0, a.width(), a.height())
        drawBanner(c, a.width(), a.height(), msg, text, image)
        c.restore()
    }

    private fun drawBanner(c: Canvas, width: Int, height: Int, msg: String, text: Paint, image: Paint) {
        val w = width.toFloat()
        val h = height.toFloat()
        // плоские цвета: на CBR 4 Мбит/с градиенты слабого энкодера идут полосами
        c.drawColor(BG)
        brandArtwork?.let { artwork ->
            val side = minOf(h * 0.52f, w * 0.48f)
            val scale = side / maxOf(artwork.width, artwork.height)
            val aw = artwork.width * scale
            val ah = artwork.height * scale
            c.drawBitmap(artwork, null, RectF((w - aw) / 2, h * 0.32f - ah / 2,
                (w + aw) / 2, h * 0.32f + ah / 2), image)
        }

        text.textAlign = Paint.Align.CENTER
        text.typeface = WORDMARK
        text.textSize = h * 0.075f
        val title = "HeadSwap Desktop"
        if (text.measureText(title) > w * 0.88f) text.textSize *= w * 0.88f / text.measureText(title)
        text.color = Color.WHITE
        c.drawText(title, w / 2, h * 0.66f, text)

        // статус моста: по нему видно, чего ждём (сервер ГУ, разблокировка, кадр)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textAlign = Paint.Align.CENTER
        text.color = STATUS
        text.textSize = h * 0.052f
        var lines = wrapBalanced(msg, w * 0.88f, text)
        while (lines.size * text.textSize * 1.25f > h * 0.23f && text.textSize > h * 0.032f) {
            text.textSize *= 0.9f
            lines = wrapBalanced(msg, w * 0.88f, text)
        }
        val lh = text.textSize * 1.25f
        var y = h * 0.73f + (h * 0.23f - lines.size * lh) / 2 - text.fontMetrics.ascent
        for (l in lines) {
            c.drawText(l, w / 2, y, text)
            y += lh
        }
    }

    /**
     * Перенос по словам на минимум строк, но строки примерно равной ширины:
     * на 4:3 «...перезапустите сервер ГУ» иначе оставляет «ГУ» одно на второй строке.
     */
    private fun wrapBalanced(msg: String, maxW: Float, p: Paint): List<String> {
        val words = msg.split(' ')
        fun greedy(limit: Float): List<String> {
            val lines = ArrayList<String>()
            var cur = ""
            for (word in words) {
                val t = if (cur.isEmpty()) word else "$cur $word"
                if (p.measureText(t) > limit && cur.isNotEmpty()) {
                    lines.add(cur)
                    cur = word
                } else {
                    cur = t
                }
            }
            if (cur.isNotEmpty()) lines.add(cur)
            return lines
        }
        val base = greedy(maxW)
        if (base.size < 2) return base
        // сужаем лимит, пока число строк не растёт: последняя строка добирает слова у предыдущих
        var best = base
        var limit = p.measureText(msg) / base.size
        while (limit < maxW) {
            val l = greedy(limit)
            if (l.size == base.size) {
                best = l
                break
            }
            limit += maxW * 0.02f
        }
        return best
    }

    private fun draw(c: Canvas) {
        drawBanner(c, banner ?: "")
    }

    companion object {
        const val MIME = "video/avc"

        private val BG = Color.rgb(0x0E, 0x14, 0x1A) // фон иконки
        private val STATUS = Color.rgb(0xB8, 0xC4, 0xCC)
        private val WORDMARK: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    }
}
