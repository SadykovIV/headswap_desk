package com.headswap.desk.aa

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** Сертификат и ключ головного устройства. */
class AapCredentials(val certSet: String, val chain: Array<X509Certificate>, val key: PrivateKey)

/** Загрузка PEM-наборов jvc/hur: assets/aa/<set>/{cert,key}.pem. */
object AapCerts {
    val SETS = listOf("jvc", "hur")

    /** Результат загрузки с запасным набором. */
    class Loaded(val creds: AapCredentials, val requested: String, val error: String?)

    private fun pemBody(pem: String, label: String): ByteArray? {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val i = pem.indexOf(begin)
        if (i < 0) return null
        val j = pem.indexOf(end, i)
        if (j < 0) return null
        return Base64.getMimeDecoder().decode(pem.substring(i + begin.length, j))
    }

    private fun derLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    }

    /** PKCS#1 RSAPrivateKey -> PKCS#8 PrivateKeyInfo (Java читает только второй). */
    fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val rsaAlg = byteArrayOf(0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00)
        val inner = ByteArrayOutputStream()
        inner.write(byteArrayOf(0x02, 0x01, 0x00))
        inner.write(rsaAlg)
        inner.write(0x04)
        inner.write(derLen(pkcs1.size))
        inner.write(pkcs1)
        val body = inner.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(0x30)
        out.write(derLen(body.size))
        out.write(body)
        return out.toByteArray()
    }

    fun parsePrivateKey(pem: String): PrivateKey {
        val pkcs8 = pemBody(pem, "PRIVATE KEY") ?: pemBody(pem, "RSA PRIVATE KEY")?.let { pkcs1ToPkcs8(it) }
            ?: throw IllegalArgumentException("в key.pem нет PRIVATE KEY / RSA PRIVATE KEY")
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    fun parseCertificate(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate

    /** Грузит один набор; бросает, если сертификат/ключ не разобрались. */
    fun load(opener: (String) -> InputStream, set: String): AapCredentials {
        require(set in SETS) { "неизвестный набор сертификатов $set, ожидается $SETS" }
        fun text(name: String) = opener("aa/$set/$name").use { String(it.readBytes(), Charsets.US_ASCII) }
        val cert = parseCertificate(text("cert.pem"))
        cert.encoded                                   // форсировать нативный разбор DER
        val key = parsePrivateKey(text("key.pem"))
        return AapCredentials(set, arrayOf(cert), key)
    }

    /** Запрошенный набор, при ошибке разбора - другой; error заполнен, если был откат. */
    fun loadWithFallback(opener: (String) -> InputStream, preferred: String): Loaded {
        return try {
            Loaded(load(opener, preferred), preferred, null)
        } catch (e: Exception) {
            val other = SETS.first { it != preferred }
            val msg = "набор '$preferred' не загрузился: ${e.javaClass.simpleName}: ${e.message}"
            Loaded(load(opener, other), preferred, msg)
        }
    }
}

/** Клиентский/серверный KeyManager с единственным ключом (HU отдаёт свой сертификат по запросу телефона). */
class SingleKeyKeyManager(private val creds: AapCredentials) : X509ExtendedKeyManager() {
    private val alias = "aa-${creds.certSet}"
    override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(alias)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) = alias
    override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(alias)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?) = alias
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) = alias
    override fun getCertificateChain(alias: String?) = if (alias == this.alias) creds.chain else null
    override fun getPrivateKey(alias: String?) = if (alias == this.alias) creds.key else null
}

/** Доверяет любому сертификату (телефон не проверяется, как в headunit-revived NoCheckTrustManager). */
class NoCheckTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * TLS-клиент (SSLEngine) поверх кадров AAP: записи TLS ездят внутри кадров, не по сокету.
 * handshakeStep(data) -> байты для plain-кадра ENCAPSULATED_SSL; encrypt/decrypt - тело одного кадра.
 * serverMode только для тестов (ответная сторона).
 */
class AapTls(creds: AapCredentials, private val serverMode: Boolean = false, tls12Only: Boolean = false) {
    private val engine: SSLEngine
    private var inBuf = ByteArray(0)
    var handshakeDone = false
        private set
    var closed = false
        private set

    init {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(SingleKeyKeyManager(creds)), arrayOf(NoCheckTrustManager()), null)
        // тот же ключ кэша сессий, что у headunit-revived: createSSLEngine("android-auto", 5277)
        engine = ctx.createSSLEngine(SNI_NAME, 5277)
        engine.useClientMode = !serverMode
        if (serverMode) engine.needClientAuth = true
        if (tls12Only) engine.enabledProtocols = arrayOf("TLSv1.2")
        engine.beginHandshake()
    }

    /** Байты от пира (на первом вызове нет), в ответ - байты для пира. После вызова смотри handshakeDone. */
    fun handshakeStep(data: ByteArray? = null): ByteArray {
        if (data != null && data.isNotEmpty()) inBuf += data
        val out = ByteArrayOutputStream()
        var guard = 0
        while (guard++ < 1000) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_TASK -> runTasks()
                HandshakeStatus.NEED_WRAP -> {
                    val r = wrapInto(ByteBuffer.allocate(0), out)
                    note(r)
                }
                HandshakeStatus.NEED_UNWRAP -> {
                    if (inBuf.isEmpty()) return out.toByteArray()
                    val src = ByteBuffer.wrap(inBuf)
                    val r = unwrapFrom(src, ByteArrayOutputStream())
                    inBuf = ByteArray(src.remaining()).also { src.get(it) }
                    note(r)
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) return out.toByteArray()
                }
                else -> {                                   // NOT_HANDSHAKING / FINISHED
                    handshakeDone = true
                    return out.toByteArray()
                }
            }
            if (handshakeDone && engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING) return out.toByteArray()
        }
        throw IllegalStateException("TLS handshake: цикл не сходится")
    }

    private fun note(r: SSLEngineResult) {
        if (r.handshakeStatus == HandshakeStatus.FINISHED) handshakeDone = true
        if (r.status == SSLEngineResult.Status.CLOSED) closed = true
    }

    /** Одна запись TLS (запись до 16 КиБ plaintext = один кадр AAP). */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val src = ByteBuffer.wrap(plaintext)
        while (src.hasRemaining()) {
            val r = wrapInto(src, out)
            if (r.status == SSLEngineResult.Status.CLOSED) throw IllegalStateException("TLS закрыт, отправка невозможна")
        }
        return out.toByteArray()
    }

    class Decrypted(val plaintext: ByteArray, val outgoing: ByteArray)

    /**
     * Тело одного кадра -> (plaintext, байты, которые TLS-слой хочет отправить). plaintext может быть пустым:
     * запись из одних handshake-данных (TLS 1.3 session tickets).
     */
    fun decrypt(record: ByteArray, drain: Boolean = true): Decrypted {
        inBuf += record
        val plain = ByteArrayOutputStream()
        val out = ByteArrayOutputStream()
        var guard = 0
        while (guard++ < 1000) {
            if (engine.handshakeStatus == HandshakeStatus.NEED_TASK) {
                runTasks()
                continue
            }
            if (drain && engine.handshakeStatus == HandshakeStatus.NEED_WRAP) {
                note(wrapInto(ByteBuffer.allocate(0), out))
                continue
            }
            if (inBuf.isEmpty()) break
            val src = ByteBuffer.wrap(inBuf)
            val r = unwrapFrom(src, plain)
            inBuf = ByteArray(src.remaining()).also { src.get(it) }
            note(r)
            if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW || r.status == SSLEngineResult.Status.CLOSED) break
            if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) break
        }
        // после CLOSED / разбора хвоста: что TLS хочет отправить (ответный close_notify и т.п.)
        var g2 = 0
        while (drain && engine.handshakeStatus == HandshakeStatus.NEED_WRAP && g2++ < 10) note(wrapInto(ByteBuffer.allocate(0), out))
        return Decrypted(plain.toByteArray(), out.toByteArray())
    }

    /** TLS-слой хочет что-то отправить (KeyUpdate, close_notify...). */
    fun wantsWrap(): Boolean = engine.handshakeStatus == HandshakeStatus.NEED_WRAP

    /** Байты, которые TLS-слой хочет отправить; зовёт только нить-писатель сессии. */
    fun drainOutgoing(): ByteArray {
        val out = ByteArrayOutputStream()
        var g = 0
        while (engine.handshakeStatus == HandshakeStatus.NEED_WRAP && g++ < 10) note(wrapInto(ByteBuffer.allocate(0), out))
        return out.toByteArray()
    }

    fun describe(): String {
        val s = engine.session
        return "${s.protocol} ${s.cipherSuite}"
    }

    private fun wrapInto(src: ByteBuffer, out: ByteArrayOutputStream): SSLEngineResult {
        var dst = ByteBuffer.allocate(engine.session.packetBufferSize + 64)
        while (true) {
            val r = engine.wrap(src, dst)
            if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                dst = ByteBuffer.allocate(dst.capacity() * 2)
                continue
            }
            dst.flip()
            val b = ByteArray(dst.remaining())
            dst.get(b)
            out.write(b)
            if (r.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
            return r
        }
    }

    private fun unwrapFrom(src: ByteBuffer, plain: ByteArrayOutputStream): SSLEngineResult {
        var dst = ByteBuffer.allocate(engine.session.applicationBufferSize + 64)
        while (true) {
            val r = engine.unwrap(src, dst)
            if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                dst = ByteBuffer.allocate(dst.capacity() * 2)
                continue
            }
            dst.flip()
            val b = ByteArray(dst.remaining())
            dst.get(b)
            plain.write(b)
            if (r.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
            return r
        }
    }

    private fun runTasks() {
        while (true) {
            val t = engine.delegatedTask ?: break
            t.run()
        }
    }

    companion object {
        const val SNI_NAME = "android-auto"
    }
}

/**
 * Самопроверка без сети: полный TLS-handshake клиента (HU, сертификат set) против серверного
 * SSLEngine (сертификат второго набора) в одном процессе, плюс обмен данными в обе стороны.
 * Показывает, принимает ли TLS-стек (Conscrypt на телефоне, JSSE в JVM) сертификат и ключ набора.
 */
object AapTlsSelfTest {
    class Result(val set: String, val ok: Boolean, val detail: String)

    fun run(opener: (String) -> InputStream, set: String, tls12Only: Boolean = false): Result {
        return try {
            val client = AapTls(AapCerts.load(opener, set), serverMode = false, tls12Only = tls12Only)
            val serverSet = AapCerts.SETS.first { it != set }
            val server = AapTls(AapCerts.load(opener, serverSet), serverMode = true, tls12Only = tls12Only)
            var toServer = client.handshakeStep()
            var rounds = 0
            while (rounds++ < 10) {
                val toClient = server.handshakeStep(toServer)
                toServer = client.handshakeStep(toClient)
                if (client.handshakeDone && server.handshakeDone && toServer.isEmpty() && toClient.isEmpty()) break
            }
            check(client.handshakeDone) { "клиент не завершил handshake" }
            check(server.handshakeDone) { "сервер не завершил handshake" }
            val msg = byteArrayOf(0, 11, 8, 5)
            val got = server.decrypt(client.encrypt(msg))
            check(got.plaintext.contentEquals(msg)) { "данные клиент->сервер не совпали" }
            val back = client.decrypt(server.encrypt(msg))
            check(back.plaintext.contentEquals(msg)) { "данные сервер->клиент не совпали" }
            Result(set, true, "${client.describe()} клиентский сертификат принят сервером")
        } catch (e: Throwable) {
            Result(set, false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
