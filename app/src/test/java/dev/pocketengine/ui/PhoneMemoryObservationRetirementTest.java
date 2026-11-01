package dev.pocketengine.ui;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.runtime.AgentRunner;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Exact old checkpoint migration through the real native Book/TaskStore/Runner/local HTTP path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneMemoryObservationRetirementTest {
    @Before public void keys(){new TaskMemoryNativeWorkflowTest().keys();}
    private JSONObject turn(TaskMemoryNativeWorkflowTest.Fixture f,String id)throws Exception{
        JSONObject attempt=f.app.tasks.startAttempt(id,"main_screen",8);RunControl control=new RunControl();control.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));
        PhonePort phone=new PhonePort(){public boolean isConnected(){return true;}public JSONObject observe(boolean image,RunControl live)throws Exception{return new JSONObject().put("ok",true).put("observationId","fixture-native-current").put("screen","safe current native page");}public JSONObject execute(String name,JSONObject arguments,RunControl live){throw new AssertionError("No phone action expected");}public void clearReferences(){}};
        var capabilities=new HostToolSupervisor(f.app.features,f.app.tasks,id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),false);
        RunOutcome result=new AgentRunner(phone,capabilities).run(id,"核对当前项目约定，不执行手机动作",f.app.settings.get(),control,event->{},f.app.tasks.journal(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),control));
        assertEquals(result.summary,"needs_confirmation",result.status);f.app.tasks.endAttempt(id,attempt.getString("runId"),result.status,result.summary,result.evidence);return f.app.tasks.get(id,false);
    }
    private JSONObject checkpoint(SQLiteDatabase db,String run)throws Exception{try(Cursor c=db.rawQuery("SELECT checkpoint FROM runs WHERE run_id=?",new String[]{run})){assertTrue(c.moveToFirst());return new JSONObject(c.getString(0));}}
    @Test public void oldUntaggedObservationSummaryIsRetiredButAuditAndRealObservationSurviveResume()throws Exception{
        try(TaskMemoryNativeWorkflowTest.Fixture f=new TaskMemoryNativeWorkflowTest.Fixture()){
            String id=f.app.tasks.create("核对项目约定，不执行任何手机动作").getString("id");JSONObject first=turn(f,id);String run=first.getString("runId");
            var field=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(f.app.tasks);
            JSONObject prior=checkpoint(db,run),audit=new JSONObject(prior.getJSONObject("observation").toString());assertTrue(audit.getString("modelSummary").contains("B1_OLD_REFERENCE_741"));
            // Explicit migration fixture: reproduce the old Runner's generated envelope in the
            // original journal. This does not fabricate a reference-provided receipt or source.
            JSONArray messages=prior.getJSONArray("messages");JSONObject legacy=null;
            for(int i=messages.length()-1;i>=0;i--){JSONObject row=messages.getJSONObject(i);if("user".equals(row.optString("role"))&&row.optJSONArray("content")!=null){legacy=row;break;}}
            assertNotNull(legacy);legacy.remove("hostObservation");JSONObject part=legacy.getJSONArray("content").getJSONObject(0);
            String prefix=dev.pocketengine.features.memory.TaskMemoryHistory.OBSERVATION_PREFIX;JSONObject body=new JSONObject(part.getString("text").substring(prefix.length()));
            body.put("modelSummary",audit.getString("modelSummary")).put("memoryContext",audit.getJSONObject("memoryContext")).put("memoryReference",audit.getJSONObject("memoryReference"));part.put("text",prefix+body);
            ContentValues values=new ContentValues();values.put("checkpoint",prior.toString());assertEquals(1,db.update("runs",values,"run_id=?",new String[]{run}));
            JSONObject proof=f.command("readTaskMemoryReceipt",f.binding(first)),selected=proof.getJSONArray("records").getJSONObject(0);f.command("forgetTaskMemoryReviewed",f.review(first,selected));
            JSONObject retired=checkpoint(db,run);assertEquals("audit observation remains original",audit.toString(),retired.getJSONObject("observation").toString());
            assertTrue(retired.getJSONArray("messages").toString().contains("safe current native page"));assertFalse(retired.getJSONArray("messages").toString().contains("B1_OLD_REFERENCE_741"));
            f.answer="当前没有保存的旧约定";turn(f,id);assertEquals(2,f.requests.size());assertFalse(f.requests.get(1).toString().contains("B1_OLD_REFERENCE_741"));
            assertEquals("same original task",id,f.app.tasks.get(id,false).getString("id"));assertFalse(f.app.tasks.get(id,false).getString("runId").equals(run));
        }
    }
}
