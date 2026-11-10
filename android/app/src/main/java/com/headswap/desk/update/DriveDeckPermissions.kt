package com.headswap.desk.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Read-only availability checks; grants are made exclusively in Android's own settings UI. */
internal object DriveDeckPermissions {
    enum class Availability { MISSING, UNTRUSTED, READY }
    data class Snapshot(val availability: Availability, val musicAccess: Boolean?, val description: String)

    fun snapshot(context: Context): Snapshot {
        val installed = try { ComponentPackages.trustedInstalled(context, DesktopComponent.DRIVEDECK) }
        catch (_: RuntimeException) { return Snapshot(Availability.UNTRUSTED, null, "Установлен несовместимый DriveDeck. Используйте адаптированный APK из комплекта HeadSwap.") }
            ?: return Snapshot(Availability.MISSING, null, "Сначала установите DriveDeck · HeadSwap из комплекта или каталога компонентов.")
        val service = try {
            context.packageManager.getServiceInfo(listener(), PackageManager.ComponentInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) { null }
        if (service == null || !service.enabled || !service.exported || !service.applicationInfo.enabled ||
            service.permission != "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE") {
            return Snapshot(Availability.UNTRUSTED, null, "В этой сборке DriveDeck недоступна служба музыкальных данных. Обновите адаптированный компонент.")
        }
        // NotificationManager's public query only accepts the caller's own package. Read the
        // enabled-component list, as AndroidX does, without inspecting any notification content.
        val access = runCatching {
            DriveDeckPermissionPolicy.listenerEnabled(Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")) ||
                context.packageManager.checkPermission("android.permission.MEDIA_CONTENT_CONTROL", installed.packageName) == PackageManager.PERMISSION_GRANTED
        }.getOrNull()
        return Snapshot(Availability.READY, access, when (access) {
            true -> "Доступ к плееру разрешён. Включите трек: название и обложка появятся, если плеер передаёт их Android."
            false -> "Для названия песни и обложки разрешите доступ к уведомлениям именно DriveDeck · HeadSwap. Разрешение HeadSwap для HUD не заменяет этот доступ."
            null -> "Android не позволил проверить состояние. Откройте доступ к уведомлениям и проверьте DriveDeck · HeadSwap."
        })
    }

    fun open(activity: Activity) { activity.startActivity(Intent(activity, ComponentPermissionsActivity::class.java)) }
    private fun listener() = ComponentName(DriveDeckPermissionPolicy.PACKAGE, DriveDeckPermissionPolicy.LISTENER_CLASS)

    fun openMusicAccess(activity: Activity) {
        if (snapshot(activity).availability != Availability.READY) return
        openSystem(activity, listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener().flattenToString()),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        ), "Откройте настройки Android → Доступ к уведомлениям → DriveDeck · HeadSwap.")
    }

    fun openAppSettings(activity: Activity) {
        if (snapshot(activity).availability != Availability.READY) return
        openSystem(activity, listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${DriveDeckPermissionPolicy.PACKAGE}"))),
            "Откройте настройки Android → Приложения → DriveDeck · HeadSwap → Разрешения.")
    }

    private fun openSystem(activity: Activity, intents: List<Intent>, fallback: String) {
        for (intent in intents) {
            // An OEM may omit the per-listener page. Accept only a real Android settings handler.
            val handler = activity.packageManager.queryIntentActivities(intent,
                PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY).firstOrNull()?.activityInfo ?: continue
            try {
                activity.startActivity(intent.setComponent(ComponentName(handler.packageName, handler.name)))
                return
            } catch (_: RuntimeException) { }
        }
        AlertDialog.Builder(activity).setTitle("Разрешения DriveDeck").setMessage(fallback).setPositiveButton("Понятно", null).show()
    }
}
