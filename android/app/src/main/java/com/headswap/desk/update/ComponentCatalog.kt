package com.headswap.desk.update

import org.json.JSONArray
import com.headswap.desk.dex.DesktopModePolicy

/** An explicit allowlist: neither a stock launcher nor HeadSwap Desktop is an installable component. */
enum class DesktopComponent(val title: String, val pkg: String, val assetPrefix: String, val tagPrefix: String, val minCode: Long) {
    DRIVEDECK("DriveDeck · HeadSwap", DesktopModePolicy.DRIVEDECK_PACKAGE, "DriveDeck-HeadSwap", "component-drivedeck-", 263L);
}

data class ComponentAsset(val component: DesktopComponent, val version: String, val code: Long, val url: String,
                          val sha256: String, val bytes: Long)

object ComponentCatalog {
    const val MAX_BYTES = 96L * 1024 * 1024
    private val desktopTag = Regex("""v[0-9]+\.[0-9]+\.[0-9]+""")
    private val suffix = Regex("""([0-9]+\.[0-9]+\.[0-9]+)-code([1-9][0-9]{0,9})\.apk""")
    private val digest = Regex("""sha256:([0-9a-f]{64})""")

    // A component may ship alongside HeadSwap or by itself. Its own versionCode decides precedence.
    fun newest(page: JSONArray, component: DesktopComponent, previous: ComponentAsset?): ComponentAsset? {
        var best = previous
        for (i in 0 until page.length()) {
            val item = page.optJSONObject(i) ?: continue
            if (item.optBoolean("draft")) continue
            val tag = item.optString("tag_name")
            val assets = item.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name")
                if (!name.startsWith(component.assetPrefix + "-") || asset.optString("state") != "uploaded") continue
                val match = suffix.matchEntire(name.removePrefix(component.assetPrefix + "-")) ?: continue
                val (version, rawCode) = match.destructured
                val code = rawCode.toLong()
                if (code !in component.minCode..Int.MAX_VALUE.toLong() || (best != null && best.code >= code)) continue
                if (!desktopTag.matches(tag) && tag != component.tagPrefix + code) continue
                val checksum = digest.matchEntire(asset.optString("digest"))?.groupValues?.get(1) ?: continue
                val bytes = asset.optLong("size", 0)
                val url = "https://github.com/${UpdateLogic.REPO}/releases/download/$tag/$name"
                if (asset.optString("browser_download_url") != url || bytes !in 1..MAX_BYTES) continue
                best = ComponentAsset(component, version, code, url, checksum, bytes)
            }
        }
        return best
    }
}
