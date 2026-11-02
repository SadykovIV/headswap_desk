package com.headswap.desk.dex

/** A blank panel is an explicit, revocable lease; polling can never blank it again. */
internal class PhonePanelLease(
    private val mayBlank: () -> Boolean,
    private val mayRestore: () -> Boolean,
    private val acquire: () -> Unit,
    private val release: () -> Unit,
    private val power: (Boolean) -> Unit,
    private val refresh: () -> Unit = {},
) {
    private var owner: Any? = null
    private var touched = false
    private var deadline = 0L
    private var restoreRequested = false
    val isBlank: Boolean get() = owner != null && touched
    val hasLease: Boolean get() = owner != null

    fun blank(token: Any, nowMs: Long) {
        if (owner == token && touched) { check(!restoreRequested) { "Восстановление панели ещё не завершено" }; return }
        check(owner == null) { "Панель уже принадлежит другому сеансу" }
        check(mayBlank()) { "Сначала включите и разблокируйте телефон" }
        owner = token
        deadline = nowMs + TIMEOUT_MS
        try {
            acquire()
            check(mayBlank()) { "Телефон был заблокирован; панель не изменена" }
            touched = true // rollback even when a native operation took effect before throwing
            power(false)
        } catch (error: Throwable) {
            try { restore() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun poll(token: Any, eligible: Boolean, nowMs: Long) {
        if (owner != token) return
        try {
            if (restoreRequested || !eligible || nowMs >= deadline || nowMs < deadline - TIMEOUT_MS || !mayBlank()) restore()
            else {
                refresh() // Only an existing, unlocked, healthy lease may renew a server-side timeout.
                deadline = nowMs + TIMEOUT_MS
            }
        } catch (error: Throwable) {
            try { restore() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun restore(token: Any? = null) {
        if (owner == null || token != null && owner != token) return
        restoreRequested = true // a live transport must never renew an unsuccessfully closed lease
        var failure: Throwable? = null
        try { if (touched && mayRestore()) power(true) }
        catch (error: Throwable) { failure = error }
        try { release() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        if (failure != null) throw failure // retain ownership for another cleanup attempt
        owner = null
        touched = false
        deadline = 0
        restoreRequested = false
    }

    companion object { const val TIMEOUT_MS = 5_000L }
}
