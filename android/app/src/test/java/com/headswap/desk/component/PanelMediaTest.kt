package com.headswap.desk.component

import android.media.session.PlaybackState
import com.headswap.desk.component.api.ProfileContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelMediaTest {
    @Test fun allowedCommandsApplyOnlyToAnActionOfferedByTheCurrentPlayer() {
        val actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT
        assertEquals(PlaybackState.ACTION_PLAY, panelTransportAction(ProfileContract.MEDIA_PLAY_PAUSE, PlaybackState.STATE_PAUSED, actions))
        assertEquals(PlaybackState.ACTION_PAUSE, panelTransportAction(ProfileContract.MEDIA_PLAY_PAUSE, PlaybackState.STATE_PLAYING, actions))
        assertEquals(PlaybackState.ACTION_SKIP_TO_NEXT, panelTransportAction(ProfileContract.MEDIA_NEXT, PlaybackState.STATE_PLAYING, actions))
        assertNull(panelTransportAction(ProfileContract.MEDIA_PREVIOUS, PlaybackState.STATE_PLAYING, actions))
        assertNull(panelTransportAction(ProfileContract.MEDIA_PLAY_PAUSE, PlaybackState.STATE_BUFFERING, actions))
        assertNull(panelTransportAction(0x10, PlaybackState.STATE_PLAYING, -1))
    }
}
