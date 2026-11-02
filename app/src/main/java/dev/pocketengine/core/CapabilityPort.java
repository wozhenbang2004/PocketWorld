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
    JSONArray tools() throws Exception;
    boolean supports(String name);
    boolean sideEffecting(String name);
    /** Native IO accounting, distinct from effect/owner-intent classification. Never a model grant. */
    default boolean requiresOperation(String name){return sideEffecting(name);}
    default void validate(String name,JSONObject arguments)throws Exception { }
    JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception;
}
