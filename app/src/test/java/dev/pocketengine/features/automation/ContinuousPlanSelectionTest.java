package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Exact plan reads must keep bounded global candidate windows out of host admission. */
public final class ContinuousPlanSelectionTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject definition(int index)throws Exception{
        return new JSONObject().put("kind","explicit_user").put("title","核对计划"+index).put("goal","核实目标"+index)
                .put("sourceRef",scope().put("kind","user_action").put("id","native-owner-review-"+index))
                .put("steps",new JSONArray().put(new JSONObject().put("title","核对原结果").put("goal","核实目标"+index).put("dueAt",2000)));
    }
    @Test public void exactSelectedPlanReadSurvivesTheGlobalTwentyCandidateWindowWithoutWriting()throws Exception{
        File file=new File(folder.getRoot(),"plans.json");
        CommitmentBook book=new CommitmentBook(file,null,null,occurrence->null);JSONObject selected=null;
        for(int i=0;i<21;i++){
            selected=book.createPlan(scope(),definition(i),1000);
            selected=book.approvePlanExecution(scope(),selected.getString("id"),selected.getLong("revision"),new JSONObject().put("mode","automatic").put("maxTaskAdmissions",1).put("modelObservationConsent",true),2500);
        }
        String id=selected.getString("id"),step=selected.getJSONArray("steps").getJSONObject(0).getString("id");
        JSONArray global=book.readyPlanTasks(scope(),4000,20).getJSONArray("candidates");assertEquals(20,global.length());
        for(int i=0;i<global.length();i++)assertNotEquals(id,global.getJSONObject(i).getString("planId"));
        assertTrue(book.inspectPlanTask(scope(),id,step,4000).getBoolean("eligible"));
        byte[] before=Files.readAllBytes(file.toPath());
        JSONArray exact=book.readyPlanTasks(scope(),id,4000,1).getJSONArray("candidates");assertEquals(1,exact.length());
        assertEquals(id,exact.getJSONObject(0).getString("planId"));assertEquals(step,exact.getJSONObject(0).getString("stepId"));
        assertFalse(exact.getJSONObject(0).getBoolean("executionAuthorized"));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        JSONObject request=book.preparePlanTask(scope(),id,exact.getJSONObject(0).getLong("planRevision"),step,4000);
        CommitmentBook reopened=new CommitmentBook(file,null,null,occurrence->null);
        assertEquals(0,reopened.readyPlanTasks(scope(),id,4000,1).getJSONArray("candidates").length());
        JSONObject lookup=reopened.preparePlanTask(scope(),id,reopened.getPlan(scope(),id,4000).getLong("revision"),step,4000);
        assertTrue(lookup.getBoolean("lookupOnly"));assertEquals(request.getString("occurrenceId"),lookup.getString("occurrenceId"));
        assertEquals(1,reopened.getPlan(scope(),id,4000).getJSONObject("execution").getInt("admissions"));
        assertThrows(IllegalArgumentException.class,()->book.readyPlanTasks(scope(),"missing-plan",4000,1));
        assertThrows(SecurityException.class,()->book.readyPlanTasks(new JSONObject().put("ownerId","owner").put("spaceId","public"),id,4000,1));
    }

    @Test public void selectedPlanCanConsiderEveryIndependentStepWithoutGrowingTheGlobalWindow()throws Exception{
        File file=new File(folder.getRoot(),"independent.json");CommitmentBook book=new CommitmentBook(file,null,null,occurrence->null);JSONArray rows=new JSONArray();
        for(int i=0;i<50;i++){JSONObject row=new JSONObject().put("title","独立步骤"+i).put("goal","独立步骤"+i).put("dueAt",2000).put("dependsOn",new JSONArray());
            if(i==49)row.put("executor","workspace").put("verification",new JSONObject().put("kind","workspace_artifact").put("projectId",java.util.UUID.randomUUID().toString()).put("path","result.md").put("requiredHeading","# Result").put("minBytes",1));rows.put(row);
        }
        JSONObject input=definition(0).put("steps",rows),plan=book.createPlan(scope(),input,1000);String id=plan.getString("id");
        plan=book.approvePlanExecution(scope(),id,plan.getLong("revision"),new JSONObject().put("mode","automatic").put("modelObservationConsent",true).put("maxTaskAdmissions",1),2500);
        byte[] before=Files.readAllBytes(file.toPath());JSONArray candidates=book.readyPlanTasks(scope(),id,4000,50).getJSONArray("candidates");assertEquals(50,candidates.length());
        assertEquals(1,book.readyPlanTasks(scope(),4000,20).getJSONArray("candidates").length());assertThrows(IllegalArgumentException.class,()->book.readyPlanTasks(scope(),4000,21));assertThrows(IllegalArgumentException.class,()->book.readyPlanTasks(scope(),id,4000,51));
        JSONObject selected=null;for(int i=0;i<candidates.length();i++){JSONObject view=PlanExecutionPolicy.executionStep(plan,candidates.getJSONObject(i).getString("stepId"));if(view.getJSONObject("nextStep").optString("executor").equals("workspace")){selected=view;break;}}
        assertNotNull(selected);JSONObject step=selected.getJSONObject("nextStep");assertEquals(plan.getJSONArray("steps").getJSONObject(49).getString("id"),step.getString("id"));assertEquals(id,selected.getString("id"));assertEquals(0,selected.getJSONObject("execution").getInt("admissions"));assertFalse(selected.getBoolean("executionAuthorized"));
        step.put("goal","修改只读视图");assertEquals("独立步骤49",plan.getJSONArray("steps").getJSONObject(49).getString("goal"));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        String stepId=candidates.getJSONObject(49).getString("stepId");JSONObject prepared=book.preparePlanTask(scope(),id,plan.getLong("revision"),stepId,4000);assertEquals("独立步骤49",prepared.getString("goal"));
        assertEquals(1,book.getPlan(scope(),id,4000).getJSONObject("execution").getInt("admissions"));assertEquals(0,book.readyPlanTasks(scope(),id,4000,50).getJSONArray("candidates").length());
    }

    @Test public void blockedRemotePhoneStepDoesNotDefineRequirementsForAnIndependentFileStep()throws Exception{
        File file=new File(folder.getRoot(),"remote-independent.json");CommitmentBook book=new CommitmentBook(file,(identity,promise,kind,task)->kind.equals("remote_wait_condition")?new JSONObject().put("authority","TaskStore").put("allowed",false).put("reason","remote_wait_pending"):null,null,occurrence->null);
        String run=java.util.UUID.randomUUID().toString();JSONObject remote=new JSONObject().put("kind","remote_command_exit").put("taskId","original-command").put("runId",run).put("operationId",run).put("coordinatorEpoch",1).put("profileId","original-computer").put("serviceBase","https://computer.invalid").put("jobId","original-job").put("environmentId","original-environment").put("expectedExitCode",0);
        JSONObject phone=new JSONObject().put("title","等待电脑后再核对手机").put("goal","等待电脑后再核对手机").put("dueAt",2000).put("dependsOn",new JSONArray()).put("remoteWait",remote);
        JSONObject fileStep=new JSONObject().put("title","独立文件步骤").put("goal","独立文件步骤").put("dueAt",2000).put("dependsOn",new JSONArray()).put("executor","workspace").put("verification",new JSONObject().put("kind","workspace_artifact").put("projectId",java.util.UUID.randomUUID().toString()).put("path","result.md").put("requiredHeading","# Result").put("minBytes",1));
        JSONObject plan=book.createPlan(scope(),definition(0).put("steps",new JSONArray().put(phone).put(fileStep)),1000);String id=plan.getString("id");plan=book.approvePlanExecution(scope(),id,plan.getLong("revision"),new JSONObject().put("mode","automatic").put("modelObservationConsent",true).put("maxTaskAdmissions",2),2500);
        String waiting=plan.getJSONArray("steps").getJSONObject(0).getString("id"),ready=book.readyPlanTasks(scope(),id,4000,50).getJSONArray("candidates").getJSONObject(0).getString("stepId");
        assertEquals("remote_wait_pending",book.inspectPlanTask(scope(),id,waiting,4000).getString("reason"));assertEquals("phone",plan.getJSONObject("nextStep").getString("executor"));JSONObject view=PlanExecutionPolicy.executionStep(plan,ready);assertEquals("workspace",view.getJSONObject("nextStep").getString("executor"));
        JSONObject current=plan;assertThrows(IllegalArgumentException.class,()->PlanExecutionPolicy.executionStep(current,"another-plan-step"));JSONObject mixed=new JSONObject(plan.toString());mixed.getJSONArray("steps").getJSONObject(1).put("planId","another-plan");assertThrows(IllegalArgumentException.class,()->PlanExecutionPolicy.executionStep(mixed,ready));
        JSONObject prepared=book.preparePlanTask(scope(),id,plan.getLong("revision"),ready,4000);assertEquals(ready,prepared.getString("commitmentId"));assertFalse(book.getPlan(scope(),id,4000).getJSONArray("steps").getJSONObject(0).has("taskAdmission"));
    }
}
