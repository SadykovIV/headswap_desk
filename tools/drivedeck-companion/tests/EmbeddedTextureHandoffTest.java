package ru.drivedeck.headswap;

/** Real rapid-return ordering: failed release, same TextureView, retry ACK, then a fresh visible lease. */
public final class EmbeddedTextureHandoffTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        EmbeddedTextureHandoff<Object,Object,EmbeddedResumeGate.Ticket> handoff = new EmbeddedTextureHandoff<>();
        Object texture = new Object(), oldProducer = new Object(), successor = new Object();
        EmbeddedResumeGate host = new EmbeddedResumeGate();
        EmbeddedResumeGate.Ticket first = host.resume(12);
        handoff.retire(texture,oldProducer);
        EmbeddedSurfaceRelease old = new EmbeddedSurfaceRelease(); old.beforeCreate();
        check(!old.mayReleaseConsumer());
        // No ACK after a failed RPC or bridge disconnect: never create a second producer.
        for (int i=0;i<1000;i++) check(handoff.deferIfRetired(texture,first));
        check(handoff.released(texture,successor) == null);
        host.pause();
        EmbeddedResumeGate.Ticket returned = host.resume(12);
        check(handoff.deferIfRetired(texture,returned));
        old.acknowledgeProducerRelease();
        check(old.mayReleaseConsumer());
        EmbeddedTextureHandoff.Completion<EmbeddedResumeGate.Ticket> next = handoff.released(texture,oldProducer);
        check(next.retry == returned && next.retry.isCurrent(12) && !next.releaseTexture);
        check(!handoff.deferIfRetired(texture,first));
        // Stale ACK from the old producer cannot release a later retired lease on this texture.
        handoff.retire(texture,successor);
        check(handoff.released(texture,oldProducer) == null && handoff.deferIfRetired(texture,returned));
        host.pause();
        next = handoff.released(texture,successor);
        check(next.retry != null && !next.retry.isCurrent(12));
        // Destruction cancels the handoff and frees the actual texture once, after all exact ACKs.
        handoff.retire(texture,oldProducer); handoff.retire(texture,successor);
        check(handoff.deferIfRetired(texture,first));
        handoff.destroyed(texture);
        check(handoff.deferIfRetired(texture,returned));
        check(handoff.released(texture,oldProducer) == null);
        next = handoff.released(texture,successor);
        check(next.releaseTexture && next.retry == null && handoff.released(texture,successor) == null);
        System.out.println("EmbeddedTextureHandoff: rapid return, ambiguous release, stale ACK, destroyed texture PASS");
    }
}
