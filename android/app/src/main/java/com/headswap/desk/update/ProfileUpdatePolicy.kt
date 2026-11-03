package com.headswap.desk.update

import java.net.URI

internal object ProfileUpdatePolicy {
    fun publisherPage(value: Any?): String? {
        val url = (value as? String)?.takeIf { it.length in 1..2048 } ?: return null
        val uri = try { URI(url) } catch (_: Exception) { return null }
        return url.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null && uri.port in listOf(-1, 443) }
    }

    fun checkArchive(pkg: String, pins: Set<String>, currentCode: Long, archivePkg: String,
                     archivePins: Set<String>, archiveCode: Long, compatible: Boolean) {
        check(pkg == archivePkg) { "Этот APK относится к другому приложению. Ожидается $pkg." }
        check(pins.isNotEmpty() && archivePins == pins) { "Подпись APK отличается от подписи установленного компонента." }
        check(archiveCode > currentCode) { "Выберите APK с кодом версии выше $currentCode." }
        check(compatible) { "APK не содержит совместимой точки входа установленного профиля." }
    }
}
