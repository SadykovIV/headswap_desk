package com.headswap.desk.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Display;
import com.headswap.desk.dex.DelegatedPanelAccess;
import com.headswap.desk.dex.PanelPlatform;
import com.headswap.desk.dex.ShellPanelClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Pattern;

/** Observe the actual internal display after calling the production backend in a disposable emulator. */
final class PanelProbe {
    static void run(Instrumentation test, String panelToken) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        if (!(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"))) {
            result.putString("error", "Requires a disposable Android emulator");
            test.finish(code, result);
            return;
        }
        UiAutomation ui = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try {
            Context context = test.getTargetContext();
            if (!DelegatedPanelAccess.INSTANCE.usesShellBackend() || panelToken == null)
                throw new IllegalArgumentException("Start the emulator test with tools/qa/panel_probe.py");
            result.putString("backend", "shell");
            ShellPanelClient.INSTANCE.connect(panelToken);
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            waitForState(ui, displays, Display.STATE_ON);
            PanelPlatform platform = new PanelPlatform(context);
            platform.prepare();
            result.putInt("before", Display.STATE_ON);
            try {
                platform.power(false);
                waitForState(ui, displays, Display.STATE_OFF);
                result.putInt("blanked", Display.STATE_OFF);
                if (!platform.mayRestore())
                    throw new IllegalStateException("OFF must retain the reset path");
            } finally {
                platform.power(true);
                waitForState(ui, displays, Display.STATE_ON);
                result.putInt("restored", Display.STATE_ON);
            }
            // Keep the lease alive across the helper's 4 s deadline, then stop renewing.
            platform.power(false);
            try {
                for (int i = 0; i < 7; i++) { SystemClock.sleep(750); platform.refresh(); }
                waitForState(ui, displays, Display.STATE_OFF);
                result.putBoolean("renewed", true);
                SystemClock.sleep(4750);
                waitForState(ui, displays, Display.STATE_ON);
                result.putBoolean("watchdogRestored", true);
            } finally { platform.power(true); }
            platform.power(false);
            try { waitForState(ui, displays, Display.STATE_OFF); }
            finally { ShellPanelClient.INSTANCE.close(); }
            waitForState(ui, displays, Display.STATE_ON);
            result.putBoolean("peerLossRestored", true);
            code = Activity.RESULT_OK;
        } catch (Exception | LinkageError failure) {
            result.putString("error", android.util.Log.getStackTraceString(failure));
        } finally {
            ShellPanelClient.INSTANCE.close();
            ui.dropShellPermissionIdentity();
            test.finish(code, result);
        }
    }

    private static void waitForState(UiAutomation ui, DisplayManager displays, int wanted) throws Exception {
        int state = Display.STATE_UNKNOWN;
        long deadline = SystemClock.elapsedRealtime() + 3500;
        while (SystemClock.elapsedRealtime() < deadline) {
            Display primary = displays.getDisplay(Display.DEFAULT_DISPLAY);
            state = compositorState(ui, primary);
            if (state == wanted) return;
            SystemClock.sleep(80);
        }
        throw new IllegalStateException("Display 0 state " + state + " (expected " + wanted + ")");
    }

    // On the SurfaceControl backend Android's logical screen stays ON while the panel is blank.
    private static int compositorState(UiAutomation ui, Display display) throws Exception {
        Object address = Display.class.getMethod("getAddress").invoke(display);
        long id = (long)address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
        Pattern header = Pattern.compile("^Display " + Long.toUnsignedString(id) + "(?:\\s.*)?$");
        boolean primary = false;
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(
                ui.executeShellCommand("dumpsys SurfaceFlinger"))))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("Display ")) primary = header.matcher(line).matches();
                if (!primary) continue;
                String mode = line.trim();
                if ("powerMode=On".equalsIgnoreCase(mode)) return Display.STATE_ON;
                if ("powerMode=Off".equalsIgnoreCase(mode)) return Display.STATE_OFF;
            }
        }
        return Display.STATE_UNKNOWN;
    }
}
