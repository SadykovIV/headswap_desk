package com.headswap.desk.dex

/** Owns one wake-lock token. A failed/uncertain acquire still requires release, even without an ACK. */
internal class DexWakeLease(private val acquireRemote: () -> Unit, private val releaseRemote: () -> Unit) {
    @Volatile var isHeld = false
        private set
    private var needsRelease = false

    @Synchronized fun acquire() {
        check(!needsRelease) { "Удержание активности уже запрошено" }
        needsRelease = true
        try {
            acquireRemote()
            isHeld = true
        } catch (error: Throwable) {
            try { release() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    @Synchronized fun release() {
        if (!needsRelease) return
        releaseRemote()
        needsRelease = false
        isHeld = false
    }
}
