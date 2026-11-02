package com.headswap.desk.update

import org.json.JSONArray

/** Only published HeadSwap Desktop releases that actually contain the Desktop APK can be offered. */
object UpdateLogic {
    const val REPO = "SadykovIV/headswap_desk"
    const val API_RELEASES = "https://api.github.com/repos/$REPO/releases"
    const val CHECK_INTERVAL_MS = 6 * 3600_000L
    const val RETRY_MS = 3600_000L

    // Desktop's published prereleases use numeric tags (v0.2.13) and an "-experimental" APK.
    private val tagPattern = Regex("""v(\d+\.\d+\.\d+)""")

    class Release private constructor(val tag: String, val version: String) {
        val apkName get() = "HeadSwap-Desktop-$version-experimental.apk"
        val apkUrl get() = "https://github.com/$REPO/releases/download/$tag/$apkName"

        companion object {
            fun fromTag(tag: String?): Release? {
                val v = tagPattern.matchEntire(tag ?: return null)?.groupValues?.get(1) ?: return null
                return if (parseVersion(v) != null) Release(tag, v) else null
            }
        }
    }

    fun parseVersion(s: String): List<Int>? {
        val m = Regex("""^[vV]?(\d+(?:\.\d+)*)""").find(s.trim()) ?: return null
        return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /** Numeric comparison; 0.2.13-desktop-experimental is the installed version of release v0.2.13. */
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

    /** Include published prereleases. A lone DriveDeck, upstream, or renamed APK is not an update. */
    fun newestInPage(page: JSONArray, previous: Release? = null): Release? {
        var best = previous
        for (i in 0 until page.length()) {
            val item = page.optJSONObject(i) ?: continue
            if (item.optBoolean("draft")) continue
            val rel = Release.fromTag(item.optString("tag_name")) ?: continue
            if (best != null && !isNewer(rel.version, best.version)) continue
            val assets = item.optJSONArray("assets") ?: continue
            if ((0 until assets.length()).any { j ->
                    val a = assets.optJSONObject(j)
                    a?.optString("state") == "uploaded" && a.optString("name") == rel.apkName &&
                        a.optString("browser_download_url") == rel.apkUrl
                }) best = rel
        }
        return best
    }

    fun shouldCheck(enabled: Boolean, lastCheckMs: Long, nowMs: Long): Boolean =
        enabled && (nowMs - lastCheckMs >= CHECK_INTERVAL_MS || nowMs < lastCheckMs)
}
