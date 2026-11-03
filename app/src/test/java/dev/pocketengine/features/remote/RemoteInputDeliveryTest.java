package dev.pocketengine.features.remote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteInputDeliveryTest {
    private static JSONObject source(String worker,String protocol)throws Exception {
        JSONObject value=new JSONObject().put("worker",worker).put("protocol",protocol).put("protocolVerified",true).put("cancelRequested",false)
                .put("jobId","job-1").put("jobOperationId","operation-1").put("threadId","session-1").put("turnId","turn-1")
                .put("executorStatus","running").put("controls",new JSONObject().put("inputSteering",true).put("threadResume",false).put("cancel",true));
        if(worker.equals("claude"))value.put("controlProtocolVerified",true).put("sessionIdentityConfirmed",true).put("sessionId","session-1").put("promptRequestId","prompt-1");return value;
    }
    private static JSONObject pi()throws Exception{JSONObject value=source("pi","pi_rpc_v1").put("noTools",true).put("sessionId","session-1").put("promptRequestId","prompt-1");value.remove("turnId");return value;}
    private static boolean available(JSONObject shown,String mode)throws Exception{return shown.getJSONObject(mode).getBoolean("observedAvailable");}

    @Test public void codexOnlyReportsAnActuallyAvailableExactTurnSteer()throws Exception {
        JSONObject raw=source("codex_app_server","codex_app_server"),shown=RemoteInputDelivery.describe(raw);
        assertFalse(raw.has("controlProtocolVerified"));assertTrue(available(shown,"currentTurn"));assertFalse(available(shown,"nextTurn"));assertTrue(available(shown,"interrupt"));
        assertFalse(shown.getJSONObject("currentTurn").getBoolean("acceptanceProvesModelUse"));
        raw.remove("turnId");assertFalse(available(RemoteInputDelivery.describe(raw),"currentTurn"));
        raw.put("turnId","changed").put("executorStatus","completed");assertFalse(available(RemoteInputDelivery.describe(raw),"currentTurn"));
        raw.put("executorStatus","running").put("cancelRequested",true);assertFalse(available(RemoteInputDelivery.describe(raw),"currentTurn"));
        raw.remove("cancelRequested");assertFalse(available(RemoteInputDelivery.describe(raw),"currentTurn"));
    }
    @Test public void claudeCannotClaimLiveSteerEvenIfAnInputBooleanIsPresent()throws Exception {
        JSONObject raw=source("claude","claude_stream_json"),shown=RemoteInputDelivery.describe(raw);
        assertFalse(shown.getJSONObject("currentTurn").getBoolean("adapterMounted"));assertFalse(available(shown,"currentTurn"));
        assertEquals("prompt_interrupt",shown.getJSONObject("interrupt").getString("mode"));
        raw.put("executorStatus","completed");raw.getJSONObject("controls").put("threadResume",true);
        assertTrue(available(RemoteInputDelivery.describe(raw),"nextTurn"));
    }
    @Test public void piConversationCannotBecomeLiveInputOrAnUnsettledNextTurn()throws Exception {
        JSONObject raw=pi();raw.getJSONObject("controls").put("threadResume",true);
        JSONObject shown=RemoteInputDelivery.describe(raw);assertTrue(shown.getBoolean("protocolEvidenceReported"));assertFalse(raw.has("controlProtocolVerified"));assertFalse(raw.has("sessionIdentityConfirmed"));assertFalse(available(shown,"currentTurn"));assertFalse(available(shown,"nextTurn"));
        assertEquals("agent_abort",shown.getJSONObject("interrupt").getString("mode"));
        assertTrue(shown.getJSONObject("nextTurn").getBoolean("noToolsSessionOnly"));
    }
    @Test public void piNextTurnStillRequiresNativeSettlementAndRealCleanupObservation()throws Exception {
        JSONObject raw=pi().put("executorStatus","completed").put("nativeSettled",true).put("resourcesHeld",false)
                .put("processCleanupProof",new JSONObject().put("processGroupStoppedVerified",true));raw.getJSONObject("controls").put("threadResume",true);
        assertTrue(available(RemoteInputDelivery.describe(raw),"nextTurn"));
        raw.put("resourcesHeld",true);assertFalse(available(RemoteInputDelivery.describe(raw),"nextTurn"));raw.put("resourcesHeld",false);
        raw.getJSONObject("processCleanupProof").put("processGroupStoppedVerified",false);assertFalse(available(RemoteInputDelivery.describe(raw),"nextTurn"));
        raw.put("nativeSettled",false);assertFalse(available(RemoteInputDelivery.describe(raw),"nextTurn"));
        raw.put("nativeSettled",true).put("cancelRequested",true);raw.getJSONObject("processCleanupProof").put("processGroupStoppedVerified",true);assertFalse(available(RemoteInputDelivery.describe(raw),"nextTurn"));
    }
    @Test public void piToolEnabledOrMismatchedSessionDoesNotInheritNoToolsModes()throws Exception {
        for(JSONObject raw:new JSONObject[]{pi().put("noTools",false),pi().put("sessionId","different"),pi().put("promptRequestId","")}){
            JSONObject shown=RemoteInputDelivery.describe(raw);assertFalse(shown.getBoolean("protocolEvidenceReported"));
            assertFalse(available(shown,"currentTurn"));assertFalse(available(shown,"nextTurn"));assertFalse(available(shown,"interrupt"));
        }
    }
    @Test public void unknownProtocolIdentityAndStringBooleansAreNotPositiveEvidence()throws Exception {
        for(JSONObject raw:new JSONObject[]{null,new JSONObject(),pi().put("threadId",JSONObject.NULL),source("kimi","process_exit"),source("claude","codex_app_server"),source("codex_app_server","codex_app_server").put("protocolVerified","true"),source("codex_app_server","codex_app_server").put("jobOperationId","")}){
            JSONObject shown=RemoteInputDelivery.describe(raw);assertFalse(shown.getBoolean("protocolEvidenceReported"));assertEquals("unknown",shown.getString("driver"));
            assertFalse(available(shown,"currentTurn"));assertFalse(available(shown,"nextTurn"));assertFalse(available(shown,"interrupt"));
        }
    }
    @Test public void terminalCleanupStopIsNotAPromiseToInterruptOrUndoACompletedTurn()throws Exception {
        JSONObject raw=source("codex_app_server","codex_app_server").put("executorStatus","completed"),shown=RemoteInputDelivery.describe(raw),stop=shown.getJSONObject("interrupt");
        assertTrue(available(shown,"interrupt"));assertEquals("native_cleanup_only",stop.getString("mode"));
        assertFalse(stop.getBoolean("requestIsStopProof"));assertFalse(stop.getBoolean("reversesCompletedEffects"));
        raw.put("executorStatus","unknown");assertEquals("turn_interrupt",RemoteInputDelivery.describe(raw).getJSONObject("interrupt").getString("mode"));
    }
    @Test public void piQueueBindsOriginalPromptAndDoesNotInventATurnOrConsumption()throws Exception {
        JSONObject source=pi().put("inputSteeringVerified",true),shown=RemoteInputDelivery.describe(source);
        assertTrue(available(shown,"currentTurn"));assertEquals("original_prompt_queue",shown.getJSONObject("currentTurn").getString("mode"));
        assertFalse(shown.getJSONObject("currentTurn").getBoolean("requiresExactTurn"));assertTrue(shown.getJSONObject("currentTurn").getBoolean("requiresExactPrompt"));
        assertFalse(shown.getJSONObject("currentTurn").getBoolean("acceptanceProvesModelUse"));
        assertEquals("agent_clear_queue_abort",shown.getJSONObject("interrupt").getString("mode"));
        JSONObject target=RemoteMessageTarget.observed(source);assertEquals("prompt-1",target.getString("expectedPromptRequestId"));assertFalse(target.has("turnId"));assertFalse(target.has("expectedTurnId"));
        JSONObject receipt=new JSONObject(target.toString()).put("status","accepted").put("disposition","queued").put("delivery","native_queue").put("consumed","unknown");
        assertTrue(RemoteMessageTarget.receiptMatches(target,receipt));
        for(JSONObject wrong:new JSONObject[]{new JSONObject(receipt.toString()).put("expectedPromptRequestId","another"),new JSONObject(receipt.toString()).put("sessionId","another"),new JSONObject(receipt.toString()).put("disposition","handled"),new JSONObject(receipt.toString()).put("delivery","turn"),new JSONObject(receipt.toString()).put("consumed",true)})assertFalse(RemoteMessageTarget.receiptMatches(target,wrong));
        assertThrows(SecurityException.class,()->RemoteMessageTarget.receiptMatches(target,new JSONObject(receipt.toString()).put("turnId","invented")));
        assertThrows(SecurityException.class,()->RemoteMessageTarget.copy(new JSONObject(target.toString()).put("expectedTurnId","invented"),new JSONObject()));
    }
    @Test public void piQueueClosesOnStopUnknownAndMissingNativeObservation()throws Exception {
        for(JSONObject source:new JSONObject[]{pi(),pi().put("inputSteeringVerified","true"),pi().put("inputSteeringVerified",true).put("cancelRequested",true),pi().put("inputSteeringVerified",true).put("steeringUnknown",true),pi().put("inputSteeringVerified",true).put("executorStatus","completed"),pi().put("inputSteeringVerified",true).put("turnId","invented")})assertFalse(available(RemoteInputDelivery.describe(source),"currentTurn"));
        JSONObject source=pi().put("inputSteeringVerified",true);source.getJSONObject("controls").put("inputSteering",false);assertFalse(available(RemoteInputDelivery.describe(source),"currentTurn"));
        assertThrows(SecurityException.class,()->RemoteMessageTarget.observed(pi()));
        assertThrows(SecurityException.class,()->RemoteMessageTarget.observed(pi().put("inputSteeringVerified",true).put("noTools",false)));
    }
    @Test public void queueCapablePiContinuationNeedsNativeEmptyQueueReadback()throws Exception {
        JSONObject source=pi().put("inputSteeringVerified",true).put("executorStatus","completed").put("nativeSettled",true).put("resourcesHeld",false)
                .put("processCleanupProof",new JSONObject().put("processGroupStoppedVerified",true));source.getJSONObject("controls").put("threadResume",true);
        assertFalse(available(RemoteInputDelivery.describe(source),"nextTurn"));source.put("nativeQueueEmptyConfirmed",true);assertTrue(available(RemoteInputDelivery.describe(source),"nextTurn"));
        source.put("nativeQueueEmptyConfirmed","true");assertFalse(available(RemoteInputDelivery.describe(source),"nextTurn"));
    }
    @Test public void describingNeverMutatesTheSourceCopiesSecretsOrIssuesAuthority()throws Exception {
        JSONObject raw=source("codex_app_server","codex_app_server").put("apiKey","PRIVATE_SENTINEL_NOT_COPIED");String before=raw.toString();JSONObject shown=RemoteInputDelivery.describe(raw);
        assertEquals(before,raw.toString());assertFalse(shown.toString().contains("PRIVATE_SENTINEL_NOT_COPIED"));assertTrue(shown.getBoolean("dataOnly"));assertFalse(shown.getBoolean("authorizesExecution"));
        assertFalse(shown.getJSONObject("nextTurn").getBoolean("automaticallySent"));assertFalse(shown.getJSONObject("nextTurn").getBoolean("addsBudget"));
    }
}
