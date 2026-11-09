package com.headswap.desk.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import com.headswap.desk.update.ComponentAsset;
import com.headswap.desk.update.ComponentPackages;
import com.headswap.desk.update.DesktopComponent;
import java.io.File;
import java.io.FileInputStream;

/** Negative controls for component verification. Reads installed test device APKs; never launches an installer. */
final class ComponentProbe {
    static void run(Instrumentation test) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))) throw new IllegalStateException("Emulator only");
            Context ctx = test.getTargetContext();
            DesktopComponent dd = DesktopComponent.DRIVEDECK;
            PackageInfo current = ComponentPackages.INSTANCE.installed(ctx, dd);
            if (current == null || current.applicationInfo == null) throw new IllegalStateException("Install DriveDeck · HeadSwap first");
            File companion = new File(current.applicationInfo.sourceDir);
            File headswap = new File(ctx.getApplicationInfo().sourceDir);
            rejection("wrong_package", "пакет ru.drivedeck.headswap", result,
                () -> ComponentPackages.INSTANCE.validate(ctx, dd, headswap));
            rejection("already_installed", "уже установлена", result,
                () -> ComponentPackages.INSTANCE.validate(ctx, dd, companion));
            ComponentAsset mismatch = new ComponentAsset(dd, current.versionName, current.getLongVersionCode(), "", "0".repeat(64), companion.length());
            rejection("download_integrity", "Контрольная сумма", result, () -> {
                try (FileInputStream in = new FileInputStream(companion)) { ComponentPackages.INSTANCE.stage(ctx, dd, in, mismatch); }
            });
            DesktopComponent bridge = DesktopComponent.CARBRIDGE;
            PackageInfo bridgeCurrent = ComponentPackages.INSTANCE.installed(ctx, bridge);
            if (bridgeCurrent != null && bridgeCurrent.applicationInfo != null) {
                File bridgeApk = new File(bridgeCurrent.applicationInfo.sourceDir);
                // validate checks pinned publisher, profile metadata, isolated task support and SDK
                // before reaching the already-installed version guard.
                rejection("carbridge_publisher_profile", "уже установлена", result,
                    () -> ComponentPackages.INSTANCE.validate(ctx, bridge, bridgeApk));
                rejection("carbridge_wrong_package", "пакет com.example.carbridge.headswap", result,
                    () -> ComponentPackages.INSTANCE.validate(ctx, bridge, companion));
            } else result.putString("carbridge_publisher_profile", "not installed; not tested");
            code = Activity.RESULT_OK;
        } catch (Exception | AssertionError e) {
            result.putString("error", android.util.Log.getStackTraceString(e));
        } finally { test.finish(code, result); }
    }

    private interface Action { void run() throws Exception; }
    private static void rejection(String key, String expected, Bundle result, Action check) throws Exception {
        try { check.run(); }
        catch (IllegalStateException refusal) {
            if (refusal.getMessage() == null || !refusal.getMessage().contains(expected)) throw refusal;
            result.putString(key, "rejected"); return;
        }
        throw new AssertionError(key + " was accepted");
    }
}
