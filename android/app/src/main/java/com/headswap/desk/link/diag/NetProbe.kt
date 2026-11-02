package com.headswap.desk.link.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Отладочная проба локальной сети (команда `cmd=net_probe`): пускает ли платформа маяк и ответчик ветки B.
 * Шлёт UDP «CarlifeHost» на 7999 (broadcast каждого IPv4-интерфейса с broadcast-адресом и, если задан, unicast),
 * слушает UDP 8999 и отвечает JSON готовности. Всё в WL-лог: состояние ACCESS_LOCAL_NETWORK, интерфейсы, исход
 * каждой отправки (ok или класс исключения), принятые датаграммы (адрес, длина, есть ли строка carlifehost).
 * Входящий TCP проверяется отдельно: debug tcp6 с `--es debug_tcp6_bind 0.0.0.0`.
 */
object NetProbe {
    const val BEACON_PORT = 7999
    const val REPLY_PORT = 8999
    private val BEACON = "CarlifeHost".toByteArray()
    private val READY = """{"carlifehost":"carlife","status":"ready"}""".toByteArray()

    /** Есть ли ACCESS_LOCAL_NETWORK (до Android 17 разрешения нет, доступ к LAN открыт). */
    fun localNetworkGranted(ctx: Context): String =
        if (Build.VERSION.SDK_INT < 37) "n/a(api ${Build.VERSION.SDK_INT})"
        else if (ctx.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) "granted"
        else "denied"

    fun run(ctx: Context, seconds: Int, unicast: String?) {
        Thread({ probe(ctx, seconds, unicast) }, "net-probe").start()
    }

    private fun probe(ctx: Context, seconds: Int, unicast: String?) {
        WLog.begin("net-probe", "api" to Build.VERSION.SDK_INT, "targetSdk" to ctx.applicationInfo.targetSdkVersion,
            "ACCESS_LOCAL_NETWORK" to localNetworkGranted(ctx))
        val targets = ArrayList<Pair<String, InetAddress>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val b = ia.broadcast
                    WLog.ev("net", "iface", "name" to ni.name, "ip" to a.hostAddress, "bcast" to b?.hostAddress)
                    if (b != null) targets += "bcast:${ni.name}" to b
                }
            }
        } catch (e: Exception) {
            WLog.ev("net", "iface-fail", "err" to e.javaClass.simpleName, "msg" to e.message)
        }
        if (unicast != null) targets += "unicast" to InetAddress.getByName(unicast)

        val listener = Thread({ listen(seconds) }, "net-probe-8999").also { it.start() }
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                val rounds = maxOf(1, seconds / 2)
                for (i in 1..rounds) {
                    for ((label, addr) in targets) {
                        val res = try {
                            s.send(DatagramPacket(BEACON, BEACON.size, addr, BEACON_PORT))
                            "ok"
                        } catch (e: Exception) {
                            "${e.javaClass.simpleName}: ${e.message}"
                        }
                        if (i == 1 || res != "ok") WLog.ev("udp", "beacon", "to" to label, "addr" to addr.hostAddress, "round" to i, "res" to res)
                    }
                    Thread.sleep(2000)
                }
                WLog.ev("udp", "beacon-done", "rounds" to rounds, "targets" to targets.size)
            }
        } catch (e: Exception) {
            WLog.ev("udp", "beacon-fail", "err" to e.javaClass.simpleName, "msg" to e.message)
        }
        listener.join()
    }

    private fun listen(seconds: Int) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        var n = 0
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(REPLY_PORT))
                WLog.ev("udp", "listen", "port" to REPLY_PORT)
                val buf = ByteArray(1500)
                while (System.currentTimeMillis() < deadline) {
                    s.soTimeout = maxOf(1, (deadline - System.currentTimeMillis()).toInt())
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    n++
                    val hit = String(p.data, 0, p.length, Charsets.ISO_8859_1).contains("carlifehost")
                    val res = if (!hit) "skip" else try {
                        s.send(DatagramPacket(READY, READY.size, p.socketAddress))
                        "replied"
                    } catch (e: Exception) {
                        "${e.javaClass.simpleName}: ${e.message}"
                    }
                    WLog.ev("udp", "rx", "port" to REPLY_PORT, "from" to p.address.hostAddress, "len" to p.length, "carlifehost" to hit, "res" to res)
                }
            }
        } catch (e: Exception) {
            WLog.ev("udp", "listen-fail", "port" to REPLY_PORT, "err" to e.javaClass.simpleName, "msg" to e.message)
        }
        WLog.ev("udp", "listen-done", "rx" to n)
    }
}
