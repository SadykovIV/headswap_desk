package com.headswap.desk.dex

/** A session/restore starts a fresh interval even when the phone has been idle since long before the car connected. */
internal object PhoneIdleDeadline {
    fun powerTime(last: String, lastWithoutBrightnessChange: String, now: Long): Long? {
        val normal = last.toLongOrNull() ?: return null
        val indirect = lastWithoutBrightnessChange.toLongOrNull() ?: return null
        if (normal !in 1..now || indirect !in 0..now) return null
        return maxOf(normal, indirect)
    }

    class GroupParser {
        private var inGroups = false
        private var inPrimary = false
        private var normal: String? = null
        private var noLights: String? = null

        fun line(input: String, now: Long): Long? {
            val value = input.trim()
            when {
                value == "Power Group User Activity:" -> inGroups = true
                !inGroups -> return null
                value.isEmpty() -> { inPrimary = false; inGroups = false }
                value.startsWith("groupId:") -> {
                    inPrimary = value.substringAfter(':').trim() == "0"
                    normal = null
                    noLights = null
                }
                !inPrimary -> return null
                value.startsWith("lastUserActivityTime=") -> normal = value.substringAfter('=').substringBefore(' ')
                value.startsWith("lastUserActivityTimeNoChangeLights=") -> noLights = value.substringAfter('=').substringBefore(' ')
            }
            if (!inPrimary) return null
            return powerTime(normal ?: return null, noLights ?: return null, now)
        }
    }

    fun remaining(now: Long, armedAt: Long, activity: Long, timeout: Long): Long? {
        if (timeout !in 1..Int.MAX_VALUE.toLong() || armedAt !in 0..now || activity !in 1..now) return null
        return (timeout - (now - maxOf(activity, armedAt))).coerceAtLeast(0)
    }
}
