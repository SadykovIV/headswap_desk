package com.headswap.desk.cluster

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings

data class ClusterNowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    val source: String = "Android",
    val durationSeconds: Int? = null,
    val positionSeconds: Int? = null,
    val artwork: ClusterArtwork? = null,
)

/** Returns the current local player in Android priority order; never replace playing music with a paused player. */
internal fun clusterPlayerIndex(states: List<Int>, retained: Int): Int {
    val playing = states.indexOf(PlaybackState.STATE_PLAYING)
    if (playing >= 0) return playing
    return if (states.getOrNull(retained) == PlaybackState.STATE_PAUSED) retained else states.indexOf(PlaybackState.STATE_PAUSED)
}

/** One CarLife session's metadata, including in-memory artwork. Never fetches remote artwork URLs. */
internal class ClusterMediaSource(context: Context, private val emit: (ClusterNowPlaying?) -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val notificationAccess = ComponentName(app, "com.headswap.desk.cluster.ClusterNavigationListener")
    private val main = Handler(Looper.getMainLooper())
    private var worker: Handler? = null
    @Volatile private var closed = false
    // The following state is confined to the worker.
    private var sessions: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var registeredComponent: ComponentName? = null
    private var observer: ContentObserver? = null
    private val callbacks = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var current = emptyList<MediaController>()
    private var selected: MediaSession.Token? = null
    private var sent = false
    private var last: ClusterNowPlaying? = null
    private var artworkBitmap: android.graphics.Bitmap? = null
    private var artworkGeneration = 0
    private var artwork: ClusterArtwork? = null

    private fun cover(metadata: MediaMetadata): ClusterArtwork? {
        val bitmap = sequenceOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON).mapNotNull { metadata.getBitmap(it) }
            .firstOrNull { !it.isRecycled }
        if (bitmap == null) { artworkBitmap = null; artwork = null; return null }
        if (artworkBitmap === bitmap && artworkGeneration == bitmap.generationId) return artwork
        artworkBitmap = bitmap
        artworkGeneration = bitmap.generationId
        artwork = com.headswap.desk.MetaImages.albumJpeg(bitmap)?.let(::ClusterArtwork)
        return artwork
    }

    @Synchronized fun start() {
        if (closed || worker != null) return
        val thread = HandlerThread("headswap-cluster-music").apply { start() }
        val handler = Handler(thread.looper)
        worker = handler
        handler.post {
            if (closed) return@post
            val listener = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = bind(handler)
            }
            runCatching {
                app.contentResolver.registerContentObserver(Settings.Secure.getUriFor("enabled_notification_listeners"), false, listener)
                observer = listener
            }
            bind(handler)
        }
    }

    private fun bind(handler: Handler) {
        if (closed) return
        detach()
        try {
            val authorized = app.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) == PackageManager.PERMISSION_GRANTED
            if (!authorized && !app.getSystemService(NotificationManager::class.java)
                    .isNotificationListenerAccessGranted(notificationAccess)) {
                publish(null)
                return
            }
            val component = if (authorized) null else notificationAccess
            val next = MediaSessionManager.OnActiveSessionsChangedListener {
                // An old notification-listener registration may already have queued a change.
                if (!closed && sessions != null && registeredComponent == component) refreshSessions(handler, component)
            }
            manager.addOnActiveSessionsChangedListener(next, component, handler)
            sessions = next
            registeredComponent = component
            refreshSessions(handler, component)
        } catch (_: RuntimeException) {
            detach()
            publish(null)
        }
    }

    private fun refreshSessions(handler: Handler, component: ComponentName?) {
        if (closed) return
        try {
            current = manager.getActiveSessions(component)
            val activeTokens = current.mapTo(HashSet()) { it.sessionToken }
            for (token in callbacks.keys - activeTokens) remove(token)
            for (controller in current) {
                val token = controller.sessionToken
                if (token in callbacks) continue
                val callback = object : MediaController.Callback() {
                    private fun updated() { if (callbacks[token]?.second === this) update() }
                    override fun onPlaybackStateChanged(state: PlaybackState?) = updated()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = updated()
                    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = updated()
                    override fun onSessionDestroyed() {
                        if (callbacks[token]?.second !== this) return
                        current = current.filterNot { it.sessionToken == token }
                        remove(token)
                        update()
                    }
                }
                controller.registerCallback(callback, handler)
                callbacks[token] = controller to callback
            }
            update()
        } catch (_: RuntimeException) {
            detach()
            publish(null)
        }
    }

    private fun update() {
        if (closed) return
        if (registeredComponent != null && !runCatching { app.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(notificationAccess) }.getOrDefault(false)) {
            detach()
            publish(null)
            return
        }
        val track = runCatching {
            val players = current.filter { it.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL }
            val playback = players.map { it.playbackState }
            val index = clusterPlayerIndex(playback.map { it?.state ?: PlaybackState.STATE_NONE },
                players.indexOfFirst { it.sessionToken == selected })
            val player = players.getOrNull(index)
            selected = player?.sessionToken
            val metadata = player?.metadata ?: return@runCatching null
            fun label(key: String) = metadata.getText(key)?.toString()?.trim()?.take(256).orEmpty()
            val title = label(MediaMetadata.METADATA_KEY_TITLE).ifEmpty { label(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
            if (title.isEmpty()) return@runCatching null
            val state = playback[index] ?: return@runCatching null
            val artist = label(MediaMetadata.METADATA_KEY_ARTIST).ifEmpty { label(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) }
                .ifEmpty { label(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) }
            ClusterNowPlaying(title, artist,
                label(MediaMetadata.METADATA_KEY_ALBUM), state.state == PlaybackState.STATE_PLAYING,
                durationSeconds = seconds(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }),
                positionSeconds = seconds(state.position.takeIf { it >= 0 }), artwork = cover(metadata))
        }.getOrNull()
        publish(track)
    }

    private fun seconds(milliseconds: Long?): Int? = milliseconds?.let { (it / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

    private fun publish(track: ClusterNowPlaying?) {
        if (closed || (sent && last == track)) return
        sent = true
        last = track
        main.post { if (!closed) emit(track) }
    }

    private fun remove(token: MediaSession.Token) {
        callbacks.remove(token)?.let { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
    }

    private fun detach() {
        sessions?.let { runCatching { manager.removeOnActiveSessionsChangedListener(it) } }
        sessions = null
        registeredComponent = null
        for (token in callbacks.keys.toList()) remove(token)
        current = emptyList()
        selected = null
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val handler = worker ?: return
        handler.post {
            detach()
            observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
            observer = null
            last = null
            artworkBitmap = null
            artwork = null
            handler.looper.quitSafely()
        }
    }
}
