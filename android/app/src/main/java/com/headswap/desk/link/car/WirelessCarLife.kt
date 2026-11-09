package com.headswap.desk.link.car

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.headswap.desk.AppLog
import com.headswap.desk.BridgeService
import com.headswap.desk.BuildConfig
import com.headswap.desk.CarLifeState
import com.headswap.desk.dex.DesktopAccess
import com.headswap.desk.dex.DexPrefs

/** Wireless projection is independent of the short-lived Wi-Fi ADB activation. */
class WirelessPrefs(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("desktop_wireless", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) {
            preferences.edit().putBoolean("enabled", value).apply()
            CarProfile.enableTrigger(app, value)
        }
}

object WirelessCarLife {
    fun missingPermissions(context: Context): List<String> = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** No sockets, Bluetooth listeners or activation are started before this check. */
    fun unavailableReason(context: Context): String? = when {
        !BuildConfig.WIRELESS_BETA || !WirelessPrefs(context).enabled -> "Выберите подключение CarLife по Wi-Fi"
        missingPermissions(context).isNotEmpty() -> "Разрешите доступ к устройствам поблизости и локальной сети"
        DexPrefs(context).useDesktopService && !DesktopAccess.isReady(context) -> "Сначала активируйте доступ HeadSwap на главном экране"
        else -> null
    }

    fun configure(activity: Activity) {
        activity.startActivity(Intent(activity, WirelessSetupActivity::class.java))
    }

    fun start(activity: Activity) {
        if (unavailableReason(activity) != null) {
            activity.startActivity(Intent(activity, WirelessSetupActivity::class.java).putExtra(EXTRA_CONNECT, true))
        } else startReady(activity)
    }

    /** Also used by the explicit notification action; never grants permissions itself. */
    fun startReady(context: Context): Boolean {
        val reason = unavailableReason(context)
        if (reason != null) {
            CarLifeState.status = reason
            return false
        }
        return try {
            context.startForegroundService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B))
            true
        } catch (error: RuntimeException) {
            CarLifeState.status = "Не удалось начать подключение по Wi-Fi. Откройте HeadSwap и повторите"
            AppLog.i("WIRELESS start failed: ${error.javaClass.simpleName}", false)
            false
        }
    }

    const val EXTRA_CONNECT = "com.headswap.desk.wireless.CONNECT"
}
