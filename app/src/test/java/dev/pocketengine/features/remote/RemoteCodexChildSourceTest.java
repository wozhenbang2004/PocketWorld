package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Official pinned native public observations only; no child session/control authority. */
public final class RemoteCodexChildSourceTest {
    static JSONObject source()throws Exception {
        return new JSONObject().put("taskId","task").put("runId","run").put("coordinatorEpoch",1).put("profileId","computer").put("serviceBase","https://computer.example").put("jobId","job").put("jobOperationId","operation").put("environmentId","workspace")
            .put("worker","codex_app_server").put("protocol","codex_app_server").put("threadId","parent-thread").put("turnId","parent-turn")
            .put("modelRuntime",new JSONObject().put("protocol","native_model_runtime_v1").put("worker","codex_app_server").put("threadId","parent-thread").put("turnId","parent-turn").put("runtimeVersion",RemoteJobEvents.CODEX_CHILD_VERSION));
    }
    static JSONObject event(String child)throws Exception {
        return new JSONObject().put("kind","child_text").put("nativeType","item/agentMessage/delta").put("itemId","same-item").put("text","public received text").put("textForm","received_public_delta_not_complete_message")
            .put("childSourceProtocol",RemoteJobEvents.CODEX_CHILD_SOURCE_PROTOCOL).put("nativeLineageVersion",RemoteJobEvents.CODEX_CHILD_VERSION).put("nativeParentProof","thread_started_direct_subagent_v1")
            .put("nativeParentThreadId","parent-thread").put("nativeParentTurnId","parent-turn").put("nativeChildThreadId",child).put("nativeChildTurnId","child-turn").put("driverObservationIndex",1).put("sequence",1).put("observedAt",1234);
    }
    static JSONObject withoutText(JSONObject row){row.remove("text");row.remove("textForm");return row;}
    @Test public void exactNativeTwoChildSourcesAreDistinctBodyFreeAndNeverGrantSessionControl()throws Exception {
        JSONObject source=source(),a=RemoteJobEvents.sourceView(event("child-a"),source),b=RemoteJobEvents.sourceView(event("child-b"),source);
        assertEquals("child",a.getString("kind"));assertNotEquals(a.getString("key"),b.getString("key"));assertEquals("child-a",a.getString("nativeChildThreadId"));assertFalse(a.toString().contains("public received text"));assertFalse(a.getBoolean("executionAuthorized"));assertFalse(a.getBoolean("childSessionControl"));assertFalse(a.getBoolean("completeTranscript"));assertFalse(a.getBoolean("providerReplayIdentity"));
    }
    @Test public void originalWorkerVersionProtocolAndParentThreadTurnMustAllMatch()throws Exception {
        for(String key:new String[]{"worker","protocol","threadId","turnId"}){JSONObject source=source().put(key,"foreign");assertThrows(key,SecurityException.class,()->RemoteJobEvents.sourceView(event("child-a"),source));}
        for(String key:new String[]{"nativeLineageVersion","nativeParentProof","nativeParentThreadId","nativeParentTurnId"}){JSONObject event=event("child-a").put(key,"foreign");assertThrows(key,SecurityException.class,()->RemoteJobEvents.sourceView(event,source()));}
        JSONObject source=source();source.getJSONObject("modelRuntime").put("runtimeVersion","0.165.0");assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(event("child-a"),source));assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(event("parent-thread"),source()));
    }
    @Test public void requestedModelCatalogOrUnboundRuntimeCannotPretendToBeActualNativeVersion()throws Exception {
        JSONObject requested=source();requested.remove("modelRuntime");requested.put("modelOptions",new JSONObject().put("runtimeVersion",RemoteJobEvents.CODEX_CHILD_VERSION));assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(event("child-a"),requested));
        JSONObject unbound=source();unbound.getJSONObject("modelRuntime").put("threadId","foreign");assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(event("child-a"),unbound));
        JSONObject scope=RemoteJobEvents.scope(source(),"a".repeat(64));assertEquals(RemoteJobEvents.CODEX_CHILD_VERSION,scope.getString("nativeRuntimeVersion"));assertEquals("parent-turn",scope.getString("turnId"));assertEquals("child",RemoteJobEvents.sourceView(event("child-a"),scope).getString("kind"));
    }
    @Test public void thinkingUsageQuestionsApprovalAndExtraBodyFieldsAreNotPublicChildEvents()throws Exception {
        for(String method:new String[]{"item/reasoning/textDelta","item/reasoning/summaryTextDelta","thread/tokenUsage/updated","item/tool/requestUserInput","item/commandExecution/requestApproval"}){JSONObject event=event("child-a").put("nativeType",method);assertThrows(method,SecurityException.class,()->RemoteJobEvents.sourceView(event,source()));}
        for(String field:new String[]{"thinking","reasoning","command","counters","approvalId","childResume"}){JSONObject event=event("child-a").put(field,"MUST_NOT_RENDER");assertThrows(field,SecurityException.class,()->RemoteJobEvents.sourceView(event,source()));}
        assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(event("child-a").put("textForm","claimed_complete"),source()));
    }
    @Test public void sourceManifestPublicToolCompleteTextAndChildStatusStayReadOnly()throws Exception {
        JSONObject manifest=withoutText(event("child-a")).put("kind","child_source").put("nativeType","thread/started").put("itemId","child-a");manifest.remove("nativeChildTurnId");assertEquals("child",RemoteJobEvents.sourceView(manifest,source()).getString("kind"));
        JSONObject tool=withoutText(event("child-a")).put("kind","tool").put("nativeType","item/started").put("tool","commandExecution").put("status","started");assertFalse(RemoteJobEvents.sourceView(tool,source()).getBoolean("executionAuthorized"));
        JSONObject complete=event("child-a").put("nativeType","item/completed").put("textForm","received_complete_public_message");assertEquals("child",RemoteJobEvents.sourceView(complete,source()).getString("kind"));
        JSONObject status=withoutText(event("child-a")).put("kind","child_status").put("nativeType","turn/completed").put("status","completed").put("itemId","child-turn");assertFalse(RemoteJobEvents.sourceView(status,source()).getBoolean("childSessionControl"));
    }
    @Test public void partialMarkersMalformedIdentifiersAndFakeObservationNumbersNeverFallBackToParent()throws Exception {
        assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(new JSONObject().put("nativeChildThreadId","child-a"),source()));
        for(Object id:new Object[]{"bad\nidentity","",JSONObject.NULL,12}){JSONObject event=event("child-a").put("nativeChildThreadId",id);assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(event,source()));}
        for(Object n:new Object[]{true,0,-1,1.5,"1"}){JSONObject event=event("child-a").put("driverObservationIndex",n);assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(event,source()));}
        assertEquals("main_or_unspecified",RemoteJobEvents.sourceView(new JSONObject().put("kind","notice").put("summary","No native child source available"),source()).getString("kind"));
    }
    @Test public void authenticatedOriginalPageKeepsSourceRowsAndCursorWithoutReclassifyingThemAsParentResult()throws Exception {
        JSONObject scope=RemoteJobEvents.scope(source(),"a".repeat(64)),request=RemoteJobEvents.request(null,scope),page=RemoteJobEventsTest.page(request,RemoteJobEventsTest.GEN,1,2,2);
        for(String field:new String[]{"nativeProtocol","threadId","turnId","nativeRuntimeVersion"})page.put(field,scope.get(field));
        page.put("events",new JSONArray().put(event("child-a")).put(event("child-b").put("sequence",2).put("driverObservationIndex",2)));
        JSONObject committed=RemoteJobEvents.merge(null,RemoteJobEvents.received(scope,request,page));assertEquals(2,committed.getLong("nextSequence"));assertEquals(2,committed.getJSONArray("events").length());assertFalse(committed.has("output"));assertFalse(committed.has("usage"));assertFalse(committed.getJSONObject("scope").has("modelOptions"));
        JSONObject wrong=new JSONObject(page.toString()).put("nativeRuntimeVersion","0.165.0");assertThrows(SecurityException.class,()->RemoteJobEvents.received(scope,request,wrong));assertEquals(2,committed.getLong("nextSequence"));
    }
}
