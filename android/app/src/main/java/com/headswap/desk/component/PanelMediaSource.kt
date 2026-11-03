package com.headswap.desk.component

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import com.headswap.desk.cluster.ClusterNavigationListener
import com.headswap.desk.cluster.clusterPlayerIndex
import com.headswap.desk.component.api.ProfileContract
import com.headswap.desk.component.api.ProfileNowPlaying
import kotlin.math.roundToInt

/** One dashboard session, polled only while its pane is visible. No player token, URI, queue or audio leaves HeadSwap. */
internal class PanelMediaSource(private val app: Context) {
    private val listener = ComponentName(app, ClusterNavigationListener::class.java)
    private var retained: MediaSession.Token? = null

    private fun player(): MediaController? {
        val access = if (app.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) == PackageManager.PERMISSION_GRANTED) null
            else if (app.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)) listener
            else return null
        val players = app.getSystemService(MediaSessionManager::class.java).getActiveSessions(access).filter {
            val info = it.playbackInfo
            info != null && info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL &&
                info.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
        }
        val index = clusterPlayerIndex(players.map { it.playbackState?.state ?: PlaybackState.STATE_NONE },
            players.indexOfFirst { it.sessionToken == retained })
        return players.getOrNull(index).also { retained = it?.sessionToken }
    }

    fun snapshot(): Bundle? {
        val player = player() ?: return null
        val metadata = player.metadata ?: return null
        val state = player.playbackState ?: return null
        fun label(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            metadata.getText(key)?.let { it.subSequence(0, minOf(it.length, 512)).toString() }?.takeIf { it.isNotBlank() }
        }.orEmpty()
        val title = label(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = label(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val image = sequenceOf(MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART).mapNotNull { metadata.getBitmap(it) }.firstOrNull()
        return ProfileNowPlaying.encode(title, artist, state.state == PlaybackState.STATE_PLAYING,
            image?.let { try { thumbnail(it) } catch (_: RuntimeException) { null } })
    }

    fun command(code: Int, stillAllowed: () -> Boolean): Boolean {
        val player = player() ?: return false
        val state = player.playbackState ?: return false
        val action = panelTransportAction(code, state.state, state.actions) ?: return false
        if (!stillAllowed()) return false
        val controls = player.transportControls
        when (action) {
            PlaybackState.ACTION_SKIP_TO_PREVIOUS -> controls.skipToPrevious()
            PlaybackState.ACTION_SKIP_TO_NEXT -> controls.skipToNext()
            PlaybackState.ACTION_PAUSE -> controls.pause()
            PlaybackState.ACTION_PLAY -> controls.play()
        }
        return true
    }

    private fun thumbnail(source: Bitmap): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0 || source.config == Bitmap.Config.HARDWARE) return null
        val ratio = minOf(1.0, ProfileNowPlaying.MAX_ART_EDGE.toDouble() / maxOf(source.width, source.height))
        val w = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (source.height * ratio).roundToInt().coerceAtLeast(1)
        // Always allocate the small destination. A sub-bitmap could share a much larger backing allocation.
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(source, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
}

internal fun panelTransportAction(code: Int, state: Int, supported: Long): Long? {
    val action = when (code) {
        ProfileContract.MEDIA_PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
        ProfileContract.MEDIA_NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
        ProfileContract.MEDIA_PLAY_PAUSE -> when (state) {
            PlaybackState.STATE_PLAYING -> PlaybackState.ACTION_PAUSE
            PlaybackState.STATE_PAUSED -> PlaybackState.ACTION_PLAY
            else -> return null
        }
        else -> return null
    }
    return action.takeIf { supported and action != 0L ||
        code == ProfileContract.MEDIA_PLAY_PAUSE && supported and PlaybackState.ACTION_PLAY_PAUSE != 0L }
}
