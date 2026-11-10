package com.headswap.desk.dex

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.headswap.desk.BuildConfig
import com.headswap.desk.AppLog

/** Main-process broker; other app processes enter through the private input gateway. */
object VdmHostLink {
    private val lock = Object()
    private var activationToken: String? = null
    private var generation = 0L
    private var pending: VdmHostRendezvous? = null
    private var host: IVdmDesktopHost? = null
    private var acceptedNonce: String? = null
    private var session: IVdmDesktopSession? = null
    private var opening = false
    private val appLifetime = Binder()
    @Volatile var lastError: String = ""
        private set

    val isReady: Boolean get() = synchronized(lock) { host?.asBinder()?.isBinderAlive == true }
    val nativeHostLifetime: IBinder? get() = synchronized(lock) { host?.asBinder()?.takeIf { it.isBinderAlive } }
    @JvmStatic fun beginActivation(token: String) {
        require(PhoneActivationPolicy.validToken(token))
        synchronized(lock) {
            check(activationToken == null || VdmHostPolicy.sameSecret(activationToken, token)) { "VDM activation already exists" }
            activationToken = token
        }
    }
    @JvmStatic fun endActivation() {
        IndependentRecoveryAccess.invalidateLiveVerification()
        val previous = synchronized(lock) {
            activationToken = null; pending = null; acceptedNonce = null; generation++; session = null
            host.also { host = null; lock.notifyAll() }
        }
        runCatching { previous?.shutdown() }
    }
    @JvmStatic fun requestLaunch(): VdmHostRendezvous? = synchronized(lock) {
        check(activationToken != null) { "Активируйте доступ HeadSwap" }
        clearDeadHostLocked()
        if (host?.asBinder()?.isBinderAlive == true) return@synchronized null
        val now = SystemClock.elapsedRealtime()
        pending?.takeIf { VdmHostPolicy.launchFresh(it.createdMs, now) } ?: VdmHostRendezvous(
            BuildConfig.APPLICATION_ID, Process.myPid(), VdmHostPolicy.nonce(), ++generation, now,
        ).also { pending = it; lastError = "" }
    }
    @JvmStatic fun cancelLaunch(request: VdmHostRendezvous) = synchronized(lock) {
        clearDeadHostLocked()
        if (pending === request && host == null) { pending = null; generation++; lock.notifyAll() }
    }
    private fun clearDeadHostLocked() {
        if (host != null && host?.asBinder()?.isBinderAlive != true) {
            IndependentRecoveryAccess.invalidateLiveVerification()
            host = null; session = null; currentHostPid = -1
            // Old death recipients compare the captured Binder before modifying a later host.
            lock.notifyAll()
        }
    }
    @JvmStatic fun awaitReady(timeoutMs: Long): Boolean {
        require(timeoutMs in 0..60_000)
        val until = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(lock) {
            while (activationToken != null && host?.asBinder()?.isBinderAlive != true) {
                val left = until - SystemClock.elapsedRealtime()
                if (left <= 0) return false
                lock.wait(left)
            }
            return host?.asBinder()?.isBinderAlive == true
        }
    }
    internal fun prepare(token: String?, appPid: Int): Bundle {
        requireActivation(token, appPid)
        val request = requestLaunch()
        return statusBundle().apply { request?.let { putString(VdmHostPolicy.EXTRA_NONCE, it.nonce) } }
    }
    internal fun status(token: String?, appPid: Int, nonce: String?): Bundle {
        requireActivation(token, appPid)
        synchronized(lock) {
            if (nonce != null) check(VdmHostPolicy.sameSecret(pending?.nonce ?: acceptedNonce, nonce)) { "Expired VDM launch" }
            return statusBundle()
        }
    }
    private fun requireActivation(token: String?, appPid: Int) = synchronized(lock) {
        check(appPid == Process.myPid() && VdmHostPolicy.sameSecret(activationToken, token)) { "No matching live activation" }
    }
    private fun statusBundle(): Bundle = synchronized(lock) {
        Bundle().apply {
            putInt(VdmHostPolicy.EXTRA_PROTOCOL, VdmHostPolicy.PROTOCOL)
            putInt(VdmHostPolicy.EXTRA_APP_PID, Process.myPid())
            putBoolean(VdmHostPolicy.EXTRA_READY, host?.asBinder()?.isBinderAlive == true)
            putString(VdmHostPolicy.EXTRA_ERROR, lastError)
            putString("status", if (host?.asBinder()?.isBinderAlive == true) "ready" else "waiting")
            // hostPid is captured from the authenticated handoff, never a reentrant Binder call.
            putInt(VdmHostPolicy.EXTRA_HOST_PID, currentHostPid)
        }
    }
    private var currentHostPid = -1
    internal fun accept(nonce: String?, appPid: Int, hostPid: Int, binder: IBinder, callingPid: Int): Bundle {
        check(hostPid == callingPid && hostPid > 0 && appPid == Process.myPid()) { "VDM host PID mismatch" }
        val ticket = synchronized(lock) {
            clearDeadHostLocked()
            val request = pending ?: error("No VDM handoff requested")
            check(activationToken != null && host == null &&
                VdmHostPolicy.sameSecret(request.nonce, nonce) &&
                VdmHostPolicy.launchFresh(request.createdMs, SystemClock.elapsedRealtime())) { "Expired VDM handoff" }
            request
        }
        val offered = IVdmDesktopHost.Stub.asInterface(binder)
        check(offered.protocol() == VdmHostPolicy.PROTOCOL && offered.hostPid() == hostPid) { "VDM protocol mismatch" }
        val death = IBinder.DeathRecipient {
            val retained = synchronized(lock) {
                if (host?.asBinder() === binder) {
                    // Retire this host's proof before any successor can obtain a new ticket.
                    IndependentRecoveryAccess.invalidateLiveVerification()
                    host = null; session = null; currentHostPid = -1
                    lastError = "Связь с независимым экраном прервалась"; lock.notifyAll()
                    activationToken != null
                } else null
            }
            if (retained != null) {
                AppLog.i("VDM host lost: generation=${ticket.generation}; activation retained=$retained", false)
            }
        }
        binder.linkToDeath(death, 0)
        synchronized(lock) {
            clearDeadHostLocked()
            if (pending !== ticket || activationToken == null || host != null || !binder.isBinderAlive) {
                binder.unlinkToDeath(death, 0); error("VDM handoff cancelled")
            }
            host = offered; pending = null; acceptedNonce = ticket.nonce; currentHostPid = hostPid
            lastError = ""; lock.notifyAll()
        }
        AppLog.i("VDM host ready: generation=${ticket.generation}; startupMs=${SystemClock.elapsedRealtime() - ticket.createdMs}", false)
        return Bundle().apply { putBoolean(VdmHostPolicy.EXTRA_READY, true); putBinder(VdmHostPolicy.EXTRA_CLIENT, appLifetime) }
    }
    @JvmStatic fun openSession(producerLifetime: IBinder, producerPid: Int): IVdmDesktopSession {
        val owner = synchronized(lock) {
            check(!opening) { "VDM session is opening" }
            checkNotNull(host).also { opening = true }
        }
        try {
            val opened = owner.openSession(producerLifetime, producerPid)
            synchronized(lock) {
                if (host !== owner || activationToken == null) { runCatching { opened.close() }; error("VDM activation ended") }
                session = opened
            }
            return opened
        } finally { synchronized(lock) { opening = false } }
    }
    /** In-process capability for this exact authenticated native family; numeric IDs are not authority. */
    internal fun captureOwnedDisplay(displayId: Int): (() -> Unit)? {
        if (displayId <= 0) return null
        val active = synchronized(lock) { session } ?: return null
        val lifetime = runCatching { active.lifetime }.getOrNull() ?: return null
        val checkCurrent = {
            check(synchronized(lock) { session === active && activationToken != null } && lifetime.isBinderAlive) {
                "Independent display session ended"
            }
            check(active.ownsDisplay(displayId) && active.displayGroup(displayId) > 0) { "Foreign independent display" }
            check(synchronized(lock) { session === active } && lifetime.isBinderAlive) { "Independent display changed" }
        }
        return if (runCatching(checkCurrent).isSuccess) checkCurrent else null
    }

    @JvmStatic @JvmOverloads fun ownsDisplay(displayId: Int, proof: IBinder? = null): Boolean {
        val active = synchronized(lock) { session?.takeIf { proof == null || it.asBinder() == proof } } ?: return false
        return runCatching { active.ownsDisplay(displayId) && active.displayGroup(displayId) > 0 }.getOrDefault(false)
    }
}
