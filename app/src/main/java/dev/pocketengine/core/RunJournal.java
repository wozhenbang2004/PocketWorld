package dev.pocketengine.core;

import org.json.JSONArray;
import org.json.JSONObject;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;

/** Host-owned durable boundary. Implementations fence by task/run/epoch before all writes. */
public interface RunJournal {
    /** Optional native original-store compaction authority. Missing means preserve the existing safe budget block. */
    default dev.pocketengine.runtime.context.CompactionJournal compaction(){return null;}
    /** Native decoration only; every operation and inference receipt still belongs to this journal. */
    default RunJournal withCompaction(dev.pocketengine.runtime.context.CompactionJournal host){
        RunJournal original=this;java.util.Objects.requireNonNull(host);return new RunJournal(){
            public dev.pocketengine.runtime.context.CompactionJournal compaction(){return host;}
            public JSONObject restore()throws Exception{return host.restore(original);}
            public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception{host.checkpoint(original,messages,steps,observation);}
            public String beginOperation(String tool,JSONObject arguments)throws Exception{return original.beginOperation(tool,arguments);}
            public void finishOperation(String operationId,JSONObject receipt)throws Exception{original.finishOperation(operationId,receipt);}
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
    String beginOperation(String tool, JSONObject arguments) throws Exception;
    void finishOperation(String operationId, JSONObject receipt) throws Exception;
    /** Native original-row proof only; JSON running/accepted fields never grant continued dispatch. */
    default boolean hasNativeProjectRunningAck(String operationId,JSONObject deliveredReceipt)throws Exception{return false;}
    /** Original operation audit only; null means no terminal/group/lease proof, never another grant. */
    default JSONObject projectServiceExitEvidence(JSONArray provenLaunchOperationIds)throws Exception{return null;}
    /** Native accounting evidence only. Older/isolated journals provide no attributed sink. */
    default InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String memberId)throws Exception{
        return InferenceCapture.unattributed();
    }
}
