package com.headswap.desk.dex

/** Used under the gateway monitor; a producer cannot carry display IDs across generations. */
internal class DelegatedInputRegistry {
    data class Entry(val id: Int, val token: Any, val parent: Int?, var width: Int, var height: Int,
        val fullscreen: Boolean = false)
    private var expectedParentToken: Any? = null
    private var generation = 0L
    private var fullscreenAllowed = false
    private val displays = mutableMapOf<Int, Entry>()

    fun begin(generation: Long, parentToken: Any, allowFullscreen: Boolean = false) {
        require(generation > 0)
        check(this.generation == 0L) { "Предыдущий экран ещё активен" }
        this.generation = generation
        fullscreenAllowed = allowFullscreen
        expectedParentToken = parentToken
        displays.clear()
    }

    fun deactivate() { generation = 0; expectedParentToken = null; displays.clear() }

    fun registeredDisplayIds(): Set<Int> = displays.keys.toSet()

    fun requireParent(expectedGeneration: Long): Entry {
        check(generation > 0 && generation == expectedGeneration) { "Сеанс экрана завершён" }
        return checkNotNull(displays.values.singleOrNull { it.parent == null && it.token == expectedParentToken })
    }

    fun registerParent(id: Int, token: Any, width: Int, height: Int) {
        requireParentGeometry(id, width, height)
        check(generation > 0 && token == expectedParentToken) { "Сеанс экрана уже завершён" }
        check(displays.isEmpty()) { "Основной экран уже зарегистрирован" }
        displays[id] = Entry(id, token, null, width, height)
    }

    fun registerChild(parent: Int, id: Int, token: Any, width: Int, height: Int, fullscreen: Boolean = false) {
        require(id > 0) { "Основной экран телефона запрещён" }
        EmbeddedDisplayPolicy.requireDimensions(width, height)
        check(generation > 0 && displays[parent]?.parent == null && displays.containsKey(parent)) { "Родительский экран недоступен" }
        if (fullscreen) check(fullscreenAllowed) { "Полноэкранное окно требует DriveDeck" }
        check(id != parent && !displays.containsKey(id) && token != expectedParentToken &&
            displays.values.none { it.token == token }) { "Недопустимый экран виджета" }
        EmbeddedDisplayPolicy.requireCapacity(displays.values.count { it.parent != null && it.fullscreen == fullscreen }, fullscreen)
        requireChildBudget(id, width, height, fullscreen)
        displays[id] = Entry(id, token, parent, width, height, fullscreen)
    }

    fun requireDisplay(id: Int, token: Any): Entry {
        require(id > 0) { "Основной экран телефона запрещён" }
        check(generation > 0) { "Сеанс экрана завершён" }
        return checkNotNull(displays[id]?.takeIf { it.token == token }) { "Экран не принадлежит этому сеансу" }
    }

    fun resize(id: Int, token: Any, width: Int, height: Int) {
        val entry = requireDisplay(id, token)
        if (entry.parent == null) requireParentGeometry(id, width, height)
        else requireChildBudget(id, width, height, entry.fullscreen)
        entry.width = width
        entry.height = height
    }

    fun unregister(id: Int, token: Any) {
        val entry = displays[id] ?: return
        check(entry.token == token) { "Неверный владелец экрана" }
        if (entry.parent == null) displays.clear() else displays.remove(id)
    }

    private fun requireChildBudget(id: Int, width: Int, height: Int, fullscreen: Boolean) {
        EmbeddedDisplayPolicy.requirePixelBudget(displays.values.filter { it.parent != null && it.id != id && it.fullscreen == fullscreen }
            .sumOf { it.width.toLong() * it.height }, width, height, fullscreen)
    }

    private fun requireParentGeometry(id: Int, width: Int, height: Int) {
        require(id > 0 && com.headswap.desk.aa.DesktopGeometryPolicy.accepts(width, height)) { "Недопустимые размеры отдельного экрана" }
    }
}
