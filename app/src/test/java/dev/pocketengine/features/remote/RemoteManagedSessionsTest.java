package dev.pocketengine.features.remote;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteManagedSessionsTest {
    static JSONObject task()throws Exception {
        String run=UUID.randomUUID().toString();return new JSONObject().put("id",UUID.randomUUID().toString()).put("runId",run).put("coordinatorEpoch",1).put("operationId",run)
            .put("actorId","owner").put("spaceId","private").put("feature","remote").put("status","running").put("externalId","job-1").put("updatedAt",123)
            .put("goal","PRIVATE_PROMPT_MUST_NOT_LEAK").put("events",new JSONArray().put(new JSONObject().put("text","PRIVATE_EVENT_MUST_NOT_LEAK")))
            .put("externalRefs",new JSONObject().put("profileId","computer"))
            .put("evidence",new JSONObject().put("profileId","computer").put("operationId",run).put("serviceBase","https://computer.example/private-route")
                .put("environmentId","same-label").put("workspaceIdentity","physical-workspace-1").put("worker","codex_app_server").put("protocol","codex_app_server").put("protocolVerified",true)
                .put("threadId","native-thread-1").put("executorStatus","running").put("progress",new JSONObject().put("text","PRIVATE_TOOL_BODY_MUST_NOT_LEAK")));
    }
    static JSONObject row(JSONObject task)throws Exception{return RemoteManagedSessions.row(task);}
    @Test public void sameWorkspaceLabelsOnDifferentEndpointsAndCliSessionsNeverMerge()throws Exception {
        JSONObject a=task(),b=new JSONObject(a.toString()),c=new JSONObject(a.toString());b.put("id",UUID.randomUUID().toString());c.put("id",UUID.randomUUID().toString());
        b.getJSONObject("evidence").put("serviceBase","https://other-computer.example/private-route");c.getJSONObject("evidence").put("threadId","native-thread-2");
        JSONObject ar=row(a),br=row(b),cr=row(c),index=RemoteManagedSessions.snapshot(new JSONArray().put(ar).put(br).put(cr),false,3);
        assertNotEquals(ar.getString("connectionKey"),br.getString("connectionKey"));assertNotEquals(ar.getString("sessionKey"),cr.getString("sessionKey"));assertEquals(ar.getString("contextKey"),cr.getString("contextKey"));assertEquals(2,index.getJSONArray("managedConnections").length());assertEquals("not_observed_endpoint_only",ar.getString("deviceIdentityState"));
        assertEquals(2,index.getJSONArray("managedConnections").getJSONObject(0).getJSONArray("contexts").getJSONObject(0).getJSONArray("sessions").length());
    }
    @Test public void noPromptAuditToolTextCredentialOrServicePathIsProjected()throws Exception {
        JSONObject task=task();task.getJSONObject("evidence").put("accessToken","PRIVATE_CREDENTIAL");String before=task.toString(),out=row(task).toString();assertEquals(before,task.toString());
        for(String secret:new String[]{"PRIVATE_","private-route","native-thread-1","physical-workspace-1","progress","events","goal"})assertFalse(secret,out.contains(secret));assertTrue(row(task).getBoolean("dataOnly"));assertFalse(row(task).getBoolean("authorizesExecution"));
    }
    @Test public void unknownPhysicalIdentityDoesNotMergeByEnvironmentName()throws Exception {
        JSONObject a=task(),b=new JSONObject(a.toString());b.put("id",UUID.randomUUID().toString());a.getJSONObject("evidence").remove("workspaceIdentity");b.getJSONObject("evidence").remove("workspaceIdentity");
        assertNotEquals(row(a).getString("contextKey"),row(b).getString("contextKey"));assertEquals("",row(a).getString("workspaceFingerprint"));
    }
    @Test public void onlyNativeActualKnownProtocolIdentityCanLabelASession()throws Exception {
        JSONObject task=task();assertEquals("native_protocol_observed",row(task).getString("sessionEvidence"));task.getJSONObject("evidence").put("protocolVerified","true");assertEquals("unavailable",row(task).getString("sessionEvidence"));
        task.getJSONObject("evidence").put("protocolVerified",true).put("protocol","invented_protocol");assertEquals("unavailable",row(task).getString("sessionEvidence"));
        task.getJSONObject("evidence").put("protocol","pi_rpc_v1").put("noTools",true).put("sessionId","different");assertEquals("unavailable",row(task).getString("sessionEvidence"));
        task.getJSONObject("evidence").put("sessionId","native-thread-1");assertEquals("native_protocol_observed",row(task).getString("sessionEvidence"));task.put("externalId","");assertEquals("managed_operation_observation",row(task).getString("identityState"));assertEquals("unavailable",row(task).getString("sessionEvidence"));
    }
    @Test public void mismatchedNativeSourceCannotBeAttributedToADeviceOrSession()throws Exception {
        JSONObject task=task();task.getJSONObject("evidence").put("operationId","other");JSONObject row=row(task);assertEquals("unconfirmed_local_task",row.getString("identityState"));for(String key:new String[]{"connectionKey","environmentId","jobId","worker","sessionKey"})assertFalse(row.has(key));
        assertEquals(1,RemoteManagedSessions.snapshot(new JSONArray().put(row),false,1).getJSONArray("unconfirmedLocalTasks").length());
    }
    @Test public void attentionIsOnlyObservedCountsAndStopDoesNotBecomeTerminal()throws Exception {
        JSONObject task=task();task.getJSONObject("evidence").put("pendingQuestions",new JSONArray().put(new JSONObject().put("requestId","q1").put("question","PRIVATE_QUESTION"))).put("pendingApprovals",new JSONArray().put(new JSONObject().put("requestId","a1").put("command","PRIVATE_COMMAND")));
        JSONObject row=row(task);assertEquals("input_observed",row.getJSONObject("attention").getString("kind"));assertEquals(1,row.getJSONObject("attention").getInt("questionsObserved"));assertFalse(row.toString().contains("PRIVATE_"));
        task.put("cancelRequested",true);row=row(task);assertEquals("stop_requested_unconfirmed",row.getJSONObject("attention").getString("kind"));assertEquals("running",row.getString("executorStatus"));assertTrue(row.getBoolean("requiresOriginalRequery"));
    }
    @Test public void privateAndTeamOrChannelProvenanceIsNeverMixed()throws Exception {
        for(String kind:new String[]{"channel","group","reviewed_external_group","team_remote_assignment"}){JSONObject task=task().put("origin",new JSONObject().put("kind",kind));assertNull(kind,row(task));}
        assertNull(row(task().put("actorId","another")));assertNull(row(task().put("spaceId","group")));assertNull(row(task().put("teamRunId","team")));assertNull(row(task().put("memberId","member")));
    }
    @Test public void exactNavigationCursorChangesWithRunEpochJobNotAttentionRefresh()throws Exception {
        JSONObject task=task();String original=row(task).getString("rowIdentity");task.put("updatedAt",456);task.getJSONObject("evidence").put("executorStatus","unknown");assertEquals(original,row(task).getString("rowIdentity"));
        task.put("externalId","job-2");assertNotEquals(original,row(task).getString("rowIdentity"));task.put("externalId","job-1").put("coordinatorEpoch",2);assertNotEquals(original,row(task).getString("rowIdentity"));
    }
    @Test public void missingExternalRegistryAndBoundedHistoryRemainExplicitlyUnavailable()throws Exception {
        JSONObject index=RemoteManagedSessions.snapshot(new JSONArray(),true,200);assertTrue(index.getBoolean("olderLocalTasksNotScanned"));assertEquals(200,index.getInt("localTaskWindow"));assertEquals("unavailable_no_registry_adapter",index.getJSONObject("externalClaimed").getString("state"));assertEquals("unavailable_no_registry_adapter",index.getJSONObject("externalDiscovery").getString("state"));assertFalse(index.getBoolean("authorizesExecution"));
    }
}
