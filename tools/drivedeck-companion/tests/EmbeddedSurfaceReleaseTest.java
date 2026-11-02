package ru.drivedeck.headswap;

public final class EmbeddedSurfaceReleaseTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        EmbeddedSurfaceRelease unused = new EmbeddedSurfaceRelease();
        check(unused.mayReleaseConsumer());
        EmbeddedSurfaceRelease uncertain = new EmbeddedSurfaceRelease();
        uncertain.beforeCreate();
        // No handle, an RPC error, gateway death or elapsed time cannot establish release.
        for (int i = 0; i < 1000; i++) check(!uncertain.mayReleaseConsumer());
        uncertain.acknowledgeProducerRelease();
        check(uncertain.mayReleaseConsumer());
        try { uncertain.beforeCreate(); throw new AssertionError("Reused an acknowledged Surface"); }
        catch (IllegalStateException expected) { }
        uncertain.acknowledgeProducerRelease();
        check(uncertain.mayReleaseConsumer());
        System.out.println("EmbeddedSurfaceRelease: unused consumer, uncertain create and explicit ACK PASS");
    }
}
