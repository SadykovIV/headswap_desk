package com.headswap.desk.dex

import java.lang.reflect.Method

/** Samsung's optional per-event marker leaves normal dispatch and its target application's tap handling intact. */
internal class SamsungHuActivity(private val motionType: Class<*>, private val keyType: Class<*>) {
    private val motion: Method? = find(motionType)
    private val key: Method? = find(keyType)

    fun mark(event: Any, displayId: Int, usbOutput: Boolean): Boolean {
        if (!usbOutput || displayId <= 0) return false
        val marker = when {
            motionType.isInstance(event) -> motion
            keyType.isInstance(event) -> key
            else -> null
        } ?: return false
        return runCatching { marker.invoke(event) }.isSuccess
    }

    private fun find(type: Class<*>): Method? = runCatching {
        type.getMethod("semSetNonResetUserActivityTimeout").takeIf { it.returnType == Void.TYPE }
    }.getOrNull()
}
