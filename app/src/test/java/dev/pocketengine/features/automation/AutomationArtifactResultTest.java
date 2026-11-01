package dev.pocketengine.features.automation;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.AutomationPlanHost;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual SQLite journal, workspace bytes and original evidence chain. No fabricated machine success. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AutomationArtifactResultTest {
    private static JSONObject j(Object... values)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static JSONObject scope()throws Exception{return j("ownerId","owner","spaceId","private");}
    private static final class Fixture {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks=new TaskStore(app);
        final WorkspaceModule workspace=new WorkspaceModule(new FeatureContext(app,null,null,event->{}));
        final AutomationPlanHost host=new AutomationPlanHost(tasks,null,id->workspace.handle("readArtifact",j("artifactId",id)));
        final AutomationModule module=new AutomationModule(new FeatureContext(app,null,null,event->{}),host::resolveEvidence,host);
        final String project;String planId;
        Fixture()throws Exception{project=workspace.handle("create",j("name","native-plan-files")).getString("id");}
        JSONObject row(String path,String heading)throws Exception{return j("title",heading,"goal","核实并冻结 "+path,"executor","workspace","dueAt",System.currentTimeMillis()-1000,"verification",j("kind","workspace_artifact","projectId",project,"path",path,"requiredHeading",heading,"minBytes",16));}
        JSONObject plan()throws Exception{return module.handle("getPlan",j("scope",scope(),"id",planId)).getJSONObject("plan");}
        void create()throws Exception{
            JSONObject plan=host.handle(module,"createPlan",j("plan",j("kind","explicit_user","title","归档两份真实文件","goal","依次核验两份真实文件成果，不请求逐步主人完成确认","steps",new JSONArray().put(row("reports/source.md","# Source")).put(row("reports/delivery.md","# Delivery"))))).getJSONObject("plan");planId=plan.getString("id");
            module.approvePlanExecution(scope(),planId,plan.getLong("revision"),j("mode","automatic","maxTaskAdmissions",2,"modelObservationConsent",true,"allowPrivateArtifactFreeze",true));
        }
        JSONObject original(int index)throws Exception{
            JSONObject step=plan().getJSONArray("steps").getJSONObject(index),request=module.preparePlanTask(scope(),planId,plan().getLong("revision"),step.getString("id"));
            JSONObject task=tasks.createReminderTask(module.planTaskRequest(scope(),planId,step.getString("id"),request.getString("occurrenceId")),"capabilities");
            JSONObject promise=module.bindPlanTask(scope(),planId,step.getString("id"),request.getString("occurrenceId"));tasks.bindCommitment(promise);return task;
        }
        JSONObject freeze(JSONObject task,String path,String content)throws Exception{
            workspace.handle("write",j("projectId",project,"path",path,"content",content,"expectedVersion",0));
            String id=task.getString("id");JSONObject run=tasks.startAttempt(id,"capabilities",4);RunControl control=new RunControl();control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));tasks.authorizeTools(id,run.getString("runId"),new JSONArray().put("workspace_create_artifact"));
            RunJournal journal=tasks.journal(id,run.getString("runId"),run.getInt("coordinatorEpoch"),control);JSONObject args=j("projectId",project,"path",path,"version",0);String operation=journal.beginOperation("workspace_create_artifact",args);
            tasks.approveAutomationArtifactFreeze(id,run.getString("runId"),args);assertTrue(tasks.requireCapabilityApproval(id,run.getString("runId"),"workspace_create_artifact",args,j("feature","workspace","command","createArtifact")).getBoolean("approved"));
            JSONObject artifact=workspace.handle("createArtifact",new JSONObject(args.toString()).put("_taskId",id).put("_runId",run.getString("runId")));journal.finishOperation(operation,artifact);
            // A file receipt during a still-live model run is not a settled task.
            tasks.status(id,"completed","untrusted model status");host.onTaskChanged(module,id);assertFalse(tasks.automationArtifactVerified(id));
            tasks.endAttempt(id,run.getString("runId"),"needs_confirmation","native run closed",new JSONObject());return artifact;
        }
    }
    @Test public void twoRealFileResultsAdvanceOriginalPlanWithoutOwnerVerificationEvents()throws Exception{
        Fixture f=new Fixture();f.create();JSONObject first=f.original(0);f.freeze(first,"reports/source.md","# Source\nNative source file bytes.\n");f.host.onTaskChanged(f.module,first.getString("id"));
        assertEquals(1,f.plan().getInt("completedSteps"));assertEquals("completed",f.tasks.get(first.getString("id"),false).getString("status"));
        JSONObject evidence=f.plan().getJSONArray("steps").getJSONObject(0).getJSONObject("completionEvidence");assertEquals("native_workspace_artifact_standard",evidence.getString("verificationSource"));assertTrue(evidence.getJSONObject("artifact").getLong("sizeBytes")>16);
        JSONObject second=f.original(1);f.freeze(second,"reports/delivery.md","# Delivery\nNative delivery file bytes.\n");f.host.onTaskChanged(f.module,second.getString("id"));assertEquals("completed",f.plan().getString("state"));
        for(JSONObject task:new JSONObject[]{f.tasks.get(first.getString("id"),false),f.tasks.get(second.getString("id"),false)}){
            JSONArray events=task.getJSONArray("events");boolean nativeProof=false;for(int i=0;i<events.length();i++){assertNotEquals("user_verification",events.getJSONObject(i).optString("type"));if(events.getJSONObject(i).optString("type").equals("native_plan_verification"))nativeProof=true;}assertTrue(nativeProof);
        }
        assertEquals(2,new TaskStore(f.app).list().length());assertEquals("completed",f.plan().getString("state"));
    }
    @Test public void missingOrWrongHeadingArtifactNeverTurnsModelCompletionIntoPlanSuccess()throws Exception{
        Fixture f=new Fixture();f.create();JSONObject first=f.original(0),artifact=f.freeze(first,"reports/source.md","# Wrong heading\nActual bytes exist, criterion does not match.\n");f.host.onTaskChanged(f.module,first.getString("id"));
        assertEquals(0,f.plan().getInt("completedSteps"));assertFalse(f.tasks.automationArtifactVerified(first.getString("id")));
        Files.delete(new java.io.File(f.app.getFilesDir(),"workspace-artifacts/"+artifact.getString("id")+".json").toPath());f.tasks.status(first.getString("id"),"completed","model claims success after file disappeared");f.host.onTaskChanged(f.module,first.getString("id"));
        assertEquals(0,f.plan().getInt("completedSteps"));assertEquals(0,f.module.readyPlanTasks(scope(),10).getJSONArray("candidates").length());
    }
    @Test public void atomicReminderCreationLeavesNoOrphanAndSameOccurrenceCannotChangeIdentity()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);
        db.execSQL("CREATE TRIGGER reject_automation_origin BEFORE INSERT ON tasks WHEN NEW.document LIKE '%\"origin\"%' BEGIN SELECT RAISE(ABORT,'injected origin save failure'); END");
        String occurrence=UUID.randomUUID().toString();JSONObject request=j("goal","atomic unique original","ruleId","rule-one","ruleRevision",1,"origin",j("kind","automation","occurrenceId",occurrence));
        assertThrows(Exception.class,()->tasks.createReminderTask(request,"background"));assertEquals(0,tasks.list().length());assertNull(tasks.automationTask(occurrence));db.execSQL("DROP TRIGGER reject_automation_origin");
        JSONObject created=tasks.createReminderTask(request,"background");assertEquals(created.getString("id"),new TaskStore(RuntimeEnvironment.getApplication()).createReminderTask(request,"background").getString("id"));
        assertThrows(SecurityException.class,()->tasks.createReminderTask(new JSONObject(request.toString()).put("goal","replacement goal"),"background"));assertEquals(1,tasks.list().length());
    }
    @Test public void independentStoresCannotCreateTwoOriginalTasksForOneOccurrence()throws Exception{
        TaskStore one=new TaskStore(RuntimeEnvironment.getApplication()),two=new TaskStore(RuntimeEnvironment.getApplication());JSONObject request=j("goal","concurrent exact occurrence","ruleId","native-rule","ruleRevision",1,"origin",j("kind","automation","occurrenceId",UUID.randomUUID().toString()));
        var executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);try{
            var left=executor.submit(()->{start.await();return one.createReminderTask(new JSONObject(request.toString()),"background").getString("id");});var right=executor.submit(()->{start.await();return two.createReminderTask(new JSONObject(request.toString()),"background").getString("id");});start.countDown();assertEquals(left.get(5,TimeUnit.SECONDS),right.get(5,TimeUnit.SECONDS));assertEquals(1,one.list().length());
        }finally{executor.shutdownNow();}
    }
}
