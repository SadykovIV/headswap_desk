package com.headswap.desk.update

import org.json.JSONArray

/** Only published HeadSwap Desktop releases that actually contain the Desktop APK can be offered. */
object UpdateLogic {
    const val REPO = "SadykovIV/headswap_desk"
    const val API_RELEASES = "https://api.github.com/repos/$REPO/releases"
    const val CHECK_INTERVAL_MS = 6 * 3600_000L
    const val RETRY_MS = 3600_000L

    private val tagPattern = Regex("""v(\d+\.\d+\.\d+)""")

    class Release private constructor(val tag: String, val version: String, val apkName: String) {
        internal val canonical get() = apkName == "HeadSwap-Desktop-$version.apk"
        val apkUrl get() = "https://github.com/$REPO/releases/download/$tag/$apkName"

        companion object {
            fun fromTag(tag: String?, apkName: String? = null): Release? {
                val v = tagPattern.matchEntire(tag ?: return null)?.groupValues?.get(1) ?: return null
                if (parseVersion(v) == null) return null
                val legacy = "HeadSwap-Desktop-$v-experimental.apk"
                // Old installations stored only the tag after verifying the legacy asset.
                val name = apkName ?: legacy
                return if (name == legacy || name == "HeadSwap-Desktop-$v.apk") Release(tag, v, name) else null
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
            val tag = item.optString("tag_name")
            val assets = item.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("state") != "uploaded") continue
                val rel = Release.fromTag(tag, asset.optString("name")) ?: continue
                if (asset.optString("browser_download_url") != rel.apkUrl) continue
                if (best == null || isNewer(rel.version, best.version) ||
                    (rel.tag == best.tag && rel.canonical && !best.canonical)) best = rel
            }
        }
        return best
    }

    fun shouldCheck(enabled: Boolean, lastCheckMs: Long, nowMs: Long): Boolean =
        enabled && (nowMs - lastCheckMs >= CHECK_INTERVAL_MS || nowMs < lastCheckMs)
}
