package dev.pocketengine.features.automation;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Native plan permission and existing-run budget boundaries, against the actual SQLite authority. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AutomationPlanScopeTest {
    private static JSONObject j(Object... values)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static final class Fixture {
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final String project=UUID.randomUUID().toString(),plan=UUID.randomUUID().toString(),step=UUID.randomUUID().toString();final JSONObject request,task,args;final String id,run;final int epoch;final RunControl control=new RunControl();
        Fixture(boolean allow)throws Exception{
            request=j("goal","核实指定私人文件","ruleId",plan,"ruleRevision",1,"planId",plan,"commitmentId",step,"allowPrivateArtifactFreeze",allow,"verification",j("kind","workspace_artifact","projectId",project,"path","reports/result.md","requiredHeading","# Result","minBytes",16),"origin",j("kind","automation","occurrenceId",UUID.randomUUID().toString(),"planId",plan,"commitmentId",step));
            request.put("occurrenceId",request.getJSONObject("origin").getString("occurrenceId"));task=tasks.createReminderTask(request,"capabilities");id=task.getString("id");JSONObject attempt=tasks.startAttempt(id,"capabilities",4);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("workspace_create_artifact"));args=j("projectId",project,"path","reports/result.md","version",0);
        }
        JSONObject approval(JSONObject arguments)throws Exception{return tasks.requireCapabilityApproval(id,run,"workspace_create_artifact",arguments,j("feature","workspace","command","createArtifact"));}
        SQLiteDatabase db()throws Exception{Field f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
        JSONObject grant()throws Exception{try(Cursor c=db().rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(c.moveToFirst());return new JSONObject(c.getString(0));}}
    }
    @Test public void reviewedPrivateFreezeNeverOverridesDenialOrCoversAnotherFile()throws Exception{
        Fixture f=new Fixture(false);f.tasks.approveAutomationArtifactFreeze(f.id,f.run,f.args);JSONObject pending=f.approval(f.args);assertFalse(pending.getBoolean("approved"));f.tasks.decideCapabilityApproval(f.id,pending.getString("approvalId"),false);
        f.tasks.updateAutomationScope(f.id,new JSONObject(f.request.toString()).put("allowPrivateArtifactFreeze",true));f.tasks.approveAutomationArtifactFreeze(f.id,f.run,f.args);assertFalse(f.approval(f.args).getBoolean("approved"));
        JSONObject foreign=new JSONObject(f.args.toString()).put("path","private-other.md");f.tasks.approveAutomationArtifactFreeze(f.id,f.run,foreign);assertFalse(f.approval(foreign).getBoolean("approved"));assertEquals("denied",f.tasks.get(f.id,false).getJSONArray("approvals").getJSONObject(0).getString("state"));
    }
    @Test public void revokingTheReviewedScopeInvalidatesUnusedNativeApproval()throws Exception{
        Fixture f=new Fixture(true);f.tasks.approveAutomationArtifactFreeze(f.id,f.run,f.args);assertEquals("approved",f.tasks.get(f.id,false).getJSONArray("approvals").getJSONObject(0).getString("state"));
        f.tasks.updateAutomationScope(f.id,new JSONObject(f.request.toString()).put("allowPrivateArtifactFreeze",false));assertEquals("invalidated",f.tasks.get(f.id,false).getJSONArray("approvals").getJSONObject(0).getString("state"));assertFalse(f.approval(f.args).getBoolean("approved"));
        assertThrows(SecurityException.class,()->f.tasks.updateAutomationScope(f.id,new JSONObject(f.request.toString()).put("verification",new JSONObject(f.request.getJSONObject("verification").toString()).put("path","new.md"))));
    }
    @Test public void sameRunResumeExtendsOnlyTimeWithinItsWindowWithoutReplenishingBudget()throws Exception{
        Fixture f=new Fixture(true);JSONObject before=f.grant();f.control.pause();long window=System.currentTimeMillis()+60_000;f.tasks.renewAutomationRun(f.id,f.run,f.epoch,f.control,window);JSONObject after=f.grant();assertEquals(window,after.getLong("expiresAt"));assertEquals(before.getInt("remainingOperations"),after.getInt("remainingOperations"));assertEquals(before.getInt("stepCeiling"),after.getInt("stepCeiling"));assertEquals(before.getJSONArray("tools").toString(),after.getJSONArray("tools").toString());assertEquals(f.run,f.tasks.get(f.id,false).getString("runId"));assertEquals(f.epoch,f.tasks.get(f.id,false).getInt("coordinatorEpoch"));
        assertThrows(SecurityException.class,()->f.tasks.renewAutomationRun(f.id,f.run,f.epoch+1,f.control,window));assertThrows(SecurityException.class,()->f.tasks.renewAutomationRun(f.id,f.run,f.epoch,f.control,System.currentTimeMillis()-1));
    }
}
