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
    private val parentDisplayId: Int,
    private val isCurrent: () -> Boolean,
    private val mediaForeground: () -> Boolean = { false },
    private val chromeForeground: () -> Boolean = { false },
    private val chromeVisible: () -> Boolean = { false },
    private val onDashboardAction: (Int) -> Unit = {},
    private val onDashboardLayout: (Boolean, Boolean, Boolean, Int) -> Unit = { _, _, _, _ -> },
    private val onCatalog: () -> Unit,
    private val onLaunch: (ComponentName) -> Unit,
) : IDesktopPanelSession.Stub() {
    private val app = context.applicationContext
    private val notificationToken = android.os.Binder()
    private val parentProof = com.headswap.desk.embed.EmbeddedSessionRegistry.captureParent(parentDisplayId)
    private val main = Handler(Looper.getMainLooper())
    private val uid = app.packageManager.getPackageUid(entry.packageName, 0)

    private val gate = PanelRequestGate(uid, app.packageName, entry.packageName)
    private val media by lazy { PanelMediaSource(app) }
    @Volatile private var attachedMedia = false

    fun attach(intent: Intent) {
        check(gate.live && intent.component == entry.componentName && intent.action == DesktopComponents.ACTION)
        attachedMedia = DesktopComponents.mediaAllowed(app, entry)
        val mediaCap = if (attachedMedia) ProfileContract.CAP_MEDIA else 0
        val chromeCap = if (entry.profileChrome) ProfileContract.CAP_PROFILE_CHROME else 0
        intent.putExtra(ProfileContract.EXTRA_SESSION, Bundle().apply {
            putInt(ProfileContract.API_KEY, ProfileContract.API_MAJOR)
            putInt(ProfileContract.CAPABILITIES_KEY, com.headswap.desk.notify.HuNotificationFeature.capabilities(
                ProfileContract.CAP_LAUNCH_APP or ProfileContract.CAP_OPEN_CATALOG or mediaCap or chromeCap or ProfileContract.CAP_NOTIFICATIONS))
            putBinder(ProfileContract.BINDER_KEY, this@PanelLaunchSession)
        })
    }

    fun revoke() { gate.revoke(); com.headswap.desk.notify.HuNotificationGateway.remove(notificationToken) }

    override fun getNotificationStatus(nativeEntryVisible: Boolean): Bundle? {
        if (!gate.live || Binder.getCallingUid() != uid) return null
        val identity = Binder.clearCallingIdentity()
        return try {
            if (!isCurrent() || !stillSelected() || parentProof == null ||
                !com.headswap.desk.embed.EmbeddedSessionRegistry.isCurrentParent(parentDisplayId, parentProof)) null
            else com.headswap.desk.notify.HuNotificationGateway.status(parentDisplayId, notificationToken,
                nativeEntryVisible && (!entry.profileChrome || chromeVisible()))
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun requestLaunch(packageName: String?, activityName: String?): Boolean {
        if (!gate.reserve(Binder.getCallingUid(), packageName, activityName, SystemClock.elapsedRealtime())) return false
        val target = ComponentName(requireNotNull(packageName), requireNotNull(activityName))
        return submit({ (!entry.profileChrome || chromeVisible()) && publicLauncher(target) }) { onLaunch(target) }
    }

    override fun requestCatalog(): Boolean {
        if (!gate.reserveCatalog(Binder.getCallingUid(), SystemClock.elapsedRealtime())) return false
        return submit({ !entry.profileChrome || chromeVisible() }, onCatalog)
    }

    override fun getNowPlaying(): Bundle? = mediaCall(read = true) {
        val snapshot = media.snapshot()
        snapshot?.takeIf { mediaScope() }
    }

    override fun sendMediaCommand(command: Int): Boolean {
        if (command !in setOf(ProfileContract.MEDIA_PREVIOUS, ProfileContract.MEDIA_PLAY_PAUSE, ProfileContract.MEDIA_NEXT)) return false
        return mediaCall(read = false) { media.command(command, ::mediaScope) } == true
    }

    /** Clear the original identity only after checking the actual inbound UID. Recheck visibility before delivery/transport. */
    private fun <T> mediaCall(read: Boolean, action: () -> T?): T? {
        if (!attachedMedia || !gate.reserveMedia(Binder.getCallingUid(), read, SystemClock.elapsedRealtime())) return null
        val identity = Binder.clearCallingIdentity()
        return try {
            if (mediaScope()) action() else null
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            Binder.restoreCallingIdentity(identity)
            gate.finishMedia()
        }
    }

    private fun mediaScope(): Boolean = gate.live && attachedMedia && DesktopComponents.mediaAllowed(app, entry) &&
        app.packageManager.getPackageUid(entry.packageName, 0) == uid && mediaForeground() && gate.live

    override fun setDashboardLayout(musicVisible: Boolean, musicOnRight: Boolean, targetVisible: Boolean, dockHeightPx: Int): Boolean {
        // The compositor applies a smaller device-specific cap to the reported height.
        if (dockHeightPx !in 0..4096) return false
        return chromeRequest(layout = true) { onDashboardLayout(musicVisible, musicOnRight, targetVisible, dockHeightPx) }
    }

    override fun sendDashboardAction(action: Int): Boolean {
        if (action == ProfileContract.DASH_NOTIFICATIONS) {
            if (!gate.reserveCatalog(Binder.getCallingUid(), SystemClock.elapsedRealtime())) return false
            return submit({ parentProof != null &&
                com.headswap.desk.embed.EmbeddedSessionRegistry.isCurrentParent(parentDisplayId, parentProof) &&
                (!entry.profileChrome || chromeVisible()) }) { com.headswap.desk.notify.HuNotificationGateway.show(parentDisplayId) }
        }
        if (action != ProfileContract.DASH_BACK && action != ProfileContract.DASH_NAVIGATION &&
            action != ProfileContract.DASH_PHONE_TOGGLE) return false
        return chromeRequest(layout = false) { onDashboardAction(action) }
    }

    /** Author-owned controls may be visible below the target even with the music pane collapsed. */
    private fun chromeRequest(layout: Boolean, action: () -> Unit): Boolean {
        if (!entry.profileChrome || !gate.reserveChrome(Binder.getCallingUid(), layout, SystemClock.elapsedRealtime())) return false
        val identity = Binder.clearCallingIdentity()
        val queued = try {
            (layout || chromeVisible()) && stillSelected() && chromeForeground() && gate.live && main.post {
                try {
                    if (gate.live && isCurrent() && (layout || chromeVisible()) && stillSelected()) action()
                } catch (_: SecurityException) {
                    // Android may revoke or remove the component between verification and the UI callback.
                } finally { gate.finishChrome(layout) }
            }
        } catch (_: RuntimeException) {
            false
        } finally { Binder.restoreCallingIdentity(identity) }
        if (!queued) gate.finishChrome(layout)
        return queued
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
            current.dashboard == entry.dashboard && current.profileChrome == entry.profileChrome &&
            runCatching { app.packageManager.getPackageUid(entry.packageName, 0) }.getOrNull() == uid
    }

    private fun publicLauncher(target: ComponentName): Boolean {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target.packageName)
        return app.packageManager.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0)).any {
            val info = it.activityInfo
            if (info.packageName != target.packageName || info.name != target.className || !info.enabled ||
                !info.applicationInfo.enabled || !info.exported || !info.permission.isNullOrEmpty()) return@any false
            val declared = info.targetActivity?.let { name ->
                try { app.packageManager.getActivityInfo(ComponentName(target.packageName, name), 0) }
                catch (_: PackageManager.NameNotFoundException) { return@any false }
            } ?: info
            // Always launch the public alias itself. A chrome provider may request a singleton;
            // the host separately checks for a task move and mediates any required consent.
            declared.enabled && declared.applicationInfo.uid == info.applicationInfo.uid &&
                (entry.profileChrome || DesktopComponentPolicy.supportsIsolatedTask(declared.launchMode, declared.documentLaunchMode,
                    declared.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING != 0))
        }
    }
}
