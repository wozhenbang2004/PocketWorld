package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual SQLite transactions and rows matching the existing native journal schema. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class OriginalRemoteStopClaimTest {
    private static final String BINDING="a".repeat(64);
    private interface Checked {void run()throws Exception;}
    private static void refused(Checked action)throws Exception {assertThrows(SecurityException.class,action::run);}
    private static final class Fixture implements AutoCloseable {
        final Object owner=new Object();final java.io.File file;SQLiteDatabase db;int recorded;
        Fixture()throws Exception {
            file=new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(),"original-stop-"+java.util.UUID.randomUUID()+".db");db=SQLiteDatabase.openOrCreateDatabase(file,null);
            db.execSQL("CREATE TABLE tasks(task_id TEXT PRIMARY KEY,document TEXT NOT NULL,updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE runs(run_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,epoch INTEGER NOT NULL,status TEXT NOT NULL)");
            db.execSQL("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,tool TEXT NOT NULL,arguments TEXT NOT NULL,state TEXT NOT NULL,receipt TEXT,created_at INTEGER NOT NULL)");
            JSONObject receipt=actual("running");JSONObject task=new JSONObject().put("id","original-task").put("feature","remote").put("actorId","owner").put("spaceId","private").put("runId","original-operation").put("operationId","original-operation").put("coordinatorEpoch",3).put("externalId","original-job").put("externalRefs",new JSONObject().put("profileId","computer")).put("evidence",receipt).put("origin",new JSONObject().put("kind","remote").put("labels",new org.json.JSONArray().put("original"))).put("status","stopping").put("executionStatus","running").put("cancelRequested",true);
            task(task);db.execSQL("INSERT INTO runs VALUES('original-operation','original-task',3,'running')");
            db.execSQL("INSERT INTO operations VALUES(?,?,?,?,?,?,?,?)",new Object[]{"original-operation","original-task","original-operation","remote_job",new JSONObject().put("taskId","original-task").put("runId","original-operation").put("coordinatorEpoch",3).toString(),"remote_job_active",receipt.toString(),1});
        }
        JSONObject task()throws Exception {try(Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id='original-task'",null)){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}}
        void task(JSONObject value){ContentValues row=new ContentValues();row.put("task_id","original-task");row.put("document",value.toString());row.put("updated_at",1);db.insertWithOnConflict("tasks",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
        OriginalRemoteStopClaim issue()throws Exception{return OriginalRemoteStopClaim.issue(owner,db,this::task,actual->recorded++,"original-task","original-operation",3,BINDING);}
        String state(){try(Cursor row=db.rawQuery("SELECT state FROM operations WHERE tool='remote_original_stop'",null)){assertTrue(row.moveToFirst());return row.getString(0);}}
        JSONObject receipt()throws Exception {try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE tool='remote_original_stop'",null)){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}}
        void reopen(){db.close();db=SQLiteDatabase.openOrCreateDatabase(file,null);}
        public void close(){db.close();SQLiteDatabase.deleteDatabase(file);}
    }
    private static JSONObject actual(String status)throws Exception{return new JSONObject().put("id","original-job").put("operationId","original-operation").put("profileId","computer").put("serviceBase","http://127.0.0.1:8911").put("status",status);}
    @Test public void ticketIsPrivateAndWrongExpectedGenerationCannotCreateAStop()throws Exception {
        assertEquals(0,OriginalRemoteStopClaim.class.getConstructors().length);
        try(Fixture f=new Fixture()){
            refused(()->OriginalRemoteStopClaim.issue(f.owner,f.db,f::task,actual->{},"original-task","original-operation",4,BINDING));
            try(Cursor row=f.db.rawQuery("SELECT count(*) FROM operations WHERE tool='remote_original_stop'",null)){assertTrue(row.moveToFirst());assertEquals(0,row.getInt(0));}
        }
    }
    @Test public void originalIdentityIsRecheckedBeforePostNotOnlyWhenTheTicketIsIssued()throws Exception {
        for(String field:new String[]{"runId","coordinatorEpoch","externalId","operationId","profileId","serviceBase","actorId","spaceId","origin"})try(Fixture f=new Fixture()){
            OriginalRemoteStopClaim ticket=f.issue();ticket.beginDispatch();JSONObject changed=f.task();
            if(field.equals("profileId"))changed.getJSONObject("externalRefs").put(field,"replacement");else if(field.equals("serviceBase"))changed.getJSONObject("evidence").put(field,"http://127.0.0.1:8912");else if(field.equals("coordinatorEpoch"))changed.put(field,4);else if(field.equals("origin"))changed.put(field,new JSONObject().put("kind","replacement"));else changed.put(field,"replacement");f.task(changed);
            refused(ticket::beforeDispatch);refused(ticket::beginPost);assertEquals(field,"prepared",f.state());assertEquals(0,f.recorded);
        }
    }
    @Test public void sqlRunAndOriginalOperationRowsMustStillMatch()throws Exception {
        for(String mutation:new String[]{"UPDATE runs SET epoch=4","UPDATE operations SET run_id='replacement' WHERE tool='remote_job'","UPDATE operations SET receipt='{}' WHERE tool='remote_job'"})try(Fixture f=new Fixture()){
            OriginalRemoteStopClaim ticket=f.issue();ticket.beginDispatch();f.db.execSQL(mutation);refused(ticket::beginPost);assertEquals("prepared",f.state());
        }
    }
    @Test public void onlyOneHolderCanEnterPostAndRestartCanOnlyQuery()throws Exception {
        try(Fixture f=new Fixture()){
            OriginalRemoteStopClaim first=f.issue(),second=f.issue();first.beginDispatch();second.beginDispatch();first.beginPost();assertEquals("unknown",f.state());
            refused(first::beginPost);refused(first::beginDispatch);refused(second::beginPost);
            f.reopen();OriginalRemoteStopClaim recovered=f.issue();assertFalse(recovered.canDispatch());recovered.beginDispatch();recovered.beforeDispatch();refused(recovered::beginPost);assertEquals("unknown",f.state());
            refused(()->OriginalRemoteStopClaim.issue(f.owner,f.db,f::task,actual->{},"original-task","original-operation",3,"b".repeat(64)));
        }
    }
    @Test public void originalParentGenerationAndRelationshipAreRecheckedBeforePost()throws Exception {
        for(boolean changeEpoch:new boolean[]{true,false})try(Fixture f=new Fixture()){
            JSONObject child=f.task().put("parentTaskId","parent-task").put("parentRunId","parent-run");f.task(child);
            JSONObject parent=new JSONObject().put("id","parent-task").put("runId","parent-run").put("coordinatorEpoch",7).put("relatedTasks",new org.json.JSONArray().put(new JSONObject().put("taskId","original-task").put("runId","original-operation").put("feature","remote")));
            f.db.execSQL("INSERT INTO tasks VALUES(?,?,1)",new Object[]{"parent-task",parent.toString()});f.db.execSQL("INSERT INTO runs VALUES('parent-run','parent-task',7,'running')");
            OriginalRemoteStopClaim ticket=f.issue();ticket.beginDispatch();
            if(changeEpoch)parent.put("coordinatorEpoch",8);else parent.put("relatedTasks",new org.json.JSONArray());f.db.execSQL("UPDATE tasks SET document=? WHERE task_id='parent-task'",new Object[]{parent.toString()});
            refused(ticket::beginPost);assertEquals("prepared",f.state());
        }
    }
    @Test public void matchingIdentityOrTerminalWordAloneDoesNotSettleUnknownEffects()throws Exception {
        try(Fixture f=new Fixture()){
            OriginalRemoteStopClaim ticket=f.issue();ticket.beginDispatch();ticket.beginPost();ticket.record(actual("stopping").put("originalCancellation",new JSONObject().put("identityMatched",true).put("effectsReversed",false)));assertEquals("unknown",f.state());
            ticket.record(actual("cancelled"));assertEquals("unknown",f.state());
            ticket.record(actual("cancelled").put("dispatchFenceFinal",true).put("resourcesHeld",true));assertEquals("unknown",f.state());
            ticket.record(actual("cancelled").put("dispatchFenceFinal",true).put("resourcesHeld",false).put("output","not part of stop metadata"));assertEquals("receipt",f.state());assertFalse(f.receipt().has("output"));assertEquals(4,f.recorded);
            ticket.record(actual("running"));assertEquals("receipt",f.state());assertEquals("cancelled",f.receipt().getString("status"));
        }
    }
    @Test public void continuedChildRetainsItsOriginalParentLinkAndCurrentSqlRun()throws Exception {
        try(Fixture f=new Fixture()){
            f.task(f.task().put("parentTaskId","parent-task").put("parentRunId","parent-run").put("parentOperationId","parent-operation").put("parentRequestHash","original-parent-hash").put("remoteRequestHash","original-request-hash"));
            JSONObject parent=new JSONObject().put("id","parent-task").put("runId","parent-run").put("coordinatorEpoch",7).put("relatedTasks",new org.json.JSONArray().put(new JSONObject().put("taskId","original-task").put("runId","original-task").put("feature","remote").put("parentOperationId","parent-operation")));
            f.db.execSQL("INSERT INTO tasks VALUES(?,?,1)",new Object[]{"parent-task",parent.toString()});f.db.execSQL("INSERT INTO runs VALUES('parent-run','parent-task',7,'running')");
            OriginalRemoteStopClaim ticket=f.issue();ticket.beginDispatch();ticket.beforeDispatch();
            JSONObject changed=f.task().put("parentOperationId","replacement");f.task(changed);refused(ticket::beginPost);assertEquals("prepared",f.state());
        }
    }
    @Test public void changedTargetCopyOrForeignOrStaleReceiptCannotChangeOriginalTask()throws Exception {
        try(Fixture f=new Fixture()){
            OriginalRemoteStopClaim ticket=f.issue();ticket.target().put("jobId","replacement");assertEquals("original-job",ticket.target().getString("jobId"));ticket.beginDispatch();ticket.beginPost();
            refused(()->ticket.record(actual("cancelled").put("operationId","foreign")));assertEquals(0,f.recorded);assertEquals("unknown",f.state());
            JSONObject changed=f.task();changed.put("coordinatorEpoch",4);f.task(changed);refused(()->ticket.record(actual("cancelled")));assertEquals(0,f.recorded);assertEquals("unknown",f.state());
        }
    }
    @Test public void alreadyReleasedOriginalIsReadOnlyAndDoesNotCreatePendingEffects()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject task=f.task().put("executionStatus","cancelled");task.getJSONObject("evidence").put("dispatchFenceFinal",true).put("resourcesHeld",false);f.task(task);
            OriginalRemoteStopClaim ticket=f.issue();assertFalse(ticket.canDispatch());assertEquals("receipt",f.state());ticket.beginDispatch();refused(ticket::beginPost);
        }
    }
}
