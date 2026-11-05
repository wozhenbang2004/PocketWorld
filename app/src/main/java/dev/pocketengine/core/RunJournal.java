package dev.pocketengine.core;

import org.json.JSONArray;
import org.json.JSONObject;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;

/** Host-owned durable boundary. Implementations fence by task/run/epoch before all writes. */
public interface RunJournal {
    /** Original native phase/read authority. A controlled restore must never lose this handle. */
    default ExecutionPlanning planning()throws Exception{return null;}
    /** Native pre-request admission stopped for an exhausted existing allowance. This is
     * diagnostic data, never a grant or evidence that earlier effects did not happen. */
    final class NativeBudgetPaused extends Exception {
        public final String taskId,runId,parentTaskId,parentRunId,purpose;
        public final int epoch,used,limit;
        public NativeBudgetPaused(String taskId,String runId,int epoch,String parentTaskId,String parentRunId,String purpose,int used,int limit){
            super("Native model allowance exhausted before request dispatch");
            this.taskId=java.util.Objects.requireNonNull(taskId);this.runId=java.util.Objects.requireNonNull(runId);this.epoch=epoch;
            this.parentTaskId=java.util.Objects.requireNonNull(parentTaskId);this.parentRunId=java.util.Objects.requireNonNull(parentRunId);this.purpose=java.util.Objects.requireNonNull(purpose);this.used=used;this.limit=limit;
        }
        public JSONObject diagnostics()throws Exception{return new JSONObject().put("code","native_model_budget_exhausted").put("taskId",taskId).put("runId",runId).put("coordinatorEpoch",epoch).put("parentTaskId",parentTaskId).put("parentRunId",parentRunId).put("purpose",purpose).put("modelRequestsReservedOrUsed",used).put("modelRequestLimit",limit).put("thisRequestDispatched",false).put("priorUsageRetained",true).put("autoRefill",false).put("executionAuthorized",false);}
    }
    /** Optional native original-store compaction authority. Missing means preserve the existing safe budget block. */
    default dev.pocketengine.runtime.context.CompactionJournal compaction(){return null;}
    /** Native decoration only; every operation and inference receipt still belongs to this journal. */
    default RunJournal withCompaction(dev.pocketengine.runtime.context.CompactionJournal host){
        RunJournal original=this;java.util.Objects.requireNonNull(host);return new RunJournal(){
            public ExecutionPlanning planning()throws Exception{return original.planning();}
            public dev.pocketengine.runtime.context.CompactionJournal compaction(){return host;}
            public JSONObject restore()throws Exception{return host.restore(original);}
            public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception{host.checkpoint(original,messages,steps,observation);}
            public boolean progressGuardSupported(){return original.progressGuardSupported();}
            public void checkpoint(JSONArray messages,int steps,JSONObject observation,JSONObject progressGuard)throws Exception{host.checkpoint(original,messages,steps,observation,progressGuard);}
            public String beginOperation(String tool,JSONObject arguments)throws Exception{return original.beginOperation(tool,arguments);}
            public void finishOperation(String operationId,JSONObject receipt)throws Exception{original.finishOperation(operationId,receipt);}
            public JSONObject finishOperationReceipt(String operationId,JSONObject receipt)throws Exception{return original.finishOperationReceipt(operationId,receipt);}
            public boolean hasNativeProjectRunningAck(String operationId,JSONObject deliveredReceipt)throws Exception{return original.hasNativeProjectRunningAck(operationId,deliveredReceipt);}
            public JSONObject projectServiceExitEvidence(JSONArray provenLaunchOperationIds)throws Exception{return original.projectServiceExitEvidence(provenLaunchOperationIds);}
            public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String memberId)throws Exception{return original.inferenceTarget(purpose,memberId);}
            public boolean reserveModelRecovery(String failedCall,long waitMillis)throws Exception{return original.reserveModelRecovery(failedCall,waitMillis);}
            public int executionStepCeiling(int captured)throws Exception{return original.executionStepCeiling(captured);}
        };
    }
    /** Original task-wide retry wait debit; absent native authority must not invent recovery. */
    default boolean reserveModelRecovery(String failedCall,long waitMillis)throws Exception{return false;}
    /** Only the original owner scope can raise the captured ceiling after native CAS review. */
    default int executionStepCeiling(int captured)throws Exception{return captured;}
    JSONObject restore() throws Exception;
    void checkpoint(JSONArray messages, int steps, JSONObject observation) throws Exception;
    /** The native store commits guard state atomically with the original protocol checkpoint.
     * Isolated/legacy journals retain live protection but cannot claim cold-recovery support. */
    default boolean progressGuardSupported(){return false;}
    default void checkpoint(JSONArray messages,int steps,JSONObject observation,JSONObject progressGuard)throws Exception{
        if(progressGuardSupported())throw new IllegalStateException("native_progress_guard_checkpoint_missing");
        checkpoint(messages,steps,observation);
    }
    String beginOperation(String tool, JSONObject arguments) throws Exception;
    void finishOperation(String operationId, JSONObject receipt) throws Exception;
    /** Settle first, then deliver the actual saved native receipt. A host may retain
     * a newer callback instead of this synchronous return. Isolated journals keep
     * their existing behavior; production decorators must forward this result. */
    default JSONObject finishOperationReceipt(String operationId,JSONObject receipt)throws Exception{
        finishOperation(operationId,receipt);return new JSONObject(receipt.toString());
    }
    /** Native original-row proof only; JSON running/accepted fields never grant continued dispatch. */
    default boolean hasNativeProjectRunningAck(String operationId,JSONObject deliveredReceipt)throws Exception{return false;}
    /** Original operation audit only; null means no terminal/group/lease proof, never another grant. */
    default JSONObject projectServiceExitEvidence(JSONArray provenLaunchOperationIds)throws Exception{return null;}
    /** Native accounting evidence only. Older/isolated journals provide no attributed sink. */
    default InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String memberId)throws Exception{
        return InferenceCapture.unattributed();
    }
}
