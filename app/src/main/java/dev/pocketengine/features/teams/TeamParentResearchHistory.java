package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.memory.MemoryHostFence;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import org.json.JSONArray;
import org.json.JSONObject;

/** Source-only decoration of the original parent journal/RAW fence. It grants nothing,
 * stores no bodies and owns no agent loop. The gate rehydrates original native read receipts. */
public final class TeamParentResearchHistory {
    private TeamParentResearchHistory(){}
    public static JSONObject observe(RunControl.AuthorityCheck current,java.util.concurrent.Callable<JSONObject> read)throws Exception {
        current.check();JSONObject result=read.call();current.check();return result;
    }
    public static MemoryHostFence fence(Object tasks,MemoryHostFence original,RunControl.AuthorityCheck current){
        return new MemoryHostFence(){
            public void check()throws Exception{original.check();if(!Thread.holdsLock(tasks))current.check();}
            public JSONObject admit(Admission action)throws Exception {
                // The outer RAW/compactor admission checks metadata outside Tasks. Reentrant
                // local SQL checks use that same short admission, never enter Book under Tasks.
                if(Thread.holdsLock(tasks))return original.admit(action);
                current.check();JSONObject result=original.admit(action);current.check();return result;
            }
        };
    }
    public static RunJournal journal(RunJournal original,RunControl.AuthorityCheck current){
        return new RunJournal(){
            public dev.pocketengine.runtime.context.CompactionJournal compaction(){return original.compaction();}
            public JSONObject restore()throws Exception{return original.restore();}
            public void checkpoint(JSONArray history,int steps,JSONObject observation)throws Exception{original.checkpoint(history,steps,observation);}
            public boolean progressGuardSupported(){return original.progressGuardSupported();}
            public void checkpoint(JSONArray messages,int steps,JSONObject observation,JSONObject progressGuard)throws Exception{original.checkpoint(messages,steps,observation,progressGuard);}
            public String beginOperation(String tool,JSONObject args)throws Exception{return original.beginOperation(tool,args);}
            public void finishOperation(String op,JSONObject receipt)throws Exception{original.finishOperation(op,receipt);}
            public boolean hasNativeProjectRunningAck(String op,JSONObject receipt)throws Exception{return original.hasNativeProjectRunningAck(op,receipt);}
            public JSONObject projectServiceExitEvidence(JSONArray operations)throws Exception{return original.projectServiceExitEvidence(operations);}
            public boolean reserveModelRecovery(String call,long wait)throws Exception{return original.reserveModelRecovery(call,wait);}
            public int executionStepCeiling(int steps)throws Exception{return original.executionStepCeiling(steps);}
            public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String member)throws Exception{
                current.check();return InferenceCapture.withAdmission(original.inferenceTarget(purpose,member),current::check);
            }
        };
    }
}
