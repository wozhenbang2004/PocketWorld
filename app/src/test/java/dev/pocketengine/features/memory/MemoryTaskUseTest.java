package dev.pocketengine.features.memory;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class MemoryTaskUseTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private final Map<String,SourceRef.State> sourceStates=new HashMap<>();
    private final TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef("task-current","run-current",3,"owner","private");
    private final TaskMemoryUse.ReceiptRef receipt=new TaskMemoryUse.ReceiptRef(task,"request-native","answer-native");
    private final Host host=new Host();private boolean admitted=true;
    private final MemoryHostFence fence=new MemoryHostFence(){public void check(){if(!admitted)throw new SecurityException("revoked");}public JSONObject admit(Admission action)throws Exception{check();return action.run();}};
    private final class Host implements TaskMemoryUse.HostReader {
        TaskMemoryUse.TaskState state=new TaskMemoryUse.TaskState(task,"running",false);TaskMemoryUse.Provided proof;int reads;
        public TaskMemoryUse.TaskState readTask(MemoryBook.Scope scope,String id){return state;}
        public TaskMemoryUse.Provided readProvided(MemoryBook.Scope scope,TaskMemoryUse.ReceiptRef expected){reads++;return proof;}
    }
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private File file(){return new File(temp.getRoot(),"records.json");}
    private MemoryBook open()throws Exception{return new MemoryBook(file(),(s,k,id)->sourceStates.get(k+":"+id));}
    private JSONObject row(String text)throws Exception{return new JSONObject().put("text",text);}
    private TaskMemoryUse use(MemoryBook book){return new TaskMemoryUse(book,host,ref->new TaskMemoryUse.Evidence(ref,"原始来源","原生精确快照"));}
    private void prove(TaskMemoryUse.Selection selection)throws Exception{JSONObject m=selection.metadata();host.proof=new TaskMemoryUse.Provided(receipt,m.getString("selectionId"),m.getString("contextDigest"),System.currentTimeMillis(),false,m.getJSONArray("records"));}
    private interface Checked{void run()throws Exception;}
    private void denied(Checked action)throws Exception{try{action.run();fail("unsafe request admitted");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}}

    @Test public void preparationIsNotDeliveryAndOnlyExactNativeProofCanConfirm()throws Exception{
        MemoryBook book=open();book.save(scope(),row("本次任务咖啡偏好").put("core",true),1);TaskMemoryUse use=use(book);
        TaskMemoryUse.Selection selected=use.prepareTaskRecall(scope(),task,"咖啡",16,12000,fence);
        assertEquals("prepared",selected.snapshot().getString("phase"));assertFalse(selected.snapshot().getBoolean("modelUseVerified"));
        assertEquals(task.taskId,selected.snapshot().getString("taskId"));assertEquals(task.runId,selected.snapshot().getString("runId"));
        assertEquals("current",selected.snapshot().getJSONArray("records").getJSONObject(0).getString("status"));
        assertEquals("unavailable",use.confirmTaskMemoryUse(scope(),selected,receipt,fence).getString("phase"));
        JSONObject copy=selected.metadata();copy.getJSONArray("records").getJSONObject(0).put("revision",999);
        prove(selected);JSONObject current=use.confirmTaskMemoryUse(scope(),selected,receipt,fence);
        assertEquals("provided_to_request",current.getString("phase"));assertEquals("current",current.getString("status"));assertEquals(1,current.getJSONArray("records").getJSONObject(0).getLong("revision"));
        assertFalse(current.getBoolean("executionAuthorized"));assertFalse(current.getBoolean("modelUseVerified"));
        assertFalse(selected.metadata().toString().contains("本次任务咖啡偏好"));assertFalse(Files.readString(file().toPath()).contains("request-native"));
    }

    @Test public void forgedDigestPartialOrOtherRequestCannotBecomeUseReceipt()throws Exception{
        MemoryBook book=open();book.save(scope(),row("正式记忆").put("core",true),1);TaskMemoryUse use=use(book);TaskMemoryUse.Selection selection=use.prepareTaskRecall(scope(),task,"",16,12000,fence);JSONObject m=selection.metadata();
        host.proof=new TaskMemoryUse.Provided(receipt,m.getString("selectionId"),"0".repeat(64),10,false,m.getJSONArray("records"));
        assertEquals("native_proof_mismatch",use.confirmTaskMemoryUse(scope(),selection,receipt,fence).getString("reason"));
        host.proof=new TaskMemoryUse.Provided(receipt,m.getString("selectionId"),m.getString("contextDigest"),10,false,new JSONArray());
        assertEquals("unavailable",use.confirmTaskMemoryUse(scope(),selection,receipt,fence).getString("phase"));
        TaskMemoryUse.ReceiptRef other=new TaskMemoryUse.ReceiptRef(task,"other-request","other-answer");host.proof=new TaskMemoryUse.Provided(other,m.getString("selectionId"),m.getString("contextDigest"),10,false,m.getJSONArray("records"));
        denied(()->use.readTaskMemoryReceipt(scope(),receipt,fence));denied(()->use(open()).confirmTaskMemoryUse(scope(),selection,receipt,fence));
    }

    @Test public void correctionAndForgetChangeFutureRecallWithoutRewritingOriginalReceipt()throws Exception{
        MemoryBook book=open();JSONObject old=book.save(scope(),row("原旧位置").put("core",true),1);TaskMemoryUse use=use(book);TaskMemoryUse.Selection selection=use.prepareTaskRecall(scope(),task,"",16,12000,fence);prove(selection);
        JSONObject edited=book.save(scope(),new JSONObject(old.toString()).put("text","机主纠正位置"),2);
        denied(()->use.validateTaskMemorySelection(scope(),selection,fence));
        JSONObject previous=use.readTaskMemoryReceipt(scope(),receipt,fence).getJSONArray("records").getJSONObject(0);assertEquals("record_changed",previous.getString("status"));assertFalse(previous.has("text"));assertEquals(1,previous.getLong("revision"));
        assertTrue(use.prepareTaskRecall(scope(),task,"",16,12000,fence).snapshot().getString("context").contains("机主纠正位置"));
        book.forgetReviewed(scope(),edited.getString("id"),edited.getLong("revision"));
        assertEquals("record_removed",use(open()).readTaskMemoryReceipt(scope(),receipt,fence).getJSONArray("records").getJSONObject(0).getString("status"));
        assertEquals(0,use(open()).prepareTaskRecall(scope(),task,"位置",16,12000,fence).snapshot().getJSONArray("records").length());
    }

    @Test public void sourceAdvanceDeletionAndUnavailableAreVisibleAfterRestartWithoutReSearch()throws Exception{
        MemoryBook book=open();SourceRef source=new SourceRef("task_evidence","task:past:verified_result",4,"owner","private");sourceStates.put("task_evidence:"+source.sourceId,new SourceRef.State(source,false));
        book.importFromSources(scope(),row("真实任务来源事实").put("sourceRefs",new JSONArray().put(source.toJson())),1);
        TaskMemoryUse use=use(book);TaskMemoryUse.Selection selection=use.prepareTaskRecall(scope(),task,"事实",16,12000,fence);prove(selection);
        SourceRef next=new SourceRef(source.kind,source.sourceId,5,"owner","private");sourceStates.put("task_evidence:"+source.sourceId,new SourceRef.State(next,false));
        TaskMemoryUse restarted=use(open());JSONObject changed=restarted.readTaskMemoryReceipt(scope(),receipt,fence).getJSONArray("records").getJSONObject(0);assertEquals("source_changed",changed.getString("status"));assertFalse(changed.has("text"));
        assertEquals("past",changed.getJSONArray("sourceRefs").getJSONObject(0).getString("taskId"));
        sourceStates.put("task_evidence:"+source.sourceId,new SourceRef.State(source,true));assertEquals("source_deleted",restarted.readTaskMemoryReceipt(scope(),receipt,fence).getJSONArray("records").getJSONObject(0).getString("status"));
        sourceStates.clear();assertEquals("source_unavailable",restarted.readTaskMemoryReceipt(scope(),receipt,fence).getJSONArray("records").getJSONObject(0).getString("status"));
        assertEquals(0,restarted.prepareTaskRecall(scope(),task,"事实",16,12000,fence).snapshot().getJSONArray("records").length());
    }

    @Test public void cancelledChangedRunRevokedProofAndAdmissionCannotPretendCurrent()throws Exception{
        MemoryBook book=open();book.save(scope(),row("核心事实").put("core",true),1);TaskMemoryUse use=use(book);TaskMemoryUse.Selection selection=use.prepareTaskRecall(scope(),task,"",16,12000,fence);prove(selection);
        host.state=new TaskMemoryUse.TaskState(task,"stopping",true);JSONObject cancelled=use.readTaskMemoryReceipt(scope(),receipt,fence);
        denied(()->use.validateTaskMemorySelection(scope(),selection,fence));
        assertEquals("task_cancelled",cancelled.getString("status"));assertFalse(cancelled.getJSONArray("records").getJSONObject(0).has("text"));denied(()->use.prepareTaskRecall(scope(),task,"",16,12000,fence));
        host.state=new TaskMemoryUse.TaskState(new TaskMemoryUse.TaskRef(task.taskId,"new-run",4,"owner","private"),"running",false);assertEquals("run_changed",use.readTaskMemoryReceipt(scope(),receipt,fence).getString("status"));
        host.state=new TaskMemoryUse.TaskState(task,"running",false);JSONObject m=selection.metadata();host.proof=new TaskMemoryUse.Provided(receipt,m.getString("selectionId"),m.getString("contextDigest"),10,true,m.getJSONArray("records"));
        assertEquals("revoked",use.readTaskMemoryReceipt(scope(),receipt,fence).getString("status"));admitted=false;denied(()->use.prepareTaskRecall(scope(),task,"",16,12000,fence));
    }

    @Test public void crossScopeAndMissingNativePortsFailClosed()throws Exception{
        MemoryBook book=open();book.save(scope(),row("私人事实").put("core",true),1);TaskMemoryUse use=use(book);
        denied(()->use.prepareTaskRecall(new JSONObject().put("ownerId","owner").put("spaceId","group"),task,"",16,12000,fence));
        denied(()->use.prepareTaskRecall(scope(),task,"",16,12000,null));denied(()->use.readTaskMemoryReceipt(null,receipt,fence));
        TaskMemoryUse missing=new TaskMemoryUse(book,null,null);denied(()->missing.prepareTaskRecall(scope(),task,"",16,12000,fence));assertEquals("unavailable",missing.readTaskMemoryReceipt(scope(),receipt,fence).getString("status"));
    }

    @Test public void sourceBodyMustBeExactAndRevalidatedAfterNativeRead()throws Exception{
        MemoryBook book=open();SourceRef source=new SourceRef("message","message-1",1,"owner","private");sourceStates.put("message:message-1",new SourceRef.State(source,false));
        JSONObject row=book.importFromSources(scope(),row("有真实来源").put("sourceRefs",new JSONArray().put(source.toJson())),1);TaskMemoryUse use=use(book);TaskMemoryUse.Selection selection=use.prepareTaskRecall(scope(),task,"来源",16,12000,fence);prove(selection);
        assertEquals("原生精确快照",use.readTaskMemorySource(scope(),receipt,row.getString("id"),1,source,fence).getString("text"));
        TaskMemoryUse missing=new TaskMemoryUse(book,host,null);assertEquals("body_unavailable",missing.readTaskMemorySource(scope(),receipt,row.getString("id"),1,source,fence).getString("status"));
        TaskMemoryUse wrongBody=new TaskMemoryUse(book,host,ref->new TaskMemoryUse.Evidence(new SourceRef("message","other-source",1,"owner","private"),"别的来源","不能替代原版本正文"));
        assertEquals("body_unavailable",wrongBody.readTaskMemorySource(scope(),receipt,row.getString("id"),1,source,fence).getString("status"));
        TaskMemoryUse stale=new TaskMemoryUse(book,host,ref->{book.save(scope(),new JSONObject(row.toString()).put("text","来源读取期间发生机主纠正"),2);return new TaskMemoryUse.Evidence(ref,"来源","不应再展示的旧文");});
        JSONObject rejected=stale.readTaskMemorySource(scope(),receipt,row.getString("id"),1,source,fence);assertFalse(rejected.has("text"));assertEquals("reference_invalidated",rejected.getString("status"));
    }

    @Test public void explicitReplacementPickerExcludesForeignPendingAndDependentFactsAndPreservesOrigin()throws Exception{
        MemoryBook book=open();JSONObject old=book.save(scope(),row("旧项目事实").put("core",true),1),kept=book.save(scope(),row("正确新项目事实"),2);
        book.save(scope(),row("依赖旧事实的摘要").put("sourceIds",new JSONArray().put(old.getString("id"))),3);
        book.save(new JSONObject().put("ownerId","owner").put("spaceId","group"),row("群内秘密"),4);
        TaskMemoryUse use=use(book);TaskMemoryUse.Selection selection=use.prepareTaskRecall(scope(),task,"",16,12000,fence);prove(selection);
        JSONArray choices=use.listTaskMemoryReplacements(scope(),receipt,old.getString("id"),1,fence).getJSONArray("records");assertEquals(1,choices.length());assertEquals(kept.getString("id"),choices.getJSONObject(0).getString("id"));
        book.supersedeReviewed(scope(),kept.getString("id"),1,old.getString("id"),1,r->{},10);
        JSONObject original=use(open()).readTaskMemoryReceipt(scope(),receipt,fence).getJSONArray("records").getJSONObject(0);assertEquals("superseded",original.getString("status"));assertFalse(original.has("text"));assertEquals(kept.getString("id"),original.getJSONArray("replacementRefs").getJSONObject(0).getString("id"));
        assertEquals(0,use(open()).prepareTaskRecall(scope(),task,"旧项目",16,12000,fence).snapshot().getInt("coreAvailable"));
    }
    @Test public void oneInvalidatedRecordDoesNotFalsifyAnotherCurrentSourceOrRefreshOriginalRefs()throws Exception{
        MemoryBook book=open();SourceRef a=new SourceRef("message","a",1,"owner","private"),b=new SourceRef("message","b",1,"owner","private");
        sourceStates.put("message:a",new SourceRef.State(a,false));sourceStates.put("message:b",new SourceRef.State(b,false));
        JSONObject left=book.save(scope(),row("左依据").put("sourceRefs",new JSONArray().put(a.toJson())).put("core",true),1);
        JSONObject right=book.save(scope(),row("右依据").put("sourceRefs",new JSONArray().put(b.toJson())).put("core",true),2);
        TaskMemoryUse use=use(book);TaskMemoryUse.Selection selection=use.prepareTaskRecall(scope(),task,"",16,12000,fence);prove(selection);book.forgetReviewed(scope(),left.getString("id"),1);
        JSONObject shown=use.readTaskMemoryReceipt(scope(),receipt,fence);assertEquals("invalidated",shown.getString("status"));
        for(int i=0;i<shown.getJSONArray("records").length();i++){
            JSONObject current=shown.getJSONArray("records").getJSONObject(i);if(current.getString("id").equals(right.getString("id"))){assertEquals("current",current.getString("status"));assertEquals("右依据",current.getString("text"));}
        }
        assertEquals("current",use.readTaskMemorySource(scope(),receipt,right.getString("id"),1,b,fence).getString("status"));
        assertEquals(2,host.proof.references().length());
    }
}
