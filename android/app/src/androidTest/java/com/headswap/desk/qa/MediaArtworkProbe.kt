package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import com.headswap.desk.MetaImages
import com.headswap.desk.CarLifeSession
import com.headswap.desk.Ch
import com.headswap.desk.CmdMsg
import com.headswap.desk.Msg
import com.headswap.desk.PbReader
import com.headswap.desk.SessionConfig
import com.headswap.desk.Wire
import com.headswap.desk.aa.AaBridgeCore
import com.headswap.desk.cluster.ClusterMediaSource
import com.headswap.desk.cluster.ClusterNowPlaying
import com.headswap.desk.link.CarTransport
import com.headswap.desk.link.TransportCaps
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real MediaSession -> bounded JPEG, metadata change and removal; no user audio or images. */
object MediaArtworkProbe {
    @JvmStatic fun run(probe: Instrumentation) {
        val result = Bundle()
        val ui = probe.uiAutomation
        var code = Activity.RESULT_CANCELED
        var source: ClusterMediaSource? = null
        var session: MediaSession? = null
        var bitmap: Bitmap? = null
        try {
            ui.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
            bitmap = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(width * height) { i -> 0xff000000.toInt() or ((i * 1103515245 + 12345) and 0xffffff) }
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
            val changes = LinkedBlockingQueue<ClusterNowPlaying>()
            source = ClusterMediaSource(probe.targetContext) { it?.let(changes::offer) }.also { it.start() }
            session = MediaSession(probe.targetContext, "HeadSwapArtworkProbe").apply {
                setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Fixture cover")
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap).build())
                setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                isActive = true
            }
            fun next(title: String): ClusterNowPlaying {
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (System.nanoTime() < end) {
                    val value = changes.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    if (value.title == title) return value
                }
                error("MediaSession update missing")
            }
            val withCover = next("Fixture cover")
            val art = withCover.artwork?.copyBytes() ?: error("Missing artwork")
            check(art.size in 1 until MetaImages.MAX_BYTES)
            check(art.contentEquals(MetaImages.albumJpeg(art))) { "Bounded JPEG was unnecessarily recompressed" }
            val decoded = BitmapFactory.decodeByteArray(art, 0, art.size) ?: error("Invalid JPEG")
            check(decoded.width <= 256 && decoded.height <= 256 && decoded.width == decoded.height * 2)
            result.putString("artwork", "JPEG ${art.size} bytes ${decoded.width}x${decoded.height}; sourceOwned=true")
            decoded.recycle()
            check(!bitmap.isRecycled)
            check(MetaImages.albumJpeg(byteArrayOf(1, 2, 3)) == null)
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Fixture no cover").build())
            val withoutCover = next("Fixture no cover")
            check(withoutCover.artwork == null)
            result.putBoolean("removalPropagated", true)
            for (caps in listOf(TransportCaps.USB_AOA, TransportCaps.TCP6))
                verifyWire(caps, withCover, withoutCover, art)
            result.putString("wire", "USB/TCP6 MEDIA_INFO: title, bounded JPEG and cover removal decoded at simulated HU")
            code = Activity.RESULT_OK
        } catch (failure: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(failure))
        } finally {
            source?.close()
            session?.release()
            // Metadata may retain the bitmap until its final callback finishes; source is caller-owned.
            ui.dropShellPermissionIdentity()
            probe.finish(code, result)
        }
    }

    /** Real session/core/serializer and image conversion; only the physical HU is substituted. */
    private fun verifyWire(transportCaps: TransportCaps, withCover: ClusterNowPlaying, withoutCover: ClusterNowPlaying, art: ByteArray) {
        val peer = object : CarTransport {
            override val caps = transportCaps
            override val label = "artwork fixture"
            override val rxBytes = 0L
            override val queuedVideoBytes = 0L
            override val queuedVideoFrames = 0
            override val drainBytesPerSec = 0.0
            val metadata = LinkedBlockingQueue<CmdMsg>()
            override fun queueSize(chan: Int) = 0
            override fun close() = Unit
            override fun start(sink: (Int, ByteArray) -> Unit, onWritten: (Int, ByteArray) -> Unit,
                               onClosed: (String) -> Unit) = Unit
            override fun send(chan: Int, inner: ByteArray) {
                if (chan == Ch.CMD) Wire.parseCmd(inner)?.takeIf { it.type == Msg.MEDIA_INFO }?.let(metadata::offer)
            }
        }
        val core = AaBridgeCore(log = { _, _ -> })
        val connection = CarLifeSession(peer, SessionConfig(), core, onClosed = {})
        try {
            connection.start()
            check(connection.outputConnected)
            core.onCarlifeStart()
            connection.onClusterMedia(withCover)
            val packet = peer.metadata.poll(3, TimeUnit.SECONDS) ?: error("${transportCaps.kind}: MEDIA_INFO absent")
            val fields = checkNotNull(PbReader.parse(packet.body))
            check(PbReader.str(fields, 2) == withCover.title)
            val transmitted = PbReader.all(fields, 5).single().data ?: error("JPEG missing from MEDIA_INFO")
            check(art.contentEquals(transmitted)) { "${transportCaps.kind}: cover changed during transport serialization" }
            connection.onClusterMedia(withoutCover)
            val cleared = peer.metadata.poll(3, TimeUnit.SECONDS) ?: error("${transportCaps.kind}: cover clear absent")
            val clearedFields = checkNotNull(PbReader.parse(cleared.body))
            check(PbReader.str(clearedFields, 2) == withoutCover.title)
            check(PbReader.all(clearedFields, 5).single().data?.isEmpty() == true)
        } finally {
            connection.close("artwork fixture finished")
            check(!connection.outputConnected)
        }
    }
}
