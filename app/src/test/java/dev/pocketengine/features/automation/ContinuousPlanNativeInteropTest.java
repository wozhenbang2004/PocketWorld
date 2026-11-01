package dev.pocketengine.features.automation;

import android.app.Application;
import dev.pocketengine.features.AutomationPlanHost;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real shared v12 SQLite/evidence integration; no simulated Runner or phone success. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ContinuousPlanNativeInteropTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject definition()throws Exception{
        long due=System.currentTimeMillis()-60000;
        return new JSONObject().put("kind","explicit_user").put("title","核实后交付").put("goal","取得原任务真实结果，再核实交付")
                .put("steps",new JSONArray().put(new JSONObject().put("title","先核实原始结果").put("goal","核实原始结果并保存凭证").put("dueAt",due))
                        .put(new JSONObject().put("title","再完成交付").put("goal","根据原始凭证核实交付").put("dueAt",due)));
    }
    private static AutomationModule module(AutomationPlanHost host)throws Exception{
        Application app=RuntimeEnvironment.getApplication();return new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),host::resolveEvidence,host);
    }
    private static JSONObject current(AutomationModule module,String id)throws Exception{return module.handle("getPlan",new JSONObject().put("scope",scope()).put("id",id)).getJSONObject("plan");}
    private static JSONObject prepare(AutomationModule module,String id,int index)throws Exception{JSONObject plan=current(module,id);return module.preparePlanTask(scope(),id,plan.getLong("revision"),plan.getJSONArray("steps").getJSONObject(index).getString("id"));}
    private static JSONObject bind(AutomationModule module,TaskStore tasks,String id,JSONObject request)throws Exception{
        JSONObject promise=module.bindPlanTask(scope(),id,request.getString("commitmentId"),request.getString("occurrenceId"));tasks.bindCommitment(promise);return promise;
    }

    @Test public void exactNativeTaskIsReusedAfterLostAckAndOnlyItsPersistedVerifiedResultUnblocksSuccessor()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());AutomationPlanHost host=new AutomationPlanHost(tasks);AutomationModule module=module(host);
        JSONObject created=host.handle(module,"createPlan",new JSONObject().put("plan",definition())).getJSONObject("plan");String id=created.getString("id");
        module.approvePlanExecution(scope(),id,created.getLong("revision"),new JSONObject().put("mode","automatic").put("maxTaskAdmissions",2).put("modelObservationConsent",true));
        JSONObject first=prepare(module,id,0),admitted=module.planTaskRequest(scope(),id,first.getString("commitmentId"),first.getString("occurrenceId"));
        String original=tasks.createReminderTask(admitted,"background").getString("id");
        // Simulate a lost create acknowledgement, then reopen the actual SQLite authority.
        TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());AutomationPlanHost restoredHost=new AutomationPlanHost(reopened);AutomationModule restored=module(restoredHost);
        JSONObject lookup=prepare(restored,id,0);assertTrue(lookup.getBoolean("lookupOnly"));assertEquals(first.getString("occurrenceId"),lookup.getString("occurrenceId"));
        assertEquals(original,reopened.automationTask(lookup.getString("occurrenceId")).getString("id"));assertEquals(original,reopened.createReminderTask(lookup,"background").getString("id"));
        bind(restored,reopened,id,lookup);assertEquals(original,current(restored,id).getJSONArray("steps").getJSONObject(0).getString("taskId"));
        reopened.status(original,"completed","model output alone");restoredHost.onTaskChanged(restored,original);assertEquals(0,current(restored,id).getInt("completedSteps"));assertEquals(0,restored.readyPlanTasks(scope(),10).getJSONArray("candidates").length());
        reopened.event(original,new JSONObject().put("type","user_verification").put("completed",true));restoredHost.onTaskChanged(restored,original);
        JSONObject progressed=current(restored,id);assertEquals(1,progressed.getInt("completedSteps"));assertEquals("task_result",progressed.getJSONArray("steps").getJSONObject(0).getJSONObject("completionEvidence").getString("kind"));
        JSONObject second=prepare(restored,id,1);assertNotEquals(first.getString("occurrenceId"),second.getString("occurrenceId"));String secondTask=reopened.createReminderTask(restored.planTaskRequest(scope(),id,second.getString("commitmentId"),second.getString("occurrenceId")),"background").getString("id");bind(restored,reopened,id,second);
        reopened.event(secondTask,new JSONObject().put("type","user_verification").put("completed",true));reopened.status(secondTask,"completed","native verified delivery result");restoredHost.onTaskChanged(restored,secondTask);
        assertEquals("completed",current(restored,id).getString("state"));assertEquals(2,current(restored,id).getInt("completedSteps"));assertEquals(2,current(restored,id).getJSONObject("execution").getInt("admissions"));
        assertEquals(original,new TaskStore(RuntimeEnvironment.getApplication()).automationTask(first.getString("occurrenceId")).getString("id"));
        assertThrows(IllegalArgumentException.class,()->restored.handle("approvePlanExecution",new JSONObject()));
    }

    @Test public void nativeFailureProjectionWithoutTerminalProofCannotStartRecoveryBranch()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());AutomationPlanHost host=new AutomationPlanHost(tasks);Application app=RuntimeEnvironment.getApplication();
        AutomationModule module=new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),host::resolveEvidence,occurrence->{
            JSONObject projection=host.resolve(occurrence);if(projection!=null)projection.remove("terminalVerified");return projection;
        });JSONObject input=definition();
        input.getJSONArray("steps").getJSONObject(1).put("dependsOn",new JSONArray().put(new JSONObject().put("step",0).put("outcome","failed")));
        JSONObject plan=host.handle(module,"createPlan",new JSONObject().put("plan",input)).getJSONObject("plan");String id=plan.getString("id");module.approvePlanExecution(scope(),id,plan.getLong("revision"),new JSONObject().put("mode","automatic").put("maxTaskAdmissions",2).put("modelObservationConsent",true));
        JSONObject request=prepare(module,id,0);String task=tasks.createReminderTask(module.planTaskRequest(scope(),id,request.getString("commitmentId"),request.getString("occurrenceId")),"background").getString("id");bind(module,tasks,id,request);tasks.status(task,"failed","native task status without extended settled proof");
        module.refreshPlanTasks(scope(),id);
        assertEquals("pending",current(module,id).getJSONArray("steps").getJSONObject(0).getString("status"));assertEquals(0,module.readyPlanTasks(scope(),10).getJSONArray("candidates").length());
    }
}
