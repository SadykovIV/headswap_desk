package com.headswap.desk.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComponentCatalogTest {
    private val dd = DesktopComponent.DRIVEDECK
    private val sha = "a".repeat(64)
    private fun release(tag: String, code: Int, version: String = "0.6.7"): JSONObject {
        val name = "DriveDeck-HeadSwap-$version-code$code.apk"
        val url = "https://github.com/${UpdateLogic.REPO}/releases/download/$tag/$name"
        return JSONObject().put("tag_name", tag).put("prerelease", true).put("assets", JSONArray().put(
            JSONObject().put("name", name).put("size", 1550).put("digest", "sha256:$sha")
                .put("state", "uploaded").put("browser_download_url", url)))
    }
    private fun asset(release: JSONObject) = release.getJSONArray("assets").getJSONObject(0)
    private fun page(vararg items: JSONObject) = JSONArray(items.toList())
    @Test fun externalPublisherUpdatesUseTheirRepositoryAndVersionFormat() {
        val spec = DesktopComponent.CARBRIDGE
        val tag = "v0.52-headswap.2"
        val name = "CarBridge-0.52-HeadSwap-profile-v2-debug.apk"
        val a = JSONObject().put("name", name).put("size", 4345554).put("digest", "sha256:$sha")
            .put("state", "uploaded").put("browser_download_url", "https://github.com/${spec.repository}/releases/download/$tag/$name")
        val item = JSONObject().put("tag_name", tag).put("assets", JSONArray().put(a))
        val latest = ComponentCatalog.newest(page(item), spec, null)!!
        assertEquals(2L, latest.code)
        assertEquals("0.52-headswap.2", latest.version)
        assertNull(ComponentCatalog.newest(page(item), dd, null))
        a.put("browser_download_url", "https://github.com/${UpdateLogic.REPO}/releases/download/$tag/$name")
        assertNull(ComponentCatalog.newest(page(item), spec, null))
    }
    @Test fun perAppDpiCompanionCanUpdateAnExistingCode304FromAHostRelease() {
        val old = ComponentCatalog.newest(page(release("v0.4.0", 304, "0.6.8")), dd, null)
        val asset = ComponentCatalog.newest(page(release("v0.4.1", 305, "0.6.8")), dd, old)!!
        assertEquals(305L, asset.code)
    }

    @Test fun independentReleaseWinsByComponentCodeAcrossPages() {
        val first = ComponentCatalog.newest(page(release("v0.2.16", 263)), dd, null)!!
        val newer = ComponentCatalog.newest(page(release("component-drivedeck-301", 301, "0.6.8")), dd, first)!!
        assertEquals(301L, newer.code)
        assertEquals(newer, ComponentCatalog.newest(page(release("v9.0.0", 263)), dd, newer))
        assertNull(UpdateLogic.newestInPage(page(release("component-drivedeck-301", 301))))
    }
    @Test fun rejectUntrustedOrIncompatibleCatalogRows() {
        val bad = listOf(
            release("v0.2.16", 263).put("draft", true),
            release("component-drivedeck-300", 301),
            release("stable-code300", 301),
            release("v0.2.16", 258),
            release("v0.2.16", 263).also { asset(it).put("digest", "sha256:") },
            release("v0.2.16", 263).also { asset(it).put("name", "DriveDeck-0.6.7.apk") },
            release("v0.2.16", 263).also { asset(it).put("state", "new") },
            release("v0.2.16", 263).also { asset(it).put("size", ComponentCatalog.MAX_BYTES + 1) },
            release("v0.2.16", 263).also { asset(it).put("browser_download_url", "https://github.com/RotkivKiv/DriveDeck-Updates/releases/download/stable-code300/DriveDeck-0.6.8.apk") })
        bad.forEach { assertNull(it.toString(), ComponentCatalog.newest(page(it), dd, null)) }
    }
}
