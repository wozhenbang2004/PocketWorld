package dev.pocketengine.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** A host-bound tool surface. Identity/grants are bound by the supervisor, never supplied by a model. */
public interface CapabilityPort extends AutoCloseable {
    /** Fresh, host-scoped reference data. It grants no execution authority and is never journaled. */
    default JSONObject contextSnapshot(String query)throws Exception{return new JSONObject();}
    /** Native lifecycle only. These callbacks never create a task, approval or effect operation. */
    default void beginRun(String goal,RunControl control)throws Exception { }
    /** Native turn boundary, before final tool fingerprints or reference admission are captured.
     * Materializes an already owner-requested host surface only; tools() remains a pure read.
     * Delegating surface wrappers must forward this boundary using the same original control. */
    default void prepareTurnSurface(RunControl control)throws Exception { }
    /** Native availability only, assembled from the final surface before reference selection. */
    default void admitCapabilities(CapabilitySnapshot snapshot,RunControl control)throws Exception { }
    /** One current tool/reference snapshot, immediately before the existing model call. */
    default JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception {
        return new JSONObject().put("tools",tools()).put("reference",contextSnapshot(query));
    }
    /** False discards an answer made against a revoked capability/context snapshot. */
    default boolean responseCurrent(RunControl control)throws Exception{return true;}
    /** Read-only evidence for optional step routing, never authorization or a tool replay.
     * Unknown producers and receipts without a currently checkable native source pin stay false. */
    default boolean stepReadSourceCurrent(String tool,JSONObject receipt,RunControl original)throws Exception{return false;}
    /** Native gateway request identity + final projected messages; never exposed as a model tool. */
    default void beforeModelRequest(String requestId,JSONArray projectedMessages,RunControl control)throws Exception { }
    /** After a real completed response survives the existing pause/source/steering gates. */
    default JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{return null;}
    /** A prepared/failed/abandoned request is not proof that reference data was provided. */
    default void modelRequestAbandoned(String requestId)throws Exception { }
    /** Native visual requirement, checked against this Runner's captured model before dispatch. */
    default NativeToolObservation.Requirement observationRequirement(String tool,JSONObject arguments,RunControl original)throws Exception{return NativeToolObservation.Requirement.NONE;}
    /** One original result, not tool JSON or a historical screenshot. Delegating wrappers must forward. */
    default NativeToolObservation takeToolObservation(String tool,String originalOperationId,RunControl original)throws Exception{return null;}
    /** A one-use native plan, never reconstructed from a tool receipt. Defaults to no plan. */
    default NativeToolPlan takeToolPlan(String tool,String originalOperationId,RunControl original)throws Exception{return null;}
    /** One bounded native safe-data repair after final budgeting; never a tool or a history mutation. */
    default NativeReadContextRepair nativeReadContextRepair(JSONArray finalMessages,RunControl original)throws Exception{return null;}
    /** Null means no interceptor. A returned receipt is a definite non-dispatch refusal only. */
    default JSONObject beforeTool(String name,JSONObject arguments,RunControl control)throws Exception{return null;}
    /** Called only after the original real receipt and text checkpoint have been persisted. */
    default void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl control)throws Exception { }
    default void endRun(RunControl control)throws Exception { }
    @Override default void close() { }
    final class Unavailable extends SecurityException {
        public Unavailable(){super("本轮工具已撤销或安装身份改变，请重新取得当前工具清单");}
    }
    /** A real result already exists, but the same native host rejected continued
     * execution. Runner may settle this original receipt, never expose its body to
     * a new model request or use it to restore authority after withdrawal. */
    final class ReceiptAfterStop extends Exception {
        private final RunControl original;
        private final String actual;
        private final Exception failure;
        public ReceiptAfterStop(RunControl original,JSONObject actual,Exception failure){
            super("Native receipt retained after continuation stopped",java.util.Objects.requireNonNull(failure));
            this.original=java.util.Objects.requireNonNull(original);this.actual=java.util.Objects.requireNonNull(actual).toString();this.failure=failure;
        }
        public JSONObject receipt(RunControl control)throws Exception{if(control!=original)throw new SecurityException("Receipt belongs to another native run");return new JSONObject(actual);}
        public Exception failure(RunControl control){if(control!=original)throw new SecurityException("Receipt belongs to another native run");return failure;}
    }
    JSONArray tools() throws Exception;
    boolean supports(String name);
    boolean sideEffecting(String name);
    /** Native route classification only. Null arguments ask about the catalogue; actual arguments
     * must recheck any dynamic read restriction. Effect declarations never establish read authority. */
    default ExecutionPlanning.Access planningAccess(String name,JSONObject arguments)throws Exception{return ExecutionPlanning.Access.DENY;}
    default void validate(String name,JSONObject arguments)throws Exception { }
    /** Return a native receipt classified by ToolOutcome. Keep dispatch/effect and
     * terminal facts separate; never use arbitrary provider data as host metadata.
     * Expected correctable refusals are receipts. Cancellation, lost source/authority
     * and storage failures retain their exception paths. */
    JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception;
}
