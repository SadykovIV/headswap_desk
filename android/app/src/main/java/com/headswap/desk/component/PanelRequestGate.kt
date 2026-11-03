package com.headswap.desk.component

/** Bound work from one panel; the issuer stays the same even if the Binder is forwarded. */
internal class PanelRequestGate(private val ownerUid: Int, private val host: String, private val provider: String) {
    @Volatile var live = true
        private set
    private var pending = false
    private var lastAttempt: Long? = null
    private var mediaPending = false
    private var lastRead: Long? = null
    private var lastCommand: Long? = null
    private var layoutBusy = false
    private var chromeActionBusy = false
    private var lastLayout: Long? = null
    private var lastChromeAction: Long? = null

    @Synchronized fun reserve(caller: Int, pkg: String?, activity: String?, nowMs: Long): Boolean {
        if (pkg.isNullOrBlank() || activity.isNullOrBlank() || pkg.length > 255 || activity.length > 512 ||
            pkg == host || pkg == provider) return false
        return reserveCatalog(caller, nowMs)
    }

    @Synchronized fun reserveCatalog(caller: Int, nowMs: Long): Boolean {
        if (!live || pending || caller != ownerUid || lastAttempt?.let { nowMs - it < 250 } == true) return false
        pending = true
        lastAttempt = nowMs
        return true
    }

    @Synchronized fun finish() { pending = false }
    /** Polling cannot starve a user click or delay the Back/catalog path. Only one media IPC is in flight. */
    @Synchronized fun reserveMedia(caller: Int, read: Boolean, nowMs: Long): Boolean {
        if (!live || caller != ownerUid || mediaPending) return false
        val last = if (read) lastRead else lastCommand
        if (last != null && nowMs - last < (if (read) 750 else 250)) return false
        mediaPending = true
        if (read) lastRead = nowMs else lastCommand = nowMs
        return true
    }
    @Synchronized fun finishMedia() { mediaPending = false }
    @Synchronized fun reserveChrome(caller: Int, layout: Boolean, nowMs: Long): Boolean {
        if (!live || caller != ownerUid || (if (layout) layoutBusy else chromeActionBusy)) return false
        val last = if (layout) lastLayout else lastChromeAction
        if (last != null && nowMs - last < (if (layout) 32 else 250)) return false
        if (layout) {
            lastLayout = nowMs
            layoutBusy = true
        } else {
            lastChromeAction = nowMs
            chromeActionBusy = true
        }
        return true
    }
    @Synchronized fun finishChrome(layout: Boolean) { if (layout) layoutBusy = false else chromeActionBusy = false }
    @Synchronized fun revoke() { live = false }
}
