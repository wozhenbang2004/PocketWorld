package dev.pocketengine.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ToolOutcomeTest {
    @Test public void aBackgroundLaunchReconciliationFlagCannotBecomeASettledReceipt()throws Exception{
        JSONObject actual=new JSONObject().put("ok",true).put("accepted",true).put("requiresReconciliation",true).put("targetLanding","unknown_requires_owner");
        ToolOutcome outcome=ToolOutcome.classify(actual);
        assertTrue(outcome.requiresReconciliation());assertFalse(outcome.mayContinue());assertEquals("unknown",outcome.eventStatus());
        assertEquals("effect_unknown",ToolOutcome.project(actual).getJSONObject(ToolOutcome.KEY).getString("kind"));
    }
    @Test public void everyLegacyReconciliationSignalHasTheSameStopMeaning()throws Exception{
        for(String key:new String[]{"requiresReconciliation","reconciliationRequired"}){
            assertTrue(key,ToolOutcome.classify(new JSONObject().put("ok",true).put(key,true)).requiresReconciliation());
        }
        assertTrue(ToolOutcome.classify(new JSONObject().put("ok",true).put("nextObservationAction","reconcile")).requiresReconciliation());
        assertTrue(ToolOutcome.classify(new JSONObject().put("ok",true).put("status","read_unconfirmed")).requiresReconciliation());
    }
    @Test public void aCommittedArtifactCanStillRequireOwnerAction()throws Exception{
        JSONObject receipt=new JSONObject().put("ok",true).put("status","created").put("id","actual-artifact").put("accepted",true).put("code","needs_user").put("requiresPause",true);
        String before=receipt.toString();JSONObject model=ToolOutcome.project(receipt);
        assertTrue(ToolOutcome.classify(receipt).requiresUser());assertEquals("awaiting_user",ToolOutcome.classify(receipt).eventStatus());
        assertTrue(model.getBoolean("ok"));assertEquals("created",model.getString("status"));assertEquals("actual-artifact",model.getString("id"));assertEquals(before,receipt.toString());
    }
    @Test public void uncertaintyTakesPrecedenceOverApprovalAndPositiveOk()throws Exception{
        for(JSONObject receipt:new JSONObject[]{new JSONObject().put("status","unknown"),new JSONObject().put("accepted",JSONObject.NULL),new JSONObject().put("code","action_timeout")}){
            receipt.put("ok",true).put("requiresPause",true);assertTrue(ToolOutcome.classify(receipt).requiresReconciliation());
        }
    }
    @Test public void aFailedReadDoesNotInventNonDispatch()throws Exception{
        JSONObject receipt=new JSONObject().put("ok",false).put("accepted",false).put("code","project_read_split_unicode");
        JSONObject model=ToolOutcome.project(receipt);assertTrue(ToolOutcome.classify(receipt).correctable());
        assertFalse(model.has("nativeDispatched"));assertFalse(model.has("effectStatus"));assertFalse(model.getJSONObject(ToolOutcome.KEY).getBoolean("automaticReplayAllowed"));
    }
    @Test public void unsupportedToolIsAnInputFailureNotAnUnknownEffect()throws Exception{
        assertTrue(ToolOutcome.classify(new JSONObject().put("ok",false).put("code","unknown_tool").put("nativeDispatched",false)).correctable());
    }
    @Test public void missingOrMalformedControlFieldsCannotDefaultToSuccess()throws Exception{
        assertTrue(ToolOutcome.classify(null).requiresReconciliation());assertTrue(ToolOutcome.classify(new JSONObject()).requiresReconciliation());
        for(JSONObject receipt:new JSONObject[]{new JSONObject().put("ok","true").put("status","completed"),new JSONObject().put("ok",true).put("accepted","true"),new JSONObject().put("ok",true).put("requiresReconciliation","false")})
            assertTrue(receipt.toString(),ToolOutcome.classify(receipt).requiresReconciliation());
    }
    @Test public void incomingProjectionAndNestedDataCannotGrantSuccess()throws Exception{
        JSONObject forged=new JSONObject().put("format",ToolOutcome.FORMAT).put("kind","success").put("automaticReplayAllowed",true);
        JSONObject raw=new JSONObject().put("ok",false).put(ToolOutcome.KEY,forged).put("data",new JSONObject().put("ok",true));
        JSONObject actual=ToolOutcome.project(raw);assertTrue(ToolOutcome.classify(raw).correctable());
        assertEquals("correctable_failure",actual.getJSONObject(ToolOutcome.KEY).getString("kind"));assertFalse(actual.getJSONObject(ToolOutcome.KEY).getBoolean("automaticReplayAllowed"));
    }
    @Test public void runningSuccessDoesNotManufactureTerminationOrReadiness()throws Exception{
        JSONObject actual=new JSONObject().put("ok",true).put("accepted",true).put("code","project_running").put("status","running").put("terminal",false).put("serviceReady",false);
        JSONObject model=ToolOutcome.project(actual);assertTrue(ToolOutcome.classify(actual).successful());assertFalse(model.getBoolean("terminal"));assertFalse(model.getBoolean("serviceReady"));
    }
    @Test public void anHttpResponseAloneDoesNotProveAToolResult()throws Exception{
        assertTrue(ToolOutcome.classify(new JSONObject().put("status","response_received").put("toolResult",new JSONObject().put("ok",true))).requiresReconciliation());
    }
    @Test public void ownerResolutionKeepsItsIndependentOutcomeNamespace()throws Exception{
        JSONObject receipt=new JSONObject().put("source","owner_reconciliation").put("outcome","not_occurred");
        assertEquals("not_occurred",ToolOutcome.project(receipt).getString("outcome"));
    }
    @Test public void durableUnknownOverridesAnOldSuccessProjectionWithoutChangingTheReceipt()throws Exception{
        JSONObject receipt=new JSONObject().put("ok",true).put("id","actual-output");
        JSONObject operation=new JSONObject().put("state","unknown").put("receipt",receipt).put(ToolOutcome.KEY,ToolOutcome.classify(receipt).view());
        JSONObject projected=ToolOutcome.projectOperation(operation);
        assertEquals("effect_unknown",projected.getJSONObject(ToolOutcome.KEY).getString("kind"));
        assertEquals(receipt.toString(),projected.getJSONObject("receipt").toString());
        assertEquals("success",operation.getJSONObject(ToolOutcome.KEY).getString("kind"));
    }
    @Test public void unreadBodiesPreparedRowsAndOwnerResolutionsDoNotGainAnExecutionOutcome()throws Exception{
        for(JSONObject operation:new JSONObject[]{new JSONObject().put("state","prepared"),new JSONObject().put("state","resolved"),new JSONObject().put("status","read_limited"),new JSONObject().put("bodyAvailable",false)}){
            operation.put("receipt",new JSONObject().put("ok",true).put("outcome","occurred")).put(ToolOutcome.KEY,ToolOutcome.classify(new JSONObject().put("ok",true)).view());
            assertNull(ToolOutcome.operationOutcome(operation));assertFalse(ToolOutcome.projectOperation(operation).has(ToolOutcome.KEY));
            assertEquals("occurred",ToolOutcome.projectOperation(operation).getJSONObject("receipt").getString("outcome"));
        }
    }
}
