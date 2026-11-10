package com.headswap.desk.cluster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import com.headswap.desk.AppLog
import com.headswap.desk.embed.EmbeddedSessionRegistry

/** Version-pinned local experiment. A different signed APK cannot inject navigation into CarLife. */
internal class NavigationAdapterClient(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val policy = NavigationAdapterPolicy()
    @Volatile private var binder: IBinder? = null
    @Volatile private var closed = false
    @Volatile private var selectedPackage: String? = null
    private var bound = false // main thread only
    private var lastReport = ""
    private var lastReportAt = 0L
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!closed && name.packageName == selectedPackage && trusted(name.packageName)) binder = service
        }
        override fun onServiceDisconnected(name: ComponentName) { if (name.packageName == selectedPackage) binder = null }
        override fun onBindingDied(name: ComponentName) { if (name.packageName == selectedPackage) unbind() }
        override fun onNullBinding(name: ComponentName) { if (name.packageName == selectedPackage) unbind() }
    }
    data class Reading(val provider: String, val display: Int, val lease: Any, val hint: ClusterNavigationHint?)

    private fun trusted(pkg: String) = runCatching {
        app.packageManager.hasSigningCertificate(pkg, CERT, PackageManager.CERT_INPUT_SHA256)
    }.getOrDefault(false)

    /** Called by the session worker. Both endpoints use elapsedRealtime from the same device. */
    fun read(includeRoad: Boolean): Reading? {
        if (closed) return null
        val provider = PACKAGES.firstOrNull { trusted(it) && EmbeddedSessionRegistry.adapterGuidanceScope(it).isNotEmpty() }
        if (provider == null) { main.post { unbind() }; return null }
        val scope = EmbeddedSessionRegistry.adapterGuidanceScope(provider)
        val remote = binder.takeIf { selectedPackage == provider }
        if (remote == null) {
            main.post {
                if (!closed && trusted(provider)) {
                    if (selectedPackage != provider) unbind()
                    if (!bound) {
                        selectedPackage = provider
                        bound = runCatching {
                            app.bindService(Intent().setComponent(ComponentName(provider, SERVICE)), connection, Context.BIND_AUTO_CREATE)
                        }.getOrDefault(false)
                    }
                }
            }
            return null
        }
        val input = Parcel.obtain(); val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(DESCRIPTOR); input.writeIntArray(scope.keys.toIntArray()); input.writeInt(if (includeRoad) 1 else 0)
            if (!remote.transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0)) return null
            output.readException()
            val b = output.readBundle(javaClass.classLoader) ?: return null
            val sample = NavigationAdapterSample(b.getInt("protocol"), b.getString("session").orEmpty(), b.getLong("sequence"),
                b.getLong("sampleTime"), b.getInt("display", -1), b.getString("state").orEmpty(), b.getLong("routeEpoch"),
                b.getString("step").orEmpty(), b.getString("action").orEmpty(), b.getInt("distance", -1), b.getString("road").orEmpty())
            val now = SystemClock.elapsedRealtime()
            val current = EmbeddedSessionRegistry.adapterGuidanceScope(provider)
            val lease = scope[sample.display] ?: return null
            if (closed || binder !== remote || selectedPackage != provider || current[sample.display] != lease || !policy.accepts(sample, now, scope.keys)) return null
            val hint = policy.hint(sample, includeRoad)
            // Boolean comparison only. Never write a street, route id, or destination into logs.
            val report = "state=${sample.state} action=${sample.action.take(32)} distance=${sample.distance} display=${sample.display}" +
                " uiCard=${b.getBoolean("uiCardPresent")} uiRoadField=${b.getBoolean("uiRoadFieldPresent")}" +
                " uiRoad=${b.getBoolean("uiRoadPresent")} uiVisibility=${b.getInt("uiRoadVisibility", -1)}" +
                " modelRoad=${sample.road.isNotBlank()} roadEnabled=$includeRoad error=${b.getString("errorType").orEmpty().take(48)}"
            if (report != lastReport || now - lastReportAt >= 10_000) {
                lastReport = report; lastReportAt = now; AppLog.i("CLUSTER adapter $report", false)
            }
            return Reading(provider, sample.display, lease, hint)
        } catch (e: Exception) {
            if (binder === remote) binder = null
            main.post { unbind() }
            val report = "unavailable ${e.javaClass.simpleName}"
            if (report != lastReport) { lastReport = report; AppLog.i("CLUSTER adapter $report", false) }
            return null
        } finally { input.recycle(); output.recycle() }
    }
    fun isCurrent(reading: Reading) = !closed && EmbeddedSessionRegistry.adapterGuidanceScope(reading.provider)[reading.display] == reading.lease
    fun idle() { main.post { unbind() } }
    private fun unbind() {
        binder = null
        if (bound) { bound = false; runCatching { app.unbindService(connection) } }
        selectedPackage = null
    }
    override fun close() { closed = true; main.post { unbind() } }
    companion object {
        private val PACKAGES = listOf("ru.yandex.yandexnavi", "com.headswap.navigator.experimental")
        private const val SERVICE = "com.headswap.navbridge.GuidanceService"
        private const val DESCRIPTOR = "com.headswap.navigation.Source.v1"
        private val CERT = "a7cd30bc9026f0e544c67e48ef52af5e8d5dd1bcd784fa7faeb9f433b185f8a0".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
