package com.headswap.desk.dex

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.headswap.desk.AppLog

internal enum class DexService(val type: String) {
    PAIRING("_adb-tls-pairing._tcp"),
    CONNECT("_adb-tls-connect._tcp"),
}

internal data class DexEndpoint(val host: String, val port: Int)

/** A service name/subnet is not proof that this is our phone. Check every numeric address. */
internal fun selectOwnDexEndpoint(
    addresses: List<String>,
    port: Int,
    isOwnHost: (String) -> Boolean,
): DexEndpoint? {
    if (port !in 1..65535) return null
    return addresses.asSequence()
        .filter(isOwnHost)
        .sortedBy { if (':' in it) 1 else 0 }
        .firstOrNull()?.let { DexEndpoint(it, port) }
}

/** Foreground-only mDNS discovery. It fills fields; it never pairs or opens ADB sockets. */
internal class DexDiscovery(
    context: Context,
    private val onUpdate: (pairing: DexEndpoint?, connect: DexEndpoint?, error: String?) -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val executor = context.mainExecutor
    private val timer = Handler(Looper.getMainLooper())
    private val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
    private val endpoints = linkedMapOf<String, Pair<DexService, DexEndpoint>>()
    // No API-34 callback type appears in this class's fields or signatures on Android 11–13.
    private val resolver: Resolver by lazy {
        if (Build.VERSION.SDK_INT >= 34) ModernResolver() else LegacyResolver()
    }
    private var active = false
    private var generation = 0
    private var error: String? = null

    // Public entry points are called by the foreground Activity on main. Older NSD
    // callbacks use an internal handler, so marshal every discovery callback explicitly.
    fun start() {
        close()
        active = true
        error = null
        val session = generation
        DexService.entries.forEach { kind ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    executor.execute {
                        if (current(session)) { discoveries.remove(this); failed("start", code) }
                    }
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) {
                    executor.execute { AppLog.i("DEX discovery stop failed code=$code") }
                }
                override fun onServiceFound(info: NsdServiceInfo) {
                    executor.execute { if (current(session)) resolver.track(kind, info, session) }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    executor.execute {
                        if (current(session)) {
                            val key = key(kind, info)
                            resolver.remove(key)
                            if (endpoints.remove(key) != null) publish()
                        }
                    }
                }
            }
            discoveries.add(listener)
            try {
                // Available since API16. The executor/Network overload was only added in33.
                manager.discoverServices(kind.type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: RuntimeException) {
                discoveries.remove(listener)
                AppLog.i("DEX discovery start failed ${e.javaClass.simpleName}")
                error = "Автопоиск недоступен. Проверьте разрешение локальной сети или введите IP и порты вручную."
            }
        }
        publish()
    }

    private interface Resolver {
        fun track(kind: DexService, info: NsdServiceInfo, session: Int)
        fun remove(key: String)
        fun close()
    }

    /** Kept in its own class so the API34 callback interface is never verified on older ART. */
    @RequiresApi(34)
    private inner class ModernResolver : Resolver {
        private val resolutions = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
        override fun track(kind: DexService, info: NsdServiceInfo, session: Int) {
            val key = key(kind, info)
            if (key in resolutions || resolutions.size >= MAX_RESOLUTIONS) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(code: Int) {
                    if (!current(session) || resolutions[key] !== this) return
                    resolutions.remove(key)
                    failed("resolve", code)
                }
                override fun onServiceInfoCallbackUnregistered() = Unit
                override fun onServiceLost() {
                    if (current(session) && resolutions[key] === this && endpoints.remove(key) != null) publish()
                }
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    if (current(session) && resolutions[key] === this) {
                        resolved(kind, key, updated.hostAddresses.mapNotNull { it.hostAddress }, updated.port)
                    }
                }
            }
            resolutions[key] = callback
            try { manager.registerServiceInfoCallback(info, executor, callback) }
            catch (e: RuntimeException) {
                resolutions.remove(key)
                resolveException(e)
            }
        }
        override fun remove(key: String) {
            resolutions.remove(key)?.let { runCatching { manager.unregisterServiceInfoCallback(it) } }
        }
        override fun close() {
            val previous = resolutions.values.toList()
            resolutions.clear()
            previous.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
        }
    }

    private data class ResolveRequest(val kind: DexService, val info: NsdServiceInfo, val session: Int)

    /** Android30–33 allows one outstanding resolve. There is no supported cancellation API. */
    @Suppress("DEPRECATION")
    private inner class LegacyResolver : Resolver {
        private val queue = DexResolveQueue<ResolveRequest>(MAX_RESOLUTIONS)
        override fun track(kind: DexService, info: NsdServiceInfo, session: Int) {
            queue.offer(key(kind, info), ResolveRequest(kind, info, session))
            pump()
        }
        override fun remove(key: String) { queue.remove(key) }
        override fun close() { queue.clear() }

        private fun pump() {
            val ticket = queue.take() ?: return
            val request = ticket.value
            val timeout = Runnable {
                if (queue.expire(ticket) && current(request.session)) {
                    error = "Автопоиск порта не ответил. Введите IP и порты из настроек отладки вручную."
                    publish()
                }
                // Keep the slot until its terminal callback: another concurrent resolve
                // would fail on Android30. No retry loop or uncancellable work is created.
            }
            val callback = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    executor.execute {
                        timer.removeCallbacks(timeout)
                        if (queue.complete(ticket) != null && current(request.session)) failed("resolve", code)
                        pump()
                    }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    executor.execute {
                        timer.removeCallbacks(timeout)
                        if (queue.complete(ticket) != null && current(request.session)) {
                            resolved(request.kind, ticket.key, listOfNotNull(info.host?.hostAddress), info.port)
                        }
                        pump()
                    }
                }
            }
            timer.postDelayed(timeout, RESOLVE_TIMEOUT_MS)
            try { manager.resolveService(request.info, callback) }
            catch (e: RuntimeException) {
                timer.removeCallbacks(timeout)
                if (queue.complete(ticket) != null && current(request.session)) resolveException(e)
                // Defer the next bounded attempt instead of recursing through queued failures.
                executor.execute { pump() }
            }
        }
    }

    private fun resolved(kind: DexService, key: String, addresses: List<String>, port: Int) {
        val own = selectOwnDexEndpoint(addresses, port, DexAccess::acceptsHost)
        if (own == null) {
            if (endpoints.remove(key) != null) publish()
            return
        }
        if (endpoints[key] == (kind to own)) return
        endpoints.remove(key)
        endpoints[key] = kind to own
        AppLog.i("DEX discovery local ${kind.name.lowercase()} host=${own.host} port=${own.port}")
        publish()
    }
    private fun resolveException(error: RuntimeException) {
        AppLog.i("DEX discovery resolve failed ${error.javaClass.simpleName}")
        this.error = "Не удалось определить порт автоматически. Введите данные из настроек отладки вручную."
        publish()
    }
    private fun key(kind: DexService, info: NsdServiceInfo): String {
        val network = if (Build.VERSION.SDK_INT >= 33) networkKey(info) else "legacy"
        return "${kind.name}:$network:${info.serviceName}"
    }
    @RequiresApi(33) private fun networkKey(info: NsdServiceInfo) = info.network?.networkHandle?.toString() ?: "default"
    private fun current(session: Int) = active && generation == session
    private fun failed(stage: String, code: Int) {
        AppLog.i("DEX discovery $stage failed code=$code")
        error = "Автопоиск не завершён (код $code). Можно ввести IP и порты вручную."
        publish()
    }
    private fun publish() {
        if (!active) return
        onUpdate(
            endpoints.values.lastOrNull { it.first == DexService.PAIRING }?.second,
            endpoints.values.lastOrNull { it.first == DexService.CONNECT }?.second,
            error,
        )
    }

    override fun close() {
        active = false
        generation++
        discoveries.toList().forEach { runCatching { manager.stopServiceDiscovery(it) } }
        discoveries.clear()
        resolver.close()
        endpoints.clear()
    }
    private companion object {
        const val MAX_RESOLUTIONS = 16
        const val RESOLVE_TIMEOUT_MS = 10_000L
    }
}
