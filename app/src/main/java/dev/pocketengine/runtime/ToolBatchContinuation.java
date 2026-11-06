package dev.pocketengine.runtime;

/**
 * Monotonic continuation state for one provider batch or one materialized native-plan step.
 * This is NOT tool admission: the existing loop may still process independent provider calls.
 * Only native-plan advancement and delivery of already collected observations consult it.
 * Original RunControl, source, budget and permission checks remain mandatory at every handoff.
 */
final class ToolBatchContinuation {
    private enum State { SEQUENCE_AND_OBSERVATIONS, OBSERVATIONS_ONLY, NEITHER }
    private State state = State.SEQUENCE_AND_OBSERVATIONS;

    /** A failed independent read may retain another call's successful observation. */
    void stopSequence() {
        if (state == State.SEQUENCE_AND_OBSERVATIONS) state = State.OBSERVATIONS_ONLY;
    }

    /** Source/owner/surface/sequence invalidation cannot be reversed by a later success. */
    void stopSequenceAndObservations() { state = State.NEITHER; }

    boolean allowsSequence() { return state == State.SEQUENCE_AND_OBSERVATIONS; }
    boolean allowsObservations() { return state != State.NEITHER; }
}
