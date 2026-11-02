package com.headswap.desk.dex

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import com.headswap.desk.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** All connections are to the phone itself. There is no arbitrary host or shell-command UI. */
object DexAccess {
    private var configured = false

    @Synchronized
    private fun configure(context: Context) {
        if (configured) return
        KadbCert.configure(IdentityStore(context.applicationContext))
        KadbCert.ensureReady()
        configured = true
    }

    /** Only literal loopback or an address currently assigned to this phone; never DNS. */
    fun acceptsHost(host: String): Boolean = LocalAddressPolicy.accepts(host, LocalAddressPolicy.ownAddresses())

    /** Keep Android's pairing-code sheet open while entering the code in split-screen. */
    suspend fun pair(context: Context, port: Int, code: String, host: String = "127.0.0.1") = withContext(Dispatchers.IO) {
        val operation = Operation("pair", host, port)
        operation.environment(context)
        try {
            val address = operation.validate(host, port)
            if (!code.matches(Regex("[0-9]{6}"))) throw DexAccessException(DexAccessStage.VALIDATE, DexAccessReason.INVALID_CODE)
            operation.stage(DexAccessStage.IDENTITY)
            configure(context)
            operation.stage(DexAccessStage.PAIR_CONNECT)
            // Kadb 2.1.1 pairing has no socket timeout. This opaque, single-connection
            // tunnel provides a hard deadline, without consuming a one-shot pairing port
            // in a preliminary TCP probe or terminating the end-to-end TLS connection.
            PairingTunnel(address, port, onConnected = { operation.stage(DexAccessStage.PAIR_HANDSHAKE) }).use { tunnel ->
                try {
                    Kadb.pair("127.0.0.1", tunnel.port, code, "HeadSwap DeX")
                } catch (e: Exception) {
                    // The client's SSL close is only a consequence when the target refused
                    // TCP or the deadline expired. Preserve the original transport failure.
                    val failure = tunnel.failure
                    if (failure != null && failure.kind != PairingTunnel.FailureKind.RELAY) {
                        val stage = if (failure.kind == PairingTunnel.FailureKind.CONNECT) DexAccessStage.PAIR_CONNECT else operation.currentStage
                        throw operation.failure(failure.error, stage, secondary = e)
                    }
                    throw e
                }
            }
            DexPrefs(context).apply { pairingPort = port; this.host = address; hasPaired = true }
            operation.success()
        } catch (e: Exception) {
            throw operation.failure(e)
        }
    }

    /** Blocking: call from a worker thread. Use the main debugging port, never a pairing port. */
    fun probe(context: Context, port: Int, host: String = "127.0.0.1"): String {
        val operation = Operation("probe", host, port)
        operation.environment(context)
        try {
            val address = operation.validate(host, port)
            operation.stage(DexAccessStage.IDENTITY)
            configure(context)
            operation.stage(DexAccessStage.PROBE_CONNECT)
            // Kadb connects lazily when the first stream opens. This includes ADB/TLS auth.
            Kadb.create(address, port, connectTimeout = 3_000, socketTimeout = 15_000).use { adb ->
                val result = adb.shell("id -u")
                operation.stage(DexAccessStage.PROBE_SHELL)
                if (result.exitCode != 0 || result.output.trim() != "2000") {
                    throw DexAccessException(DexAccessStage.PROBE_SHELL, DexAccessReason.SHELL_REJECTED)
                }
            }
            DexPrefs(context).hasPaired = true
            operation.success()
            return "Android shell доступен (UID 2000)"
        } catch (e: Exception) {
            throw operation.failure(e)
        }
    }

    /** Explicit phone-only bootstrap. Dispatch acceptance is never treated as a grant. */
    internal suspend fun activatePhone(context: Context, endpoint: DexEndpoint, audio: Boolean,
                                      screen: Boolean): PhoneActivationLaunch = withContext(Dispatchers.IO) {
        val operation = Operation("phone-activation", endpoint.host, endpoint.port)
        val request = PhoneActivationStatus.begin(context, audio, screen)
        var submitted = false
        try {
            val address = operation.validate(endpoint.host, endpoint.port)
            configure(context)
            Kadb.create(address, endpoint.port, connectTimeout = 3_000, socketTimeout = 15_000).use { adb ->
                verifyShell(adb, address, endpoint.port)
                val uid = Process.myUid()
                // The nonce exists only in this installation's private data. A service name,
                // local-looking address or an identical package on another device is insufficient.
                val proof = adb.shell("run-as ${ManualAccessPolicy.PACKAGE} --user ${uid / 100000} cat ${PhoneActivationPolicy.REQUEST}")
                check(proof.exitCode == 0 && proof.output.length <= 4096) { "Не удалось подтвердить этот телефон" }
                val owned = JSONObject(proof.output)
                check(owned.optString("token") == request.token && owned.optInt("uid") == uid) { "ADB относится к другой установке приложения" }
                val path = context.applicationInfo.sourceDir
                val installed = adb.shell("pm path ${ManualAccessPolicy.PACKAGE}")
                check(installed.exitCode == 0 && installed.output.lineSequence().any { it.trim() == "package:$path" }) {
                    "Установленная версия HeadSwap изменилась; откройте приложение заново"
                }
                val commands = adb.shell("test -x /system/bin/setsid && test -x /system/bin/nohup && test -x /system/bin/app_process")
                check(commands.exitCode == 0) { "Прошивка не поддерживает безопасный запуск на телефоне" }
                val command = PhoneActivationPolicy.dispatchCommand(path, uid, request.token)
                submitted = true
                val result = adb.shell(command)
                check(result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "HEADSWAP_PHONE_DISPATCHED=${request.token}" }) {
                    "Запуск ещё не подтверждён"
                }
            }
            operation.success()
            PhoneActivationLaunch.ACCEPTED
        } catch (error: Exception) {
            // am instrument intentionally kills this process and its ADB connection. If
            // dispatch was submitted, only the detached result or deadline may decide failure.
            if (submitted || PhoneActivationStatus.dispatched(context, request)) {
                AppLog.i("Phone activation dispatched; waiting for live access confirmation")
                PhoneActivationLaunch.ACCEPTED
            } else {
                val failure = operation.failure(error)
                PhoneActivationStatus.failed(context, request, "Не удалось подключиться к беспроводной отладке. Проверьте Wi-Fi, отладку и сопряжение.")
                throw failure
            }
        }
    }

    internal fun connect(context: Context, port: Int): Kadb {
        val host = DexPrefs(context).host
        val operation = Operation("connect", host, port)
        try {
            val address = operation.validate(host, port)
            operation.stage(DexAccessStage.IDENTITY)
            configure(context)
            operation.stage(DexAccessStage.CONNECT)
            return Kadb.create(address, port, connectTimeout = 3_000, socketTimeout = 15_000)
        } catch (e: Exception) {
            throw operation.failure(e)
        }
    }

    /** Called after the connector owns the socket, so Stop can interrupt initial auth. */
    internal fun verifyShell(adb: Kadb, host: String, port: Int) {
        val operation = Operation("connect", host, port)
        operation.stage(DexAccessStage.CONNECT)
        try {
            val result = adb.shell("id -u")
            if (result.exitCode != 0 || result.output.trim() != "2000") {
                throw DexAccessException(DexAccessStage.CONNECT, DexAccessReason.SHELL_REJECTED)
            }
            operation.success()
        } catch (e: Exception) {
            throw operation.failure(e)
        }
    }

    private class Operation(private val name: String, host: String, port: Int) {
        private val started = System.nanoTime()
        private val safeHost = LocalAddressPolicy.normalizeOrNull(host) ?: "invalid"
        private val safePort = if (port in 1..65535) port.toString() else "invalid"
        @Volatile var currentStage: DexAccessStage = DexAccessStage.VALIDATE
            private set
        private var loggedFailure: DexAccessException? = null

        fun validate(host: String, port: Int): String {
            stage(DexAccessStage.VALIDATE)
            if (!acceptsHost(host)) throw DexAccessException(currentStage, DexAccessReason.INVALID_HOST)
            if (port !in 1..65535) throw DexAccessException(currentStage, DexAccessReason.INVALID_PORT)
            return LocalAddressPolicy.normalizeOrNull(host)!!
        }

        fun stage(value: DexAccessStage) {
            currentStage = value
            AppLog.detail("${prefix()} result=started")
        }

        fun success() = AppLog.i("${prefix()} result=success")

        fun failure(error: Exception, stage: DexAccessStage = currentStage, secondary: Exception? = null): DexAccessException {
            if (error === loggedFailure) return error as DexAccessException
            val failure = if (error is DexAccessException) error else DexAccessException(stage,
                if (stage == DexAccessStage.IDENTITY) DexAccessReason.IDENTITY_FAILURE else DexAccessDiagnostics.classify(error))
            currentStage = failure.stage
            // Never hand an unsanitized Throwable to AppLog: the pairing library may put
            // codes, private keys or peer material in messages or nested causes.
            val secondaryTrace = secondary?.let { " secondary=${DexAccessDiagnostics.safeTrace(it)}" }.orEmpty()
            AppLog.e("${prefix()} result=failed reason=${failure.reason} trace=${DexAccessDiagnostics.safeTrace(error)}$secondaryTrace")
            loggedFailure = failure
            return failure
        }

        fun environment(context: Context) {
            val network = runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                manager?.getNetworkCapabilities(manager.activeNetwork)
            }.getOrNull()
            val wifi = network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?.toString() ?: "unknown"
            val vpn = network?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?.toString() ?: "unknown"
            val lan = if (Build.VERSION.SDK_INT >= 37) {
                (context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED).toString()
            } else "not_required"
            fun safe(value: String): String = value.filter { it.isLetterOrDigit() || it in " ._-" }.take(64)
            AppLog.detail("${prefix()} result=environment manufacturer=${safe(Build.MANUFACTURER)} model=${safe(Build.MODEL)} sdk=${Build.VERSION.SDK_INT} release=${safe(Build.VERSION.RELEASE)} wifi=$wifi vpn=$vpn lanPermission=$lan")
        }

        private fun prefix(): String = "DEX access operation=$name stage=$currentStage host=$safeHost port=$safePort elapsedMs=${(System.nanoTime() - started) / 1_000_000}"
    }

    /** ADB private identity is encrypted with a non-exportable Android Keystore AES key. */
    private class IdentityStore(context: Context) : KadbPrivateKeyStore {
        private val file = context.noBackupFilesDir.resolve("dex-adb-identity.bin")
        private val alias = "headswap.dex.adb.identity.v1"
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun key(): SecretKey {
            (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        @Synchronized override fun readPrivateKeyPem(): ByteArray? {
            if (!file.exists()) return null
            val data = file.readBytes()
            require(data.size in 30..16_384 && data[0] == 1.toByte()) { "Повреждена сохранённая ADB-идентичность" }
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
                doFinal(data.copyOfRange(13, data.size))
            }
        }
        @Synchronized override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val tmp = file.resolveSibling("${file.name}.tmp")
            tmp.outputStream().use { out ->
                out.write(byteArrayOf(1) + cipher.iv + cipher.doFinal(privateKeyPem))
                out.fd.sync()
            }
            check(tmp.renameTo(file)) { "Не удалось сохранить ADB-идентичность" }
        }
        @Synchronized override fun clear() {
            file.delete()
            file.resolveSibling("${file.name}.tmp").delete()
            keyStore().deleteEntry(alias)
        }
    }

}
