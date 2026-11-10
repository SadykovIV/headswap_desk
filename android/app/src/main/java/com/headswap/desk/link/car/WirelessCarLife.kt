package com.headswap.desk.link.car

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.hardware.usb.UsbManager
import com.headswap.desk.AppLog
import com.headswap.desk.BridgeService
import com.headswap.desk.BuildConfig
import com.headswap.desk.CarLifeState
import com.headswap.desk.dex.DesktopAccess
import com.headswap.desk.ui.Actions

enum class CarLifeConnectionMode { USB, WIFI, AUTO }

/** Wireless projection is independent of the short-lived Wi-Fi ADB activation. */
class WirelessPrefs(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("desktop_wireless", Context.MODE_PRIVATE)

    var mode: CarLifeConnectionMode
        get() = WirelessConnectionPolicy.savedMode(preferences.getString("mode", null), preferences.getBoolean("enabled", false))
        set(value) {
            preferences.edit().putString("mode", value.name).putBoolean("enabled", value != CarLifeConnectionMode.USB).apply()
            CarProfile.enableTrigger(app, value != CarLifeConnectionMode.USB)
        }

    var enabled: Boolean
        get() = mode != CarLifeConnectionMode.USB
        set(value) {
            mode = if (!value) CarLifeConnectionMode.USB
                else mode.takeUnless { it == CarLifeConnectionMode.USB } ?: CarLifeConnectionMode.WIFI
        }

    fun observeEnabled(changed: (Boolean) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enabled" || key == "mode" || key == null) changed(enabled)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

object WirelessCarLife {
    fun hasCompatibleUsb(context: Context): Boolean = runCatching {
        context.getSystemService(UsbManager::class.java).accessoryList.orEmpty().any(Actions::isCompatibleAccessory)
    }.getOrDefault(false)

    fun selectedTransport(context: Context): CarLifeConnectionMode =
        WirelessConnectionPolicy.selectedTransport(WirelessPrefs(context).mode, hasCompatibleUsb(context), hasConfiguredCar(context))

    fun preferUsb(context: Context): Boolean = selectedTransport(context) == CarLifeConnectionMode.USB

    fun hasConfiguredCar(context: Context): Boolean = CarProfile(context).isSet || CarWifi(context).isSet

    fun missingPermissions(context: Context): List<String> = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** No sockets, Bluetooth listeners or activation are started before this check. */
    fun unavailableReason(context: Context): String? = when {
        !BuildConfig.WIRELESS_BETA || !WirelessPrefs(context).enabled -> "Выберите подключение CarLife по Wi-Fi"
        WirelessPrefs(context).mode == CarLifeConnectionMode.AUTO && !hasConfiguredCar(context) ->
            "Настройте беспроводную магнитолу для автоматического подключения без USB"
        missingPermissions(context).isNotEmpty() -> "Разрешите доступ к устройствам поблизости и локальной сети"
        com.headswap.desk.dex.PhonePairingService.blocksProjectionForCommittedActivation(context) -> "Завершаю проверку активации рабочего стола…"
        !DesktopAccess.isReadyForProjection(context) -> "Сначала подготовьте выбранный способ доступа на главном экране HeadSwap"
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
            // This method is an explicit UI continuation. A prior Stop must not leave
            // the main screen saying "stopped" while the settings button starts Wi-Fi.
            Actions.resumeUsbAutoStart()
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
