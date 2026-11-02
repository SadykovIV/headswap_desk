package com.headswap.desk.update

/**
 * Чистая логика обновлений без Android: релизы GitHub, сравнение версий, выбор APK, когда проверять.
 * Релиз: тег vX.Y.Z, файл headswap-X.Y.Z.apk; «последний» определяет сам GitHub (не черновик и не пре-релиз).
 */
object UpdateLogic {
    const val REPO = "YegorkaPomidorka/headswap"
    const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    /** Запасной путь, если API ответил не 200 (лимит 60 запросов в час на IP): редирект на страницу тега. */
    const val PAGE_LATEST = "https://github.com/$REPO/releases/latest"

    /** Автопроверка не чаще раза в 6 часов; после неудачи повтор через час. */
    const val CHECK_INTERVAL_MS = 6 * 3600_000L
    const val RETRY_MS = 3600_000L

    private const val MAX_NOTES = 1500

    /** Найденный релиз; size = -1 и sha256 = null, если известен только тег (запасной путь). */
    data class Release(
        val version: String,
        val tag: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String?,
        val notes: String,
        val page: String,
    )

    data class Asset(val name: String, val url: String, val size: Long, val digest: String?)

    /** "v1.1.10", "1.1.10", "1.1.10-cx5test1" -> [1, 1, 10]; без цифр в начале - null. */
    fun parseVersion(s: String): List<Int>? {
        val m = Regex("""^[vV]?(\d+(?:\.\d+)*)""").find(s.trim()) ?: return null
        return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /** remote новее local; недостающие части = 0 (1.2 == 1.2.0), суффикс тестовой сборки не учитывается. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun versionFromTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** APK релиза: headswap-<версия>.apk, иначе единственный .apk среди файлов. */
    fun pickApk(assets: List<Asset>, version: String): Asset? =
        assets.firstOrNull { it.name.equals("headswap-$version.apk", ignoreCase = true) }
            ?: assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.singleOrNull()

    /** digest файла релиза GitHub "sha256:<hex>" -> hex в нижнем регистре; другие алгоритмы - null. */
    fun sha256(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        return d.substring(7).lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Location редиректа .../releases/tag/v1.1.10 -> "v1.1.10". */
    fun tagFromLocation(location: String?): String? =
        location?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.substringBefore('#')?.takeIf { it.isNotBlank() }

    fun assetUrl(tag: String, version: String) = "https://github.com/$REPO/releases/download/$tag/headswap-$version.apk"

    fun tagPage(tag: String) = "https://github.com/$REPO/releases/tag/$tag"

    /** Описание релиза для диалога: без \r и лишних пустых строк, не длиннее MAX_NOTES. */
    fun notes(body: String?): String {
        val t = (body ?: "").replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (t.length > MAX_NOTES) t.take(MAX_NOTES).trimEnd() + "…" else t
    }

    /** Пора ли автопроверке: включена и с прошлой прошло [CHECK_INTERVAL_MS] (часы перевели назад - тоже пора). */
    fun shouldCheck(enabled: Boolean, lastCheckMs: Long, nowMs: Long): Boolean =
        enabled && (nowMs - lastCheckMs >= CHECK_INTERVAL_MS || nowMs < lastCheckMs)
}
