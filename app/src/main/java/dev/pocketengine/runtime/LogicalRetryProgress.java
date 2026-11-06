package dev.pocketengine.runtime;

/**
 * Bookkeeping for one logical request's already approved retries. This object
 * owns route progression and cumulative scheduled wait, not retry permission.
 * ModelGateway must first check receipts, provider policy, cancellation, semantic
 * commitment, deadline and the original authority's reserveRetry decision.
 * Caller-thread/request-local state: no persistent cursor or replay authority.
 */
final class LogicalRetryProgress {
    private int candidateIndex;
    private int switches;
    private long waited;
    private String reason = "initial_ordered_candidate";

    int candidateIndex() { return candidateIndex; }
    String reason() { return reason; }
    long remainingWaitMillis() { return 30_000 - waited; }

    /**
     * Called only after retry approval. A classified 429 stays on its original
     * route. Other eligible transient failures may advance by exactly one
     * candidate within the original plan's switch limit; never wrap or reorder.
     */
    void selectAfterApprovedRetry(int httpStatus, int maxRouteSwitches, int eligibleCount) {
        if (httpStatus != 429 && switches < maxRouteSwitches && candidateIndex + 1 < eligibleCount) {
            candidateIndex++;
            switches++;
            reason = "transient_failure_next_candidate";
        } else {
            reason = "transient_failure_same_route";
        }
    }

    /**
     * Keep the original charge point: after beforeWait succeeds, before sleep.
     * This records scheduled wait, not observed wall-clock duration. Cancellation
     * during sleep does not refund it or create permission for another attempt.
     */
    void recordWait(long delay) { waited += delay; }
}
