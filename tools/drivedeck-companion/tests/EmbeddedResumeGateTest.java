package ru.drivedeck.headswap;

public final class EmbeddedResumeGateTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        EmbeddedResumeGate host = new EmbeddedResumeGate();
        EmbeddedResumeGate.Ticket first = host.resume(23);
        check(first.isCurrent(23));
        // App switch during a posted restore or pending resize cancels that restore launch.
        EmbeddedRetryGate<String> resizes = new EmbeddedRetryGate<>(String::equals,250,1000,3000);
        EmbeddedRetryGate.Attempt<String> pending = resizes.begin(resizes.offer("1600x900@320"));
        host.pause();
        check(!first.isCurrent(23));
        resizes.complete(pending,true);
        check(!first.isCurrent(23));
        // Returning needs a fresh ticket; the old callback remains stale after returning.
        EmbeddedResumeGate.Ticket returned = host.resume(23);
        check(returned.isCurrent(23) && !first.isCurrent(23));
        EmbeddedResumeGate otherHost = new EmbeddedResumeGate();
        check(otherHost.resume(24).isCurrent(24));
        check(!returned.isCurrent(24) && !returned.isCurrent(0));
        EmbeddedResumeGate.Ticket moved = host.resume(24);
        check(moved.isCurrent(24) && !returned.isCurrent(23));
        host.pause();host.pause();
        check(!moved.isCurrent(24));
        try { host.resume(0); throw new AssertionError("Primary display accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("EmbeddedResumeGate: app switch, late resize ACK, host/display replacement PASS");
    }
}
