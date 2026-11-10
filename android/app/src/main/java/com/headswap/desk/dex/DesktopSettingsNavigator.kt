package com.headswap.desk.dex

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Settings are a single scroll of blocks; activation and permission details use nested pages. */
internal class DesktopSettingsNavigator(
    private val ui: DesktopSetupViews,
    private val scroll: ScrollView,
    container: LinearLayout,
    private val exit: () -> Unit,
) {
    private data class Page(val title: String, val body: LinearLayout, val summary: TextView)
    private val pages = linkedMapOf<String, Page>()
    private val positions = mutableMapOf<String, Int>()
    private val history = mutableListOf<String?>()
    private val heading: TextView
    private val index: LinearLayout
    private val content: LinearLayout
    private var current: String? = null
    private var entry: String? = null
    private var revision = 0

    init {
        val header = LinearLayout(container.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            container.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        ui.button(header, "Назад", ::back).layoutParams = LinearLayout.LayoutParams(-2, -2)
        heading = ui.text(header, "Настройки", 23f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(ui.dp(12), 0, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        index = ui.group(container)
        content = ui.group(container)
    }

    fun block(title: String): LinearLayout = ui.section(index, title)

    fun subpage(id: String, title: String, summary: String): LinearLayout {
        check(id.isNotEmpty() && id !in pages)
        val entry = ui.section(index)
        ui.button(entry, title) { open(id) }
        val detail = ui.text(entry, summary, 13f)
        val body = ui.group(content).apply { visibility = View.GONE }
        pages[id] = Page(title, body, detail)
        return ui.section(body)
    }

    fun summary(id: String, text: String) {
        pages[id]?.summary?.let { if (it.text.toString() != text) it.text = text }
    }
    fun open(id: String?) {
        require(id == null || id in pages)
        positions[current.orEmpty()] = scroll.scrollY
        if (id == null) { history.clear(); entry = null }
        else if (id != current) history.add(current)
        current = id
        render()
    }
    /** A permission screen opened by the setup wizard returns to its caller with one Back. */
    fun openEntry(id: String) {
        open(id)
        history.clear()
        entry = id
    }
    fun back() {
        if (current == null || current == entry && history.isEmpty()) exit() else {
            positions[current.orEmpty()] = scroll.scrollY
            current = history.removeLastOrNull()
            render()
        }
    }
    private fun render() {
        index.visibility = if (current == null) View.VISIBLE else View.GONE
        pages.forEach { (id, page) -> page.body.visibility = if (current == id) View.VISIBLE else View.GONE }
        heading.text = current?.let { pages.getValue(it).title } ?: "Настройки"
        val target = ++revision
        val position = positions[current.orEmpty()] ?: 0
        scroll.post { if (target == revision) scroll.scrollTo(0, position) }
    }
    fun save(state: Bundle) {
        positions[current.orEmpty()] = scroll.scrollY
        state.putString("settings_section", current)
        state.putString("settings_entry", entry)
        state.putStringArrayList("settings_history", ArrayList(history.map { it.orEmpty() }))
        state.putBundle("settings_scroll", Bundle().apply { positions.forEach { (key, value) -> putInt(key, value) } })
    }
    fun restore(state: Bundle?) {
        current = state?.getString("settings_section")?.takeIf { it in pages }
        entry = state?.getString("settings_entry")?.takeIf { it in pages }
        history.clear()
        state?.getStringArrayList("settings_history")?.filter { it.isEmpty() || it in pages }
            ?.forEach { history.add(it.takeIf(String::isNotEmpty)) }
        state?.getBundle("settings_scroll")?.let { saved -> saved.keySet().forEach { positions[it] = saved.getInt(it) } }
        render()
    }
}
