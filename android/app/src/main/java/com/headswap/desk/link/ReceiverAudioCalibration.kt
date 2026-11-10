package com.headswap.desk.link

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/** Only the reported model and connection type identify a calibration, never a VIN, address or serial. */
class ReceiverAudioCalibration(context: Context, private val kind: String) {
    private val store = prefs(context)
    @Volatile private var key = ""
    init { identify(null, null) }
    fun identify(manufacturer: String?, model: String?) {
        fun clean(s: String?) = s.orEmpty().filterNot(Char::isISOControl).trim().take(64)
        val maker = clean(manufacturer); val name = clean(model)
        val route = if (kind == TransportCaps.TCP6.kind) "Wi-Fi" else "USB"
        val identity = listOf(route, maker, name).joinToString("\u0000").lowercase(Locale.ROOT)
        val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val title = listOf(maker, name).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Модель не указана" }
        key = id
        store.edit().putString("selected", id).putString("name", "$title · $route").apply()
    }
    fun milliseconds(): Int = store.getInt("delay.$key", 0).coerceIn(0, MAX_MS)
    data class Selection(val key: String, val name: String, val ms: Int)
    companion object {
        const val MAX_MS = 2000
        private fun prefs(c: Context) = c.getSharedPreferences("desktop_receiver_audio_v1", Context.MODE_PRIVATE)
        fun selected(c: Context): Selection? = prefs(c).let { p ->
            val key = p.getString("selected", null) ?: return null
            Selection(key, p.getString("name", "").orEmpty(), p.getInt("delay.$key", 0).coerceIn(0, MAX_MS))
        }
        fun set(c: Context, target: Selection, value: Int) {
            require(value in 0..MAX_MS)
            prefs(c).edit().putInt("delay.${target.key}", value).apply()
        }
    }
}
