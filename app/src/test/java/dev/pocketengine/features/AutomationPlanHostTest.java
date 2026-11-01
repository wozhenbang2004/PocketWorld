package dev.pocketengine.features;

import android.app.Application;
import dev.pocketengine.features.automation.AutomationBook;
import dev.pocketengine.features.automation.AutomationModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual TaskStore SQLite and original commitment domain, not fake task receipts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AutomationPlanHostTest {
    private TaskStore tasks;private AutomationPlanHost host;private AutomationModule module;
    @Before public void prepare()throws Exception{
        Application app=RuntimeEnvironment.getApplication();tasks=new TaskStore(app);host=new AutomationPlanHost(tasks);
        module=new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),host::resolveEvidence,host);
    }
    @After public void close()throws Exception{java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}
    private JSONObject plan(String first,String second,long expires)throws Exception{
        long now=System.currentTimeMillis();JSONObject step=new JSONObject().put("title","第一步").put("dueAt",now-2000).put("taskId",first);
        if(expires>0)step.put("expiresAt",expires);
        return host.handle(module,"createPlan",new JSONObject().put("scope",new JSONObject().put("ownerId","attacker").put("spaceId","public"))
                .put("plan",new JSONObject().put("kind","explicit_user").put("title","原任务计划").put("sourceRef",new JSONObject().put("kind","model_text").put("id","forged"))
                        .put("steps",new JSONArray().put(step).put(new JSONObject().put("title","第二步").put("dueAt",now+5000).put("taskId",second)))))
                .getJSONObject("plan");
    }
    private JSONObject read(String id)throws Exception{return host.handle(module,"getPlan",new JSONObject().put("id",id)).getJSONObject("plan");}
    @Test public void ownerScopeAndNativeBindingsDriveVerifiedCompletionOnly()throws Exception{
        String one=tasks.create("第一步目标").getString("id"),two=tasks.create("第二步目标").getString("id");JSONObject plan=plan(one,two,0);
        assertEquals("owner",plan.getString("ownerId"));assertEquals("private",plan.getString("spaceId"));assertEquals("user_action",plan.getJSONObject("sourceRef").getString("kind"));
        assertEquals(1,tasks.get(one,false).getJSONArray("commitmentBindings").length());
        tasks.status(one,"completed","模型说完成但尚未确认");host.onTaskChanged(module,one);
        JSONObject current=read(plan.getString("id"));assertEquals("pending",current.getString("state"));assertEquals("unverified_result",current.getJSONArray("steps").getJSONObject(0).getString("taskState"));
        tasks.event(one,new JSONObject().put("type","user_verification").put("completed",true));host.onTaskChanged(module,one);
        current=read(plan.getString("id"));assertEquals(1,current.getInt("completedSteps"));assertEquals(two,current.getJSONObject("nextStep").getString("taskId"));
        long revision=current.getLong("revision");host.onTaskChanged(module,one);assertEquals(revision,read(plan.getString("id")).getLong("revision"));
        tasks.status(two,"failed","真实任务失败");current=read(plan.getString("id"));assertEquals("failed",current.getString("taskState"));assertEquals("pending",current.getString("state"));
        assertFalse(current.getBoolean("executionAuthorized"));assertEquals("pending",current.getJSONArray("steps").getJSONObject(1).getString("status"));
    }
    @Test public void expiryRevocationAndOwnerCancellationRemainDistinct()throws Exception{
        String one=tasks.create("过期的真实目标").getString("id"),two=tasks.create("后续目标").getString("id");
        JSONObject plan=plan(one,two,System.currentTimeMillis()-1000);assertEquals("blocked",plan.getString("state"));assertEquals("expired",plan.getJSONArray("steps").getJSONObject(0).getString("taskState"));
        JSONObject cancelled=host.handle(module,"cancelPlan",new JSONObject().put("id",plan.getString("id")).put("revision",plan.getLong("revision"))).getJSONObject("plan");
        assertEquals("cancelled",cancelled.getString("state"));assertEquals("cancelled",cancelled.getJSONArray("steps").getJSONObject(0).getString("taskState"));
        assertFalse(tasks.get(one,false).optBoolean("cancelRequested")); // Promise cancellation is not phone execution authority.
        JSONObject live=plan(one,two,0);tasks.requestCancel(one,null);assertEquals("revoked",read(live.getString("id")).getJSONArray("steps").getJSONObject(0).getString("taskState"));
        assertThrows(IllegalStateException.class,()->host.handle(module,"cancelPlan",new JSONObject().put("id",cancelled.getString("id")).put("revision",plan.getLong("revision"))));
    }
    @Test public void completionEvidenceUsesDomainToStoreLockOrderAndRejectsRevokedConfirmation()throws Exception{
        Application app=RuntimeEnvironment.getApplication();
        module=new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),(scope,commitment,kind,id)->{
            assertFalse("TaskStore must not be held while entering the domain evidence callback",Thread.holdsLock(tasks));
            return host.resolveEvidence(scope,commitment,kind,id);
        },host);
        String one=tasks.create("需实际核对的目标").getString("id"),two=tasks.create("后续目标").getString("id");JSONObject plan=plan(one,two,0);
        tasks.event(one,new JSONObject().put("type","user_verification").put("completed",true));tasks.status(one,"completed","主人确认");
        JSONObject binding=tasks.get(one,false).getJSONArray("commitmentBindings").getJSONObject(0),commitment=plan.getJSONArray("steps").getJSONObject(0);
        JSONObject ref=tasks.recordTaskCommitmentCompletion(one,binding),scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        assertNotNull(host.resolveEvidence(scope,commitment,ref.getString("kind"),ref.getString("id")));
        // Expose the real event->status transition window: the newest confirmation already revokes
        // the old one even while the previous completed status is still visible.
        tasks.event(one,new JSONObject().put("type","user_verification").put("completed",false));
        assertNull(host.resolveEvidence(scope,commitment,ref.getString("kind"),ref.getString("id")));
        assertThrows(IllegalStateException.class,()->module.onCommitmentTaskCompleted(scope,commitment.getString("id"),commitment.getLong("revision"),ref.getString("id"),one,ref));
        tasks.event(one,new JSONObject().put("type","user_verification").put("completed",true));host.onTaskChanged(module,one);
        assertEquals(1,read(plan.getString("id")).getInt("completedSteps"));
    }
    @Test public void originalOccurrenceLookupSurvivesUiWindowAndLatestOwnerEvidenceRevocation()throws Exception{
        File file=new File(Files.createTempDirectory("automation-host-rule-").toFile(),"rules.json");AutomationBook book=new AutomationBook(file,host);
        book.save(new JSONObject().put("title","核对").put("kind","phone_goal").put("goal","真实目标").put("schedule",new JSONObject().put("type","once").put("at",2000)),1000);
        String occurrence=book.claimDue(2000,1).getJSONObject(0).getString("id");book.delivered(occurrence,"awaiting_user","仅通知",2100);
        JSONObject request=book.taskRequest(occurrence,2200);String task=tasks.createReminderTask(request,"main_screen").getString("id");
        for(int i=0;i<205;i++)tasks.create("较新的UI任务"+i);
        assertEquals(200,tasks.list().length());assertEquals(task,host.resolve(occurrence).getString("taskId"));
        tasks.status(task,"completed","仅停止或模型文本");assertEquals("unverified_result",book.goalState(occurrence).getString("state"));
        tasks.event(task,new JSONObject().put("type","user_verification").put("completed",true));String before=tasks.get(task,false).toString();
        assertEquals("succeeded",book.goalState(occurrence).getString("state"));assertEquals(before,tasks.get(task,false).toString());
        tasks.event(task,new JSONObject().put("type","user_verification").put("completed",false));assertEquals("unverified_result",book.goalState(occurrence).getString("state"));
        assertNull(host.resolve("does-not-exist"));
    }

    @Test public void selectedPlanBeyondGlobalCandidateWindowReachesItsOriginalTask()throws Exception{
        java.util.concurrent.atomic.AtomicReference<String> allowedPlan=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<String> admissions=new java.util.ArrayList<>();
        host=new AutomationPlanHost(tasks,new AutomationPlanHost.CurrentControl(){
            @Override public dev.pocketengine.core.RunControl get(String taskId){return null;}
            @Override public JSONObject requirementsForPlan(JSONObject plan)throws Exception{return new JSONObject().put("allowed",plan.getString("id").equals(allowedPlan.get()));}
            @Override public JSONObject admitPlanTask(AutomationModule module,JSONObject prepared)throws Exception{
                JSONObject request=module.planTaskRequest(new JSONObject().put("ownerId","owner").put("spaceId","private"),prepared.getString("planId"),prepared.getString("commitmentId"),prepared.getString("occurrenceId"));
                JSONObject original=tasks.createReminderTask(request,"background");
                JSONObject promise=module.bindPlanTask(new JSONObject().put("ownerId","owner").put("spaceId","private"),request.getString("planId"),request.getString("commitmentId"),request.getString("occurrenceId"));
                tasks.bindCommitment(promise);admissions.add(original.getString("id"));return original;
            }
        });
        Application app=RuntimeEnvironment.getApplication();
        module=new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),host::resolveEvidence,host);
        JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private"),last=null;
        long due=System.currentTimeMillis()-60000;
        for(int i=0;i<21;i++){
            last=host.handle(module,"createPlan",new JSONObject().put("plan",new JSONObject().put("kind","explicit_user").put("title","到期计划"+i).put("goal","核对目标"+i)
                    .put("steps",new JSONArray().put(new JSONObject().put("title","核对原结果").put("goal","核对目标"+i).put("dueAt",due))))).getJSONObject("plan");
            module.approvePlanExecution(scope,last.getString("id"),last.getLong("revision"),new JSONObject().put("mode","automatic").put("modelObservationConsent",true).put("maxTaskAdmissions",1));
        }
        String id=last.getString("id"),step=last.getJSONArray("steps").getJSONObject(0).getString("id");allowedPlan.set(id);
        JSONArray global=module.readyPlanTasks(scope,20).getJSONArray("candidates");assertEquals(20,global.length());
        for(int i=0;i<global.length();i++)assertNotEquals(id,global.getJSONObject(i).getString("planId"));
        assertTrue(module.inspectPlanTask(scope,id,step).getBoolean("eligible"));
        host.ownerCommand(module,"refresh",new JSONObject().put("id",id));
        assertFalse("The selected plan must not be hidden by other plans in the global candidate window",admissions.isEmpty());
        JSONObject bound=read(id).getJSONArray("steps").getJSONObject(0);String occurrence=bound.getJSONObject("taskAdmission").getString("occurrenceId");
        String original=tasks.automationTask(occurrence).getString("id");assertEquals(original,bound.getString("taskId"));assertEquals(1,tasks.list().length());
        host.ownerCommand(module,"refresh",new JSONObject().put("id",id));
        assertEquals(2,admissions.size());assertEquals(original,admissions.get(1));
        assertEquals(original,tasks.automationTask(occurrence).getString("id"));assertEquals(1,tasks.list().length());assertEquals(1,read(id).getJSONObject("execution").getInt("admissions"));
    }

    @Test public void selectedRecheckResumesThePausedOriginalOnlyAfterConditionsRecover()throws Exception{
        JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        java.util.concurrent.atomic.AtomicBoolean online=new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger resumes=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<dev.pocketengine.core.RunControl> retained=new java.util.concurrent.atomic.AtomicReference<>();
        host=new AutomationPlanHost(tasks,new AutomationPlanHost.CurrentControl(){
            @Override public dev.pocketengine.core.RunControl get(String taskId){return retained.get();}
            @Override public JSONObject requirementsForPlan(JSONObject plan)throws Exception{return new JSONObject().put("allowed",true);}
            @Override public JSONObject admitPlanTask(AutomationModule module,JSONObject prepared){throw new AssertionError("The original run must not be replaced");}
            @Override public JSONObject controlPlanTask(AutomationModule module,JSONObject expected,String action)throws Exception{
                assertEquals("resume",action);JSONObject current=tasks.get(expected.getString("taskId"),false);
                assertEquals(current.getString("runId"),expected.getString("runId"));assertEquals(current.getInt("coordinatorEpoch"),expected.getInt("coordinatorEpoch"));
                JSONObject origin=current.getJSONObject("origin"),request=module.planTaskRequest(scope,origin.getString("planId"),origin.getString("commitmentId"),expected.getString("occurrenceId"));
                tasks.renewAutomationRun(current.getString("id"),expected.getString("runId"),expected.getInt("coordinatorEpoch"),retained.get(),request.getLong("validUntil"));
                retained.get().resume();tasks.status(current.getString("id"),"running","同一原执行已恢复");resumes.incrementAndGet();return new JSONObject().put("controlRequested",true);
            }
        });
        Application app=RuntimeEnvironment.getApplication();module=new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),host::resolveEvidence,host,
                now->new dev.pocketengine.features.automation.AutomationConditions.Snapshot(now,online.get()?"validated":"disconnected","",true,90,""));
        JSONObject plan=host.handle(module,"createPlan",new JSONObject().put("plan",new JSONObject().put("kind","explicit_user").put("title","条件恢复后继续原任务").put("goal","核实同一个目标")
                .put("steps",new JSONArray().put(new JSONObject().put("title","核实原结果").put("goal","核实同一个目标").put("dueAt",System.currentTimeMillis()-60000))))).getJSONObject("plan");
        String id=plan.getString("id"),step=plan.getJSONArray("steps").getJSONObject(0).getString("id");
        plan=module.approvePlanExecution(scope,id,plan.getLong("revision"),new JSONObject().put("mode","automatic").put("modelObservationConsent",true).put("requiresNetwork",true).put("maxTaskAdmissions",1));
        JSONObject request=module.preparePlanTask(scope,id,plan.getLong("revision"),step);String occurrence=request.getString("occurrenceId"),task=tasks.createReminderTask(request,"background").getString("id");
        tasks.bindCommitment(module.bindPlanTask(scope,id,step,occurrence));JSONObject run=tasks.startAttempt(task,"background",4);
        dev.pocketengine.core.RunControl control=new dev.pocketengine.core.RunControl();control.bindHostRun(task,run.getString("runId"),run.getInt("coordinatorEpoch"));control.pause();retained.set(control);tasks.status(task,"paused","主人已暂停原任务");
        host.onDue(module,System.currentTimeMillis(),1);assertEquals(0,resumes.get()); // An ordinary alarm cannot undo an owner pause.
        online.set(false);host.ownerCommand(module,"refresh",new JSONObject().put("id",id));assertEquals(0,resumes.get());assertTrue(control.isPaused());
        online.set(true);host.ownerCommand(module,"inspect",new JSONObject().put("id",id));assertEquals(0,resumes.get());assertTrue(control.isPaused());
        host.ownerCommand(module,"refresh",new JSONObject().put("id",id));assertEquals(1,resumes.get());assertFalse(control.isPaused());
        JSONObject original=tasks.automationTask(occurrence);assertEquals(task,original.getString("id"));assertEquals(run.getString("runId"),original.getString("runId"));assertEquals(run.getInt("coordinatorEpoch"),original.getInt("coordinatorEpoch"));
        assertEquals(1,read(id).getJSONObject("execution").getInt("admissions"));assertEquals(1,tasks.list().length());
        host.ownerCommand(module,"refresh",new JSONObject().put("id",id));assertEquals(1,resumes.get());
    }

    @Test public void unavailablePhonePreparationDoesNotHideTheIndependentFileStepAfterTwentyCandidates()throws Exception{
        java.util.concurrent.atomic.AtomicInteger admissions=new java.util.concurrent.atomic.AtomicInteger();
        JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        host=new AutomationPlanHost(tasks,new AutomationPlanHost.CurrentControl(){
            @Override public dev.pocketengine.core.RunControl get(String taskId){return null;}
            @Override public JSONObject requirementsForPlan(JSONObject plan)throws Exception{return new JSONObject().put("allowed","workspace".equals(plan.getJSONObject("nextStep").optString("executor")));}
            @Override public JSONObject admitPlanTask(AutomationModule module,JSONObject prepared)throws Exception{
                assertEquals("workspace",prepared.getString("executor"));JSONObject request=module.planTaskRequest(scope,prepared.getString("planId"),prepared.getString("commitmentId"),prepared.getString("occurrenceId"));
                JSONObject task=tasks.createReminderTask(request,"capabilities");tasks.bindCommitment(module.bindPlanTask(scope,request.getString("planId"),request.getString("commitmentId"),request.getString("occurrenceId")));admissions.incrementAndGet();return task;
            }
        });
        Application app=RuntimeEnvironment.getApplication();FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});module=new AutomationModule(context,host::resolveEvidence,host);
        String project=new dev.pocketengine.features.workspace.WorkspaceModule(context).handle("create",new JSONObject().put("name","independent-preparation")).getString("id");
        JSONArray steps=new JSONArray();long due=System.currentTimeMillis()-60000;
        for(int i=0;i<50;i++){JSONObject row=new JSONObject().put("title","独立步骤"+i).put("goal","独立步骤"+i).put("dueAt",due).put("dependsOn",new JSONArray());
            if(i==49)row.put("executor","workspace").put("verification",new JSONObject().put("kind","workspace_artifact").put("projectId",project).put("path","result.md").put("requiredHeading","# Result").put("minBytes",1));steps.put(row);
        }
        JSONObject plan=host.handle(module,"createPlan",new JSONObject().put("plan",new JSONObject().put("kind","explicit_user").put("title","手机暂不可用时继续独立文件步骤").put("goal","各步骤按自己的前置与准备条件推进").put("steps",steps))).getJSONObject("plan");String id=plan.getString("id"),selected=plan.getJSONArray("steps").getJSONObject(49).getString("id");
        module.approvePlanExecution(scope,id,plan.getLong("revision"),new JSONObject().put("mode","automatic").put("modelObservationConsent",true).put("allowPrivateArtifactFreeze",true).put("maxTaskAdmissions",1));
        JSONObject snapshot=host.ownerSnapshot(module),view=snapshot.getJSONArray("plans").getJSONObject(0);assertEquals(selected,view.getJSONObject("nextStep").getString("id"));assertTrue(view.getJSONObject("requirements").getBoolean("allowed"));assertEquals(selected,view.getJSONObject("runtimeDecision").getString("stepId"));
        host.ownerCommand(module,"refresh",new JSONObject().put("id",id));assertEquals(1,admissions.get());assertEquals(1,tasks.list().length());
        JSONObject current=read(id);assertEquals("assistant",tasks.list().getJSONObject(0).getString("kind"));assertEquals(1,current.getJSONObject("execution").getInt("admissions"));
        for(int i=0;i<49;i++)assertFalse(current.getJSONArray("steps").getJSONObject(i).has("taskAdmission"));assertEquals(tasks.list().getJSONObject(0).getString("id"),current.getJSONArray("steps").getJSONObject(49).getString("taskId"));
    }
}
