package com.headswap.desk.dex

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Requires RSA AUTH before opening the authenticated library connection; never sends a key. */
internal object LegacyAdbChallenge {
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "headswap-adb-challenge-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    fun requireAuthenticated(port: Int, timeoutMs: Int = 2000) {
        require(port in 1..65535 && timeoutMs in 1..5000)
        Socket().use { socket ->
            val expiry = deadlines.schedule({ runCatching { socket.close() } }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            try {
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            socket.soTimeout = timeoutMs
            val command = 0x4e584e43
            val payload = "host::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII)
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(0x01000001).putInt(1_048_576).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 255 }).putInt(command xor -1).array()
            socket.getOutputStream().apply { write(header); write(payload); flush() }
            val reply = ByteArray(24)
            DataInputStream(socket.getInputStream()).readFully(reply)
            val decoded = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
            val response = decoded.int
            val type = decoded.int
            decoded.int
            val length = decoded.int
            decoded.int
            val magic = decoded.int
            check(response == 0x48545541 && type == 1 && length == 20 && magic == (response xor -1)) {
                "Сетевая отладка не подтвердила проверку ключа. Режим не применён."
            }
            } finally { expiry.cancel(false) }
        }
    }
}
