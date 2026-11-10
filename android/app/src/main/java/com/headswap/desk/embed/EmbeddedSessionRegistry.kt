package com.headswap.desk.embed

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import com.headswap.desk.component.DesktopComponentPolicy
import com.headswap.desk.component.DesktopComponentEntry
import com.headswap.desk.component.DesktopComponents
import android.util.Log
import android.view.Surface
import com.headswap.desk.AppLog
import com.headswap.desk.CarLifeState
import com.headswap.desk.cluster.ShellManeuverSample
import com.headswap.desk.cluster.YandexGuidanceCard
import com.headswap.desk.aa.DesktopGeometryPolicy
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.EmbeddedDisplayPolicy
import com.headswap.desk.dex.IDexUserService
import com.headswap.desk.dex.DesktopTouchState
import com.headswap.desk.shared.DesktopRenderPolicy
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Written by the host UI. Each reveal invalidates samples started before the pane was covered. */
internal class GuidancePaneVisibility {
    @Volatile var visibleEpoch: Any? = Any()
        private set
    private var closed = false

    fun show(shown: Boolean) {
        if (!closed && shown != (visibleEpoch != null)) visibleEpoch = if (shown) Any() else null
    }

    fun close() {
        closed = true
        visibleEpoch = null
    }
}

/** Session authority stays in HeadSwap. Public handles are unrelated to Android display IDs. */
internal object EmbeddedSessionRegistry {
    private class Parent(val owner: Any, val remote: IDexUserService, val displayId: Int, val calls: ExecutorService,
        val mode: Int, val density: Int, val correctedPackages: Set<String>, val shizuku: Boolean,
        val nativeLifetime: IBinder) {
        fun bufferDensity(requested: Int): Int = if (correctedPackages.isNotEmpty()) requested else density
    }
    private class Lease(val handle: Int, val uid: Int, val parent: Parent, val token: IBinder, val fullscreen: Boolean,
        val guidanceVisibility: GuidancePaneVisibility?) {
        lateinit var death: IBinder.DeathRecipient
        @Volatile var displayId = -1
        @Volatile var linked = false
        @Volatile var packageName: String? = null
        var closing: CompletableFuture<Unit>? = null // Guarded by lock.
    }
    private val lock = Any()
    private var parent: Parent? = null
    private val leases = HashMap<Int, Lease>()
    private val nextHandle = AtomicInteger(1000)
    private val cleanup = Executors.newSingleThreadExecutor { r ->
        Thread(r, "headswap-embedded-cleanup").apply { isDaemon = true }
    }

    fun activate(owner: Any, remote: IDexUserService, displayId: Int, calls: ExecutorService, mode: Int,
        density: Int, compactMapSymbols: Boolean, correctionPackages: Set<String> = DesktopRenderPolicy.DEFAULT_PACKAGES,
        shizuku: Boolean = false, nativeLifetime: IBinder = remote.asBinder()) = synchronized(lock) {
        require(displayId > 0)
        DesktopGeometryPolicy.requireDensity(density)
        check(parent == null || parent?.owner === owner) { "Предыдущий рабочий стол ещё активен" }
        val selected = if (compactMapSymbols) correctionPackages.toSet() else emptySet()
        check(nativeLifetime.isBinderAlive) { "Производитель рабочего стола завершился" }
        parent = Parent(owner, remote, displayId, calls, mode, density, selected, shizuku, nativeLifetime)
    }

    /** In-process capability. A reused numeric display ID never authenticates a successor session. */
    fun captureParent(parentId: Int): Any? = synchronized(lock) {
        parent?.takeIf { it.displayId == parentId && it.nativeLifetime.isBinderAlive }
    }
    fun isCurrentParent(parentId: Int, proof: Any): Boolean = synchronized(lock) {
        parent === proof && parent?.displayId == parentId && parent?.nativeLifetime?.isBinderAlive == true
    }
    fun parentMode(parentId: Int): Int? = synchronized(lock) { parent?.takeIf { it.displayId == parentId }?.mode }
    /** Uses the producer's existing Home/profile path; no UI intent guesses the selected component. */
    fun requestHome(parentId: Int, proof: Any): Boolean {
        val active = synchronized(lock) { parent?.takeIf { it === proof && it.displayId == parentId && it.nativeLifetime.isBinderAlive } } ?: return false
        cleanup.execute {
            runCatching { call(active) { remote ->
                check(isCurrentParent(parentId, proof))
                remote.showDesktop()
            } }
        }
        return true
    }


    fun renderConfig(parentId: Int, ownDesktop: Boolean, packageName: String? = null): IntArray = synchronized(lock) {
        val active = checkNotNull(parent) { "Рабочий стол ещё не готов" }
        EmbeddedGatewayPolicy.requireParent(active.mode, parentId, active.displayId, ownDesktop)
        // Older companions have one flag for both maps. Never turn on a package the user removed.
        val corrected = if (packageName == null) active.correctedPackages.containsAll(DesktopRenderPolicy.DEFAULT_PACKAGES)
            else {
                EmbeddedDisplayPolicy.requirePackage(packageName)
                packageName in active.correctedPackages
            }
        intArrayOf(active.density, if (corrected) 1 else 0)
    }

    /** UI overlays can target only the current live parent, never display 0 or an unrelated monitor. */
    fun ownsParentDisplay(id: Int): Boolean = synchronized(lock) {
        id > 0 && parent?.let { it.displayId == id && it.remote.asBinder().isBinderAlive } == true
    }

    fun isAndroidDesktop(id: Int): Boolean = synchronized(lock) {
        id > 0 && parent?.let { it.displayId == id && it.mode == DesktopModePolicy.ANDROID_DESKTOP } == true
    }

    private data class GuidanceTarget(val parent: Parent, val displayId: Int, val child: Lease?, val pkg: String?,
        val visibleEpoch: Any? = null)

    /** Called with lock. Never query the phone display or tiles hidden behind a fullscreen app. */
    private fun guidanceTargets(packages: Set<String> = YandexGuidanceCard.PACKAGES): List<GuidanceTarget> {
        if (!CarLifeState.running) return emptyList()
        val active = parent?.takeIf { it.remote.asBinder().isBinderAlive && it.nativeLifetime.isBinderAlive } ?: return emptyList()
        val current = leases.values.filter { it.parent === active }
        if (current.isEmpty()) return listOf(GuidanceTarget(active, active.displayId, null, null))
        val foreground = current.filter { it.fullscreen }.ifEmpty { current }
        return foreground.mapNotNull { lease ->
            val pkg = lease.packageName
            val visibleEpoch = lease.guidanceVisibility?.let { it.visibleEpoch ?: return@mapNotNull null }
            if (lease.displayId <= 0 || lease.closing != null || !lease.token.isBinderAlive || pkg !in packages) null
            else GuidanceTarget(active, lease.displayId, lease, pkg, visibleEpoch)
        }.sortedBy { it.child?.handle }
    }

    fun guidanceDisplayIds(): List<Int> = synchronized(lock) { guidanceTargets().map { it.displayId } }

    /** A provider reads only its own visible Activity. Capture leases before IPC and validate them after. */
    fun adapterGuidanceScope(pkg: String): Map<Int, Any> = synchronized(lock) {
        guidanceTargets(setOf(pkg)).associate { it.displayId to it }
    }

    /** Capture the active lease around the bounded shell read. A replacement task, display or
     * connection cannot supply a late result to a new session. The caller is a HUD worker. */
    private fun readGuidance(displayId: Int, shizuku: Boolean,
        read: (IDexUserService) -> ShellManeuverSample): ShellManeuverSample {
        val target = synchronized(lock) { guidanceTargets().find { it.parent.shizuku == shizuku && it.displayId == displayId } }
            ?: return ShellManeuverSample.Unavailable
        val result = try { read(target.parent.remote) }
        catch (_: android.os.RemoteException) { return ShellManeuverSample.Unavailable }
        catch (_: RuntimeException) { return ShellManeuverSample.Unavailable }
        if (result is ShellManeuverSample.Card && !YandexGuidanceScope.allows(target.pkg, result.packageName))
            return ShellManeuverSample.Unavailable
        return synchronized(lock) { if (target in guidanceTargets()) result else ShellManeuverSample.Unavailable }
    }

    fun readShizukuGuidance(displayId: Int, includeRoad: Boolean = false): ShellManeuverSample = readGuidance(displayId, true) {
        ShellManeuverSample.decode(if (includeRoad) it.readShizukuGuidanceWithRoad(displayId) else it.readShizukuGuidance(displayId))
    }

    fun readDelegatedGuidance(displayId: Int, read: () -> ShellManeuverSample): ShellManeuverSample =
        readGuidance(displayId, false) { read() }

    /** Revalidate the captured pane before every asynchronous Assist callback, including hide/reveal. */
    fun readDelegatedGuidanceScoped(displayId: Int, read: (() -> Unit) -> ShellManeuverSample): ShellManeuverSample {
        val target = synchronized(lock) { guidanceTargets().find { !it.parent.shizuku && it.displayId == displayId } }
            ?: return ShellManeuverSample.Unavailable
        val requireCurrent = { check(synchronized(lock) { target in guidanceTargets() }) { "Guidance pane changed" } }
        return readDelegatedGuidance(displayId) { read(requireCurrent) }
    }

    /** Reject new operations immediately; retained leases can still acknowledge producer release. */
    fun deactivate(owner: Any) = synchronized(lock) {
        if (parent?.owner === owner) parent = null
    }

    /** Called only after stop ACK or confirmed shell process death. */
    fun producerStopped(owner: Any) {
        val completed = synchronized(lock) {
            if (parent?.owner === owner) parent = null
            leases.values.filter { it.parent.owner === owner }.also { list -> list.forEach { leases.remove(it.handle) } }
        }
        completed.forEach { it.closing?.complete(Unit); unlink(it) }
    }

    fun create(uid: Int, surface: Surface, token: IBinder, requestedParent: Int,
        width: Int, height: Int, density: Int, ownDesktop: Boolean = false, fullscreen: Boolean = false,
        guidanceVisibility: GuidancePaneVisibility? = null): Int {
        EmbeddedGatewayPolicy.requireGeometry(width, height, density)
        require(surface.isValid && token.isBinderAlive) { "Поверхность встроенного приложения недоступна" }
        val lease = synchronized(lock) {
            val active = checkNotNull(parent) { "Рабочий стол ещё не готов" }
            EmbeddedGatewayPolicy.requireParent(active.mode, requestedParent, active.displayId, ownDesktop)
            check(active.remote.asBinder().isBinderAlive) { "Служба рабочего стола завершилась" }
            require(leases.values.none { it.token == token }) { "Токен поверхности уже используется" }
            if (fullscreen) {
                EmbeddedGatewayPolicy.requireFullscreenCaller(active.mode, uid, android.os.Process.myUid())
                if (leases.values.any { it.fullscreen }) throw FullscreenSlotBusyException()
            } else EmbeddedGatewayPolicy.requireCanAllocate(leases.values.count { !it.fullscreen })
            Lease(nextHandle.incrementAndGet(), uid, active, token, fullscreen, guidanceVisibility).also { leases[it.handle] = it }
        }
        lease.death = IBinder.DeathRecipient {
            cleanup.execute { runCatching { close(lease) }.onFailure { Log.w("HeadSwapEmbed", "Companion cleanup pending", it) } }
        }
        try {
            token.linkToDeath(lease.death, 0)
            lease.linked = true
            activeCall(lease) {
                val bufferDpi = lease.parent.bufferDensity(density)
                val id = if (fullscreen) it.createFullscreenChildDisplay(surface, token, width, height, bufferDpi)
                else it.createChildDisplay(surface, token, width, height, bufferDpi)
                check(id > 0 && id != requestedParent) { "Приложение не получило отдельный дисплей" }
                lease.displayId = id
            }
            synchronized(lock) {
                check(parent === lease.parent && leases[lease.handle] === lease && token.isBinderAlive) {
                    "Сеанс рабочего стола завершился"
                }
            }
            return lease.handle
        } catch (error: Throwable) {
            // Never claim that a Surface can be freed if its producer might still be alive.
            runCatching { close(lease) }.onFailure { Log.w("HeadSwapEmbed", "Failed creation cleanup pending", it) }
            throw error
        }
    }

    private fun active(uid: Int, handle: Int): Lease = synchronized(lock) {
        val lease = checkNotNull(leases[handle]) { "Встроенное окно уже закрыто" }
        if (lease.uid != uid) throw SecurityException("Чужое встроенное окно")
        check(parent === lease.parent && lease.token.isBinderAlive) { "Сеанс рабочего стола завершился" }
        lease
    }

    /** Local lease liveness is independent of a delayed or unavailable ActivityManager observation. */
    fun isComponentLeaseAlive(uid: Int, handle: Int): Boolean = synchronized(lock) {
        val lease = leases[handle] ?: return@synchronized false
        lease.uid == uid && parent === lease.parent && lease.parent.mode == DesktopModePolicy.ANDROID_DESKTOP &&
            !lease.fullscreen && lease.displayId > 0 && lease.closing == null && lease.token.isBinderAlive &&
            lease.parent.remote.asBinder().isBinderAlive
    }

    fun resize(uid: Int, handle: Int, width: Int, height: Int, density: Int) {
        EmbeddedGatewayPolicy.requireGeometry(width, height, density)
        val lease = active(uid, handle)
        activeCall(lease) { it.resizeChildDisplay(lease.token, width, height, lease.parent.bufferDensity(density)) }
    }
    fun launch(uid: Int, handle: Int, packageName: String, className: String) {
        EmbeddedGatewayPolicy.requireComponent(packageName, className)
        val lease = active(uid, handle)
        activeCall(lease) {
            val settings = packageName == "com.android.settings"
            try {
                it.launchChildApp(lease.token, packageName, className)
                lease.packageName = packageName
                if (settings) AppLog.i("SYSTEM SETTINGS: launch accepted component=$className display=${lease.displayId}", false)
            } catch (error: Exception) {
                if (settings) AppLog.e("SYSTEM SETTINGS: launch failed component=$className display=${lease.displayId}", error)
                throw error
            }
        }
    }
    /** Internal-only: providers never receive this gateway or the child display ID. Start as our
     * ordinary Activity UID, including when the producer of this particular display runs as shell. */
    fun launchDesktopComponent(activity: Activity, handle: Int, target: DesktopComponentEntry,
        decorate: (Intent, Int) -> Unit) {
        launchHostChild(activity, handle, target.componentName, decorate) {
            val selected = DesktopComponents.selected(activity)
            check(selected == target) {
                "Компонент рабочего стола изменился"
            }
            // A warm provider also opened on the phone keeps its own task there.
            Intent(DesktopComponents.ACTION).addCategory(Intent.CATEGORY_DEFAULT).addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
    }

    /** A panel request cannot inherit Shizuku's launch permissions. Resolve it at dispatch
     * on the ordinary host UID, exactly as a public MAIN/LAUNCHER entry in this Android user. */
    fun launchPanelApp(activity: Activity, handle: Int, target: ComponentName) {
        launchHostChild(activity, handle, target, { _, _ -> }) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target.packageName)
            check(activity.packageManager.queryIntentActivities(launcher, 0).any {
                val info = it.activityInfo
                info.packageName == target.packageName && info.name == target.className && info.enabled &&
                    info.applicationInfo.enabled && info.exported && info.permission.isNullOrEmpty() &&
                    DesktopComponentPolicy.supportsIsolatedTask(info.launchMode, info.documentLaunchMode,
                        info.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING != 0)
            }) { "Приложение не поддерживает независимую задачу в окне панели" }
            launcher.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
    }

    /** Starts only as the ordinary host UID. For an existing/unknown single-task window we first
     * return to the host for confirmation. Only a stopped package is definitively cold. */
    fun launchDashboardApp(activity: Activity, handle: Int, provider: DesktopComponentEntry,
        target: ComponentName, allowTaskMove: Boolean, stillCurrent: () -> Boolean): Boolean {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target.packageName)
        val pm = activity.packageManager
        return launchHostChild(activity, handle, target, { _, _ ->
            check(stillCurrent() && provider == DesktopComponents.selected(activity))
        }) {
            check(stillCurrent() && provider.profileChrome && provider == DesktopComponents.selected(activity))
            check(EmbeddedGatewayPolicy.allowsLauncherPackage(target.packageName, activity.packageName) &&
                target.packageName != provider.packageName)
            val info = pm.queryIntentActivities(query, 0).mapNotNull { it.activityInfo }.firstOrNull {
                it.packageName == target.packageName && it.name == target.className && it.enabled &&
                    it.exported && it.applicationInfo.enabled && it.permission.isNullOrEmpty()
            } ?: error("Приложение недоступно на рабочем столе")
            // Some Android versions do not copy all launch attributes onto an exported alias.
            val declared = info.targetActivity?.let { pm.getActivityInfo(ComponentName(target.packageName, it), 0) } ?: info
            check(declared.enabled && declared.applicationInfo.enabled &&
                declared.applicationInfo.uid == info.applicationInfo.uid)
            val isolated = DesktopComponentPolicy.supportsIsolatedTask(declared.launchMode, declared.documentLaunchMode,
                declared.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING != 0)
            if (!isolated && !allowTaskMove && (info.applicationInfo.flags and ApplicationInfo.FLAG_STOPPED) == 0)
                return@launchHostChild null
            query.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
    }

    private fun launchHostChild(activity: Activity, handle: Int, target: ComponentName,
        decorate: (Intent, Int) -> Unit, resolve: () -> Intent?): Boolean {
        val lease = active(Process.myUid(), handle)
        return activeCall(lease) { remote ->
            val id = lease.displayId
            EmbeddedGatewayPolicy.requireComponentChild(lease.parent.mode, lease.fullscreen,
                activity.display?.displayId ?: -1, lease.parent.displayId, id)
            val context = activity.applicationContext
            val intent = resolve()?.setComponent(target)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return@activeCall false
            check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS) &&
                context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, id, intent)) {
                "Android не разрешил запуск на дочернем дисплее рабочего стола"
            }
            decorate(intent, id)
            remote.trackDesktopChildLaunch(lease.token, target.packageName)
            synchronized(lock) {
                check(parent === lease.parent && leases[handle] === lease && lease.closing == null &&
                    lease.token.isBinderAlive && !activity.isDestroyed)
            }
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
            lease.packageName = target.packageName
            true
        }
    }

    /** Notification capabilities stay in the host; a component can never supply an arbitrary PendingIntent. */
    fun launchNotification(activity: Activity, handle: Int, ticket: com.headswap.desk.notify.HuNotificationLaunch.Ticket) {
        val lease = active(Process.myUid(), handle)
        activeCall(lease) { remote ->
            check(lease.fullscreen && activity.display?.displayId == lease.parent.displayId &&
                ticket.parentId == lease.parent.displayId && ticket.parentProof === lease.parent && ticket.current(activity))
            com.headswap.desk.dex.EmbeddedDisplayPolicy.requireChildId(lease.displayId, lease.parent.displayId)
            remote.trackDesktopChildLaunch(lease.token, ticket.action.packageName)
            check(ticket.current(activity) && !activity.isDestroyed && !activity.isFinishing &&
                activity is com.headswap.desk.desktop.NotificationFullscreenActivity && activity.launchVisible)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(lease.displayId)
            // Android 14+ requires the sender to opt in for a user-click PendingIntent.
            // Android 16+ narrows this to our actually visible fullscreen host Activity.
            if (android.os.Build.VERSION.SDK_INT >= 36)
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            else if (android.os.Build.VERSION.SDK_INT >= 34)
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            ticket.action.intent.send(activity, 0, null, null, null, null, options.toBundle())
            lease.packageName = ticket.action.packageName
        }
    }

    fun touch(uid: Int, handle: Int, action: Int, x: Float, y: Float) {
        val lease = active(uid, handle)
        activeCall(lease) { it.injectChildTouch(lease.token, action, x, y) }
    }
    fun touchPoints(uid: Int, handle: Int, action: Int, actionIndex: Int, pointerIds: IntArray,
        xs: FloatArray, ys: FloatArray) {
        DesktopTouchState.validate(action, actionIndex, pointerIds, xs, ys)
        val lease = active(uid, handle)
        activeCall(lease) { it.injectChildTouchPoints(lease.token, action, actionIndex, pointerIds, xs, ys) }
    }
    fun back(uid: Int, handle: Int) {
        val lease = active(uid, handle)
        activeCall(lease) { it.injectChildBack(lease.token) }
    }
    fun producerLifetime(uid: Int, handle: Int): IBinder {
        val lease = active(uid, handle)
        return activeCall(lease) { it.childProducerLifetime(lease.token) }
    }
    fun taskState(uid: Int, handle: Int, packageName: String): Int {
        val lease = active(uid, handle)
        if (lease.packageName == null) return 0 // A newly created owned display has no app task yet.
        if (lease.packageName != packageName) return 0 // Only the app explicitly launched into this lease is tracked.
        return activeCall(lease) { it.childTaskState(lease.token, packageName) }
    }
    fun release(uid: Int, handle: Int) {
        val lease = synchronized(lock) { leases[handle] } ?: return // Already stopped with ACK.
        if (lease.uid != uid) throw SecurityException("Чужое встроенное окно")
        close(lease)
    }
    /** Also acknowledges a partially created producer whose original create() reply was lost. */
    fun releaseOwnSurface(uid: Int, token: IBinder) {
        val lease = synchronized(lock) { leases.values.firstOrNull { it.uid == uid && it.token == token } } ?: return
        close(lease)
    }

    private fun close(lease: Lease) {
        var leader = false
        val done = synchronized(lock) {
            if (leases[lease.handle] !== lease) { unlink(lease); return }
            lease.closing ?: CompletableFuture<Unit>().also { lease.closing = it; leader = true }
        }
        if (!leader) { done.get(30, TimeUnit.SECONDS); return }
        try {
            if (lease.parent.remote.asBinder().isBinderAlive) {
                val stop: (IDexUserService) -> Unit = { remote ->
                    if (synchronized(lock) { leases[lease.handle] === lease }) remote.closeChildDisplay(lease.token)
                }
                try {
                    try { call(lease.parent, stop) }
                    catch (_: RejectedExecutionException) { stop(lease.parent.remote) }
                } catch (error: Exception) { if (lease.parent.nativeLifetime.isBinderAlive) throw error }
            } else {
                check(!lease.parent.nativeLifetime.isBinderAlive) {
                    "Нативный производитель ещё не подтвердил закрытие окна"
                }
            }
            synchronized(lock) { if (leases[lease.handle] === lease) leases.remove(lease.handle) }
            unlink(lease)
            done.complete(Unit)
        } catch (error: Throwable) {
            synchronized(lock) { if (lease.closing === done) lease.closing = null }
            done.completeExceptionally(error)
            throw error
        }
    }

    private fun <T> activeCall(lease: Lease, action: (IDexUserService) -> T): T = call(lease.parent) { remote ->
        synchronized(lock) {
            check(parent === lease.parent && leases[lease.handle] === lease && lease.closing == null &&
                lease.token.isBinderAlive) { "Встроенное окно уже закрыто" }
        }
        action(remote)
    }
    private fun unlink(lease: Lease) {
        if (lease.linked) {
            runCatching { lease.token.unlinkToDeath(lease.death, 0) }
            lease.linked = false
        }
    }

    /** Child app starts cannot overlap parent input RPCs and trip its shorter input timeout. */
    private fun <T> call(parent: Parent, action: (IDexUserService) -> T): T {
        val result = parent.calls.submit<T> { action(parent.remote) }
        try { return result.get(30, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw error.cause ?: error }
        // Do not cancel a running Binder call: its producer must still acknowledge Surface release.
    }
}
