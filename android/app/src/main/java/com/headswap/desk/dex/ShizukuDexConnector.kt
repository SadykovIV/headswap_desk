package com.headswap.desk.dex

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.os.Binder
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import com.headswap.desk.AppLog
import com.headswap.desk.BuildConfig
import com.headswap.desk.embed.EmbeddedSessionRegistry
import com.headswap.desk.aa.AaBridgeCore
import com.headswap.desk.aa.AaLifecycle
import com.headswap.desk.aa.AaPeer
import com.headswap.desk.aa.AaState
import com.headswap.desk.aa.DesktopSurfaceListener
import com.headswap.desk.aa.DesktopPowerControl
import com.headswap.desk.aa.DesktopNavigation
import com.headswap.desk.aa.TerminalSource
import com.headswap.desk.aa.SurfaceVideoInput
import com.headswap.desk.aa.TouchPoint
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException

/** Owns the privileged producer; CarLife owns its consumer Surface and single H.264 encoder. */
class ShizukuDexConnector(context: Context, private val core: AaBridgeCore,
    private val onFailed: (Failure) -> Unit = {}) :
    AaLifecycle, AaPeer, DesktopSurfaceListener, DesktopPowerControl, DesktopNavigation, TerminalSource {
    enum class Failure { HELPER_UNAVAILABLE, OTHER }
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean()
    private val stopping = AtomicBoolean()
    private val failureSignalled = AtomicBoolean()
    override val isTerminated: Boolean get() = stopping.get() || failureSignalled.get()
    private val stateLock = Any()
    private val connected = CountDownLatch(1)
    private val serviceDied = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val lifetime = Binder()
    // Binder transactions are not cancelled by Future.cancel(). Keep start/stop ordered
    // even when the caller's deadline expires and the remote start finishes late.
    private val binderCalls = Executors.newSingleThreadExecutor { r -> Thread(r, "dex-binder").apply { isDaemon = true } }
    private val input = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue(128),
        { r -> Thread(r, "dex-mouse").apply { isDaemon = true } })
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dex-watch").apply { isDaemon = true } }
    // Independent tags prevent a late callback from an old USB session killing its successor.
    private val args = Shizuku.UserServiceArgs(ComponentName(this.context, DexUserService::class.java))
        .processNameSuffix("dex").tag("headswap-dex-${UUID.randomUUID()}")
        .version(BuildConfig.VERSION_CODE).daemon(false).debuggable(BuildConfig.DEBUG)
    @Volatile private var service: IDexUserService? = null
    @Volatile private var serviceBinder: IBinder? = null
    private var bindRequested = false // Read/written only on the main looper.
    @Volatile private var generation = 0L
    @Volatile private var retiringGeneration = 0L
    @Volatile private var displayId = -1
    @Volatile private var desktopMode = DesktopModePolicy.ANDROID_DESKTOP
    @Volatile private var firstFrame = false
    @Volatile private var established = false
    @Volatile private var failure: String? = null
    @Volatile private var generationStartedAt = 0L
    private var phonePowerPending = false // Guarded by stateLock.
    private val death = IBinder.DeathRecipient {
        EmbeddedSessionRegistry.producerStopped(this)
        serviceDied.countDown()
        val retired = retiringGeneration
        if (retired != 0L) core.desktopSurfaceProducerClosed(retired)
        fail(helperUnavailableMessage(), Failure.HELPER_UNAVAILABLE)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                serviceBinder = binder
                binder.linkToDeath(death, 0)
                service = IDexUserService.Stub.asInterface(binder)
                // A callback already queued by Shizuku may arrive after removal. Repeat
                // removal for this unique tag rather than leaving its new process alive.
                if (stopping.get()) unbind()
            } catch (e: Exception) { fail("Не удалось открыть службу Shizuku: ${e.message}") }
            finally { connected.countDown() }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            // A disconnect callback is not proof that the producer process died.
            fail(helperUnavailableMessage(), Failure.HELPER_UNAVAILABLE)
        }
        override fun onNullBinding(name: ComponentName) { connected.countDown(); fail("Shizuku не запустил службу рабочего стола") }
    }

    override fun start() {
        synchronized(stateLock) {
            if (!started.compareAndSet(false, true) || stopping.get()) return
            try {
                DexState.error = null
                DexState.running = false
                DexState.displayId = -1
                resetPhonePowerState()
                core.onAaConnecting()
                core.peer = this
                core.enableDesktopSurface(this)
                status("Запускаю рабочий стол через Shizuku…")
            } catch (e: Exception) { fail(e.message ?: "Не удалось подготовить рабочий стол"); return }
        }
        main.post {
            if (!stopping.get()) try {
                check(DexPrivilegeAccess.isReady(context)) { "Откройте Shizuku, запустите службу и разрешите HeadSwap доступ" }
                bindRequested = true
                Shizuku.bindUserService(args, connection)
            } catch (e: Exception) { connected.countDown(); fail(e.message ?: "Shizuku недоступен") }
        }
        try { watchdog.scheduleWithFixedDelay({
            if (!stopping.get()) try {
                core.tick()
                core.desktopVideoError?.let { fail(it) }
                if (generationStartedAt != 0L && !firstFrame &&
                    System.nanoTime() - generationStartedAt > TimeUnit.SECONDS.toNanos(30)) {
                    fail("Рабочий стол не передал первый кадр за 30 секунд")
                }
            } catch (e: Exception) { fail(e.message ?: "Ошибка рабочего стола") }
        }, 500, 500, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.RejectedExecutionException) {
            // stop() raced with scheduling; its cleanup already owns the lifecycle.
        }
    }

    override fun onSurfaceAvailable(generation: Long, input: SurfaceVideoInput) {
        if (stopping.get()) return
        this.generation = generation
        firstFrame = false
        generationStartedAt = System.nanoTime()
        try {
            check(connected.await(12, TimeUnit.SECONDS)) { "Shizuku не ответил за 12 секунд" }
            check(!stopping.get()) { "Подключение остановлено" }
            val remote = checkNotNull(service) { "Служба Shizuku недоступна" }
            val prefs = DexPrefs(context)
            val density = input.density
            val compactMapSymbols = prefs.compactMapSymbols
            desktopMode = prefs.desktopMode
            if (desktopMode == DesktopModePolicy.DRIVEDECK) {
                val activity = try {
                    context.packageManager.getActivityInfo(
                        ComponentName(DesktopModePolicy.DRIVEDECK_PACKAGE, DesktopModePolicy.DRIVEDECK_HOME_CLASS),
                        0)
                } catch (_: PackageManager.NameNotFoundException) {
                    error("Установите адаптированный DriveDeck для HeadSwap и подключитесь снова")
                }
                check(activity.exported && activity.enabled && activity.applicationInfo.enabled) {
                    "Адаптированный DriveDeck отключён или недоступен"
                }
            }
            val id = bounded(25) {
                check(remote.protocolVersion() == DexUserServicePolicy.PROTOCOL_VERSION) { "Версия службы Shizuku не совпадает с приложением" }
                remote.startDesktopSized(input.surface, lifetime, input.width, input.height, density, desktopMode)
            }
            check(id > 0) { "Не удалось создать отдельный экран" }
            displayId = id
            if (stopping.get() || !core.desktopSurfaceReady(generation)) {
                onSurfaceClosing(generation)
                return
            }
            synchronized(stateLock) {
                check(!stopping.get() && retiringGeneration != generation) { "Сеанс завершён" }
                DexState.displayId = id
                EmbeddedSessionRegistry.activate(this, remote, id, this.input, desktopMode, density, compactMapSymbols,
                    shizuku = true)
            }
            status("Рабочий стол ${input.width}×${input.height} готов, ожидаю видео…")
            AppLog.i("DESKTOP mode=$desktopMode Shizuku display=$id generation=$generation native=${input.width}x${input.height} density=$density preview=false singleEncode=true", false)
        } catch (e: Exception) {
            if (!stopping.get()) {
                val reason = e.cause?.message ?: e.message ?: "Ошибка создания экрана"
                core.desktopSurfaceFailed(generation, reason)
                fail(reason, failureKind(e))
            }
        }
    }

    override fun onSurfaceClosing(generation: Long) {
        if (this.generation != generation) return
        // Invalidate queued input before retiring the corresponding display.
        synchronized(stateLock) {
            EmbeddedSessionRegistry.deactivate(this)
            retiringGeneration = generation
            resetPhonePowerState()
        }
        while (true) {
            val queued = input.queue.poll() ?: break
            (queued as? Future<*>)?.cancel(false)
        }
        firstFrame = false
        generationStartedAt = 0
        DexState.running = false
        DexState.displayId = -1
        val remote = service
        val binder = serviceBinder
        if (remote != null && binder?.isBinderAlive == true && serviceDied.count != 0L) {
            try { bounded(8) { remote.stop() } }
            catch (e: Exception) {
                AppLog.i("DEX Shizuku: display close failed (${e.javaClass.simpleName}); removing user service", false)
                unbind()
                val confirmedDead = try { serviceDied.await(6, TimeUnit.SECONDS) || !binder.isBinderAlive }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); !binder.isBinderAlive }
                if (!confirmedDead) {
                    val message = "Shizuku не подтвердил закрытие экрана. Остановите Shizuku, затем подключитесь снова"
                    failure = message
                    DexState.error = message
                    fail(message)
                    // The core retains this generation's consumer until the actual
                    // death recipient acknowledges desktopSurfaceProducerClosed().
                    throw IOException(message, e)
                }
            }
        }
        EmbeddedSessionRegistry.producerStopped(this)
        this.generation = 0
        retiringGeneration = 0
        displayId = -1
        if (!stopping.get()) status("Ожидаю повторного подключения USB CarLife…")
    }

    override fun onFirstFrame(generation: Long) {
        if (stopping.get() || this.generation != generation) return
        firstFrame = true
        established = true
        DexState.running = true
        DexState.displayId = displayId
        status("${DesktopModePolicy.label(desktopMode)} → CarLife · ${core.videoMode.outLabel}", false)
    }

    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        if (points.isEmpty() || points.size > 2) return
        val width = core.videoMode.outW
        val height = core.videoMode.outH
        if (desktopMode != DesktopModePolicy.SAMSUNG_DEX) {
            val ids = points.map { it.id }.toIntArray()
            val xs = points.map { it.x.coerceIn(0, width - 1).toFloat() }.toFloatArray()
            val ys = points.map { it.y.coerceIn(0, height - 1).toFloat() }.toFloatArray()
            if (runCatching { DesktopTouchState.validate(action, actionIndex, ids, xs, ys) }.isFailure) return
            enqueue { it.injectTouchPoints(action, actionIndex, ids, xs, ys) }
        } else {
            // Samsung window captions and resize edges require mouse input.
            val p = points.first()
            if (action !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL)) return
            val buttons = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0 else MotionEvent.BUTTON_PRIMARY
            enqueue { it.injectPointer(action, p.x.coerceIn(0, width - 1), p.y.coerceIn(0, height - 1), buttons) }
        }
    }

    override fun sendKey(keycode: Int) {
        if (keycode !in 0..1000) return
        enqueue { it.injectKey(0, keycode); it.injectKey(1, keycode) }
    }

    override fun requestKeyFrame() { /* CarLife requests its own encoder's IDR directly. */ }

    override fun showDesktop(): Boolean {
        if (stopping.get() || generation == 0L || retiringGeneration == generation || !firstFrame) return false
        enqueue { it.showDesktop() }
        return true
    }

    /** Serializes with display start/stop, but never blocks the service's main thread. */
    override fun setPhoneScreenOff(off: Boolean): Boolean {
        val token = synchronized(stateLock) {
            if (!canControlPhonePanel(generation) || phonePowerPending) return false
            phonePowerPending = true
            DexState.phoneScreenBusy = true
            DexState.phoneScreenError = null
            generation
        }
        try {
            binderCalls.execute {
                try {
                    if (!synchronized(stateLock) { canControlPhonePanel(token) }) return@execute
                    checkNotNull(service) { "Служба Shizuku недоступна" }.setPhoneScreenOff(off)
                    synchronized(stateLock) {
                        if (canControlPhonePanel(token)) {
                            DexState.phoneScreenOff = off
                            AppLog.i("DEX phone panel acknowledged off=$off generation=$token", false)
                        }
                    }
                } catch (e: Exception) {
                    synchronized(stateLock) {
                        if (canControlPhonePanel(token)) {
                            val detail = e.cause?.message ?: e.message ?: e.javaClass.simpleName
                            DexState.phoneScreenError = "Не удалось изменить состояние экрана телефона: $detail"
                            AppLog.i("DEX phone panel failed off=$off generation=$token type=${e.javaClass.simpleName}", false)
                        }
                    }
                } finally {
                    synchronized(stateLock) {
                        if (generation == token) {
                            phonePowerPending = false
                            DexState.phoneScreenBusy = false
                        }
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(stateLock) {
                if (generation == token) {
                    phonePowerPending = false
                    DexState.phoneScreenBusy = false
                }
            }
            return false
        }
        return true
    }

    private fun canControlPhonePanel(token: Long): Boolean =
        token != 0L && generation == token && retiringGeneration != token &&
            !stopping.get() && firstFrame && service != null

    /** Call under stateLock, after invalidating requests for a retiring generation. */
    private fun resetPhonePowerState() {
        phonePowerPending = false
        DexState.phoneScreenOff = false
        DexState.phoneScreenBusy = false
        DexState.phoneScreenError = null
    }

    private fun enqueue(block: (IDexUserService) -> Unit) {
        val token = generation
        if (stopping.get() || token == 0L || retiringGeneration == token || !firstFrame) return
        try { input.execute {
            if (!stopping.get() && generation == token && retiringGeneration != token && firstFrame) try { service?.let(block) }
            catch (e: Exception) {
                // An already dequeued input can overlap normal USB-detach cleanup.
                // Its retired display is gone; this must not end a reconnectable session.
                if (!stopping.get() && generation == token && retiringGeneration != token && firstFrame) {
                    fail("Не удалось передать управление рабочему столу: ${e.message}", failureKind(e))
                }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (!stopping.get()) fail("Очередь управления переполнена. Подключитесь снова")
        }
    }

    private fun <T> bounded(seconds: Long, block: () -> T): T {
        val future = binderCalls.submit<T> { block() }
        return future.get(seconds, TimeUnit.SECONDS)
    }

    private fun helperUnavailableMessage(): String =
        if (DexPrivilegeAccess.isReady(context)) "Служба рабочего стола завершилась; Shizuku доступен"
        else "Сервер Shizuku недоступен. Запустите Shizuku и подключитесь снова"

    private fun failureKind(error: Throwable): Failure =
        if (error is DeadObjectException || error.cause is DeadObjectException) Failure.HELPER_UNAVAILABLE else Failure.OTHER

    private fun fail(message: String, reason: Failure = Failure.OTHER) {
        if (stopping.get() || !failureSignalled.compareAndSet(false, true)) return
        failure = message
        DexState.error = message
        AppLog.i("DEX Shizuku: $message")
        // The host must retire this one-shot connector even if the USB transport stays alive.
        runCatching { onFailed(reason) }.onFailure { AppLog.i("DEX failure callback: ${it.javaClass.simpleName}", false) }
        stop {}
    }

    override fun stop(onDone: () -> Unit) {
        val firstStop = synchronized(stateLock) {
            stopping.compareAndSet(false, true).also { if (it) {
                EmbeddedSessionRegistry.deactivate(this)
                resetPhonePowerState()
            } }
        }
        if (firstStop) {
            connected.countDown()
            watchdog.shutdownNow()
            input.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
            // Terminate our dedicated producer promptly, including a pending start
            // transaction. The Surface is still retained until closure/death is proved.
            unbind()
            core.stopDesktopSurface {
                unbind()
                binderCalls.shutdownNow()
                if (core.peer === this) core.peer = null
                core.onAaEnded(established)
                DexState.running = false
                DexState.displayId = -1
                DexState.error = failure
                status(failure?.let { "DeX: $it" } ?: "DeX остановлен")
                finished.countDown()
            }
        }
        Thread({
            try { finished.await(); onDone() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }, "dex-stop").apply { isDaemon = true; start() }
    }

    private fun unbind() {
        // Serialize with the bind runnable. Do not unlink the death recipient until
        // process death: it is the proof needed before a retained Surface is released.
        main.post {
            if (bindRequested) runCatching { Shizuku.unbindUserService(args, connection, true) }
                .onFailure { AppLog.i("DEX Shizuku unbind: ${it.javaClass.simpleName}", false) }
        }
    }

    private fun status(text: String, placeholder: Boolean = true) {
        DexState.status = text
        AaState.status = text
        if (placeholder) core.showStatus(text)
        AppLog.i("DEX: $text")
    }
}
