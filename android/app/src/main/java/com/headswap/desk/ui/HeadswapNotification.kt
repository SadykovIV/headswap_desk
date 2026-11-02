package com.headswap.desk.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.headswap.desk.BridgeService
import com.headswap.desk.CarLifeState
import com.headswap.desk.R
import com.headswap.desk.dex.DexSetupActivity
import com.headswap.desk.dex.DexState

/** Foreground status for the DeX source; never offers the Android Auto setup action. */
object HeadswapNotification {
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var lastKey = ""

    fun build(ctx: Context, channel: String, notifId: Int): Notification {
        val app = ctx.applicationContext
        val result = from(app, channel)
        startTicker(app, channel, notifId)
        return result
    }

    private fun from(ctx: Context, channel: String): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, StopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = DexState.error ?: "${DexState.status} · ${CarLifeState.status}"
        val builder = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle("HeadSwap Desktop")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stop), "Остановить", stop).build())
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    private fun startTicker(app: Context, channel: String, notifId: Int) {
        if (ticking) return
        ticking = true
        val nm = app.getSystemService(NotificationManager::class.java)
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!BridgeService.alive) {
                    ticking = false
                    lastKey = ""
                    return
                }
                val key = "${DexState.status}|${DexState.error}|${CarLifeState.status}"
                if (key != lastKey) {
                    lastKey = key
                    nm.notify(notifId, from(app, channel))
                }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }
}

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Actions.stopBridge(context)
    }
}
