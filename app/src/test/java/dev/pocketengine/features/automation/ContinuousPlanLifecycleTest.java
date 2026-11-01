package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Domain lifecycle tests. The separate native interop check uses the real shared TaskStore. */
public final class ContinuousPlanLifecycleTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject settings(String mode,int maximum)throws Exception{return new JSONObject().put("mode",mode).put("maxTaskAdmissions",maximum).put("modelObservationConsent",true).put("zoneId","UTC");}
    private static JSONObject step(String goal)throws Exception{return new JSONObject().put("title",goal).put("goal",goal).put("dueAt",2000).put("expiresAt",20000);}
    private static JSONObject input(JSONArray steps)throws Exception{return new JSONObject().put("kind","explicit_user").put("title","核实并交付真实结果").put("goal","先核实原结果，再完成交付；失败时核实恢复")
            .put("sourceRef",scope().put("kind","user_action").put("id","native-reviewed-request")).put("steps",steps);}
    private static JSONArray dep(int index,String outcome)throws Exception{return new JSONArray().put(new JSONObject().put("step",index).put("outcome",outcome));}

    private final class Fixture {
        final File file=new File(folder.getRoot(),"plans-"+System.nanoTime()+".json");
        final Map<String,JSONObject> tasks=new HashMap<>();
        boolean online=true,sourceAvailable=true;
        CommitmentBook book;
        String planId;
        Fixture()throws Exception{reopen();}
        void reopen()throws Exception{book=new CommitmentBook(file,(identity,promise,kind,id)->{
            if(!kind.equals("task_result"))return null;
            JSONObject task=null;for(JSONObject candidate:tasks.values())if(candidate.getString("taskId").equals(promise.optString("taskId")))task=candidate;
            if(task==null||!task.optString("status").equals("completed")||!task.optBoolean("resultVerified"))return null;
            return copy(identity).put("kind",kind).put("id",id).put("commitmentId",promise.getString("id")).put("commitmentRevision",promise.getLong("revision"))
                    .put("taskId",task.getString("taskId")).put("outcome","completed").put("recordedAt",3000);
        },now->new AutomationConditions.Snapshot(now,online?"validated":"disconnected","",true,90,""),occurrence->{
            if(!sourceAvailable)throw new java.io.IOException("query unavailable");JSONObject task=tasks.get(occurrence);return task==null?null:copy(task);
        });}
        JSONObject create(JSONArray steps)throws Exception{JSONObject plan=book.createPlan(scope(),input(steps),1000);planId=plan.getString("id");return plan;}
        JSONObject current()throws Exception{return book.getPlan(scope(),planId,4000);}
        JSONObject promise(int i)throws Exception{return current().getJSONArray("steps").getJSONObject(i);}
        JSONObject approve(String mode,int max)throws Exception{return book.approvePlanExecution(scope(),planId,current().getLong("revision"),settings(mode,max),2500);}
        JSONObject prepare(int i)throws Exception{return book.preparePlanTask(scope(),planId,current().getLong("revision"),promise(i).getString("id"),4000);}
        JSONObject original(JSONObject request,String status,boolean terminal,boolean result)throws Exception{
            JSONObject task=new JSONObject().put("occurrenceId",request.getString("occurrenceId")).put("ruleId",request.getString("ruleId")).put("ruleRevision",request.getLong("ruleRevision"))
                    .put("goal",request.getString("goal")).put("actorId","owner").put("spaceId","private").put("taskId","original-"+(tasks.size()+1))
                    .put("status",status).put("terminalVerified",terminal).put("resultVerified",result).put("runId","run-one").put("coordinatorEpoch",7);
            tasks.put(request.getString("occurrenceId"),task);return task;
        }
        JSONObject bind(int i,JSONObject request)throws Exception{return book.bindPlanTask(scope(),planId,promise(i).getString("id"),request.getString("occurrenceId"),4000);}
        JSONObject refresh()throws Exception{return book.refreshPlanTasks(scope(),planId,4000);}
        JSONObject pause(boolean value)throws Exception{return book.setPlanPaused(scope(),planId,current().getLong("revision"),value,4000);}
        void complete(int i)throws Exception{JSONObject p=promise(i);book.onTaskCompleted(scope(),p.getString("id"),p.getLong("revision"),"native-result-"+p.getString("taskId"),p.getString("taskId"),
                new JSONObject().put("kind","task_result").put("id","proof-"+p.getString("taskId")),4000);}
    }

    @Test public void reviewedManualPlanUsesOriginalTaskPauseEditResumeAndVerifiedResultBeforeNextStep()throws Exception {
        Fixture f=new Fixture();f.create(new JSONArray().put(step("核实订单已送达")).put(step("将真实结果交付机主")));
        assertEquals(0,f.book.readyPlanTasks(scope(),4000,10).getJSONArray("candidates").length());
        assertEquals("waiting_for_owner_start",f.approve("manual",2).getString("state"));
        f.book.startPlanExecution(scope(),f.planId,f.current().getLong("revision"),3000);
        assertEquals(1,f.book.readyPlanTasks(scope(),4000,10).getJSONArray("candidates").length());
        JSONObject request=f.prepare(0),task=f.original(request,"running",false,false);f.bind(0,request);
        assertFalse(request.getBoolean("executionAuthorized"));assertEquals(0,f.book.due(scope(),4000,10).getJSONArray("commitments").length());
        JSONObject paused=f.pause(true),control=paused.getJSONArray("taskControls").getJSONObject(0);
        assertEquals("paused",paused.getString("state"));assertEquals(task.getString("taskId"),control.getString("taskId"));assertEquals("run-one",control.getString("runId"));assertEquals(7,control.getLong("coordinatorEpoch"));
        assertEquals("running",control.getString("lastObservedState"));assertFalse(control.getBoolean("controlApplied"));
        assertThrows(IllegalStateException.class,()->f.book.planTaskRequest(scope(),f.planId,f.promise(0).getString("id"),request.getString("occurrenceId"),4000));
        assertThrows(IllegalStateException.class,()->f.book.editPlanStep(scope(),f.planId,f.current().getLong("revision"),f.promise(0).getString("id"),f.promise(0).getLong("revision"),new JSONObject().put("goal","另一个订单"),4000));
        JSONObject future=f.promise(1);JSONObject edited=f.book.editPlanStep(scope(),f.planId,f.current().getLong("revision"),future.getString("id"),future.getLong("revision"),new JSONObject().put("goal","将原订单核实结果和凭证一起交付机主"),4000);
        assertFalse(edited.getBoolean("approvalValid"));assertThrows(IllegalStateException.class,()->f.pause(false));
        f.approve("manual",2);f.pause(false);
        JSONObject same=f.book.planTaskRequest(scope(),f.planId,f.promise(0).getString("id"),request.getString("occurrenceId"),4000);
        assertEquals(request.getString("occurrenceId"),same.getString("occurrenceId"));assertEquals(task.getString("taskId"),same.getString("taskId"));assertEquals(1,f.current().getJSONObject("execution").getInt("admissions"));
        task.put("status","completed");f.refresh();assertEquals(0,f.current().getInt("completedSteps"));assertEquals(0,f.book.readyPlanTasks(scope(),4000,10).getJSONArray("candidates").length());
        assertThrows(IllegalStateException.class,()->f.complete(0));task.put("resultVerified",true);assertEquals(1,f.refresh().getJSONArray("needsCompletionEvidence").length());f.complete(0);
        JSONObject second=f.prepare(1),last=f.original(second,"completed",true,true);f.bind(1,second);f.complete(1);
        assertEquals("completed",f.current().getString("state"));assertEquals(2,f.current().getInt("completedSteps"));assertEquals(2,f.current().getJSONObject("execution").getInt("admissions"));assertEquals(last.getString("taskId"),f.promise(1).getString("taskId"));
    }

    @Test public void failureBranchRequiresCurrentNativeSettledProofAndRecordsRecoveryWithoutFalseSuccess()throws Exception {
        Fixture f=new Fixture();f.create(new JSONArray().put(step("提交并核实交付"))
                .put(step("成功后保存交付凭证").put("dependsOn",dep(0,"completed")))
                .put(step("失败后核实原因并请机主处理").put("dependsOn",dep(0,"failed"))));f.approve("automatic",3);
        JSONObject first=f.prepare(0),task=f.original(first,"failed",false,false);f.bind(0,first);
        assertEquals("pending",f.promise(0).getString("status"));assertEquals(0,f.book.readyPlanTasks(scope(),4000,10).getJSONArray("candidates").length());
        task.put("terminalVerified",true);f.refresh();assertEquals("failed",f.promise(0).getString("status"));assertEquals("skipped",f.promise(1).getString("status"));
        assertEquals(f.promise(2).getString("id"),f.book.readyPlanTasks(scope(),4000,10).getJSONArray("candidates").getJSONObject(0).getString("stepId"));
        JSONObject recovery=f.prepare(2);task.put("status","running").put("terminalVerified",false);
        assertThrows(IllegalStateException.class,()->f.book.planTaskRequest(scope(),f.planId,f.promise(2).getString("id"),recovery.getString("occurrenceId"),4000));
        task.put("status","failed").put("terminalVerified",true);f.book.planTaskRequest(scope(),f.planId,f.promise(2).getString("id"),recovery.getString("occurrenceId"),4000);
        f.original(recovery,"completed",true,true);f.bind(2,recovery);f.complete(2);
        assertEquals("finished_with_failures",f.current().getString("state"));assertEquals(1,f.current().getInt("failedSteps"));assertEquals(1,f.current().getInt("skippedSteps"));assertEquals(1,f.current().getInt("completedSteps"));
    }

    @Test public void unknownCreationSurvivesRestartAndCannotBeEditedRetriedOrReplaced()throws Exception {
        Fixture f=new Fixture();f.create(new JSONArray().put(step("核实原任务结果")));f.approve("automatic",2);JSONObject original=f.prepare(0);f.reopen();
        JSONObject lookup=f.prepare(0);assertTrue(lookup.getBoolean("lookupOnly"));assertEquals(original.getString("occurrenceId"),lookup.getString("occurrenceId"));
        f.sourceAvailable=false;f.refresh();assertEquals("task_creation_unknown",f.promise(0).getJSONObject("taskAdmission").getString("state"));assertEquals(1,f.current().getJSONObject("execution").getInt("admissions"));
        JSONObject paused=f.pause(true);assertEquals(original.getString("occurrenceId"),paused.getJSONArray("taskControls").getJSONObject(0).getString("occurrenceId"));
        assertThrows(IllegalStateException.class,()->f.book.retryPlanStep(scope(),f.planId,f.current().getLong("revision"),f.promise(0).getString("id"),f.promise(0).getLong("revision"),4000));
        assertThrows(IllegalStateException.class,()->f.book.editPlanStep(scope(),f.planId,f.current().getLong("revision"),f.promise(0).getString("id"),f.promise(0).getLong("revision"),new JSONObject().put("dueAt",5000),4000));
        f.sourceAvailable=true;JSONObject task=f.original(original,"failed",true,false);f.refresh();JSONObject failed=f.promise(0);
        task.put("status","running").put("terminalVerified",false);assertThrows(IllegalStateException.class,()->f.book.retryPlanStep(scope(),f.planId,f.current().getLong("revision"),failed.getString("id"),failed.getLong("revision"),4000));
        task.put("status","failed").put("terminalVerified",true);f.book.retryPlanStep(scope(),f.planId,f.current().getLong("revision"),failed.getString("id"),failed.getLong("revision"),4000);
        assertEquals(task.getString("taskId"),f.promise(0).getJSONArray("priorTaskRefs").getJSONObject(0).getString("taskId"));assertFalse(f.current().getBoolean("approvalValid"));
        f.approve("automatic",2);f.pause(false);JSONObject retry=f.prepare(0);assertNotEquals(original.getString("occurrenceId"),retry.getString("occurrenceId"));assertEquals(2,f.current().getJSONObject("execution").getInt("admissions"));
        assertThrows(IllegalArgumentException.class,()->f.book.onTaskCompleted(scope(),failed.getString("id"),failed.getLong("revision"),"late-old-task",task.getString("taskId"),new JSONObject().put("kind","task_result").put("id","late-old-proof"),4000));
    }

    @Test public void currentConditionsDueAndOwnerCasAreRecheckedAtAdmissionAndCancelKeepsControlIdentity()throws Exception {
        Fixture f=new Fixture();f.create(new JSONArray().put(step("核实并保存资料")));JSONObject approved=f.book.approvePlanExecution(scope(),f.planId,f.current().getLong("revision"),settings("automatic",1).put("requiresNetwork",true),2500);
        assertEquals(0,f.book.readyPlanTasks(scope(),1500,10).getJSONArray("candidates").length());f.online=false;assertEquals(0,f.book.readyPlanTasks(scope(),4000,10).getJSONArray("candidates").length());f.online=true;
        JSONObject request=f.prepare(0);f.online=false;assertThrows(IllegalStateException.class,()->f.book.planTaskRequest(scope(),f.planId,f.promise(0).getString("id"),request.getString("occurrenceId"),4000));f.online=true;
        assertThrows(IllegalStateException.class,()->f.book.planTaskRequest(scope(),f.planId,f.promise(0).getString("id"),request.getString("occurrenceId"),1500));
        JSONObject task=f.original(request,"running",false,false);f.bind(0,request);byte[] before=Files.readAllBytes(f.file.toPath());
        assertThrows(IllegalStateException.class,()->f.book.setPlanPaused(scope(),f.planId,approved.getLong("revision"),true,4000));assertArrayEquals(before,Files.readAllBytes(f.file.toPath()));
        assertThrows(IllegalStateException.class,()->f.book.cancel(scope(),f.promise(0).getString("id"),f.promise(0).getLong("revision"),4000));
        assertThrows(IllegalStateException.class,()->f.book.cancelPlan(scope(),f.planId,f.current().getLong("revision"),4000));
        JSONObject cancelled=f.book.cancelPlanExecution(scope(),f.planId,f.current().getLong("revision"),4000),control=cancelled.getJSONArray("taskControls").getJSONObject(0);
        assertEquals("cancel",control.getString("action"));assertEquals(task.getString("taskId"),control.getString("taskId"));assertEquals("running",control.getString("lastObservedState"));assertFalse(cancelled.getBoolean("taskControlApplied"));
        assertEquals(0,f.book.readyPlanTasks(scope(),4000,10).getJSONArray("candidates").length());f.reopen();assertEquals(1,f.current().getJSONArray("taskControls").length());
        assertThrows(SecurityException.class,()->f.book.readyPlanTasks(new JSONObject().put("ownerId","owner").put("spaceId","group-x"),4000,10));
    }

    @Test public void invalidGraphAndFailedAtomicIntentWriteLeavePriorReviewedStateUnchanged()throws Exception {
        Fixture f=new Fixture();JSONObject invalid=input(new JSONArray().put(step("自循环").put("dependsOn",dep(0,"completed"))));assertThrows(IllegalArgumentException.class,()->f.book.createPlan(scope(),invalid,1000));assertFalse(f.file.exists());
        f.create(new JSONArray().put(step("核实待办")));f.approve("automatic",1);byte[] before=Files.readAllBytes(f.file.toPath());
        File blocker=new File(f.file.getPath()+".new");assertTrue(blocker.mkdir());Files.write(new File(blocker,"prevent-replace").toPath(),new byte[]{1});
        assertThrows(java.io.FileNotFoundException.class,()->f.prepare(0));assertArrayEquals(before,Files.readAllBytes(f.file.toPath()));assertEquals(0,f.current().getJSONObject("execution").getInt("admissions"));
        Fixture unavailable=new Fixture();CommitmentBook legacy=new CommitmentBook(unavailable.file);JSONObject old=legacy.createPlan(scope(),input(new JSONArray().put(step("旧计划"))),1000);byte[] oldBytes=Files.readAllBytes(unavailable.file.toPath());
        assertThrows(IllegalStateException.class,()->legacy.refreshPlanTasks(scope(),old.getString("id"),4000));assertArrayEquals(oldBytes,Files.readAllBytes(unavailable.file.toPath()));
    }

    @Test public void freshReviewCannotExtendAnOldUnknownAdmissionWindowOrResetItsBudget()throws Exception {
        Fixture f=new Fixture();f.create(new JSONArray().put(step("核实原发生身份")));f.book.approvePlanExecution(scope(),f.planId,f.current().getLong("revision"),settings("automatic",1).put("expiresAt",5000),2500);
        JSONObject original=f.prepare(0);assertEquals(5000,original.getLong("validUntil"));f.pause(true);f.approve("automatic",1);f.pause(false);
        JSONObject same=f.book.planTaskRequest(scope(),f.planId,f.promise(0).getString("id"),original.getString("occurrenceId"),4500);assertEquals(5000,same.getLong("validUntil"));assertEquals(1,f.current().getJSONObject("execution").getInt("admissions"));
        assertThrows(IllegalStateException.class,()->f.book.planTaskRequest(scope(),f.planId,f.promise(0).getString("id"),original.getString("occurrenceId"),5000));assertEquals("task_creation_unknown",f.promise(0).getJSONObject("taskAdmission").getString("state"));
    }

    @Test public void missingObservationConsentCannotPrepareOrConsumeAnAdmission()throws Exception {
        Fixture f=new Fixture();f.create(new JSONArray().put(step("需要机主明确同意的目标")));
        f.book.approvePlanExecution(scope(),f.planId,f.current().getLong("revision"),new JSONObject().put("mode","automatic").put("maxTaskAdmissions",1),2500);
        byte[] before=Files.readAllBytes(f.file.toPath());JSONObject decision=f.prepare(0);
        assertFalse(decision.getBoolean("eligible"));assertEquals("model_consent_required",decision.getString("reason"));assertFalse(decision.has("occurrenceId"));
        assertEquals(0,f.book.readyPlanTasks(scope(),f.planId,4000,1).getJSONArray("candidates").length());assertEquals(0,f.current().getJSONObject("execution").getInt("admissions"));
        assertArrayEquals(before,Files.readAllBytes(f.file.toPath()));
    }

    @Test public void conditionRecoveryKeepsOriginalOccurrenceAndRetryHistoryThroughTheSuccessorResult()throws Exception {
        Fixture f=new Fixture();f.create(new JSONArray().put(step("核实原始结果")).put(step("保存后继交付结果")));String goal=f.planId;
        f.book.approvePlanExecution(scope(),goal,f.current().getLong("revision"),settings("automatic",3).put("requiresNetwork",true),2500);
        JSONObject first=f.prepare(0),failed=f.original(first,"failed",true,false);f.bind(0,first);f.refresh();
        assertEquals("skipped",f.promise(1).getString("status"));f.pause(true);JSONObject stopped=f.promise(0);
        f.book.retryPlanStep(scope(),goal,f.current().getLong("revision"),stopped.getString("id"),stopped.getLong("revision"),4000);
        f.book.approvePlanExecution(scope(),goal,f.current().getLong("revision"),settings("automatic",3).put("requiresNetwork",true),4000);f.pause(false);f.online=false;
        assertEquals("conditions_blocked",f.prepare(0).getString("reason"));assertEquals(1,f.current().getJSONObject("execution").getInt("admissions"));
        f.online=true;JSONObject retry=f.prepare(0),running=f.original(retry,"running",false,false);f.bind(0,retry);
        assertNotEquals(first.getString("occurrenceId"),retry.getString("occurrenceId"));f.online=false;
        assertThrows(IllegalStateException.class,()->f.book.planTaskRequest(scope(),goal,f.promise(0).getString("id"),retry.getString("occurrenceId"),4000));
        f.online=true;f.reopen();JSONObject resumed=f.book.planTaskRequest(scope(),goal,f.promise(0).getString("id"),retry.getString("occurrenceId"),4000);
        assertEquals(retry.getString("occurrenceId"),resumed.getString("occurrenceId"));assertEquals(running.getString("taskId"),resumed.getString("taskId"));assertEquals(2,f.current().getJSONObject("execution").getInt("admissions"));
        running.put("status","completed").put("resultVerified",true);f.refresh();f.complete(0);
        JSONObject next=f.prepare(1);f.original(next,"completed",true,true);f.bind(1,next);f.complete(1);f.reopen();
        JSONObject completed=f.current(),history=f.promise(0).getJSONArray("priorTaskRefs").getJSONObject(0);
        assertEquals(goal,completed.getString("id"));assertEquals("completed",completed.getString("state"));assertEquals(3,completed.getJSONObject("execution").getInt("admissions"));
        assertEquals(failed.getString("taskId"),history.getString("taskId"));assertEquals(first.getString("occurrenceId"),history.getString("occurrenceId"));
        assertEquals("failed",history.getString("status"));assertEquals("run-one",history.getString("runId"));assertEquals(7,history.getInt("coordinatorEpoch"));
        assertEquals(running.getString("taskId"),f.promise(0).getString("taskId"));assertNotNull(f.promise(0).getJSONObject("completionEvidence"));
    }
}
