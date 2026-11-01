package dev.pocketengine.features.automation;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.AutomationPlanHost;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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

/** Original SQL/run/control and native stop entry, without provider or physical-phone execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AutomationConditionWaitDeliveryRegressionTest {
    private Fixture fixture;
    private EngineApplication engine;
    private static JSONObject j(Object... values)throws Exception{JSONObject v=new JSONObject();for(int i=0;i<values.length;i+=2)v.put((String)values[i],values[i+1]);return v;}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static SQLiteDatabase db(TaskStore tasks)throws Exception{return (SQLiteDatabase)field(tasks,"db");}
    private static JSONObject grant(TaskStore tasks,String run)throws Exception{try(Cursor c=db(tasks).rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(c.moveToFirst());return new JSONObject(c.getString(0));}}
    private final class Fixture {
        final TaskStore tasks;final AtomicBoolean online=new AtomicBoolean(true);final AutomationConditions.Source observations=now->new AutomationConditions.Snapshot(now,online.get()?"validated":"disconnected","",true,90,"");
        final JSONObject request,run;final String id;final RunControl control=new RunControl();final RunJournal journal;
        Fixture(TaskStore store)throws Exception{
            tasks=store;Application app=RuntimeEnvironment.getApplication();AutomationPlanHost host=new AutomationPlanHost(tasks);
            AutomationModule module=new AutomationModule(new FeatureContext(app,null,null,event->{}),host::resolveEvidence,host,observations);JSONObject scope=j("ownerId","owner","spaceId","private");
            JSONObject plan=host.handle(module,"createPlan",j("plan",j("kind","explicit_user","title","原等待预算与停止","goal","保留这个原目标的预算与取消边界","steps",new JSONArray().put(j("title","原目标","goal","保留这个原目标的预算与取消边界","dueAt",System.currentTimeMillis()-1000))))).getJSONObject("plan");
            String planId=plan.getString("id");plan=module.approvePlanExecution(scope,planId,plan.getLong("revision"),j("mode","automatic","modelObservationConsent",true,"requiresNetwork",true,"maxTaskAdmissions",1,"expiresAt",System.currentTimeMillis()+3600000));
            String step=plan.getJSONArray("steps").getJSONObject(0).getString("id");request=module.preparePlanTask(scope,planId,plan.getLong("revision"),step);
            id=tasks.createReminderTask(request,"background").getString("id");tasks.bindCommitment(module.bindPlanTask(scope,planId,step,request.getString("occurrenceId")));
            run=tasks.startAttempt(id,"background",4);control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));AutomationRunGate.bind(control,request,observations);journal=tasks.journal(id,run.getString("runId"),run.getInt("coordinatorEpoch"),control);
        }
        void consume()throws Exception{String op=journal.beginOperation("phone_home",new JSONObject());journal.finishOperation(op,j("ok",false,"accepted",false,"testDisposition","journal_only_no_phone_dispatch"));journal.checkpoint(new JSONArray().put(j("role","user","content","原目标")),1,new JSONObject());}
        void waitForNetwork()throws Exception{online.set(false);assertThrows(RunControl.ConditionWaitException.class,control::check);tasks.endAutomationConditionWait(id,run.getString("runId"),control,"等待原运行条件",new JSONObject());}
    }
    @After public void close()throws Exception{
        if(engine!=null){engine.features.close();ExecutorService workers=(ExecutorService)field(engine,"capabilityWorkers");workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);}
        if(fixture!=null)db(fixture.tasks).close();
    }
    @Test public void ownerPauseRevokesAutomaticRecoveryButNeverAddsBudgetToThisOccurrence()throws Exception{
        Fixture f=fixture=new Fixture(new TaskStore(RuntimeEnvironment.getApplication()));f.consume();JSONObject before=grant(f.tasks,f.run.getString("runId"));f.waitForNetwork();
        f.tasks.status(f.id,"paused","主人明确暂停");assertFalse(f.tasks.automationConditionWaiting(f.id));f.online.set(true);JSONObject next=f.tasks.startAttempt(f.id,"background",40),after=grant(f.tasks,next.getString("runId"));
        assertEquals(before.getInt("stepCeiling"),after.getInt("stepCeiling"));assertEquals(before.getInt("remainingOperations"),after.getInt("remainingOperations"));assertEquals(3,after.getInt("remainingOperations"));
        f.tasks.endAttempt(f.id,next.getString("runId"),"paused","主人再次暂停",new JSONObject());JSONObject again=f.tasks.startAttempt(f.id,"background",40);
        assertEquals(before.getInt("stepCeiling"),grant(f.tasks,again.getString("runId")).getInt("stepCeiling"));assertEquals(f.request.getString("occurrenceId"),again.getJSONObject("origin").getString("occurrenceId"));
        f.tasks.requestCancel(f.id,again.getString("runId"));f.tasks.endAttempt(f.id,again.getString("runId"),"paused","late pause callback",new JSONObject());assertEquals("cancelled",f.tasks.get(f.id,false).getString("status"));assertThrows(SecurityException.class,()->f.tasks.startAttempt(f.id,"background",40));
    }
    @Test @Config(application=EngineApplication.class) public void mainTaskStopPersistsTheClosedWaitCancellationWithoutStoppingAnotherTask()throws Exception{
        engine=(EngineApplication)RuntimeEnvironment.getApplication();Fixture f=fixture=new Fixture(engine.tasks);String other=f.tasks.createCapabilityTask("不相关原任务","").getString("id");String otherStatus=f.tasks.get(other,false).getString("status");f.waitForNetwork();assertNull(engine.control(f.id));assertTrue(f.tasks.automationConditionWaiting(f.id));
        engine.dispatchOwnerAction("stopTask",j("taskId",f.id));JSONObject stopped=f.tasks.get(f.id,false);assertTrue(stopped.getBoolean("cancelRequested"));assertEquals("cancelled",stopped.getString("status"));assertEquals(f.run.getString("runId"),stopped.getString("runId"));assertEquals(f.run.getInt("coordinatorEpoch"),stopped.getInt("coordinatorEpoch"));assertEquals(f.request.getString("occurrenceId"),stopped.getJSONObject("origin").getString("occurrenceId"));assertEquals(otherStatus,f.tasks.get(other,false).getString("status"));
        f.online.set(true);assertFalse(f.tasks.automationConditionWaiting(f.id));assertThrows(SecurityException.class,()->f.tasks.startAttempt(f.id,"background",40));
    }
    @Test public void actualRunnerRetainsTypedWaitInsteadOfReportingPermanentCancellation()throws Exception{
        Fixture f=fixture=new Fixture(new TaskStore(RuntimeEnvironment.getApplication()));f.online.set(false);
        var result=new AgentRunner(new dev.pocketengine.phone.AndroidPhonePort(RuntimeEnvironment.getApplication()),null).run(f.id,"保留原目标",new ModelConfig("https://model.invalid","","fixture-model",false,4,10),f.control,event->{},f.journal);
        assertEquals("paused",result.status);assertFalse(f.control.isCancelled());assertNotNull(f.control.conditionWait());assertEquals(0,f.tasks.get(f.id,false).getJSONArray("operations").length());
        f.tasks.endAutomationConditionWait(f.id,f.run.getString("runId"),f.control,result.summary,result.evidence);assertTrue(f.tasks.automationConditionWaiting(f.id));
    }
    @Test public void ownerCancelWinsOverTheTypedWaitExitWithoutCreatingAnAutomaticReceipt()throws Exception{
        Fixture f=fixture=new Fixture(new TaskStore(RuntimeEnvironment.getApplication()));f.online.set(false);assertThrows(RunControl.ConditionWaitException.class,f.control::check);f.control.cancel();f.tasks.requestCancel(f.id,f.run.getString("runId"));
        f.tasks.endAutomationConditionWait(f.id,f.run.getString("runId"),f.control,"late condition callback",new JSONObject());assertEquals("cancelled",f.tasks.get(f.id,false).getString("status"));assertFalse(f.tasks.automationConditionWaiting(f.id));assertThrows(SecurityException.class,()->f.tasks.startAttempt(f.id,"background",40));
    }
    @Test @Config(application=EngineApplication.class) public void closedWaitStopRetainsUnknownEffectForOriginalReconciliation()throws Exception{
        engine=(EngineApplication)RuntimeEnvironment.getApplication();Fixture f=fixture=new Fixture(engine.tasks);String operation=f.journal.beginOperation("phone_home",new JSONObject());f.waitForNetwork();assertTrue(f.tasks.hasUnresolved(f.id));assertFalse(f.tasks.automationConditionWaiting(f.id));
        engine.dispatchOwnerAction("stopTask",j("taskId",f.id));JSONObject stopped=f.tasks.get(f.id,false);assertEquals("reconciling",stopped.getString("status"));assertTrue(stopped.getBoolean("cancelRequested"));assertTrue(f.tasks.hasUnresolved(f.id));assertEquals(operation,stopped.getJSONArray("operations").getJSONObject(0).getString("operationId"));
        assertThrows(IllegalStateException.class,()->f.tasks.startAttempt(f.id,"background",40));assertEquals(1,f.tasks.get(f.id,false).getJSONArray("operations").length());
    }

}
