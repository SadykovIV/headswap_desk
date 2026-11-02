package com.headswap.desk.embed

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.ViewConfiguration
import com.headswap.desk.CarLifeState
import com.headswap.desk.aa.DesktopGeometryPolicy
import com.headswap.desk.cluster.ShellManeuverSample
import com.headswap.desk.cluster.YandexGuidanceCard
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.IDexUserService
import com.headswap.desk.dex.DesktopTouchState
import com.headswap.desk.ime.ImeDismissTap
import com.headswap.desk.ime.TapOutsideImeAccessibility
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Session authority stays in HeadSwap. Public handles are unrelated to Android display IDs. */
internal object EmbeddedSessionRegistry {
    private class Parent(val owner: Any, val remote: IDexUserService, val displayId: Int, val calls: ExecutorService,
        val mode: Int, val density: Int, val compactMapSymbols: Boolean, val shizuku: Boolean)
    private class Lease(val handle: Int, val uid: Int, val parent: Parent, val token: IBinder, val fullscreen: Boolean,
        var density: Int) {
        lateinit var death: IBinder.DeathRecipient
        @Volatile var displayId = -1
        val dismissTap = ImeDismissTap()
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
        density: Int, compactMapSymbols: Boolean, shizuku: Boolean = false) = synchronized(lock) {
        require(displayId > 0)
        DesktopGeometryPolicy.requireDensity(density)
        check(parent == null || parent?.owner === owner) { "Предыдущий рабочий стол ещё активен" }
        parent = Parent(owner, remote, displayId, calls, mode, density, compactMapSymbols, shizuku)
    }

    fun renderConfig(parentId: Int, ownDesktop: Boolean): IntArray = synchronized(lock) {
        val active = checkNotNull(parent) { "Рабочий стол ещё не готов" }
        EmbeddedGatewayPolicy.requireParent(active.mode, parentId, active.displayId, ownDesktop)
        intArrayOf(active.density, if (active.compactMapSymbols) 1 else 0)
    }

    fun isAndroidDesktop(id: Int): Boolean = synchronized(lock) {
        id > 0 && parent?.let { it.displayId == id && it.mode == DesktopModePolicy.ANDROID_DESKTOP } == true
    }

    private data class GuidanceTarget(val parent: Parent, val displayId: Int, val child: Lease?, val pkg: String?)

    /** Called with lock. Never query the phone display or tiles hidden behind a fullscreen app. */
    private fun guidanceTargets(): List<GuidanceTarget> {
        if (!CarLifeState.running) return emptyList()
        val active = parent?.takeIf { it.remote.asBinder().isBinderAlive } ?: return emptyList()
        val current = leases.values.filter { it.parent === active }
        val foreground = current.filter { it.fullscreen }.ifEmpty { current }
        return foreground.mapNotNull { lease ->
            val pkg = lease.packageName
            if (lease.displayId <= 0 || lease.closing != null || !lease.token.isBinderAlive || pkg !in YandexGuidanceCard.PACKAGES) null
            else GuidanceTarget(active, lease.displayId, lease, pkg)
        }.sortedBy { it.child?.handle } + GuidanceTarget(active, active.displayId, null, null)
    }

    fun guidanceDisplayIds(): List<Int> = synchronized(lock) { guidanceTargets().map { it.displayId } }

    /** Called on a HUD worker, outside the input queue. Revalidate identity after crossing Binder. */
    fun readShizukuGuidance(displayId: Int): ShellManeuverSample {
        val target = synchronized(lock) { guidanceTargets().find { it.parent.shizuku && it.displayId == displayId } }
            ?: return ShellManeuverSample.Unavailable
        val result = try { ShellManeuverSample.decode(target.parent.remote.readShizukuGuidance(displayId)) }
        catch (_: android.os.RemoteException) { return ShellManeuverSample.Unavailable }
        catch (_: RuntimeException) { return ShellManeuverSample.Unavailable }
        if (result is ShellManeuverSample.Card && target.pkg != null && result.packageName != target.pkg)
            return ShellManeuverSample.Unavailable
        return synchronized(lock) { if (target in guidanceTargets()) result else ShellManeuverSample.Unavailable }
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
        width: Int, height: Int, density: Int, ownDesktop: Boolean = false, fullscreen: Boolean = false): Int {
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
            Lease(nextHandle.incrementAndGet(), uid, active, token, fullscreen, density).also { leases[it.handle] = it }
        }
        lease.death = IBinder.DeathRecipient {
            cleanup.execute { runCatching { close(lease) }.onFailure { Log.w("HeadSwapEmbed", "Companion cleanup pending", it) } }
        }
        try {
            token.linkToDeath(lease.death, 0)
            lease.linked = true
            activeCall(lease) {
                val id = if (fullscreen) it.createFullscreenChildDisplay(surface, token, width, height, density)
                else it.createChildDisplay(surface, token, width, height, density)
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

    fun resize(uid: Int, handle: Int, width: Int, height: Int, density: Int) {
        EmbeddedGatewayPolicy.requireGeometry(width, height, density)
        val lease = active(uid, handle)
        activeCall(lease) {
            it.resizeChildDisplay(lease.token, width, height, density)
            lease.density = density
            lease.dismissTap.reset()
        }
    }
    fun launch(uid: Int, handle: Int, packageName: String, className: String) {
        EmbeddedGatewayPolicy.requireComponent(packageName, className)
        val lease = active(uid, handle)
        activeCall(lease) {
            it.launchChildApp(lease.token, packageName, className)
            lease.packageName = packageName
            lease.dismissTap.reset()
        }
    }
    fun touch(uid: Int, handle: Int, action: Int, x: Float, y: Float) {
        val lease = active(uid, handle)
        activeCall(lease) { remote ->
            val dismiss = dismissOnUp(lease, action, 1, x, y)
            remote.injectChildTouch(lease.token, if (dismiss) 3 else action, x, y)
            if (dismiss) remote.dismissChildIme(lease.token)
        }
    }
    fun touchPoints(uid: Int, handle: Int, action: Int, actionIndex: Int, pointerIds: IntArray,
        xs: FloatArray, ys: FloatArray) {
        DesktopTouchState.validate(action, actionIndex, pointerIds, xs, ys)
        val lease = active(uid, handle)
        activeCall(lease) { remote ->
            val dismiss = dismissOnUp(lease, action, pointerIds.size, xs[0], ys[0])
            remote.injectChildTouchPoints(lease.token, if (dismiss) 3 else action, actionIndex, pointerIds, xs, ys)
            if (dismiss) remote.dismissChildIme(lease.token)
        }
    }
    // Called only on the parent's serial worker. A disambiguated outside tap is consumed,
    // so the same UP cannot both activate a map control and close the keyboard.
    private fun dismissOnUp(lease: Lease, action: Int, count: Int, x: Float, y: Float): Boolean {
        val pkg = lease.packageName ?: return false
        return lease.dismissTap.consumeUp(action, count, x, y, SystemClock.uptimeMillis(), lease.density * 8f / 160,
            ViewConfiguration.getLongPressTimeout().toLong()) {
            TapOutsideImeAccessibility.classify(lease.displayId, pkg, x, y) == TapOutsideImeAccessibility.Hit.OUTSIDE
        }
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
                } catch (error: Exception) { if (lease.parent.remote.asBinder().isBinderAlive) throw error }
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
