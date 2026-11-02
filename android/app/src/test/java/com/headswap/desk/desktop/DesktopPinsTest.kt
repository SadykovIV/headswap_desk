package com.headswap.desk.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopPinsTest {
    private fun app(pkg: String, cls: String = "$pkg.Main") = DesktopAppCatalog.Entry(pkg, cls, pkg)
    private val maps = app("com.google.android.apps.maps")
    private val music = app("ru.yandex.music")
    private val navigator = app("ru.yandex.yandexnavi")
    private val dialer = app("com.android.dialer")
    private val extra = app("example.extra")
    private val catalog = listOf(extra, maps, dialer, music, navigator)
    private var saved: String? = null
    private fun open() = DesktopPins({ saved }, { saved = it })

    @Test fun firstCatalogSeedsOnceAndEmptyUserSelectionSurvivesRestart() {
        val pins = open()
        assertEquals(emptyList<DesktopAppCatalog.Entry>(), pins.entries(emptyList()))
        assertNull(saved)
        assertEquals(listOf(navigator, music, maps, dialer), pins.entries(catalog))
        for (entry in pins.entries(catalog)) assertTrue(pins.remove(catalog, entry.key))

        val reopened = open()
        assertEquals(emptyList<DesktopAppCatalog.Entry>(), reopened.entries(catalog + app("example.new")))
        assertTrue(reopened.pin(catalog, extra.key))
        assertEquals(listOf(extra), open().entries(catalog))
    }

    @Test fun dropAndReorderKeepDifferentActivitiesFromSamePackageAndIgnoreDuplicates() {
        val second = app(extra.packageName, "example.extra.Second")
        val all = catalog + second
        val pins = open()
        assertTrue(pins.pin(all, extra.key, 1))
        assertFalse(pins.pin(all, extra.key))
        assertTrue(pins.pin(all, second.key, 0))
        assertTrue(pins.pin(all, second.key, 3))
        assertEquals(listOf(navigator, extra, music, second, maps, dialer), open().entries(all))
        assertFalse(pins.pin(all, second.key, 3))
        assertFalse(pins.pin(all, "not.installed/not.installed.Main", 0))
        assertTrue(pins.remove(all, extra.key))
        assertEquals(listOf(navigator, music, second, maps, dialer), open().entries(all))
    }

    @Test fun unavailableActivityNeverAppearsAsALaunchTargetAndUsesNoVisibleDropSlot() {
        val pins = open()
        assertEquals(listOf(navigator, music, maps, dialer), pins.entries(catalog))
        val available = catalog - music
        assertEquals(listOf(navigator, maps, dialer), pins.entries(available))
        assertTrue(pins.pin(available, extra.key, 1))
        assertEquals(listOf(navigator, extra, maps, dialer), pins.entries(available))
        // A temporary package update does not erase its pin or create another one.
        assertEquals(listOf(navigator, music, extra, maps, dialer), open().entries(catalog))
    }
}
