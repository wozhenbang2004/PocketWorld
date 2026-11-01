package dev.pocketengine.features.automation;

import android.app.Application;
import dev.pocketengine.features.AutomationPlanHost;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Native SQLite boundary cases; receipt fixtures are separate from real Companion-process evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AutomationRemoteWaitBoundaryTest {
    private static JSONObject j(Object... values)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private static JSONObject scope()throws Exception{return j("ownerId","owner","spaceId","private");}
    private static final class Fixture {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final AutomationPlanHost host;final AutomationModule module;final String source=UUID.randomUUID().toString();long version=1;
        Fixture()throws Exception{app.deleteDatabase("pocket-world.db");new File(app.getFilesDir(),"automation/commitments.json").delete();tasks=new TaskStore(app);host=new AutomationPlanHost(tasks);module=new AutomationModule(new FeatureContext(app,null,null,event->{}),host::resolveEvidence,host);tasks.beginExternal("remote","submitJob",source,j("profileId","computer","environmentId","workspace","worker","command","goal","原命令"));receipt("running",null);}
        void receipt(String state,Object code)throws Exception{JSONObject receipt=j("id","original-job","profileId","computer","environmentId","workspace","worker","command","operationId",source,"serviceBase","https://original.example","status",state,"updatedAt",version++);if(code!=null)receipt.put("exitCode",code);tasks.updateExternal(source,receipt);}
        JSONObject reference()throws Exception{return tasks.automationRemoteWaitReference(source);}
        JSONObject create()throws Exception{
            long now=System.currentTimeMillis();JSONArray steps=new JSONArray().put(j("title","等原命令后处理","goal","等原命令后处理","executor","phone","dueAt",now,"dependsOn",new JSONArray(),"remoteWait",reference())).put(j("title","有真实成果才归档","goal","有真实成果才归档","executor","phone","dueAt",now,"dependsOn",new JSONArray().put(j("step",0,"outcome","completed"))));
            return host.ownerCommand(module,"create",j("plan",j("kind","explicit_user","title","同一持续目标","goal","同一持续目标","steps",steps))).getJSONObject("plan");
        }
        JSONObject get(String id)throws Exception{return module.handle("getPlan",j("scope",scope(),"id",id)).getJSONObject("plan");}
        JSONObject approve(JSONObject plan)throws Exception{return module.approvePlanExecution(scope(),plan.getString("id"),plan.getLong("revision"),j("mode","automatic","maxTaskAdmissions",2,"phoneMode","main_screen","modelObservationConsent",true,"requiresNetwork",false,"expiresAt",System.currentTimeMillis()+3_600_000L));}
    }
    @Test public void originalNativeReceiptNeedsIntegerExitAndExactIdentityAndNeverAcceptsAgentCompletion()throws Exception{
        Fixture f=new Fixture();JSONObject reference=f.reference();assertEquals("remote_wait_pending",f.tasks.automationRemoteWaitObservation(reference).getString("reason"));
        f.receipt("completed",null);assertEquals("remote_wait_exit_unavailable",f.tasks.automationRemoteWaitObservation(reference).getString("reason"));
        f.receipt("completed","0");assertFalse("A string is not a process exit code",f.tasks.automationRemoteWaitObservation(reference).getBoolean("allowed"));f.receipt("completed",0.5);assertFalse("A fractional number is not a process exit code",f.tasks.automationRemoteWaitObservation(reference).getBoolean("allowed"));
        f.receipt("completed",0);assertTrue(f.tasks.automationRemoteWaitObservation(reference).getBoolean("allowed"));assertFalse(f.tasks.get(f.source,false).getJSONObject("evidence").getBoolean("automaticallyVerified"));assertEquals("needs_confirmation",f.tasks.get(f.source,false).getString("status"));
        assertFalse(f.tasks.automationRemoteWaitObservation(new JSONObject(reference.toString()).put("jobId","other-job")).getBoolean("allowed"));assertFalse(f.tasks.automationRemoteWaitObservation(new JSONObject(reference.toString()).put("coordinatorEpoch",1)).getBoolean("allowed"));
        String agent=UUID.randomUUID().toString();f.tasks.beginExternal("remote","submitJob",agent,j("profileId","computer","environmentId","workspace","worker","codex_app_server","goal","Agent 自述完成"));f.tasks.updateExternal(agent,j("id","agent-job","profileId","computer","environmentId","workspace","worker","codex_app_server","operationId",agent,"serviceBase","https://original.example","status","completed","exitCode",0));assertThrows(SecurityException.class,()->f.tasks.automationRemoteWaitReference(agent));
    }
    @Test public void futureEditKeepsOriginalWaitAndDagAndCannotBorrowGenericFeatureAuthority()throws Exception{
        Fixture f=new Fixture();JSONObject created=f.create(),approved=f.approve(created);String id=created.getString("id");JSONObject first=approved.getJSONArray("steps").getJSONObject(0);String edges=approved.getJSONArray("steps").getJSONObject(1).getJSONArray("dependencies").toString();
        JSONObject definition=j("kind","explicit_user","title","外来私有引用","goal","外来私有引用","steps",new JSONArray().put(j("title","等待","goal","等待","dueAt",System.currentTimeMillis(),"remoteWait",f.reference())));assertThrows(SecurityException.class,()->f.host.handle(f.module,"createPlan",j("plan",definition)));
        JSONObject paused=f.module.setPlanPaused(scope(),id,approved.getLong("revision"),true);JSONObject edited=f.module.editPlanStep(scope(),id,paused.getLong("revision"),first.getString("id"),first.getLong("revision"),j("goal","修改未来目标"));assertFalse(edited.getBoolean("approvalValid"));assertEquals(f.reference().toString(),edited.getJSONArray("steps").getJSONObject(0).getJSONObject("remoteWait").toString());assertEquals(edges,edited.getJSONArray("steps").getJSONObject(1).getJSONArray("dependencies").toString());
        JSONObject wrong=j("id",id,"revision",edited.getLong("revision"),"stepId",first.getString("id"),"stepRevision",edited.getJSONArray("steps").getJSONObject(0).getLong("revision"),"patch",j("remoteWait",new JSONObject(f.reference().toString()).put("jobId","unselected-job")));assertThrows(SecurityException.class,()->f.host.ownerCommand(f.module,"editStep",wrong));assertEquals(edited.getLong("revision"),f.get(id).getLong("revision"));assertEquals(1,f.tasks.list().length());
        JSONObject cleared=f.module.editPlanStep(scope(),id,edited.getLong("revision"),first.getString("id"),edited.getJSONArray("steps").getJSONObject(0).getLong("revision"),j("remoteWait",JSONObject.NULL));JSONObject current=cleared.getJSONArray("steps").getJSONObject(0);assertFalse(current.has("remoteWait"));assertEquals(f.reference().toString(),current.getJSONArray("priorRemoteWaitRefs").getJSONObject(0).getJSONObject("reference").toString());assertFalse(cleared.getBoolean("approvalValid"));assertEquals(edges,cleared.getJSONArray("steps").getJSONObject(1).getJSONArray("dependencies").toString());
    }
    @Test public void finalSqliteFenceRejectsStaleProofAndReopenRetainsSameIntentAndBudget()throws Exception{
        Fixture f=new Fixture();f.receipt("completed",0);JSONObject approved=f.approve(f.create());String id=approved.getString("id"),step=approved.getJSONArray("steps").getJSONObject(0).getString("id");JSONObject prepared=f.module.preparePlanTask(scope(),id,approved.getLong("revision"),step),request=f.module.planTaskRequest(scope(),id,step,prepared.getString("occurrenceId"));long deadline=request.getLong("validUntil");
        f.receipt("failed",7);assertThrows(SecurityException.class,()->f.tasks.createReminderTask(request,"main_screen"));assertEquals(1,f.tasks.list().length());
        TaskStore reopened=new TaskStore(f.app);AutomationPlanHost reader=new AutomationPlanHost(reopened);AutomationModule recovered=new AutomationModule(new FeatureContext(f.app,null,null,event->{}),reader::resolveEvidence,reader);JSONObject saved=recovered.handle("getPlan",j("scope",scope(),"id",id)).getJSONObject("plan");JSONObject intent=saved.getJSONArray("steps").getJSONObject(0).getJSONObject("taskAdmission");assertEquals(prepared.getString("occurrenceId"),intent.getString("occurrenceId"));assertEquals(deadline,intent.getLong("validUntil"));assertEquals(1,saved.getJSONObject("execution").getLong("admissions"));assertEquals("remote_wait_failed",recovered.inspectPlanTask(scope(),id,step).getString("reason"));assertThrows(IllegalStateException.class,()->recovered.planTaskRequest(scope(),id,step,intent.getString("occurrenceId")));assertEquals(1,reopened.list().length());
        recovered.cancelPlanExecution(scope(),id,saved.getLong("revision"));assertFalse(reopened.get(f.source,false).optBoolean("cancelRequested"));
    }
}
