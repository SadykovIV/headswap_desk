package com.headswap.desk.dex

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.flyfishxu.kadb.Kadb
import com.headswap.desk.BridgeService
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Shared idempotent decision point. UI and notifications are observers, never grant owners. */
internal object DesktopActivationCoordinator {
    private val lock = ReentrantLock()
    class PairingRequired : Exception()
    enum class Stage { CHECKING, PREPARING_RECOVERY, RECOVERING_DESKTOP, DISPATCHED }
    enum class Result { READY, DISPATCHED }

    fun facts(context: Context): DesktopReadinessPolicy.Facts = DesktopReadinessPolicy.Facts(
        DelegatedDesktopAccess.isReady(context), DesktopAudioAccess.hasPermission(context),
        DelegatedPanelAccess.hasPermission(context), DelegatedPanelAccess.unsupportedThisActivation,
        Build.VERSION.SDK_INT >= 37 && DexPrefs(context).desktopMode != DesktopModePolicy.SAMSUNG_DEX,
        VdmHostLink.isReady, IndependentRecoveryAccess.needsApply(context),
        IndependentRecoveryAccess.requiresLiveVerification(context), DesktopUsbDebugging.confirmedMissing(context))

    fun request(context: Context, needs: DesktopReadinessPolicy.Requirements,
                explicit: Boolean, allowAcquireGrants: Boolean = true, current: () -> Boolean): Request =
        Request(context.applicationContext, needs, explicit, allowAcquireGrants, current)

    fun currentStep(context: Context, explicit: Boolean = true): DesktopReadinessPolicy.Step {
        val prefs = DexPrefs(context)
        return DesktopReadinessPolicy.next(facts(context), DesktopReadinessPolicy.Requirements(
            prefs.usbPlaybackAudio, prefs.desktopPanelControl), explicit)
    }
    fun isReady(context: Context) = currentStep(context) == DesktopReadinessPolicy.Step.READY

    class Request internal constructor(private val context: Context,
        private val needs: DesktopReadinessPolicy.Requirements, private val explicit: Boolean,
        private val allowAcquireGrants: Boolean, private val current: () -> Boolean) : AutoCloseable {
        private val closed = AtomicBoolean()
        @Volatile private var recovery: VdmHostRecovery.Request? = null
        @Volatile private var bootstrapConnection: Kadb? = null
        private val endpointRequest = PhoneAdbEndpointRequest(context, ::checkActive)
        private var recoveryVerified = false

        fun next(): DesktopReadinessPolicy.Step = DesktopReadinessPolicy.next(facts(context), needs, explicit, allowAcquireGrants)
        fun checkActive() {
            if (closed.get() || Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
        }

        /** Endpoint discovery is supplied by the one service that owns the pairing prompt. */
        fun ensureReady(discovered: () -> DexEndpoint?, startDiscovery: () -> Unit,
                        stage: (Stage) -> Unit, committing: () -> Unit): Result {
            check(Looper.myLooper() != Looper.getMainLooper())
            var locked = false
            try {
                while (!locked) { checkActive(); locked = lock.tryLock(100, TimeUnit.MILLISECONDS) }
                repeat(5) {
                    checkActive()
                    val step = next()
                    if (explicit && !recoveryVerified && IndependentRecoveryAccess.requested(context) &&
                        step in setOf(DesktopReadinessPolicy.Step.READY, DesktopReadinessPolicy.Step.RECOVER_HOST)) {
                        stage(Stage.CHECKING)
                        recoveryVerified = IndependentRecoveryAccess.verifyReady(context, ::checkActive) { adb ->
                            bootstrapConnection = adb
                            try { checkActive() } catch (error: Exception) { runCatching { adb?.close() }; throw error }
                        }
                        if (!recoveryVerified) return@repeat
                    }
                    when (step) {
                        DesktopReadinessPolicy.Step.VERIFY_RECOVERY -> {
                            stage(Stage.CHECKING)
                            recoveryVerified = IndependentRecoveryAccess.verifyReady(context, ::checkActive) { adb ->
                                bootstrapConnection = adb
                                try { checkActive() } catch (error: Exception) { runCatching { adb?.close() }; throw error }
                            }
                        }
                        DesktopReadinessPolicy.Step.READY -> return Result.READY
                        DesktopReadinessPolicy.Step.USB_DEBUGGING_REQUIRED -> throw DexAccessException(
                            DexAccessStage.PROBE_SHELL, DexAccessReason.USB_DEBUGGING_DISABLED)
                        DesktopReadinessPolicy.Step.USER_ACTIVATION_REQUIRED -> throw DesktopPreparationException("Активируйте HeadSwap на телефоне.")
                        DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED -> throw DesktopPreparationException(
                            "Изменены разрешения для звука или дисплея. Завершите текущий доступ HeadSwap и активируйте приложение снова.")
                        DesktopReadinessPolicy.Step.RECOVER_HOST -> {
                            if (!DexPrefs(context).hasPaired) throw PairingRequired()
                            stage(Stage.RECOVERING_DESKTOP)
                            VdmHostRecovery.create(context) { !closed.get() && current() }.use { repair ->
                                recovery = repair
                                if (!repair.ensureReady()) {
                                    repair.failureReason?.let { throw DexAccessException(DexAccessStage.CONNECT, it) }
                                    throw DesktopPreparationException(repair.failure ?: "Не удалось подготовить рабочий стол. Включите Wi-Fi и повторите активацию.")
                                }
                            }
                            recovery = null
                        }
                        DesktopReadinessPolicy.Step.APPLY_MODE -> {
                            requireIdleForModePreparation()
                            if (!DexPrefs(context).hasPaired) throw PairingRequired()
                            stage(Stage.PREPARING_RECOVERY)
                            val endpoint = endpointRequest.resolve(discovered, startDiscovery) { next() == DesktopReadinessPolicy.Step.READY }
                                ?: return@repeat
                            checkActive()
                            // This stage is entered only from an explicit activation and the user's saved opt-in.
                            endpointRequest.withConnection(endpoint) { adb ->
                                requireIdleForModePreparation()
                                IndependentRecoveryAccess.prepare(context, adb, endpoint, ::checkActive)
                            }
                            recoveryVerified = true
                        }
                        DesktopReadinessPolicy.Step.ACQUIRE_GRANTS -> {
                            requireGrantAuthorization()
                            if (!DexPrefs(context).hasPaired) throw PairingRequired()
                            stage(Stage.CHECKING)
                            val endpoint = endpointRequest.resolve(discovered, startDiscovery) {
                                next() != DesktopReadinessPolicy.Step.ACQUIRE_GRANTS
                            } ?: return@repeat
                            checkActive()
                            if (next() != DesktopReadinessPolicy.Step.ACQUIRE_GRANTS) return@repeat
                            check(DexPrefs(context).persistActivationSelection(needs.audio, needs.panel)) {
                                "Настройки активации изменились. Повторите запуск."
                            }
                            requireGrantAuthorization()
                            runBlocking {
                                DexAccess.activatePhone(context, endpoint, needs.audio, needs.panel,
                                    onCommitting = { checkActive(); requireGrantAuthorization(); committing(); stage(Stage.DISPATCHED) },
                                    onConnection = { adb ->
                                        bootstrapConnection = adb
                                        try { checkActive() } catch (error: Exception) {
                                            runCatching { adb?.close() }; throw error
                                        }
                                    },
                                    checkRequest = ::checkActive)
                            }
                            return Result.DISPATCHED
                        }
                    }
                }
                error("Настройки изменились во время подготовки. Повторите активацию.")
            } finally { recovery = null; if (locked) lock.unlock() }
        }
        private fun requireIdleForModePreparation() {
            // A queued Bridge start rejected by the activation gate still needs onDestroy.
            // Wait briefly for that lifecycle cleanup; never stop a user's working session here.
            val deadline = SystemClock.elapsedRealtime() + 2500
            while (true) {
                checkActive()
                if (!BridgeService.alive && !DexState.running) return
                if (SystemClock.elapsedRealtime() >= deadline) throw DesktopPreparationException(
                    "Остановите подключение к CarLife, затем повторите активацию.", canPair = false)
                Thread.sleep(50)
            }
        }
        private fun requireGrantAuthorization() {
            if (!allowAcquireGrants) throw DesktopPreparationException(
                "Доступ завершён. Нажмите «Активировать», чтобы выдать его снова.")
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            endpointRequest.close()
            recovery?.close()
            runCatching { bootstrapConnection?.close() }
        }
    }
}
