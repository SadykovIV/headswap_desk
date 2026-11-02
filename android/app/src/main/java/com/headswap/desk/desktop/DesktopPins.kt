package com.headswap.desk.desktop

import android.content.Context
import android.content.SharedPreferences

/** Ordered launcher components for the Android Desktop rail. All changes originate on the UI thread. */
internal class DesktopPins(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    private constructor(prefs: SharedPreferences) : this(
        { prefs.getString(KEY, null) },
        { value -> prefs.edit().putString(KEY, value).apply() },
    )

    constructor(context: Context) : this(context.getSharedPreferences("android_desktop_rail", Context.MODE_PRIVATE))

    fun entries(catalog: List<DesktopAppCatalog.Entry>): List<DesktopAppCatalog.Entry> {
        val available = catalog.associateBy { it.key }
        return order(catalog).mapNotNull { available[it] }
    }

    /** index is the final position among visible pins. Without it, append a new pin. */
    fun pin(catalog: List<DesktopAppCatalog.Entry>, key: String, index: Int? = null): Boolean {
        val available = catalog.mapTo(mutableSetOf()) { it.key }
        if (key !in available) return false
        val current = order(catalog)
        if (index == null && key in current) return false

        val next = current.toMutableList().apply { remove(key) }
        val visible = next.filter { it in available }
        val anchor = index?.let { visible.getOrNull(it.coerceIn(0, visible.size)) }
        if (anchor == null) next.add(key) else next.add(next.indexOf(anchor), key)
        if (current == next) return false
        save(next)
        return true
    }

    fun remove(catalog: List<DesktopAppCatalog.Entry>, key: String): Boolean {
        val next = order(catalog).toMutableList()
        if (!next.remove(key)) return false
        save(next)
        return true
    }

    private fun order(catalog: List<DesktopAppCatalog.Entry>): List<String> {
        // null means the rail has never been initialized. An empty string is a deliberate empty rail.
        read()?.let { return it.lineSequence().filter(String::isNotBlank).distinct().toList() }
        if (catalog.isEmpty()) return emptyList()
        val byPackage = catalog.groupBy { it.packageName }
        val preferred = FIRST_PACKAGES.mapNotNull { byPackage[it]?.firstOrNull() }
        return (preferred + catalog).distinctBy { it.packageName }.take(4).map { it.key }.also(::save)
    }

    private fun save(keys: List<String>) = write(keys.joinToString("\n"))

    private companion object {
        const val KEY = "pinned_components"
        val FIRST_PACKAGES = listOf(
            "ru.yandex.yandexnavi", "ru.yandex.music", "com.google.android.apps.maps",
            "com.google.android.apps.youtube.music", "com.google.android.dialer", "com.android.dialer",
        )
    }
}
