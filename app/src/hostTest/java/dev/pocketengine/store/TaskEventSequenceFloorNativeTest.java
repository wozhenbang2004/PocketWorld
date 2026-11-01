package dev.pocketengine.store;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskEventSequenceFloorNativeTest {
    private static SQLiteDatabase db(TaskStore tasks)throws Exception {var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    private static long count(SQLiteDatabase db,String id){try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM task_events WHERE task_id=?",new String[]{id})){c.moveToFirst();return c.getLong(0);}}
    @Test public void legacySeedSurvivesTailAndAllRowsDeletionWithoutReusingAnEventIdentity()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);String id=tasks.create("只核原事件身份").getString("id");SQLiteDatabase sql=db(tasks);
        tasks.event(id,new JSONObject().put("type","fixture_original").put("value","first"));tasks.event(id,new JSONObject().put("type","fixture_original").put("value","second"));long old=TaskAuditStore.lastSequence(sql,id);assertEquals(2,old);
        sql.delete("meta","key=?",new String[]{"task_events.sequence_floor:"+id});TaskAuditStore.seedSequenceFloors(sql);
        assertEquals(1,sql.delete("task_events","task_id=? AND event_order=?",new String[]{id,Long.toString(old)}));assertEquals(old,TaskAuditStore.lastSequence(sql,id));
        tasks.event(id,new JSONObject().put("type","fixture_original").put("value","third"));long third=TaskAuditStore.lastSequence(sql,id);assertEquals(old+1,third);
        sql.delete("task_events","task_id=?",new String[]{id});assertEquals(third,TaskAuditStore.lastSequence(sql,id));TaskStore reopened=new TaskStore(app);reopened.event(id,new JSONObject().put("type","fixture_original").put("value","fourth"));assertEquals(third+1,TaskAuditStore.lastSequence(db(reopened),id));assertEquals(1,count(db(reopened),id));
        JSONObject raw=reopened.get(id,false);db(reopened).delete("tasks","task_id=?",new String[]{id});db(reopened).delete("task_events","task_id=?",new String[]{id});raw.put("events",new JSONArray().put(new JSONObject().put("seq",third+1).put("type","fixture_original")));
        db(reopened).beginTransaction();try{assertThrows(IllegalStateException.class,()->TaskAuditStore.coreAndImport(db(reopened),raw));}finally{db(reopened).endTransaction();}assertEquals(0,count(db(reopened),id));
    }
    @Test public void failedFloorWriteRollsBackTheSameOriginalEventTransaction()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);String id=tasks.create("原事件屏障事务反例").getString("id");SQLiteDatabase sql=db(tasks);long old=TaskAuditStore.lastSequence(sql,id),before=count(sql,id);
        sql.execSQL("CREATE TEMP TRIGGER fixture_sequence_floor_abort BEFORE INSERT ON meta WHEN NEW.key LIKE 'task_events.sequence_floor:%' BEGIN SELECT RAISE(ABORT,'fixture floor rejected'); END");
        try{assertThrows(Exception.class,()->tasks.event(id,new JSONObject().put("type","fixture_original")));assertEquals(before,count(sql,id));assertEquals(old,TaskAuditStore.lastSequence(sql,id));}
        finally{sql.execSQL("DROP TRIGGER fixture_sequence_floor_abort");}
        tasks.event(id,new JSONObject().put("type","fixture_original"));assertEquals(old+1,TaskAuditStore.lastSequence(sql,id));
    }
    @Test public void malformedOrExhaustedNativeFloorFailsClosedWithoutWraparoundOrBodyWrite()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);String id=tasks.create("原序号耗尽").getString("id");SQLiteDatabase sql=db(tasks);long before=count(sql,id);
        sql.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",new Object[]{"task_events.sequence_floor:"+id,"9223372036854775807"});assertThrows(IllegalStateException.class,()->tasks.event(id,new JSONObject().put("type","must_not_wrap")));assertEquals(before,count(sql,id));
        sql.execSQL("UPDATE meta SET value='1e3' WHERE key=?",new Object[]{"task_events.sequence_floor:"+id});assertThrows(IllegalStateException.class,()->TaskAuditStore.lastSequence(sql,id));assertThrows(IllegalStateException.class,()->tasks.event(id,new JSONObject().put("type","must_not_guess")));assertEquals(before,count(sql,id));
    }
}
