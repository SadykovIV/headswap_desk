package com.headswap.desk.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.headswap.desk.BuildConfig
import com.headswap.desk.CarLifeState
import com.headswap.desk.dex.DesktopSetupViews
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.DexPrefs
import java.io.File
import java.util.concurrent.Executors

/** Phone-only, explicit updates. Projection and shell activation are not needed to install a component. */
class ComponentUpdatesActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var version: TextView
    private lateinit var status: TextView
    private lateinit var profiles: InstalledProfileCards
    private lateinit var check: Button
    private lateinit var pick: Button
    private lateinit var remote: Button
    private lateinit var install: Button
    private lateinit var remove: Button
    private var spec = DesktopComponent.entries.first()
    private var available: ComponentAsset? = null
    private var pending: File? = null
    private var busy = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        spec = DesktopComponent.entries.firstOrNull { it.pkg == state?.getString("component") } ?: spec
        pending = ComponentPackages.restore(this, state?.getString("pending"))
        val ui = DesktopSetupViews(this)
        val pad = ui.dp(16)
        val root = ui.column().apply { setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ui.background)
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
            addView(root)
        })
        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        ui.button(header, "Назад") { finish() }.layoutParams = LinearLayout.LayoutParams(-2, -2)
        ui.text(header, "Компоненты HeadSwap Desktop", 22f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(ui.dp(12), 0, 0, 0)
        }
        val mainApp = ui.section(root, "HeadSwap Desktop")
        ui.text(mainApp, "Основное приложение · ${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})", 15f)
        ui.button(mainApp, "Проверить обновления HeadSwap Desktop") { Updater.check(this, manual = true) }
        val card = ui.section(root)
        fun button(value: String, click: () -> Unit) = ui.button(card, value, click)
        val componentTitle = ui.text(card, spec.title, 18f, true)
        if (DesktopComponent.entries.size > 1) button("Выбрать компонент") {
            if (!busy) AlertDialog.Builder(this).setTitle("Компоненты HeadSwap Desktop")
                .setItems(DesktopComponent.entries.map { it.title }.toTypedArray()) { _, index ->
                    spec = DesktopComponent.entries[index]
                    componentTitle.text = spec.title
                    pending = null; available = null; status.text = ""; refresh()
                }.show()
        }
        version = ui.text(card, "", 15f)
        check = button("Проверить версии компонента") { query() }
        remote = button("") {
            val a = available ?: return@button
            work("Загружаю и проверяю APK…", { ComponentPackages.download(this, spec, a) }, ::prepared)
        }
        pick = button("Установить из файла…") {
            launchSystem(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(APK_TYPE), PICK)
        }
        install = button("Продолжить установку") { installPrepared() }
        remove = button("Удалить компонент…") {
            work("Проверяю установленный компонент…", {
                check(!componentInUse()) {
                    "Остановите сеанс компонента и повторите действие."
                }
                ComponentPackages.builtinRemoval(this, spec)
            }) { launchSystem(it, REMOVE) }
        }
        status = ui.text(card, state?.getString("status") ?: "APK загружается из проверенного репозитория компонента. Проверяются подпись и контрольная сумма. Установку подтверждает Android.", 15f)
        profiles = InstalledProfileCards(this, ui, root, worker, state)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh(); profiles.refresh() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("pending", pending?.name)
        state.putString("component", spec.pkg)
        profiles.save(state)
        if (!busy) state.putString("status", status.text.toString())
    }

    private fun refresh() {
        val current = ComponentPackages.installed(this, spec)
        version.text = if (current == null) "Не установлен" else "Установлен: ${current.versionName} (код ${current.longVersionCode})"
        val next = available?.takeIf { current == null || it.code > current.longVersionCode }
        remote.text = "${if (current == null) "Скачать" else "Обновить до"} ${next?.version} (код ${next?.code})"
        remote.visibility = if (next != null) View.VISIBLE else View.GONE
        install.visibility = if (pending != null) View.VISIBLE else View.GONE
        remove.visibility = if (current == null) View.GONE else View.VISIBLE
        listOf(check, pick, install, remote, remove).forEach { it.isEnabled = !busy }
    }

    private fun <T> work(message: String, run: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        status.text = message
        refresh()
        worker.execute {
            val result = runCatching(run)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                result.fold(done) { status.text = it.message ?: "Не удалось проверить компонент." }
                refresh()
            }
        }
    }

    private fun query() {
        available = null
        work("Проверяю выпуски ${spec.title}…", {
            var latest: ComponentAsset? = null
            ReleaseFeed.pages(spec.repository) { latest = ComponentCatalog.newest(it, spec, latest) }
            latest
        }) { newest ->
            available = newest
            val current = ComponentPackages.installed(this, spec)
            status.text = when {
                newest == null -> "Совместимый выпуск компонента не найден."
                current == null -> "Доступна установка."
                current.longVersionCode >= newest.code -> "Обновление не требуется."
                else -> "Доступна новая версия."
            }
        }
    }

    private fun prepared(apk: PreparedComponent) {
        pending = apk.file
        status.text = "Проверен ${spec.title} ${apk.version} (код ${apk.code})."
        // If the user left the screen during the download, wait for another explicit tap.
        if (hasWindowFocus()) handoff(apk)
    }

    private fun installPrepared() {
        val f = pending ?: return
        work("Проверяю APK…", { ComponentPackages.validate(this, spec, f) }) { apk -> handoff(apk) }
    }

    private fun componentInUse(): Boolean = CarLifeState.running && (
        (spec == DesktopComponent.DRIVEDECK && DexPrefs(this).desktopMode == DesktopModePolicy.DRIVEDECK) ||
        com.headswap.desk.component.DesktopComponents.selected(this)?.packageName == spec.pkg)

    private fun handoff(apk: PreparedComponent) {
        if (componentInUse()) {
            status.text = "Остановите сеанс компонента и нажмите «Продолжить установку»."
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            status.text = "Разрешите установку из источника HeadSwap и вернитесь сюда."
            launchSystem(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), SOURCE)
            return
        }
        val intent = runCatching { ComponentPackages.installerIntent(this, apk) }
            .getOrElse { status.text = it.message ?: "Системный установщик APK недоступен."; return }
        launchSystem(intent, INSTALL)
    }

    private fun launchSystem(intent: Intent, request: Int) {
        try { startActivityForResult(intent, request) }
        catch (e: RuntimeException) { status.text = "Не удалось открыть системное окно: ${e.message}"; refresh() }
    }

    @Deprecated("Platform Activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (profiles.result(requestCode, resultCode, data)) return
        when (requestCode) {
            PICK -> if (resultCode == RESULT_OK) data?.data?.let { uri ->
                pending = null
                work("Проверяю выбранный APK…", {
                    checkNotNull(contentResolver.openInputStream(uri)) { "Не удалось открыть файл." }.use { ComponentPackages.stage(this, spec, it) }
                }, ::prepared)
            }
            SOURCE -> if (packageManager.canRequestPackageInstalls()) installPrepared()
                else status.text = "Установка не разрешена. Можно продолжить позже."
            INSTALL -> if (resultCode == RESULT_OK) {
                pending = null; status.text = "Компонент установлен."
            } else status.text = "Установка не завершена. Можно повторить."
            REMOVE -> if (resultCode == RESULT_OK) {
                pending = null; available = null; status.text = "Компонент удалён."
            } else status.text = "Компонент остался установлен."
        }
        refresh()
    }

    private companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
        const val PICK = 1
        const val SOURCE = 2
        const val INSTALL = 3
        const val REMOVE = 4
    }
}
