package com.headswap.desk.cluster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

/** The system binds the listener only after a user's explicit notification-access grant. */
class ClusterNavigationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        source?.refresh(this)
    }
    override fun onListenerDisconnected() {
        if (connected === this) { connected = null; source?.replace(emptyList()) }
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        if (connected === this) { connected = null; source?.replace(emptyList()) }
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
internal class ClusterNavigationSource(context: Context, private val emit: (ClusterNavigationHint?) -> Unit) {
    private val cards = YandexGuidanceCard(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "headswap-route-notifications").apply { isDaemon = true } }
    // Touched only on worker. Ignore unrelated notifications, retaining the latest usable manoeuvre.
    private val known = LinkedHashMap<String, ClusterNavigationHint>()
    @Volatile private var closed = false
    private var published: ClusterNavigationHint? = null
    private var initial = true

    fun start() {
        check(!closed)
        ClusterNavigationListener.source = this
        val listener = ClusterNavigationListener.connected
        if (listener != null) refresh(listener) else replace(emptyList())
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

    private fun read(sbn: StatusBarNotification) {
        // An actionable update may replace the previous manoeuvre under the same notification key.
        known.remove(sbn.key)
        val hint = runCatching { cards.parse(sbn.packageName, sbn.notification) }.getOrNull()
        if (hint != null) known[sbn.key] = hint
    }
    private fun update(action: () -> Unit) {
        if (closed) return
        val listener = ClusterNavigationListener.connected
        try { worker.execute {
            if (closed) return@execute
            if (listener != null && listener !== ClusterNavigationListener.connected) return@execute
            if (listener == null) known.clear() else action()
            val next = known.values.lastOrNull()
            if (initial || next != published) {
                initial = false
                published = next
                main.post {
                    if (!closed && ClusterNavigationListener.source === this &&
                        ClusterNavigationListener.connected === listener) emit(next)
                }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* session already stopped */ }
    }
    fun close() {
        closed = true
        if (ClusterNavigationListener.source === this) ClusterNavigationListener.source = null
        worker.shutdownNow()
    }
}
