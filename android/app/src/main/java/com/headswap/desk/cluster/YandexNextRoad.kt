package com.headswap.desk.cluster

/** Only the next-street field of the already verified, primary manoeuvre balloon.
 * Missing, hidden or ambiguous fields stay empty; no current-road or instruction fallback. */
internal object YandexNextRoad {
    const val MAX_CHARS = 100
    private const val MAX_NODES = 100

    interface Tree<N> {
        fun visible(node: N): Boolean
        fun id(node: N): String?
        fun pkg(node: N): String?
        fun text(node: N): String?
        fun childCount(node: N): Int
        fun child(node: N, index: Int): N
    }

    fun <N> read(primary: N, expectedPackage: String, enabled: Boolean, tree: Tree<N>): String {
        if (!enabled) return "" // Do not even read a text value without the option.
        if (!tree.visible(primary) || tree.id(primary) != ForegroundYandexManeuver.PRIMARY ||
            tree.pkg(primary) != expectedPackage) return ""
        val stack = ArrayDeque<N>()
        stack.addLast(primary)
        var result: String? = null
        var visited = 0
        while (stack.isNotEmpty()) {
            if (++visited > MAX_NODES) return ""
            val node = stack.removeLast()
            if (!tree.visible(node) || tree.id(node) == "next_upcoming_maneuver") continue
            if (tree.pkg(node)?.let { it != expectedPackage } == true) continue
            if (tree.id(node) == ForegroundYandexManeuver.ROAD && tree.pkg(node) == expectedPackage) {
                if (result != null) return ""
                result = tree.text(node).orEmpty().trim().takeIf {
                    it.length <= MAX_CHARS && it.none(Char::isISOControl)
                } ?: return ""
            }
            val count = tree.childCount(node)
            if (count < 0 || count > MAX_NODES - visited) return ""
            for (i in 0 until count) stack.addLast(tree.child(node, i))
        }
        return result.orEmpty()
    }
}
