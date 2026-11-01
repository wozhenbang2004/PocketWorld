package dev.pocketengine.features.memory;

import java.io.File;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class MemoryRecallDiagnosticsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private MemoryBook book(String name)throws Exception{return new MemoryBook(new File(temp.getRoot(),name));}
    private void save(MemoryBook b,String text,boolean core,int now)throws Exception{b.save(scope(),new JSONObject().put("text",text).put("core",core),now);}
    private JSONArray refs(JSONObject snapshot)throws Exception{
        JSONArray refs=new JSONArray(),rows=snapshot.getJSONArray("records");
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);refs.put(new JSONObject().put("id",r.getString("id")).put("revision",r.getLong("revision")).put("selection",r.getString("selection")));}return refs;
    }
    @Test public void balancedSlotsBorrowUnusedTaskAllocationAndNeverDuplicateCoreMatches()throws Exception{
        MemoryBook b=book("balanced.json");for(int i=0;i<16;i++)save(b,"CORE_BODY_"+i,true,i);for(int i=0;i<16;i++)save(b,"shared TASK_BODY_"+i,false,30+i);
        JSONObject full=b.contextSnapshot(scope(),"shared",16,12000),d=MemoryRecallBudget.checked(full.getJSONObject("selectionDiagnostics"),refs(full));
        assertEquals(8,d.getInt("coreSelected"));assertEquals(8,d.getInt("retrievedSelected"));assertEquals(16,d.getJSONObject("omissionCounts").getInt("record_limit"));
        assertFalse(d.toString().contains("CORE_BODY"));assertFalse(d.toString().contains("TASK_BODY"));
        MemoryBook borrow=book("borrow.json");for(int i=0;i<16;i++)save(borrow,"CORE_BODY_"+i,true,i);save(borrow,"shared TASK_BODY",false,30);
        JSONObject one=borrow.contextSnapshot(scope(),"shared",16,12000).getJSONObject("selectionDiagnostics");assertEquals(15,one.getInt("coreSelected"));assertEquals(1,one.getInt("retrievedSelected"));assertEquals(8,one.getInt("coreQuota"));
        JSONObject duplicate=borrow.contextSnapshot(scope(),"CORE",16,12000);assertEquals(16,duplicate.getJSONArray("records").length());assertEquals(0,duplicate.getInt("retrievedAvailable"));
    }
    @Test public void wholeRecordCharacterBoundsZeroAndSingleSlotHaveExplicitReasons()throws Exception{
        MemoryBook b=book("chars.json");save(b,"x".repeat(3900),true,1);save(b,"shared short task",false,2);
        JSONObject small=b.contextSnapshot(scope(),"shared",16,900);assertEquals("shared short task",small.getJSONArray("records").getJSONObject(0).getString("text"));
        assertTrue(small.getString("context").length()<=900);assertEquals(1,small.getJSONObject("selectionDiagnostics").getJSONObject("omissionCounts").getInt("record_too_large"));
        JSONObject zero=b.contextSnapshot(scope(),"shared",0,12000);assertEquals("",zero.getString("context"));assertEquals(2,zero.getJSONObject("selectionDiagnostics").getJSONObject("omissionCounts").getInt("record_limit"));
        JSONObject one=b.contextSnapshot(scope(),"shared",1,12000);assertEquals("retrieved",one.getJSONArray("records").getJSONObject(0).getString("selection"));
        assertEquals(one.getString("context").length(),one.getInt("charsUsed"));
    }
    @Test public void omissionSamplesAreBoundedAndMalformedOrBodyBearingExplanationsAreRejected()throws Exception{
        MemoryBook b=book("samples.json");for(int i=0;i<40;i++)save(b,"shared BODY_"+i,false,i);
        JSONObject selected=b.contextSnapshot(scope(),"shared",1,12000),d=selected.getJSONObject("selectionDiagnostics");
        assertEquals(32,d.getJSONArray("omitted").length());assertTrue(d.getBoolean("omissionsTruncated"));assertEquals(39,d.getJSONObject("omissionCounts").getInt("record_limit"));
        assertNotNull(MemoryRecallBudget.checked(d,refs(selected)));
        assertThrows(IllegalArgumentException.class,()->MemoryRecallBudget.checked(new JSONObject(d.toString()).put("text","invented body"),refs(selected)));
        JSONObject wrong=new JSONObject(d.toString());wrong.getJSONArray("selected").getJSONObject(0).put("revision",99);
        assertThrows(SecurityException.class,()->MemoryRecallBudget.checked(wrong,refs(selected)));
        assertThrows(IllegalArgumentException.class,()->MemoryRecallBudget.checked(new JSONObject(d.toString()).put("retrievedSelected",0),refs(selected)));
    }
    @Test public void formatTwoBindsOriginalDiagnosticsAndFormatOneDoesNotInventAnExplanation()throws Exception{
        MemoryBook b=book("journal.json");save(b,"shared original",false,1);
        TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"owner","private");
        TaskMemoryUse.HostReader host=new TaskMemoryUse.HostReader(){public TaskMemoryUse.TaskState readTask(MemoryBook.Scope s,String id){return new TaskMemoryUse.TaskState(task,"running",false);}public TaskMemoryUse.Provided readProvided(MemoryBook.Scope s,TaskMemoryUse.ReceiptRef r){return null;}};
        MemoryHostFence fence=new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}};
        TaskMemoryUse.Selection selected=new TaskMemoryUse(b,host,null).prepareTaskRecall(scope(),task,"shared",16,12000,fence);
        TaskMemoryJournal.Intent intent=TaskMemoryJournal.prepare(UUID.randomUUID().toString(),task,"native_request",selected);JSONObject args=intent.arguments(),receipt=TaskMemoryJournal.completed(intent,"native_answer",2);
        TaskMemoryUse.ReceiptRef ref=new TaskMemoryUse.ReceiptRef(task,"native_request","native_answer");
        JSONObject original=new JSONObject().put("id",task.taskId).put("actorId","owner").put("spaceId","private");
        JSONObject op=new JSONObject().put("tool",TaskMemoryJournal.TOOL).put("state",TaskMemoryJournal.PROVIDED).put("runId",task.runId).put("arguments",args).put("receipt",receipt);original.put("operations",new JSONArray().put(op));
        assertEquals(2,args.getInt("format"));assertEquals(selected.snapshot().getJSONObject("selectionDiagnostics").toString(),TaskMemoryJournal.readProvided(original,ref).selectionDiagnostics().toString());
        JSONObject mutated=intent.arguments();mutated.getJSONObject("selectionDiagnostics").getJSONArray("selected").getJSONObject(0).put("reason","model_says_used");assertThrows(SecurityException.class,()->TaskMemoryJournal.requireIntent(intent,mutated));
        JSONObject legacyArgs=new JSONObject(args.toString());legacyArgs.put("format",1);legacyArgs.remove("selectionDiagnostics");legacyArgs.remove("diagnosticsDigest");
        JSONObject legacyReceipt=new JSONObject(receipt.toString());legacyReceipt.put("format",1);legacyReceipt.remove("diagnosticsDigest");op.put("arguments",legacyArgs).put("receipt",legacyReceipt);
        assertNull("format1 is original identity without a historical budget",TaskMemoryJournal.readProvided(original,ref).selectionDiagnostics());
        op.put("arguments",new JSONObject(args.toString()).put("diagnosticsDigest","0".repeat(64))).put("receipt",receipt);
        assertThrows(SecurityException.class,()->TaskMemoryJournal.readProvided(original,ref));
    }
    @Test public void nativeFeedbackAndOwnerQueueOrderSurviveHistorySerializationWithoutWireMetadata()throws Exception{
        var method=dev.pocketengine.runtime.AgentRunner.class.getDeclaredMethod("latestOwnerRecallQuery",JSONArray.class,String.class);method.setAccessible(true);
        JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","previous queued requirement").put("ownerMessageId",UUID.randomUUID().toString()).put("ownerInputMode","queue"))
                .put(new JSONObject().put("role","user").put("content","机主的新补充要求：\nlatest feedback DELTA_TICKET").put("ownerRecallFeedback",true));
        assertEquals("latest feedback DELTA_TICKET",method.invoke(null,new JSONArray(history.toString()),"original goal"));
        history.put(new JSONObject().put("role","user").put("content","newer steering DELTA_NEXT").put("ownerMessageId",UUID.randomUUID().toString()).put("ownerInputMode","steer"));
        assertEquals("newer steering DELTA_NEXT",method.invoke(null,new JSONArray(history.toString()),"original goal"));
        assertFalse(TaskMemoryHistory.wireMessages(history).toString().contains("ownerRecallFeedback"));assertTrue(history.toString().contains("ownerRecallFeedback"));
    }
}
