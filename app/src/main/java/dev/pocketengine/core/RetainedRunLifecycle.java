package dev.pocketengine.core;

import java.util.concurrent.atomic.AtomicReference;

/** In-memory ownership of one retained native runner. This is not a durable task
 * status, grant or recovery authority; a rejected close never permits resume. */
public final class RetainedRunLifecycle {
    public enum Phase { ACTIVE, YIELDED, CLOSE_QUEUED, CLOSE_PENDING, CLOSING, CLOSED }
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.ACTIVE);

    public Phase phase() { return phase.get(); }
    public boolean yielded() { return phase.get() == Phase.YIELDED; }
    public void yield() {
        if (!phase.compareAndSet(Phase.ACTIVE, Phase.YIELDED))
            throw new IllegalStateException("Original runner is not active");
    }
    public boolean resume() { return phase.compareAndSet(Phase.YIELDED, Phase.ACTIVE); }
    public boolean queueClose() {
        for (;;) {
            Phase current = phase.get();
            if (current != Phase.YIELDED && current != Phase.CLOSE_PENDING) return false;
            if (phase.compareAndSet(current, Phase.CLOSE_QUEUED)) return true;
        }
    }
    public boolean pickUpClose() { return phase.compareAndSet(Phase.CLOSE_QUEUED, Phase.CLOSING); }
    /** Call only after execute rejected without pickup, after cancellation callbacks. */
    public void closeRejected() { phase.compareAndSet(Phase.CLOSE_QUEUED, Phase.CLOSE_PENDING); }
    public void closeFailed() { phase.compareAndSet(Phase.CLOSING, Phase.CLOSE_PENDING); }
    public boolean release() { return phase.getAndSet(Phase.CLOSED) != Phase.CLOSED; }
}
