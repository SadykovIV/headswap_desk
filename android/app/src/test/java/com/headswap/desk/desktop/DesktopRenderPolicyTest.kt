package com.headswap.desk.desktop

import com.headswap.desk.shared.DesktopRenderPolicy
import org.junit.Assert.*
import org.junit.Test

class DesktopRenderPolicyTest {
    @Test fun selectedDensityIsExactExceptForAnEnabledMap() {
        for (dpi in listOf(96, 128, 160, 200, 320, 640)) {
            for (pkg in listOf("com.example.player", "ru.yandex.yandexmaps.test", "ru.yandex.music")) {
                assertArrayEquals(intArrayOf(704, 544, dpi), DesktopRenderPolicy.geometry(pkg, 704, 544, dpi, true))
            }
            for (pkg in listOf("ru.yandex.yandexmaps", "ru.yandex.yandexnavi")) {
                assertArrayEquals(intArrayOf(704, 544, dpi), DesktopRenderPolicy.geometry(pkg, 704, 544, dpi, false))
            }
        }
    }

    @Test fun compactMapsKeepButtonSizeAndPreserveTheNavigatorCalibration() {
        for (pkg in listOf("ru.yandex.yandexmaps", "ru.yandex.yandexnavi")) {
            for ((dpi, signWidth) in listOf(160 to 64, 200 to 80)) {
                val (w, h, density) = DesktopRenderPolicy.geometry(pkg, 705, 541, dpi, true)
                assertEquals(signWidth.toDouble(), 128.0 * 705 / w, 0.1)
                assertEquals(32.0 * dpi / 160, 32.0 * density / 160 * 705 / w, 0.1)
                assertEquals(32.0 * dpi / 160, 32.0 * density / 160 * 541 / h, 0.1)
            }
            assertArrayEquals(intArrayOf(705, 541, 640), DesktopRenderPolicy.geometry(pkg, 705, 541, 640, true))
            val highResolution = DesktopRenderPolicy.geometry(pkg, 1920, 1080, 96, true)
            assertArrayEquals(intArrayOf(3840, 2160, 192), highResolution)
        }
    }
}
