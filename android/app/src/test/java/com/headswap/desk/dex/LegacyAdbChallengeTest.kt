package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LegacyAdbChallengeTest {
    @Test fun requiresRsaChallengeInsteadOfAnonymousOrTlsConnection() {
        reply(0x48545541, 1, 20) { LegacyAdbChallenge.requireAuthenticated(it) }
        for ((command, type, size) in listOf(Triple(0x4e584e43, 0x01000001, 0), Triple(0x534c5453, 0x01000000, 0),
            Triple(0x48545541, 2, 20), Triple(0x48545541, 1, 0))) {
            reply(command, type, size) { port ->
                assertThrows(IllegalStateException::class.java) { LegacyAdbChallenge.requireAuthenticated(port) }
            }
        }
    }
    @Test fun malformedMagicIsNotAcceptedAsAuthenticatedAdb() {
        reply(0x48545541, 1, 20, magic = 0) { port ->
            assertThrows(IllegalStateException::class.java) { LegacyAdbChallenge.requireAuthenticated(port) }
        }
    }
    @Test fun slowHeaderCannotExtendTheTotalHandshakeDeadline() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            val result = worker.submit {
                server.accept().use { peer ->
                    runCatching {
                        repeat(24) {
                            peer.getOutputStream().write(0)
                            Thread.sleep(50)
                        }
                    }
                }
            }
            val started = System.nanoTime()
            try {
                assertThrows(java.io.IOException::class.java) { LegacyAdbChallenge.requireAuthenticated(server.localPort, 180) }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 900)
                result.get(3, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }
    private fun reply(command: Int, type: Int, size: Int, magic: Int = command xor -1, action: (Int) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            val result = worker.submit {
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    val input = DataInputStream(peer.getInputStream())
                    val header = ByteArray(24).also(input::readFully)
                    val decoded = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals(0x4e584e43, decoded.int)
                    decoded.int; decoded.int
                    val count = decoded.int
                    assertTrue(count in 1..100)
                    val payload = ByteArray(count).also(input::readFully)
                    assertEquals("host::features=shell_v2\u0000", payload.toString(Charsets.US_ASCII))
                    peer.getOutputStream().write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(command).putInt(type).putInt(0).putInt(size).putInt(0).putInt(magic).array())
                    // The challenge check must not send any key or a shell request.
                    assertEquals(-1, input.read())
                }
            }
            try { action(server.localPort); result.get(5, TimeUnit.SECONDS) } finally { worker.shutdownNow() }
        }
    }
}
