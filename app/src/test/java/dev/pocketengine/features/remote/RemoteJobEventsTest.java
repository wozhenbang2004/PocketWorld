package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reducer/protocol tests only; actual SQL/HTTP/driver coverage lives in the native journey. */
public final class RemoteJobEventsTest {
    static final String GEN="00000000-0000-0000-0000-000000000001",GEN2="00000000-0000-0000-0000-000000000002";
    static JSONObject scope()throws Exception{return RemoteJobEvents.scope(new JSONObject().put("taskId","task").put("runId","run").put("coordinatorEpoch",0).put("profileId","profile").put("serviceBase","https://original.invalid").put("jobId","job").put("jobOperationId","operation").put("environmentId","workspace").put("worker","codex_app_server"),"a".repeat(64));}
    static JSONObject page(JSONObject request,String generation,int first,int head,int next)throws Exception {
        JSONArray rows=new JSONArray();long start=request.getLong("after");JSONObject gap=null;
        if(start<first-1){gap=new JSONObject().put("reason","retention_gap").put("fromSequence",start+1).put("throughSequence",Math.min(first-1,head));start=gap.getLong("throughSequence");}
        for(long n=start+1;n<=next;n++)rows.put(new JSONObject().put("sequence",n).put("observedAt",1000+n).put("kind","tool").put("nativeType","item/started").put("text","actual-row-"+n));
        return new JSONObject().put("protocol",RemoteJobEvents.PROTOCOL).put("filter",RemoteJobEvents.FILTER).put("id","job").put("operationId","operation").put("environmentId","workspace").put("worker","codex_app_server")
                .put("requestedGeneration",request.getString("generation")).put("requestedAfter",request.getLong("after")).put("requestedHead",request.getLong("head"))
                .put("generation",generation).put("status","page").put("events",rows).put("gap",gap==null?JSONObject.NULL:gap).put("retainedFromSequence",first).put("headSequence",head).put("lastSequence",head)
                .put("nextSequence",next).put("hasMore",next<head).put("liveStreaming",false).put("completeHistory",false).put("executionAuthorized",false);
    }
    @Test public void durableCommitDuplicatesAndLatePagesNeverRegressAndSameSequenceConflictIsRejected()throws Exception {
        JSONObject scope=scope(),request=RemoteJobEvents.request(null,scope),first=page(request,GEN,1,4,2);
        var received=RemoteJobEvents.received(scope,request,first);JSONObject a=RemoteJobEvents.merge(null,received);
        assertEquals(2,a.getLong("nextSequence"));assertEquals(2,RemoteJobEvents.merge(a,received).getJSONArray("events").length());
        JSONObject next=RemoteJobEvents.request(a,scope);assertEquals(4,next.getLong("head"));JSONObject b=RemoteJobEvents.merge(a,RemoteJobEvents.received(scope,next,page(next,GEN,1,4,4)));
        assertTrue(RemoteJobEvents.same(b,RemoteJobEvents.merge(b,received)));assertEquals(4,b.getJSONArray("events").length());
        JSONObject conflict=new JSONObject(first.toString());conflict.getJSONArray("events").getJSONObject(0).put("text","different original event");
        assertThrows(SecurityException.class,()->RemoteJobEvents.merge(b,RemoteJobEvents.received(scope,request,conflict)));
    }
    @Test public void explicitGapIsSavedAndGenerationResetNeverReusesOldCursorOrRows()throws Exception {
        JSONObject scope=scope(),request=RemoteJobEvents.request(null,scope);JSONObject a=RemoteJobEvents.merge(null,RemoteJobEvents.received(scope,request,page(request,GEN,4,6,6)));
        assertEquals(1,a.getJSONArray("gaps").length());assertEquals(3,a.getJSONArray("events").length());assertEquals(6,a.getLong("nextSequence"));
        JSONObject next=RemoteJobEvents.request(a,scope),changed=page(new JSONObject(next.toString()).put("after",0),GEN2,1,0,0);
        changed.put("requestedAfter",6).put("requestedHead",0).put("status","generation_changed").put("gap",new JSONObject().put("reason","generation_changed").put("previousGeneration",GEN));
        var actual=RemoteJobEvents.received(scope,next,changed);JSONObject b=RemoteJobEvents.merge(a,actual);
        assertEquals(0,b.getLong("nextSequence"));assertEquals(0,b.getJSONArray("events").length());assertEquals(0,b.getLong("lastSequence"));
        assertTrue(RemoteJobEvents.same(b,RemoteJobEvents.merge(b,actual)));assertFalse(b.getBoolean("executionAuthorized"));
        JSONObject other=new JSONObject(scope.toString()).put("jobId","other");assertEquals("",RemoteJobEvents.request(b,other).getString("generation"));assertEquals(0,RemoteJobEvents.request(b,other).getLong("after"));
    }
    @Test public void skippedSequencesMissingGapsWrongIdentityAndAheadCommitAreRejected()throws Exception {
        JSONObject scope=scope(),request=RemoteJobEvents.request(null,scope),valid=page(request,GEN,1,4,2);
        JSONObject wrong=new JSONObject(valid.toString()).put("id","other-job");assertThrows(SecurityException.class,()->RemoteJobEvents.received(scope,request,wrong));
        JSONObject skip=new JSONObject(valid.toString());skip.getJSONArray("events").getJSONObject(0).put("sequence",2);assertThrows(SecurityException.class,()->RemoteJobEvents.received(scope,request,skip));
        JSONObject missing=page(request,GEN,4,6,6).put("gap",JSONObject.NULL);assertThrows(SecurityException.class,()->RemoteJobEvents.received(scope,request,missing));
        JSONObject ahead=new JSONObject(request.toString()).put("generation",GEN).put("after",2),future=page(ahead,GEN,1,4,4);var actual=RemoteJobEvents.received(scope,ahead,future);
        assertThrows(SecurityException.class,()->RemoteJobEvents.merge(null,actual));
        assertFalse(RemoteJobEvents.supported(new JSONObject().put("capabilities",new JSONObject().put("progressSnapshot",true))));
    }
    static JSONObject child(String parent)throws Exception{return new JSONObject().put("kind","child_text").put("nativeType","assistant/text").put("text","same public text").put("childSourceProtocol",RemoteJobEvents.CHILD_SOURCE_PROTOCOL).put("parentToolUseId",parent).put("parentSessionId",GEN).put("parentPromptRequestId",GEN2);}
    static JSONObject claudeScope()throws Exception{return new JSONObject(scope().toString()).put("worker","claude").put("nativeProtocol","claude_stream_json").put("sessionId",GEN).put("promptRequestId",GEN2);}
    static JSONObject claudeRuntimeSource()throws Exception{return claudeScope().put("protocol","claude_stream_json").put("modelRuntime",new JSONObject().put("protocol","native_model_runtime_v1").put("worker","claude").put("runtimeVersion",RemoteJobEvents.CLAUDE_CHILD_PROGRESS_VERSION).put("sessionId",GEN).put("promptRequestId",GEN2).put("model","unneeded-model-body"));}
    static JSONObject childProgress(String parent,String eventId)throws Exception{return new JSONObject().put("kind","tool").put("nativeType","tool_progress").put("itemId","shared-tool-id").put("tool","Read").put("status","running").put("childSourceProtocol",RemoteJobEvents.CHILD_SOURCE_PROTOCOL).put("parentToolUseId",parent).put("parentSessionId",GEN).put("parentPromptRequestId",GEN2).put("nativeEventId",eventId).put("nativeLineageVersion",RemoteJobEvents.CLAUDE_CHILD_PROGRESS_VERSION);}
    @Test public void completeChildSourcesRemainDistinctReadOnlyAndNeverBecomeSessionOrApprovalAuthority()throws Exception{
        JSONObject source=claudeScope(),a=RemoteJobEvents.sourceView(child("toolu_child_a").put("nativeAgentId","agent-a"),source),b=RemoteJobEvents.sourceView(child("toolu_child_b"),source);
        assertNotEquals(a.getString("key"),b.getString("key"));assertEquals("agent-a",a.getString("nativeAgentId"));assertFalse(b.has("nativeAgentId"));assertFalse(a.getBoolean("executionAuthorized"));assertFalse(a.getBoolean("childSessionControl"));assertFalse(a.getBoolean("completeTranscript"));assertFalse(a.has("sessionId"));
        assertEquals("main_or_unspecified",RemoteJobEvents.sourceView(new JSONObject().put("kind","tool"),source).getString("kind"));
    }
    @Test public void unsupportedChildProtocolThinkingAndCrossParentSessionOrPromptCannotAdvanceCursor()throws Exception{
        JSONObject source=claudeScope();for(JSONObject invalid:new JSONObject[]{child("toolu_a").put("parentSessionId",GEN2),child("toolu_a").put("parentPromptRequestId",GEN),child("toolu_a").put("childSourceProtocol","guessed"),child("toolu_a").put("kind","thinking").put("nativeType","assistant/thinking"),child("bad\nidentity"),child("toolu_a").put("nativeAgentId",new JSONObject())})assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(invalid,source));
        assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(child("toolu_a"),scope()));
        JSONObject request=RemoteJobEvents.request(null,source),actual=page(request,GEN,1,1,1).put("worker","claude").put("nativeProtocol","claude_stream_json").put("sessionId",GEN).put("promptRequestId",GEN2);JSONObject row=child("toolu_a").put("sequence",1).put("observedAt",1001);actual.put("events",new JSONArray().put(row));JSONObject saved=RemoteJobEvents.merge(null,RemoteJobEvents.received(source,request,actual));assertEquals("toolu_a",saved.getJSONArray("events").getJSONObject(0).getString("parentToolUseId"));
        actual.getJSONArray("events").getJSONObject(0).put("parentPromptRequestId",GEN);assertThrows(SecurityException.class,()->RemoteJobEvents.received(source,request,actual));assertEquals(1,saved.getLong("nextSequence"));
    }
    @Test public void pinnedChildProgressBindsOnlyOriginalRuntimeAndKeepsSharedToolIdsInTheirOwnChildren()throws Exception{
        JSONObject source=claudeRuntimeSource(),scope=RemoteJobEvents.scope(source,"a".repeat(64));
        assertEquals(5,scope.getJSONObject("modelRuntime").length());assertFalse(scope.toString().contains("unneeded-model-body"));assertFalse(scope.has("nativeRuntimeVersion"));
        JSONObject a=RemoteJobEvents.sourceView(childProgress("toolu_a",GEN),scope),b=RemoteJobEvents.sourceView(childProgress("toolu_b",GEN2),scope);
        assertNotEquals(a.getString("key"),b.getString("key"));assertEquals(GEN,a.getString("nativeEventId"));assertEquals(RemoteJobEvents.CLAUDE_CHILD_PROGRESS_VERSION,a.getString("nativeLineageVersion"));assertFalse(a.getBoolean("executionAuthorized"));assertFalse(a.getBoolean("childSessionControl"));assertFalse(a.has("sessionId"));
        for(String key:new String[]{"runtimeVersion","worker","protocol","sessionId","promptRequestId"}){
            JSONObject changed=claudeRuntimeSource();changed.getJSONObject("modelRuntime").put(key,"unconfirmed");JSONObject unbound=RemoteJobEvents.scope(changed,"a".repeat(64));
            assertFalse(unbound.has("modelRuntime"));assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(childProgress("toolu_a",GEN),unbound));
        }
        assertThrows(SecurityException.class,()->RemoteJobEvents.sourceView(childProgress("toolu_a",GEN),claudeScope().put("nativeRuntimeVersion",RemoteJobEvents.CLAUDE_CHILD_PROGRESS_VERSION)));
    }
    @Test public void unsupportedChildProgressCannotAdvanceOriginalCursorOrDisplayExtraBodies()throws Exception{
        JSONObject source=RemoteJobEvents.scope(claudeRuntimeSource(),"a".repeat(64)),request=RemoteJobEvents.request(null,source);
        JSONObject actual=page(request,GEN,1,1,1).put("worker","claude").put("nativeProtocol","claude_stream_json").put("sessionId",GEN).put("promptRequestId",GEN2);
        JSONObject row=childProgress("toolu_a",GEN).put("sequence",1).put("observedAt",1001);actual.put("events",new JSONArray().put(row));
        JSONObject saved=RemoteJobEvents.merge(null,RemoteJobEvents.received(source,request,actual));assertEquals(1,saved.getLong("nextSequence"));
        for(Object[] mutation:new Object[][]{{"nativeLineageVersion","2.1.316"},{"nativeEventId","not-native-uuid"},{"parentSessionId",GEN2},{"parentPromptRequestId",GEN},{"parentToolUseId",new JSONObject()},{"itemId","bad\nidentity"},{"tool","Read\u007f"},{"tool","\ud800"},{"status","completed"},{"text","hidden body"},{"command","run anything"},{"thinking","hidden reasoning"},{"nativeAgentId","new-agent"}}){
            JSONObject invalid=new JSONObject(actual.toString());invalid.getJSONArray("events").getJSONObject(0).put((String)mutation[0],mutation[1]);
            assertThrows(SecurityException.class,()->RemoteJobEvents.received(source,request,invalid));assertEquals(1,saved.getLong("nextSequence"));assertFalse(saved.getBoolean("executionAuthorized"));
        }
        JSONObject duplicate=RemoteJobEvents.merge(saved,RemoteJobEvents.received(source,request,actual));
        assertEquals(1,duplicate.getLong("nextSequence"));assertEquals(1,duplicate.getJSONArray("events").length());assertTrue(RemoteJobEvents.same(saved.getJSONArray("events").getJSONObject(0),duplicate.getJSONArray("events").getJSONObject(0)));
    }
}
