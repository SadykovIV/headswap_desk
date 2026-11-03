package com.headswap.desk.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    // Use the published naming convention, with the companion first as in a multi-APK release.
    private val owner = "https://github.com/SadykovIV/headswap_desk"
    private fun desktop(v: String) = "HeadSwap-Desktop-$v-experimental.apk"
    private fun release(v: String, vararg files: String, draft: Boolean = false, repo: String = owner) =
        JSONObject().put("tag_name", "v$v").put("draft", draft).put("prerelease", true).put("assets",
            JSONArray().apply { files.forEach { name ->
                put(JSONObject().put("name", name).put("state", "uploaded")
                    .put("browser_download_url", "$repo/releases/download/v$v/$name"))
            } })

    private fun page(vararg items: JSONObject) = JSONArray(items.toList())

    @Test fun selectsOnlyDesktopAssetFromOwnPrerelease() {
        assertEquals("https://api.github.com/repos/SadykovIV/headswap_desk/releases", UpdateLogic.API_RELEASES)
        val rel = UpdateLogic.newestInPage(page(release("0.2.13", "DriveDeck-HeadSwap-0.6.7-code263.apk",
            "headswap-0.2.13.apk", "SHA256SUMS.txt", desktop("0.2.13"))))!!
        assertEquals("0.2.13", rel.version)
        assertEquals("$owner/releases/download/v0.2.13/HeadSwap-Desktop-0.2.13-experimental.apk", rel.apkUrl)
    }

    @Test fun neverOffersLoneOtherOrMismatchedApk() {
        assertNull(UpdateLogic.newestInPage(page(
            release("0.2.13", "DriveDeck-HeadSwap-0.6.7-code263.apk"),
            release("3.0.0", "headswap-3.0.0.apk"),
            release("0.2.14", desktop("0.2.13")),
            release("0.2.13", desktop("0.2.13"), repo = "https://github.com/YegorkaPomidorka/headswap"),
        )))
    }

    @Test fun canonicalAssetIsPreferredInEitherOrderAndRestoredWithItsExactUrl() {
        val canonical = "HeadSwap-Desktop-0.5.1.apk"
        val legacy = desktop("0.5.1")
        for (files in listOf(listOf(canonical), listOf(legacy, canonical), listOf(canonical, legacy))) {
            val rel = UpdateLogic.newestInPage(page(release("0.5.1", *files.toTypedArray()).put("prerelease", false)))!!
            val restored = UpdateLogic.Release.fromTag(rel.tag, rel.apkName)!!
            assertEquals("$owner/releases/download/v0.5.1/$canonical", restored.apkUrl)
        }
        assertEquals(legacy, UpdateLogic.Release.fromTag("v0.5.1")?.apkName)
        assertNull(UpdateLogic.Release.fromTag("v0.5.1", "HeadSwap-Desktop-0.5.0.apk"))
    }

    @Test fun untrustedCanonicalUrlCannotDisplaceVerifiedLegacyAsset() {
        val release = release("0.5.1", "HeadSwap-Desktop-0.5.1.apk", desktop("0.5.1"))
        release.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "$owner/releases/download/v0.5.0/HeadSwap-Desktop-0.5.0.apk")
        val rel = UpdateLogic.newestInPage(page(release))!!
        assertEquals("$owner/releases/download/v0.5.1/${desktop("0.5.1")}", UpdateLogic.Release.fromTag(rel.tag, rel.apkName)?.apkUrl)
    }

    @Test fun checksTagAndAssetStateBeforeOfferingDownload() {
        assertNull(UpdateLogic.Release.fromTag("v0.2.14/../../other"))
        assertNull(UpdateLogic.Release.fromTag("v0.2.14-beta3"))
        val incomplete = release("0.2.14", desktop("0.2.14"))
        incomplete.getJSONArray("assets").getJSONObject(0).put("state", "starter")
        assertNull(UpdateLogic.newestInPage(page(incomplete)))
    }

    @Test fun ordersNumericallyAcrossPagesIgnoringDraftsAndNonDesktopReleases() {
        val first = UpdateLogic.newestInPage(page(release("0.2.99", desktop("0.2.99"), draft = true),
            release("0.3.1", "DriveDeck.apk"), release("0.2.9", desktop("0.2.9"))))
        val second = UpdateLogic.newestInPage(page(release("0.2.13", desktop("0.2.13")),
            release("0.2.8", desktop("0.2.8"))), first)
        assertEquals("0.2.13", second?.version)
        assertEquals("0.2.13", UpdateLogic.newestInPage(JSONArray(), second)?.version)
    }

    @Test fun comparesInstalledDesktopVersionWithoutExperimentalSuffix() {
        assertFalse(UpdateLogic.isNewer("0.2.13", "0.2.13-desktop-experimental"))
        assertTrue(UpdateLogic.isNewer("0.2.14", "0.2.13-desktop-experimental"))
        assertFalse(UpdateLogic.isNewer("0.2.9", "0.2.13-desktop-experimental"))
        assertFalse(UpdateLogic.isNewer("latest", "0.2.13"))
        assertFalse(UpdateLogic.isNewer("0.2.13", "?"))
    }

    @Test fun optOutAndCheckIntervals() {
        val now = 100 * 3600_000L
        assertFalse(UpdateLogic.shouldCheck(false, 0, now))
        assertTrue(UpdateLogic.shouldCheck(true, 0, now))
        assertFalse(UpdateLogic.shouldCheck(true, now, now + UpdateLogic.CHECK_INTERVAL_MS - 1))
        assertTrue(UpdateLogic.shouldCheck(true, now, now + UpdateLogic.CHECK_INTERVAL_MS))
        assertTrue(UpdateLogic.shouldCheck(true, now, now - 1))
        val retry = now - UpdateLogic.CHECK_INTERVAL_MS + UpdateLogic.RETRY_MS
        assertFalse(UpdateLogic.shouldCheck(true, retry, now + UpdateLogic.RETRY_MS - 1))
        assertTrue(UpdateLogic.shouldCheck(true, retry, now + UpdateLogic.RETRY_MS))
    }
}
