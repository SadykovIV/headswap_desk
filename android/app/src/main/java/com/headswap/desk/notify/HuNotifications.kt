package com.headswap.desk.notify

import android.app.Notification
import android.app.PendingIntent
import java.util.concurrent.ConcurrentHashMap
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import com.headswap.desk.cluster.ClusterNavigationListener

/** Ephemeral current-session model. No PendingIntent, RemoteViews, images or notification text is persisted. */
internal object HuNotifications {
    private val main = Handler(Looper.getMainLooper())
    private var accepting = false
    private var app: Context? = null
    internal data class Action(val key: String, val packageName: String, val postTime: Long, val intent: PendingIntent, val epoch: Any)
    private val actions = ConcurrentHashMap<String, Action>()
    @Volatile private var epoch = Any()
    fun action(key: String, packageName: String): Action? = actions[key]?.takeIf { it.packageName == packageName && current(it) }
    fun current(action: Action): Boolean = HuNotificationFeature.AVAILABLE && action.epoch === epoch && actions[action.key] === action
    private fun clearActions() { epoch = Any(); actions.clear(); HuNotificationLaunch.clear() }
    @Volatile var items: List<NotifEntry> = emptyList()
        private set
    var generation = 0L
        private set
    @Volatile var connected = false
        private set

    fun session(context: Context, active: Boolean) {
        val enabled = HuNotificationFeature.enabled(active)
        if (accepting == enabled && HuNotificationFeature.AVAILABLE) return
        accepting = enabled
        app = if (enabled) context.applicationContext else null
        clearActions()
        replace(emptyList())
        if (enabled) resync()
    }
    fun listenerConnected() = onMain {
        connected = true
        if (accepting) resync()
    }
    fun listenerDisconnected() = onMain {
        connected = false
        clearActions()
        replace(emptyList())
    }
    fun posted(sbn: StatusBarNotification) = onMain {
        if (!accepting) return@onMain
        actions.remove(sbn.key)
        val parsed = runCatching { parse(sbn) }.getOrNull()
        replace(if (parsed == null) items.filterNot { it.key == sbn.key } else NotificationsLogic.upsert(items, parsed))
    }
    fun removed(sbn: StatusBarNotification) = onMain {
        if (sbn.user == Process.myUserHandle()) { actions.remove(sbn.key); replace(items.filterNot { it.key == sbn.key }) }
    }
    fun dismiss(key: String) {
        if (!accepting || items.none { it.key == key }) return
        val listener = ClusterNavigationListener.connected ?: return
        // The displayed list contains only same-user, clearable entries. Android remains the final authority.
        runCatching { listener.cancelNotification(key) }
    }
    fun clearAll() { items.map { it.key }.forEach(::dismiss) }
    private fun resync() {
        val listener = ClusterNavigationListener.connected
        connected = listener != null
        if (!accepting || listener == null) return
        actions.clear()
        val fresh = runCatching { listener.activeNotifications.orEmpty().asSequence()
            .filter { it.user == Process.myUserHandle() }
            .sortedByDescending { it.postTime }
            .take(256).mapNotNull { runCatching { parse(it) }.getOrNull() }.take(NotificationsLogic.MAX_ITEMS).toList()
        }.getOrDefault(emptyList())
        replace(NotificationsLogic.sorted(fresh))
    }
    private fun parse(sbn: StatusBarNotification): NotifEntry? {
        val context = app ?: return null
        if (!accepting || sbn.user != Process.myUserHandle()) return null
        val n = sbn.notification
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(256)
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(4000)
        val raw = NotifRaw(sbn.packageName, n.flags, sbn.isClearable,
            extras.getString(Notification.EXTRA_TEMPLATE), n.category, title, text)
        if (!NotificationsLogic.accept(raw, context.packageName)) return null
        val label = runCatching { context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString().take(128) }.getOrDefault(sbn.packageName)
        val messages = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages?.lastOrNull() }.getOrNull()
        val composed = NotificationsLogic.compose(label, title, text,
            messages?.text?.toString()?.take(4000), messages?.person?.name?.toString()?.take(256),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.take(256))
        n.contentIntent?.takeIf { HuNotificationActionPolicy.allowed(it.isActivity,
            it.creatorPackage == sbn.packageName, it.creatorUid == sbn.uid,
            it.creatorUserHandle == Process.myUserHandle()) }?.let {
            actions[sbn.key] = Action(sbn.key, sbn.packageName, sbn.postTime, it, epoch)
        }
        return NotifEntry(sbn.key, sbn.packageName, composed.first, composed.second, sbn.postTime, NotificationManager.IMPORTANCE_DEFAULT)
    }
    private fun replace(next: List<NotifEntry>) {
        if (items == next) return
        val kept = next.mapTo(HashSet()) { it.key }
        actions.keys.removeAll { it !in kept }
        items = next
        generation++
    }
    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action) }
}
