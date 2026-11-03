package com.headswap.desk.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.media.AudioAttributes;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.headswap.desk.dex.RegisteredPlaybackMix;
import java.io.InputStream;

/** Single instrumentation entry for the two opt-in, emulator-only hardware probes. */
public final class CompatibilityProbe extends Instrumentation {
    private String probe;
    private String panelToken;
    @Override public void onCreate(Bundle options) {
        super.onCreate(options);
        probe = options == null ? null : options.getString("probe");
        panelToken = options == null ? null : options.getString("panel_token");
        start();
    }
    @Override public void onStart() {
        if ("panel".equals(probe)) { PanelProbe.run(this, panelToken); return; }
        if ("panel-reconnect".equals(probe)) { PanelReconnectProbe.run(this, panelToken); return; }
        if ("components".equals(probe)) { ComponentProbe.run(this); return; }
        Bundle result = new Bundle(); int code = Activity.RESULT_CANCELED;
        if (!"audio".equals(probe) || !(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"))) {
            result.putString("error", "Select -e probe audio or panel on a disposable Android emulator."); finish(code, result); return;
        }
        UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try {
            ui.adoptShellPermissionIdentity("android.permission.CAPTURE_AUDIO_OUTPUT","android.permission.MODIFY_AUDIO_ROUTING");
            int testUid = getTargetContext().getPackageManager().getApplicationInfo(getContext().getPackageName(), 0).uid;
            if (testUid == android.os.Process.myUid()) throw new IllegalStateException("Fixture must use a separate UID");
            // No media or navigation is playing during the negative control.
            measure(ui, AudioAttributes.USAGE_ALARM, false, result);
            measure(ui, AudioAttributes.USAGE_MEDIA, true, result);
            measure(ui, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, true, result);
            code = Activity.RESULT_OK;
        } catch (Exception | LinkageError e) {
            result.putString("error", android.util.Log.getStackTraceString(e));
        } finally {
            ui.dropShellPermissionIdentity();
            finish(code, result);
        }
    }

    private void measure(UiAutomation ui, int usage, boolean expectedSignal, Bundle result) throws Exception {
        try (RegisteredPlaybackMix mix = RegisteredPlaybackMix.Companion.open(getTargetContext())) {
            AudioRecord recorder = mix.getRecord();
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Recorder did not start");
            String cmd = "am start -W -f 0x10008000 -n com.headswap.desk.test/com.headswap.desk.qa.MixToneActivity --ei usage " + usage;
            try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd))) {
                String launch = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!launch.contains("Status: ok")) throw new IllegalStateException("Fixture did not start: " + launch);
            }
            int frequency = usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ? 1500 : 1000;
            short[] pcm = new short[1920]; int peak = 0, samples = 0, matchingWindows = 0;
            long end = SystemClock.elapsedRealtime() + 3200;
            while (SystemClock.elapsedRealtime() < end) {
                int n = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
                if (n < 0) throw new IllegalStateException("read returned " + n);
                double real = 0, imag = 0, energy = 0; int frames = n / 2;
                for (int f = 0; f < frames; f++) {
                    int value = pcm[2*f]; peak = Math.max(peak, Math.abs(value));
                    double angle = 2 * Math.PI * f * frequency / 48000;
                    real += value * Math.cos(angle); imag += value * Math.sin(angle);
                    energy += (double)value * value;
                }
                if (frames >= 192 && energy > frames * 64 && 2*(real*real + imag*imag) > .7 * frames * energy) matchingWindows++;
                samples += frames;
                SystemClock.sleep(8);
            }
            result.putString("usage_" + usage, "frames=" + samples + ", peak=" + peak + ", frequencyHz=" + frequency + ", matchingWindows=" + matchingWindows);
            if (samples < 24000 || (expectedSignal ? matchingWindows < 3 : matchingWindows != 0))
                throw new IllegalStateException("Unexpected PCM for usage " + usage + ": " + result.getString("usage_" + usage));
            recorder.stop();
        }
    }
}
