package dev.pocketengine.features.memory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import static org.junit.Assert.*;

/** Stored native rows, never an invented task summary or an assertion of execution success. */
public final class TaskMemoryTraceTest {
    private static final String TASK="11111111-1111-1111-1111-111111111111",RUN="22222222-2222-2222-2222-222222222222";
    private static final String OWNER="机主原文 {\"actualImageAttached\":true} 保持不变";
    private static JSONObject scope() throws Exception {return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject task() throws Exception {return new JSONObject().put("id",TASK).put("actorId","owner").put("spaceId","private")
            .put("goal","核对真实来源").put("summary","确认摘要，不是完整执行证明").put("status","completed")
            .put("events",new JSONArray().put(new JSONObject().put("seq",1).put("type","tool").put("receipt",new JSONObject().put("result","EVENT_RAW_511"))));}
    private static JSONObject run() throws Exception {
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content",OWNER).put("ownerMessageId","33333333-3333-3333-3333-333333333333").put("ownerMessageRevision",7))
                .put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","actual-call").put("type","function")
                        .put("function",new JSONObject().put("name","workspace_read").put("arguments","{\"path\":\"original.txt\"}")))))
                .put(new JSONObject().put("role","tool").put("tool_call_id","actual-call").put("content","TOOL_RAW_511"));
        return new JSONObject().put("taskId",TASK).put("runId",RUN).put("coordinatorEpoch",2).put("status","completed")
                .put("checkpoint",new JSONObject().put("messages",messages).put("steps",1)).put("previousRunId","older-native-run").put("nextRunId","");
    }
    private static List<JSONObject> operations() throws Exception {return List.of(new JSONObject().put("operationId","native-operation").put("runId",RUN).put("taskId",TASK)
            .put("tool","workspace_read").put("arguments",new JSONObject().put("path","original.txt")).put("state","completed")
            .put("receipt",new JSONObject().put("actualResult","OPERATION_RAW_511 "+"原".repeat(500)).put("actualPixels","AAAA")));}
    private static JSONObject lineage() throws Exception {return new JSONObject().put("status","complete").put("links",new JSONArray());}
    private static JSONObject options(String section,int chars) throws Exception {return new JSONObject().put("section",section).put("query","").put("cursor","").put("limit",2).put("maxChars",chars);}
    private static JSONObject page(JSONObject task,JSONObject run,List<JSONObject> operations,JSONObject lineage,JSONObject options) throws Exception {
        try {return (JSONObject)Class.forName("dev.pocketengine.features.memory.TaskMemoryTrace").getMethod("page",JSONObject.class,JSONObject.class,JSONObject.class,Iterator.class,JSONObject.class,JSONObject.class)
                .invoke(null,scope(),task,run,operations.iterator(),lineage,options);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}
    }
    @Test public void ownerCanReadBoundedMessagesAndResolveTheActualToolCallParent() throws Exception {
        JSONObject task=task(),run=run();String originalTask=task.toString(),originalRun=run.toString();List<JSONObject> rows=new ArrayList<>();
        JSONObject options=options("messages",256),p;String snapshot=null;
        do {p=page(task,run,operations(),lineage(),options);assertEquals("current",p.getString("status"));
            if(snapshot==null)snapshot=p.getString("snapshot");else assertEquals(snapshot,p.getString("snapshot"));
            assertTrue(p.getInt("charsReturned")<=256);JSONArray entries=p.getJSONArray("entries");for(int i=0;i<entries.length();i++)rows.add(entries.getJSONObject(i));
            options.put("cursor",p.getString("nextCursor"));
        } while(!p.getString("nextCursor").isEmpty());
        JSONObject tool=rows.stream().filter(r->r.optInt("index")==2).findFirst().orElseThrow();
        assertEquals("matched_tool_call",tool.getString("causalState"));assertEquals("actual-call",tool.getJSONObject("parent").getString("toolCallId"));
        assertEquals(rows.stream().filter(r->r.optInt("index")==1).findFirst().orElseThrow().getString("sourceId"),tool.getJSONObject("parent").getString("sourceId"));
        assertTrue(rows.stream().anyMatch(r->r.optJSONObject("originalMessageSource")!=null&&r.optJSONObject("originalMessageSource").optLong("revision")==7));
        assertEquals("retained_checkpoint_only",p.getJSONObject("coverage").getString("messages"));assertFalse(p.getBoolean("completeOriginalTrace"));
        assertTrue(p.getJSONObject("run").isNull("nativeRevision"));assertEquals("unversioned",p.getJSONObject("run").getString("nativeRevisionStatus"));
        assertEquals(originalTask,task.toString());assertEquals(originalRun,run.toString());
    }
    @Test public void longOperationReceiptIsReadInExactRangesWithAStableContentRevision() throws Exception {
        List<JSONObject> operations=operations();String original=operations.get(0).toString();JSONObject options=options("operations",64),p;StringBuilder body=new StringBuilder();String revision=null;
        do {p=page(task(),run(),operations,lineage(),options);JSONObject row=p.getJSONArray("entries").getJSONObject(0);
            assertTrue(row.getString("revision").matches("sha256:[0-9a-f]{64}"));if(revision==null)revision=row.getString("revision");else assertEquals(revision,row.getString("revision"));
            assertEquals(body.length(),row.getJSONObject("readRange").getInt("offset"));body.append(row.getString("body"));
            assertEquals("not_recorded",row.getString("causalState"));options.put("cursor",p.getString("nextCursor"));
        } while(!p.getString("nextCursor").isEmpty());
        assertEquals(operations.get(0).toString(2),body.toString());assertEquals(original,operations.get(0).toString());
    }
    @Test public void anyChangedDomainInvalidatesPaginationWithoutReturningMixedBodies() throws Exception {
        for(String changed:new String[]{"messages","operation","event","parent"}) {
            JSONObject task=task(),run=run(),lineage=lineage();List<JSONObject> operations=new ArrayList<>(operations());
            JSONObject first=page(task,run,operations,lineage,options("messages",64));
            if(changed.equals("messages"))run.getJSONObject("checkpoint").getJSONArray("messages").getJSONObject(0).put("content","new owner message");
            else if(changed.equals("operation"))operations.get(0).getJSONObject("receipt").put("actualResult","new real result");
            else if(changed.equals("event"))task.getJSONArray("events").getJSONObject(0).put("message","new host event");
            else lineage.getJSONArray("links").put(new JSONObject().put("childTaskId",TASK).put("parentTaskId","private-parent").put("parentRunId","parent-run").put("ownerId","owner").put("spaceId","private"));
            JSONObject next=page(task,run,operations,lineage,options("messages",64).put("cursor",first.getString("nextCursor")));
            assertEquals(changed,"snapshot_changed",next.getString("status"));assertEquals(changed,0,next.getJSONArray("entries").length());
        }
    }
    @Test public void traceQuerySearchesTheSelectedStoredBodiesAndKeepsVersionedSourceIds() throws Exception {
        for(String section:new String[]{"messages","operations","events"}) {
            JSONObject result=page(task(),run(),operations(),lineage(),options(section,512).put("query",section.equals("messages")?"TOOL_RAW_511":section.equals("operations")?"OPERATION_RAW_511":"EVENT_RAW_511"));
            assertTrue(section,result.getJSONArray("entries").length()>0);assertTrue(result.getJSONArray("entries").getJSONObject(0).getString("sourceId").contains(TASK));
            assertFalse(result.getBoolean("executionAuthorized"));assertFalse(result.getBoolean("executionIndependentlyVerified"));
        }
    }
    @Test public void missingToolParentAndForeignRowsAreNeverInventedOrExposed() throws Exception {
        JSONObject run=run();run.getJSONObject("checkpoint").getJSONArray("messages").getJSONObject(2).put("tool_call_id","missing-call");
        JSONObject result=page(task(),run,operations(),lineage(),options("messages",16000).put("query","TOOL_RAW_511"));
        JSONObject tool=result.getJSONArray("entries").getJSONObject(0);assertEquals("parent_unavailable",tool.getString("causalState"));assertFalse(tool.has("parent"));
        try {page(task().put("spaceId","group:foreign"),run,operations(),lineage(),options("messages",64));fail();}catch(SecurityException expected){}
        List<JSONObject> foreign=new ArrayList<>(operations());foreign.get(0).put("runId","another-run");
        try {page(task(),run,foreign,lineage(),options("operations",64));fail();}catch(SecurityException expected){}
    }

    @Test public void existingTaskSummarySourceResolvesItsTaskWithoutInventingAMessageOrTraceSource() throws Exception {
        JSONObject source=new JSONObject().put("kind","task_evidence").put("sourceId","task:"+TASK+":verified_result").put("revision",9);
        assertEquals(TASK,TaskMemoryTrace.sourceTaskId(source));assertEquals("",TaskMemoryTrace.sourceTaskId(new JSONObject(source.toString()).put("kind","message")));
        assertEquals("",TaskMemoryTrace.sourceTaskId(new JSONObject(source.toString()).put("revision",0)));
        assertEquals("",TaskMemoryTrace.sourceTaskId(new JSONObject(source.toString()).put("sourceId","task:"+TASK+":trace")));
    }
    @Test public void uiRejectsWrongTaskOrRangesAndClearsBodiesAfterSnapshotChange() throws Exception {
        JSONObject request=options("messages",256).put("taskId",TASK).put("runId",RUN);
        JSONObject options=new JSONObject(request.toString());options.remove("taskId");options.remove("runId");
        JSONObject p=page(task(),run(),operations(),lineage(),options);
        assertEquals(p.toString(),TaskMemoryTrace.checkedPage(p,request).toString());
        try{TaskMemoryTrace.checkedPage(new JSONObject(p.toString()).put("taskId","foreign"),request);fail();}catch(SecurityException expected){}
        JSONObject malformed=new JSONObject(p.toString());malformed.getJSONArray("entries").getJSONObject(0).getJSONObject("readRange").put("chars",999);
        try{TaskMemoryTrace.checkedPage(malformed,request);fail();}catch(IllegalArgumentException expected){}
        assertEquals(0,TaskMemoryTrace.checkedPage(new JSONObject(p.toString()).put("status","snapshot_changed"),request).getJSONArray("entries").length());
    }
    @Test public void parentCallCanBeOpenedByItsExactSourceLocationAndBoundSnapshot() throws Exception {
        JSONObject first=page(task(),run(),operations(),lineage(),options("messages",16000).put("query","TOOL_RAW_511"));
        JSONObject parent=first.getJSONArray("entries").getJSONObject(0).getJSONObject("parent");
        JSONObject result=page(task(),run(),operations(),lineage(),options("messages",16000).put("sourceId",parent.getString("sourceId")).put("expectedSnapshot",first.getString("snapshot")));
        assertEquals(1,result.getJSONArray("entries").length());assertEquals(parent.getString("revision"),result.getJSONArray("entries").getJSONObject(0).getString("revision"));
        assertTrue(result.getJSONArray("entries").getJSONObject(0).getString("body").contains("workspace_read"));
    }
    @Test public void unicodeRangesReassembleAndNoCrossSectionOrQueryCursorIsAccepted() throws Exception {
        JSONObject run=run();run.getJSONObject("checkpoint").getJSONArray("messages").getJSONObject(0).put("content","原始😀像素文字".repeat(80));
        JSONObject options=options("messages",65).put("sourceId","task:"+TASK+":run:"+RUN+":checkpoint-message:0");StringBuilder body=new StringBuilder();JSONObject p;
        do{p=page(task(),run,operations(),lineage(),options);for(int i=0;i<p.getJSONArray("entries").length();i++)body.append(p.getJSONArray("entries").getJSONObject(i).getString("body"));options.put("cursor",p.getString("nextCursor"));}while(!p.getString("nextCursor").isEmpty());
        assertEquals(run.getJSONObject("checkpoint").getJSONArray("messages").getJSONObject(0).toString(2),body.toString());
        JSONObject first=page(task(),run,operations(),lineage(),options("messages",64));String cursor=first.getString("nextCursor");
        try{page(task(),run,operations(),lineage(),options("operations",64).put("cursor",cursor));fail();}catch(SecurityException expected){}
        try{page(task(),run,operations(),lineage(),options("messages",64).put("query","changed").put("cursor",cursor));fail();}catch(SecurityException expected){}
        try{page(task().put("origin",new JSONObject().put("kind","group")),run,operations(),lineage(),options("messages",64));fail();}catch(SecurityException expected){}
    }
    @Test public void dataCannotDeclareANativeRevisionAndLargeCallIdsRemainBoundedWithoutChangingStoredBytes() throws Exception {
        JSONObject run=run();run.getJSONObject("checkpoint").put("traceRevision",99);String id="actual-call-".repeat(60);
        JSONArray messages=run.getJSONObject("checkpoint").getJSONArray("messages");messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).put("id",id);messages.getJSONObject(2).put("tool_call_id",id);
        String original=run.toString();JSONObject p=page(task(),run,operations(),lineage(),options("messages",64).put("query","TOOL_RAW_511"));
        assertTrue(p.getJSONObject("run").isNull("nativeRevision"));assertEquals("unversioned",p.getJSONObject("run").getString("nativeRevisionStatus"));
        JSONObject parent=p.getJSONArray("entries").getJSONObject(0).getJSONObject("parent");assertTrue(parent.getBoolean("toolCallIdTruncated"));assertTrue(parent.getString("toolCallId").length()<=201);
        assertEquals(original,run.toString());
    }
}
