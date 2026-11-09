package com.headswap.desk.dex

/** User-visible commands, with Android substitutions quoted for the host shell. */
internal object DesktopActivationGuide {
    const val PLATFORM_TOOLS_URL = "https://developer.android.com/tools/releases/platform-tools"
    fun command(windows: Boolean, audio: Boolean, screen: Boolean): String {
        val adb = if (windows) ".\\adb.exe" else "./adb"
        val flags = (if (audio) " --audio" else "") + (if (screen) " --screen" else "")
        return "$adb -d shell 'CLASSPATH=\$(pm path com.headswap.desk | cut -d: -f2 | head -n 1) app_process / com.headswap.desk.dex.ManualAccessLauncher$flags'"
    }
    fun instructions(audio: Boolean, screen: Boolean): String = """
        1. Установите Android Platform Tools с сайта Android Developers и откройте терминал в папке platform-tools.
        2. Включите «Отладку по USB» в параметрах разработчика телефона. Подключите USB-кабель и подтвердите доступ компьютеру на телефоне.
        3. Проверьте подключение:

        Windows PowerShell:
        .\adb.exe devices

        macOS / Linux:
        ./adb devices

        4. Выполните одну команду для вашей системы:

        Windows PowerShell:
        ${command(true, audio, screen)}

        macOS / Linux:
        ${command(false, audio, screen)}

        5. Дождитесь HEADSWAP_READY. После этого подключайте телефон к CarLife.

        Команда соответствует выбранным настройкам звука и управления панелью. После перезагрузки или обновления приложения активацию нужно повторить. Для восстановления службы независимого экрана на Android 17 может понадобиться Wi-Fi и беспроводная отладка.
    """.trimIndent()
}
