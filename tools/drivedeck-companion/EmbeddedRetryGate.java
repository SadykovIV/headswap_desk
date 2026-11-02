package ru.drivedeck.headswap;

import java.util.function.BiPredicate;

/** One latest request, one scheduled task, and a finite retry budget for unchanged input. */
public final class EmbeddedRetryGate<T> {
    public static final class Ticket {
        public final long delayMillis;
        private Ticket(long delayMillis) { this.delayMillis = delayMillis; }
    }
    public static final class Attempt<T> {
        public final T value;
        private final long revision;
        private Attempt(T value, long revision) { this.value = value; this.revision = revision; }
    }
    private final BiPredicate<T, T> same;
    private final long[] delays;
    private T wanted;
    private long revision;
    private int failures;
    private boolean closed, settled, exhausted;
    private Ticket scheduled;
    private Attempt<T> running;

    public EmbeddedRetryGate(BiPredicate<T, T> same, long... delays) {
        this.same = same; this.delays = delays.clone();
        for (long delay : delays) if (delay < 0) throw new IllegalArgumentException("Negative retry delay");
    }

    public synchronized Ticket offer(T value) {
        return offer(value, false);
    }

    /** A host resume explicitly revalidates the last ACK; ordinary repeated resize events still coalesce. */
    public synchronized Ticket refresh(T value) {
        return offer(value, true);
    }

    private Ticket offer(T value, boolean refresh) {
        if (closed) return null;
        boolean changed = refresh || wanted == null || !same.test(wanted, value);
        if (changed) {
            wanted = value; revision++; failures = 0; settled = false; exhausted = false;
            scheduled = null; // Any old runnable becomes stale, even if it already left the queue.
        }
        if (running != null || settled || exhausted || scheduled != null) return null;
        return scheduled = new Ticket(0);
    }

    public synchronized Attempt<T> begin(Ticket ticket) {
        if (closed || ticket == null || ticket != scheduled || running != null) return null;
        scheduled = null;
        return running = new Attempt<>(wanted, revision);
    }

    public synchronized Ticket complete(Attempt<T> attempt, boolean success) {
        if (running != attempt) return null;
        running = null;
        if (closed) return null;
        if (attempt.revision != revision) {
            failures = 0;
            return scheduled = new Ticket(0); // Apply the newest geometry after the in-flight RPC.
        }
        if (success) { settled = true; return null; }
        failures++;
        if (failures > delays.length) { exhausted = true; return null; }
        return scheduled = new Ticket(delays[failures - 1]);
    }

    public synchronized void close() { closed = true; scheduled = null; }
}
