package com.headswap.desk.dex

/** Desktop selection is independent from the transport and never changes the phone's HOME app. */
object DesktopModePolicy {
    const val SAMSUNG_DEX = 0
    const val ANDROID_DESKTOP = 1
    const val DRIVEDECK = 2
    const val ANDROID_HOME_CLASS = "com.headswap.desk.desktop.AndroidDesktopActivity"
    const val DRIVEDECK_PACKAGE = "ru.drivedeck.headswap"
    const val DRIVEDECK_HOME_CLASS = "ru.drivedeck.launcher.MainActivity"
    private const val SAMSUNG_HOME = "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher"

    fun requireMode(mode: Int) {
        require(mode == SAMSUNG_DEX || mode == ANDROID_DESKTOP || mode == DRIVEDECK) { "Неизвестный режим рабочего стола" }
    }

    fun label(mode: Int): String {
        requireMode(mode)
        return when (mode) {
            SAMSUNG_DEX -> "Samsung DeX"
            DRIVEDECK -> "DriveDeck"
            else -> "Android Desktop"
        }
    }

    /** PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | TRUSTED | DESTROY_CONTENT. */
    const val ANDROID_DISPLAY_FLAGS = 1 or 2 or 8 or 64 or 1024 or 256
    private const val VIRTUAL_DISPLAY_OWN_FOCUS = 1 shl 14

    fun displayFlags(mode: Int, sdkInt: Int): Int {
        requireMode(mode)
        if (mode == SAMSUNG_DEX) return DexUserServicePolicy.VIRTUAL_DISPLAY_FLAGS
        // A tap is forwarded parent -> child. Independent focus prevents the parent's DOWN
        // from clearing the embedded app's pressed state before its UP arrives. Android 13
        // has no per-display flag; retain its existing behavior. Do not disable stealing top
        // focus: that separate flag prevents the child from showing its software keyboard.
        return ANDROID_DISPLAY_FLAGS or (if (sdkInt >= 34) VIRTUAL_DISPLAY_OWN_FOCUS else 0)
    }

    /** Every launch names the owned secondary display; no default-display or phone-HOME fallback. */
    fun homeCommand(mode: Int, displayId: Int, appPackage: String): Array<String> {
        requireMode(mode)
        require(displayId > 0) { "Запуск рабочего стола на основном экране запрещён" }
        require(appPackage.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
            "Неверный пакет приложения"
        }
        val common = arrayOf("/system/bin/am", "start", "--display", displayId.toString())
        return when (mode) {
            SAMSUNG_DEX -> common + arrayOf(
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.SECONDARY_HOME", "-n", SAMSUNG_HOME,
            )
            DRIVEDECK -> common + arrayOf(
                "--windowingMode", "1", "-f", "0x34000000", // NEW_TASK | CLEAR_TOP | SINGLE_TOP
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                "-n", "$DRIVEDECK_PACKAGE/$DRIVEDECK_HOME_CLASS",
            )
            else -> common + arrayOf(
                "--windowingMode", "1", "-f", "0x34000000", // NEW_TASK | CLEAR_TOP | SINGLE_TOP
                "-a", "android.intent.action.MAIN", "-n", "$appPackage/$ANDROID_HOME_CLASS",
            )
        }
    }

    /** HOME and APP_SWITCH must never reach a primary-display global navigation handler. */
    fun consumesNavigationKey(mode: Int, keycode: Int): Boolean {
        requireMode(mode)
        return mode != SAMSUNG_DEX && (keycode == 3 || keycode == 187)
    }
}
