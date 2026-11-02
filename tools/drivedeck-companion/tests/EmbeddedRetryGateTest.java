package ru.drivedeck.headswap;

public final class EmbeddedRetryGateTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static EmbeddedRetryGate<String> gate() { return new EmbeddedRetryGate<>(String::equals, 250, 1000, 3000); }
    public static void main(String[] args) {
        EmbeddedRetryGate<String> gate = gate();
        EmbeddedRetryGate.Ticket first = gate.offer("A");
        for (int i = 0; i < 1000; i++) check(gate.offer("A") == null);
        EmbeddedRetryGate.Ticket latest = gate.offer("B");
        check(gate.begin(first) == null);
        EmbeddedRetryGate.Attempt<String> attempt = gate.begin(latest);
        check(attempt.value.equals("B"));
        check(gate.offer("C") == null);
        check(gate.offer("D") == null);
        latest = gate.complete(attempt, true);
        attempt = gate.begin(latest);
        check(attempt.value.equals("D"));
        check(gate.complete(attempt, true) == null);
        for (int i = 0; i < 1000; i++) check(gate.offer("D") == null);
        // Resume needs a new ACK even when dimensions are identical to the old successful resize.
        attempt = gate.begin(gate.refresh("D"));
        check(attempt != null && attempt.value.equals("D"));
        for (int i = 0; i < 1000; i++) check(gate.offer("D") == null);
        // A resume while the first RPC is still in flight must not accept its stale ACK.
        check(gate.refresh("D") == null);
        latest = gate.complete(attempt, true);
        check(latest != null && latest.delayMillis == 0);
        attempt = gate.begin(latest);
        check(attempt != null && gate.complete(attempt, true) == null && gate.offer("D") == null);

        gate = gate();
        latest = gate.offer("failed");
        for (long delay : new long[]{250, 1000, 3000}) {
            attempt = gate.begin(latest);
            latest = gate.complete(attempt, false);
            check(latest.delayMillis == delay);
            for (int i = 0; i < 1000; i++) check(gate.offer("failed") == null);
        }
        attempt = gate.begin(latest);
        check(gate.complete(attempt, false) == null);
        for (int i = 0; i < 1000; i++) check(gate.offer("failed") == null);
        latest = gate.offer("new geometry");
        check(latest.delayMillis == 0);
        gate.close();
        check(gate.begin(latest) == null && gate.offer("later") == null);

        gate = gate();
        attempt = gate.begin(gate.offer("closing"));
        gate.close();
        check(gate.complete(attempt, false) == null);
        System.out.println("EmbeddedRetryGate: coalescing, backoff, exhaustion, stale work and close PASS");
    }
}
