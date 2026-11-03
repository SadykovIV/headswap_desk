package com.headswap.desk.embed

import com.headswap.desk.cluster.YandexGuidanceCard

/** A Maps tile may hand its route to Navigator on the same owned display. */
internal object YandexGuidanceScope {
    fun allows(launched: String?, foreground: String): Boolean =
        foreground in YandexGuidanceCard.PACKAGES && (launched == null || launched == foreground ||
            launched == "ru.yandex.yandexmaps" && foreground == "ru.yandex.yandexnavi")
}
