package com.headswap.desk.notify

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import com.headswap.desk.embed.EmbeddedSessionRegistry
import com.headswap.desk.desktop.NotificationFullscreenActivity

internal object HuNotificationActionPolicy {
    fun allowed(activity: Boolean, samePackage: Boolean, sameUid: Boolean, sameUser: Boolean) =
        activity && samePackage && sameUid && sameUser
}

/** The only intent sent by a notification card is a current same-user Activity PendingIntent.
 * Tickets never leave our process except for an opaque Binder passed to our nonexported host. */
internal object HuNotificationLaunch {
    class Ticket(val action: HuNotifications.Action, val parentId: Int, val parentProof: Any) {
        fun current(context: Context): Boolean {
            if (!HuNotificationFeature.AVAILABLE) return false
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return HuNotifications.current(action) && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
                EmbeddedSessionRegistry.isCurrentParent(parentId, parentProof)
        }
    }
    private data class Pending(val ticket: Ticket, val expires: Long)
    private val tickets = HashMap<IBinder, Pending>()
    fun clear() { synchronized(tickets) { tickets.clear() } }
    fun open(context: Context, parentId: Int, key: String, packageName: String): Boolean {
        if (!HuNotificationFeature.AVAILABLE) return false
        val action = HuNotifications.action(key, packageName) ?: return false
        val parent = EmbeddedSessionRegistry.captureParent(parentId) ?: return false
        val ticket = Ticket(action, parentId, parent)
        if (!ticket.current(context)) return false
        val token = Binder()
        synchronized(tickets) {
            tickets.entries.removeAll { it.value.expires < SystemClock.elapsedRealtime() }
            if (tickets.size >= 8) return false
            tickets[token] = Pending(ticket, SystemClock.elapsedRealtime() + 10_000)
        }
        return runCatching {
            val intent = Intent(context, NotificationFullscreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("headswap_notification", android.os.Bundle().apply { putBinder("ticket", token) })
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(parentId).toBundle())
            true
        }.getOrElse { synchronized(tickets) { tickets.remove(token) }; false }
    }
    fun consume(intent: Intent, parentId: Int, context: Context): Ticket? {
        if (!HuNotificationFeature.AVAILABLE) { clear(); return null }
        val token = intent.getBundleExtra("headswap_notification")?.getBinder("ticket") ?: return null
        val pending = synchronized(tickets) { tickets.remove(token) } ?: return null
        return pending.ticket.takeIf { pending.expires >= SystemClock.elapsedRealtime() &&
            it.parentId == parentId && it.current(context) }
    }
}
