package com.headswap.desk.qa;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Silent local playback in the test APK's own UID. Counters reflect delivered player commands. */
public final class SilentPlayerActivity extends Activity {
    private AudioTrack track;
    private MediaSession session;
    private int plays, pauses;
    private long pausedAt;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        int rate = 16000;
        track = new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(new AudioFormat.Builder()
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(rate * 2).build();
        if (track.write(new short[rate], 0, rate) != rate || track.setLoopPoints(0, rate, -1) != AudioTrack.SUCCESS)
            throw new IllegalStateException("Cannot start silent fixture");
        session = new MediaSession(this, "HeadSwap USB pause probe");
        session.setPlaybackToLocal(attrs);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "silent-test-item").build());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { plays++; update(true); }
            @Override public void onPause() { pauses++; pausedAt = SystemClock.elapsedRealtime(); update(false); }
            @Override public void onStop() { finish(); }
        }, new Handler(Looper.getMainLooper()));
        session.setActive(true);
        update(true);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 30_000);
    }

    private void update(boolean playing) {
        if (playing) track.play(); else track.pause();
        Bundle info = new Bundle();
        info.putInt("plays", plays); info.putInt("pauses", pauses); info.putLong("pausedAt", pausedAt);
        session.setExtras(info);
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, 500, playing ? 1f : 0f).build());
    }

    @Override protected void onDestroy() {
        if (session != null) { session.setActive(false); session.release(); }
        if (track != null) { track.stop(); track.release(); }
        super.onDestroy();
    }
}
