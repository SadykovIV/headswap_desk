package ru.drivedeck.headswap;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** A SurfaceTexture cannot be acquired by a successor until every old producer is proven closed. */
public final class EmbeddedTextureHandoff<T,O,R> {
    public static final class Completion<R> {
        public final boolean releaseTexture;
        public final R retry;
        private Completion(boolean releaseTexture, R retry) { this.releaseTexture = releaseTexture; this.retry = retry; }
    }
    private static final class Hold<O,R> {
        final Set<O> owners = Collections.newSetFromMap(new IdentityHashMap<O,Boolean>());
        R retry;
        boolean destroyed;
    }
    private final IdentityHashMap<T,Hold<O,R>> holds = new IdentityHashMap<>();
    public synchronized void retire(T texture, O producer) {
        Hold<O,R> hold = holds.get(texture);
        if (hold == null) { hold = new Hold<>(); holds.put(texture, hold); }
        hold.owners.add(producer);
    }
    /** Coalesce to one latest request. An unknown create/release result never clears a hold. */
    public synchronized boolean deferIfRetired(T texture, R retry) {
        Hold<O,R> hold = holds.get(texture);
        if (hold == null) return false;
        if (!hold.destroyed && retry != null) hold.retry = retry;
        return true;
    }
    public synchronized void destroyed(T texture) {
        Hold<O,R> hold = holds.get(texture);
        if (hold != null) { hold.destroyed = true; hold.retry = null; }
    }
    /** Called only after the exact producer ACK/death and release of its consumer Surface on main. */
    public synchronized Completion<R> released(T texture, O producer) {
        Hold<O,R> hold = holds.get(texture);
        if (hold == null || !hold.owners.remove(producer) || !hold.owners.isEmpty()) return null;
        holds.remove(texture);
        return new Completion<>(hold.destroyed, hold.destroyed ? null : hold.retry);
    }
}
