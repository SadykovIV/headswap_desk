package com.headswap.desk.qa;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** A finite synthetic tone in the test APK's UID, never shipped in HeadSwap. */
public final class MixToneActivity extends Activity {
    private AudioTrack track;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int usage = getIntent().getIntExtra("usage", AudioAttributes.USAGE_MEDIA);
        int rate = 48000;
        short[] tone = new short[rate / 10];
        int frequency = usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ? 1500 : 1000;
        for (int i = 0; i < tone.length; i++) tone[i] = (short)(12000 * Math.sin(2 * Math.PI * i * frequency / rate));
        track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(tone.length * 2).build();
        if (track.write(tone, 0, tone.length) != tone.length ||
                track.setLoopPoints(0, tone.length, -1) != AudioTrack.SUCCESS) throw new IllegalStateException("tone");
        track.play();
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 10000);
    }
    @Override public void onPause() {
        if (track != null) { track.stop(); track.release(); track = null; }
        super.onPause();
    }
}
