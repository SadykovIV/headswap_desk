package com.headswap.desk.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Сертификаты берутся из app/src/main/assets (рабочий каталог unit-тестов - app/).
 * В публичном клоне ключей AA нет: тест, который их открывает, пропускается (Assume).
 */
object TestAssets {
    val opener: (String) -> InputStream = { path ->
        val f = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path")).firstOrNull { it.isFile }
        Assume.assumeTrue("AA certs missing, see README", f != null)
        FileInputStream(f!!)
    }
}

class AapTlsTest {
    @Test
    fun certificatesLoad() {
        for (set in AapCerts.SETS) {
            val c = AapCerts.load(TestAssets.opener, set)
            assertEquals(set, c.certSet)
            assertEquals("RSA", c.key.algorithm)
            println("[$set] subject=${c.chain[0].subjectX500Principal} notBefore=${c.chain[0].notBefore} notAfter=${c.chain[0].notAfter}")
        }
    }

    @Test
    fun jvcLoadsWithoutFallback() {
        // странный notBefore (UTCTime с -0700): если JVM-парсер его не съест, этот тест это покажет
        val l = AapCerts.loadWithFallback(TestAssets.opener, "jvc")
        assertEquals("откат на hur: ${l.error}", "jvc", l.creds.certSet)
        assertEquals(null, l.error)
    }

    @Test
    fun pkcs1KeyIsWrappedToPkcs8() {
        val key = AapCerts.parsePrivateKey(String(TestAssets.opener("aa/jvc/key.pem").readBytes()))
        assertEquals("PKCS#8", key.format)
    }

    private fun check(set: String, tls12: Boolean) {
        // AapTlsSelfTest.run глотает любые Throwable (в т.ч. пропуск теста), поэтому проверяем наличие заранее
        TestAssets.opener("aa/$set/cert.pem").close()
        val r = AapTlsSelfTest.run(TestAssets.opener, set, tls12)
        println("TLS selftest set=$set tls12=$tls12 -> ${r.ok} ${r.detail}")
        assertTrue("set=$set tls12=$tls12: ${r.detail}", r.ok)
        if (tls12) assertTrue(r.detail, r.detail.startsWith("TLSv1.2"))
    }

    @Test fun handshakeJvcAuto() = check("jvc", false)
    @Test fun handshakeHurAuto() = check("hur", false)
    @Test fun handshakeJvcTls12() = check("jvc", true)
    @Test fun handshakeHurTls12() = check("hur", true)

    @Test
    fun clientCertificateReachesServer() {
        // клиент jvc, сервер hur: сервер требует сертификат клиента; handshake проходит только если он принят
        val client = AapTls(AapCerts.load(TestAssets.opener, "jvc"))
        val server = AapTls(AapCerts.load(TestAssets.opener, "hur"), serverMode = true)
        var toServer = client.handshakeStep()
        repeat(6) {
            val toClient = server.handshakeStep(toServer)
            toServer = client.handshakeStep(toClient)
        }
        assertTrue(client.handshakeDone && server.handshakeDone)
        val big = ByteArray(16384) { it.toByte() }
        val rec = client.encrypt(big)
        assertTrue(rec.size > big.size)
        assertEquals(big.toList(), server.decrypt(rec).plaintext.toList())
    }
}
