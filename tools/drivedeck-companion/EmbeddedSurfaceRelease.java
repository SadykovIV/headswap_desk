package ru.drivedeck.headswap;

/** Gateway disconnect/timeout is deliberately not a producer-release acknowledgement. */
public final class EmbeddedSurfaceRelease {
    private boolean createAttempted;
    private boolean producerClosed;

    public synchronized void beforeCreate() {
        if (producerClosed) throw new IllegalStateException("Producer release was already acknowledged");
        createAttempted = true;
    }
    public synchronized void acknowledgeProducerRelease() { producerClosed = true; }
    public synchronized boolean mayReleaseConsumer() { return producerClosed || !createAttempted; }
}
