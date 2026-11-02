package com.headswap.desk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuAppsTest {
    private val fermata = HuApps.CATALOG.first { it.name == "Fermata Auto" }
    private val parkedTube = HuApps.CATALOG.first { it.name == "ParkedTube" }
    private val browser = HuApps.CATALOG.first { it.name == "AABrowser" }

    @Test
    fun catalogHasThreeAppsWithGithubReleases() {
        assertEquals(
            listOf("me.aap.fermata.auto.dear.google.why", "dev.codex.aaparkedyoutube", "com.kododake.aabrowser"),
            HuApps.CATALOG.map { it.pkg },
        )
        assertTrue(HuApps.CATALOG.all { it.url.startsWith("https://github.com/") && it.url.endsWith("/releases") })
    }

    @Test
    fun installedComesFromPackageCheck() {
        val installed = setOf(fermata.pkg)
        assertTrue(HuApps.status(fermata, { it in installed }, 36).installed)
        assertFalse(HuApps.status(browser, { it in installed }, 36).installed)
    }

    @Test
    fun aabrowserNeedsAndroid15() {
        assertTrue(HuApps.status(browser, { false }, 34).unsupported)
        assertFalse(HuApps.status(browser, { false }, 35).unsupported)
        assertFalse(HuApps.status(browser, { false }, 36).unsupported)
        assertTrue(browser.reqRes != null)
    }

    @Test
    fun parkedTubeNeedsAndroid15() {
        assertEquals(35, parkedTube.minSdk)
        assertTrue(HuApps.status(parkedTube, { false }, 34).unsupported)
        assertFalse(HuApps.status(parkedTube, { false }, 35).unsupported)
        assertTrue(parkedTube.reqRes != null)
    }

    @Test
    fun fermataHasNoSdkRestriction() {
        assertFalse(HuApps.status(fermata, { false }, 26).unsupported)
        assertNull(fermata.reqRes)
    }

    private val self = "com.headswap.desk"

    private fun select(candidates: List<AppCandidate>, installed: Set<String> = emptySet()) =
        HuApps.select(candidates, self, HuApps.CATALOG) { it in installed }

    @Test
    fun selectExcludesSelfSystemAndPlay() {
        val r = select(
            listOf(
                AppCandidate(self, false, null),
                AppCandidate("com.google.android.apps.maps", true, null),
                AppCandidate("com.google.android.googlequicksearchbox", true, "com.android.vending"),
                AppCandidate("com.some.player", false, "com.android.vending"),
                AppCandidate("com.some.sideloaded", false, null),
            ),
        )
        assertEquals(listOf("com.some.sideloaded"), r.installed)
    }

    @Test
    fun selectKeepsNullPackageInstallerAndXiaomi() {
        val r = select(
            listOf(
                AppCandidate("com.a.adb", false, null),
                AppCandidate("com.b.pkginstaller", false, "com.google.android.packageinstaller"),
                AppCandidate("com.c.xiaomi", false, "com.xiaomi.discover"),
            ),
        )
        assertEquals(listOf("com.a.adb", "com.b.pkginstaller", "com.c.xiaomi"), r.installed)
    }

    @Test
    fun catalogAppMissedByQueriesStillInstalled() {
        val r = select(emptyList(), installed = setOf(fermata.pkg))
        assertEquals(listOf(fermata.pkg), r.installed)
        assertEquals(listOf(parkedTube, browser), r.recommended)
    }

    @Test
    fun catalogAppFromPlayStillInstalledIfPresent() {
        val r = select(listOf(AppCandidate(fermata.pkg, false, "com.android.vending")), installed = setOf(fermata.pkg))
        assertEquals(listOf(fermata.pkg), r.installed)
    }

    @Test
    fun recommendedIsWholeCatalogWhenNothingInstalled() {
        val r = select(emptyList())
        assertTrue(r.installed.isEmpty())
        assertEquals(HuApps.CATALOG, r.recommended)
    }

    @Test
    fun recommendedEmptyWhenAllCatalogInstalled() {
        val r = select(emptyList(), installed = HuApps.CATALOG.map { it.pkg }.toSet())
        assertTrue(r.recommended.isEmpty())
        assertEquals(HuApps.CATALOG.map { it.pkg }, r.installed)
    }

    @Test
    fun recommendedSkipsCatalogAppFoundByQueries() {
        val r = select(listOf(AppCandidate(browser.pkg, false, null)))
        assertTrue(browser !in r.recommended)
        assertEquals(listOf(browser.pkg), r.installed)
    }

    @Test
    fun selectDeduplicatesByPackage() {
        val r = select(
            listOf(
                AppCandidate("com.a.player", false, null),
                AppCandidate("com.a.player", false, null),
                AppCandidate(fermata.pkg, false, null),
            ),
            installed = setOf(fermata.pkg),
        )
        assertEquals(listOf("com.a.player", fermata.pkg), r.installed)
    }

    @Test
    fun catalogSizeIsThree() {
        assertEquals(3, HuApps.CATALOG.size)
    }
}
