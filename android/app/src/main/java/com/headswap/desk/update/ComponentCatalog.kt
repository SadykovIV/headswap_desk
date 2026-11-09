package com.headswap.desk.update

import org.json.JSONArray
import com.headswap.desk.dex.DesktopModePolicy

/** An explicit allowlist: neither a stock launcher nor HeadSwap Desktop is an installable component. */
enum class DesktopComponent(val title: String, val pkg: String, val assetPrefix: String, val tagPrefix: String, val minCode: Long,
                            val repository: String = UpdateLogic.REPO, val publisherSha256: String? = null) {
    DRIVEDECK("DriveDeck · HeadSwap", DesktopModePolicy.DRIVEDECK_PACKAGE, "DriveDeck-HeadSwap", "component-drivedeck-", 263L),
    CARBRIDGE("CarBridge · HeadSwap", "com.example.carbridge.headswap", "CarBridge-", "v", 2L,
        "kukulkanod-source/CarBridge-0.52-HeadSwap-profile",
        "4cb7c733435e69d2da1056711aa97021dfd5fd0235863cf76dc96c3406887d52");
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
                if (asset.optString("state") != "uploaded") continue
                val (version, rawCode) = releaseVersion(component, tag, name) ?: continue
                val code = rawCode.toLong()
                if (code !in component.minCode..Int.MAX_VALUE.toLong() || (best != null && best.code >= code)) continue
                val checksum = digest.matchEntire(asset.optString("digest"))?.groupValues?.get(1) ?: continue
                val bytes = asset.optLong("size", 0)
                val url = "https://github.com/${component.repository}/releases/download/$tag/$name"
                if (asset.optString("browser_download_url") != url || bytes !in 1..MAX_BYTES) continue
                best = ComponentAsset(component, version, code, url, checksum, bytes)
            }
        }
        return best
    }

    private fun releaseVersion(component: DesktopComponent, tag: String, name: String): Pair<String, String>? {
        if (component == DesktopComponent.CARBRIDGE) {
            val match = Regex("v([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)-headswap\\.([1-9][0-9]{0,8})").matchEntire(tag) ?: return null
            val (version, code) = match.destructured
            if (name != "CarBridge-$version-HeadSwap-profile-v$code-debug.apk") return null
            return "$version-headswap.$code" to code
        }
        if (!name.startsWith(component.assetPrefix + "-")) return null
        val match = suffix.matchEntire(name.removePrefix(component.assetPrefix + "-")) ?: return null
        val (version, code) = match.destructured
        if (!desktopTag.matches(tag) && tag != component.tagPrefix + code) return null
        return version to code
    }
}
