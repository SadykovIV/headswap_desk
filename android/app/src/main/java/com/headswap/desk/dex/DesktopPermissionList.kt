package com.headswap.desk.dex

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import com.headswap.desk.cluster.ClusterNavigationListener
import com.headswap.desk.update.*

/** The wizard and Settings show the same grantable capabilities. Never request another package's grants. */
internal class DesktopPermissionList(private val activity: Activity, private val ui: DesktopSetupViews) {
    private val pm get() = activity.packageManager
    private fun pkgUri(pkg: String) = Uri.fromParts("package", pkg, null)
    private fun details(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(pkg))
    private fun settings(vararg intents: Intent) {
        for (intent in intents) {
            try { activity.startActivity(intent); return } catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        AlertDialog.Builder(activity).setMessage("На этом телефоне откройте нужный пункт вручную в настройках Android.")
            .setPositiveButton("Понятно", null).show()
    }
    private fun row(parent: LinearLayout, name: String, granted: Boolean?, reason: String, action: () -> Unit) {
        ui.permission(parent, name, action).update(granted, reason)
    }
    private fun runtime(parent: LinearLayout, pkg: String) {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val mine = pkg == activity.packageName
        info.requestedPermissions.orEmpty().distinct().forEach { permission ->
            @Suppress("DEPRECATION")
            val declared = try { pm.getPermissionInfo(permission, 0) } catch (_: PackageManager.NameNotFoundException) { return@forEach }
            if (declared.protection != PermissionInfo.PROTECTION_DANGEROUS ||
                (mine && permission == Manifest.permission.POST_NOTIFICATIONS)) return@forEach
            val granted = pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
            val label = labels[permission] ?: declared.loadLabel(pm).toString()
            row(parent, label, granted, when {
                mine && permission == "android.permission.ACCESS_LOCAL_NETWORK" -> "Поиск телефона и подключение к устройствам локальной сети."
                mine -> "Для беспроводного подключения к совместимой магнитоле."
                else -> if (granted) "Разрешено для DriveDeck." else "Не выдано. Можно настроить в разрешениях DriveDeck."
            }) {
                if (!mine) DriveDeckPermissions.openAppSettings(activity)
                else if (granted) settings(details(pkg))
                else activity.requestPermissions(arrayOf(permission), REQUEST)
            }
        }
    }
    fun headswap(parent: LinearLayout) {
        val pkg = activity.packageName
        val debug = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val usb = DesktopUsbDebugging.state(activity)
        row(parent, "Отладка по USB", when (usb) {
            DesktopUsbDebuggingPolicy.State.ENABLED -> true
            DesktopUsbDebuggingPolicy.State.DISABLED -> false
            else -> null
        }, "Для активации с компьютера и восстановления независимого экрана по кабелю на Android 17.") { settings(debug, Intent(Settings.ACTION_SETTINGS)) }
        val wifi = runCatching { Settings.Global.getString(activity.contentResolver, "adb_wifi_enabled") }.getOrNull()
        row(parent, "Беспроводная отладка (Wi-Fi)", when (wifi) { "1" -> true; "0" -> false; else -> null },
            "Для активации с телефона по коду. Для передачи рабочего стола по USB Wi-Fi не нужен.") { settings(debug, Intent(Settings.ACTION_SETTINGS)) }
        val blocked = PhonePairingService.notificationBlock(activity, needsCode = true)
        row(parent, "Уведомления HeadSwap", blocked == null, when (blocked) {
            PairingNotificationBlock.CODE_CHANNEL -> "Включите канал «Ввод кода сопряжения»."
            PairingNotificationBlock.PROGRESS_CHANNEL -> "Включите канал «Активация HeadSwap»."
            else -> "Состояние активации и ввод кода прямо в уведомлении."
        }) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
            else settings(PhonePairingService.notificationSettings(activity, blocked ?: PairingNotificationBlock.APP), details(pkg))
        }
        val component = ComponentName(activity, ClusterNavigationListener::class.java)
        row(parent, "Доступ HeadSwap к уведомлениям",
            activity.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component),
            "Для подсказок навигации на приборной панели и HUD.") {
            settings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row(parent, "Показ поверх приложений", Settings.canDrawOverlays(activity),
            "Для шторки на экране ГУ. Шторка отключена; этот доступ можно не выдавать.") {
            settings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(pkg)), details(pkg))
        }
        val battery = DesktopOnboardingActivity.batteryExempt(activity)
        row(parent, "Работа без ограничений батареи", battery, "Помогает сохранить соединение с магнитолой в фоне.") {
            if (battery) settings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details(pkg))
            else DesktopOnboardingActivity.requestBatteryExemption(activity)
        }
        row(parent, "Установка компонентов", pm.canRequestPackageInstalls(), "Нужно только для установки APK из каталога компонентов.") {
            settings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri(pkg)), details(pkg))
        }
        runtime(parent, pkg)
    }
    fun drivedeck(parent: LinearLayout) {
        val state = DriveDeckPermissions.snapshot(activity)
        if (state.availability != DriveDeckPermissions.Availability.READY) {
            ui.text(parent, state.description)
            ui.button(parent, "Установить компоненты") { activity.startActivity(Intent(activity, ComponentUpdatesActivity::class.java)) }
            return
        }
        row(parent, "Доступ DriveDeck к музыкальным данным", state.musicAccess,
            "Для названия трека и обложки. Android выдаёт DriveDeck доступ к уведомлениям.") { DriveDeckPermissions.openMusicAccess(activity) }
        try { runtime(parent, DriveDeckPermissionPolicy.PACKAGE) }
        catch (_: PackageManager.NameNotFoundException) { ui.text(parent, "Компонент удалён.") }
    }
    companion object {
        const val REQUEST = 1307
        private val labels = mapOf(
            Manifest.permission.ACCESS_COARSE_LOCATION to "Приблизительное местоположение",
            Manifest.permission.ACCESS_FINE_LOCATION to "Точное местоположение",
            Manifest.permission.READ_CONTACTS to "Контакты",
            Manifest.permission.READ_PHONE_STATE to "Состояние телефона",
            Manifest.permission.BLUETOOTH_CONNECT to "Подключение устройств Bluetooth",
            Manifest.permission.BLUETOOTH_SCAN to "Поиск устройств Bluetooth",
            Manifest.permission.NEARBY_WIFI_DEVICES to "Устройства Wi-Fi поблизости",
            Manifest.permission.POST_NOTIFICATIONS to "Показывать уведомления DriveDeck",
            "android.permission.ACCESS_LOCAL_NETWORK" to "Доступ к локальной сети",
        )
    }
}
