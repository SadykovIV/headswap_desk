package ru.drivedeck.headswap;

/** A posted restore belongs to one visible host generation, never a later Activity/display. */
public final class EmbeddedResumeGate {
    private long generation;
    private int displayId = -1;
    public static final class Ticket {
        private final EmbeddedResumeGate owner;
        private final long generation;
        private final int displayId;
        private Ticket(EmbeddedResumeGate owner, long generation, int displayId) {
            this.owner = owner; this.generation = generation; this.displayId = displayId;
        }
        public boolean isCurrent(int parentDisplayId) {
            synchronized (owner) {
                return parentDisplayId > 0 && parentDisplayId == displayId &&
                        owner.displayId == displayId && owner.generation == generation;
            }
        }
    }
    public synchronized Ticket resume(int parentDisplayId) {
        if (parentDisplayId <= 0) throw new IllegalArgumentException("A secondary host display is required");
        displayId = parentDisplayId;
        return new Ticket(this, ++generation, parentDisplayId);
    }
    public synchronized Ticket current(int parentDisplayId) {
        return parentDisplayId > 0 && displayId == parentDisplayId ? new Ticket(this, generation, displayId) : null;
    }
    public synchronized void pause() { displayId = -1; generation++; }
}
