package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

/** Projection invariants only. Actual process/IO evidence is in ManagedProcessActivityTest. */
public final class RuntimeActivityTest {
    private static JSONObject live()throws Exception{return new JSONObject().put("status","running").put("ownedWorkerLive",true).put("nativeStartObserved",true);}
    private static JSONObject exit(boolean pty)throws Exception{return live().put("status","completed").put("receiptReceived",true).put("processExited",true).put("resourcesReleased",true).put("processGroupAbsent",true).put("nativeResultContract",pty?NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT:NativeProcessSupervisor.EXIT_RESULT_CONTRACT);}
    private static JSONObject view(JSONObject receipt,Long silence,boolean output)throws Exception{return RuntimeActivity.describe("original-only",receipt,silence,output);}
    @Test public void silenceThresholdNeverMeansSuccessOrInputReadiness()throws Exception {
        JSONObject quiet=view(live(),RuntimeActivity.IDLE_AFTER_MILLIS,true);
        assertEquals("inferred_idle",quiet.getString("state"));assertTrue(quiet.getBoolean("inferred"));
        assertFalse(quiet.getBoolean("processTerminal"));assertFalse(quiet.getBoolean("businessVerified"));
        assertFalse(quiet.getBoolean("replayAllowed"));assertEquals("unknown",quiet.getString("waitingForInput"));
        assertEquals("active_output",view(live(),RuntimeActivity.IDLE_AFTER_MILLIS-1,true).getString("state"));
        assertEquals("running",view(live(),null,false).getString("state"));
    }
    @Test public void retainedOrMalformedLiveFlagsCannotReconstructMonotonicActivity()throws Exception {
        JSONObject cold=live().put("ownedWorkerLive",false);JSONObject value=view(cold,999999L,true);
        assertEquals("observation_unknown",value.getString("state"));assertTrue(value.isNull("silenceMillis"));assertFalse(value.getBoolean("outputObservedThisOwner"));
        assertEquals("observation_unknown",view(live().put("ownedWorkerLive","true"),999999L,true).getString("state"));
        assertEquals("starting",view(live().put("nativeStartObserved",false),999999L,true).getString("state"));
    }
    @Test public void processExitNeedsOriginalNativeContractGroupAndPtySessionRelease()throws Exception {
        assertEquals("exited",view(exit(false),null,false).getString("state"));
        assertFalse(view(exit(false).put("resourcesReleased",false),null,false).getBoolean("processTerminal"));
        assertEquals("awaiting_resource_release",view(exit(false).put("resourcesReleased",false),null,false).getString("state"));
        assertFalse(view(exit(false).put("nativeResultContract","external_json"),null,false).getBoolean("processTerminal"));
        assertFalse(view(exit(false).put("processGroupAbsent",false),null,false).getBoolean("processTerminal"));
        assertFalse(view(exit(true),null,false).getBoolean("processTerminal"));
        assertTrue(view(exit(true).put("processSessionAbsent",true),null,false).getBoolean("processTerminal"));
        assertFalse(view(exit(true).put("processSessionAbsent",true).put("status","unknown"),null,false).getBoolean("processTerminal"));
    }
    @Test public void pendingStopAndPreparationCannotBecomeIdleSuccess()throws Exception {
        assertEquals("stopping",view(live().put("cancelRequested",true),999999L,true).getString("state"));
        assertEquals("preparing",view(live().put("preparationPhase","downloading"),999999L,true).getString("state"));
        assertFalse(view(live().put("preparationPhase","ready"),999999L,true).getBoolean("processTerminal"));
    }
    @Test public void onlyPublishedNativeAckMetadataIsProjectedWithoutInputOrErrors()throws Exception {
        JSONObject ack=new JSONObject().put("status","delivered").put("nativeAcknowledged",true).put("inputOperationId","original-line").put("text","private input").put("error","private path");
        JSONObject value=view(live().put("lastTerminalInput",ack),0L,true),input=value.getJSONObject("input");
        assertEquals("delivered",input.getString("state"));assertEquals("original-line",input.getString("operationId"));
        assertFalse(input.getBoolean("applicationConsumptionProven"));assertEquals("not_observed",input.getString("queueState"));
        assertFalse(value.toString().contains("private"));
        ack.put("nativeAcknowledged",false);assertEquals("unknown",view(live().put("lastTerminalInput",ack),0L,true).getJSONObject("input").getString("state"));
        ack.put("inputOperationId","not a native operation: private");assertFalse(view(live().put("lastTerminalInput",ack),0L,true).getJSONObject("input").has("operationId"));
    }
}
