package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.assistant.OwnerQuestionInputs;
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReply;
import dev.pocketengine.runtime.RepeatToolNotice;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original SQLite admission. Fixed native project metadata is not a Local checkout or HTTP fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ReviewedDevelopmentContinuationTest {
    private Application app;
    private TaskStore store;
    private SQLiteDatabase sql;
    private final AtomicBoolean ready=new AtomicBoolean();
    private final List<RunControl> controls=new ArrayList<>();
    private record Original(String id,String run,int epoch,String conversation,RunControl control,RunJournal journal){}
    private record Question(Original original,String request,long revision,String token,OwnerQuestionInputs.Waiting waiting){}
    @Before public void prepare()throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase(TaskStore.DATABASE_NAME);open();
    }
    private void open()throws Exception {
        ready.set(false);store=new TaskStore(app,ready::get,(stage,schema)->{});
        store.verifyBootstrapReadback();Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);sql=(SQLiteDatabase)field.get(store);ready.set(true);
    }
    @After public void close(){
        for(RunControl control:controls)control.cancel();ready.set(false);
        if(store!=null&&!store.closedForMaintenance())store.closeForMaintenance();
    }
    private RunControl control(){RunControl value=new RunControl();controls.add(value);return value;}
    private Original start(boolean planning,int tools,int models,boolean scoped)throws Exception {
        String conversation=UUID.randomUUID().toString();
        store.conversationRepository().transaction(()->{store.conversationRepository().saveConversation(new JSONObject().put("conversationId",conversation).put("title","Original development SQL fixture").put("messages",new JSONArray()));return null;});
        String id=store.createCapabilityTask("Continue this original project only",conversation).getString("id");
        if(scoped)store.configureExecutionScope(id,0,0,tools,models);
        if(planning)store.enterExecutionPlanning(id,"",0,1,()->true);
        JSONObject attempt=store.startAttempt(id,"capabilities",tools);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");
        RunControl original=control();original.bindHostRun(id,run,epoch);
        store.bindDevelopmentContext(id,run,epoch,original,new JSONObject().put("format","pocketworld.development-context.v1")
                .put("projectId",UUID.randomUUID().toString()).put("checkoutId",UUID.randomUUID().toString()).put("runtime","managed_process_v1")
                .put("initialSourceDigest","a".repeat(64)).put("clientMessageId",UUID.randomUUID().toString()));
        store.authorizeTools(id,run,new JSONArray().put("workspace_write").put("memory_search"));
        store.status(id,"running","Original controlled SQL fixture");
        return new Original(id,run,epoch,conversation,original,store.journal(id,run,epoch,original));
    }
    private Original start()throws Exception{return start(false,8,8,true);}
    private JSONObject end(Original original,String status)throws Exception {
        store.endAttempt(original.id,original.run,status,"Original fixture worker settled",new JSONObject());return store.get(original.id,false);
    }
    private JSONObject reserve(JSONObject reviewed,RunControl next)throws Exception{return store.startOriginalDevelopmentAttempt(reviewed,40,next,"  Continue only the reviewed original project  ",()->true);}
    private JSONObject grant(String run)throws Exception {
        try(Cursor row=sql.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}
    }
    private String runStatus(String run){try(Cursor row=sql.rawQuery("SELECT status FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return row.getString(0);}}
    private int count(String table,String task){try(Cursor row=sql.rawQuery("SELECT count(*) FROM "+table+" WHERE task_id=?",new String[]{task})){assertTrue(row.moveToFirst());return row.getInt(0);}}
    private String state(String task)throws Exception {
        JSONObject value=new JSONObject().put("task",store.get(task,false));JSONArray rows=new JSONArray();
        try(Cursor cursor=sql.rawQuery("SELECT run_id,epoch,status,checkpoint,grant_json FROM runs WHERE task_id=? ORDER BY epoch",new String[]{task})){
            while(cursor.moveToNext())rows.put(new JSONArray().put(cursor.getString(0)).put(cursor.getInt(1)).put(cursor.getString(2)).put(cursor.getString(3)).put(cursor.getString(4)));
        }
        return TaskStore.capabilityFingerprint(value.put("runs",rows));
    }
    private void rejected(JSONObject reviewed)throws Exception {
        String id=reviewed.getString("id"),before=state(id);RunControl next=control();
        assertThrows(Exception.class,()->reserve(reviewed,next));assertEquals(before,state(id));assertFalse(next.hasHostRun());
    }
    private String spendTool(Original original,boolean settled)throws Exception {
        String operation=original.journal.beginOperation("workspace_write",new JSONObject().put("path","original.txt"));
        if(settled)original.journal.finishOperation(operation,new JSONObject().put("ok",true).put("accepted",true));return operation;
    }
    private void spendModel(Original original)throws Exception {
        // The actual native STARTED/terminal sink spends SQL model quota; no network request is made.
        ModelConfig model=new ModelConfig("http://127.0.0.1:9/v1","fixture-key","fixture-model",false,8,30,"openai_chat",128);
        InferenceCapture capture=new InferenceCapture(model,original.control,original.journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));
        capture.tokenReservation(history(),new JSONArray());capture.dispatch();capture.finish(InferenceReceipt.Outcome.COMPLETED);
    }
    private static JSONArray history()throws Exception{return new JSONArray().put(new JSONObject().put("role","system").put("content","Native SQL fixture"))
            .put(new JSONObject().put("role","user").put("content","Keep this original project"));}
    private static String fingerprint(JSONObject value)throws Exception{return TaskStore.capabilityFingerprint(value);}
    private JSONObject storedTask(String id)throws Exception {try(Cursor row=sql.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{id})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}}
    /** Negative persisted-state fixtures only; successful admissions use the original public native methods. */
    private void replaceStoredTask(JSONObject task){ContentValues row=new ContentValues();row.put("document",task.toString());assertEquals(1,sql.update("tasks",row,"task_id=?",new String[]{task.optString("id")}));}
    private Question ask(Original original)throws Exception {
        store.openOwnerInputMailbox(original.id,original.run,original.epoch,original.conversation,original.control);
        String message=UUID.randomUUID().toString(),client=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();long revision=2;
        String content="Original project question fixture",payload=fingerprint(new JSONObject().put("content",content).put("mode","queue").put("attachments",new JSONArray()));
        JSONObject ack=new JSONObject().put("status","unconfirmed").put("messageId",message).put("clientMessageId",client).put("conversationId",original.conversation);
        JSONObject user=new JSONObject().put("id",message).put("revision",1L).put("role","user").put("content",content).put("clientMessageId",client)
                .put("conversationId",original.conversation).put("at",System.currentTimeMillis()).put("inputMode","queue").put("requestFingerprint",payload).put("submission",ack);
        var history=store.conversationRepository();history.transaction(()->{
            history.saveSource(new JSONObject().put("kind","message").put("sourceId",message).put("revision",1L).put("ownerId","owner").put("spaceId","private")
                    .put("role","user").put("conversationId",original.conversation).put("deleted",false).put("updatedAt",System.currentTimeMillis())
                    .put("clientMessageId",client).put("requestFingerprint",payload).put("submission",ack));history.saveMessage(original.conversation,user);return null;
        });
        store.appendOwnerInput(original.id,original.run,original.epoch,original.conversation,user,"queue",original.control);
        assertEquals(1,store.pollOwnerInputs(original.id,original.run,original.epoch,new JSONArray(),true,false,original.control).length());
        original.journal.checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content",content).put("ownerMessageId",message).put("ownerMessageRevision",1L).put("ownerInputMode","queue"))
                .put(new JSONObject().put("role","assistant").put("content","Which original option?")),1,new JSONObject());
        JSONObject question=new JSONObject().put("format","pocketworld.owner-question.v1").put("taskId",original.id).put("runId",original.run).put("coordinatorEpoch",original.epoch)
                .put("requestId",request).put("waitRevision",revision).put("question","Which original option?").put("replyKind","answer").put("approval",false).put("takeover",false);
        store.endAttempt(original.id,original.run,"awaiting_user","Which original option?",new JSONObject().put("ownerQuestion",question));
        TaskStore.OwnerTaskNotice notice=store.ownerTaskNotice(original.id);assertNotNull(notice);assertTrue(store.beginOwnerTaskNoticePosting(notice));store.ownerTaskNoticePhase(notice,"posted");
        OwnerQuestionInputs.Waiting waiting=store.retainOwnerQuestionOriginal(new OwnerQuestionInputs(),original.id,original.control,new ModelConfig("http://127.0.0.1:9/v1","","fixture",false,8,30));
        assertNotNull(waiting);return new Question(original,request,revision,notice.view().getString("token"),waiting);
    }
    private JSONObject answer(Question question)throws Exception {
        Original original=question.original;
        return store.consumeOwnerQuestionReply(original.id,original.run,original.epoch,question.request,question.revision,question.token,"Use the original second option");
    }

    @Test public void completedSuccessPreservesConversationCheckpointSpentScopeAndOldCeiling()throws Exception {
        Original first=start();String operation=spendTool(first,true);spendModel(first);
        RepeatToolNotice progress=new RepeatToolNotice();progress.beginRound(1);progress.afterTool("memory_search",new JSONObject().put("query","same"),new JSONObject().put("ok",false).put("code","permission_denied"),true,false);
        first.journal.checkpoint(history(),3,new JSONObject(),progress.snapshot());
        JSONObject reviewed=end(first,"completed"),scope=new JSONObject(reviewed.getJSONObject("executionScope").toString()),oldGrant=grant(first.run);
        assertEquals(1,scope.getInt("toolsUsed"));assertEquals(1,scope.getInt("modelCallsUsed"));assertEquals(8,oldGrant.getInt("stepCeiling"));
        reviewed.put("coordinatorEpoch",(long)first.epoch);RunControl nextControl=control();JSONObject next=reserve(reviewed,nextControl);
        assertEquals(first.id,next.getString("id"));assertEquals(first.conversation,next.getString("conversationId"));assertNotEquals(first.run,next.getString("runId"));assertEquals(first.epoch+1,next.getInt("coordinatorEpoch"));
        assertEquals(fingerprint(scope),fingerprint(next.getJSONObject("executionScope")));assertEquals(fingerprint(reviewed.getJSONObject("developmentContext")),fingerprint(next.getJSONObject("developmentContext")));
        assertEquals(8,next.getInt("stepCeiling"));assertEquals(8,grant(next.getString("runId")).getInt("stepCeiling"));assertEquals(7,grant(next.getString("runId")).getInt("remainingOperations"));
        assertEquals("Continue only the reviewed original project",next.getString("feedback"));
        assertEquals(next.getString("runId"),store.requireDevelopmentContinuationAttempt(next,nextControl).getString("runId"));
        JSONObject restored=store.journal(first.id,next.getString("runId"),next.getInt("coordinatorEpoch"),nextControl).restore();
        assertEquals(3,restored.getInt("steps"));assertEquals(fingerprint(progress.snapshot()),fingerprint(restored.getJSONObject("progressGuard")));assertEquals(history().toString(),restored.getJSONArray("messages").toString());
        assertEquals(operation,store.get(first.id,false).getJSONArray("operations").getJSONObject(0).getString("operationId"));assertEquals(2,count("runs",first.id));assertEquals(1,store.list().length());
        rejected(reviewed);assertEquals(2,count("runs",first.id));
    }

    @Test public void ownerConfirmedCompletionKeepsTheDifferentOriginalRunStatus()throws Exception {
        Original first=start();JSONObject result=end(first,"needs_confirmation");
        JSONObject reviewed=store.confirmReviewedResult(first.id,result.getString("resultReviewDigest"),true,"");
        assertEquals("completed",reviewed.getString("status"));assertEquals("needs_confirmation",runStatus(first.run));
        RunControl nextControl=control();JSONObject next=reserve(reviewed,nextControl);store.requireDevelopmentContinuationAttempt(next,nextControl);
        assertEquals("needs_confirmation",runStatus(first.run));assertEquals(first.id,next.getString("id"));
    }

    @Test public void eachOfTheSixEndedStatesKeepsItsOriginalTaskAndConversation()throws Exception {
        for(String status:new String[]{"paused","interrupted","awaiting_user","needs_confirmation","failed","completed"}){
            Original first=start();JSONObject reviewed=end(first,status);RunControl nextControl=control();JSONObject next=reserve(reviewed,nextControl);
            assertEquals(first.id,next.getString("id"));assertEquals(first.conversation,next.getString("conversationId"));assertEquals(first.epoch+1,next.getInt("coordinatorEpoch"));
            store.requireDevelopmentContinuationAttempt(next,nextControl);rejected(reviewed);assertEquals(2,count("runs",first.id));
        }
    }

    @Test public void existingToolPinTighteningCannotWidenTheOriginalList()throws Exception {
        Original first=start();JSONObject reviewed=end(first,"failed");assertFalse(reviewed.getJSONObject("executionScope").getBoolean("toolsPinned"));
        JSONArray allowed=reviewed.getJSONObject("executionScope").getJSONArray("tools");RunControl nextControl=control();JSONObject next=reserve(reviewed,nextControl);
        assertTrue(next.getJSONObject("executionScope").getBoolean("toolsPinned"));assertEquals(allowed.toString(),next.getJSONObject("executionScope").getJSONArray("tools").toString());
        store.requireDevelopmentContinuationAttempt(next,nextControl);
        store.authorizeTools(first.id,next.getString("runId"),new JSONArray().put("workspace_write").put("never_originally_granted"));
        assertEquals(new JSONArray().put("workspace_write").toString(),grant(next.getString("runId")).getJSONArray("tools").toString());
        assertEquals(allowed.toString(),store.get(first.id,false).getJSONObject("executionScope").getJSONArray("tools").toString());
    }

    @Test public void sameRevisionCannotSubstituteContextCountersToolsPhaseOrExactPlan()throws Exception {
        Original first=start(true,12,12,true);ExecutionPlanning planning=first.journal.planning();
        planning.submitPlan(planning.capture(),new JSONObject().put("format","pocketworld.execution-plan.v1").put("goal","Read the original source")
                .put("steps",new JSONArray().put(new JSONObject().put("id","read").put("title","Read source").put("detail","Use the original checkout and review changes"))),"native-plan-fixture");
        JSONObject proposed=end(first,"awaiting_user"),approval=proposed.getJSONArray("approvals").getJSONObject(0),plan=store.readTaskExecutionPlanApproval(first.id,approval.getString("id"));
        store.decideExecutionPlanApproval(first.id,approval.getString("id"),true,first.run,first.epoch,plan.getLong("scopeRevision"),plan.getLong("planRevision"),plan.getString("planArtifactRef"),plan.getString("planHash"),()->true);
        JSONObject reviewed=store.get(first.id,false);
        for(String key:new String[]{"projectId","checkoutId","runtime","initialSourceDigest","clientMessageId"}){
            JSONObject wrong=new JSONObject(reviewed.toString());wrong.getJSONObject("developmentContext").put(key,"changed-native-selection");rejected(wrong);
        }
        for(String key:new String[]{"toolsUsed","modelCallsUsed","toolLimit","modelCallLimit","deadlineAt","executionPhase","planRevision","planArtifactRef","planHash","planApproval","tools"}){
            JSONObject wrong=new JSONObject(reviewed.toString());JSONObject scope=wrong.getJSONObject("executionScope");long revision=scope.getLong("revision");scope.put(key,"changed-at-same-revision");assertEquals(revision,scope.getLong("revision"));rejected(wrong);
        }
        JSONObject wrong=new JSONObject(reviewed.toString()).put("conversationId",UUID.randomUUID().toString());rejected(wrong);
        wrong=new JSONObject(reviewed.toString()).put("coordinatorEpoch",first.epoch+1);rejected(wrong);
        wrong=new JSONObject(reviewed.toString()).put("status","failed");rejected(wrong);
        RunControl nextControl=control();JSONObject next=reserve(reviewed,nextControl);store.requireDevelopmentContinuationAttempt(next,nextControl);
    }

    @Test public void incompleteReviewCannotBorrowMissingFieldsFromTheLatestTask()throws Exception {
        Original first=start();JSONObject reviewed=end(first,"completed");
        for(String key:new String[]{"kind","actorId","spaceId","executionMode","conversationId","runId","coordinatorEpoch","status","cancelRequested","developmentContext","executionScope"}){
            JSONObject missing=new JSONObject(reviewed.toString());missing.remove(key);rejected(missing);
        }
        JSONObject wrong=new JSONObject(reviewed.toString()).put("runId",UUID.randomUUID().toString());rejected(wrong);
    }

    @Test public void lateWindowLossRollsBackRunFeedbackScopeAndOriginalSourceReservation()throws Exception {
        Original first=start();JSONObject reviewed=end(first,"completed");String before=state(first.id);AtomicInteger calls=new AtomicInteger();RunControl nextControl=control();
        assertThrows(SecurityException.class,()->store.startOriginalDevelopmentAttempt(reviewed,40,nextControl,"This must roll back",()->{
            if(calls.incrementAndGet()==1)return true;assertEquals(2,count("runs",first.id));return false;
        }));
        assertEquals(2,calls.get());assertEquals(before,state(first.id));assertFalse(nextControl.hasHostRun());assertEquals(1,count("runs",first.id));
        assertThrows(SecurityException.class,()->store.startOriginalDevelopmentAttempt(reviewed,40,control(),"No window",()->false));
        assertThrows(SecurityException.class,()->store.startOriginalDevelopmentAttempt(reviewed,40,control(),"No native supplier",null));assertEquals(before,state(first.id));
    }

    @Test public void stoppedCancelledQueuedAndActiveStatesCannotResurrect()throws Exception {
        Original first=start();end(first,"completed");
        for(String status:new String[]{"cancelled","canceled","stopped","stopping","queued","preparing","running","pausing","reconciling","unknown","pending"}){
            store.status(first.id,status,"Rejected state fixture");rejected(store.get(first.id,false));
        }
        store.status(first.id,"paused","Original owner stop is independent of presentation");store.requestCancel(first.id,first.run);rejected(store.get(first.id,false));
    }

    @Test public void pausedPresentationCannotHideAWorkerOrAMissingOriginalRun()throws Exception {
        Original first=start();store.status(first.id,"paused","Actual original SQL run is still running");rejected(store.get(first.id,false));
        JSONObject reviewed=end(first,"paused");assertEquals(1,sql.delete("runs","task_id=? AND run_id=?",new String[]{first.id,first.run}));rejected(reviewed);
    }

    @Test public void pendingEffectRemainsOriginalAndCannotBeReplayed()throws Exception {
        Original first=start();String operation=spendTool(first,false);end(first,"paused");assertTrue(store.hasUnresolved(first.id));
        store.status(first.id,"paused","Presentation cannot clear the original prepared effect");rejected(store.get(first.id,false));
        JSONObject original=store.get(first.id,false).getJSONArray("operations").getJSONObject(0);assertEquals(operation,original.getString("operationId"));assertEquals("prepared",original.getString("state"));assertEquals(1,count("runs",first.id));
    }

    @Test public void pendingApprovalAfterReviewRefusesWithoutFeedbackOrNewRun()throws Exception {
        Original first=start();JSONObject reviewed=end(first,"completed");
        JSONObject pending=store.requireCapabilityApproval(first.id,first.run,"workspace_write",new JSONObject().put("path","reviewed.txt"),new JSONObject().put("kind","fixture_original_write"));
        assertEquals("pending",pending.getString("state"));assertFalse(store.hasUnresolved(first.id));rejected(reviewed);rejected(store.get(first.id,false));
        assertEquals(1,count("runs",first.id));assertEquals("",store.get(first.id,false).getString("feedback"));
        store.decideCapabilityApproval(first.id,pending.getString("approvalId"),false,first.run,first.epoch);
        RunControl nextControl=control();JSONObject next=reserve(store.get(first.id,false),nextControl);store.requireDevelopmentContinuationAttempt(next,nextControl);
        assertEquals("denied",store.get(first.id,false).getJSONArray("approvals").getJSONObject(0).getString("state"));
    }

    @Test public void unansweredCurrentQuestionRefusesWithoutFeedbackOrNewRun()throws Exception {
        Original first=start();ask(first);JSONObject reviewed=store.get(first.id,false);
        assertNotNull(OwnerQuestionReply.identity(reviewed));assertFalse(store.hasUnresolved(first.id));rejected(reviewed);
        assertEquals(1,count("runs",first.id));assertEquals("",store.get(first.id,false).getString("feedback"));
        // A lost mailbox is not permission to route an unanswered exact question through Continue.
        JSONObject lostMailbox=storedTask(first.id);lostMailbox.remove("ownerInputMailbox");replaceStoredTask(lostMailbox);
        assertNull(OwnerQuestionReply.reference(store.get(first.id,false)));assertNotNull(OwnerQuestionReply.identity(store.get(first.id,false)));rejected(store.get(first.id,false));
    }

    @Test public void queuedOriginalQuestionWorkerRequiresItsExactSettledMarker()throws Exception {
        Original first=start();Question question=ask(first);JSONObject accepted=answer(question);
        assertEquals("original_worker_requested",store.noteOwnerQuestionAnswerState(accepted,"original_worker_requested").getString("continuationState"));
        assertNull(OwnerQuestionReply.identity(store.get(first.id,false)));assertTrue(OwnerQuestionInputs.pageQuestion(store.get(first.id,false)).getBoolean("answered"));
        rejected(store.get(first.id,false));assertEquals(1,count("runs",first.id));
        store.resumeOwnerQuestionRun(question.waiting);store.ownerQuestionContinuationUnavailable(question.waiting);
        JSONObject settled=storedTask(first.id);assertEquals("unavailable",settled.getJSONObject("ownerQuestionContinuation").getString("phase"));
        // Negative persisted marker fixtures cannot borrow a different wait's settled proof.
        for(String field:new String[]{"runId","epoch","requestId","waitRevision","messageId","phase"}){
            JSONObject changed=new JSONObject(settled.toString());JSONObject marker=changed.getJSONObject("ownerQuestionContinuation");
            marker.put(field,field.equals("epoch")||field.equals("waitRevision")?marker.getLong(field)+1:"different-original-wait");replaceStoredTask(changed);rejected(store.get(first.id,false));
        }
        replaceStoredTask(settled);RunControl nextControl=control();JSONObject next=reserve(store.get(first.id,false),nextControl);store.requireDevelopmentContinuationAttempt(next,nextControl);
        assertEquals(2,count("runs",first.id));assertEquals("pending",store.get(first.id,false).getJSONArray("ownerInputs").getJSONObject(1).getString("queueState"));
    }

    @Test public void savedUnavailableAnswerRetainsTheOriginalExplicitColdContinuation()throws Exception {
        Original first=start();Question question=ask(first);JSONObject accepted=answer(question);
        assertEquals("saved_worker_unavailable",store.noteOwnerQuestionAnswerState(accepted,"saved_worker_unavailable").getString("continuationState"));
        JSONObject before=store.get(first.id,false),scope=new JSONObject(before.getJSONObject("executionScope").toString());scope.put("toolsPinned",true);
        for(RunControl control:controls)control.cancel();ready.set(false);store.closeForMaintenance();open();
        JSONObject reviewed=store.get(first.id,false);assertEquals("saved_worker_unavailable",OwnerQuestionInputs.pageQuestion(reviewed).getString("continuationState"));
        RunControl nextControl=control();JSONObject next=reserve(reviewed,nextControl);store.requireDevelopmentContinuationAttempt(next,nextControl);
        assertEquals(first.id,next.getString("id"));assertEquals(first.conversation,next.getString("conversationId"));assertEquals(fingerprint(scope),fingerprint(next.getJSONObject("executionScope")));
        JSONObject row=store.get(first.id,false).getJSONArray("ownerInputs").getJSONObject(1);assertEquals(accepted.getString("messageId"),row.getString("messageId"));assertEquals("pending",row.getString("queueState"));
        assertEquals(2,store.journal(first.id,next.getString("runId"),next.getInt("coordinatorEpoch"),nextControl).restore().getJSONArray("messages").length());
        assertEquals(2,count("runs",first.id));
    }

    @Test public void materializedHistoricalQuestionDoesNotBlockASecondOriginalAttempt()throws Exception {
        Original first=start();Question question=ask(first);JSONObject accepted=answer(question);
        store.noteOwnerQuestionAnswerState(accepted,"original_worker_requested");store.resumeOwnerQuestionRun(question.waiting);
        JSONArray delivered=store.pollOwnerInputs(first.id,first.run,first.epoch,new JSONArray(),true,false,first.control);assertEquals(1,delivered.length());JSONObject reply=delivered.getJSONObject(0);
        JSONArray messages=first.journal.restore().getJSONArray("messages");messages.put(new JSONObject().put("role","user").put("content",reply.getString("content"))
                .put("ownerMessageId",reply.getString("messageId")).put("ownerMessageRevision",reply.getLong("ownerMessageRevision")).put("ownerInputMode",reply.getString("mode")));
        first.journal.checkpoint(messages,2,new JSONObject());JSONObject task=store.get(first.id,false);
        store.endAttempt(first.id,first.run,"awaiting_user","Later ordinary pause",task.getJSONObject("evidence"));
        JSONObject reviewed=store.get(first.id,false);assertEquals("materialized",reviewed.getJSONArray("ownerInputs").getJSONObject(1).getString("queueState"));
        assertEquals("claimed",reviewed.getJSONObject("ownerQuestionContinuation").getString("phase"));assertTrue(OwnerQuestionInputs.pageQuestion(reviewed).getBoolean("answered"));
        RunControl nextControl=control();JSONObject next=reserve(reviewed,nextControl);store.requireDevelopmentContinuationAttempt(next,nextControl);
        assertEquals(3,store.journal(first.id,next.getString("runId"),next.getInt("coordinatorEpoch"),nextControl).restore().getJSONArray("messages").length());
    }

    @Test public void pickupRechecksLateApprovalAndCurrentQuestionWithoutFurtherMutation()throws Exception {
        Original first=start();RunControl nextControl=control();JSONObject reserved=reserve(end(first,"completed"),nextControl);
        store.requireCapabilityApproval(first.id,reserved.getString("runId"),"workspace_write",new JSONObject().put("path","after-reserve.txt"),new JSONObject().put("kind","fixture_original_write"));
        String afterApproval=state(first.id);SecurityException pending=assertThrows(SecurityException.class,()->store.requireDevelopmentContinuationAttempt(reserved,nextControl));
        assertTrue(pending.getMessage().contains("待审批"));assertEquals(afterApproval,state(first.id));assertEquals(2,count("runs",first.id));
        Original second=start();RunControl questionControl=control();JSONObject captured=reserve(end(second,"completed"),questionControl);String run=captured.getString("runId");int epoch=captured.getInt("coordinatorEpoch");
        Original receiver=new Original(second.id,run,epoch,second.conversation,questionControl,store.journal(second.id,run,epoch,questionControl));ask(receiver);
        String afterQuestion=state(second.id);SecurityException unanswered=assertThrows(SecurityException.class,()->store.requireDevelopmentContinuationAttempt(captured,questionControl));
        assertTrue(unanswered.getMessage().contains("当前的问题"));assertEquals(afterQuestion,state(second.id));assertEquals(2,count("runs",second.id));
    }

    @Test public void missingExpiredAndSpentScopesNeverInitializeOrRefill()throws Exception {
        Original legacy=start(false,8,8,false);rejected(end(legacy,"completed"));assertFalse(store.get(legacy.id,false).has("executionScope"));
        Original tools=start(false,1,8,true);spendTool(tools,true);rejected(end(tools,"completed"));assertEquals(1,store.get(tools.id,false).getJSONObject("executionScope").getInt("toolsUsed"));
        Original models=start(false,8,1,true);spendModel(models);rejected(end(models,"completed"));assertEquals(1,store.get(models.id,false).getJSONObject("executionScope").getInt("modelCallsUsed"));
        Original expired=start();end(expired,"completed");JSONObject expiredRow=storedTask(expired.id);
        // Persist the same native scope after its deadline has elapsed; no alternate clock or admission stub.
        expiredRow.getJSONObject("executionScope").put("lifetime","deadline").put("deadlineAt",System.currentTimeMillis()-1);replaceStoredTask(expiredRow);
        JSONObject reviewed=store.get(expired.id,false);assertTrue(TaskExecutionScope.effectiveDeadline(reviewed.getJSONObject("executionScope"))<System.currentTimeMillis());rejected(reviewed);
    }

    @Test public void spentOriginalStepCeilingCannotGrowFromUnusedToolQuota()throws Exception {
        Original first=start();first.journal.checkpoint(history(),8,new JSONObject());JSONObject reviewed=end(first,"completed");
        assertEquals(0,reviewed.getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(8,grant(first.run).getInt("stepCeiling"));rejected(reviewed);
    }

    @Test public void missingConversationAndDedicatedRoutingRefuseWithoutReplacingIdentity()throws Exception {
        Original missing=start();JSONObject reviewed=end(missing,"completed");
        store.conversationRepository().transaction(()->{store.conversationRepository().deleteConversation(missing.conversation,"fixture_owner_deleted");return null;});rejected(reviewed);
        for(String key:new String[]{"teamReservationPool","teamProjectRoundOrigin","sourceLearningJob","ownerLocalTerminal","workProfileRef","workProfileIntent"}){
            Original first=start();end(first,"completed");JSONObject row=storedTask(first.id);row.put(key,new JSONObject().put("fixtureDedicatedSource",true));replaceStoredTask(row);rejected(store.get(first.id,false));
        }
    }

    @Test public void feedbackAndControlMustBeExplicitAndCannotSendConversationDrafts()throws Exception {
        Original first=start();JSONObject reviewed=end(first,"completed");String before=state(first.id);
        for(String feedback:new String[]{"","  \n\t ","x".repeat(8001)})assertThrows(IllegalArgumentException.class,()->store.startOriginalDevelopmentAttempt(reviewed,40,control(),feedback,()->true));
        RunControl cancelled=control();cancelled.cancel();assertThrows(Exception.class,()->reserve(reviewed,cancelled));
        assertThrows(SecurityException.class,()->reserve(reviewed,first.control));assertThrows(SecurityException.class,()->reserve(reviewed,null));assertEquals(before,state(first.id));
        JSONObject next=reserve(reviewed,control());assertEquals("Continue only the reviewed original project",next.getString("feedback"));
        try(Cursor row=sql.rawQuery("SELECT count(*) FROM assistant_messages WHERE conversation_id=?",new String[]{first.conversation})){assertTrue(row.moveToFirst());assertEquals(0,row.getInt(0));}
    }

    @Test public void pickupUsesCapturedScopeAndOriginalControlRatherThanANewerReview()throws Exception {
        Original first=start();RunControl nextControl=control();JSONObject reserved=reserve(end(first,"completed"),nextControl);
        assertThrows(SecurityException.class,()->store.requireDevelopmentContinuationAttempt(reserved,control()));
        JSONObject scope=store.get(first.id,false).getJSONObject("executionScope");store.configureExecutionScope(first.id,scope.getLong("revision"),0,scope.getInt("toolLimit"),scope.getInt("modelCallLimit"));
        String changed=state(first.id);assertThrows(SecurityException.class,()->store.requireDevelopmentContinuationAttempt(reserved,nextControl));assertEquals(changed,state(first.id));
        Original cancelled=start();RunControl stopped=control();JSONObject reservedCancelled=reserve(end(cancelled,"completed"),stopped);stopped.cancel();assertThrows(Exception.class,()->store.requireDevelopmentContinuationAttempt(reservedCancelled,stopped));
        Original ended=start();RunControl endedControl=control();JSONObject reservedEnded=reserve(end(ended,"completed"),endedControl);store.endAttempt(ended.id,reservedEnded.getString("runId"),"completed","Successor already ended",new JSONObject());
        assertThrows(SecurityException.class,()->store.requireDevelopmentContinuationAttempt(reservedEnded,endedControl));
    }

    @Test public void chargedUnconfirmedReadSurvivesContinuationWithoutARecreatedClaim()throws Exception {
        Original first=start(true,8,8,true);ExecutionPlanning planning=first.journal.planning();JSONObject args=new JSONObject().put("query","source");
        ExecutionPlanning.ReadClaim read=planning.beginRead(planning.capture(),"memory_search",args,"native-read",ExecutionPlanning.Access.READ);
        planning.finishRead(read,new JSONObject().put("ok",false).put("code","original_read_unconfirmed"),ExecutionPlanning.ReadOutcome.UNCONFIRMED);
        JSONObject reviewed=end(first,"awaiting_user");assertFalse(store.hasUnresolved(first.id));RunControl nextControl=control();JSONObject next=reserve(reviewed,nextControl);store.requireDevelopmentContinuationAttempt(next,nextControl);
        JSONObject after=store.get(first.id,false);assertEquals(1,after.getJSONObject("executionScope").getInt("toolsUsed"));assertEquals("planning",after.getJSONObject("executionScope").getString("executionPhase"));
        assertEquals("read_unconfirmed",after.getJSONArray("operations").getJSONObject(0).getString("state"));assertEquals(read.operationId,after.getJSONArray("operations").getJSONObject(0).getString("operationId"));
        assertThrows(Exception.class,()->planning.requireReadDispatch("memory_search",args,first.control));
        ExecutionPlanning successor=store.journal(first.id,next.getString("runId"),next.getInt("coordinatorEpoch"),nextControl).planning();
        assertThrows(SecurityException.class,()->successor.requireReadDispatch("memory_search",args,nextControl));assertEquals(1,count("operations",first.id));
    }

    @Test public void coldPendingReadUsesOriginalRecoveryAndDoesNotRefundOrReplay()throws Exception {
        Original first=start(true,8,8,true);ExecutionPlanning planning=first.journal.planning();
        ExecutionPlanning.ReadClaim read=planning.beginRead(planning.capture(),"memory_search",new JSONObject().put("query","cold source"),"native-cold-read",ExecutionPlanning.Access.READ);
        end(first,"awaiting_user");for(RunControl control:controls)control.cancel();ready.set(false);store.closeForMaintenance();open();
        JSONObject reviewed=store.get(first.id,false);assertEquals("read_unconfirmed",reviewed.getJSONArray("operations").getJSONObject(0).getString("state"));assertEquals(1,reviewed.getJSONObject("executionScope").getInt("toolsUsed"));
        RunControl nextControl=control();JSONObject next=reserve(reviewed,nextControl);store.requireDevelopmentContinuationAttempt(next,nextControl);
        JSONObject kept=store.get(first.id,false).getJSONArray("operations").getJSONObject(0);assertEquals(read.operationId,kept.getString("operationId"));assertEquals("read_unconfirmed",kept.getString("state"));assertEquals(1,count("operations",first.id));
    }

    @Test public void oldCapabilityEntryStillRejectsCompletedTasks()throws Exception {
        Original first=start();JSONObject reviewed=end(first,"completed");String before=state(first.id);
        assertThrows(SecurityException.class,()->store.startOriginalCapabilityAttempt(first.id,first.run,first.epoch,40,control(),"Old entry must stay unchanged"));
        assertEquals(before,state(first.id));reserve(reviewed,control());
    }
}
