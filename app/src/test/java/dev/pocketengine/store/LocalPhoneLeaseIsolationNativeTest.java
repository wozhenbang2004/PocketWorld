package dev.pocketengine.store;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import java.lang.reflect.Field;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original TaskStore and real SQLite leases. No physical executor, display or stop proof is simulated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class LocalPhoneLeaseIsolationNativeTest {
    private TaskStore store;
    private SQLiteDatabase db;
    @Before public void open()throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");store=new TaskStore(app);
        Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(store);
    }
    @After public void close(){db.close();}
    private String task()throws Exception{return store.create("原本机租约隔离测试").getString("id");}
    private void lease(String resource,String run,int epoch,long expiry){db.execSQL("INSERT INTO leases(resource_id,run_id,epoch,expires_at) VALUES(?,?,?,?)",new Object[]{resource,run,epoch,expiry});}
    private JSONObject row(String resource)throws Exception {
        try(Cursor cursor=db.rawQuery("SELECT run_id,epoch,expires_at FROM leases WHERE resource_id=?",new String[]{resource})){
            if(!cursor.moveToFirst())return null;
            return new JSONObject().put("runId",cursor.getString(0)).put("epoch",cursor.getInt(1)).put("expiresAt",cursor.getLong(2));
        }
    }
    private int runs(){try(Cursor rows=db.rawQuery("SELECT COUNT(*) FROM runs",null)){assertTrue(rows.moveToFirst());return rows.getInt(0);}}

    @Test public void expiredForegroundLeaseCannotBeReplacedByAnotherTask()throws Exception {
        JSONObject first=store.startAttempt(task(),"main_screen",4);String next=task();
        db.execSQL("UPDATE leases SET expires_at=0 WHERE resource_id='phone.local'");JSONObject before=row("phone.local");
        assertThrows(IllegalStateException.class,()->store.startAttempt(next,"main_screen",4));
        assertEquals(before.toString(),row("phone.local").toString());assertEquals(first.getString("runId"),before.getString("runId"));
        assertEquals(1,runs());assertTrue(store.get(next,false).optString("runId").isEmpty());
    }
    @Test public void expiredBackgroundLeaseStillBlocksBothLocalPhoneModes()throws Exception {
        String resource="phone.local.bg:"+UUID.randomUUID(),owner=UUID.randomUUID().toString();lease(resource,owner,7,0);
        String first=task(),second=task();
        assertThrows(IllegalStateException.class,()->store.startAttempt(first,"main_screen",4));
        assertThrows(IllegalStateException.class,()->store.startAttempt(second,"background",4));
        assertEquals(owner,row(resource).getString("runId"));assertEquals(7,row(resource).getInt("epoch"));assertEquals(0,runs());
    }
    @Test public void independentDeviceAndSimilarPrefixesAreNotLocalPhoneLeases()throws Exception {
        String other=UUID.randomUUID().toString();lease("device.other.phone.local",other,3,Long.MAX_VALUE);lease("phone.local.background:other",other,3,Long.MAX_VALUE);
        JSONObject original=store.startAttempt(task(),"main_screen",4);
        store.requireExclusivePhoneLeaseCurrent(original.getString("runId"),original.getInt("coordinatorEpoch"));
        assertEquals(other,row("device.other.phone.local").getString("runId"));assertEquals(other,row("phone.local.background:other").getString("runId"));
    }
    @Test public void currentLeaseKeepsExactRunEpochAndRejectsAnyUnreleasedLocalBackgroundRow()throws Exception {
        JSONObject original=store.startAttempt(task(),"main_screen",4);String run=original.getString("runId");int epoch=original.getInt("coordinatorEpoch");
        store.requireExclusivePhoneLeaseCurrent(run,epoch);
        assertThrows(SecurityException.class,()->store.requireExclusivePhoneLeaseCurrent(UUID.randomUUID().toString(),epoch));
        assertThrows(SecurityException.class,()->store.requireExclusivePhoneLeaseCurrent(run,epoch+1));
        String resource="phone.local.bg:"+UUID.randomUUID();lease(resource,UUID.randomUUID().toString(),1,0);
        assertThrows(SecurityException.class,()->store.requireExclusivePhoneLeaseCurrent(run,epoch));
        // Remove only this test-owned synthetic background row; no production release is inferred.
        db.delete("leases","resource_id=?",new String[]{resource});store.requireExclusivePhoneLeaseCurrent(run,epoch);
    }
    @Test public void nativeEndAllowsNextAttemptAndLateOldEndCannotReleaseItsReplacement()throws Exception {
        String id=task();JSONObject first=store.startAttempt(id,"main_screen",4);
        store.endAttempt(id,first.getString("runId"),"cancelled","原生测试结束",new JSONObject());assertNull(row("phone.local"));
        JSONObject next=store.startAttempt(id,"main_screen",4);String run=next.getString("runId");assertNotEquals(first.getString("runId"),run);
        store.endAttempt(id,first.getString("runId"),"cancelled","晚到旧结束",new JSONObject());
        store.requireExclusivePhoneLeaseCurrent(run,next.getInt("coordinatorEpoch"));assertEquals(run,row("phone.local").getString("runId"));
    }
    @Test public void originalRunPauseAndResumeKeepsItsExistingJournalAndLease()throws Exception {
        JSONObject task=store.startAttempt(task(),"main_screen",4);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
        RunControl original=new RunControl();original.bindHostRun(id,run,epoch);RunJournal journal=store.journal(id,run,epoch,original);
        original.pause();original.resume();String operation=journal.beginOperation("phone_back",new JSONObject());journal.finishOperation(operation,new JSONObject().put("ok",true).put("accepted",true));
        store.requireExclusivePhoneLeaseCurrent(run,epoch);assertEquals(1,runs());assertEquals(run,row("phone.local").getString("runId"));
    }
    @Test public void expiredLeaseDoesNotEraseAnUnknownOriginalOperationOrCreateAnotherRun()throws Exception {
        JSONObject task=store.startAttempt(task(),"main_screen",4);String id=task.getString("id"),run=task.getString("runId");RunControl original=new RunControl();original.bindHostRun(id,run,task.getInt("coordinatorEpoch"));
        RunJournal journal=store.journal(id,run,task.getInt("coordinatorEpoch"),original);String operation=journal.beginOperation("phone_back",new JSONObject());
        journal.finishOperation(operation,new JSONObject().put("accepted",JSONObject.NULL).put("code","action_unknown"));db.execSQL("UPDATE leases SET expires_at=0 WHERE resource_id='phone.local'");
        assertThrows(IllegalStateException.class,()->store.startAttempt(id,"main_screen",4));String other=task();assertThrows(IllegalStateException.class,()->store.startAttempt(other,"main_screen",4));
        assertTrue(store.hasUnresolved(id));assertEquals(run,row("phone.local").getString("runId"));assertEquals(1,runs());
    }
}
