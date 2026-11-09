package com.headswap.desk.dex

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.flyfishxu.kadb.Kadb
import com.headswap.desk.AppLog
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Session-owned repair using this installation's paired ADB identity; never configures adbd. */
internal object VdmHostRecovery {
    private val launchLock = ReentrantLock()

    /** Explicitly reports the boundary; no hidden tcpip:5555/ADB_ENABLED/OEM fallback. */
    enum class Capability { NOT_REQUIRED, HOST_READY, PAIRED_DEBUGGING_REQUIRED, SAVED_PAIRING }
    fun capability(context: Context): Capability = when {
        Build.VERSION.SDK_INT < 37 -> Capability.NOT_REQUIRED
        VdmHostLink.isReady -> Capability.HOST_READY
        DexPrefs(context).hasPaired -> Capability.SAVED_PAIRING
        else -> Capability.PAIRED_DEBUGGING_REQUIRED
    }

    fun create(context: Context, sessionAlive: () -> Boolean): Request = Request(context.applicationContext, sessionAlive)

    class Request internal constructor(private val context: Context, private val sessionAlive: () -> Boolean) : AutoCloseable {
        private val closed = AtomicBoolean()
        private val main = Handler(Looper.getMainLooper())
        private val endpoints = ConcurrentLinkedQueue<DexEndpoint>()
        @Volatile private var connection: Kadb? = null
        @Volatile private var discovery: DexDiscovery? = null
        @Volatile private var launch: VdmHostRendezvous? = null
        @Volatile var failure: String? = null
            private set

        /** Blocking worker call. Stop/retirement must close the request, including its owned socket. */
        fun ensureReady(timeoutMs: Long = 18_000): Boolean {
            check(Looper.myLooper() != Looper.getMainLooper()) { "VDM recovery must run off main" }
            require(timeoutMs in 1..30_000)
            if (Build.VERSION.SDK_INT < 37) return current()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var locked = false
            try {
                while (current() && SystemClock.elapsedRealtime() < deadline) {
                    if (VdmHostLink.isReady) return true
                    if (launchLock.tryLock(100, TimeUnit.MILLISECONDS)) { locked = true; break }
                }
                if (!locked || !current()) return false
                if (VdmHostLink.isReady) return true
                if (!DelegatedDesktopAccess.isReady(context)) return fail("Доступ HeadSwap завершён. Активируйте приложение снова.")
                val prefs = DexPrefs(context)
                if (!prefs.hasPaired) return fail("Для восстановления независимого экрана выполните первое сопряжение через «Активировать с телефона».")
                val owner = activeOwner() ?: return fail("Владелец текущей активации не подтверждён.")
                val request = VdmHostLink.requestLaunch()
                    ?: return (VdmHostLink.isReady || fail("Сеанс независимого экрана завершён."))
                launch = request
                if (!current()) return false
                enqueue(DexEndpoint(prefs.host, prefs.connectPort), savedPairedPort = true)
                // A USB mode change can restart TLS on a new port before mDNS is delivered.
                // SELinux/hidden-API denial is harmless: saved endpoint and mDNS remain available.
                val tlsPort = runCatching {
                    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                        .invoke(null, "service.adb.tls.port") as? String
                }.getOrNull()
                VdmRecoveryEndpoints.tlsLoopback(tlsPort)?.let { enqueue(it, savedPairedPort = true) }
                main.post {
                    if (current()) discovery = DexDiscovery(context) { _, endpoint, _ -> endpoint?.let(::enqueue) }.also { it.start() }
                }
                val tried = mutableMapOf<DexEndpoint, Long>()
                while (current() && SystemClock.elapsedRealtime() < deadline) {
                    if (VdmHostLink.isReady) return true
                    val endpoint = endpoints.poll() ?: tried.entries.firstOrNull {
                        SystemClock.elapsedRealtime() - it.value >= 3_000
                    }?.key
                    if (endpoint == null) { Thread.sleep(80); continue }
                    val now = SystemClock.elapsedRealtime()
                    if (now - (tried[endpoint] ?: Long.MIN_VALUE / 2) < 3_000) continue
                    tried[endpoint] = now
                    val remaining = (deadline - now).coerceIn(1, 20_000).toInt()
                    try {
                        val adb = DexAccess.connectEndpoint(context, endpoint, remaining)
                        connection = adb
                        try {
                            if (!current()) return false
                            DexAccess.verifyShell(adb, endpoint.host, endpoint.port)
                            if (!current() || activeOwner()?.encode() != owner.encode()) return false
                            // Exact private activation record proves the socket reaches this installation,
                            // not just a device with the same package name or a stale mDNS address.
                            val proof = adb.shell("run-as ${ManualAccessPolicy.PACKAGE} --user ${owner.uid / 100000} cat ${DisplayHelperRendezvous.RUN_AS_PATH}")
                            check(proof.exitCode == 0 && proof.output.trimEnd() == owner.encode()) { "Foreign activation endpoint" }
                            if (!current()) return false
                            val command = VdmHostBootstrapPolicy.recoveryCommand(context.applicationInfo.sourceDir,
                                owner.uid, owner.pid, owner.token, request.nonce)
                            val result = adb.shell(command)
                            if (!current()) return false
                            if (result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "HEADSWAP_VDM_READY" } && VdmHostLink.isReady) {
                                AppLog.i("VDM: независимый экран восстановлен через сохранённое сопряжение", false)
                                return true
                            }
                        } finally { connection = null; adb.close() }
                    } catch (error: Exception) {
                        if (!current()) return false
                        // No private record, nonce, command or ADB response belongs in logs.
                        AppLog.i("VDM recovery attempt: ${error.javaClass.simpleName}", false)
                    }
                }
                return fail("Хост независимого экрана недоступен. Включите Wi-Fi и беспроводную отладку, затем повторите подключение.")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            } finally {
                val pending = launch
                if (!VdmHostLink.isReady && pending != null) VdmHostLink.cancelLaunch(pending)
                launch = null
                main.post { discovery?.close(); discovery = null }
                if (locked) launchLock.unlock()
            }
        }

        private fun activeOwner(): DisplayHelperRendezvous? = runCatching {
            val file = File(context.noBackupFilesDir, DisplayHelperRendezvous.FILE_NAME)
            if (file.length() !in 1..256 || !DelegatedDesktopAccess.isReady(context)) return null
            DisplayHelperRendezvous.parse(file.readText(Charsets.US_ASCII))?.takeIf {
                it.uid == Process.myUid() && it.pid == Process.myPid() && it.elapsed <= SystemClock.elapsedRealtime()
            }
        }.getOrNull()

        private fun enqueue(endpoint: DexEndpoint, savedPairedPort: Boolean = false) {
            if (!current() || endpoints.size >= 12) return
            VdmRecoveryEndpoints.candidates(endpoint, savedPairedPort, DexAccess::acceptsHost).forEach(endpoints::add)
        }
        private fun current() = !closed.get() && !Thread.currentThread().isInterrupted && runCatching(sessionAlive).getOrDefault(false)
        private fun fail(message: String): Boolean { if (current()) failure = message; return false }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { connection?.close() }
            launch?.let(VdmHostLink::cancelLaunch)
            main.post { discovery?.close(); discovery = null }
            endpoints.clear()
        }
    }
}
