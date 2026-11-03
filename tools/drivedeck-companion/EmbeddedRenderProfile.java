package ru.drivedeck.headswap;

import com.headswap.desk.shared.DesktopRenderPolicy;

/** Geometry for the surface consumer; older hosts use their protocol-1 compatibility rules. */
public final class EmbeddedRenderProfile {
    public static final String NAVIGATOR_PACKAGE = "ru.yandex.yandexnavi";
    public static final int REFERENCE_PARENT_DENSITY = 160;
    public static final int REFERENCE_NAVIGATOR_SCALE = 2;
    private EmbeddedRenderProfile() {}

    public static final class Geometry {
        public final int width, height, density, parentDensity;
        public final double requestedScale, effectiveScale;
        Geometry(int width, int height, int density, int parentDensity, double requestedScale, double effectiveScale) {
            this.width = width; this.height = height; this.density = density;
            this.parentDensity = parentDensity;
            this.requestedScale = requestedScale; this.effectiveScale = effectiveScale;
        }
        public boolean sameAs(Geometry other) {
            return other != null && width == other.width && height == other.height && density == other.density;
        }
    }

    public static boolean corrected(String pkg, boolean hostFlag, boolean exactPackageConfig) {
        return pkg != null && !pkg.isEmpty() && hostFlag && (exactPackageConfig || DesktopRenderPolicy.isMap(pkg));
    }

    public static Geometry configured(int width, int height, int parentDpi, boolean correctThisPackage) {
        int[] pixels = DesktopRenderPolicy.geometry(width, height, parentDpi, correctThisPackage);
        double scale = pixels[0] / (double) Math.max(128, width);
        return new Geometry(pixels[0], pixels[1], pixels[2], parentDpi, scale, scale);
    }

    public static Geometry geometry(String packageName, boolean highResolutionSupported,
                                    boolean extendedGeometrySupported,
                                    int layoutWidth, int layoutHeight, int parentDensity, int baseDensity) {
        int maximumWidth = extendedGeometrySupported ? 1920 : 1600;
        int maximumHeight = extendedGeometrySupported ? 1080 : 900;
        int minimumDensity = extendedGeometrySupported ? 96 : 120;
        int maximumDensity = extendedGeometrySupported ? 640 : 320;
        boolean navigator = highResolutionSupported && NAVIGATOR_PACKAGE.equals(packageName);
        if (layoutWidth <= 0 || layoutHeight <= 0 || layoutWidth > maximumWidth || layoutHeight > maximumHeight
                || parentDensity < minimumDensity || parentDensity > maximumDensity
                || baseDensity < (navigator ? Math.round(parentDensity * 0.5f) : minimumDensity)
                || baseDensity > (navigator ? Math.round(parentDensity * 1.2f) : maximumDensity)) {
            throw new IllegalArgumentException(extendedGeometrySupported
                    ? "Размер окна или DPI вне диапазона: до 1920×1080, от 96 до 640 DPI"
                    : "Обновите HeadSwap для этого размера окна или DPI (старая версия: до 1600×900, 120–320 DPI)");
        }
        double requestedScale = navigator
                ? REFERENCE_NAVIGATOR_SCALE * (double) REFERENCE_PARENT_DENSITY / parentDensity : 1;
        // Fixed MapKit pixels / scale must grow in proportion to the user's parent DPI.
        // Scaling density by the same factor leaves ordinary Android dp widgets unchanged.
        // Bound only this extra rendering work, without changing the chosen parent layout/DPI.
        int maximumBufferWidth = navigator ? maximumWidth * 2 : maximumWidth;
        int maximumBufferHeight = navigator ? maximumHeight * 2 : maximumHeight;
        int maximumBufferDensity = navigator ? maximumDensity * 2 : maximumDensity;
        double effectiveScale = Math.min(requestedScale, Math.min(
                maximumBufferWidth / (double) Math.max(128, layoutWidth), Math.min(
                maximumBufferHeight / (double) Math.max(128, layoutHeight),
                maximumBufferDensity / (double) baseDensity)));
        int width = Math.max(128, (int) Math.round(Math.max(128, layoutWidth) * effectiveScale));
        int height = Math.max(128, (int) Math.round(Math.max(128, layoutHeight) * effectiveScale));
        int density = Math.max(minimumDensity, (int) Math.round(baseDensity * effectiveScale));
        return new Geometry(width, height, density, parentDensity, requestedScale, effectiveScale);
    }
}
