package com.headswap.desk.dex

import android.content.Context

/** Check only the explicitly selected backend; never fall back to another grant. */
object DesktopAccess {
    /** UI/setup completion includes the phone transaction; transport recovery still has its own gate. */
    fun activationSetupComplete(context: Context): Boolean {
        if (!isReady(context)) return false
        val prefs = DexPrefs(context)
        if (!prefs.useDelegated) return true
        return DesktopActivationSetupPolicy.isReady(prefs.activationMethod,
            PhoneActivationStatus.read(context).activationBinding,
            DesktopActivationCoordinator.facts(context),
            DesktopReadinessPolicy.Requirements(prefs.usbPlaybackAudio, prefs.desktopPanelControl),
            PhonePairingService.blocksProjectionForCommittedActivation(context))
    }

    /** Transport preparation includes the legacy backend's separate authenticated probe. */
    fun isReadyForProjection(context: Context): Boolean =
        if (DesktopBackendTransitionPolicy.blocksProjection(DelegatedDesktopAccess.ownsActivation, DexPrefs(context).accessMode)) false
        else if (PhonePairingService.blocksProjectionForCommittedActivation(context)) false
        else if (DexPrefs(context).accessMode == DesktopAccessMode.LEGACY_ADB) DexAccess.hasVerifiedEndpoint(context)
        else isReady(context)

    fun isReady(context: Context): Boolean = if (DesktopBackendTransitionPolicy.blocksProjection(
        DelegatedDesktopAccess.ownsActivation, DexPrefs(context).accessMode)) false else when (DexPrefs(context).accessMode) {
        DesktopAccessMode.COMPUTER -> DelegatedDesktopAccess.isReady(context)
        DesktopAccessMode.SHIZUKU -> DexPrivilegeAccess.isReady(context)
        DesktopAccessMode.LEGACY_ADB -> false // The separate ADB flow performs its own live probe.
    }
}
