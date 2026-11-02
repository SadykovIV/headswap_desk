package com.headswap.desk.dex

import com.headswap.desk.aa.TouchPoint
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.IOException

/** Wire layout from the official scrcpy v4.1 Streamer and ControlMessageReader. */
internal object ScrcpyProtocol {
    const val VERSION = "4.1"
    const val MAX_PACKET_BYTES = 4 * 1024 * 1024
    sealed class Packet {
        data class Session(val width: Int, val height: Int) : Packet()
        data class Video(val bytes: ByteArray, val ptsUs: Long, val config: Boolean, val key: Boolean) : Packet()
    }

    class VideoReader(input: InputStream) {
        private val source = DataInputStream(input)
        fun readCodec() {
            if (source.readInt() != 0x68323634) throw IOException("scrcpy: ожидается поток H.264")
        }
        fun next(): Packet {
            val first = source.readInt()
            val second = source.readInt()
            val third = source.readInt()
            if (first < 0) {
                if (second !in 1..8192 || third !in 1..8192) throw IOException("scrcpy: неверный размер видеосессии")
                return Packet.Session(second, third)
            }
            if (third !in 1..MAX_PACKET_BYTES) throw IOException("scrcpy: неверный размер видеопакета: $third")
            val flagsAndPts = (first.toLong() shl 32) or (second.toLong() and 0xffffffffL)
            val bytes = ByteArray(third)
            source.readFully(bytes)
            return Packet.Video(bytes, flagsAndPts and ((1L shl 61) - 1),
                flagsAndPts and (1L shl 62) != 0L, flagsAndPts and (1L shl 61) != 0L)
        }
    }

    fun touch(action: Int, id: Int, x: Int, y: Int, width: Int, height: Int): ByteArray {
        require(action in 0..3 && id >= 0 && width in 1..65535 && height in 1..65535)
        return packet {
            writeByte(2)
            writeByte(action)
            writeLong(id.toLong())
            writeInt(x.coerceIn(0, width - 1))
            writeInt(y.coerceIn(0, height - 1))
            writeShort(width)
            writeShort(height)
            writeShort(if (action == 1 || action == 3) 0 else 0xffff)
            writeInt(0) // touchscreen: actionButton
            writeInt(0) // touchscreen: buttons
        }
    }

    fun key(action: Int, keycode: Int): ByteArray {
        require(action in 0..1 && keycode in 0..1000)
        return packet { writeByte(0); writeByte(action); writeInt(keycode); writeInt(0); writeInt(0) }
    }

    private fun packet(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(32)
        DataOutputStream(bytes).use(block)
        return bytes.toByteArray()
    }
}

/** scrcpy maintains pointer indices itself; POINTER_DOWN/UP must be sent as DOWN/UP. */
internal class DexTouchMapper(private val width: Int, private val height: Int) {
    private val active = linkedMapOf<Int, TouchPoint>()

    @Synchronized
    fun encode(action: Int, actionIndex: Int, points: List<TouchPoint>): List<ByteArray> {
        if (points.size > 10 || points.any { it.id < 0 } || points.map { it.id }.distinct().size != points.size) return emptyList()
        val out = mutableListOf<ByteArray>()
        fun send(a: Int, p: TouchPoint) { out += ScrcpyProtocol.touch(a, p.id, p.x, p.y, width, height) }
        when (action) {
            0 -> {
                active.values.forEach { send(1, it) }
                active.clear()
                points.firstOrNull()?.let { active[it.id] = it; send(0, it) }
            }
            1 -> {
                points.forEach { if (it.id in active) active[it.id] = it }
                active.values.forEach { send(1, it) }
                active.clear()
            }
            3 -> {
                points.forEach { if (it.id in active) active[it.id] = it }
                // Cancel the Android gesture before releasing scrcpy's pointer bookkeeping.
                // Its Controller only removes pointers on UP, not on CANCEL (v4.1).
                active.values.firstOrNull()?.let { send(3, it) }
                active.values.forEach { send(1, it) }
                active.clear()
            }
            2 -> points.forEach { p ->
                if (p.id in active) { active[p.id] = p; send(2, p) }
            }
            5 -> points.getOrNull(actionIndex)?.let { p ->
                if (p.id !in active) { active[p.id] = p; send(0, p) }
            }
            6 -> points.getOrNull(actionIndex)?.let { p ->
                if (active.remove(p.id) != null) send(1, p)
            }
        }
        return out
    }
}
