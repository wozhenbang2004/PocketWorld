package dev.pocketengine.features.local.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original observed states, not simulated installation success or Android execution. */
public final class RuntimeSetupStateTest {
    private JSONObject facts(boolean ready)throws Exception{return new JSONObject().put("canExecute",ready).put("preparationAvailable",true).put("preparation",new JSONObject());}
    private JSONObject admission(boolean allowed)throws Exception{return new JSONObject().put("canPrepare",allowed).put("preparationInProgress",false);}
    private JSONObject original(String state,JSONObject receipt)throws Exception {
        JSONObject task=new JSONObject().put("id","task").put("runId","run").put("coordinatorEpoch",1);
        JSONObject row=new JSONObject().put("operationId","operation").put("state",state);if(receipt!=null)row.put("receipt",receipt);
        return new JSONObject().put("task",task).put("operation",row);
    }
    @Test public void cleanUserCanChoosePreparationWithoutProjectOrModel()throws Exception {
        JSONObject runtime=facts(false),view=RuntimeSetupState.view(runtime,admission(true),null,false,null,"");
        assertEquals("not_prepared",view.getString("status"));assertTrue(view.getBoolean("canPrepare"));assertFalse(view.getBoolean("canCancel"));
        assertFalse(view.getBoolean("modelRequired"));assertFalse(view.getBoolean("projectRequired"));assertFalse(view.getBoolean("automaticReplay"));assertTrue(view.isNull("operation"));
        view.getJSONObject("runtime").put("canExecute",true);assertFalse(runtime.getBoolean("canExecute"));
    }
    @Test public void byteProgressBelongsToOriginalCurrentPhaseAndAptHasNoMadeUpPercent()throws Exception {
        JSONObject receipt=new JSONObject().put("status","awaiting_receipt").put("ownedWorkerLive",true).put("preparationPhase","downloading").put("completedBytes",30L).put("totalBytes",100L);
        JSONObject view=RuntimeSetupState.view(facts(false),admission(false),original("unknown",receipt),true,null,"");
        assertEquals("running",view.getString("status"));assertTrue(view.getBoolean("canCancel"));assertFalse(view.getBoolean("canPrepare"));
        assertEquals(.3,view.getJSONObject("operation").getDouble("fraction"),0);assertEquals("bytes",view.getJSONObject("operation").getString("progressUnit"));
        receipt.put("preparationPhase","installing_system_tools").put("totalBytes",5);
        view=RuntimeSetupState.view(facts(false),admission(false),original("unknown",receipt),true,null,"");
        assertTrue(view.getJSONObject("operation").isNull("fraction"));assertTrue(view.getJSONObject("operation").isNull("progressUnit"));
    }
    @Test public void interruptedUnknownNeverAdvertisesRetryEvenWithAnOldUsableEnvironment()throws Exception {
        JSONObject receipt=new JSONObject().put("status","unknown").put("ownedWorkerLive",false).put("observation","owner_process_restarted_no_replay").put("resourcesReleased",false);
        JSONObject view=RuntimeSetupState.view(facts(true),admission(true),original("unknown",receipt),false,null,"");
        assertEquals("interrupted",view.getString("status"));assertTrue(view.getBoolean("ready"));assertFalse(view.getBoolean("canRetry"));assertFalse(view.getBoolean("canPrepare"));assertFalse(view.getBoolean("canCancel"));
    }
    @Test public void cancellationWaitsForActualResourceReleaseBeforeRetry()throws Exception {
        JSONObject receipt=new JSONObject().put("status","awaiting_receipt").put("ownedWorkerLive",true).put("cancelRequested",true);
        JSONObject view=RuntimeSetupState.view(facts(false),admission(true),original("unknown",receipt),true,null,"");
        assertEquals("cancel_requested",view.getString("status"));assertFalse(view.getBoolean("canRetry"));assertFalse(view.getBoolean("canCancel"));
        receipt.put("status","failed").put("ownedWorkerLive",false).put("receiptReceived",true).put("resourcesReleased",true);
        view=RuntimeSetupState.view(facts(false),admission(true),original("receipt",receipt),false,null,"");
        assertEquals("cancelled",view.getString("status"));assertTrue(view.getBoolean("canRetry"));
    }
    @Test public void failureRetainsActualNativeDetailsAndLogCursor()throws Exception {
        JSONObject receipt=new JSONObject().put("status","failed").put("receiptReceived",true).put("resourcesReleased",true)
                .put("error","runtime_native_command_failed").put("launchErrno",13).put("exitCode",-1).put("timedOut",false).put("stderr","Permission denied");
        JSONObject runtime=facts(false);runtime.getJSONObject("preparation").put("error","runtime_native_command_failed").put("failure",new JSONObject().put("category","permissions"));
        JSONObject logs=new JSONObject().put("nextCursor",42).put("hasMore",true);
        JSONObject view=RuntimeSetupState.view(runtime,admission(true),original("receipt",receipt),false,logs,"");
        assertEquals("failed",view.getString("status"));assertTrue(view.getBoolean("canRetry"));
        assertEquals(13,view.getJSONObject("operation").getJSONObject("error").getJSONObject("details").getInt("launchErrno"));
        assertEquals("Permission denied",view.getJSONObject("operation").getJSONObject("receipt").getString("stderr"));assertEquals(42,view.getJSONObject("logs").getInt("nextCursor"));
    }
    @Test public void historicalSuccessDoesNotMakeAChangedRuntimeReady()throws Exception {
        JSONObject receipt=new JSONObject().put("status","completed").put("receiptReceived",true).put("resourcesReleased",true).put("toolProbeReceipt",new JSONObject().put("generationIdentity","old"));
        JSONObject view=RuntimeSetupState.view(facts(false),admission(true),original("receipt",receipt),false,null,"");
        assertFalse(view.getBoolean("ready"));assertEquals("not_prepared",view.getString("status"));assertTrue(view.getBoolean("canPrepare"));
        assertEquals("old",view.getJSONObject("operation").getJSONObject("receipt").getJSONObject("toolProbeReceipt").getString("generationIdentity"));
    }
    @Test public void safePreflightInterruptionAllowsFreshReviewButOtherInstallRemainsObservationOnly()throws Exception {
        JSONObject receipt=new JSONObject().put("accepted",false).put("notDispatched",true).put("code","runtime_setup_interrupted_before_dispatch");
        JSONObject view=RuntimeSetupState.view(facts(false),admission(true),original("receipt",receipt),false,null,"");
        assertEquals("interrupted",view.getString("status"));assertTrue(view.getBoolean("canRetry"));
        view=RuntimeSetupState.view(facts(false),admission(false).put("preparationInProgress",true),null,false,null,"");
        assertEquals("running",view.getString("status"));assertFalse(view.getBoolean("canPrepare"));assertFalse(view.getBoolean("canCancel"));
    }
    @Test public void disabledOwnerAdmissionIsExplainedWithoutPretendingAnInstallIsRunning()throws Exception {
        JSONObject unavailable=admission(false).put("executionEnabled",false).put("message","本机执行准入已停用");
        JSONObject view=RuntimeSetupState.view(facts(false),unavailable,null,false,null,"");
        assertEquals("blocked",view.getString("status"));assertEquals("本机执行准入已停用",view.getString("message"));assertFalse(view.getBoolean("canPrepare"));assertFalse(view.getBoolean("canCancel"));
    }
}
