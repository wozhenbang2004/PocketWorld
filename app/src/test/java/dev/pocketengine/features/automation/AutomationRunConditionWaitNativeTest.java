package dev.pocketengine.features.automation;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.AutomationPlanHost;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual Book/SQLite/control boundary fixture; no phone, provider or complete Runner claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AutomationRunConditionWaitNativeTest {
    private Fixture fixture;
    private static JSONObject j(Object... values)throws Exception{JSONObject v=new JSONObject();for(int i=0;i<values.length;i+=2)v.put((String)values[i],values[i+1]);return v;}
    private static JSONObject scope()throws Exception{return j("ownerId","owner","spaceId","private");}
    private final class Fixture {
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final AutomationPlanHost host=new AutomationPlanHost(tasks);
        final AtomicBoolean online=new AtomicBoolean(true);final AutomationConditions.Source observations=now->new AutomationConditions.Snapshot(now,online.get()?"validated":"disconnected","",true,90,"");
        final AutomationModule module=new AutomationModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}),host::resolveEvidence,host,observations);
        final JSONObject request,run;final String id,planId,occurrence;final RunControl control=new RunControl();final RunJournal journal;
        Fixture()throws Exception{
            JSONObject plan=host.handle(module,"createPlan",j("plan",j("kind","explicit_user","title","临时断网后继续同一个目标","goal","保留原任务与操作预算","steps",new JSONArray().put(j("title","核对原结果","goal","保留原任务与操作预算","dueAt",System.currentTimeMillis()-1000))))).getJSONObject("plan");
            planId=plan.getString("id");plan=module.approvePlanExecution(scope(),planId,plan.getLong("revision"),j("mode","automatic","modelObservationConsent",true,"requiresNetwork",true,"maxTaskAdmissions",1,"expiresAt",System.currentTimeMillis()+3600000));
            String step=plan.getJSONArray("steps").getJSONObject(0).getString("id");request=module.preparePlanTask(scope(),planId,plan.getLong("revision"),step);occurrence=request.getString("occurrenceId");
            id=tasks.createReminderTask(request,"background").getString("id");tasks.bindCommitment(module.bindPlanTask(scope(),planId,step,occurrence));run=tasks.startAttempt(id,"background",4);control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));AutomationRunGate.bind(control,request,observations);
            journal=tasks.journal(id,run.getString("runId"),run.getInt("coordinatorEpoch"),control);
        }
        JSONObject plan()throws Exception{return module.handle("getPlan",j("scope",scope(),"id",planId)).getJSONObject("plan");}
        void waitForNetwork()throws Exception{online.set(false);assertThrows(RunControl.ConditionWaitException.class,control::check);tasks.endAutomationConditionWait(id,run.getString("runId"),control,"等待原目标运行条件",new JSONObject());}
        SQLiteDatabase db()throws Exception{Field f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
        JSONObject grant(String run)throws Exception{try(Cursor c=db().rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(c.moveToFirst());return new JSONObject(c.getString(0));}}
    }
    @After public void close()throws Exception{if(fixture!=null)fixture.db().close();}
    @Test public void persistedWaitRestoresOriginalTaskOccurrenceAndExactRemainingBudget()throws Exception{
        Fixture f=fixture=new Fixture();String operation=f.journal.beginOperation("phone_home",new JSONObject());
        f.journal.finishOperation(operation,j("ok",false,"accepted",false,"testDisposition","journal_fixture_no_phone_dispatch"));
        f.journal.checkpoint(new JSONArray().put(j("role","user","content","保留原目标")),1,new JSONObject());JSONObject oldGrant=f.grant(f.run.getString("runId"));f.waitForNetwork();
        assertEquals("paused",f.tasks.get(f.id,false).getString("status"));assertTrue(f.tasks.automationConditionWaiting(f.id));
        assertEquals(1,f.plan().getJSONObject("execution").getInt("admissions"));f.online.set(true);JSONObject resumed=f.tasks.startAttempt(f.id,"background",40);
        assertEquals(f.id,resumed.getString("id"));assertEquals(f.occurrence,resumed.getJSONObject("origin").getString("occurrenceId"));assertNotEquals(f.run.getString("runId"),resumed.getString("runId"));assertEquals(f.run.getInt("coordinatorEpoch")+1,resumed.getInt("coordinatorEpoch"));
        JSONObject current=f.grant(resumed.getString("runId"));assertEquals(oldGrant.getInt("stepCeiling"),current.getInt("stepCeiling"));assertEquals(oldGrant.getInt("remainingOperations"),current.getInt("remainingOperations"));assertEquals(3,current.getInt("remainingOperations"));
        RunControl restored=new RunControl();restored.bindHostRun(f.id,resumed.getString("runId"),resumed.getInt("coordinatorEpoch"));AutomationRunGate.bind(restored,f.request,f.observations);
        assertEquals(1,f.tasks.journal(f.id,resumed.getString("runId"),resumed.getInt("coordinatorEpoch"),restored).restore().getInt("steps"));
        f.tasks.endAttempt(f.id,f.run.getString("runId"),"cancelled","late old callback",new JSONObject());assertEquals(resumed.getString("runId"),f.tasks.get(f.id,false).getString("runId"));assertNotEquals("cancelled",f.tasks.get(f.id,false).getString("status"));
        f.control.resume();assertThrows(Exception.class,()->f.journal.beginOperation("phone_home",new JSONObject()));assertFalse(f.tasks.automationConditionWaiting(f.id));assertEquals(1,f.tasks.list().length());assertEquals(1,f.plan().getJSONObject("execution").getInt("admissions"));
    }
    @Test public void userPauseAndPayloadFlagsCannotAuthorizeAutomaticRestoration()throws Exception{
        Fixture f=fixture=new Fixture();f.tasks.finish(f.id,"paused","untrusted payload",j("automationConditionWaitId","forged","automationWaitReason","run_conditions"));assertFalse(f.tasks.automationConditionWaiting(f.id));
        f.control.pause();assertThrows(SecurityException.class,()->f.tasks.endAutomationConditionWait(f.id,f.run.getString("runId"),f.control,"not a typed native wait",new JSONObject()));
        f.control.resume();f.waitForNetwork();assertTrue(f.tasks.automationConditionWaiting(f.id));f.tasks.status(f.id,"paused","主人显式暂停");assertFalse(f.tasks.automationConditionWaiting(f.id));
        boolean retained=false;JSONArray events=f.tasks.get(f.id,false).getJSONArray("events");for(int i=0;i<events.length();i++)if("automation_condition_wait".equals(events.getJSONObject(i).optString("type")))retained=true;assertTrue(retained);
    }
    @Test public void cancellationAndOriginalExecutionPositionRemainFinalBarriers()throws Exception{
        Fixture f=fixture=new Fixture();f.waitForNetwork();assertThrows(SecurityException.class,()->f.tasks.startAttempt(f.id,"main_screen",40));
        f.tasks.requestCancel(f.id,null);f.control.cancel();assertFalse(f.tasks.automationConditionWaiting(f.id));assertThrows(SecurityException.class,()->f.tasks.startAttempt(f.id,"background",40));assertEquals(1,f.tasks.list().length());
    }
    @Test public void unknownEffectAndSpecificApprovalRemainOutsideAutomaticRecovery()throws Exception{
        Fixture f=fixture=new Fixture();String operation=f.journal.beginOperation("phone_home",new JSONObject());f.waitForNetwork();
        assertEquals("reconciling",f.tasks.get(f.id,false).getString("status"));assertFalse(f.tasks.automationConditionWaiting(f.id));assertTrue(f.tasks.hasUnresolved(f.id));assertFalse(operation.isEmpty());
    }
    @Test public void pendingSpecificApprovalIsNotChangedIntoAutomaticPermission()throws Exception{
        Fixture f=fixture=new Fixture();f.tasks.requireCapabilityApproval(f.id,f.run.getString("runId"),"phone_home",new JSONObject());f.waitForNetwork();assertFalse(f.tasks.automationConditionWaiting(f.id));assertEquals("pending",f.tasks.get(f.id,false).getJSONArray("approvals").getJSONObject(0).getString("state"));
    }
}
