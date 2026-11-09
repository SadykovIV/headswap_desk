package com.headswap.desk.dex

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import com.headswap.desk.AppLog

/** Uses only voice-entry Activities actually exported by the installed application. */
internal object DesktopAssistant {
    private val packages = listOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.yandex.searchapp", "ru.yandex.searchplugin")
    private val actions = listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)

    /** Called on the phone UI thread, but starts the assistant only on the owned HU display. */
    fun launch(context: Context, parentId: Int, ownedDisplays: Set<Int>, isCurrent: () -> Boolean): Int? {
        if (parentId <= 0 || parentId !in ownedDisplays || !isCurrent()) return null
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(parentId)
        if (display?.isValid != true || display.displayId != parentId) return null
        val manager = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val observation = runCatching { manager.getRunningTasks(100) }
        val existing = observation.getOrDefault(emptyList())
        val fullTaskVisibility = observation.isSuccess && existing.size < 100 &&
            context.checkSelfPermission(DelegatedDesktopAccess.REAL_GET_TASKS) == PackageManager.PERMISSION_GRANTED
        val pm = context.packageManager
        for (pkg in packages) for (action in actions) {
            val request = Intent(action).setPackage(pkg)
            val match = pm.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull { result ->
                val info = result.activityInfo
                info != null && info.exported && info.enabled && info.applicationInfo.enabled &&
                    (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
            } ?: continue
            val info = match.activityInfo
            // Do not steal an existing assistant task from the phone. A singleton entry cannot
            // guarantee setLaunchDisplayId when the application already owns another task.
            val packageTasks = existing.filter { it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg }
            val foreignTask = packageTasks.any { FrameworkTaskIdentityReader.read(it)?.displayId !in ownedDisplays }
            val ownedTask = packageTasks.firstNotNullOfOrNull {
                FrameworkTaskIdentityReader.read(it)?.displayId?.takeIf(ownedDisplays::contains)
            }
            val plan = DesktopAssistantLaunchPolicy.plan(info.launchMode, fullTaskVisibility, foreignTask,
                ownedTask, parentId) ?: continue
            val target = plan.displayId
            val intent = request.setComponent(ComponentName(info.packageName, info.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (plan.newTask) Intent.FLAG_ACTIVITY_MULTIPLE_TASK else 0))
            if (!isCurrent() || !manager.isActivityStartAllowedOnDisplay(context, target, intent)) continue
            try {
                context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
                AppLog.i("DESKTOP VOICE: exported action=$action package=$pkg display=$target", false)
                return target
            } catch (error: RuntimeException) {
                AppLog.i("DESKTOP VOICE: activity unavailable (${error.javaClass.simpleName})", false)
            }
        }
        AppLog.i("DESKTOP VOICE: no exported assistant entry available on the owned display", false)
        Toast.makeText(context.createDisplayContext(display),
            "Голосовой вход недоступен. Вызовите Алису в Навигаторе или установите «Яндекс — с Алисой».", Toast.LENGTH_LONG).show()
        return null
    }
}
