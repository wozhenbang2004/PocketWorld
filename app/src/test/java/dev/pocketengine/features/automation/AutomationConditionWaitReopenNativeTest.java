package dev.pocketengine.features.automation;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.AutomationPlanHost;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual SQLite close/reopen boundary, not Android process-death or device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=android.app.Application.class)
public final class AutomationConditionWaitReopenNativeTest {
    private static JSONObject j(Object... values)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static SQLiteDatabase db(TaskStore tasks)throws Exception{Field f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    private static JSONObject grant(TaskStore tasks,String run)throws Exception{try(Cursor c=db(tasks).rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(c.moveToFirst());return new JSONObject(c.getString(0));}}
    @Test public void aReopenedWorkspaceWaitRetainsItsNativeReceiptAndBudgetWithoutAuthorizingOrdinaryInterruptedTasks()throws Exception{
        android.app.Application app=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(app),reopened=null;
        try{
            FeatureContext context=new FeatureContext(app,null,null,event->{});WorkspaceModule workspace=new WorkspaceModule(context);String project=workspace.handle("create",j("name","condition-wait-reopen")).getString("id");
            AutomationPlanHost host=new AutomationPlanHost(tasks);AtomicBoolean online=new AtomicBoolean(true);AutomationConditions.Source conditions=now->new AutomationConditions.Snapshot(now,online.get()?"validated":"disconnected","",true,90,"");
            AutomationModule module=new AutomationModule(context,host::resolveEvidence,host,conditions);JSONObject scope=j("ownerId","owner","spaceId","private");
            JSONObject row=j("title","继续原项目文件目标","goal","保留原任务与预算后继续项目文件","executor","workspace","dueAt",System.currentTimeMillis()-1000,"verification",j("kind","workspace_artifact","projectId",project,"path","result.md","requiredHeading","# Result","minBytes",1));
            JSONObject plan=host.handle(module,"createPlan",j("plan",j("kind","explicit_user","title","重开后等待网络","goal","保留原任务与预算后继续项目文件","steps",new JSONArray().put(row)))).getJSONObject("plan");String planId=plan.getString("id"),step=plan.getJSONArray("steps").getJSONObject(0).getString("id");
            plan=module.approvePlanExecution(scope,planId,plan.getLong("revision"),j("mode","automatic","maxTaskAdmissions",1,"modelObservationConsent",true,"requiresNetwork",true,"expiresAt",System.currentTimeMillis()+3600000));
            JSONObject request=module.preparePlanTask(scope,planId,plan.getLong("revision"),step);String id=tasks.createReminderTask(request,"capabilities").getString("id");tasks.bindCommitment(module.bindPlanTask(scope,planId,step,request.getString("occurrenceId")));
            JSONObject run=tasks.startAttempt(id,"capabilities",4);RunControl control=new RunControl();control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));AutomationRunGate.bind(control,request,conditions);online.set(false);assertThrows(RunControl.ConditionWaitException.class,control::check);tasks.endAutomationConditionWait(id,run.getString("runId"),control,"等待网络",new JSONObject());
            String ordinary=tasks.createCapabilityTask("主人主动暂停的普通任务","").getString("id");JSONObject other=tasks.startAttempt(ordinary,"capabilities",4);tasks.endAttempt(ordinary,other.getString("runId"),"paused","主人主动暂停",new JSONObject());
            JSONObject before=grant(tasks,run.getString("runId"));assertTrue(tasks.automationConditionWaiting(id));db(tasks).close();tasks=null;
            reopened=new TaskStore(app);assertEquals("interrupted",reopened.get(id,false).getString("status"));assertTrue(reopened.automationConditionWaiting(id));assertFalse(reopened.automationConditionWaiting(ordinary));
            online.set(true);JSONObject restored=reopened.startAttempt(id,"capabilities",40),after=grant(reopened,restored.getString("runId"));
            assertEquals(id,restored.getString("id"));assertEquals(request.getString("occurrenceId"),restored.getJSONObject("origin").getString("occurrenceId"));assertEquals(before.getInt("stepCeiling"),after.getInt("stepCeiling"));assertEquals(before.getInt("remainingOperations"),after.getInt("remainingOperations"));assertNotEquals(run.getString("runId"),restored.getString("runId"));
        }finally{if(tasks!=null)db(tasks).close();if(reopened!=null)db(reopened).close();}
    }
}
