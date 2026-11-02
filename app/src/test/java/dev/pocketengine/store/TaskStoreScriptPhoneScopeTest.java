package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original SQLite authority; no actual phone action or script process is started. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskStoreScriptPhoneScopeTest {
    private TaskStore tasks;private SQLiteDatabase db;private RunControl original;
    private RunJournal journal;private String task,run,parent;private int epoch;
    @Before public void open()throws Exception {
        var context=RuntimeEnvironment.getApplication();context.deleteDatabase("pocket-world.db");tasks=new TaskStore(context);
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
        task=tasks.createCapabilityTask("Run this reviewed local script","").getString("id");
        JSONObject started=tasks.startAttempt(task,"capabilities",40);run=started.getString("runId");epoch=started.getInt("coordinatorEpoch");
        original=new RunControl();original.bindHostRun(task,run,epoch);
        tasks.authorizeTools(task,run,new JSONArray().put("skills_execute").put("phone_tap"));journal=tasks.journal(task,run,epoch,original);
        parent=journal.beginOperation("skills_execute",new JSONObject().put("scriptPath","task.py"));
    }
    @After public void close(){if(original!=null)original.cancel();if(db!=null)db.close();}
    private interface Action{void run()throws Exception;}
    private static void rejected(Action action)throws Exception {try{action.run();fail("Expected native rejection");}catch(SecurityException|IllegalStateException expected){}}
    private JSONObject args()throws Exception{return new JSONObject().put("x",10).put("y",20);}
    private int remaining()throws Exception {try(Cursor row=db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0)).getInt("remainingOperations");}}
    private TaskStore.ScriptPhoneScope scope()throws Exception{return tasks.scriptPhoneScope(task,run,epoch,parent,original,Set.of("phone_tap"));}
    private String seed(String state)throws Exception {
        String id=UUID.randomUUID().toString();ContentValues values=new ContentValues();values.put("operation_id",id);values.put("task_id",task);values.put("run_id",run);values.put("tool","phone_tap");values.put("arguments","{}");values.put("state",state);values.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,values);return id;
    }
    @Test public void nativeChildUsesOriginalJournalAndDebitsOriginalGrant()throws Exception {
        int before=remaining();String[] child={null};
        try(var scope=scope()){
            child[0]=scope.invoke("phone_tap",()->{
                String op=journal.beginOperation("phone_tap",args());tasks.requirePreparedCapability(task,run,epoch,op,"phone_tap",args());
                journal.finishOperation(op,new JSONObject().put("ok",true).put("code","observed"));return op;
            });
        }
        assertNotNull(child[0]);assertEquals(before-1,remaining());assertEquals(2,tasks.get(task,false).getJSONArray("operations").length());
        assertTrue(tasks.hasUnresolved(task));rejected(()->journal.beginOperation("phone_tap",args()));
        journal.finishOperation(parent,new JSONObject().put("ok",true));assertFalse(tasks.hasUnresolved(task));
    }
    @Test public void parentPreparedNeverAdmitsOrdinaryParallelDispatch()throws Exception {
        rejected(()->journal.beginOperation("phone_tap",args()));
        try(var scope=scope()){rejected(()->tasks.requirePreparedCapability(task,run,epoch,parent,"skills_execute",new JSONObject().put("scriptPath","task.py")));}
    }
    @Test public void unknownSiblingAndForeignPreparedAreNotExceptionsToBarrier()throws Exception {
        String unknown=seed("unknown");rejected(()->scope());db.delete("operations","operation_id=?",new String[]{unknown});
        try(var scope=scope()){seed("prepared");rejected(()->scope.invoke("phone_tap",()->journal.beginOperation("phone_tap",args())));}
    }
    @Test public void unknownChildBlocksEveryFollowingCallback()throws Exception {
        try(var scope=scope()){
            scope.invoke("phone_tap",()->{String op=journal.beginOperation("phone_tap",args());journal.finishOperation(op,new JSONObject().put("ok",false).put("code","action_timeout"));return op;});
            int before=remaining();rejected(()->scope.invoke("phone_tap",()->journal.beginOperation("phone_tap",args())));assertEquals(before,remaining());
        }
    }
    @Test public void scopeDoesNotAddMissingToolPermission()throws Exception {
        rejected(()->tasks.scriptPhoneScope(task,run,epoch,parent,original,Set.of("phone_home")));
        try(var scope=scope()){rejected(()->scope.invoke("phone_home",()->journal.beginOperation("phone_home",new JSONObject())));}
    }
    @Test public void nestedCallsAndMultiplePreparedChildrenAreRejected()throws Exception {
        try(var scope=scope()){
            rejected(()->scope.invoke("phone_tap",()->scope.invoke("phone_tap",()->"nested")));
            scope.invoke("phone_tap",()->{String op=journal.beginOperation("phone_tap",args());int before=remaining();rejected(()->journal.beginOperation("phone_tap",args()));assertEquals(before,remaining());journal.finishOperation(op,new JSONObject().put("ok",true));return op;});
            assertEquals(2,tasks.get(task,false).getJSONArray("operations").length());
        }
    }
    @Test public void cancellationClosedScopeAndParentCompletionStopCallback()throws Exception {
        var scope=scope();scope.close();rejected(()->scope.invoke("phone_tap",()->"closed"));
        try(var next=scope()){original.cancel();rejected(()->next.invoke("phone_tap",()->journal.beginOperation("phone_tap",args())));}
    }
    @Test public void exactPreparedArgumentsStillApplyInsideNativeScope()throws Exception {
        try(var scope=scope()){
            scope.invoke("phone_tap",()->{String op=journal.beginOperation("phone_tap",args());rejected(()->tasks.requirePreparedCapability(task,run,epoch,op,"phone_tap",new JSONObject().put("x",11).put("y",20)));tasks.requirePreparedCapability(task,run,epoch,op,"phone_tap",args());journal.finishOperation(op,new JSONObject().put("ok",true));return op;});
        }
    }
}
