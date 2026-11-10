package com.headswap.desk.dex

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import com.flyfishxu.kadb.Kadb
import com.headswap.desk.AppLog
import com.headswap.desk.link.car.CarLifeConnectionMode
import com.headswap.desk.link.car.WirelessPrefs

/** Observes settings only. Enabling debugging always remains an explicit action in Android Settings. */
internal object DesktopUsbDebugging {
    const val INSTRUCTION = "В параметрах разработчика включите «Отладку по USB» и оставьте её включённой. " +
        "Она нужна для восстановления рабочего стола при подключении кабеля. «Беспроводная отладка» — отдельная настройка для активации по Wi-Fi."
    const val MISSING = "Выключена «Отладка по USB». $INSTRUCTION Сопряжение и уже выданные права сохранены; удалять приложение и вводить новый код не нужно."

    @Volatile private var confirmation: DesktopUsbDebuggingPolicy.Confirmation? = null

    fun required(context: Context): Boolean {
        val prefs = DexPrefs(context)
        return DesktopUsbDebuggingPolicy.required(Build.VERSION.SDK_INT, prefs.useDelegated,
            prefs.desktopMode == DesktopModePolicy.SAMSUNG_DEX,
            WirelessPrefs(context).mode == CarLifeConnectionMode.WIFI)
    }

    private fun appSetting(context: Context): String? = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.ADB_ENABLED)
    }.getOrNull()

    private fun usbAdb(context: Context): Boolean? = runCatching {
        val usb = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        // A disconnected/stale broadcast cannot prove that debugging is disabled.
        usb?.getBooleanExtra("adb", false)?.takeIf { usb.getBooleanExtra("connected", false) }
    }.getOrNull()

    fun state(context: Context): DesktopUsbDebuggingPolicy.State =
        DesktopUsbDebuggingPolicy.state(appSetting(context), usbAdb(context))

    fun confirmedMissing(context: Context): Boolean = required(context) &&
        confirmation?.stillMissing(appSetting(context), usbAdb(context)) == true

    /** Call only after verifying shell UID and this installation's private proof. */
    fun requireEnabled(context: Context, adb: Kadb) {
        if (!required(context)) return
        fun read(command: String): String? = adb.shell(command).let { result ->
            result.output.trim().takeIf { result.exitCode == 0 && it.length <= 256 }
        }
        val setting = read("settings get global adb_enabled")
        val functions = read("getprop sys.usb.config")
        val current = DesktopUsbDebuggingPolicy.verifiedState(setting, functions)
        confirmation = DesktopUsbDebuggingPolicy.Confirmation(appSetting(context), current)
        AppLog.i("Desktop USB debugging preflight: state=$current", false)
        if (current == DesktopUsbDebuggingPolicy.State.DISABLED)
            throw DexAccessException(DexAccessStage.PROBE_SHELL, DexAccessReason.USB_DEBUGGING_DISABLED)
    }
}
