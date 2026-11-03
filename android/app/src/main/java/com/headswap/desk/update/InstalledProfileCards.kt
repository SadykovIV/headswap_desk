package com.headswap.desk.update

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import com.headswap.desk.CarLifeState
import com.headswap.desk.component.DesktopComponentEntry
import com.headswap.desk.component.DesktopComponents
import com.headswap.desk.component.api.ProfileContract
import com.headswap.desk.dex.DesktopSetupViews
import java.util.concurrent.ExecutorService

/** Updates apply to a publisher's installed APK. Every picker result is bound to the card that opened it. */
internal class InstalledProfileCards(private val activity: Activity, private val ui: DesktopSetupViews,
    parent: LinearLayout, private val worker: ExecutorService, state: Bundle?) {
    private val group = ui.group(parent)
    private data class Card(val entry: DesktopComponentEntry, val title: String, val description: String, val page: String?)
    private var cards = emptyList<Card>()
    private var loaded = false
    private var generation = 0
    private var busy = false
    private var target = state?.getString("profileTarget")?.let(ComponentName::unflattenFromString)
    private var pins = state?.getStringArrayList("profilePins")?.toSet().orEmpty()
    private var mode = state?.getString("profileMode")
    private var pending = ComponentPackages.restore(activity, state?.getString("profileFile"))
    private var message = state?.getString("profileMessage").orEmpty()

    fun save(state: Bundle) {
        state.putString("profileTarget", target?.flattenToString())
        state.putStringArrayList("profilePins", ArrayList(pins))
        state.putString("profileMode", mode)
        state.putString("profileFile", pending?.name)
        if (!busy) state.putString("profileMessage", message)
    }

    @Suppress("DEPRECATION")
    fun refresh() {
        val serial = ++generation
        worker.execute {
            val list = DesktopComponents.list(activity).filter { entry -> DesktopComponent.entries.none { it.pkg == entry.packageName } }
                .groupBy { it.packageName }.values.mapNotNull { profiles ->
                    val entry = profiles.first()
                    try {
                        val info = activity.packageManager.getActivityInfo(entry.componentName, PackageManager.GET_META_DATA)
                        val desc = info.applicationInfo.loadDescription(activity.packageManager)?.take(400)?.filterNot {
                            it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
                        }?.trim()?.toString().orEmpty()
                        Card(entry, if (profiles.size == 1) entry.label else entry.appLabel, desc,
                            ProfileUpdatePolicy.publisherPage(info.metaData?.get(ProfileContract.META_UPDATE_PAGE)))
                    } catch (_: PackageManager.NameNotFoundException) { null }
                    catch (_: android.content.res.Resources.NotFoundException) { null }
                    catch (_: SecurityException) { null }
                }
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing && serial == generation) {
                    loaded = true
                    cards = list
                    render()
                }
            }
        }
    }

    private fun render() {
        group.removeAllViews()
        if (loaded && cards.isEmpty()) ui.text(group, "Дополнительные компоненты не установлены.")
        for (card in cards) {
            val row = ui.section(group, card.title)
            val entry = card.entry
            ui.text(row, "Версия ${entry.versionName} (код ${entry.versionCode}) · ${entry.packageName}")
            if (card.description.isNotEmpty()) ui.text(row, card.description)
            card.page?.let { page ->
                ui.button(row, "Страница обновлений разработчика") {
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                        .onFailure { select(entry); message = "Не удалось открыть страницу разработчика."; render() }
                }.isEnabled = !busy
            }
            ui.button(row, "Обновить из APK…") {
                select(entry)
                open(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive"), PICK)
            }.isEnabled = !busy
            ui.button(row, "Удалить компонент…") { select(entry); remove() }.isEnabled = !busy
            if (entry.componentName == target && entry.signerSha256 == pins) {
                if (message.isNotEmpty()) ui.text(row, message)
                if (pending != null) ui.button(row, "Продолжить установку") { verifyPending() }.isEnabled = !busy
            }
        }
    }

    private fun select(entry: DesktopComponentEntry) {
        target = entry.componentName; pins = entry.signerSha256; mode = entry.modeId
        pending = null; message = ""
    }

    private fun live(): DesktopComponentEntry {
        val entry = target?.let { DesktopComponents.installed(activity, it) }
        check(entry != null && entry.signerSha256 == pins && entry.modeId == mode) { "Компонент изменился. Откройте его карточку заново." }
        check(!CarLifeState.running || DesktopComponents.selected(activity)?.packageName != entry.packageName) {
            "Остановите проекцию с этим компонентом и повторите действие."
        }
        return entry
    }

    private fun <T> work(note: String, action: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; message = note; render()
        worker.execute {
            val result = runCatching(action)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                busy = false
                result.fold({ value -> runCatching { done(value) }.onFailure { message = it.message.orEmpty() } },
                    { message = it.message ?: "Не удалось выполнить действие." })
                render()
            }
        }
    }

    private fun open(intent: Intent, code: Int) {
        try { activity.startActivityForResult(intent, code) }
        catch (_: RuntimeException) { message = "Системное окно недоступно."; render() }
    }

    private fun remove() = work("Проверяю установленный компонент…", ::live) { entry ->
        open(ComponentPackages.uninstallIntent(activity, entry.packageName), REMOVE)
    }

    private fun prepared(apk: PreparedComponent) {
        pending = apk.file
        message = "Проверена версия ${apk.version} (код ${apk.code})."
        if (!activity.hasWindowFocus()) return
        if (activity.packageManager.canRequestPackageInstalls()) {
            open(ComponentPackages.installerIntent(activity, apk), INSTALL)
        } else {
            message = "Разрешите установку из источника HeadSwap."
            open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")), SOURCE)
        }
    }

    private fun verifyPending() {
        val file = pending ?: return
        work("Проверяю APK…", { ComponentPackages.validateProfile(activity, live(), file) }, ::prepared)
    }

    fun result(code: Int, result: Int, data: Intent?): Boolean {
        when (code) {
            PICK -> if (result == Activity.RESULT_OK) data?.data?.let { uri ->
                work("Проверяю подпись и версию APK…", {
                    val entry = live()
                    checkNotNull(activity.contentResolver.openInputStream(uri)) { "Не удалось открыть APK." }
                        .use { ComponentPackages.stageProfile(activity, entry, it) }
                }, ::prepared)
            }
            SOURCE -> if (activity.packageManager.canRequestPackageInstalls()) verifyPending()
                else message = "Установка не разрешена."
            INSTALL -> if (result == Activity.RESULT_OK) { pending = null; message = "Обновление установлено." }
                else message = "Установка не завершена."
            REMOVE -> {
                if (result == Activity.RESULT_OK) target?.let { DesktopComponents.clearIfRemoved(activity, it.packageName) }
                else message = "Компонент остался установлен."
            }
            else -> return false
        }
        refresh()
        return true
    }

    companion object {
        private const val PICK = 21
        private const val SOURCE = 22
        private const val INSTALL = 23
        private const val REMOVE = 24
    }
}
