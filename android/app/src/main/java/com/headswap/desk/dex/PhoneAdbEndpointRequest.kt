package com.headswap.desk.dex

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import com.flyfishxu.kadb.Kadb
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class DesktopPreparationException(message: String, val canPair: Boolean = false) : Exception(message)

/** Bounded, cancellable authentication. A stopped radio does not wait for impossible mDNS events. */
internal class PhoneAdbEndpointRequest(private val context: Context, private val checkActive: () -> Unit) : AutoCloseable {
    private val active = AtomicReference<Kadb?>()
    private val closed = AtomicBoolean()

    fun resolve(discovered: () -> DexEndpoint?, startDiscovery: () -> Unit,
                obsolete: () -> Boolean): DexEndpoint? {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        val expired = AtomicBoolean()
        val owned = AtomicReference<Kadb?>()
        val timer = deadlines.schedule({ expired.set(true); runCatching { owned.getAndSet(null)?.close() } }, 20_000, TimeUnit.MILLISECONDS)
        val retry = DexConnectRetryPolicy()
        var failure: DexAccessException? = null
        var discoveryStarted = false
        try {
            while (true) {
                ensureActive()
                if (obsolete()) return null
                if (expired.get() || SystemClock.elapsedRealtime() >= deadline) {
                    throw failure ?: DesktopPreparationException("Отладка не найдена. Включите Wi-Fi и беспроводную отладку.", canPair = true)
                }
                val allowed = discoveryAllowed(context)
                if (allowed && !discoveryStarted) { discoveryStarted = true; startDiscovery() }
                val prefs = DexPrefs(context)
                val local = IndependentRecoveryAccess.recoveryEndpoint(context)
                val saved = DexEndpoint(prefs.host, prefs.connectPort)
                val candidates = buildList {
                    local?.let(::add)
                    tlsEndpoint()?.let(::add)
                    if (allowed) discovered()?.let(::add)
                    addAll(VdmRecoveryEndpoints.candidates(saved, savedPairedPort = true, DexAccess::acceptsHost))
                }.distinct().filter { it.port in 1..65535 && DexAccess.acceptsHost(it.host) &&
                    VdmRecoveryPolicy.endpointAllowed(it, !allowed) && retry.accepts(it) }
                val endpoint = candidates.firstOrNull()
                if (endpoint == null) {
                    if (!allowed) throw DesktopPreparationException(
                        "Локальная отладка сейчас недоступна. Включите Wi-Fi и беспроводную отладку.", canPair = true)
                    Thread.sleep(80)
                    continue
                }
                try {
                    val remaining = (deadline - SystemClock.elapsedRealtime()).coerceIn(1, 15_000).toInt()
                    if (endpoint == local) try {
                        LegacyAdbChallenge.requireAuthenticated(endpoint.port, minOf(remaining, 1500))
                    } catch (_: IllegalStateException) {
                        throw DexAccessException(DexAccessStage.PROBE_CONNECT, DexAccessReason.LOCAL_ADB_CONFIGURATION)
                    }
                    val adb = DexAccess.connectEndpoint(context, endpoint, remaining)
                    active.set(adb); owned.set(adb)
                    try {
                        ensureActive()
                        check(!expired.get()) { "Истекло время проверки отладки" }
                        DexAccess.verifyShell(adb, endpoint.host, endpoint.port)
                        ensureActive()
                        if (obsolete()) return null
                        if (endpoint != local) { prefs.host = endpoint.host; prefs.connectPort = endpoint.port }
                        return endpoint
                    } finally { owned.compareAndSet(adb, null); active.compareAndSet(adb, null); adb.close() }
                } catch (error: Exception) {
                    ensureActive()
                    if (error is InterruptedException) throw error
                    val classified = error as? DexAccessException ?: DexAccessException(
                        DexAccessStage.PROBE_CONNECT, DexAccessDiagnostics.classify(error))
                    failure = classified
                    if (!retry.failed(endpoint, classified.reason)) throw classified
                }
            }
        } finally { timer.cancel(false); runCatching { owned.getAndSet(null)?.close() } }
    }

    fun <T> withConnection(endpoint: DexEndpoint, action: (Kadb) -> T): T {
        ensureActive()
        val adb = DexAccess.connectEndpoint(context, endpoint, 15_000)
        active.set(adb)
        try { ensureActive(); return action(adb) }
        finally { active.compareAndSet(adb, null); adb.close() }
    }
    private fun ensureActive() { checkActive(); if (closed.get()) throw InterruptedException() }
    override fun close() { closed.set(true); runCatching { active.getAndSet(null)?.close() } }

    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "headswap-phone-endpoint-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        internal fun tlsEndpoint(): DexEndpoint? = VdmRecoveryEndpoints.tlsLoopback(runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "service.adb.tls.port") as? String
        }.getOrNull())
        private fun wifiEnabled(context: Context): Boolean? {
            val radio = runCatching { context.getSystemService(WifiManager::class.java)?.wifiState }.getOrNull()
            return when (radio) {
                WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> false
                WifiManager.WIFI_STATE_ENABLED -> true
                else -> null
            }
        }
        /** A code sheet can start advertising before adb_wifi_enabled is observed by this process. */
        internal fun pairingDiscoveryAllowed(context: Context): Boolean = wifiEnabled(context) != false
        internal fun environment(context: Context): Pair<Boolean?, Int> = wifiEnabled(context) to runCatching {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1)
        }.getOrDefault(-1)
        internal fun discoveryAllowed(context: Context): Boolean {
            val (enabled, debugging) = environment(context)
            return VdmRecoveryPolicy.discoveryAllowed(enabled, debugging, tlsEndpoint()?.port?.toString())
        }
    }
}
