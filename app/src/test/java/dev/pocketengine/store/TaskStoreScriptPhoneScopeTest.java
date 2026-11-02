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
    private static void rejected(Action action)throws Exception {try{action.run();fail("Expected native rejection");}catch(SecurityException|IllegalStateException|InterruptedException expected){}}
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
        int before=remaining();
        try(var scope=scope()){
            // Runner installs callbacks before the original parent host performs its first
            // exact admission. Installing a scope cannot forbid that legitimate admission.
            tasks.requirePreparedCapability(task,run,epoch,parent,"skills_execute",new JSONObject().put("scriptPath","task.py"));
            rejected(()->journal.beginOperation("phone_tap",args()));
            scope.invoke("phone_tap",()->{
                // The native child invocation may never reuse its parent's prepared identity.
                rejected(()->tasks.requirePreparedCapability(task,run,epoch,parent,"skills_execute",new JSONObject().put("scriptPath","task.py")));
                return null;
            });
        }
        assertEquals(before,remaining());
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
    @Test public void callbackCeilingNeverRefillsOriginalBudget()throws Exception {
        try(var scope=scope()){
            for(int i=0;i<32;i++)scope.invoke("phone_tap",()->{String op=journal.beginOperation("phone_tap",args());journal.finishOperation(op,new JSONObject().put("ok",true));return op;});
            assertEquals(7,remaining());rejected(()->scope.invoke("phone_tap",()->journal.beginOperation("phone_tap",args())));assertEquals(7,remaining());
        }
    }
    @Test public void parentCompletionAndPermissionRevocationCannotBeReopened()throws Exception {
        try(var scope=scope()){
            tasks.authorizeTools(task,run,new JSONArray().put("skills_execute"));int before=remaining();rejected(()->scope.invoke("phone_tap",()->journal.beginOperation("phone_tap",args())));assertEquals(before,remaining());
            journal.finishOperation(parent,new JSONObject().put("ok",true));rejected(()->scope.invoke("phone_tap",()->"late"));
        }
    }

    private JSONObject parentArguments()throws Exception {
        try(Cursor row=db.rawQuery("SELECT arguments FROM operations WHERE task_id=? AND operation_id=?",new String[]{task,parent})){
            assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));
        }
    }
    /** Storage-identity fixture only: real host reserve/decide/claim writes were traced
     * separately. This does not assert owner review, process launch or device effects. */
    private void replaceParentArguments(JSONObject arguments){
        ContentValues values=new ContentValues();values.put("arguments",arguments.toString());
        assertEquals(1,db.update("operations",values,"task_id=? AND operation_id=?",new String[]{task,parent}));
    }
    @Test public void nativeSkillMetadataEvolutionKeepsEveryOriginalRequestField()throws Exception {
        JSONObject request=parentArguments();String exact=TaskStore.capabilityFingerprint(request);int before=remaining();
        try(var scope=scope()){
            JSONObject metadata=new JSONObject().put("format","pocketworld.native-skill-operation.v1").put("approvalState","pending").put("dispatchState","not_dispatched");
            for(int stage=0;stage<5;stage++){
                if(stage==1)metadata.put("approvalState","consumed").put("decidedAt",1L);
                if(stage==2)metadata.put("dispatchState","claimed").put("claimedAt",2L);
                if(stage==3)metadata.put("scriptSharedArtifacts",new JSONObject().put("script_output_0",new JSONObject().put("role","script_output_0").put("path","out.txt").put("sha256","a".repeat(64)).put("bytes",1).put("claimId",UUID.randomUUID().toString()).put("attachmentId",UUID.randomUUID().toString()).put("state","publication_unknown")));
                if(stage==4)metadata.getJSONObject("scriptSharedArtifacts").getJSONObject("script_output_0").put("state","published").put("publishedAt",3L);
                JSONObject updated=new JSONObject(request.toString()).put("nativeSkill",new JSONObject(metadata.toString()));
                assertNotEquals(exact,TaskStore.capabilityFingerprint(updated));replaceParentArguments(updated);
                scope.invoke("phone_tap",()->{String op=journal.beginOperation("phone_tap",args());tasks.requirePreparedCapability(task,run,epoch,op,"phone_tap",args());journal.finishOperation(op,new JSONObject().put("ok",true));return op;});
            }
        }
        assertEquals(before-5,remaining());assertEquals(6,tasks.get(task,false).getJSONArray("operations").length());assertTrue(tasks.hasUnresolved(task));
    }
    @Test public void preexistingNativeSkillCannotBecomeModelSuppliedScopeAuthority()throws Exception {
        replaceParentArguments(parentArguments().put("nativeSkill",new JSONObject().put("format","pocketworld.native-skill-operation.v1").put("approvalState","consumed").put("dispatchState","claimed")));
        int before=remaining();rejected(()->scope());assertEquals(before,remaining());assertEquals(1,tasks.get(task,false).getJSONArray("operations").length());
    }
    @Test public void changingAnyNonNativeRequestFieldStillRejectsBeforeAnotherOperation()throws Exception {
        JSONObject exact=parentArguments().put("id",UUID.randomUUID().toString()).put("revision",1).put("digest","a".repeat(64)).put("arguments",new JSONArray().put("literal")).put("timeoutSeconds",30).put("maxOutputBytes",4096);
        replaceParentArguments(exact);int before=remaining();
        try(var scope=scope()){
            for(String key:new String[]{"scriptPath","id","revision","digest","arguments","timeoutSeconds","maxOutputBytes","unexpectedModelKey"}){
                JSONObject changed=new JSONObject(exact.toString()).put("nativeSkill",new JSONObject().put("format","pocketworld.native-skill-operation.v1"));
                switch(key){
                    case "scriptPath":changed.put(key,"other.py");break;
                    case "id":changed.put(key,UUID.randomUUID().toString());break;
                    case "revision":changed.put(key,2);break;
                    case "digest":changed.put(key,"b".repeat(64));break;
                    case "arguments":changed.put(key,new JSONArray().put("changed"));break;
                    case "timeoutSeconds":changed.put(key,31);break;
                    case "maxOutputBytes":changed.put(key,8192);break;
                    default:changed.put(key,true);
                }
                replaceParentArguments(changed);rejected(()->scope.invoke("phone_tap",()->journal.beginOperation("phone_tap",args())));assertEquals(before,remaining());assertEquals(1,tasks.get(task,false).getJSONArray("operations").length());
            }
            replaceParentArguments(new JSONObject(exact.toString()).put("nativeSkill",new JSONObject().put("format","pocketworld.native-skill-operation.v1")));
            scope.invoke("phone_tap",()->{String op=journal.beginOperation("phone_tap",args());journal.finishOperation(op,new JSONObject().put("ok",true));return op;});
        }
        assertEquals(before-1,remaining());
    }
    @Test public void nativeMetadataNeverAdmitsUnknownParentOrUnknownSibling()throws Exception {
        int before=remaining();
        try(var scope=scope()){
            replaceParentArguments(parentArguments().put("nativeSkill",new JSONObject().put("format","pocketworld.native-skill-operation.v1")));
            ContentValues state=new ContentValues();state.put("state","unknown");assertEquals(1,db.update("operations",state,"operation_id=?",new String[]{parent}));
            rejected(()->scope.invoke("phone_tap",()->journal.beginOperation("phone_tap",args())));assertEquals(before,remaining());
            state.put("state","prepared");assertEquals(1,db.update("operations",state,"operation_id=?",new String[]{parent}));
            String sibling=seed("unknown");rejected(()->scope.invoke("phone_tap",()->journal.beginOperation("phone_tap",args())));assertEquals(before,remaining());
            db.delete("operations","operation_id=?",new String[]{sibling});
            scope.invoke("phone_tap",()->{String op=journal.beginOperation("phone_tap",args());journal.finishOperation(op,new JSONObject().put("ok",true));return op;});
        }
        assertEquals(before-1,remaining());
    }
}
