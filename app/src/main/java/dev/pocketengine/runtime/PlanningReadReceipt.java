package dev.pocketengine.runtime;

import dev.pocketengine.core.ExecutionPlanning;
import org.json.JSONObject;

/**
 * Settlement bookkeeping for one already-issued native planning read, not read admission.
 * The native planning ledger still owns idempotency and the claim's authority.
 * Deliberately not AutoCloseable: Runner owns the distinct failure and stopped-receipt paths.
 */
final class PlanningReadReceipt {
    private enum State { NOT_ISSUED, PENDING, SETTLED }
    private final ExecutionPlanning owner;
    private final ExecutionPlanning.ReadClaim claim;
    private State state;

    PlanningReadReceipt(ExecutionPlanning owner, ExecutionPlanning.ReadClaim claim) {
        this.owner = owner;
        this.claim = claim;
        state = claim == null ? State.NOT_ISSUED : State.PENDING;
    }

    boolean pending() { return state == State.PENDING; }
    ExecutionPlanning.ReadClaim claim() { return claim; }
    String operationId() { return claim.operationId; }

    /** Caller supplies the original sanitized receipt and checks live sources where required.
     * ReceiptAfterStop intentionally needs no fresh admission to save the issued read's fact. */
    void settle(JSONObject sanitizedReceipt, ExecutionPlanning.ReadOutcome outcome) throws Exception {
        record(sanitizedReceipt, outcome);
        state = State.SETTLED;
    }

    /** Preserve the original stopped path: native receipt, evidence, then relinquish pending. */
    void settleStopped(JSONObject sanitizedReceipt, ExecutionPlanning.ReadOutcome outcome,
                       JSONObject evidence) throws Exception {
        record(sanitizedReceipt, outcome);
        evidence.put("planningReadOperationId", claim.operationId).put("receiptRecorded", true);
        state = State.SETTLED;
    }

    private void record(JSONObject sanitizedReceipt, ExecutionPlanning.ReadOutcome outcome) throws Exception {
        if (!pending()) throw new IllegalStateException("Original planning read is not pending");
        owner.finishRead(claim, sanitizedReceipt, outcome);
    }

    void settleUnconfirmed() throws Exception {
        settle(unconfirmedReceipt(), ExecutionPlanning.ReadOutcome.UNCONFIRMED);
    }

    static JSONObject unconfirmedReceipt() throws Exception {
        return new JSONObject().put("ok",false).put("status","read_unconfirmed").put("code","planning_read_unconfirmed")
                .put("readCompleted",false).put("automaticReplay",false).put("executionAuthorized",false);
    }
}
