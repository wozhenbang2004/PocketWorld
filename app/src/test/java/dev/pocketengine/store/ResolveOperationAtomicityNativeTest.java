package dev.pocketengine.store;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Original Task/Run/Operation rows and real SQLite trigger faults, without dispatching a phone action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ResolveOperationAtomicityNativeTest {
    private static SQLiteDatabase db(TaskStore store)throws Exception {
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);
    }
    private static String row(TaskStore store,String table,String column,String id)throws Exception {
        return row(db(store),table,column,id);
    }
    private static String row(SQLiteDatabase source,String table,String column,String id)throws Exception {
        try(Cursor cursor=source.rawQuery("SELECT * FROM "+table+" WHERE "+column+"=?",new String[]{id})){
            assertTrue(cursor.moveToFirst());JSONObject result=new JSONObject();
            for(int i=0;i<cursor.getColumnCount();i++)result.put(cursor.getColumnName(i),cursor.isNull(i)?JSONObject.NULL:cursor.getString(i));
            assertFalse(cursor.moveToNext());return result.toString();
        }
    }
    private static int count(TaskStore store,String table,String task)throws Exception {
        try(Cursor cursor=db(store).rawQuery("SELECT COUNT(*) FROM "+table+" WHERE task_id=?",new String[]{task})){assertTrue(cursor.moveToFirst());return cursor.getInt(0);}
    }
    private static JSONObject operation(TaskStore store,String task,String operation)throws Exception {
        var rows=store.get(task,false).getJSONArray("operations");
        for(int i=0;i<rows.length();i++)if(operation.equals(rows.getJSONObject(i).getString("operationId")))return rows.getJSONObject(i);
        throw new AssertionError("Missing original operation");
    }
    private static final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();
        TaskStore store=new TaskStore(context);
        final String task,run,operation;
        String coldTask,coldOperation;
        Fixture()throws Exception {
            task=store.create("核对原动作，不重放").getString("id");JSONObject attempt=store.startAttempt(task,"main_screen",4);run=attempt.getString("runId");
            RunControl control=new RunControl();control.bindHostRun(task,run,attempt.getInt("coordinatorEpoch"));
            store.status(task,"running","fixture original run");RunJournal journal=store.journal(task,run,attempt.getInt("coordinatorEpoch"),control);
            operation=journal.beginOperation("phone_back",new JSONObject().put("originalArgument","retained"));
            journal.finishOperation(operation,new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("code","phone_result_unknown").put("replayAllowed",false));
            store.endAttempt(task,run,"interrupted","fixture original unknown result",new JSONObject());
            assertEquals("unknown",operation(store,task,operation).getString("state"));assertEquals("reconciling",store.get(task,false).getString("status"));
        }
        void reopen()throws Exception {
            db(store).close();
            try(SQLiteDatabase cold=SQLiteDatabase.openDatabase(context.getDatabasePath("pocket-world.db").getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)){
                coldTask=row(cold,"tasks","task_id",task);coldOperation=row(cold,"operations","operation_id",operation);
            }
            store=new TaskStore(context);
        }
        public void close()throws Exception {db(store).close();}
    }

    private static void taskWriteFailure(String timing)throws Exception {
        try(Fixture f=new Fixture()){
            String originalOperation=row(f.store,"operations","operation_id",f.operation),originalTask=row(f.store,"tasks","task_id",f.task),originalRun=row(f.store,"runs","run_id",f.run);
            String marker="fixture_resolve_task_write_"+timing.toLowerCase(java.util.Locale.ROOT);
            String trigger="resolve_task_fault_"+f.operation.replace("-","");
            db(f.store).execSQL("CREATE TRIGGER "+trigger+" "+timing+" UPDATE ON tasks WHEN NEW.task_id='"+f.task+"' AND EXISTS (SELECT 1 FROM operations WHERE operation_id='"+f.operation+"' AND state='resolved') BEGIN SELECT RAISE(ABORT,'"+marker+"'); END");
            SQLiteException failure=assertThrows(SQLiteException.class,()->f.store.resolveOperation(f.task,f.operation,"occurred"));assertTrue(failure.toString(),failure.getMessage().contains(marker));
            String failedOperation=row(f.store,"operations","operation_id",f.operation),failedTask=row(f.store,"tasks","task_id",f.task);
            f.reopen();
            assertEquals(originalOperation,f.coldOperation);assertEquals(originalTask,f.coldTask);
            assertEquals("The original unknown receipt must survive a cold reopen",originalOperation,row(f.store,"operations","operation_id",f.operation));
            assertEquals(originalTask,row(f.store,"tasks","task_id",f.task));assertEquals(originalRun,row(f.store,"runs","run_id",f.run));
            assertEquals(originalOperation,failedOperation);assertEquals(originalTask,failedTask);assertTrue(f.store.hasUnresolved(f.task));
            assertThrows(IllegalStateException.class,()->f.store.startAttempt(f.task,"main_screen",4));
            assertEquals(1,count(f.store,"operations",f.task));assertEquals(1,count(f.store,"runs",f.task));
            db(f.store).execSQL("DROP TRIGGER "+trigger);
            assertEquals("paused",f.store.resolveOperation(f.task,f.operation,"occurred").getString("status"));
            assertFalse(f.store.hasUnresolved(f.task));assertEquals(1,count(f.store,"operations",f.task));assertEquals(1,count(f.store,"runs",f.task));
        }
    }
    @Test public void beforeTaskWriteFailurePreservesUnknownAcrossColdReopen()throws Exception {taskWriteFailure("BEFORE");}
    @Test public void afterTaskWriteFailurePreservesUnknownAcrossColdReopen()throws Exception {taskWriteFailure("AFTER");}

    private static void successfulResolution(String outcome)throws Exception {
        try(Fixture f=new Fixture()){
            String originalRun=row(f.store,"runs","run_id",f.run);JSONObject original=operation(f.store,f.task,f.operation);
            JSONObject resolved=f.store.resolveOperation(f.task,f.operation,outcome);assertEquals("paused",resolved.getString("status"));assertEquals("动作结果已核对，可以继续原任务",resolved.getString("summary"));
            String committedOperation=row(f.store,"operations","operation_id",f.operation),committedTask=row(f.store,"tasks","task_id",f.task);
            f.reopen();JSONObject actual=operation(f.store,f.task,f.operation),receipt=actual.getJSONObject("receipt");
            assertEquals("resolved",actual.getString("state"));assertEquals("owner_reconciliation",receipt.getString("source"));assertEquals(outcome,receipt.getString("outcome"));assertTrue(receipt.getLong("at")>0);
            assertEquals(original.getString("runId"),actual.getString("runId"));assertEquals(original.getString("tool"),actual.getString("tool"));assertEquals(original.getJSONObject("arguments").toString(),actual.getJSONObject("arguments").toString());
            assertEquals("paused",f.store.get(f.task,false).getString("status"));assertFalse(f.store.hasUnresolved(f.task));
            assertEquals(committedOperation,f.coldOperation);assertEquals(committedTask,f.coldTask);assertEquals(committedOperation,row(f.store,"operations","operation_id",f.operation));assertEquals(originalRun,row(f.store,"runs","run_id",f.run));
            assertEquals(1,count(f.store,"operations",f.task));assertEquals(1,count(f.store,"runs",f.task));
        }
    }
    @Test public void occurredResolutionCommitsReceiptAndPausedTaskAcrossColdReopen()throws Exception {successfulResolution("occurred");}
    @Test public void notOccurredResolutionCommitsReceiptAndPausedTaskAcrossColdReopen()throws Exception {successfulResolution("not_occurred");}

    @Test public void resolvingOneOperationDoesNotSettleAnotherUnknown()throws Exception {
        try(Fixture f=new Fixture()){
            // A second persisted unknown is a history fixture, not another tool dispatch or grant.
            String other=UUID.randomUUID().toString();db(f.store).execSQL("INSERT INTO operations(operation_id,task_id,run_id,tool,arguments,state,receipt,created_at) SELECT ?,task_id,run_id,tool,arguments,state,receipt,created_at+1 FROM operations WHERE operation_id=?",new Object[]{other,f.operation});
            String originalOther=row(f.store,"operations","operation_id",other),originalRun=row(f.store,"runs","run_id",f.run);
            assertEquals("reconciling",f.store.resolveOperation(f.task,f.operation,"not_occurred").getString("status"));f.reopen();
            assertEquals("resolved",operation(f.store,f.task,f.operation).getString("state"));assertEquals(originalOther,row(f.store,"operations","operation_id",other));assertTrue(f.store.hasUnresolved(f.task));
            assertEquals("reconciling",f.store.get(f.task,false).getString("status"));assertEquals(originalRun,row(f.store,"runs","run_id",f.run));assertEquals(2,count(f.store,"operations",f.task));assertEquals(1,count(f.store,"runs",f.task));
        }
    }
    @Test public void invalidOutcomeOrRepeatedResolutionLeavesOriginalRowsUnchanged()throws Exception {
        try(Fixture f=new Fixture()){
            String originalOperation=row(f.store,"operations","operation_id",f.operation),originalTask=row(f.store,"tasks","task_id",f.task);
            assertThrows(IllegalArgumentException.class,()->f.store.resolveOperation(f.task,f.operation,"unknown"));
            assertEquals(originalOperation,row(f.store,"operations","operation_id",f.operation));assertEquals(originalTask,row(f.store,"tasks","task_id",f.task));
            f.store.resolveOperation(f.task,f.operation,"occurred");String resolvedOperation=row(f.store,"operations","operation_id",f.operation),resolvedTask=row(f.store,"tasks","task_id",f.task);
            assertThrows(IllegalArgumentException.class,()->f.store.resolveOperation(f.task,f.operation,"not_occurred"));
            assertEquals(resolvedOperation,row(f.store,"operations","operation_id",f.operation));assertEquals(resolvedTask,row(f.store,"tasks","task_id",f.task));
        }
    }
}
