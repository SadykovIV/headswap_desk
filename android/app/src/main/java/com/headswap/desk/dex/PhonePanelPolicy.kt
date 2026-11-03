package com.headswap.desk.dex

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display

/** On this handset a blanked internal panel also blacks out the CarLife picture. */
internal object PhonePanelPolicy {
    fun needsPhoneCurtain(manufacturer: String, model: String): Boolean =
        manufacturer.equals("HONOR", ignoreCase = true) && model.equals("ABR-LX1", ignoreCase = true)

    val prefersCurtain: Boolean get() = needsPhoneCurtain(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())

    fun screenMode(selected: String?, previous: String?, prefersCurtain: Boolean): PhoneIdleMode =
        when (selected) {
            PhoneIdleMode.PANEL.name -> PhoneIdleMode.PANEL
            PhoneIdleMode.CURTAIN.name -> PhoneIdleMode.CURTAIN
            else -> if (previous == PhoneIdleMode.CURTAIN.name || prefersCurtain) PhoneIdleMode.CURTAIN else PhoneIdleMode.PANEL
        }
}

/** Read-only observations. Logical display state and recent writes don't prove what the HU shows. */
internal fun phoneDisplayStatus(context: Context, projectedId: Int = DexState.displayId, localVideo: Boolean = true): String {
    fun read(get: () -> Any?): String = runCatching { get()?.toString() }.getOrNull() ?: "unknown"
    fun logical(id: Int) = read { context.getSystemService(DisplayManager::class.java)?.getDisplay(id)?.state }
    return "interactive=${read { context.getSystemService(PowerManager::class.java)?.isInteractive }} " +
        "keyguard=${read { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked }} " +
        "deviceLocked=${read { context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked }} " +
        "phoneLogicalState=${logical(Display.DEFAULT_DISPLAY)} projectionLogicalState=${logical(projectedId)} " +
        "recentVideoWrites=${if (localVideo) DesktopConnectionState.videoIsFlowing(SystemClock.elapsedRealtime()) else "unknown"}"
}
