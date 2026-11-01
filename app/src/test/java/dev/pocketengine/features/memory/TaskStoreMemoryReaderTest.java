package dev.pocketengine.features.memory;

import android.app.Application;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskStoreMemoryReaderTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private TaskStore tasks(){Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");return new TaskStore(app);}
    private JSONObject record(String text)throws Exception{return new JSONObject().put("text",text);}
    private MemoryHostFence fence(){return new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}};}
    private TaskMemoryUse.TaskRef ref(JSONObject task)throws Exception{return new TaskMemoryUse.TaskRef(task.getString("id"),task.getString("runId"),task.getLong("coordinatorEpoch"),task.getString("actorId"),task.getString("spaceId"));}

    @Test public void realTaskSearchDistinguishesFailedUnverifiedAndOwnerConfirmedWithoutSavingFacts()throws Exception{
        TaskStore tasks=tasks();JSONObject failed=tasks.createCapabilityTask("查咖啡采购结果","");tasks.finish(failed.getString("id"),"failed","采购未完成，待核对",new JSONObject());
        JSONObject confirmed=tasks.createCapabilityTask("昨天咖啡采购","");tasks.finish(confirmed.getString("id"),"completed","旧确认摘要",new JSONObject());tasks.event(confirmed.getString("id"),new JSONObject().put("type","user_verification").put("completed",true));
        TaskStoreMemoryReader reader=new TaskStoreMemoryReader(tasks,null);JSONObject result=reader.searchTasks(scope(),"咖啡",20);assertEquals(2,result.getJSONArray("tasks").length());assertEquals("recent_200_and_exact_task_id",result.getString("coverage"));
        JSONObject failure=reader.readTaskRecord(scope(),failed.getString("id")),yes=reader.readTaskRecord(scope(),confirmed.getString("id"));
        assertEquals("failed",failure.getString("status"));assertEquals("unverified_result",failure.getString("evidenceState"));assertFalse(failure.has("evidenceRef"));assertFalse(failure.getBoolean("formalMemory"));
        assertEquals("owner_confirmed_result",yes.getString("evidenceState"));assertTrue(yes.getBoolean("snapshotAvailable"));assertFalse(yes.getBoolean("executionIndependentlyVerified"));
        SourceRef source=SourceRef.fromJson(yes.getJSONObject("evidenceRef"));tasks.status(confirmed.getString("id"),"completed","后来当前摘要不得冒充确认原文");
        assertTrue(reader.read(source).text.contains("旧确认摘要"));assertFalse(reader.read(source).text.contains("后来当前摘要"));
        assertEquals("unavailable",reader.readTaskRecord(new JSONObject().put("ownerId","other").put("spaceId","private"),confirmed.getString("id")).getString("status"));
        tasks.event(confirmed.getString("id"),new JSONObject().put("type","user_verification").put("completed",false));assertEquals("verification_revoked",reader.readTaskRecord(scope(),confirmed.getString("id")).getString("evidenceState"));
    }

    @Test public void actualTaskStoreSourcesDriveRecallProofProjectionCorrectionAndForgetAcrossRestart()throws Exception{
        TaskStore tasks=tasks();JSONObject past=tasks.createCapabilityTask("真实历史任务：咖啡发票","");tasks.finish(past.getString("id"),"completed","确认地址甲",new JSONObject());tasks.event(past.getString("id"),new JSONObject().put("type","user_verification").put("completed",true));
        TaskStoreMemoryReader reader=new TaskStoreMemoryReader(tasks,null);SourceRef source=SourceRef.fromJson(reader.readTaskRecord(scope(),past.getString("id")).getJSONObject("evidenceRef"));
        JSONObject current=tasks.createCapabilityTask("本次整理发票","");current=tasks.startAttempt(current.getString("id"),"capabilities",5);TaskMemoryUse.TaskRef task=ref(current);TaskMemoryUse.ReceiptRef receipt=new TaskMemoryUse.ReceiptRef(task,"native-request-fixture","native-answer-fixture");
        final TaskMemoryUse.Provided[] nativeJournalFixture={null};reader=new TaskStoreMemoryReader(tasks,(s,r)->nativeJournalFixture[0]);
        File file=new File(temp.getRoot(),"memory.json");MemoryBook book=new MemoryBook(file,tasks::resolveMemorySource);JSONObject old=book.importFromSources(scope(),record("来源确认地址甲").put("sourceRefs",new JSONArray().put(source.toJson())),1);
        TaskMemoryUse use=new TaskMemoryUse(book,reader,reader);TaskMemoryUse.Selection selected=use.prepareTaskRecall(scope(),task,"地址",16,12000,fence());
        assertEquals(1,selected.snapshot().getJSONArray("records").length());assertEquals("unavailable",use.confirmTaskMemoryUse(scope(),selected,receipt,fence()).getString("phase"));
        JSONObject m=selected.metadata();nativeJournalFixture[0]=new TaskMemoryUse.Provided(receipt,m.getString("selectionId"),m.getString("contextDigest"),System.currentTimeMillis(),false,m.getJSONArray("records"));
        assertEquals("provided_to_request",use.confirmTaskMemoryUse(scope(),selected,receipt,fence()).getString("phase"));
        assertTrue(use.readTaskMemorySource(scope(),receipt,old.getString("id"),1,source,fence()).getString("text").contains("确认地址甲"));
        JSONObject corrected=book.save(scope(),new JSONObject(old.toString()).put("text","机主纠正地址乙"),2);
        assertEquals("record_changed",use.readTaskMemoryReceipt(scope(),receipt,fence()).getJSONArray("records").getJSONObject(0).getString("status"));
        book.forgetReviewed(scope(),corrected.getString("id"),corrected.getLong("revision"));tasks.forgetMemorySourceSnapshot(source);
        MemoryBook restarted=new MemoryBook(file,tasks::resolveMemorySource);TaskMemoryUse recovered=new TaskMemoryUse(restarted,reader,reader);
        assertEquals("record_removed",recovered.readTaskMemoryReceipt(scope(),receipt,fence()).getJSONArray("records").getJSONObject(0).getString("status"));
        assertEquals(0,recovered.prepareTaskRecall(scope(),task,"地址",16,12000,fence()).snapshot().getJSONArray("records").length());
        assertFalse(reader.readTaskRecord(scope(),past.getString("id")).getBoolean("snapshotAvailable"));
        tasks.requestCancel(task.taskId,task.runId);assertEquals("task_cancelled",recovered.readTaskMemoryReceipt(scope(),receipt,fence()).getString("status"));
        System.out.println("REAL_TASKSTORE_SOURCE_AND_LIFECYCLE_PASS; delivery witness is a native-journal fixture, not a real model/provider claim");
    }

    @Test public void nativeQueryPagesAndFallbackBoundaryNeverPretendAllHistoryWasSearched()throws Exception{
        TaskStore tasks=tasks();JSONObject a=tasks.createCapabilityTask("原任务咖啡","");JSONObject b=tasks.createCapabilityTask("后任务咖啡","");
        TaskStoreMemoryReader full=new TaskStoreMemoryReader(tasks,null,(s,q,c,l)->new TaskStoreMemoryReader.TaskPage(c.isEmpty()?List.of(b.getString("id")):List.of(a.getString("id")),c.isEmpty()?"opaque-next":""));
        JSONObject first=full.searchTasks(scope(),"咖啡","",1),second=full.searchTasks(scope(),"咖啡",first.getString("nextCursor"),1);
        assertEquals("full_task_store",first.getString("coverage"));assertTrue(first.getBoolean("resultTruncated"));assertEquals(a.getString("id"),second.getJSONArray("tasks").getJSONObject(0).getString("taskId"));assertFalse(second.getBoolean("resultTruncated"));
        TaskStoreMemoryReader fallback=new TaskStoreMemoryReader(tasks,null);assertEquals("native_query_unavailable",fallback.searchTasks(scope(),"咖啡","opaque-next",1).getString("coverage"));
        TaskStoreMemoryReader unavailable=new TaskStoreMemoryReader(tasks,null,(s,q,c,l)->{throw new IllegalStateException("query port unavailable");});assertEquals("unavailable",unavailable.searchTasks(scope(),"咖啡",1).getString("status"));
        for(int i=0;i<201;i++)tasks.createCapabilityTask("新任务"+i,"");JSONObject limited=fallback.searchTasks(scope(),"原任务咖啡",20);assertTrue(limited.getBoolean("mayHaveOlderTasks"));assertEquals(0,limited.getJSONArray("tasks").length());
        assertEquals(a.getString("id"),fallback.searchTasks(scope(),a.getString("id"),20).getJSONArray("tasks").getJSONObject(0).getString("taskId"));
        // The full-query callback above is an injected paging fixture; actual SQL implementation stays root-owned.
    }
}
