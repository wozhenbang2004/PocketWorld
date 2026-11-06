package dev.pocketengine.runtime;

import org.json.JSONObject;

/**
 * Runner-thread bookkeeping for one original native plan, not a persisted cursor.
 * NativeToolPlan and RunControl still own currentness and dispatch permission.
 * Keep operational fields typed; JSON is only the final observation projection,
 * never a source from which a plan, grant, or retry may be reconstructed.
 */
final class NativeSequenceProgress {
    private final String parentOperationId;
    private final String traceParentCallId;
    private final int totalSteps;
    private int dispatchEnteredSteps;

    NativeSequenceProgress(String parentOperationId, String traceParentCallId, int totalSteps) {
        this.parentOperationId = parentOperationId;
        this.traceParentCallId = traceParentCallId;
        this.totalSteps = totalSteps;
    }

    String traceParentCallId() { return traceParentCallId; }
    int dispatchEnteredSteps() { return dispatchEnteredSteps; }

    /** Entering dispatch does not prove an effect happened or succeeded. */
    void dispatchEntered(int stepIndex) { dispatchEnteredSteps = stepIndex + 1; }

    /**
     * Called only for the current call's explicit Boolean accepted=false receipt.
     * An exception, missing receipt or unknown effect must not erase entered work.
     * This corrects progress reporting only; it does not retry or rewind the plan.
     */
    void notDispatched(int stepIndex) { dispatchEnteredSteps = stepIndex; }

    /** Fresh projection: callers can append display fields without mutating state. */
    JSONObject view() throws Exception {
        return new JSONObject().put("parentOperationId", parentOperationId)
                .put("traceParentCallId", traceParentCallId)
                .put("totalSteps", totalSteps)
                .put("dispatchEnteredSteps", dispatchEnteredSteps);
    }
}
