package com.headswap.desk

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Язык интерфейса приложения: «system» (по умолчанию), «en» или «ru».
 * Android 13+: системный per-app язык (LocaleManager, виден и в системных настройках, см. res/xml/locales_config.xml).
 * Android 10–12: выбор хранится в prefs, контексты activity/сервисов оборачиваются через [wrap] в attachBaseContext.
 */
object AppLocale {
    const val SYSTEM = "system"
    val TAGS = listOf(SYSTEM, "en", "ru")

    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    /** Текущий выбор: на 33+ из LocaleManager (его могли сменить в системных настройках), ниже из prefs. */
    fun get(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = ctx.getSystemService(LocaleManager::class.java).applicationLocales
            if (list.isEmpty) return SYSTEM
            return list[0].language.takeIf { it in TAGS } ?: SYSTEM
        }
        return stored(ctx)
    }

    /** Сменить язык. На 33+ система сама пересоздаёт activity; ниже вызывающий делает recreate(). */
    fun set(ctx: Context, tag: String) {
        val t = if (tag in TAGS) tag else SYSTEM
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (t == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(t)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, t).apply()
    }

    /** Нужен ли ручной recreate() после [set]. */
    val needsRecreate: Boolean get() = Build.VERSION.SDK_INT < 33

    private fun stored(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /**
     * Контекст с выбранным языком для Android 10–12 (на 33+ система уже подставила per-app язык, возвращается как есть).
     * Использовать в attachBaseContext и там, где строки берутся из applicationContext.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = stored(base)
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(locale))
        return base.createConfigurationContext(cfg)
    }
}
