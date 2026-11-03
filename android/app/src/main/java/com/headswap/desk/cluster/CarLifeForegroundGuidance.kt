package com.headswap.desk.cluster

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.headswap.desk.AppLog
import com.headswap.desk.aa.AaPrefs
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.DelegatedDesktopAccess
import com.headswap.desk.dex.ShellPanelClient
import com.headswap.desk.embed.EmbeddedSessionRegistry
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Owned by one CarLife session. The physical display never participates in a read. */
internal class CarLifeForegroundGuidance(context: Context, private val interest: () -> GuidanceInterest) : AutoCloseable {
    private val app = context.applicationContext
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val listener = ComponentName(app, ClusterNavigationListener::class.java)
    private val hudPrefs = AaPrefs(app)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "headswap-hud-window").apply { isDaemon = true } }
    @Volatile private var closed = false
    private var lastState = ""
    private var lastBackend = ""

    companion object {
        const val TASK_ASSIST_PERMISSION = "android.permission.GET_TOP_ACTIVITY_INFO"

        // Only the existing, explicitly activated process can borrow the task read, and
        // only if the user enabled both HUD and notification access. Do not persist a grant.
        fun requestedForActivation(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 36 && AaPrefs(context).metaNavi && runCatching {
                context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
                    ComponentName(context, ClusterNavigationListener::class.java))
            }.getOrDefault(false)
    }

    fun request(callback: (ForegroundGuidance) -> Unit) {
        val token = interest()
        if (!enabled(token)) { callback(ForegroundGuidance.Unavailable); return }
        try {
            worker.execute {
                val result = try { read(token) }
                catch (_: Exception) { ForegroundGuidance.Unavailable }
                catch (_: LinkageError) { ForegroundGuidance.Unavailable }
                if (enabled(token)) {
                    val state = when (result) {
                        is ForegroundGuidance.Maneuver -> "maneuver"
                        ForegroundGuidance.VisibleWithoutManeuver -> "no maneuver"
                        ForegroundGuidance.Unavailable -> "unavailable"
                    }
                    if (state != lastState) { lastState = state; AppLog.i("CLUSTER navigation foreground=$state", false) }
                    callback(result)
                } else if (!closed) callback(ForegroundGuidance.Unavailable)
            }
        } catch (_: RejectedExecutionException) { callback(ForegroundGuidance.Unavailable) }
    }

    private fun enabled(token: GuidanceInterest) = !closed && token.mode != GuidanceInterest.Mode.NONE &&
        interest() === token && hudPrefs.metaNavi && ClusterNavigationListener.connected != null &&
        runCatching { !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked &&
            notifications.isNotificationListenerAccessGranted(listener) }.getOrDefault(false)

    private fun read(token: GuidanceInterest): ForegroundGuidance {
        val prefs = DexPrefs(app)
        var emptyRoute = false
        for (id in EmbeddedSessionRegistry.guidanceDisplayIds()) {
            if (id <= 0 || !enabled(token)) continue
            val sample = when {
                prefs.useDelegated -> readActivated(id, token)
                prefs.useShizuku -> EmbeddedSessionRegistry.readShizukuGuidance(id)
                else -> return ForegroundGuidance.Unavailable
            }
            if (!enabled(token) || id !in EmbeddedSessionRegistry.guidanceDisplayIds()) continue
            when (sample) {
                is ShellManeuverSample.Card -> {
                    val labels = ForegroundYandexManeuver.labels(app, sample.packageName, sample.languageTags)
                    val hint = ForegroundYandexManeuver.parse(sample.arrowLabel, sample.distance, sample.unit,
                        road = null, viaPointVisible = false, turnLabels = labels)
                    if (hint != null) return ForegroundGuidance.Maneuver(hint)
                }
                ShellManeuverSample.VisibleWithoutHint -> emptyRoute = true
                ShellManeuverSample.Unavailable -> Unit
            }
        }
        return if (emptyRoute) ForegroundGuidance.VisibleWithoutManeuver else ForegroundGuidance.Unavailable
    }

    private fun readActivated(id: Int, token: GuidanceInterest): ShellManeuverSample {
        if (ShellPanelClient.isConnected) {
            reportBackend("helper")
            return ShellPanelClient.readGuidance(id)
        }
        // Switching USB from the activation computer to the car can retire the entire
        // ADB shell process group while the authorized HeadSwap process is still alive.
        if (!DelegatedDesktopAccess.isReady(app) ||
            app.checkSelfPermission(TASK_ASSIST_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            reportBackend("unavailable")
            return ShellManeuverSample.Unavailable
        }
        reportBackend("activation")
        return EmbeddedSessionRegistry.readDelegatedGuidance(id) {
            if (!enabled(token)) ShellManeuverSample.Unavailable
            else ShellYandexGuidance.read(id, Process.myUid(), Process.myUid() / 100000)
        }
    }

    private fun reportBackend(name: String) {
        if (lastBackend != name) { lastBackend = name; AppLog.i("CLUSTER navigation reader=$name", false) }
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
    }
}
