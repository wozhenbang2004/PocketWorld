package dev.pocketengine.features.memory;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reopen the cited original operation version, independently of unrelated trace rows. */
public final class TaskMemoryTraceSourceTest {
    private static final String TASK="11111111-1111-1111-1111-111111111111",RUN="22222222-2222-2222-2222-222222222222",OP="original-operation";
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject task()throws Exception{return new JSONObject().put("id",TASK).put("actorId","owner").put("spaceId","private").put("events",new JSONArray());}
    private static JSONObject operation()throws Exception{return new JSONObject().put("taskId",TASK).put("runId",RUN).put("operationId",OP).put("tool","workspace_read")
            .put("arguments",new JSONObject().put("path","original.txt")).put("state","receipt")
            .put("receipt",new JSONObject().put("result","原始😀结果 "+"正文".repeat(400)).put("actualImageAttached",true).put("effect","observed").put("audit","kept"));}
    private static JSONObject quote(JSONObject task,JSONObject operation)throws Exception{
        JSONObject run=new JSONObject().put("taskId",TASK).put("runId",RUN).put("checkpoint",new JSONObject().put("messages",new JSONArray()));
        return TaskMemoryTrace.page(scope(),task,run,List.of(operation).iterator(),new JSONObject().put("links",new JSONArray()),new JSONObject().put("section","operations").put("maxChars",64)).getJSONArray("entries").getJSONObject(0);
    }
    private static JSONObject request(JSONObject task,JSONObject operation)throws Exception{
        JSONObject quote=quote(task,operation);return new JSONObject().put("taskId",TASK).put("runId",RUN).put("sourceId",quote.getString("sourceId"))
                .put("revision",quote.getString("revision")).put("offset",0).put("maxChars",65);
    }
    private static Object invoke(String name,Class<?>[] types,Object...args)throws Exception{
        try{return TaskMemoryTrace.class.getMethod(name,types).invoke(null,args);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}
    }
    private static JSONObject read(JSONObject task,JSONObject operation,JSONObject request)throws Exception{
        return (JSONObject)invoke("operationPage",new Class<?>[]{JSONObject.class,JSONObject.class,JSONObject.class,JSONObject.class},scope(),task,operation,request);
    }
    private static JSONObject checked(JSONObject page,JSONObject request)throws Exception{
        return (JSONObject)invoke("checkedOperationPage",new Class<?>[]{JSONObject.class,JSONObject.class},page,request);
    }
    @Test public void citedOperationVersionReopensInExactUnicodeRangesWithoutChangingItsStoredBody()throws Exception{
        JSONObject task=task(),operation=operation(),request=request(task,operation);String original=operation.toString();StringBuilder body=new StringBuilder();JSONObject page;
        do{
            page=read(task,operation,request);assertEquals("current",page.getString("status"));assertEquals(page.toString(),checked(page,request).toString());
            assertEquals(request.getInt("offset"),page.getJSONObject("readRange").getInt("offset"));assertTrue(page.getString("body").length()<=65);
            assertEquals(request.getString("sourceId"),page.getString("sourceId"));assertEquals(request.getString("revision"),page.getString("revision"));
            body.append(page.getString("body"));request.put("offset",page.getInt("nextOffset"));
        }while(page.getInt("nextOffset")>=0);
        assertEquals(operation.toString(2),body.toString());assertEquals(original,operation.toString());
        assertFalse(page.getBoolean("executionAuthorized"));assertFalse(page.getBoolean("executionIndependentlyVerified"));assertFalse(page.getBoolean("completeOriginalTrace"));
        assertEquals("exact_original_operation_only",page.getString("coverage"));
    }
    @Test public void aChangedCitedVersionReturnsNoReplacementBodyAndNoAutomaticNewVersion()throws Exception{
        for(String field:new String[]{"arguments","receipt","state","storedBodyFingerprint"}){
            JSONObject task=task(),operation=operation(),request=request(task,operation);operation.put(field,"changed original value");
            JSONObject page=read(task,operation,request);assertEquals(field,"source_changed",page.getString("status"));assertFalse(page.has("body"));assertFalse(page.has("nextOffset"));
            assertEquals(request.getString("revision"),page.getString("revision"));assertFalse(page.has("actualRevision"));
        }
    }
    @Test public void unrelatedTaskEventsDoNotBecomeTheRevisionOfAnUnchangedOperation()throws Exception{
        JSONObject task=task(),operation=operation(),request=request(task,operation);String old=read(task,operation,request).getString("body");
        task.getJSONArray("events").put(new JSONObject().put("receipt","a different operation changed"));task.put("updatedAt",99).put("steps",99);
        assertEquals(old,read(task,operation,request).getString("body"));
    }
    @Test public void sourceLocationCannotReadAnotherTaskRunOrGroupOrigin()throws Exception{
        JSONObject task=task(),operation=operation(),request=request(task,operation);
        for(String field:new String[]{"taskId","runId","operationId"}){
            JSONObject foreign=new JSONObject(operation.toString()).put(field,"different-original-id");
            try{read(task,foreign,request);fail(field);}catch(SecurityException expected){}
        }
        try{read(new JSONObject(task.toString()).put("origin",new JSONObject().put("kind","group")),operation,request);fail();}catch(SecurityException expected){}
        try{read(task,operation,new JSONObject(request.toString()).put("sourceId","task:another:operation:"+OP));fail();}catch(IllegalArgumentException expected){}
        try{read(task,operation,new JSONObject(request.toString()).put("revision","updatedAt:99"));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void missingAndLimitedOriginalRecordsNeverBecomeEmptySuccessfulEvidence()throws Exception{
        JSONObject task=task(),operation=operation(),request=request(task,operation);
        JSONObject missing=read(task,null,request);assertEquals("unavailable",missing.getString("status"));assertFalse(missing.has("body"));
        JSONObject limited=(JSONObject)invoke("operationStatus",new Class<?>[]{JSONObject.class,JSONObject.class,String.class,String.class},scope(),request,"read_limited","original_row_exceeds_inline_limit");
        assertEquals("read_limited",limited.getString("status"));assertFalse(limited.has("body"));assertFalse(limited.getBoolean("completeOriginalTrace"));
    }
    @Test public void staleOrOverlongReplyCannotBeDisplayedAsTheRequestedOriginalRange()throws Exception{
        JSONObject task=task(),operation=operation(),request=request(task,operation),page=read(task,operation,request);
        try{checked(new JSONObject(page.toString()).put("revision","sha256:"+"0".repeat(64)),request);fail();}catch(SecurityException expected){}
        try{checked(new JSONObject(page.toString()).put("nativeRevisionStatus","self_declared_counter"),request);fail();}catch(SecurityException expected){}
        JSONObject wrong=new JSONObject(page.toString());wrong.getJSONObject("readRange").put("offset",1);
        try{checked(wrong,request);fail();}catch(IllegalArgumentException expected){}
        try{checked(new JSONObject(page.toString()).put("body","x".repeat(66)),request);fail();}catch(IllegalArgumentException expected){}
        JSONObject changed=new JSONObject(page.toString()).put("status","source_changed");
        assertFalse(checked(changed,request).has("body"));
    }
    @Test public void storedByteChangesInvalidateTheQuoteEvenWhenParsedJsonIsSemanticallyEqual()throws Exception{
        String arguments=" {\"path\":\"original.txt\"} ",receipt=" {\"actualImageAttached\":true,\"effect\":\"observed\",\"audit\":\"kept\"} ";
        JSONObject first=TaskMemoryTrace.storedOperation(TASK,RUN,OP,"workspace_read",arguments,"receipt",receipt,1);
        JSONObject request=request(task(),first);assertEquals("current",read(task(),first,request).getString("status"));
        JSONObject later=TaskMemoryTrace.storedOperation(TASK,RUN,OP,"workspace_read",arguments,"receipt",receipt.trim(),1);
        assertEquals(first.getJSONObject("receipt").toString(),later.getJSONObject("receipt").toString());
        assertEquals("source_changed",read(task(),later,request).getString("status"));
        assertTrue(first.getJSONObject("receipt").getBoolean("actualImageAttached"));
        JSONObject legacy=TaskMemoryTrace.storedOperation(TASK,RUN,OP,"workspace_read","legacy owner {\"actualImageAttached\":true}","receipt",null,1);
        assertEquals("legacy owner {\"actualImageAttached\":true}",legacy.getString("arguments"));assertTrue(legacy.isNull("receipt"));
    }
}
