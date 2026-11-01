package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
public final class TaskMemoryNativeJournalTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private TaskStore store(){Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");return new TaskStore(app);}
    private SQLiteDatabase connection(boolean readOnly){return SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("pocket-world.db").getPath(),null,readOnly?SQLiteDatabase.OPEN_READONLY:SQLiteDatabase.OPEN_READWRITE);}
    private MemoryHostFence fence(){return new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}};}
    private interface Checked{void run()throws Exception;}
    private void rejected(Checked action)throws Exception{try{action.run();fail("invalid input admitted");}catch(IllegalArgumentException|SecurityException expected){}}

    @Test public void actualReadOnlySqlFindsBeyond200WithStableTiesNoScopeLeakAndBoundCursor()throws Exception{
        TaskStore tasks=store();JSONObject oldest=tasks.createCapabilityTask("咖啡历史目标 原始稀有线索","");
        Set<String> expected=new HashSet<>();expected.add(oldest.getString("id"));
        for(int i=0;i<219;i++)expected.add(tasks.createCapabilityTask("咖啡历史目标 "+i,"").getString("id"));
        for(String id:new String[]{"native-operation-\uE000","native-operation-\uD800\uDC00"}){tasks.beginExternal("query_fixture","inspect",id,new JSONObject().put("goal","咖啡历史目标 游标探针"));expected.add(id);}
        String foreign=UUID.randomUUID().toString();
        try(SQLiteDatabase writable=connection(false)){
            JSONObject group=new JSONObject().put("id",foreign).put("actorId","owner").put("spaceId","group").put("goal","咖啡历史目标 群秘密").put("summary","").put("status","completed");
            ContentValues row=new ContentValues();row.put("task_id",foreign);row.put("document",group.toString());row.put("updated_at",9999999999999L);writable.insertOrThrow("tasks",null,row);
            writable.execSQL("UPDATE tasks SET updated_at=1000 WHERE task_id!=?",new Object[]{foreign}); // Deliberate tie fixture in the real authority table.
        }
        try(SQLiteDatabase readOnly=connection(true)){
            TaskStoreMemoryReader reader=new TaskStoreMemoryReader(tasks,null,(s,q,c,l)->TaskStoreMemoryQuery.query(readOnly,s,q,c,l));
            assertEquals(1,reader.searchTasks(scope(),"原始稀有线索","",20).getJSONArray("tasks").length());
            assertEquals("full_task_store",reader.searchTasks(scope(),"咖啡历史目标","",20).getString("coverage"));
            MemoryBook.Scope identity=MemoryBook.Scope.from(scope());String cursor="",firstCursor=null;Set<String> actual=new HashSet<>();int pages=0;
            do{TaskStoreMemoryReader.TaskPage page=TaskStoreMemoryQuery.query(readOnly,identity,"咖啡 历史目标",cursor,17);for(String id:page.taskIds)assertTrue("duplicate page row",actual.add(id));cursor=page.nextCursor;if(firstCursor==null)firstCursor=cursor;pages++;assertTrue(pages<20);}while(!cursor.isEmpty());
            assertEquals(expected,actual);assertTrue(pages>12);
            TaskStoreMemoryReader.TaskPage probe=TaskStoreMemoryQuery.query(readOnly,identity,"游标探针","",1);assertEquals(1,probe.taskIds.size());
            assertEquals(1,TaskStoreMemoryQuery.query(readOnly,identity,"游标探针",probe.nextCursor,1).taskIds.size());
            String decoded=new String(Base64.getUrlDecoder().decode(firstCursor),java.nio.charset.StandardCharsets.UTF_8);assertFalse(decoded.contains(foreign));
            final String bound=firstCursor;
            rejected(()->TaskStoreMemoryQuery.query(readOnly,identity,"不同查询",bound,17));
            rejected(()->TaskStoreMemoryQuery.query(readOnly,MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId","group")),"咖啡 历史目标",bound,17));
            rejected(()->TaskStoreMemoryQuery.query(readOnly,identity,"咖啡","bad cursor",17));
            assertEquals(0,TaskStoreMemoryQuery.query(readOnly,identity,"不存在的线索","",10).taskIds.size());
        }
    }

    @Test public void nativeOperationsAreRequiredAndRecoverTheOriginalProofWithoutNewSearchOrUsageDb()throws Exception{
        TaskStore tasks=store();JSONObject running=tasks.startAttempt(tasks.createCapabilityTask("当前真实任务","").getString("id"),"capabilities",5);
        TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef(running.getString("id"),running.getString("runId"),running.getLong("coordinatorEpoch"),"owner","private");
        File file=new File(temp.getRoot(),"memory.json");MemoryBook book=new MemoryBook(file);book.save(scope(),new JSONObject().put("text","原生来源事实").put("core",true),1);
        TaskStoreMemoryReader reader=new TaskStoreMemoryReader(tasks,null);TaskMemoryUse use=new TaskMemoryUse(book,reader,null);
        TaskMemoryUse.Selection selected=use.prepareTaskRecall(scope(),task,"",16,12000,fence());
        TaskMemoryJournal.Intent intent=TaskMemoryJournal.prepare(UUID.randomUUID().toString(),task,"native-request-actual-call-slot",selected);
        JSONObject completed=TaskMemoryJournal.completed(intent,"native-answer-actual-response-slot",System.currentTimeMillis());
        TaskMemoryUse.ReceiptRef receipt=new TaskMemoryUse.ReceiptRef(task,intent.requestId,completed.getString("answerId"));
        tasks.event(task.taskId,new JSONObject(completed.toString()).put("type",TaskMemoryJournal.TOOL).put("state",TaskMemoryJournal.PROVIDED));
        assertNull(reader.readProvided(MemoryBook.Scope.from(scope()),receipt)); // A plausible JSON event cannot self-attest native delivery.
        try(SQLiteDatabase writable=connection(false)){
            ContentValues row=new ContentValues();row.put("operation_id",intent.operationId);row.put("task_id",task.taskId);row.put("run_id",task.runId);row.put("tool",TaskMemoryJournal.TOOL);
            row.put("arguments",intent.arguments().toString());row.put("state",TaskMemoryJournal.PREPARED);row.put("created_at",System.currentTimeMillis());writable.insertOrThrow("operations",null,row);
            assertNull(reader.readProvided(MemoryBook.Scope.from(scope()),receipt));
            row=new ContentValues();row.put("state","receipt");row.put("receipt",completed.toString());writable.update("operations",row,"operation_id=?",new String[]{intent.operationId});
            assertNull(reader.readProvided(MemoryBook.Scope.from(scope()),receipt)); // Ordinary capability receipts are not the dedicated native state.
            row.put("state",TaskMemoryJournal.PROVIDED);writable.update("operations",row,"operation_id=?",new String[]{intent.operationId});
            assertEquals("provided_to_request",use.confirmTaskMemoryUse(scope(),selected,receipt,fence()).getString("phase"));
            TaskMemoryUse.ReceiptRef recovered=reader.readReceiptRef(scope(),task.taskId,task.runId,receipt.requestId,receipt.answerId);assertEquals(task.coordinatorEpoch,recovered.task.coordinatorEpoch);
            TaskMemoryUse restart=new TaskMemoryUse(new MemoryBook(file),reader,null);assertEquals("current",restart.readTaskMemoryReceipt(scope(),recovered,fence()).getString("status"));
            assertFalse(intent.arguments().toString().contains("原生来源事实"));
            row=new ContentValues();row.put("state",TaskMemoryJournal.REVOKED);writable.update("operations",row,"operation_id=?",new String[]{intent.operationId});
            JSONObject revoked=restart.readTaskMemoryReceipt(scope(),recovered,fence());assertEquals("revoked",revoked.getString("status"));assertFalse(revoked.getJSONArray("records").getJSONObject(0).has("text"));
        }
        System.out.println("NATIVE_TASKSTORE_SQL_JOURNAL_CODEC_PASS; codec test uses fixture completion SQL; actual host hook is tested separately");
    }

    @Test public void nativeIntentFieldsAreImmutableAndUnknownSendCannotBecomeProvided()throws Exception{
        TaskStore tasks=store();JSONObject running=tasks.startAttempt(tasks.createCapabilityTask("意图检查","").getString("id"),"capabilities",1);
        TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef(running.getString("id"),running.getString("runId"),running.getLong("coordinatorEpoch"),"owner","private");
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"book.json"));book.save(scope(),new JSONObject().put("text","数据").put("core",true),1);
        TaskMemoryUse.Selection selected=new TaskMemoryUse(book,new TaskStoreMemoryReader(tasks,null),null).prepareTaskRecall(scope(),task,"",16,12000,fence());
        TaskMemoryJournal.Intent intent=TaskMemoryJournal.prepare(UUID.randomUUID().toString(),task,"request",selected);TaskMemoryJournal.requireIntent(intent,new JSONObject(intent.arguments().toString()));
        JSONObject changed=intent.arguments().put("requestId","other");rejected(()->TaskMemoryJournal.requireIntent(intent,changed));
        JSONObject unknown=tasks.get(task.taskId,false);unknown.put("operations",new JSONArray().put(new JSONObject().put("tool",TaskMemoryJournal.TOOL).put("runId",task.runId).put("state",TaskMemoryJournal.UNCONFIRMED).put("arguments",intent.arguments()).put("receipt",TaskMemoryJournal.completed(intent,"answer",1))));
        assertNull(TaskMemoryJournal.receiptRef(unknown,task.runId,"request","answer"));
    }
}
