package com.headswap.desk.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.headswap.desk.AppLog
import com.headswap.desk.R

/**
 * Чужие приложения, которые Android Auto показывает на «dev-ГУ» (Headswap представляется как Desktop Head Unit,
 * в AA включены «Неизвестные источники»). Установленные находим сами (HuApps.detect), каталог - только рекомендации
 * со ссылкой на релизы. Ничего не скачиваем и не ставим сами.
 * Пакет каталога должен быть и в `<queries>` манифеста, иначе на API 30+ его не видно (остальные находит `<intent>` там же).
 */
class HuApp(
    val name: String,
    val pkg: String,
    val url: String,
    val descRes: Int,
    /** Строка-требование («Нужен Android 15+») или null. */
    val reqRes: Int? = null,
    /** Минимальный SDK_INT телефона; 0 - без ограничения. */
    val minSdk: Int = 0,
)

/** Что показать про приложение: стоит ли оно и пойдёт ли на этом телефоне. */
data class HuAppStatus(val installed: Boolean, val unsupported: Boolean)

/** Кандидат из запросов к PackageManager: пакет, системный ли он и кто его поставил (null - adb, браузер, пакетный установщик). */
data class AppCandidate(val pkg: String, val isSystem: Boolean, val installer: String?)

/** Итог отбора: пакеты для «Установлено на телефоне» (порядок обнаружения) и записи каталога для «Рекомендуем». */
class AppsSelection(val installed: List<String>, val recommended: List<HuApp>)

object HuApps {
    /** Приложения из Play Android Auto показывает и так, их не перечисляем. */
    const val PLAY_INSTALLER = "com.android.vending"

    private const val CATEGORY_PROJECTION = "com.google.android.gms.car.category.CATEGORY_PROJECTION"
    private const val CATEGORY_CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"

    val CATALOG: List<HuApp> = listOf(
        HuApp(
            "Fermata Auto", "me.aap.fermata.auto.dear.google.why",
            "https://github.com/AndreyPavlenko/Fermata/releases", R.string.apps_fermata_desc,
        ),
        HuApp(
            "ParkedTube", "dev.codex.aaparkedyoutube",
            "https://github.com/gadmin2151/ParkedTube/releases", R.string.apps_parkedtube_desc,
            R.string.apps_aabrowser_req, minSdk = 35,
        ),
        HuApp(
            "AABrowser", "com.kododake.aabrowser",
            "https://github.com/kododake/AABrowser/releases", R.string.apps_aabrowser_desc,
            R.string.apps_aabrowser_req, minSdk = 35,
        ),
    )

    /** Чистая логика статуса: проверка установки и SDK_INT приходят снаружи (в тестах - фейки). */
    fun status(app: HuApp, isInstalled: (String) -> Boolean, sdkInt: Int): HuAppStatus =
        HuAppStatus(isInstalled(app.pkg), sdkInt < app.minSdk)

    /**
     * Чистая логика отбора, без Android. В «Установлено» попадает кандидат, который не мы сами, не системный и не из Play;
     * плюс всё из каталога, что стоит на телефоне (даже если запросы его не нашли). В «Рекомендуем» - каталожное, чего нет.
     */
    fun select(
        candidates: List<AppCandidate>,
        selfPkg: String,
        catalog: List<HuApp>,
        isInstalled: (String) -> Boolean,
    ): AppsSelection {
        val installed = LinkedHashSet<String>()
        for (c in candidates) {
            if (c.pkg == selfPkg || c.isSystem || c.installer == PLAY_INSTALLER) continue
            installed.add(c.pkg)
        }
        for (app in catalog) if (isInstalled(app.pkg)) installed.add(app.pkg)
        return AppsSelection(installed.toList(), catalog.filter { it.pkg !in installed })
    }

    /** Ищем приложения для AA на телефоне: сервисы-плееры (CATEGORY_PROJECTION) и полноэкранные activity (CAR_LAUNCHER). */
    fun detect(ctx: Context): AppsSelection =
        select(scan(ctx.packageManager), ctx.packageName, CATALOG) { isInstalled(ctx, it) }

    private fun scan(pm: PackageManager): List<AppCandidate> {
        val projection = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_PROJECTION)
        val carLauncher = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_CAR_LAUNCHER)
        val infos = ArrayList<ApplicationInfo>()
        try {
            val services = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(projection, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentServices(projection, 0)
            }
            services.mapNotNullTo(infos) { it.serviceInfo?.applicationInfo }
            val activities = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(carLauncher, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentActivities(carLauncher, 0)
            }
            activities.mapNotNullTo(infos) { it.activityInfo?.applicationInfo }
        } catch (e: Exception) {
            AppLog.e("Поиск приложений для AA не удался: ${e.message}")
        }
        return infos.distinctBy { it.packageName }.map {
            val system = it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            AppCandidate(it.packageName, system, installerOf(pm, it.packageName))
        }
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
    } catch (_: Exception) {
        null
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun status(ctx: Context, app: HuApp): HuAppStatus = status(app, { isInstalled(ctx, it) }, Build.VERSION.SDK_INT)

    /** Intent запуска или null: у приложения может не быть своей иконки (тогда кнопку «Открыть» не показываем). */
    fun launchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun download(ctx: Context, app: HuApp) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("${app.name}: браузер не открылся: ${e.message}")
        }
    }

    fun open(ctx: Context, pkg: String) {
        try {
            launchIntent(ctx, pkg)?.let { ctx.startActivity(it) }
        } catch (e: Exception) {
            AppLog.e("$pkg: не запустился: ${e.message}")
        }
    }
}
