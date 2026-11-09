package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.headswap.desk.BridgeService
import com.headswap.desk.link.car.WirelessPrefs
import java.net.InetSocketAddress
import java.net.Socket

/** Starts the real six-channel listener on loopback, then revokes wireless opt-in mid-handshake. */
object WirelessOptOutProbe {
    @JvmStatic fun run(probe: Instrumentation) {
        val result = Bundle()
        val context = probe.targetContext
        val preferences = WirelessPrefs(context)
        val original = preferences.mode
        var peer: Socket? = null
        try {
            check(!BridgeService.alive) { "Run this probe with no existing projection" }
            preferences.enabled = true
            context.startForegroundService(Intent(context, BridgeService::class.java).putExtra("debug_tcp6", true))
            val limit = SystemClock.elapsedRealtime() + 8_000
            while (peer == null && SystemClock.elapsedRealtime() < limit) {
                val candidate = Socket()
                try {
                    candidate.connect(InetSocketAddress("127.0.0.1", 7240), 250)
                    peer = candidate
                } catch (_: Exception) {
                    candidate.close()
                    SystemClock.sleep(50)
                }
            }
            val accepted = checkNotNull(peer) { "TCP6 listener did not open" }
            check(BridgeService.alive)
            preferences.enabled = false
            val stoppedBy = SystemClock.elapsedRealtime() + 5_000
            while (BridgeService.alive && SystemClock.elapsedRealtime() < stoppedBy) SystemClock.sleep(25)
            check(!BridgeService.alive) { "Wireless service survived opt-out" }
            accepted.soTimeout = 1_000
            val ended = runCatching { accepted.getInputStream().read() }.fold({ it == -1 }, { it is java.net.SocketException })
            check(ended) { "Accepted channel was not closed" }
            SystemClock.sleep(1_750) // Exceeds the 1500-ms reconnect scheduling window.
            check(!BridgeService.alive) { "Cancelled wireless attempt restarted itself" }
            val reopened = Socket().use { socket ->
                runCatching { socket.connect(InetSocketAddress("127.0.0.1", 7240), 250) }.isSuccess
            }
            check(!reopened) { "Listener reopened after opt-out" }
            result.putString("wirelessOptOut", "PASS real TCP6 listener and accepted channel closed; no delayed restart")
        } catch (failure: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(failure))
        } finally {
            peer?.close()
            context.stopService(Intent(context, BridgeService::class.java))
            preferences.mode = original
            probe.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }
}
