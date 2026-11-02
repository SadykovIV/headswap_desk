package com.headswap.desk.cluster

import android.app.KeyguardManager
import android.content.Context
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.ShellPanelClient
import com.headswap.desk.embed.EmbeddedSessionRegistry
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Owned by one CarLife session. The physical display never participates in a read. */
internal class CarLifeForegroundGuidance(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "headswap-hud-window").apply { isDaemon = true } }
    @Volatile private var closed = false

    fun request(callback: (ForegroundGuidance) -> Unit) {
        if (!enabled()) { callback(ForegroundGuidance.Unavailable); return }
        try {
            worker.execute {
                val result = try { read() }
                catch (_: Exception) { ForegroundGuidance.Unavailable }
                catch (_: LinkageError) { ForegroundGuidance.Unavailable }
                if (!closed) callback(result)
            }
        } catch (_: RejectedExecutionException) { callback(ForegroundGuidance.Unavailable) }
    }

    private fun enabled() = !closed && ClusterNavigationListener.connected != null && !keyguard.isKeyguardLocked

    private fun read(): ForegroundGuidance {
        val prefs = DexPrefs(app)
        for (id in EmbeddedSessionRegistry.guidanceDisplayIds()) {
            if (!enabled()) break
            val sample = when {
                prefs.useDelegated -> ShellPanelClient.readGuidance(id)
                prefs.useShizuku -> EmbeddedSessionRegistry.readShizukuGuidance(id)
                else -> return ForegroundGuidance.Unavailable
            }
            if (!enabled() || id !in EmbeddedSessionRegistry.guidanceDisplayIds()) continue
            when (sample) {
                is ShellManeuverSample.Card -> {
                    val labels = ForegroundYandexManeuver.labels(app, sample.packageName, sample.languageTags)
                    val hint = ForegroundYandexManeuver.parse(sample.arrowLabel, sample.distance, sample.unit,
                        road = null, viaPointVisible = false, turnLabels = labels)
                    if (hint != null) return ForegroundGuidance.Maneuver(hint)
                }
                ShellManeuverSample.VisibleWithoutHint -> return ForegroundGuidance.VisibleWithoutManeuver
                ShellManeuverSample.Unavailable -> Unit
            }
        }
        return ForegroundGuidance.Unavailable
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
    }
}
