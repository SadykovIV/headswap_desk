package com.headswap.desk.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.headswap.desk.dex.EmbeddedDisplayPolicy;
import com.headswap.desk.dex.ShellPanelClient;
import java.lang.reflect.Field;
import java.net.Socket;

/** Verify IME routing across an authenticated socket reconnect on a disposable AVD. */
final class PanelReconnectProbe {
    private static final ShellPanelClient CLIENT = ShellPanelClient.INSTANCE;

    static void run(Instrumentation test, String token) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        UiAutomation ui = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try {
            if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)))
                throw new IllegalStateException("Disposable emulator required");
            CLIENT.connect(token);
            ui.adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY");
            Context context = test.getTargetContext();
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            try (ImageReader images = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 2)) {
                images.setOnImageAvailableListener(reader -> {
                    try (Image ignored = reader.acquireLatestImage()) { }
                }, new Handler(Looper.getMainLooper()));
                VirtualDisplay display = displays.createVirtualDisplay("HeadSwap navigator widget A",
                    640, 480, 160, images.getSurface(), EmbeddedDisplayPolicy.INSTANCE.displayFlags(Build.VERSION.SDK_INT));
                if (display == null) throw new IllegalStateException("Trusted virtual display unavailable");
                try {
                    int id = display.getDisplay().getDisplayId();
                    verifyIme(id);
                    result.putInt("imeDisplay", id);
                    result.putBoolean("imeHiddenBefore", true);

                    // Drop only the transport. Production close() intentionally ends the activation.
                    Field connection = ShellPanelClient.class.getDeclaredField("connection");
                    connection.setAccessible(true);
                    Object first = connection.get(CLIENT);
                    Field socket = first.getClass().getDeclaredField("socket");
                    socket.setAccessible(true);
                    ((Socket)socket.get(first)).close();
                    long until = SystemClock.elapsedRealtime() + 5000;
                    while (connection.get(CLIENT) == first || !CLIENT.isConnected()) {
                        if (SystemClock.elapsedRealtime() >= until)
                            throw new IllegalStateException("No new authenticated connection after EOF");
                        SystemClock.sleep(30);
                    }
                    if (!display.getDisplay().isValid() || display.getDisplay().getDisplayId() != id)
                        throw new IllegalStateException("Original virtual display did not survive reconnect");
                    verifyIme(id);
                    result.putBoolean("imeHiddenAfter", true);
                    code = Activity.RESULT_OK;
                } finally { display.release(); }
            }
        } catch (Exception | LinkageError e) {
            result.putString("error", android.util.Log.getStackTraceString(e));
        } finally {
            CLIENT.close();
            ui.dropShellPermissionIdentity();
            test.finish(code, result);
        }
    }

    private static void verifyIme(int display) {
        if (!CLIENT.enableLocalIme(display) || !Boolean.FALSE.equals(CLIENT.imeVisible(display)))
            throw new IllegalStateException("No IME routing/visibility reply on display " + display);
    }
}
