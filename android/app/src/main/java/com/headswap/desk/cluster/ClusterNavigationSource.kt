package com.headswap.desk.cluster

import android.content.Context
import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.headswap.desk.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The system binds the listener only after a user's explicit notification-access grant. */
class ClusterNavigationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        source?.refresh(this)
    }
    override fun onListenerDisconnected() {
        if (connected === this) { connected = null; source?.disconnected() }
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        if (connected === this) { connected = null; source?.disconnected() }
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (connected === this && accepts(sbn)) source?.posted(sbn)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (connected === this && accepts(sbn)) source?.removed(sbn.key)
    }
    companion object {
        @Volatile internal var source: ClusterNavigationSource? = null
        @Volatile internal var connected: ClusterNavigationListener? = null
        internal fun accepts(sbn: StatusBarNotification) = sbn.user == android.os.Process.myUserHandle() &&
            sbn.packageName in YandexGuidanceCard.PACKAGES
    }
}

/** A single CarLife session owns these hints. No route or notification text is stored on disk. */
internal class ClusterNavigationSource(
    context: Context,
    private val interest: () -> GuidanceInterest,
    requestForeground: RequestForegroundGuidance? = null,
    private val emit: (ClusterNavigationHint?) -> Unit,
) {
    private val cards = YandexGuidanceCard(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "headswap-route-guidance").apply { isDaemon = true } }
    // Touched only on worker. Ignore unrelated notifications, retaining the latest usable manoeuvre.
    private val known = LinkedHashMap<String, ClusterNavigationHint>()
    @Volatile private var closed = false
    private val foregroundEnabled = requestForeground != null
    private var appliedInterest: GuidanceInterest? = null
    private var periodic: ScheduledFuture<*>? = null
    private var lastStatus = ""
    private val checkedForeground = requestForeground?.let { request -> { callback: (ForegroundGuidance) -> Unit ->
        val listener = ClusterNavigationListener.connected
        val token = interest()
        if (closed || listener == null || token.mode == GuidanceInterest.Mode.NONE) callback(ForegroundGuidance.Unavailable)
        else request { result ->
            if (!closed && ClusterNavigationListener.connected === listener && interest() === token) callback(result)
        }
    } }
    private val coordinator = RouteGuidanceCoordinator(checkedForeground, ::onWorker,
        { delay, action ->
            try { worker.schedule({ if (!closed) action() }, delay, TimeUnit.MILLISECONDS) }
            catch (_: RejectedExecutionException) { /* the session closed while scheduling a retry */ }
        },
    ) { hint ->
        val listener = ClusterNavigationListener.connected
        val token = appliedInterest
        main.post {
            if (!closed && ClusterNavigationListener.source === this && interest() === token &&
                (hint == null || (listener != null && ClusterNavigationListener.connected === listener))) emit(hint)
        }
    }

    fun start() {
        check(!closed)
        ClusterNavigationListener.source = this
        val listener = ClusterNavigationListener.connected
        if (listener != null) refresh(listener) else disconnected()
    }
    fun subscriptionChanged() = onWorker { reconcile() }
    internal fun refresh(listener: ClusterNavigationListener) {
        // NLS cannot request an app-specific snapshot. Filter by package before accessing any payload.
        replace(runCatching { listener.activeNotifications.orEmpty().filter { ClusterNavigationListener.accepts(it) } }
            .getOrElse { emptyList() })
    }
    internal fun replace(snapshot: List<StatusBarNotification>) = update {
        known.clear()
        snapshot.sortedBy { it.postTime }.forEach(::read)
    }
    internal fun posted(sbn: StatusBarNotification) =
        update(bootstrap = sbn.notification.category == Notification.CATEGORY_NAVIGATION) { read(sbn) }
    internal fun removed(key: String) = update { known.remove(key) }
    internal fun disconnected() = onWorker {
        known.clear()
        reconcile()
    }

    private fun read(sbn: StatusBarNotification) {
        // An actionable update may replace the previous manoeuvre under the same notification key.
        known.remove(sbn.key)
        val hint = runCatching { cards.parse(sbn.packageName, sbn.notification) }.getOrNull()
        if (hint != null) known[sbn.key] = hint
    }
    private fun update(bootstrap: Boolean = false, action: () -> Unit) {
        val listener = ClusterNavigationListener.connected
        onWorker {
            if (listener !== ClusterNavigationListener.connected) return@onWorker
            if (listener == null) known.clear() else action()
            reconcile(bootstrap)
        }
    }

    private fun reconcile(bootstrap: Boolean = false) {
        val token = interest()
        val changed = appliedInterest !== token
        if (changed) {
            coordinator.interestChanged(appliedInterest?.mode == GuidanceInterest.Mode.REQUESTED && token.mode == GuidanceInterest.Mode.ACTIVE)
            appliedInterest = token
        }
        val connected = ClusterNavigationListener.connected != null
        val canSample = connected && foregroundEnabled && token.mode != GuidanceInterest.Mode.NONE
        val hint = known.values.lastOrNull()
        val status = "listener=${if (connected) "connected" else "unavailable"} notification=${if (hint != null) "maneuver" else "no maneuver"}"
        if (status != lastStatus) { lastStatus = status; AppLog.i("CLUSTER navigation $status", false) }
        coordinator.notifications(hint, canSample)
        if (canSample && token.mode == GuidanceInterest.Mode.ACTIVE) {
            if (periodic == null) periodic = worker.scheduleWithFixedDelay({
                if (!closed && ClusterNavigationListener.connected != null && interest().mode == GuidanceInterest.Mode.ACTIVE)
                    coordinator.poll()
            }, 2, 2, TimeUnit.SECONDS)
        } else {
            periodic?.cancel(false)
            periodic = null
            // Some HUs send START only after a maneuver raises NAVI=1. Bootstrap from a real
            // subscribe request or guidance notification; there is no timer before START.
            if (canSample && token.mode == GuidanceInterest.Mode.REQUESTED && bootstrap && !changed) coordinator.poll()
        }
    }
    private fun onWorker(action: () -> Unit) {
        if (closed) return
        try { worker.execute { if (!closed) action() } }
        catch (_: RejectedExecutionException) { /* session already stopped */ }
    }
    fun close() {
        closed = true
        if (ClusterNavigationListener.source === this) ClusterNavigationListener.source = null
        worker.shutdownNow()
    }
}
