package com.headswap.desk.dex

/** A saved selection is not evidence of a live privileged process. */
enum class DesktopAccessMode {
    SHIZUKU, COMPUTER, LEGACY_ADB;

    companion object {
        fun restore(saved: String?, legacyUseShizuku: Boolean?): DesktopAccessMode =
            entries.firstOrNull { it.name == saved }
                ?: when (legacyUseShizuku) {
                    true -> SHIZUKU
                    false -> LEGACY_ADB
                    null -> if (saved == null) COMPUTER else SHIZUKU
                }
    }
}
