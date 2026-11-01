package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.memory.MemoryBook;
import dev.pocketengine.features.memory.SourceRef;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual TaskStore and native SQLite: finite owner windows never become native admission truth. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskStoreAuditProjectionTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static void closeOwnedStore(TaskStore tasks)throws Exception{java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    private static final class Fixture implements AutoCloseable{
        final TaskStore tasks;final SQLiteDatabase db;final String task,run;final int epoch;final RunControl original=new RunControl();
        Fixture()throws Exception{Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);task=tasks.createCapabilityTask("原长任务","original-conversation").getString("id");JSONObject attempt=tasks.startAttempt(task,"capabilities",60);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original.bindHostRun(task,run,epoch);db=SQLiteDatabase.openDatabase(app.getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);}
        public void close()throws Exception{original.cancel();try{db.close();}finally{closeOwnedStore(tasks);}}
        String operation(String state,String body)throws Exception{String op=id();ContentValues row=new ContentValues();row.put("operation_id",op);row.put("task_id",task);row.put("run_id",run);row.put("tool","fixture_read");row.put("arguments",new JSONObject().put("content",body).toString());row.put("state",state);row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);return op;}
    }
    @Test public void nativeGetAndUnknownFenceKeepOperationOutsideTheUiWindow()throws Exception{
        try(Fixture f=new Fixture()){
            String pending=f.operation("unknown","unknown-original-body");for(int i=0;i<35;i++)f.operation("receipt","later"+i);JSONObject ui=f.tasks.getForUi(f.task,false);assertEquals(24,ui.getJSONArray("operations").length());assertTrue(ui.getBoolean("hasUnresolvedOperations"));assertTrue(f.tasks.hasUnresolved(f.task));assertFalse(ui.getJSONArray("operations").toString().contains(pending));
            JSONObject nativeTask=f.tasks.get(f.task,false);assertEquals(36,nativeTask.getJSONArray("operations").length());assertTrue(nativeTask.getJSONArray("operations").toString().contains(pending));RunJournal journal=f.tasks.journal(f.task,f.run,f.epoch,f.original);assertThrows(IllegalStateException.class,journal::restore);
        }
    }
    @Test public void taskHeadersAndUiWindowsDoNotDeleteAnyNativeEvent()throws Exception{
        try(Fixture f=new Fixture()){
            for(int i=0;i<85;i++)f.tasks.event(f.task,new JSONObject().put("type","fixture_step").put("message","原记录"+i));JSONObject ui=f.tasks.getForUi(f.task,false);assertEquals(32,ui.getJSONArray("events").length());assertTrue(ui.getJSONObject("historyEventPage").getBoolean("hasMore"));assertEquals(85,f.tasks.get(f.task,false).getJSONArray("events").length());assertEquals(85,f.tasks.list().getJSONObject(0).getJSONArray("events").length());JSONObject header=f.tasks.taskSummaryPage("",40).getJSONArray("tasks").getJSONObject(0);assertEquals(0,header.getJSONArray("events").length());assertFalse(header.has("operations"));
            try(Cursor row=f.db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{f.task})){row.moveToFirst();assertEquals(0,new JSONObject(row.getString(0)).getJSONArray("events").length());}
        }
    }
    @Test public void confirmedSourceUsesOriginalArchivedRevisionAndForgetCannotReviveItsBody()throws Exception{
        try(Fixture f=new Fixture()){
            f.tasks.event(f.task,new JSONObject().put("type","user_verification").put("completed",true).put("feedback","原主人确认"));SourceRef.State state=f.tasks.resolveMemorySource(MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId","private")),"task_evidence","task:"+f.task+":verified_result");assertNotNull(state);long revision=state.ref.revision;for(int i=0;i<70;i++)f.tasks.event(f.task,new JSONObject().put("type","after_confirmation"));assertEquals(revision,f.tasks.resolveMemorySource(MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId","private")),"task_evidence",state.ref.sourceId).ref.revision);assertTrue(f.tasks.readMemorySource(state.ref).text.contains("原主人确认"));f.tasks.forgetMemorySourceSnapshot(state.ref);assertThrows(IllegalStateException.class,()->f.tasks.readMemorySource(state.ref));assertEquals(71,f.tasks.get(f.task,false).getJSONArray("events").length());
        }
    }
    @Test public void versionTwoUpgradeUsesOriginalDatabaseAndPreservesAuditAndUnknownOperation()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");String task=id(),operation=id();java.io.File file=app.getDatabasePath("pocket-world.db");file.getParentFile().mkdirs();
        try(SQLiteDatabase old=SQLiteDatabase.openOrCreateDatabase(file,null)){
            old.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");old.execSQL("INSERT INTO meta(key,value) VALUES('legacyImported','1')");
            old.execSQL("CREATE TABLE tasks(task_id TEXT PRIMARY KEY,document TEXT NOT NULL,updated_at INTEGER NOT NULL)");old.execSQL("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,tool TEXT NOT NULL,arguments TEXT NOT NULL,state TEXT NOT NULL,receipt TEXT,created_at INTEGER NOT NULL)");old.execSQL("CREATE INDEX operations_task ON operations(task_id,state)");
            old.execSQL("CREATE TABLE runs(run_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,epoch INTEGER NOT NULL,status TEXT NOT NULL,checkpoint TEXT NOT NULL,grant_json TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(task_id,epoch))");old.execSQL("CREATE TABLE leases(resource_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,epoch INTEGER NOT NULL,expires_at INTEGER NOT NULL)");dev.pocketengine.features.assistant.ConversationRepository.createSchema(old);
            JSONObject original=new JSONObject().put("id",task).put("kind","assistant").put("status","reconciling").put("goal","旧任务不可重放").put("actorId","owner").put("spaceId","private").put("events",new JSONArray().put(new JSONObject().put("type","original").put("seq",7).put("message","旧正文🧭")));ContentValues t=new ContentValues();t.put("task_id",task);t.put("document",original.toString());t.put("updated_at",42);old.insertOrThrow("tasks",null,t);
            old.execSQL("INSERT INTO operations(operation_id,task_id,run_id,tool,arguments,state,created_at) VALUES(?,?,?,?,?,?,?)",new Object[]{operation,task,id(),"send_original","{}","unknown",42});old.setVersion(2);
        }
        TaskStore migrated=new TaskStore(app);assertTrue(migrated.hasUnresolved(task));JSONObject audit=migrated.get(task,false);assertEquals("旧正文🧭",audit.getJSONArray("events").getJSONObject(0).getString("message"));assertEquals(7,audit.getJSONArray("events").getJSONObject(0).getLong("seq"));assertEquals(operation,audit.getJSONArray("operations").getJSONObject(0).getString("operationId"));
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){assertEquals(3,db.getVersion());try(Cursor row=db.rawQuery("SELECT audit_format,document FROM tasks WHERE task_id=?",new String[]{task})){assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));assertEquals(0,new JSONObject(row.getString(1)).getJSONArray("events").length());}}
        closeOwnedStore(migrated);
    }

}
