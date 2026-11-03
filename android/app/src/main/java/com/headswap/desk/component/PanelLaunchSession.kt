package com.headswap.desk.component

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.headswap.desk.component.api.IDesktopPanelSession
import com.headswap.desk.component.api.ProfileContract

/** Handed directly to one panel. No exported service, arbitrary intents, display IDs or shell calls. */
internal class PanelLaunchSession(
    context: Context,
    private val entry: DesktopComponentEntry,
    private val isCurrent: () -> Boolean,
    private val onCatalog: () -> Unit,
    private val onLaunch: (ComponentName) -> Unit,
) : IDesktopPanelSession.Stub() {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val uid = app.packageManager.getPackageUid(entry.packageName, 0)

    private val gate = PanelRequestGate(uid, app.packageName, entry.packageName)

    fun attach(intent: Intent) {
        check(gate.live && intent.component == entry.componentName && intent.action == DesktopComponents.ACTION)
        intent.putExtra(ProfileContract.EXTRA_SESSION, Bundle().apply {
            putInt(ProfileContract.API_KEY, ProfileContract.API_MAJOR)
            putInt(ProfileContract.CAPABILITIES_KEY, ProfileContract.CAP_LAUNCH_APP or ProfileContract.CAP_OPEN_CATALOG)
            putBinder(ProfileContract.BINDER_KEY, this@PanelLaunchSession)
        })
    }

    fun revoke() = gate.revoke()

    override fun requestLaunch(packageName: String?, activityName: String?): Boolean {
        if (!gate.reserve(Binder.getCallingUid(), packageName, activityName, SystemClock.elapsedRealtime())) return false
        val target = ComponentName(requireNotNull(packageName), requireNotNull(activityName))
        return submit({ publicLauncher(target) }) { onLaunch(target) }
    }

    override fun requestCatalog(): Boolean {
        if (!gate.reserveCatalog(Binder.getCallingUid(), SystemClock.elapsedRealtime())) return false
        return submit({ true }, onCatalog)
    }

    private fun submit(allowed: () -> Boolean, action: () -> Unit): Boolean {
        val identity = Binder.clearCallingIdentity()
        val submitted = try {
            stillSelected() && allowed() && main.post {
                try {
                    if (gate.live && isCurrent() && stillSelected() && allowed()) action()
                } catch (_: SecurityException) {
                    // The entry or target was disabled while Android was processing the request.
                } finally { gate.finish() }
            }
        } catch (_: SecurityException) {
            false
        } finally { Binder.restoreCallingIdentity(identity) }
        if (!submitted) gate.finish()
        return submitted
    }

    private fun stillSelected(): Boolean {
        val current = DesktopComponents.selected(app) ?: return false
        return current.componentName == entry.componentName && current.signerSha256 == entry.signerSha256 &&
            current.modeId == entry.modeId && current.versionCode == entry.versionCode &&
            runCatching { app.packageManager.getPackageUid(entry.packageName, 0) }.getOrNull() == uid
    }

    private fun publicLauncher(target: ComponentName): Boolean {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target.packageName)
        return app.packageManager.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0)).any {
            val info = it.activityInfo
            info.packageName == target.packageName && info.name == target.className && info.enabled &&
                info.applicationInfo.enabled && info.exported && info.permission.isNullOrEmpty() &&
                DesktopComponentPolicy.supportsIsolatedTask(info.launchMode, info.documentLaunchMode,
                    info.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING != 0)
        }
    }
}
