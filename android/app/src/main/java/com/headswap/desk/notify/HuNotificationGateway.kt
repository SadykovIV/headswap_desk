package com.headswap.desk.notify

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.headswap.desk.embed.EmbeddedSessionRegistry

/** A component receives status and two explicit UI actions, never notification text or PendingIntents. */
internal object HuNotificationGateway {
    private data class Entry(val parentId: Int, val parent: Any, val until: Long)
    private val entries = HashMap<IBinder, Entry>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var shade: HuNotificationShade? = null
    fun attach(owner: HuNotificationShade) { if (HuNotificationFeature.AVAILABLE) shade = owner }
    fun detach(owner: HuNotificationShade) { if (shade === owner) { shade = null; synchronized(entries) { entries.clear() } } }
    fun status(parentId: Int, token: IBinder?, visible: Boolean): Bundle {
        if (!HuNotificationFeature.AVAILABLE) {
            synchronized(entries) { entries.clear() }
            return Bundle().apply { putBoolean("enabled", false); putBoolean("connected", false); putInt("count", 0) }
        }
        val proof = EmbeddedSessionRegistry.captureParent(parentId)
        val active = proof != null && shade?.available(parentId) == true
        if (token != null) synchronized(entries) {
            if (visible && active && token.isBinderAlive) entries[token] = Entry(parentId, proof!!, SystemClock.elapsedRealtime() + 4_000)
            else entries.remove(token)
        }
        return Bundle().apply {
            putBoolean("enabled", active)
            putBoolean("connected", active && HuNotifications.connected)
            putInt("count", if (active) HuNotifications.items.size else 0)
        }
    }
    fun remove(token: IBinder) { synchronized(entries) { entries.remove(token) } }
    fun hasNativeEntry(parentId: Int): Boolean = synchronized(entries) {
        if (!HuNotificationFeature.AVAILABLE) { entries.clear(); return@synchronized false }
        val now = SystemClock.elapsedRealtime()
        entries.entries.removeAll { !it.key.isBinderAlive || it.value.until < now ||
            !EmbeddedSessionRegistry.isCurrentParent(it.value.parentId, it.value.parent) }
        entries.values.any { it.parentId == parentId }
    }
    fun show(parentId: Int): Boolean {
        if (!HuNotificationFeature.AVAILABLE) return false
        val proof = EmbeddedSessionRegistry.captureParent(parentId) ?: return false
        val target = shade ?: return false
        if (!target.available(parentId)) return false
        return main.post { if (shade === target && EmbeddedSessionRegistry.isCurrentParent(parentId, proof)) target.expand(parentId) }
    }
    fun open(context: Context, parentId: Int, key: String, packageName: String): Boolean {
        if (!HuNotificationFeature.AVAILABLE) return false
        if (key.length > 1024 || packageName.length > 255 || shade?.available(parentId) != true) return false
        val proof = EmbeddedSessionRegistry.captureParent(parentId) ?: return false
        return main.post {
            if (EmbeddedSessionRegistry.isCurrentParent(parentId, proof)) {
                if (HuNotificationLaunch.open(context, parentId, key, packageName)) shade?.collapse()
                else android.widget.Toast.makeText(context, "Уведомление недоступно. Проверьте блокировку телефона или откройте приложение из меню.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
