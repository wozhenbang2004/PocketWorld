package dev.pocketengine.runtime.usage;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class InferenceTrendNativeQueryTest {
    @Test public void oversizedOriginalRowAdvancesAsUnknownWithoutChangingNativeLedger()throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);
        try{String task=tasks.create("PW-EVAL-TREND 只读原用量").getString("id");JSONObject attempt=tasks.startAttempt(task,"main_screen",4);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(task,run,epoch);
            InferenceCapture capture=new InferenceCapture(new ModelConfig("https://fixture.invalid/v1","fixture-only-key","fixture-model",false,4,10),control,tasks.journal(task,run,epoch,control).inferenceTarget(InferenceReceipt.Purpose.MAIN,""));capture.dispatch();capture.finish(InferenceReceipt.Outcome.COMPLETED);
            db.execSQL("UPDATE task_events SET body_bytes=? WHERE task_id=? AND call_id=?",new Object[]{InferenceTrendQuery.MAX_BYTES+1,task,capture.callId});
            JSONObject result=InferenceTrendQuery.read(db,System.currentTimeMillis()-60000,System.currentTimeMillis()+60000,"UTC","");
            assertFalse(result.getBoolean("hasMore"));assertEquals("",result.getString("nextCursor"));assertEquals(1,result.getInt("unreadableRows"));assertFalse(result.getBoolean("completeCoverage"));assertEquals(0,result.getJSONObject("total").getInt("attempts"));
            try(var row=db.rawQuery("SELECT body_bytes FROM task_events WHERE task_id=? AND call_id=?",new String[]{task,capture.callId})){assertTrue(row.moveToFirst());assertEquals(InferenceTrendQuery.MAX_BYTES+1,row.getInt(0));assertFalse(row.moveToNext());}
        }finally{db.close();}
    }
}
