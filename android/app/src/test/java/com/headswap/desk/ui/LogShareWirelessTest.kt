package com.headswap.desk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LogShareWirelessTest {
    @Test
    fun picksOnlyLinesWithWlMark() {
        val lines = listOf(
            "12:00:00.001 WL begin wifi-b bind=0.0.0.0",
            "12:00:00.002 сервис: wifi-b, слушаю 6 портов",
            "12:00:00.003 WLAN down",
            "12:00:00.004 WL udp beacon-first iface=wlan2",
        )
        assertEquals(
            listOf("12:00:00.001 WL begin wifi-b bind=0.0.0.0", "12:00:00.004 WL udp beacon-first iface=wlan2"),
            LogShare.wirelessLines(lines),
        )
    }

    @Test
    fun blocksPerFileInGivenOrder() {
        val out = LogShare.wirelessLog(
            listOf(
                "headswap-1.log" to listOf("10:00:00.000 WL a"),
                "headswap-2.log" to emptyList(),
                "headswap-3.log" to listOf("11:00:00.000 WL b", "11:00:00.001 WL c"),
            ),
        )
        assertEquals(
            "== headswap-1.log\n10:00:00.000 WL a\n== headswap-2.log\n== headswap-3.log\n11:00:00.000 WL b\n11:00:00.001 WL c\n",
            out,
        )
    }

    @Test
    fun noLinesGivesStub() {
        assertEquals("WL-строк нет\n", LogShare.wirelessLog(listOf("headswap-1.log" to listOf("обычная строка"))))
        assertEquals("WL-строк нет\n", LogShare.wirelessLog(emptyList()))
    }
}
