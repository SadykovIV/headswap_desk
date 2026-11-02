package ru.drivedeck.headswap;

/** Tests visual invariants, not one particular display/slot split. */
public final class EmbeddedRenderProfileTest {
    private static final String NAV = "ru.yandex.yandexnavi";
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    private static void near(double actual, double expected, double tolerance) {
        if (Math.abs(actual - expected) > tolerance) throw new AssertionError(actual + " != " + expected);
    }
    private static void rejects(Runnable operation) {
        try { operation.run(); throw new AssertionError("Unsupported geometry was silently accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static EmbeddedRenderProfile.Geometry geometry(String pkg, int w, int h, int parent, int base) {
        return EmbeddedRenderProfile.geometry(pkg, true, true, w, h, parent, base);
    }
    public static void main(String[] args) {
        // Current 160DPI desktop + independently selected 85% pane scale must remain identical.
        EmbeddedRenderProfile.Geometry reference = geometry(NAV, 1022, 876, 160, 136);
        check(reference.width == 2044 && reference.height == 1752 && reference.density == 272);
        // MapKit fixed pixels grow with parent DPI. Android dp controls retain the pane DPI.
        for (int parent : new int[]{96,128,160,200,320,640}) {
            EmbeddedRenderProfile.Geometry g = geometry(NAV,1000,600,parent,parent);
            near(2.0 / g.effectiveScale, parent / 160.0, 0.00001);
            near(g.width / (double) g.density, 1000.0 / parent, 0.004);
            near(g.height / (double) g.density, 600.0 / parent, 0.004);
            check(g.density == 320);
        }
        EmbeddedRenderProfile.Geometry at200 = geometry(NAV,1022,876,200,170);
        near(at200.density / at200.effectiveScale,170,0.01);
        check(at200.density == reference.density);
        near(2 / at200.effectiveScale,1.25,0.00001);
        // Repeated resize/resume never treats already-scaled child DPI as parent DPI.
        for (int i=0;i<100;i++) check(geometry(NAV,1022,876,200,170).sameAs(at200));
        for (String pkg : new String[]{"ru.yandex.music","ru.yandex.yandexmaps","ru.yandex.yandexnavi.clone","",null}) {
            EmbeddedRenderProfile.Geometry other = geometry(pkg,438,876,200,170);
            check(other.width == 438 && other.height == 876 && other.density == 170);
        }
        // A reused Nav -> Music -> Nav slot restores its reference without compounding.
        check(geometry("ru.yandex.music",1022,876,160,136).width == 1022);
        check(geometry(NAV,1022,876,160,136).sameAs(reference));
        EmbeddedRenderProfile.Geometry legacy = EmbeddedRenderProfile.geometry(NAV,false,false,1022,876,160,136);
        check(legacy.width == 1022 && legacy.height == 876 && legacy.density == 136);
        // Low DPI samples are quality-limited instead of exceeding the existing buffer budget.
        EmbeddedRenderProfile.Geometry limited = geometry(NAV,1022,876,128,128);
        check(limited.height == 2160 && limited.width <= 3840 && limited.effectiveScale < limited.requestedScale);
        near(2 / limited.effectiveScale,0.8,0.012);
        for (int parent : new int[]{96,128,160,200,320,640}) {
            EmbeddedRenderProfile.Geometry bounded = geometry(NAV,1920,1080,parent,parent);
            check(bounded.width <= 3840 && bounded.height <= 2160 && bounded.density <= 1280);
            check((long) bounded.width * bounded.height + 1920L * 1080 <= 10368000L);
        }
        EmbeddedRenderProfile.Geometry baseOnly = EmbeddedRenderProfile.geometry(NAV,false,true,1920,1080,96,96);
        check(baseOnly.width == 1920 && baseOnly.height == 1080 && baseOnly.density == 96);
        for (int[] bad : new int[][]{{1921,1080,160},{1920,1081,160},{1280,720,95},{1280,720,641},
                {0,720,160},{1280,0,160},{Integer.MAX_VALUE,720,160}}) {
            rejects(() -> geometry(NAV,bad[0],bad[1],bad[2],160));
        }
        // Map pane percentages remain meaningful even at the extreme parent DPI values.
        check(geometry(NAV,1000,600,96,48).density == 160);
        check(geometry(NAV,1000,600,640,768).density == 384);
        rejects(() -> geometry(NAV,1280,720,160,79));
        rejects(() -> geometry(NAV,1280,720,160,193));
        for (int[] bad : new int[][]{{1920,900,160},{1600,1080,160},{1280,720,96},{1280,720,640}}) {
            rejects(() -> EmbeddedRenderProfile.geometry(NAV,true,false,bad[0],bad[1],bad[2],160));
        }
        check(geometry("music",80,480,128,128).width == 128);
        System.out.println("EmbeddedRenderProfile: parent-DPI map/UI proportions, reference restoration, bounded buffers PASS");
    }
}
