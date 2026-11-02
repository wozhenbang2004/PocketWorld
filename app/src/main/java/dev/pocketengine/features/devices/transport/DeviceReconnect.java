package dev.pocketengine.features.devices.transport;

import java.util.concurrent.ThreadLocalRandom;

/** Connect-only exponential backoff. Does not own payloads, task state, operation IDs or a retry queue. */
public final class DeviceReconnect {
    private int failures;
    public void authenticated() { failures = 0; }
    public long disconnectedDelayMillis() {
        long cap = Math.min(30_000L, 500L << Math.min(6, failures++));
        return ThreadLocalRandom.current().nextLong(Math.max(1, cap / 2), cap + 1);
    }
    /** Original owner must re-authorize and query its original operation/task, never resend pending effects. */
    public enum AfterReconnect { REAUTHORIZE_AND_QUERY_ORIGINAL_IDS }
    public AfterReconnect action() { return AfterReconnect.REAUTHORIZE_AND_QUERY_ORIGINAL_IDS; }
}
