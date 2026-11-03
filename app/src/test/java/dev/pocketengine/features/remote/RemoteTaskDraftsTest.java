package dev.pocketengine.features.remote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteTaskDraftsTest {
    private static final String GENERATION="a".repeat(64);
    private static JSONObject pi()throws Exception {
        return new JSONObject().put("taskId","task").put("runId","run").put("coordinatorEpoch",1).put("profileId","computer").put("serviceBase","https://fixture.invalid")
                .put("jobId","job").put("jobOperationId","operation").put("environmentId","workspace").put("worker","pi").put("protocol","pi_rpc_v1")
                .put("protocolVerified",true).put("noTools",true)
                .put("sessionId","session").put("threadId","session").put("promptRequestId","prompt").put("executorStatus","running");
    }
    @Test public void piLocalNextTurnIntentPinsTheNoToolsSessionButNotMutableSettlement()throws Exception {
        JSONObject source=pi(),scope=RemoteTaskDrafts.scope(source,GENERATION,"next_turn");
        assertTrue(scope.getBoolean("noTools"));assertFalse(scope.has("controlProtocolVerified"));assertFalse(scope.has("sessionIdentityConfirmed"));assertEquals(scope.getString("sessionId"),scope.getString("threadId"));
        assertFalse(scope.has("executorStatus"));assertFalse(scope.has("nativeSettled"));assertFalse(scope.has("resourcesHeld"));assertFalse(scope.has("processCleanupProof"));
        source.put("executorStatus","completed").put("nativeSettled",true).put("resourcesHeld",false).put("processCleanupProof",new JSONObject().put("processGroupStoppedVerified",true));
        assertTrue(RemoteTaskDrafts.same(scope,RemoteTaskDrafts.scope(source,GENERATION,"next_turn")));
    }
    @Test public void piWithoutNativeQueueObservationCannotSteerAndCannotChangeNoToolsSession()throws Exception {
        assertThrows(SecurityException.class,()->RemoteTaskDrafts.scope(pi(),GENERATION,"steer"));
        for(JSONObject bad:new JSONObject[]{pi().put("noTools",false),pi().put("noTools","true"),pi().put("promptRequestId",JSONObject.NULL),pi().put("sessionId","another"),pi().put("threadId",1),pi().put("promptRequestId",""),pi().put("protocolVerified","true"),pi().put("worker","claude")})
            assertThrows(bad.toString(),SecurityException.class,()->RemoteTaskDrafts.scope(bad,GENERATION,"next_turn"));
    }
    @Test public void verifiedPiQueueDraftPinsPromptWithoutCreatingATurnOrDispatchGrant()throws Exception {
        JSONObject source=pi().put("inputSteeringVerified",true),scope=RemoteTaskDrafts.scope(source,GENERATION,"steer");
        assertEquals("prompt",scope.getString("expectedPromptRequestId"));assertEquals("session",scope.getString("sessionId"));assertTrue(scope.getBoolean("noTools"));assertFalse(scope.has("expectedTurnId"));assertFalse(scope.has("turnId"));assertFalse(scope.has("controls"));
        source.put("promptRequestId","later-prompt");assertFalse(RemoteTaskDrafts.same(scope,RemoteTaskDrafts.scope(source,GENERATION,"steer")));
        assertThrows(SecurityException.class,()->RemoteTaskDrafts.scope(pi().put("inputSteeringVerified",true).put("turnId","invented"),GENERATION,"steer"));
    }
    @Test public void originalTargetAndComputerGenerationRemainExactAndNoTextIssuesAuthority()throws Exception {
        JSONObject source=pi(),scope=RemoteTaskDrafts.scope(source,GENERATION,"next_turn");
        for(String field:new String[]{"taskId","runId","jobId","jobOperationId","threadId","sessionId","profileId","environmentId"}) {
            JSONObject changed=new JSONObject(source.toString()).put(field,"changed");
            try { assertFalse(field,RemoteTaskDrafts.same(scope,RemoteTaskDrafts.scope(changed,GENERATION,"next_turn"))); }
            catch(SecurityException refused){assertTrue(field.equals("sessionId")||field.equals("threadId"));}
        }
        assertFalse(RemoteTaskDrafts.same(scope,RemoteTaskDrafts.scope(source,"b".repeat(64),"next_turn")));
        assertThrows(SecurityException.class,()->RemoteTaskDrafts.scope(source,"bad-generation","next_turn"));
        assertEquals("not permission",RemoteTaskDrafts.text("not permission"));assertFalse(scope.has("controls"));
    }
}
