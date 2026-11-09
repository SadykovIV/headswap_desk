package com.headswap.desk.dex

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.headswap.desk.AppLog
import com.headswap.desk.aa.BridgeHost

/** Main-process memory survives USB service grace. No track names, URIs or PCM are logged/saved. */
internal object DesktopMusicPlayback {
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private val link = DesktopMusicUsbLink(BridgeHost.GRACE_MS)
    private var ready: (() -> Boolean)? = null
    private var audioReady: (() -> Boolean)? = null
    private var autoplayAttempted = false
    private var watched: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var mediaManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var selectedListener: MediaSessionManager.OnMediaKeyEventSessionChangedListener? = null
    private val policy = DesktopMusicResumePolicy { reason ->
        AppLog.i("DESKTOP MUSIC: resume cancelled ($reason)", false)
    }
    private val tick = object : Runnable {
        override fun run() {
            val app = context ?: return
            if (!allowed(app)) { clear("access ended"); return }
            val systemReady = receivingVideo()
            if (systemReady) link.markReady()
            val outputReady = systemReady && runCatching { audioReady?.invoke() == true }.getOrDefault(false)
            val pending = policy.hasPending
            if (pending) checkPending(outputReady)
            if (outputReady && !autoplayAttempted) {
                autoplayAttempted = true
                if (!pending && DexPrefs(app).autoMusicPlayback) startSelectedPlayer(app)
            }
            scheduleNext()
        }
    }

    fun attach(context: Context, serviceOwner: Any, owner: Any, isUsb: Boolean,
        ready: () -> Boolean, audioReady: () -> Boolean) = onMain {
        if (!isUsb || !allowed(context)) { clear("unsupported link"); return@onMain }
        this.context = context.applicationContext
        link.attach(serviceOwner, owner, SystemClock.elapsedRealtime())
        this.ready = ready
        this.audioReady = audioReady
        autoplayAttempted = false
        main.removeCallbacks(tick)
        tick.run()
    }

    /** User opt-in resumes only Android's selected local media session, never an arbitrary app. */
    private fun startSelectedPlayer(app: Context) {
        runCatching {
            val manager = app.getSystemService(MediaSessionManager::class.java)
            val selected = manager.mediaKeyEventSession ?: return
            val players = manager.getActiveSessions(null)
            if (players.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }) return
            val target = players.firstOrNull { it.sessionToken == selected } ?: return
            val state = target.playbackState ?: return
            if (state.state != PlaybackState.STATE_PAUSED || state.actions and PlaybackState.ACTION_PLAY == 0L) return
            if (target.playbackInfo?.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL ||
                target.playbackInfo?.audioAttributes?.usage != AudioAttributes.USAGE_MEDIA) return
            target.transportControls.play()
            AppLog.i("DESKTOP MUSIC: automatic playback of selected paused media session", false)
        }
    }

    /** An old session cannot retire a replacement session. Physical detach may be reported later. */
    fun linkClosed(owner: Any) = onMain {
        if (!link.close(owner, SystemClock.elapsedRealtime())) return@onMain
        ready = null
        audioReady = null
        scheduleNext()
    }

    fun usbOutputLost(serviceOwner: Any, owner: Any, hadPicture: Boolean) = onMain {
        val app = context ?: return@onMain
        if (link.consumeTransportLoss(serviceOwner, owner, hadPicture)) pauseCurrentPlayer(app, "USB transport loss")
    }

    fun physicalDetach(serviceOwner: Any) = onMain {
        val app = context ?: return@onMain
        // A failed retry may replace the working session before USB reports physical detach.
        if (link.serviceOwner !== serviceOwner) return@onMain
        if (receivingVideo()) link.markReady() // The first frame may arrive between readiness polls.
        if (link.consumePhysicalDetach(serviceOwner, SystemClock.elapsedRealtime())) pauseCurrentPlayer(app, "physical USB detach")
    }

    private fun pauseCurrentPlayer(app: Context, cause: String, retryToken: MediaSession.Token? = null) {
        if (policy.hasPending || !allowed(app)) return
        var selected: MediaSession.Token? = null
        var commandAttempted = false
        try {
            val manager = app.getSystemService(MediaSessionManager::class.java)
            selected = manager.mediaKeyEventSession ?: return
            if (retryToken != null && selected != retryToken) return
            val playing = manager.getActiveSessions(null).filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val target = playing.singleOrNull()?.takeIf { it.sessionToken == selected } ?: return
            val info = target.playbackInfo
            if (info.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL ||
                info.audioAttributes.usage != AudioAttributes.USAGE_MEDIA) return
            val before = snapshot(target) ?: return
            releaseObserver()
            if (!policy.pause(before, SystemClock.elapsedRealtime())) return
            if (policy.hasPending) {
                // Observers protect a possible later PLAY. Their setup is optional for PAUSE.
                try { watchForResume(app, manager, target) }
                catch (_: Exception) { cancelPending("unable to observe this player") }
            }
            commandAttempted = true // a Binder failure does not prove the player missed PAUSE
            target.transportControls.pause()
            AppLog.i("DESKTOP MUSIC: paused current session on $cause", false)
            if (policy.hasPending) tick.run()
        } catch (error: Exception) {
            cancelPending("pause unavailable")
            val token = selected
            if (!commandAttempted && error !is SecurityException && token != null && retryToken == null) {
                val loss = link.lossId ?: return
                // Retry a transient query once, only for the same selected player and loss.
                main.postDelayed({
                    if (link.lossId === loss) pauseCurrentPlayer(app, cause, retryToken = token)
                }, 100L)
            }
        }
    }

    private fun watchForResume(app: Context, manager: MediaSessionManager, target: MediaController) {
        watched = target
        val listener = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (watched === target && policy.hasPending)
                    checkPending(false, playbackCallback = true, event = state)
            }
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (watched === target && policy.hasPending) checkPending(false)
            }
            override fun onSessionDestroyed() {
                if (watched === target) cancelPending("session destroyed")
            }
        }
        callback = listener
        target.registerCallback(listener, main)
        mediaManager = manager
        sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
            if (watched === target && policy.hasPending) checkPending(false)
        }.also { manager.addOnActiveSessionsChangedListener(it, null, main) }
        selectedListener = MediaSessionManager.OnMediaKeyEventSessionChangedListener { _, token ->
            if (watched === target && policy.hasPending && !runCatching {
                policy.acceptsSelectionEvent(token) { manager.mediaKeyEventSession }
            }.getOrDefault(false)) cancelPending("selected player changed")
        }.also { manager.addOnMediaKeyEventSessionChangedListener(app.mainExecutor, it) }
    }

    fun serviceDestroyed(serviceOwner: Any, physicallyDetached: Boolean) = onMain {
        if (link.serviceOwner !== serviceOwner) return@onMain
        link.owner?.let(::linkClosed)
        // A failed reconnect may end its own service before new frames arrive.
        if (policy.hasPending) {
            link.clear()
            AppLog.i("DESKTOP MUSIC: retaining paused session after USB service ended", false)
            scheduleNext()
        } else if (physicallyDetached) link.clear() else clear("USB service ended")
    }

    /** Also called when Stop is pressed after the foreground service has already left its grace. */
    fun cancel(reason: String) = onMain { clear(reason) }

    private fun checkPending(systemReady: Boolean, playbackCallback: Boolean = false, event: PlaybackState? = null) {
        val app = context ?: return
        if (!DexPrefs(app).autoMusicPlayback) { cancelPending("automatic playback disabled"); return }
        val target = watched ?: return
        try {
            if (app.checkSelfPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL) != PackageManager.PERMISSION_GRANTED) {
                cancelPending("media control permission lost"); return
            }
            val manager = app.getSystemService(MediaSessionManager::class.java)
            val selected = manager.mediaKeyEventSession
            val active = manager.getActiveSessions(null)
            val current = active.firstOrNull { it.sessionToken == target.sessionToken }
            val otherPlaying = active.any { it.sessionToken != target.sessionToken && it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val value = current?.let { snapshot(it, if (playbackCallback) event else it.playbackState) }
            val hadAcknowledgement = policy.acknowledged
            val resume = policy.takeResume(systemReady, value, selected, otherPlaying, SystemClock.elapsedRealtime())
            if (!hadAcknowledgement && policy.acknowledged)
                AppLog.i("DESKTOP MUSIC: player acknowledged PAUSE; waiting for USB desktop and audio", false)
            if (resume == target.sessionToken) {
                releaseObserver()
                runCatching { target.transportControls.play() }
                    .onSuccess { AppLog.i("DESKTOP MUSIC: sent PLAY to the paused session after USB desktop became ready", false) }
                    .onFailure { AppLog.i("DESKTOP MUSIC: player rejected PLAY (${it.javaClass.simpleName})", false) }
            } else if (!policy.hasPending) releaseObserver()
            scheduleNext()
        } catch (_: Exception) { cancelPending("media session changed or access lost") }
    }

    private fun scheduleNext() {
        main.removeCallbacks(tick)
        val delay = if (link.owner != null && !link.outputLost && (!link.sawReady || policy.hasPending || !autoplayAttempted)) 250L
            else policy.nextDeadlineDelay(SystemClock.elapsedRealtime())
        if (delay != null) main.postDelayed(tick, delay)
    }

    private fun receivingVideo(): Boolean = !link.outputLost && link.owner != null &&
        runCatching { ready?.invoke() == true }.getOrDefault(false)

    private fun snapshot(controller: MediaController, state: PlaybackState? = controller.playbackState): DesktopMusicResumePolicy.Playback? {
        state ?: return null
        val item = try {
            val metadata = controller.metadata
            metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotEmpty() }
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)?.takeIf { it.isNotEmpty() }
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotEmpty() }?.let {
                    listOf(it, metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).toString()).joinToString("\u0000")
                } ?: state.activeQueueItemId.takeIf { it != MediaSession.QueueItem.UNKNOWN_ID.toLong() }?.let { "queue:$it" }
        } catch (_: RuntimeException) {
            null // Unreadable track metadata precludes automatic PLAY, not PAUSE of the verified player.
        }
        return DesktopMusicResumePolicy.Playback(controller.sessionToken, item, when (state.state) {
            PlaybackState.STATE_PLAYING -> DesktopMusicResumePolicy.State.PLAYING
            PlaybackState.STATE_PAUSED -> DesktopMusicResumePolicy.State.PAUSED
            else -> DesktopMusicResumePolicy.State.OTHER
        }, state.position)
    }

    private fun allowed(context: Context): Boolean = DexPrefs(context).useDelegated && DelegatedDesktopAccess.isReady(context)
    private fun releaseObserver() {
        val old = watched; val listener = callback
        watched = null; callback = null
        if (old != null && listener != null) runCatching { old.unregisterCallback(listener) }
        val manager = mediaManager
        sessionsListener?.let { runCatching { manager?.removeOnActiveSessionsChangedListener(it) } }
        selectedListener?.let { runCatching { manager?.removeOnMediaKeyEventSessionChangedListener(it) } }
        mediaManager = null; sessionsListener = null; selectedListener = null
    }
    private fun cancelPending(reason: String) {
        if (policy.hasPending) AppLog.i("DESKTOP MUSIC: resume cancelled ($reason)", false)
        policy.cancel()
        releaseObserver()
        scheduleNext()
    }
    private fun clear(reason: String) {
        main.removeCallbacks(tick)
        cancelPending(reason)
        link.clear(); ready = null; audioReady = null; context = null
        main.removeCallbacks(tick)
    }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
}
