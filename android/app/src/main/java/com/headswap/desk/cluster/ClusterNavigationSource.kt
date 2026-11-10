package com.headswap.desk.cluster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.headswap.desk.notify.HuNotifications
import com.headswap.desk.AppLog
import com.headswap.desk.aa.AaPrefs
import com.headswap.desk.embed.EmbeddedSessionRegistry
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** The system binds the listener only after a user's explicit notification-access grant. */
class ClusterNavigationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        source?.refresh(this)
        HuNotifications.listenerConnected()
    }
    override fun onListenerDisconnected() {
        if (connected === this) { connected = null; source?.disconnected(); HuNotifications.listenerDisconnected() }
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        if (connected === this) { connected = null; source?.disconnected(); HuNotifications.listenerDisconnected() }
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (connected === this) {
            if (accepts(sbn)) source?.posted(sbn)
            HuNotifications.posted(sbn)
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (connected === this) {
            if (accepts(sbn)) source?.removed(sbn.key)
            HuNotifications.removed(sbn)
        }
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
    private val hudPrefs = AaPrefs(context.applicationContext)
    private val adapter = NavigationAdapterClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "headswap-route-guidance").apply { isDaemon = true } }
    // Touched only on worker. Ignore unrelated notifications, retaining the latest usable manoeuvre.
    private val known = LinkedHashMap<String, ClusterNavigationHint>()
    @Volatile private var closed = false
    @Volatile private var roadRevision = 0L
    private val foregroundEnabled = requestForeground != null
    private var appliedInterest: GuidanceInterest? = null
    private var lastStatus = ""
    private var fallbackHint: ClusterNavigationHint? = null
    private var fallbackListener: ClusterNavigationListener? = null
    private var adapterReading: NavigationAdapterClient.Reading? = null
    @Volatile private var deliveryRevision = 0L
    private val checkedForeground = requestForeground?.let { request -> { callback: (ForegroundGuidance) -> Unit ->
        val listener = ClusterNavigationListener.connected
        val token = interest()
        val revision = roadRevision
        if (closed || listener == null || token.mode == GuidanceInterest.Mode.NONE ||
            EmbeddedSessionRegistry.guidanceDisplayIds().isEmpty()) callback(ForegroundGuidance.Unavailable)
        else request { result ->
            if (!closed && ClusterNavigationListener.connected === listener && interest() === token) {
                callback(if (result is ForegroundGuidance.Maneuver && (!hudPrefs.metaNaviStreetNames || revision != roadRevision))
                    result.copy(hint = result.hint.copy(road = "")) else result)
            }
        }
    } }
    private val coordinator = RouteGuidanceCoordinator(checkedForeground, ::onWorker,
        { delay, action ->
            try { worker.schedule({ if (!closed) action() }, delay, TimeUnit.MILLISECONDS) }
            catch (_: RejectedExecutionException) { /* the session closed while scheduling a retry */ }
        },
    ) { hint ->
        fallbackHint = hint
        fallbackListener = ClusterNavigationListener.connected
        deliver()
    }
    private val polling = ForegroundGuidancePoller(
        schedule = { interval, tick ->
            val future = worker.scheduleWithFixedDelay(tick, interval, interval, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { future.cancel(false) }
            cancel
        },
        canRead = { token -> !closed && interest() === token && ClusterNavigationListener.connected != null },
        poll = coordinator::poll,
    )

    fun start() {
        check(!closed)
        ClusterNavigationListener.source = this
        val listener = ClusterNavigationListener.connected
        if (listener != null) refresh(listener) else disconnected()
        worker.scheduleWithFixedDelay({ if (!closed) pollAdapter() }, 0, 500, TimeUnit.MILLISECONDS)
    }
    fun subscriptionChanged() = onWorker { reconcile() }
    fun streetNamesChanged() {
        roadRevision++
        onWorker {
            known.replaceAll { _, value -> value.copy(road = "") }
            adapterReading = adapterReading?.let { it.copy(hint = it.hint?.copy(road = "")) }
            coordinator.streetNamesChanged()
            reconcile()
            pollAdapter()
        }
        // Re-read active allowed notifications as well: Android 13–15 has no Assist reader.
        ClusterNavigationListener.connected?.let(::refresh)
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
        reconcile()
    }

    private fun read(sbn: StatusBarNotification) {
        // An actionable update may replace the previous manoeuvre under the same notification key.
        known.remove(sbn.key)
        val hint = runCatching { cards.parse(sbn.packageName, sbn.notification, hudPrefs.metaNaviStreetNames) }.getOrNull()
        if (hint != null) known[sbn.key] = if (hudPrefs.metaNaviStreetNames) hint else hint.copy(road = "")
    }
    private fun update(action: () -> Unit) {
        val listener = ClusterNavigationListener.connected
        onWorker {
            if (listener !== ClusterNavigationListener.connected) return@onWorker
            if (listener == null) known.clear() else action()
            reconcile()
        }
    }

    private fun reconcile() {
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
        polling.update(if (canSample) token else null)
    }

    private fun pollAdapter() {
        val token = interest()
        if (token.mode == GuidanceInterest.Mode.NONE) {
            adapterReading = null
            adapter.idle()
        } else {
            val revision = roadRevision
            val result = adapter.read(hudPrefs.metaNaviStreetNames)
            if (closed || interest() !== token || revision != roadRevision) return
            // While this adapter still owns the visible pane, failure clears guidance. Do not revive
            // an unrelated old notification or merge its street into the adapter's next manoeuvre.
            adapterReading = result ?: adapterReading?.takeIf(adapter::isCurrent)?.copy(hint = null)
        }
        deliver()
    }

    private fun deliver() {
        val token = interest()
        val revision = roadRevision
        val delivery = ++deliveryRevision
        val reading = adapterReading
        val listener = fallbackListener
        val hint = when {
            token.mode == GuidanceInterest.Mode.NONE -> null
            reading != null -> reading.hint
            listener != null && listener === ClusterNavigationListener.connected -> fallbackHint
            else -> null
        }
        main.post {
            if (closed || ClusterNavigationListener.source !== this || interest() !== token || deliveryRevision != delivery) return@post
            val valid = if (reading != null) adapter.isCurrent(reading)
                else hint == null || (listener != null && listener === ClusterNavigationListener.connected)
            emit(if (!valid) null else if (hudPrefs.metaNaviStreetNames && revision == roadRevision) hint else hint?.copy(road = ""))
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
        adapter.close()
        worker.shutdownNow()
    }
}
