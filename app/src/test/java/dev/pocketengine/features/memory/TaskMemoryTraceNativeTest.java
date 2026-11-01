package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.util.UUID;
import static org.junit.Assert.*;

/** Real original SQLite tables; fixture rows are explicitly not executed phone/model operations. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskMemoryTraceNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject options(String section)throws Exception{return new JSONObject().put("section",section).put("query","").put("cursor","").put("limit",2).put("maxChars",64);}
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final SQLiteDatabase db;final String id,run=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        Fixture()throws Exception{
            Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);
            id=tasks.createCapabilityTask("机主原始目标 {\"actualImageAttached\":true}","").getString("id");
            db=SQLiteDatabase.openDatabase(app.getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","机主原文 "+"原".repeat(200)))
                    .put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","native-call").put("function",new JSONObject().put("name","workspace_read").put("arguments","{}")))) )
                    .put(new JSONObject().put("role","tool").put("tool_call_id","native-call").put("content","实际保存的工具正文 TRACE_NATIVE_511"));
            ContentValues values=new ContentValues();values.put("run_id",run);values.put("task_id",id);values.put("epoch",1);values.put("status","completed");
            values.put("checkpoint",new JSONObject().put("messages",messages).put("steps",1).toString());values.put("grant_json","{}");values.put("created_at",1);values.put("updated_at",1);db.insertOrThrow("runs",null,values);
            values=new ContentValues();values.put("operation_id",operation);values.put("task_id",id);values.put("run_id",run);values.put("tool","workspace_read");values.put("arguments","{\"path\":\"actual.txt\"}");
            values.put("state","completed");values.put("receipt"," {\"actualResult\":\"native receipt TRACE_NATIVE_511\"} ");values.put("created_at",1);db.insertOrThrow("operations",null,values);
        }
        JSONObject read(JSONObject options)throws Exception{return new TaskStoreMemoryReader(tasks,null).readTaskTrace(scope(),id,run,options);}
        String stored()throws Exception{
            JSONObject all=new JSONObject();for(String table:new String[]{"tasks","runs","operations","meta","leases"}){
                JSONArray rows=new JSONArray();try(Cursor cursor=db.rawQuery("SELECT * FROM "+table+" ORDER BY 1",null)){while(cursor.moveToNext()){JSONArray row=new JSONArray();for(int i=0;i<cursor.getColumnCount();i++)row.put(cursor.isNull(i)?JSONObject.NULL:cursor.getString(i));rows.put(row);}}
                all.put(table,rows);
            }return all.toString();
        }
        public void close(){db.close();}
    }
    @Test public void originalStoreReadFindsActualToolBodyWithoutMutatingAnyAuthorityTable()throws Exception{
        try(Fixture f=new Fixture()){
            String before=f.stored();JSONObject page=f.read(options("messages").put("query","TRACE_NATIVE_511"));
            assertEquals("current",page.getString("status"));assertEquals(f.run,page.getString("runId"));assertFalse(page.getBoolean("completeOriginalTrace"));
            assertEquals("matched_tool_call",page.getJSONArray("entries").getJSONObject(0).getString("causalState"));
            assertEquals("native-call",page.getJSONArray("entries").getJSONObject(0).getJSONObject("parent").getString("toolCallId"));
            JSONObject operation=f.read(options("operations").put("maxChars",4000));assertTrue(operation.getJSONArray("entries").getJSONObject(0).getString("body").contains("native receipt TRACE_NATIVE_511"));
            assertEquals(before,f.stored());
        }
    }
    @Test public void operationOnlyChangeBreaksTheMessageSnapshotFence()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject first=f.read(options("messages"));assertFalse(first.getString("nextCursor").isEmpty());
            ContentValues changed=new ContentValues();changed.put("receipt","{\"actualResult\":\"a new native receipt\"}");f.db.update("operations",changed,"operation_id=?",new String[]{f.operation});
            JSONObject next=f.read(options("messages").put("cursor",first.getString("nextCursor")));
            assertEquals("snapshot_changed",next.getString("status"));assertEquals(0,next.getJSONArray("entries").length());
        }
    }
    @Test public void foreignRunAndGroupOriginsCannotExpandThePrivateReadScope()throws Exception{
        try(Fixture f=new Fixture()){
            assertEquals("unavailable",f.tasks.readMemoryTaskTrace(MemoryBook.Scope.from(scope()),f.id,"foreign-run",options("messages")).getString("status"));
            JSONObject task=f.tasks.get(f.id,false);task.remove("operations");task.put("origin",new JSONObject().put("kind","group"));ContentValues changed=new ContentValues();changed.put("document",task.toString());f.db.update("tasks",changed,"task_id=?",new String[]{f.id});
            try{f.read(options("messages"));fail("group requires its original authority");}catch(SecurityException expected){}
        }
    }
    private static JSONObject citedOperation(Fixture f)throws Exception{
        JSONObject quote=f.read(options("operations")).getJSONArray("entries").getJSONObject(0);
        return new JSONObject().put("taskId",f.id).put("runId",f.run).put("sourceId",quote.getString("sourceId"))
                .put("revision",quote.getString("revision")).put("offset",0).put("maxChars",64);
    }
    @Test public void exactSourceReadIgnoresOtherOperationsButRejectsChangedStoredReceiptBytes()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject request=citedOperation(f);TaskStoreMemoryReader reader=new TaskStoreMemoryReader(f.tasks,null);
            ContentValues other=new ContentValues();other.put("operation_id",UUID.randomUUID().toString());other.put("task_id",f.id);other.put("run_id",f.run);other.put("tool","other_original_tool");
            other.put("arguments","{}");other.put("state","receipt");other.put("receipt","z".repeat(TaskMemoryTrace.MAX_OPERATION_SOURCE_BYTES+1));other.put("created_at",2);f.db.insertOrThrow("operations",null,other);
            String before=f.stored();assertEquals("current",reader.readTraceOperation(scope(),request).getString("status"));assertEquals(before,f.stored());
            ContentValues changed=new ContentValues();changed.put("receipt","{\"actualResult\":\"native receipt TRACE_NATIVE_511\"}");f.db.update("operations",changed,"operation_id=?",new String[]{f.operation});
            JSONObject stale=reader.readTraceOperation(scope(),request);assertEquals("source_changed",stale.getString("status"));assertFalse(stale.has("body"));
        }
    }
    @Test public void oversizedOriginalRowsRemainStoredAndAreNotReturnedAsSuccessfulEmptySources()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject request=citedOperation(f);TaskStoreMemoryReader reader=new TaskStoreMemoryReader(f.tasks,null);
            JSONObject task=f.tasks.get(f.id,false);
            ContentValues changed=new ContentValues();changed.put("receipt","x".repeat(TaskMemoryTrace.MAX_OPERATION_SOURCE_BYTES+1));f.db.update("operations",changed,"operation_id=?",new String[]{f.operation});
            String before=f.stored();JSONObject limited=reader.readTraceOperation(scope(),request);assertEquals("read_limited",limited.getString("status"));assertFalse(limited.has("body"));assertEquals(before,f.stored());
            task.remove("operations");task.put("oldOriginalEventBody","x".repeat(TaskMemoryTrace.MAX_OPERATION_AUTHORITY_BYTES+1));
            changed=new ContentValues();changed.put("document",task.toString());f.db.update("tasks",changed,"task_id=?",new String[]{f.id});
            before=f.stored();assertEquals("unavailable",reader.readTraceOperation(scope(),request).getString("status"));assertEquals(before,f.stored());
        }
    }
}
