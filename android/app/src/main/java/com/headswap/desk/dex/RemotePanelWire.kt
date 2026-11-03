package com.headswap.desk.dex

import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Private, single-activation loopback channel. No app-supplied paths, display commands or shell text. */
internal object RemotePanelWire {
    const val PREPARE = 1
    const val OFF = 2
    const val RESTORE = 3
    const val RENEW = 4
    const val LOCAL_IME = 5
    const val IME_VISIBLE = 6
    const val READ_GUIDANCE = 7
    const val GUIDANCE_REPLY = 16 // Framed separately from synchronous panel/IME acknowledgements.
    const val END_ACTIVATION = 8
    const val UNAVAILABLE = 2
    const val HIDDEN = 3
    const val OK = 0
    const val FAILED = 1
    const val NONCE_BYTES = 16
    const val MAC_BYTES = 32
    const val LEASE_MS = 4_000L
    val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun port(token: String): Int = 20000 + ((token.take(8).toLong(16) % 40000).toInt())
    fun nonce() = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
    fun proof(token: String, role: Int, pid: Int, client: ByteArray, server: ByteArray): ByteArray {
        val bytes = ByteBuffer.allocate(5 + 2 * NONCE_BYTES)
            .put(role.toByte()).putInt(pid).put(client).put(server).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal(bytes)
    }
    fun validProof(expected: ByteArray, received: ByteArray) = MessageDigest.isEqual(expected, received)
}
