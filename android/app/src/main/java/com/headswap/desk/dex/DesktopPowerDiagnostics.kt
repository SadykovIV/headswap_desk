package com.headswap.desk.dex

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import com.headswap.desk.AppLog

/** Session-scoped observations. Never changes power, lock, battery or scheduler policy. */
internal class DesktopPowerDiagnostics(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var active = false
    private var registered = false
    private var previous: String? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            sample()
            main.postDelayed(this, 15_000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (active) sample() }
    }

    fun start() {
        if (active) return
        active = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        registered = runCatching {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            true
        }.getOrElse {
            AppLog.i("DESKTOP POWER: broadcast observation unavailable=${it.javaClass.simpleName}", false)
            false
        }
        tick.run()
    }

    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacks(tick)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun sample() {
        val value = runCatching {
            val ids = (DelegatedInputGateway.ownedDisplayIds() + DexState.displayId).filter { it > 0 }.sorted()
            val states = ids.joinToString(",") { id -> "$id:${displays.getDisplay(id)?.state ?: -1}" }
            "phoneState=${displays.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: -1} " +
                "interactive=${power.isInteractive} locked=${keyguard.isDeviceLocked || keyguard.isKeyguardLocked} " +
                "powerSave=${power.isPowerSaveMode} idle=${power.isDeviceIdleMode} " +
                "batteryExempt=${power.isIgnoringBatteryOptimizations(context.packageName)} " +
                "thermal=${power.currentThermalStatus} desktopStates=[$states]"
        }.getOrElse { "unavailable=${it.javaClass.simpleName}" }
        if (value != previous) {
            previous = value
            AppLog.i("DESKTOP POWER: $value", false)
        }
    }
}
