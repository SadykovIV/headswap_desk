package com.headswap.desk.dex

/** Only used while the desktop is actively delivered to CarLife. */
internal enum class PhoneIdleTimeout(val label: String) {
    TEN_SECONDS("10 секунд"),
    SYSTEM("Как в телефоне");

    fun effectiveMs(systemTimeoutMs: Long): Long = when (this) {
        TEN_SECONDS -> 10_000L
        SYSTEM -> systemTimeoutMs
    }

    companion object {
        fun restore(saved: String?): PhoneIdleTimeout = entries.firstOrNull { it.name == saved } ?: TEN_SECONDS
    }
}
