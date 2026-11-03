package com.headswap.desk.shared;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Geometry of a child display rendered into a HeadSwap window on the head unit. */
public final class DesktopRenderPolicy {
    private DesktopRenderPolicy() {}

    public static final Set<String> DEFAULT_PACKAGES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("ru.yandex.yandexmaps", "ru.yandex.yandexnavi")));

    public static boolean isMap(String pkg) {
        return DEFAULT_PACKAGES.contains(pkg);
    }

    /** Legacy two-map switch on hosts without a package-specific configuration endpoint. */
    public static int[] geometry(String pkg, int width, int height, int parentDpi, boolean compactMapSymbols) {
        return geometry(width, height, parentDpi, compactMapSymbols && isMap(pkg));
    }

    /** Buffer width, buffer height and Android logical density for an already resolved selection. */
    public static int[] geometry(int width, int height, int parentDpi, boolean correctThisPackage) {
        if (width <= 0 || height <= 0 || width > 1920 || height > 1080 || parentDpi < 96 || parentDpi > 640) {
            throw new IllegalArgumentException("Недопустимый размер окна или DPI рабочего стола");
        }
        int w = Math.max(128, width);
        int h = Math.max(128, height);
        if (!correctThisPackage) return new int[]{w, h, parentDpi};

        // A fixed-pixel sign is half size at 160 DPI. A dp button keeps its on-screen size
        // because density and surface dimensions change by the same factor.
        double scale = Math.max(1.0, 320.0 / parentDpi);
        scale = Math.min(scale, Math.min(3840.0 / w, 2160.0 / h));
        // At very low DPI allow more than 2x for small tiles only. Two such tiles
        // share the producer's 10,368,000-pixel budget.
        double pixels = (double) w * h;
        if (scale > 2.0) scale = Math.min(scale, Math.max(2.0, Math.sqrt(5_184_000.0 / pixels)));
        return new int[]{(int) Math.round(w * scale), (int) Math.round(h * scale), (int) Math.round(parentDpi * scale)};
    }
}
