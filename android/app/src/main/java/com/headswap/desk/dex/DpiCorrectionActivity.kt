package com.headswap.desk.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import com.headswap.desk.desktop.DesktopAppCatalog
import java.util.concurrent.Executors

/** The selected packages are independent of the phone's installed-app inventory. */
class DpiCorrectionActivity : Activity() {
    private lateinit var prefs: DexPrefs
    private lateinit var ui: DesktopSetupViews
    private lateinit var rows: LinearLayout
    private lateinit var add: Button
    private val loader = Executors.newSingleThreadExecutor()
    private var installed: Map<String, String> = emptyMap()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = DexPrefs(this)
        ui = DesktopSetupViews(this)
        val page = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(ui.background); addView(page) })
        ui.button(page, "Назад") { finish() }
        ui.text(page, "Исправить DPI приложения", 23f, true)
        val settings = ui.section(page)
        ui.toggle(settings, "Включить исправление DPI", prefs.compactMapSymbols) { prefs.compactMapSymbols = it }
        ui.text(settings, "Для приложений в списке HeadSwap увеличивает внутреннее разрешение и выводит изображение в исходном размере окна ГУ. Выбор применяется при следующем подключении Android Desktop или DriveDeck.")
        val apps = ui.section(page, "Приложения")
        rows = ui.group(apps)
        add = ui.button(apps, "Добавить приложение") { chooseApp() }
        renderRows()
    }

    override fun onResume() {
        super.onResume()
        add.isEnabled = false
        loader.execute {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val candidates = packageManager.queryIntentActivities(query, 0).mapNotNull { result ->
                val info = result.activityInfo ?: return@mapNotNull null
                DesktopAppCatalog.Entry(info.packageName, info.name, runCatching { result.loadLabel(packageManager).toString() }.getOrDefault(info.packageName),
                    info.enabled && info.applicationInfo.enabled, info.exported)
            }
            val inventory = DesktopAppCatalog.select(candidates, packageName).distinctBy { it.packageName }
                .associate { it.packageName to it.label }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                installed = inventory
                add.isEnabled = true
                renderRows()
            }
        }
    }

    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }

    private fun label(pkg: String): String = installed[pkg] ?: when (pkg) {
        "ru.yandex.yandexmaps" -> "Яндекс Карты"
        "ru.yandex.yandexnavi" -> "Яндекс Навигатор"
        else -> pkg
    }

    private fun renderRows() {
        rows.removeAllViews()
        val selected = prefs.dpiCorrectionPackages
        if (selected.isEmpty()) ui.text(rows, "Список пуст. Добавьте приложение для коррекции DPI.")
        for (pkg in selected.sortedBy(::label)) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(58)
            }
            rows.addView(row)
            val icon = ImageView(this)
            icon.setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrDefault(packageManager.defaultActivityIcon))
            row.addView(icon, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
            val labels = ui.column().apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            ui.text(labels, label(pkg), 16f, true)
            ui.text(labels, pkg, 12f)
            row.addView(ImageButton(this).apply {
                contentDescription = "Убрать ${label(pkg)} из списка"
                setImageResource(android.R.drawable.ic_menu_delete)
                setOnClickListener {
                    prefs.dpiCorrectionPackages = prefs.dpiCorrectionPackages - pkg
                    renderRows()
                }
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        }
    }

    private fun chooseApp() {
        val available = installed.keys.filterNot(prefs.dpiCorrectionPackages::contains)
        val content = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val search = EditText(this).apply { hint = "Поиск по названию или пакету"; setSingleLine(true) }
        val list = ListView(this)
        val empty = android.widget.TextView(this).apply { text = "Приложения не найдены"; gravity = Gravity.CENTER }
        content.addView(search)
        content.addView(list, LinearLayout.LayoutParams(-1, ui.dp(280)))
        content.addView(empty)
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        list.adapter = adapter
        var visible: List<String> = emptyList()
        fun filter(query: String) {
            visible = available.filter { label(it).contains(query, true) || it.contains(query, true) }
            adapter.clear()
            adapter.addAll(visible.map { "${label(it)}\n$it" })
            list.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        }
        filter("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) = filter(text?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        val dialog = AlertDialog.Builder(this).setTitle("Добавить приложение").setView(content)
            .setNegativeButton("Отмена", null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            prefs.dpiCorrectionPackages = prefs.dpiCorrectionPackages + visible[position]
            renderRows()
            dialog.dismiss()
        }
        dialog.show()
    }
}
