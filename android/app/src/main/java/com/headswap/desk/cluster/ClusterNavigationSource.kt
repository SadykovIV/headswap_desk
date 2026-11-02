package com.headswap.desk.cluster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
    private val checkedForeground = requestForeground?.let { request -> { callback: (ForegroundGuidance) -> Unit ->
        val listener = ClusterNavigationListener.connected
        if (closed || listener == null) callback(ForegroundGuidance.Unavailable)
        else request { result ->
            callback(if (!closed && ClusterNavigationListener.connected === listener) result else ForegroundGuidance.Unavailable)
        }
    } }
    private val coordinator = RouteGuidanceCoordinator(checkedForeground, ::onWorker,
        { delay, action -> worker.schedule(action, delay, TimeUnit.MILLISECONDS) },
    ) { hint ->
        val listener = ClusterNavigationListener.connected
        main.post {
            if (!closed && ClusterNavigationListener.source === this &&
                (hint == null || (listener != null && ClusterNavigationListener.connected === listener))) emit(hint)
        }
    }

    fun start() {
        check(!closed)
        ClusterNavigationListener.source = this
        val listener = ClusterNavigationListener.connected
        if (listener != null) refresh(listener) else disconnected()
        if (foregroundEnabled) worker.scheduleWithFixedDelay({
            if (!closed && ClusterNavigationListener.connected != null) coordinator.poll()
        }, 2, 2, TimeUnit.SECONDS)
    }
    internal fun refresh(listener: ClusterNavigationListener) {
        // NLS cannot request an app-specific snapshot. Filter by package before accessing any payload.
        replace(runCatching { listener.activeNotifications.orEmpty().filter { ClusterNavigationListener.accepts(it) } }
            .getOrElse { emptyList() })
    }
    internal fun replace(snapshot: List<StatusBarNotification>) = update {
        known.clear()
        snapshot.sortedBy { it.postTime }.forEach(::read)
    }
    internal fun posted(sbn: StatusBarNotification) = update { read(sbn) }
    internal fun removed(key: String) = update { known.remove(key) }
    internal fun disconnected() = onWorker {
        known.clear()
        coordinator.notifications(null, canReadForeground = false)
    }

    private fun read(sbn: StatusBarNotification) {
        // An actionable update may replace the previous manoeuvre under the same notification key.
        known.remove(sbn.key)
        val hint = runCatching { cards.parse(sbn.packageName, sbn.notification) }.getOrNull()
        if (hint != null) known[sbn.key] = hint
    }
    private fun update(action: () -> Unit) = onWorker {
        action()
        val connected = ClusterNavigationListener.connected != null
        if (!connected) known.clear()
        coordinator.notifications(known.values.lastOrNull(), canReadForeground = connected)
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
