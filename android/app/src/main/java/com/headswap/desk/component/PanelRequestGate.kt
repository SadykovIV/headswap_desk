package com.headswap.desk.component

/** Bound work from one panel; the issuer stays the same even if the Binder is forwarded. */
internal class PanelRequestGate(private val ownerUid: Int, private val host: String, private val provider: String) {
    @Volatile var live = true
        private set
    private var pending = false
    private var lastAttempt: Long? = null

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
    @Synchronized fun revoke() { live = false }
}
