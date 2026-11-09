package com.headswap.desk.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import com.headswap.desk.dex.DesktopModePolicy;
import com.headswap.desk.dex.EmbeddedDisplayPolicy;

/** Disposable-emulator regression: projecting at another density is independent of the phone. */
final class ProjectionDensityProbe {
    @SuppressWarnings("deprecation")
    private static int dpi(Display display) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return metrics.densityDpi;
    }

    static void run(Instrumentation test) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        UiAutomation ui = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try {
            if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)))
                throw new IllegalStateException("Disposable emulator required");
            ui.adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY");
            Context context = test.getTargetContext();
            DisplayManager dm = context.getSystemService(DisplayManager.class);
            int original = dpi(dm.getDisplay(Display.DEFAULT_DISPLAY));
            for (int mode : new int[]{DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK}) {
                int projected = original == 128 ? 200 : 128;
                try (ImageReader parentPixels = ImageReader.newInstance(1280,720,PixelFormat.RGBA_8888,2);
                     ImageReader childPixels = ImageReader.newInstance(576,684,PixelFormat.RGBA_8888,2)) {
                    VirtualDisplay parent = dm.createVirtualDisplay("HeadSwap " + DesktopModePolicy.INSTANCE.label(mode),
                        1280,720,projected,parentPixels.getSurface(),DesktopModePolicy.INSTANCE.displayFlags(mode, Build.VERSION.SDK_INT));
                    if (parent == null) throw new IllegalStateException("Parent VD unavailable");
                    try {
                        VirtualDisplay child = dm.createVirtualDisplay("HeadSwap test child",576,684,projected,
                            childPixels.getSurface(),EmbeddedDisplayPolicy.INSTANCE.displayFlags(Build.VERSION.SDK_INT));
                        if (child == null) throw new IllegalStateException("Child VD unavailable");
                        try {
                            if (dpi(parent.getDisplay()) != projected || dpi(child.getDisplay()) != projected
                                    || dpi(dm.getDisplay(0)) != original) throw new IllegalStateException("Per-display DPI mismatch");
                            child.resize(480,640,200);
                            long until=SystemClock.elapsedRealtime()+2000;
                            while (dpi(child.getDisplay()) != 200 && SystemClock.elapsedRealtime()<until) SystemClock.sleep(30);
                            if (dpi(child.getDisplay()) != 200 || dpi(dm.getDisplay(0)) != original)
                                throw new IllegalStateException("Child resize changed primary DPI");
                        } finally { child.release(); }
                    } finally { parent.release(); }
                }
                if (dpi(dm.getDisplay(0)) != original) throw new IllegalStateException("VD release changed primary DPI");
            }
            result.putString("profiles", "Android Desktop, DriveDeck; native Samsung DeX requires a Samsung device");
            result.putBoolean("primaryUnchanged",true);
            result.putInt("primaryDpi",original);
            code=Activity.RESULT_OK;
        } catch (Exception | LinkageError e) {
            result.putString("error",android.util.Log.getStackTraceString(e));
        } finally { ui.dropShellPermissionIdentity(); test.finish(code,result); }
    }
}
