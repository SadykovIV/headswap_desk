package com.headswap.desk.dex

/** Playback only. The microphone and call recording sources are never selectable. */
object DesktopAudioPolicy {
    const val PERMISSION = "android.permission.CAPTURE_AUDIO_OUTPUT"
    const val ROUTING_PERMISSION = "android.permission.MODIFY_AUDIO_ROUTING"
    const val ACTIVATION_ARGUMENT = "headswap_audio"
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = 4 // stereo PCM16 LE
    const val READ_BYTES = 3840 // 20 ms; no unbounded PCM queue in the app
    const val MEDIA_CHANNEL = 6 // existing AA sink -> CarLife MEDIA channel 3

    fun requestFromArgument(value: String?): Boolean = value == "true"

    fun mayCapture(enabled: Boolean, delegated: Boolean, accessReady: Boolean, permission: Boolean,
        usb: Boolean, carlifeStarted: Boolean, routeAllowed: Boolean): Boolean =
        enabled && delegated && accessReady && permission && usb && carlifeStarted && routeAllowed
}

/** Preserve incomplete PCM frames instead of truncating/offsetting stereo on a short read. */
internal class DesktopPcmFrames {
    companion object {
        /** Sample magnitude in signed little-endian PCM16; no sample data is retained in logs. */
        fun peak(pcm: ByteArray): Int {
            var max = 0
            for (i in 0 until pcm.size - 1 step 2) {
                val sample = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
                max = maxOf(max, kotlin.math.abs(sample))
            }
            return max
        }
    }

    private val tail = ByteArray(DesktopAudioPolicy.BYTES_PER_FRAME)
    private var tailBytes = 0

    fun append(input: ByteArray, length: Int): ByteArray {
        require(length in 0..input.size)
        val total = tailBytes + length
        val complete = total - total % DesktopAudioPolicy.BYTES_PER_FRAME
        val joined = ByteArray(total)
        tail.copyInto(joined, endIndex = tailBytes)
        input.copyInto(joined, destinationOffset = tailBytes, endIndex = length)
        tailBytes = total - complete
        joined.copyInto(tail, startIndex = complete)
        return joined.copyOf(complete)
    }
}

/** Serializes a transport replacement with the last PCM/STOP of its previous capture. */
internal class DesktopAudioSessionGate {
    private var generation = 0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun runIfCurrent(token: Long, action: () -> Unit): Boolean {
        if (token != generation) return false
        action()
        return true
    }
}
