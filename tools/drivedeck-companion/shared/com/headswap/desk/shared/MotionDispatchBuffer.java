package com.headswap.desk.shared;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Latest-position delivery on an existing serial worker. Only an unstarted MOVE
 * with an equal stream key can be replaced. A null key is an ordering barrier
 * (DOWN/UP/CANCEL, pointer changes, keys); its command is never discarded.
 *
 * Stream keys include the display lease and coordinate configuration. The caller
 * still validates the lease when executing: coalescing does not grant ownership.
 * The executor must enqueue asynchronously, as Handler and our serial pools do.
 */
public final class MotionDispatchBuffer {
    private final Executor executor;
    private Entry tail;
    private long coalesced;

    public MotionDispatchBuffer(Executor executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    public synchronized void submit(Object moveKey, Runnable command) {
        Objects.requireNonNull(command);
        if (moveKey != null && tail != null && moveKey.equals(tail.key)) {
            tail.command = command;
            coalesced++;
            return;
        }
        Entry entry = new Entry(moveKey, command);
        tail = moveKey == null ? null : entry;
        try { executor.execute(entry); }
        catch (RuntimeException error) {
            if (tail == entry) tail = null;
            throw error;
        }
    }

    /** Retire the replaceable slot without dropping its already queued command. */
    public synchronized void barrier() { tail = null; }

    public synchronized long coalescedCount() { return coalesced; }

    private final class Entry implements Runnable {
        final Object key;
        Runnable command;
        Entry(Object key, Runnable command) { this.key = key; this.command = command; }
        @Override public void run() {
            final Runnable current;
            synchronized (MotionDispatchBuffer.this) {
                if (tail == this) tail = null;
                current = command;
            }
            current.run();
        }
    }
}
