package com.headswap.desk.shared;

/** Geometry of a child display rendered into a HeadSwap window on the head unit. */
public final class DesktopRenderPolicy {
    private DesktopRenderPolicy() {}

    public static boolean isMap(String pkg) {
        return "ru.yandex.yandexnavi".equals(pkg) || "ru.yandex.yandexmaps".equals(pkg);
    }

    /** Buffer width, buffer height and Android logical density, in that order. */
    public static int[] geometry(String pkg, int width, int height, int parentDpi, boolean compactMapSymbols) {
        if (width <= 0 || height <= 0 || width > 1920 || height > 1080 || parentDpi < 96 || parentDpi > 640) {
            throw new IllegalArgumentException("Недопустимый размер окна или DPI рабочего стола");
        }
        int w = Math.max(128, width);
        int h = Math.max(128, height);
        if (!compactMapSymbols || !isMap(pkg)) return new int[]{w, h, parentDpi};

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
