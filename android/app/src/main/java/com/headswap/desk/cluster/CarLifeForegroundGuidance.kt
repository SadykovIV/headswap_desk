package com.headswap.desk.cluster

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import com.headswap.desk.dex.DesktopDisplayIsolation
import com.headswap.desk.dex.VdmHostLink
import com.headswap.desk.dex.ShellDisplayIme
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
        runCatching { notifications.isNotificationListenerAccessGranted(listener) }.getOrDefault(false)

    private fun read(token: GuidanceInterest): ForegroundGuidance {
        val prefs = DexPrefs(app)
        var emptyRoute = false
        for (id in EmbeddedSessionRegistry.guidanceDisplayIds()) {
            if (id <= 0 || !enabled(token) || !displayEligible(id)) continue
            val includeRoad = hudPrefs.metaNaviStreetNames
            val sample = when {
                prefs.useDelegated -> readActivated(id, token, includeRoad)
                prefs.useShizuku -> EmbeddedSessionRegistry.readShizukuGuidance(id, includeRoad)
                else -> return ForegroundGuidance.Unavailable
            }
            if (!enabled(token) || !displayEligible(id) || id !in EmbeddedSessionRegistry.guidanceDisplayIds()) continue
            when (sample) {
                is ShellManeuverSample.Card -> {
                    val labels = ForegroundYandexManeuver.labels(app, sample.packageName, sample.languageTags)
                    val hint = ForegroundYandexManeuver.parse(sample.arrowLabel, sample.distance, sample.unit,
                        road = sample.road.takeIf { hudPrefs.metaNaviStreetNames }, viaPointVisible = false, turnLabels = labels)
                    if (hint != null) return ForegroundGuidance.Maneuver(hint)
                }
                ShellManeuverSample.VisibleWithoutHint -> emptyRoute = true
                ShellManeuverSample.Unavailable -> Unit
            }
        }
        return if (emptyRoute) ForegroundGuidance.VisibleWithoutManeuver else ForegroundGuidance.Unavailable
    }

    private fun readActivated(id: Int, token: GuidanceInterest, includeRoad: Boolean): ShellManeuverSample {
        val nativeOwnership = VdmHostLink.captureOwnedDisplay(id)
        if (nativeOwnership == null && ShellPanelClient.isConnected) {
            reportBackend("helper")
            return ShellPanelClient.readGuidance(id, includeRoad)
        }
        // Switching USB from the activation computer to the car can retire the entire
        // ADB shell process group while the authorized HeadSwap process is still alive.
        if (!DelegatedDesktopAccess.isReady(app) ||
            app.checkSelfPermission(TASK_ASSIST_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            reportBackend("unavailable")
            return ShellManeuverSample.Unavailable
        }
        reportBackend("activation")
        return EmbeddedSessionRegistry.readDelegatedGuidanceScoped(id) { requireCurrentPane ->
            if (!enabled(token) || !displayEligible(id)) ShellManeuverSample.Unavailable
            else ShellYandexGuidance.readDelegated(id, Process.myUid() / 100000, includeRoad) {
                check(enabled(token) && displayEligible(id)) { "Guidance session ended" }
                requireCurrentPane()
                if (nativeOwnership != null) {
                    nativeOwnership()
                    ShellDisplayIme.requireOwned(id, 2000)
                } else ShellDisplayIme.requireOwned(id, Process.myUid())
            }
        }
    }

    private fun displayEligible(id: Int): Boolean = runCatching {
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) true
        else id in EmbeddedSessionRegistry.guidanceDisplayIds() &&
            app.getSystemService(DisplayManager::class.java).getDisplay(id)?.let {
                DesktopDisplayIsolation.confirmedIndependence(it) == true
            } == true
    }.getOrDefault(false)

    private fun reportBackend(name: String) {
        if (lastBackend != name) { lastBackend = name; AppLog.i("CLUSTER navigation reader=$name", false) }
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
    }
}
