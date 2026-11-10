package com.headswap.desk.dex

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import com.headswap.desk.AppLog
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import java.util.UUID

/** Owns the privileged producer; CarLife owns its consumer Surface and single H.264 encoder. */
class DelegatedDexConnector(context: Context, private val core: AaBridgeCore,
    private val onFailed: (Failure) -> Unit = {}) :
    AaLifecycle, AaPeer, DesktopSurfaceListener, DesktopPowerControl, DesktopNavigation, TerminalSource, com.headswap.desk.aa.DesktopLockIsolation {
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
    private val inputGateway = DelegatedInputGateway(this.context, ::watchNativeProducer)
    private val bindAction = DelegatedDesktopService.BIND_ACTION_PREFIX + UUID.randomUUID()
    // Binder transactions are not cancelled by Future.cancel(). Keep start/stop ordered
    // even when the caller's deadline expires and the remote start finishes late.
    private val binderCalls = Executors.newSingleThreadExecutor { r -> Thread(r, "dex-binder").apply { isDaemon = true } }
    private val inputDiagnostics = DesktopInputDiagnostics("delegated")
    private val input = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue(128),
        { r -> Thread(r, "dex-mouse").apply { isDaemon = true } })
    private val inputMoves = com.headswap.desk.shared.MotionDispatchBuffer(input)
    private val panel = Executors.newSingleThreadExecutor { r -> Thread(r, "desktop-panel").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dex-watch").apply { isDaemon = true } }
    @Volatile private var service: IDexUserService? = null
    @Volatile private var serviceBinder: IBinder? = null
    private var bindRequested = false // Read/written only on the main looper.
    @Volatile private var generation = 0L
    @Volatile private var retiringGeneration = 0L
    @Volatile private var nativeProducer: IBinder? = null
    private val nativeLock = Any()
    private var nativeDeath: IBinder.DeathRecipient? = null
    @Volatile private var vdmRecovery: VdmHostRecovery.Request? = null
    override val requiresIsolatedPhoneLock: Boolean get() =
        DesktopDisplayBackendPolicy.requiresVdm(android.os.Build.VERSION.SDK_INT, desktopMode)
    @Volatile override var hadIsolatedDesktop: Boolean = false
        private set
    @Volatile private var displayId = -1
    @Volatile private var desktopMode = DesktopModePolicy.ANDROID_DESKTOP
    @Volatile private var firstFrame = false
    @Volatile private var established = false
    @Volatile private var failure: String? = null
    @Volatile private var generationStartedAt = 0L
    private val death = IBinder.DeathRecipient {
        if (nativeHasStopped()) EmbeddedSessionRegistry.producerStopped(this)
        serviceDied.countDown()
        val retired = retiringGeneration
        if (retired != 0L && nativeHasStopped()) core.desktopSurfaceProducerClosed(retired)
        fail(helperUnavailableMessage(), Failure.HELPER_UNAVAILABLE)
    }
    private fun nativeHasStopped(): Boolean = nativeProducer?.let { !it.isBinderAlive }
        ?: (serviceBinder?.isBinderAlive != true)

    private fun watchNativeProducer(binder: IBinder) {
        synchronized(nativeLock) {
            nativeProducer?.let { old -> nativeDeath?.let { runCatching { old.unlinkToDeath(it, 0) } } }
            nativeProducer = binder
            val callback = IBinder.DeathRecipient {
                if (nativeProducer === binder) {
                    EmbeddedSessionRegistry.producerStopped(this)
                    retiringGeneration.takeIf { it != 0L }?.let(core::desktopSurfaceProducerClosed)
                    fail("Связь с независимым рабочим столом прервалась", Failure.HELPER_UNAVAILABLE)
                }
            }
            nativeDeath = callback
            try { binder.linkToDeath(callback, 0) }
            catch (_: android.os.RemoteException) {
                Thread({ callback.binderDied() }, "desktop-native-death").apply { isDaemon = true; start() }
            }
        }
    }

    private fun forgetNativeProducer() = synchronized(nativeLock) {
        nativeProducer?.let { old -> nativeDeath?.let { runCatching { old.unlinkToDeath(it, 0) } } }
        nativeProducer = null
        nativeDeath = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                serviceBinder = binder
                binder.linkToDeath(death, 0)
                service = IDexUserService.Stub.asInterface(binder)
                // A connection queued before a stop can arrive after the session ended.
                if (stopping.get()) unbind()
            } catch (e: Exception) { fail("Не удалось открыть службу рабочего стола: ${e.message}") }
            finally { connected.countDown() }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            // A disconnect callback is not proof that the producer process died.
            fail(helperUnavailableMessage(), Failure.HELPER_UNAVAILABLE)
        }
        override fun onBindingDied(name: ComponentName) {
            connected.countDown()
            fail(helperUnavailableMessage(), Failure.HELPER_UNAVAILABLE)
        }
        override fun onNullBinding(name: ComponentName) { connected.countDown(); fail("Служба рабочего стола недоступна. Активируйте доступ с компьютера") }
    }

    override fun start() {
        synchronized(stateLock) {
            if (!started.compareAndSet(false, true) || stopping.get()) return
            try {
                inputGateway.preflight()
                DexState.error = null
                DexState.running = false
                DexState.displayId = -1
                resetPhonePowerState()
                core.onAaConnecting()
                core.peer = this
                core.enableDesktopSurface(this)
                status("Запускаю рабочий стол…")
            } catch (e: Exception) { fail(e.message ?: "Не удалось подготовить рабочий стол"); return }
        }
        main.post {
            if (!stopping.get()) try {
                check(DelegatedDesktopAccess.isReady(context)) { "Активируйте доступ с компьютера" }
                val intent = Intent(context, DelegatedDesktopService::class.java).setAction(bindAction)
                    .putExtras(Bundle().apply { putBinder(DelegatedInputGateway.EXTRA_BINDER, inputGateway) })
                bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bindRequested) { "Android не запустил службу рабочего стола" }
            } catch (e: Exception) { connected.countDown(); fail(e.message ?: "Доступ с компьютера недоступен") }
        }
        try { watchdog.scheduleWithFixedDelay({
            if (!stopping.get()) try {
                check(DelegatedDesktopAccess.isReady(context)) { "Доступ завершён. Подключите телефон к компьютеру для повторной активации" }
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
            check(connected.await(12, TimeUnit.SECONDS)) { "Служба рабочего стола не ответила за 12 секунд" }
            check(!stopping.get()) { "Подключение остановлено" }
            val remote = checkNotNull(service) { "Служба рабочего стола недоступна" }
            val prefs = DexPrefs(context)
            val compactMapSymbols = prefs.compactMapSymbols
            val correctionPackages = prefs.dpiCorrectionPackages
            val density = input.density
            desktopMode = prefs.desktopMode
            check(desktopMode != DesktopModePolicy.SAMSUNG_DEX) { "Для доступа с компьютера выберите Android или DriveDeck" }
            check(DelegatedDesktopAccess.isReady(context)) { "Активируйте доступ с компьютера" }
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
            if (requiresIsolatedPhoneLock) {
                val repair = VdmHostRecovery.create(context) { !stopping.get() && this.generation == generation }
                vdmRecovery = repair
                if (!repair.ensureReady(18_000)) throw VdmRecoveryException(
                    repair.failure ?: "Служба независимого экрана недоступна. Повторите подключение к CarLife.",
                    repair.failureReason)
            }
            val lifetime = Binder()
            synchronized(stateLock) {
                check(!stopping.get()) { "Подключение остановлено" }
                inputGateway.beginGeneration(generation, lifetime, allowFullscreen = EmbeddedDisplayPolicy.supportsChildren(desktopMode))
            }
            val id = bounded(25) {
                check(remote.protocolVersion() == DexUserServicePolicy.PROTOCOL_VERSION) { "Версия службы рабочего стола не совпадает с приложением. Завершите доступ и активируйте HeadSwap снова." }
                remote.startDesktopSized(input.surface, lifetime, input.width, input.height, density, desktopMode)
            }
            check(id > 0) { "Не удалось создать отдельный экран" }
            displayId = id
            hadIsolatedDesktop = hadIsolatedDesktop || context.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(id)?.let(DesktopDisplayIsolation::confirmedIndependence) == true
            if (stopping.get() || !core.desktopSurfaceReady(generation)) {
                onSurfaceClosing(generation)
                return
            }
            synchronized(stateLock) {
                check(!stopping.get() && retiringGeneration != generation) { "Сеанс завершён" }
                DexState.displayId = id
                if (desktopMode != DesktopModePolicy.SAMSUNG_DEX) {
                    EmbeddedSessionRegistry.activate(this, remote, id, this.input, desktopMode, density, compactMapSymbols, correctionPackages,
                        nativeLifetime = nativeProducer ?: checkNotNull(serviceBinder))
                }
            }
            status("Рабочий стол ${input.width}×${input.height} готов, ожидаю видео…")
            AppLog.i("DESKTOP mode=$desktopMode delegated display=$id generation=$generation native=${input.width}x${input.height} density=$density preview=false singleEncode=true", false)
        } catch (e: Exception) {
            if (!stopping.get()) {
                AppLog.e("DESKTOP delegated: screen creation failed", e)
                val reason = e.cause?.message ?: e.message ?: "Ошибка создания экрана"
                core.desktopSurfaceFailed(generation, reason)
                fail(reason, failureKind(e))
            }
        }
    }

    override fun onSurfaceClosing(generation: Long) {
        if (this.generation != generation) return
        vdmRecovery?.close()
        vdmRecovery = null
        // Invalidate queued input before retiring the corresponding display.
        synchronized(stateLock) {
            EmbeddedSessionRegistry.deactivate(this)
            inputGateway.deactivate()
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
        var nativeClosed = nativeHasStopped()
        if (remote != null && binder?.isBinderAlive == true && serviceDied.count != 0L) {
            try { bounded(8) { remote.stop() }; nativeClosed = true }
            catch (e: Exception) {
                AppLog.i("DESKTOP delegated: stop failed (${e.javaClass.simpleName}); closing producer", false)
                // Ordinary unbind is NOT a producer-death acknowledgement. Try the
                // explicit close transaction before trusting actual Binder death.
                val closed = runCatching { bounded(8) { remote.destroy() }; true }.getOrDefault(false)
                val confirmedDead = if (closed) false else try {
                    serviceDied.await(6, TimeUnit.SECONDS); nativeHasStopped()
                } catch (_: InterruptedException) { Thread.currentThread().interrupt(); nativeHasStopped() }
                nativeClosed = closed || confirmedDead
                if (!nativeClosed) {
                    val message = "Служба не подтвердила закрытие экрана. Завершите HeadSwap перед повторным подключением"
                    failure = message
                    DexState.error = message
                    fail(message)
                    // Keep this generation's consumer until the actual producer death
                    // recipient acknowledges desktopSurfaceProducerClosed().
                    throw IOException(message, e)
                }
            }
        }
        check(nativeClosed || nativeHasStopped()) { "Производитель экрана ещё не подтвердил завершение" }
        EmbeddedSessionRegistry.producerStopped(this)
        this.generation = 0
        retiringGeneration = 0
        forgetNativeProducer()
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
            enqueue(if (action == MotionEvent.ACTION_MOVE) listOf(generation, desktopMode, width, height, ids.toList()) else null) {
                it.injectTouchPoints(action, actionIndex, ids, xs, ys)
            }
        } else {
            // Samsung window captions and resize edges require mouse input.
            val p = points.first()
            if (action !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL)) return
            val buttons = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0 else MotionEvent.BUTTON_PRIMARY
            enqueue(if (action == MotionEvent.ACTION_MOVE) listOf(generation, desktopMode, width, height, buttons) else null) {
                it.injectPointer(action, p.x.coerceIn(0, width - 1), p.y.coerceIn(0, height - 1), buttons)
            }
        }
    }

    override fun sendRotary(delta: Int) {
        dispatchHardware { requestedDisplay ->
            if (delta != 0) com.headswap.desk.desktop.DesktopHardwareUi.interruptBack(requestedDisplay)
            if (com.headswap.desk.desktop.DesktopHardwareUi.rotate(requestedDisplay, delta)) {
                enqueue { it.resetHardwareInput() }
            } else DesktopRotaryInput.keys(delta).forEach(::injectHardwareKey)
        }
    }

    override fun sendKey(keycode: Int) {
        dispatchHardware { requestedDisplay ->
            if (keycode != android.view.KeyEvent.KEYCODE_BACK)
                com.headswap.desk.desktop.DesktopHardwareUi.interruptBack(requestedDisplay)
            val backTarget = if (keycode == android.view.KeyEvent.KEYCODE_BACK)
                com.headswap.desk.desktop.DesktopHardwareUi.captureBack(requestedDisplay) else null
            when {
                backTarget != null -> {
                    val epoch = generation
                    enqueue { remote ->
                        if (remote.routeHostBack(requestedDisplay, backTarget.modal)) main.post {
                            if (!stopping.get() && firstFrame && displayId == requestedDisplay &&
                                generation == epoch && retiringGeneration != epoch) backTarget.dispatch()
                        }
                    }
                }
                com.headswap.desk.desktop.DesktopHardwareUi.key(requestedDisplay, keycode) ->
                    enqueue { it.resetHardwareInput() }
                keycode == android.view.KeyEvent.KEYCODE_SEARCH || keycode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST -> {
                    enqueue { it.resetHardwareInput() }
                    val assistantDisplay = DesktopAssistant.launch(context, requestedDisplay,
                        DelegatedInputGateway.ownedDisplayIds() + requestedDisplay) {
                        !stopping.get() && firstFrame && displayId == requestedDisplay && requestedDisplay > 0
                    }
                    if (assistantDisplay != null) enqueue { it.focusHardwareDisplay(assistantDisplay) }
                }
                // NAVI/MUSIC are launcher commands, not injectable Android keys. A foreign
                // component keeps ownership of its UI; never let MUSIC launch on the phone.
                keycode == 65538 || keycode == android.view.KeyEvent.KEYCODE_MUSIC -> {
                    enqueue { it.resetHardwareInput() }
                    AppLog.i("DESKTOP hardware category unavailable in selected profile", false)
                }
                else -> injectHardwareKey(keycode)
            }
        }
    }

    private fun injectHardwareKey(keycode: Int) {
        if (keycode !in 1..1000) return
        enqueue { it.injectKey(0, keycode); it.injectKey(1, keycode) }
    }

    /** Recheck the exact session after crossing threads; an old HU event cannot reach a new display. */
    private fun dispatchHardware(action: (Int) -> Unit) {
        val requestedDisplay = displayId
        val epoch = generation
        main.post {
            if (!stopping.get() && firstFrame && requestedDisplay > 0 && displayId == requestedDisplay &&
                epoch != 0L && generation == epoch && retiringGeneration != epoch) action(requestedDisplay)
        }
    }

    override fun requestKeyFrame() { /* CarLife requests its own encoder's IDR directly. */ }

    override fun showDesktop(): Boolean {
        if (stopping.get() || generation == 0L || retiringGeneration == generation || !firstFrame) return false
        enqueue { it.showDesktop() }
        return true
    }

    /** The permission and native preflight are an explicit --screen activation, never inferred. */
    override fun setPhoneScreenOff(off: Boolean): Boolean {
        val token = synchronized(stateLock) {
            if (stopping.get() || generation == 0L || retiringGeneration == generation || !firstFrame ||
                DexState.phoneScreenBusy || off && !DelegatedPanelAccess.isAvailable(context)) return false
            DexState.phoneScreenBusy = true
            DexState.phoneScreenError = null
            generation
        }
        try { panel.execute {
            try {
                if (stopping.get() || generation != token || retiringGeneration == token) return@execute
                // The physical panel is owned here. Waiting for the phone's wake Activity must
                // neither block input nor time out the virtual display producer's Binder thread.
                inputGateway.setLocalPanelOff(token, off)
                // The main-process panel owner updates state; a late ACK must not undo watchdog restoration.
            } catch (error: Exception) {
                if (generation == token) DexState.phoneScreenError =
                    "Не удалось изменить питание панели: ${error.cause?.message ?: error.message}"
                AppLog.e("DESKTOP PANEL: request failed", error)
            } finally { if (generation == token) DexState.phoneScreenBusy = false }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (generation == token) DexState.phoneScreenBusy = false
            return false
        }
        return true
    }

    private fun resetPhonePowerState() {
        DexState.phoneScreenOff = false
        DexState.phoneScreenBusy = false
        DexState.phoneScreenError = null
    }

    private fun enqueue(moveKey: Any? = null, block: (IDexUserService) -> Unit) {
        val token = generation
        if (stopping.get() || token == 0L || retiringGeneration == token || !firstFrame) return
        val arrivedNs = android.os.SystemClock.elapsedRealtimeNanos()
        val observedDepth = input.queue.size + 1
        try { inputMoves.submit(moveKey) {
            if (!stopping.get() && generation == token && retiringGeneration != token && firstFrame) {
                val startedNs = android.os.SystemClock.elapsedRealtimeNanos()
                var succeeded = false
                try {
                    try {
                        service?.let { remote -> block(remote); succeeded = true }
                    } finally {
                        // Record the remote call before any failure-triggered teardown work.
                        inputDiagnostics.completed(token, arrivedNs, startedNs,
                            android.os.SystemClock.elapsedRealtimeNanos(), observedDepth, succeeded)
                            ?.let { AppLog.i(it + " coalescedMoves=${inputMoves.coalescedCount()}", false) }
                    }
                } catch (e: Exception) {
                    // An already dequeued input can overlap normal USB-detach cleanup.
                    // Its retired display is gone; this must not end a reconnectable session.
                    if (!stopping.get() && generation == token && retiringGeneration != token && firstFrame) {
                        fail("Не удалось передать управление рабочему столу: ${e.message}", failureKind(e))
                    }
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
        if (DelegatedDesktopAccess.isReady(context)) "Служба рабочего стола завершилась; права HeadSwap сохранены"
        else "Доступ завершён. Откройте «Активация» в настройках HeadSwap"

    private fun failureKind(error: Throwable): Failure =
        if (error is DeadObjectException || error.cause is DeadObjectException ||
            error is VdmRecoveryException && error.retryable) Failure.HELPER_UNAVAILABLE else Failure.OTHER

    private fun fail(message: String, reason: Failure = Failure.OTHER) {
        if (stopping.get() || !failureSignalled.compareAndSet(false, true)) return
        failure = message
        DexState.error = message
        AppLog.i("DESKTOP delegated: $message")
        // The host must retire this one-shot connector even if the USB transport stays alive.
        runCatching { onFailed(reason) }.onFailure { AppLog.i("DEX failure callback: ${it.javaClass.simpleName}", false) }
        stop {}
    }

    override fun stop(onDone: () -> Unit) {
        vdmRecovery?.close()
        val firstStop = synchronized(stateLock) {
            stopping.compareAndSet(false, true).also { if (it) {
                EmbeddedSessionRegistry.deactivate(this)
                inputGateway.deactivate()
                resetPhonePowerState()
            } }
        }
        if (firstStop) {
            connected.countDown()
            watchdog.shutdownNow()
            panel.shutdownNow()
            input.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
            // Release the producer first. Unlike a Shizuku user service, ordinary
            // unbind does not kill its process and cannot prove Surface safety.
            core.stopDesktopSurface {
                unbind()
                binderCalls.shutdownNow()
                if (core.peer === this) core.peer = null
                core.onAaEnded(established)
                DexState.running = false
                DexState.displayId = -1
                DexState.error = failure
                status(failure ?: "Рабочий стол остановлен")
                finished.countDown()
            }
        }
        Thread({
            try { finished.await(); onDone() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }, "dex-stop").apply { isDaemon = true; start() }
    }

    private fun unbind() {
        main.post {
            // Reached after consumer disposal, or a late first bind that never started
            // a producer. The helper process may stay cached: release its death hook
            // so completed USB sessions are not retained until that process exits.
            serviceBinder?.let { binder -> runCatching { binder.unlinkToDeath(death, 0) } }
            service = null
            serviceBinder = null
            if (bindRequested) {
                bindRequested = false
                runCatching { context.unbindService(connection) }
                    .onFailure { AppLog.i("DESKTOP delegated unbind: ${it.javaClass.simpleName}", false) }
            }
        }
    }

    private fun status(text: String, placeholder: Boolean = true) {
        DexState.status = text
        AaState.status = text
        if (placeholder) core.showStatus(text)
        AppLog.i("DEX: $text")
    }
}
