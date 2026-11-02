package com.headswap.desk.link.diag

import android.os.SystemClock
import com.headswap.desk.AppLog

/**
 * Структурный лог фаз связи (каркас Э6): строка `WL <фаза> <событие> ключ=значение … t=<мс от начала попытки>`
 * в общий лог (только файл и logcat, не экран). По этим строкам разбирается лог бета-тестера без вопросов к нему.
 *
 * Чего в WL нет и не должно появиться: SSID и пароли (хотспот, P2P-группа), p2pNetInfo и scanRecord (только наличие
 * и длина), ключи, сертификаты, подписи и случайные значения рукопожатия, hex-дампы. BT-MAC только через [maskMac].
 */
object WLog {
    /** Фазы связи. */
    const val LINK = "link"
    const val TCP = "tcp"
    const val HS = "hs"
    const val VIDEO = "video"
    const val BT = "bt"
    /** Вход телефона в Wi-Fi Direct-группу ГУ (ветка A). */
    const val P2P = "p2p"
    /** Автостарт по Bluetooth машины (выбор, ACL, авторежим сервиса). */
    const val TRIGGER = "trigger"

    @Volatile var clock: () -> Long = { SystemClock.elapsedRealtime() }
    @Volatile var sink: (String) -> Unit = { AppLog.i(it, ui = false) }
    @Volatile private var t0 = 0L

    /** Начало попытки подключения: от него считается t. */
    fun begin(kind: String, vararg kv: Pair<String, Any?>) {
        t0 = clock()
        ev(LINK, "begin", "transport" to kind, *kv)
    }

    fun ev(phase: String, event: String, vararg kv: Pair<String, Any?>) {
        sink(format(phase, event, clock() - t0, kv.asList()))
    }

    fun format(phase: String, event: String, tMs: Long, kv: List<Pair<String, Any?>>): String {
        val sb = StringBuilder("WL ").append(phase).append(' ').append(event)
        for ((k, v) in kv) sb.append(' ').append(k).append('=').append(value(v))
        return sb.append(" t=").append(tMs).toString()
    }

    /** Значение в одно слово: пробелы и кавычки экранируются, длинное режется. */
    private fun value(v: Any?): String {
        val s = (v ?: "-").toString().replace('\n', ' ')
        val cut = if (s.length > MAX_VALUE) s.take(MAX_VALUE) + "…" else s
        return if (cut.isEmpty() || cut.any { it == ' ' || it == '"' || it == '=' }) "\"" + cut.replace("\"", "'") + "\"" else cut
    }

    /** BT-MAC: первые три октета (производитель), остальное скрыто. */
    fun maskMac(mac: String?): String {
        if (mac == null) return "-"
        val p = mac.split(':')
        return if (p.size == 6) p.take(3).joinToString(":") + ":xx:xx:xx" else "?"
    }

    private const val MAX_VALUE = 120
}
