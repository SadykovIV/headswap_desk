package com.headswap.desk.desktop

/** Stable application/control IDs, including items outside the current scrolled viewport. */
internal class DesktopKnobFocus {
    enum class Move { NEXT, PREVIOUS, UP, DOWN, LEFT, RIGHT }
    data class Item(val id: String, val x: Int, val y: Int)
    var selected: String? = null
        private set
    var visible = false
        private set

    fun touch() { visible = false }

    fun move(items: List<Item>, direction: Move): String? {
        if (items.isEmpty()) { selected = null; visible = false; return null }
        val index = items.indexOfFirst { it.id == selected }
        if (!visible || index < 0) {
            visible = true
            selected = items[if (index < 0) 0 else index].id
            return selected
        }
        val current = items[index]
        selected = when (direction) {
            Move.NEXT -> items[(index + 1) % items.size].id
            Move.PREVIOUS -> items[(index + items.size - 1) % items.size].id
            else -> items.asSequence().filter { candidate -> when (direction) {
                Move.UP -> candidate.y < current.y
                Move.DOWN -> candidate.y > current.y
                Move.LEFT -> candidate.x < current.x
                Move.RIGHT -> candidate.x > current.x
                else -> false
            } }.minByOrNull { candidate ->
                val dx = (candidate.x - current.x).toLong()
                val dy = (candidate.y - current.y).toLong()
                // Prefer the same row/column, then the closest item in that direction.
                val (major, minor) = if (direction == Move.UP || direction == Move.DOWN) dy to dx else dx to dy
                major * major + 13 * minor * minor
            }?.id ?: current.id
        }
        return selected
    }

    /** The first OK after touch reveals focus; it never activates an invisible selection. */
    fun activate(items: List<Item>): String? {
        if (!visible || items.none { it.id == selected }) { move(items, Move.NEXT); return null }
        return selected
    }
}
