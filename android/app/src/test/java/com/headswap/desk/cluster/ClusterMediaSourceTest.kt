package com.headswap.desk.cluster

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterMediaSourceTest {
    @Test fun aPlayingPlayerWinsOverThePreviouslySelectedPausedPlayer() {
        assertEquals(1, clusterPlayerIndex(listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING), retained = 0))
    }

    @Test fun honorSystemPriorityDuringPlaybackAndRetainLastTrackWhenPaused() {
        assertEquals(0, clusterPlayerIndex(listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PLAYING), retained = 1))
        assertEquals(1, clusterPlayerIndex(listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PAUSED), retained = 1))
        assertEquals(0, clusterPlayerIndex(listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PAUSED), retained = -1))
    }

    @Test fun noTrackSurvivesTheEndOfPlayback() {
        assertEquals(-1, clusterPlayerIndex(listOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR), retained = 0))
        assertEquals(-1, clusterPlayerIndex(emptyList(), retained = 0))
    }
}
