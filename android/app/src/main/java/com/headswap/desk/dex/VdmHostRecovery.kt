package com.headswap.desk.dex

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.flyfishxu.kadb.Kadb
import com.headswap.desk.AppLog
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Session-owned repair using this installation's paired ADB identity; never configures adbd. */
internal object VdmHostRecovery {
    private val launchLock = ReentrantLock()
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "headswap-vdm-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun needsRecovery(context: Context): Boolean = Build.VERSION.SDK_INT >= 37 &&
        DexPrefs(context).useDelegated && DexPrefs(context).desktopMode != DesktopModePolicy.SAMSUNG_DEX &&
        DelegatedDesktopAccess.isReady(context) && !VdmHostLink.isReady

    private fun localTlsPort(): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tls.port") as? String
    }.getOrNull()

    private fun wifiEnabled(context: Context): Boolean? = runCatching {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return@runCatching null
        // ENABLING/UNKNOWN are not evidence that discovery cannot become available.
        when (wifi.wifiState) {
            WifiManager.WIFI_STATE_ENABLED -> true
            WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> false
            else -> null
        }
    }.getOrNull()

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
        @Volatile private var localOnly = false
        @Volatile private var connection: Kadb? = null
        @Volatile private var discovery: DexDiscovery? = null
        @Volatile private var launch: VdmHostRendezvous? = null
        @Volatile private var managedProof: RecoveryReadinessProof.Attempt? = null
        @Volatile var failure: String? = null
            private set

        @Volatile var failureReason: DexAccessReason? = null
            private set

        /** Blocking worker call. Stop/retirement must close the request, including its owned socket. */
        fun ensureReady(timeoutMs: Long = 18_000): Boolean {
            check(Looper.myLooper() != Looper.getMainLooper()) { "VDM recovery must run off main" }
            require(timeoutMs in 1..30_000)
            if (Build.VERSION.SDK_INT < 37) return current()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            // A socket timeout bounds each read, not the total handshake/proof/bootstrap chain.
            // One request-owned deadline cancels the current socket and pending handoff only.
            val expiry = deadlines.schedule({
                if (current()) {
                    failure = VdmRecoveryPolicy.OFFLINE_MESSAGE
                    close()
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
            var locked = false
            var managedEndpoint: DexEndpoint? = null
            var managedRefused = false
            var lastTransportFailure: DexAccessReason? = null
            try {
                while (current() && SystemClock.elapsedRealtime() < deadline) {
                    if (VdmHostLink.isReady) return true
                    if (launchLock.tryLock(100, TimeUnit.MILLISECONDS)) { locked = true; break }
                }
                if (!locked || !current()) return false
                if (VdmHostLink.isReady) return true
                if (!DelegatedDesktopAccess.isReady(context)) return fail("Доступ HeadSwap завершён. Активируйте приложение снова.")
                val tlsPort = localTlsPort()
                val wifiDebugging = runCatching {
                    Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1)
                }.getOrDefault(-1)
                val radioEnabled = wifiEnabled(context)
                localOnly = !VdmRecoveryPolicy.discoveryAllowed(radioEnabled, wifiDebugging, tlsPort)
                if (localOnly) AppLog.i("VDM recovery local-only: wifi=$radioEnabled wirelessDebugging=$wifiDebugging activation=ready", false)
                val prefs = DexPrefs(context)
                if (!prefs.hasPaired) return fail("Для восстановления независимого экрана выполните первое сопряжение через «Активировать с телефона».")
                val owner = activeOwner() ?: return fail("Владелец текущей активации не подтверждён.")
                val request = VdmHostLink.requestLaunch()
                    ?: return (VdmHostLink.isReady || fail("Сеанс независимого экрана завершён."))
                launch = request
                if (!current()) return false
                // A USB mode change can restart TLS on a new port before mDNS is delivered.
                // SELinux/hidden-API denial is harmless: saved endpoint and mDNS remain available.
                val independent = IndependentRecoveryAccess.recoveryEndpoint(context)
                managedEndpoint = independent
                independent?.let { enqueue(it, savedPairedPort = true) }
                VdmRecoveryEndpoints.tlsLoopback(tlsPort)?.let { enqueue(it, savedPairedPort = true) }
                enqueue(DexEndpoint(prefs.host, prefs.connectPort), savedPairedPort = true)
                if (!localOnly) main.post {
                    if (current()) discovery = DexDiscovery(context) { _, endpoint, _ -> endpoint?.let(::enqueue) }.also { it.start() }
                }
                val tried = mutableSetOf<DexEndpoint>()
                var managedRetryAt = 0L
                var managedRetries = 0
                while (current() && SystemClock.elapsedRealtime() < deadline) {
                    if (VdmHostLink.isReady) return true
                    val now = SystemClock.elapsedRealtime()
                    if (managedRetryAt > 0 && now >= managedRetryAt && independent != null) {
                        tried.remove(independent)
                        endpoints.add(independent)
                        managedRetryAt = 0
                    }
                    val endpoint = endpoints.poll()
                    if (endpoint == null) {
                        // A disabled radio cannot discover a replacement listener. Still try
                        // every known loopback port first: it may survive a network change.
                        if (localOnly && managedRetryAt == 0L) return fail(VdmRecoveryPolicy.OFFLINE_MESSAGE)
                        Thread.sleep(80)
                        continue
                    }
                    if (!tried.add(endpoint)) continue
                    // DexAccess already caps TCP connect at 3s. Keep a separate, longer read
                    // deadline for the authenticated bootstrap command (up to 15s).
                    val remaining = (deadline - now).coerceIn(1, 20_000).toInt()
                    val proofAttempt = if (endpoint == independent)
                        IndependentRecoveryAccess.captureManagedRecovery(context, endpoint) else null
                    managedProof = proofAttempt
                    try {
                        if (!current()) return false
                        if (endpoint == independent) try {
                            LegacyAdbChallenge.requireAuthenticated(endpoint.port, minOf(remaining, 1500))
                        } catch (_: IllegalStateException) {
                            throw DexAccessException(DexAccessStage.CONNECT, DexAccessReason.LOCAL_ADB_CONFIGURATION)
                        }
                        val adb = DexAccess.connectEndpoint(context, endpoint, remaining)
                        connection = adb
                        try {
                            if (!current()) return false
                            DexAccess.verifyShell(adb, endpoint.host, endpoint.port)
                            if (endpoint == independent) {
                                val authentication = adb.shell("getprop ro.adb.secure")
                                if (authentication.exitCode != 0 || authentication.output.trim() != "1")
                                    throw DexAccessException(DexAccessStage.CONNECT, DexAccessReason.LOCAL_ADB_CONFIGURATION)
                            }
                            if (!current() || activeOwner()?.encode() != owner.encode()) return false
                            // Exact private activation record proves the socket reaches this installation,
                            // not just a device with the same package name or a stale mDNS address.
                            val proof = adb.shell("run-as ${ManualAccessPolicy.PACKAGE} --user ${owner.uid / 100000} cat ${DisplayHelperRendezvous.RUN_AS_PATH}")
                            if (proof.exitCode != 0 || proof.output.trimEnd() != owner.encode())
                                throw DexAccessException(DexAccessStage.CONNECT, DexAccessReason.IDENTITY_FAILURE)
                            if (!current()) return false
                            if (endpoint != independent) {
                                prefs.host = endpoint.host
                                prefs.connectPort = endpoint.port
                            }
                            val command = VdmHostBootstrapPolicy.recoveryCommand(context.applicationInfo.sourceDir,
                                owner.uid, owner.pid, owner.token, request.nonce)
                            val result = adb.shell(command)
                            if (!current()) return false
                            if (result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "HEADSWAP_VDM_READY" } && VdmHostLink.isReady) {
                                managedRefused = false
                                if (proofAttempt != null) runCatching {
                                    IndependentRecoveryAccess.confirmManagedRecovery(context, endpoint, proofAttempt, adb) {
                                        current() && activeOwner()?.encode() == owner.encode() && runCatching {
                                            VdmHostLink.status(owner.token, owner.pid, request.nonce)
                                                .getBoolean(VdmHostPolicy.EXTRA_READY)
                                        }.getOrDefault(false)
                                    }
                                }.onFailure { error ->
                                    AppLog.i("VDM recovery proof refresh: ${error.javaClass.simpleName}", false)
                                }
                                if (!current()) return false
                                AppLog.i("VDM: независимый экран восстановлен через сохранённое сопряжение", false)
                                return true
                            }
                        } finally { connection = null; adb.close() }
                    } catch (error: Exception) {
                        if (!current()) return false
                        // No private record, nonce, command or ADB response belongs in logs.
                        val reason = DexAccessDiagnostics.classify(error)
                        if (reason in setOf(DexAccessReason.REFUSED, DexAccessReason.TIMEOUT,
                                DexAccessReason.NETWORK_UNREACHABLE, DexAccessReason.CONNECTION_CLOSED)) lastTransportFailure = reason
                        if (endpoint == independent && reason == DexAccessReason.REFUSED) managedRefused = true
                        AppLog.i("VDM recovery attempt: route=${if (endpoint == independent) "managed" else "tls"} port=${endpoint.port} reason=$reason", false)
                        if (VdmRecoveryPolicy.terminal(reason)) {
                            failureReason = reason
                            return fail(DexAccessException(DexAccessStage.CONNECT, reason).message ?: VdmRecoveryPolicy.OFFLINE_MESSAGE)
                        }
                        VdmRecoveryPolicy.managedRetryDelay(reason, endpoint == independent, managedRetries)?.let { delay ->
                            managedRetries++
                            managedRetryAt = SystemClock.elapsedRealtime() + delay
                        }
                    } finally {
                        IndependentRecoveryAccess.cancelManagedRecovery(proofAttempt)
                        if (managedProof === proofAttempt) managedProof = null
                    }
                }
                return fail(VdmRecoveryPolicy.OFFLINE_MESSAGE)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            } finally {
                expiry.cancel(false)
                if (!VdmHostLink.isReady && failureReason == null) failureReason = lastTransportFailure
                if (managedRefused && !VdmHostLink.isReady) managedEndpoint?.let {
                    IndependentRecoveryAccess.noteUnavailable(context, it, DexAccessReason.REFUSED)
                }
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
            VdmRecoveryEndpoints.candidates(endpoint, savedPairedPort, DexAccess::acceptsHost)
                .filter { VdmRecoveryPolicy.endpointAllowed(it, localOnly) }.forEach(endpoints::add)
        }
        private fun current() = !closed.get() && !Thread.currentThread().isInterrupted && runCatching(sessionAlive).getOrDefault(false)
        private fun fail(message: String): Boolean { if (current()) failure = message; return false }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            IndependentRecoveryAccess.cancelManagedRecovery(managedProof)
            runCatching { connection?.close() }
            launch?.let(VdmHostLink::cancelLaunch)
            main.post { discovery?.close(); discovery = null }
            endpoints.clear()
        }
    }
}
