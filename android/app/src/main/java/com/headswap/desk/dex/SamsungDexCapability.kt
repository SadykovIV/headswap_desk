package com.headswap.desk.dex

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/** Shows the existing Shizuku profile only; this does not authorize or launch DeX. */
object SamsungDexCapability {
    fun isAvailable(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        // One UI 9 uses Android's desktop capabilities and may omit the older desktopmode service.
        // Both modern features are still combined with the exact installed system DeX launcher below.
        val feature = SamsungDexCapabilityPolicy.desktopFeature(
            pm.hasSystemFeature("com.sec.feature.desktopmode") || context.getSystemService("desktopmode") != null,
            pm.hasSystemFeature("android.software.freeform_window_management"),
            pm.hasSystemFeature("android.software.activities_on_secondary_displays"))
        val info = pm.getActivityInfo(ComponentName("com.sec.android.app.launcher", "com.honeyspace.dexservice.SecondaryLauncher"), 0)
        SamsungDexCapabilityPolicy.available(samsung, feature, info.enabled && info.applicationInfo.enabled,
            info.exported, info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
    }.getOrDefault(false)
}

internal object SamsungDexCapabilityPolicy {
    fun desktopFeature(legacy: Boolean, freeform: Boolean, secondaryActivities: Boolean): Boolean =
        legacy || freeform && secondaryActivities
    fun available(samsung: Boolean, desktopFeature: Boolean, enabled: Boolean, exported: Boolean, system: Boolean): Boolean =
        samsung && desktopFeature && enabled && exported && system
}
