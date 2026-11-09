package com.headswap.desk.dex

/** A double press consists of two completed BACK gestures, never repeated DOWN events. */
internal class DesktopBackSequence(private val intervalMs: Long = 500) {
    private var previousUp = Long.MIN_VALUE
    private var down = false
    private var home = false

    /** true means suppress this gesture's BACK; Home is emitted only on its matching UP. */
    fun down(atMs: Long): Boolean {
        if (down) return home
        down = true
        home = previousUp != Long.MIN_VALUE && atMs >= previousUp && atMs - previousUp <= intervalMs
        return home
    }

    fun up(atMs: Long): Boolean {
        if (!down) return false
        down = false
        val result = home
        home = false
        previousUp = if (result) Long.MIN_VALUE else atMs
        return result
    }

    fun reset() { previousUp = Long.MIN_VALUE; down = false; home = false }
}

/** Same CarLife selector convention as upstream. Native Android apps receive DPAD navigation. */
internal object DesktopRotaryInput {
    fun keys(delta: Int): List<Int> = when {
        delta > 0 -> List(delta.coerceAtMost(8)) { 20 } // DPAD_DOWN
        delta < 0 -> List((-delta.toLong()).coerceAtMost(8).toInt()) { 19 } // DPAD_UP
        else -> emptyList()
    }
}

/** Only our own registered display can be a hardware-input destination. */
internal object DesktopInputTarget {
    fun resolve(parentId: Int, children: Set<Int>, focusedId: Int?): Int {
        require(parentId > 0 && children.all { it > 0 && it != parentId })
        return focusedId?.takeIf { it == parentId || it in children } ?: parentId
    }
}

/** A partially visible task list cannot prove that an assistant is absent from the phone. */
internal object DesktopAssistantLaunchPolicy {
    data class Plan(val displayId: Int, val newTask: Boolean)

    fun plan(launchMode: Int, fullTaskVisibility: Boolean, foreignTask: Boolean,
        ownedTaskDisplay: Int?, parentId: Int): Plan? {
        require(parentId > 0 && (ownedTaskDisplay == null || ownedTaskDisplay > 0))
        if (launchMode !in 0..4) return null
        val verifiedReuse = fullTaskVisibility && !foreignTask && ownedTaskDisplay != null
        // Only STANDARD guarantees a separate task with NEW_TASK | MULTIPLE_TASK.
        if (launchMode != 0 && !verifiedReuse) return null
        return Plan(ownedTaskDisplay ?: parentId, newTask = !verifiedReuse)
    }
}
