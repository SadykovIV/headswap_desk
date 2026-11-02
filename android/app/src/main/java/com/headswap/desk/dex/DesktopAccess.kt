package com.headswap.desk.dex

import android.content.Context

/** Check only the explicitly selected backend; never fall back to another grant. */
object DesktopAccess {
    fun isReady(context: Context): Boolean = when (DexPrefs(context).accessMode) {
        DesktopAccessMode.COMPUTER -> DelegatedDesktopAccess.isReady(context)
        DesktopAccessMode.SHIZUKU -> DexPrivilegeAccess.isReady(context)
        DesktopAccessMode.LEGACY_ADB -> false // The separate ADB flow performs its own live probe.
    }
}
