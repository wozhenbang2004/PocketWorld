package dev.pocketengine.features.assistant;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Original SQLite, real source/message/submission rows, native operations and controls.
 * No second scheduler, mocked persistence, borrowed grant or source-identity replacement. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class OwnerWorkNativeTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject args()throws Exception{return new JSONObject().put("goal","研究原目标并给出证据").put("toolLimit",8).put("modelCallLimit",10);}
    private static final class Fixture implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final ConversationRepository repo;final String conversation=id(),task,run,messageId;final int epoch;
        final RunControl control=new RunControl();final RunJournal journal;final JSONArray history=new JSONArray();final java.util.List<TaskStore> reopened=new java.util.ArrayList<>();
        Fixture()throws Exception {
            android.deleteDatabase(TaskStore.DATABASE_NAME);tasks=new TaskStore(android);repo=tasks.conversationRepository();repo.initializeEmpty();
            repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title","私人管家").put("createdAt",1L).put("updatedAt",1L));return null;});
            String goal="请安排研究，同时让我继续聊天";task=tasks.createCapabilityTask(goal,conversation).getString("id");tasks.initializeExecutionScope(task,32);
            JSONObject attempt=tasks.startAttempt(task,"capabilities",32);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);
            tasks.authorizeTools(task,run,new JSONArray().put("owner_work_start").put("owner_work_input").put("fixture_read").put("teams_plan"));tasks.openOwnerInputMailbox(task,run,epoch,conversation,control);journal=tasks.journal(task,run,epoch,control);
            JSONObject original=saveInput(task,run,epoch,goal,new JSONArray());messageId=original.getString("id");tasks.appendOwnerGoalInput(task,run,epoch,conversation,original,"queue",control);
            history.put(new JSONObject().put("role","system").put("content","native butler")).put(nativeMessage(original));journal.checkpoint(history,0,new JSONObject());
        }
        JSONObject saveInput(String target,String targetRun,int targetEpoch,String text,JSONArray attachments)throws Exception {
            String message=id(),client=id(),fingerprint="a".repeat(64);JSONObject ack=new JSONObject().put("status","accepted").put("taskId",target).put("runId",targetRun).put("epoch",targetEpoch).put("conversationId",conversation).put("messageId",message).put("clientMessageId",client);
            JSONObject row=new JSONObject().put("id",message).put("revision",1L).put("role","user").put("content",text).put("at",System.currentTimeMillis()).put("conversationId",conversation)
                    .put("clientMessageId",client).put("requestFingerprint",fingerprint).put("inputMode","queue").put("attachments",attachments).put("submission",ack);
            repo.transaction(()->{repo.saveSource(new SourceRef("message",message,1L,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false).put("clientMessageId",client).put("requestFingerprint",fingerprint).put("submission",ack));repo.saveMessage(conversation,row);return null;});return row;
        }
        JSONObject nativeMessage(JSONObject row)throws Exception {JSONObject message=new JSONObject().put("role","user").put("content",row.getString("content")).put("ownerMessageId",row.getString("id")).put("ownerMessageRevision",row.getLong("revision")).put("ownerInputMode",row.getString("inputMode"));if(row.getJSONArray("attachments").length()>0)message.put("ownerAttachmentRefs",row.getJSONArray("attachments"));return message;}
        String operation(JSONObject input)throws Exception{return journal.beginOperation("owner_work_start",input);}
        JSONObject admit()throws Exception{JSONObject input=args();return tasks.admitOwnerWork(task,run,epoch,operation(input),input,control);}
        JSONObject follow(String text,JSONArray attachments)throws Exception {JSONObject row=saveInput(task,run,epoch,text,attachments);tasks.appendOwnerInput(task,run,epoch,conversation,row,"queue",control);tasks.pollOwnerInputs(task,run,epoch,new JSONArray().put(messageId),true,false,control);history.put(nativeMessage(row));journal.checkpoint(history,1,new JSONObject());return row;}
        JSONObject claim(String work,RunControl worker)throws Exception{return tasks.claimOwnerWork(work,worker);}
        TaskStore reopen(){TaskStore next=new TaskStore(android);reopened.add(next);return next;}
        SQLiteDatabase database()throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
        public void close()throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);for(TaskStore next:reopened)((SQLiteDatabase)field.get(next)).close();((SQLiteDatabase)field.get(tasks)).close();}
    }
    @Test public void admissionSplitsBudgetExactlyAndForegroundCompletionDoesNotRevokeWork()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject input=args();String operation=f.operation(input);JSONObject admitted=f.tasks.admitOwnerWork(f.task,f.run,f.epoch,operation,input,f.control),duplicate=f.tasks.admitOwnerWork(f.task,f.run,f.epoch,operation,new JSONObject(input.toString()),f.control);
            String work=admitted.getString("taskId");assertTrue(duplicate.getBoolean("duplicate"));assertEquals(work,duplicate.getString("taskId"));assertEquals(2,f.tasks.list().length());
            JSONObject sourceScope=f.tasks.get(f.task,false).getJSONObject("executionScope"),workScope=f.tasks.get(work,false).getJSONObject("executionScope");
            assertEquals(32,sourceScope.getInt("toolLimit")+workScope.getInt("toolLimit"));assertEquals(40,sourceScope.getInt("modelCallLimit")+workScope.getInt("modelCallLimit"));assertEquals(1,sourceScope.getInt("toolsUsed"));assertEquals(0,workScope.getInt("toolsUsed"));
            assertFalse(f.tasks.get(work,false).has("origin"));assertFalse(f.tasks.get(work,false).has("parentTaskId"));
            assertEquals(f.task,f.repo.source(f.messageId).getJSONObject("submission").getString("taskId"));
            f.tasks.endAttempt(f.task,f.run,"completed","工作已安排",new JSONObject());RunControl worker=new RunControl();JSONObject started=f.claim(work,worker);
            assertNotEquals(f.run,started.getString("runId"));assertEquals(1,started.getInt("epoch"));assertTrue(started.getBoolean("claimed"));
            f.tasks.authorizeTools(work,started.getString("runId"),new JSONArray().put("fixture_read").put("owner_work_start"));f.tasks.checkCapabilityCurrent(work,started.getString("runId"),started.getInt("epoch"),"fixture_read");
            assertThrows(SecurityException.class,()->f.tasks.checkCapabilityCurrent(work,started.getString("runId"),started.getInt("epoch"),"owner_work_start"));
            assertFalse(f.claim(work,worker).getBoolean("dispatchAllowed"));assertThrows(SecurityException.class,()->f.claim(work,new RunControl()));
            assertEquals(f.task,f.tasks.ownerTokenBudgetState(work,new JSONArray()).getJSONObject("task").getString("ownerTaskId"));
            assertThrows(SecurityException.class,()->f.tasks.setTaskTokenBudgetForOwner(work,0,true,100000));
        }
    }
    @Test public void changedOperationAndOversizedAllocationCannotCreateWorkOrRefillParent()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject input=args();String operation=f.operation(input);JSONObject accepted=f.tasks.admitOwnerWork(f.task,f.run,f.epoch,operation,input,f.control);
            assertThrows(SecurityException.class,()->f.tasks.admitOwnerWork(f.task,f.run,f.epoch,operation,new JSONObject(input.toString()).put("goal","替换目标"),f.control));
            JSONObject impossible=args().put("toolLimit",24);String rejected=f.operation(impossible);int before=f.tasks.get(f.task,false).getJSONObject("executionScope").getInt("toolLimit");
            assertThrows(SecurityException.class,()->f.tasks.admitOwnerWork(f.task,f.run,f.epoch,rejected,impossible,f.control));assertEquals(before,f.tasks.get(f.task,false).getJSONObject("executionScope").getInt("toolLimit"));assertEquals(1,f.tasks.ownerWorks(f.conversation).length());
            assertThrows(SecurityException.class,()->f.tasks.startAttempt(accepted.getString("taskId"),"capabilities",40));
        }
    }
    @Test public void workRetainsActualTeamParentAuthorityAfterForegroundEnds()throws Exception {
        try(Fixture f=new Fixture()){
            String work=f.admit().getString("taskId");f.tasks.endAttempt(f.task,f.run,"completed","已把工作交给独立任务",new JSONObject());RunControl worker=new RunControl();JSONObject started=f.claim(work,worker);String run=started.getString("runId");int epoch=started.getInt("epoch");
            f.tasks.authorizeTools(work,run,new JSONArray().put("teams_plan"));assertTrue(f.tasks.teamParentEligible(work,run,epoch));assertTrue(f.tasks.teamParentExecutionCurrent(work,run,epoch));
            JSONObject plan=new JSONObject().put("goal","在独立工作中安排原团队研究");String operation=f.tasks.journal(work,run,epoch,worker).beginOperation("teams_plan",plan);
            TaskStore.TeamParentOrigin origin=f.tasks.teamParentOrigin(work,run,epoch,"teams_plan",operation,plan,worker);origin.checkCurrent();assertEquals(work,origin.view().getString("parentTaskId"));assertEquals(run,origin.view().getString("parentRunId"));
            assertFalse(f.tasks.get(work,false).has("parentTaskId"));f.tasks.requestOwnerWorkCancel(work,run,epoch);assertThrows(SecurityException.class,origin::checkCurrent);
        }
    }
    @Test public void originalSourcesSupportScheduleAndOwnerQuestionWithoutForgedWorkGoalInput()throws Exception {
        try(Fixture f=new Fixture()){
            String work=f.admit().getString("taskId");f.tasks.endAttempt(f.task,f.run,"completed","已安排",new JSONObject());RunControl worker=new RunControl();JSONObject started=f.claim(work,worker);String run=started.getString("runId");int epoch=started.getInt("epoch");
            JSONArray initial=f.tasks.ownerWorkInitialMessages(work);f.tasks.journal(work,run,epoch,worker).checkpoint(initial,0,new JSONObject());assertEquals(0,f.tasks.get(work,false).getJSONArray("ownerInputs").length());
            JSONArray refs=f.tasks.scheduleProposalUserRefs(work,run,epoch,f.conversation);assertEquals(1,refs.length());assertEquals(f.messageId,refs.getJSONObject(0).getString("sourceId"));
            assertTrue(f.tasks.withScheduleProposalSourceCurrent(work,run,epoch,f.conversation,refs,()->true));
            String request=id();JSONObject question=new JSONObject().put("format","pocketworld.owner-question.v1").put("taskId",work).put("runId",run).put("coordinatorEpoch",epoch).put("requestId",request).put("waitRevision",2L).put("question","请选一个原目标范围").put("replyKind","answer").put("approval",false).put("takeover",false);
            f.tasks.endAttempt(work,run,"awaiting_user","请选一个原目标范围",new JSONObject().put("ownerQuestion",question));var notice=f.tasks.ownerTaskNotice(work);assertTrue(f.tasks.beginOwnerTaskNoticePosting(notice));f.tasks.ownerTaskNoticePhase(notice,"posted");
            var waiting=f.tasks.retainOwnerQuestionOriginal(new OwnerQuestionInputs(),work,worker,new dev.pocketengine.core.ModelConfig("http://127.0.0.1:1","","fixture",false,8,10));assertNotNull(waiting);
            JSONObject answer=f.tasks.consumeOwnerQuestionReply(work,run,epoch,request,2L,notice.view().getString("token"),"只研究第一个范围");assertTrue(answer.getBoolean("accepted"));assertEquals(work,f.repo.source(answer.getString("messageId")).getJSONObject("submission").getString("taskId"));assertEquals(f.task,f.repo.source(f.messageId).getJSONObject("submission").getString("taskId"));
            JSONObject resumed=f.tasks.resumeOwnerQuestionRun(waiting);assertEquals(run,resumed.getString("runId"));assertEquals(epoch,resumed.getInt("coordinatorEpoch"));f.tasks.endAttempt(work,run,"completed","已按选择完成",new JSONObject());
            JSONArray deliveries=f.tasks.pendingOwnerWorkDeliveries(8);assertEquals(2,deliveries.length());assertNotEquals(deliveries.getJSONObject(0).getString("deliveryId"),deliveries.getJSONObject(1).getString("deliveryId"));assertEquals(run,deliveries.getJSONObject(1).getString("runId"));
            f.tasks.endAttempt(work,run,"completed","已按选择完成",new JSONObject());assertEquals(2,f.tasks.pendingOwnerWorkDeliveries(8).length());
        }
    }
    @Test public void coldQueuedAdmissionSurvivesButClaimedRunNeedsExactExplicitContinuation()throws Exception {
        try(Fixture f=new Fixture()){
            String work=f.admit().getString("taskId");TaskStore cold=f.reopen();assertEquals(1,cold.pendingOwnerWorkDispatches(8).length());RunControl first=new RunControl();JSONObject started=cold.claimOwnerWork(work,first);
            TaskStore restarted=f.reopen();assertEquals(0,restarted.pendingOwnerWorkDispatches(8).length());assertEquals("interrupted",restarted.ownerWork(work,f.conversation).getString("status"));assertEquals(1,restarted.pendingOwnerWorkDeliveries(8).length());
            assertThrows(SecurityException.class,()->restarted.claimOwnerWork(work,new RunControl()));assertThrows(SecurityException.class,()->restarted.continueOwnerWork(work,started.getString("runId"),99,new RunControl()));
            JSONObject resumed=restarted.continueOwnerWork(work,started.getString("runId"),started.getInt("epoch"),new RunControl());assertEquals(2,resumed.getInt("epoch"));assertEquals(8,resumed.getJSONObject("executionScope").getInt("toolLimit"));assertEquals(0,resumed.getJSONObject("executionScope").getInt("toolsUsed"));
            assertThrows(SecurityException.class,()->restarted.requestOwnerWorkCancel(work,started.getString("runId"),started.getInt("epoch")));
        }
    }
    @Test public void unclaimedCancellationUsesEmptyRunAndDoesNotTouchForeground()throws Exception {
        try(Fixture f=new Fixture()){
            String work=f.admit().getString("taskId");assertTrue(f.tasks.ownerWork(work,f.conversation).getJSONObject("controls").getBoolean("canStop"));
            f.tasks.requestOwnerWorkCancel(work,"",0);assertEquals("cancelled",f.tasks.get(work,false).getString("status"));assertFalse(f.control.isCancelled());assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));assertEquals(0,f.tasks.pendingOwnerWorkDispatches(8).length());assertThrows(SecurityException.class,()->f.claim(work,new RunControl()));
        }
    }
    @Test public void taskPushAndLimitedHeadersKeepCurrentWorkIdentityAndControls()throws Exception {
        try(Fixture f=new Fixture()){
            String work=f.admit().getString("taskId");JSONObject queued=f.tasks.getForUi(work,false);
            assertEquals("owner_work",queued.getString("executionRole"));assertEquals(work,queued.getString("taskId"));assertEquals("",queued.getString("runId"));assertEquals(0,queued.getInt("epoch"));
            assertTrue(queued.getJSONObject("controls").getBoolean("canStop"));assertFalse(queued.getJSONObject("controls").getBoolean("canInput"));assertFalse(queued.getJSONObject("ownerWork").has("initialMessages"));
            RunControl control=new RunControl();JSONObject claimed=f.claim(work,control),active=f.tasks.getForUi(work,false);String run=claimed.getString("runId");int epoch=claimed.getInt("epoch");
            assertEquals(run,active.getString("runId"));assertEquals(epoch,active.getInt("epoch"));assertTrue(active.getJSONObject("controls").getBoolean("canInput"));assertFalse(active.getJSONObject("controls").getBoolean("canContinue"));assertFalse(active.getBoolean("taskCoreReadLimited"));
            assertTrue(f.tasks.getForUi(work,true).getJSONObject("controls").getBoolean("canInput"));
            f.tasks.status(work,"paused","原执行停下后保留的长诊断："+"x".repeat(300000));JSONObject limited=f.tasks.getForUi(work,false);
            assertTrue(limited.getBoolean("taskCoreReadLimited"));assertEquals(run,limited.getString("runId"));assertEquals(epoch,limited.getInt("epoch"));assertTrue(limited.getJSONObject("controls").getBoolean("canContinue"));assertTrue(limited.getJSONObject("controls").getBoolean("canStop"));assertFalse(limited.has("ownerWorkControlState"));assertFalse(limited.getJSONObject("ownerWork").has("initialMessages"));
            JSONArray summaries=f.tasks.taskSummaryPage("",10).getJSONArray("tasks");JSONObject summary=null;for(int i=0;i<summaries.length();i++)if(work.equals(summaries.getJSONObject(i).getString("id")))summary=summaries.getJSONObject(i);assertNotNull(summary);assertTrue(summary.getJSONObject("controls").getBoolean("canContinue"));assertFalse(summary.has("ownerWorkControlState"));
            f.repo.transaction(()->{JSONObject source=f.repo.source(f.messageId);source.put("deleted",true);f.repo.saveSource(source);return null;});JSONObject withdrawn=f.tasks.getForUi(work,false);assertEquals("source_unavailable",withdrawn.getString("taskProjection"));assertTrue(withdrawn.getJSONObject("controls").getBoolean("canStop"));assertFalse(withdrawn.getJSONObject("controls").getBoolean("canInput"));assertFalse(withdrawn.getJSONObject("controls").getBoolean("canContinue"));assertFalse(f.tasks.ownerWork(work,f.conversation).getJSONObject("controls").getBoolean("canContinue"));
            f.tasks.requestOwnerWorkCancel(work,run,epoch);JSONObject stopping=f.tasks.getForUi(work,false);assertEquals(run,stopping.getString("runId"));assertEquals(epoch,stopping.getInt("epoch"));for(String key:new String[]{"canStop","canInput","canContinue"})assertFalse(stopping.getJSONObject("controls").getBoolean(key));
            f.tasks.endAttempt(work,run,"cancelled","原工作已停止",new JSONObject());JSONObject ended=f.tasks.getForUi(work,false);assertEquals("cancelled",ended.getString("status"));assertFalse(ended.getJSONObject("ownerWork").has("initialMessages"));assertFalse(ended.getJSONObject("ownerWork").has("nativeFinalAnswerProof"));for(String key:new String[]{"canStop","canInput","canContinue"})assertFalse(ended.getJSONObject("controls").getBoolean(key));
        }
    }
    @Test public void routedActualInputPreservesOriginalSubmissionAndSeparateNativeMailbox()throws Exception {
        try(Fixture f=new Fixture()){
            String work=f.admit().getString("taskId");RunControl worker=new RunControl();JSONObject started=f.claim(work,worker);
            JSONArray attachments=new JSONArray().put(new JSONObject().put("id",id()).put("sha256","b".repeat(64)).put("mimeType","text/plain").put("size",7));JSONObject message=f.follow("刚才研究改成只核对第一项",attachments);String originalAck=f.repo.source(message.getString("id")).getJSONObject("submission").toString();
            JSONObject route=new JSONObject().put("taskId",work).put("runId",started.getString("runId")).put("epoch",started.getInt("epoch")).put("mode","steer");String operation=f.journal.beginOperation("owner_work_input",route);
            JSONObject receipt=f.tasks.routeOwnerWorkInput(f.task,f.run,f.epoch,operation,route,f.control,worker);assertTrue(receipt.getBoolean("accepted"));assertTrue(f.tasks.routeOwnerWorkInput(f.task,f.run,f.epoch,operation,route,f.control,worker).getBoolean("duplicate"));
            JSONArray polled=f.tasks.pollOwnerInputs(work,started.getString("runId"),started.getInt("epoch"),new JSONArray(),false,false,worker);assertEquals(1,polled.length());JSONObject row=polled.getJSONObject(0);
            assertEquals(message.getString("content"),row.getString("content"));assertEquals(attachments.toString(),row.getJSONArray("attachments").toString());assertEquals(f.task,row.getJSONObject("ownerWorkRoute").getJSONObject("sourceInput").getString("acceptedTaskId"));assertEquals(work,row.getString("acceptedTaskId"));assertEquals(originalAck,f.repo.source(message.getString("id")).getJSONObject("submission").toString());
            JSONObject clocks=f.tasks.ownerInputClockContext(work,started.getString("runId"),started.getInt("epoch"),java.util.List.of(f.messageId,message.getString("id")),worker,false);assertEquals(0,clocks.getInt("missingInputCount"));assertEquals(2,clocks.getInt("knownInputCount"));
            assertThrows(SecurityException.class,()->f.tasks.appendOwnerWorkInput(work,started.getString("runId"),started.getInt("epoch"),f.conversation,message,"queue",worker));
        }
    }
    @Test public void originalSourceWithdrawalStopsDispatchWithoutRestoringAllocatedBudget()throws Exception {
        try(Fixture f=new Fixture()){
            String work=f.admit().getString("taskId");JSONObject source=f.repo.source(f.messageId);f.repo.transaction(()->{source.put("deleted",true);f.repo.saveSource(source);return null;});
            assertThrows(Exception.class,()->f.claim(work,new RunControl()));assertEquals(24,f.tasks.get(f.task,false).getJSONObject("executionScope").getInt("toolLimit"));assertEquals(8,f.tasks.get(work,false).getJSONObject("executionScope").getInt("toolLimit"));
            JSONObject hidden=f.tasks.getForUi(work,false);assertEquals("source_unavailable",hidden.getString("taskProjection"));assertFalse(hidden.getBoolean("contextAvailable"));assertTrue(hidden.getJSONObject("controls").getBoolean("canStop"));assertFalse(hidden.toString().contains(args().getString("goal")));assertFalse(hidden.getBoolean("auditBodyIncluded"));
            f.tasks.status(work,"queued","PRIVATE-WITHDRAWN-WORK-SUMMARY"+"x".repeat(300000));JSONObject limited=f.tasks.getForUi(work,false);assertTrue(limited.getBoolean("taskCoreReadLimited"));assertEquals("source_unavailable",limited.getString("taskProjection"));assertTrue(limited.getJSONObject("controls").getBoolean("canStop"));assertFalse(limited.getJSONObject("controls").getBoolean("canInput"));assertFalse(limited.getJSONObject("controls").getBoolean("canContinue"));assertFalse(limited.toString().contains("PRIVATE-WITHDRAWN-WORK-SUMMARY"));assertFalse(limited.toString().contains(args().getString("goal")));
            JSONArray summaries=f.tasks.taskSummaryPage("",10).getJSONArray("tasks");boolean found=false;for(int i=0;i<summaries.length();i++){JSONObject row=summaries.getJSONObject(i);if(!work.equals(row.getString("id")))continue;found=true;assertEquals("source_unavailable",row.getString("taskProjection"));assertFalse(row.toString().contains("PRIVATE-WITHDRAWN-WORK-SUMMARY"));assertFalse(row.toString().contains(args().getString("goal")));}assertTrue(found);
        }
    }
    @Test public void deliveryIsAtomicIdempotentAndDoesNotPretendButlerConsumedIt()throws Exception {
        try(Fixture f=new Fixture()){
            String work=f.admit().getString("taskId");JSONObject started=f.claim(work,new RunControl());String run=started.getString("runId");int epoch=started.getInt("epoch");f.tasks.endAttempt(work,run,"completed","已查到真实结果",new JSONObject());
            String sourceAck=f.repo.source(f.messageId).getJSONObject("submission").toString();AtomicInteger attempts=new AtomicInteger();
            assertThrows(java.io.IOException.class,()->f.tasks.withOwnerWorkDelivery(work,run,epoch,delivery->{f.repo.appendOwnerWorkResult(f.tasks,delivery,false);throw new java.io.IOException("rollback original SQL transaction");}));
            assertEquals(1,f.tasks.pendingOwnerWorkDeliveries(8).length());JSONObject delivered=f.tasks.withOwnerWorkDelivery(work,run,epoch,delivery->{attempts.incrementAndGet();return f.repo.appendOwnerWorkResult(f.tasks,delivery,false);});
            assertTrue(delivered.getBoolean("accepted"));assertEquals("delivered",delivered.getJSONObject("delivery").getString("deliveryState"));assertEquals("pending",delivered.getJSONObject("delivery").getString("receiptState"));assertEquals(0,f.tasks.pendingOwnerWorkDeliveries(8).length());assertEquals(1,f.tasks.pendingOwnerWorkReceipts(f.conversation,8).length());
            assertTrue(f.tasks.withOwnerWorkDelivery(work,run,epoch,delivery->{attempts.incrementAndGet();return f.repo.appendOwnerWorkResult(f.tasks,delivery,false);}).getBoolean("duplicate"));assertEquals(1,attempts.get());
            JSONObject message=f.repo.message(f.conversation,delivered.getString("messageId"));assertEquals(f.messageId,message.getJSONArray("replyToMessageIds").getString(0));assertEquals(run,message.getString("nativeRunId"));assertEquals(sourceAck,f.repo.source(f.messageId).getJSONObject("submission").toString());
            String deliveryId=delivered.getJSONObject("delivery").getString("deliveryId");JSONObject claim=f.tasks.claimOwnerWorkReceipt(work,deliveryId,f.task,f.run,f.epoch,f.control);assertTrue(claim.getBoolean("claimed"));assertEquals(claim.getJSONObject("butlerClaim").toString(),f.tasks.claimOwnerWorkReceipt(work,deliveryId,f.task,f.run,f.epoch,f.control).getJSONObject("butlerClaim").toString());
            JSONObject tombstone=f.repo.source(message.getString("id"));f.repo.transaction(()->{tombstone.put("deleted",true);f.repo.saveSource(tombstone);return null;});assertEquals(0,f.tasks.pendingOwnerWorkReceipts(f.conversation,8).length());
            assertThrows(SecurityException.class,()->f.tasks.claimOwnerWorkReceipt(work,deliveryId,f.task,f.run,f.epoch,f.control));assertFalse(f.tasks.withOwnerWorkDelivery(work,run,epoch,delivery->{throw new AssertionError("Deleted result must not be recreated");}).getBoolean("available"));
        }
    }
    @Test public void nativeResultClaimRequiresTheExactSelectedHostContextPrefix()throws Exception {
        try(Fixture f=new Fixture()){
            String work=f.admit().getString("taskId"),body="原工作结果供管家核对";JSONObject started=f.claim(work,new RunControl());
            String run=started.getString("runId");int epoch=started.getInt("epoch");f.tasks.endAttempt(work,run,"completed",body,new JSONObject());
            JSONObject delivered=f.tasks.withOwnerWorkDelivery(work,run,epoch,delivery->f.repo.appendOwnerWorkResult(f.tasks,delivery,false));
            assertTrue(delivered.getBoolean("accepted"));String deliveryId=delivered.getJSONObject("delivery").getString("deliveryId");
            String sourceAck=f.repo.source(f.messageId).getJSONObject("submission").toString();
            try(FeatureHost host=new FeatureHost(event->{},f.tasks,()->false);
                HostToolSupervisor supervisor=new HostToolSupervisor(host,f.tasks,f.task,f.run,f.epoch,true)){
                supervisor.beginRun("结合原请求核对已完成的工作",f.control);
                String context=supervisor.contextSnapshot("核对工作结果").getString("context");
                assertTrue("the selected native context must include the real result",context.contains(body));
                var producer=dev.pocketengine.runtime.RecoveryProjection.class.getDeclaredMethod("hostReference",String.class);producer.setAccessible(true);
                JSONObject nativeFrame=(JSONObject)producer.invoke(null,context);
                JSONObject[] rejected={
                    new JSONObject().put("role","user").put("content",context),
                    new JSONObject(nativeFrame.toString()).put("role","assistant"),
                    (JSONObject)producer.invoke(null,"改写的参考前缀\n"+context),
                    (JSONObject)producer.invoke(null,context.replace(body,"改写后的工作结果"))
                };
                for(JSONObject frame:rejected){
                    supervisor.beforeModelRequest(id(),new JSONArray().put(frame),f.control);
                    JSONArray pending=f.tasks.pendingOwnerWorkReceipts(f.conversation,8);assertEquals(1,pending.length());
                    JSONObject receipt=pending.getJSONObject(0);assertEquals(deliveryId,receipt.getString("deliveryId"));
                    assertEquals("a non-native or altered frame must not claim the result","pending",receipt.getString("receiptState"));assertFalse(receipt.has("butlerClaim"));
                }
                supervisor.beforeModelRequest(id(),new JSONArray().put(nativeFrame),f.control);
                JSONArray pending=f.tasks.pendingOwnerWorkReceipts(f.conversation,8);assertEquals(1,pending.length());JSONObject receipt=pending.getJSONObject(0);
                assertEquals(deliveryId,receipt.getString("deliveryId"));assertEquals("delivered",receipt.getString("deliveryState"));assertEquals("claimed",receipt.getString("receiptState"));
                JSONObject claim=receipt.getJSONObject("butlerClaim");assertEquals(f.task,claim.getString("taskId"));assertEquals(f.run,claim.getString("runId"));assertEquals(f.epoch,claim.getInt("epoch"));
                assertFalse("preparation is not a physical model response",claim.has("requestId"));assertFalse(claim.has("preparedAt"));
                assertEquals(sourceAck,f.repo.source(f.messageId).getJSONObject("submission").toString());
            }
        }
    }
    @Test public void forgottenFormalDependencyRetiresPendingResultWithoutBlockingNextButler()throws Exception {
        try(Fixture f=new Fixture()){
            String withdrawn=f.admit().getString("taskId"),current=f.admit().getString("taskId");JSONObject old=f.claim(withdrawn,new RunControl()),good=f.claim(current,new RunControl());String oldRun=old.getString("runId"),goodRun=good.getString("runId");int oldEpoch=old.getInt("epoch"),goodEpoch=good.getInt("epoch");
            JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");var memory=new dev.pocketengine.features.memory.MemoryBook(new java.io.File(f.android.getFilesDir(),"owner-work-withdrawal-"+id()+".json"));
            JSONObject used=memory.save(scope,new JSONObject().put("text","只有第一项工作实际准备使用的正式依据"),1L);
            dev.pocketengine.features.memory.OwnerSourceRunContext.formalPrepared(f.database(),withdrawn,oldRun,new JSONArray().put(new JSONObject().put("id",used.getString("id")).put("revision",used.getLong("revision"))));
            f.tasks.endAttempt(withdrawn,oldRun,"completed","WITHDRAWN-FORMAL-RESULT",new JSONObject());f.tasks.endAttempt(current,goodRun,"completed","当前来源仍有效的独立结果",new JSONObject());
            JSONObject waiting=f.tasks.withOwnerWorkDelivery(withdrawn,oldRun,oldEpoch,delivery->f.repo.appendOwnerWorkResult(f.tasks,delivery,true));assertTrue(waiting.getBoolean("waitingForOriginalConversation"));String oldDelivery=waiting.getJSONObject("delivery").getString("deliveryId");assertNull(f.repo.sourceByReply(oldDelivery));assertEquals(2,f.tasks.pendingOwnerWorkReceipts(f.conversation,8).length());
            memory.forgetReviewed(scope,used.getString("id"),used.getLong("revision"),gone->f.tasks.invalidateMemoryContexts(dev.pocketengine.features.memory.MemoryBook.Scope.from(scope),gone));
            assertThrows(dev.pocketengine.features.memory.TaskStoreMemoryReader.RawMessageUnavailable.class,()->dev.pocketengine.features.memory.TaskStoreMemoryQuery.requireOutputContext(f.database(),null,withdrawn,oldRun));
            JSONArray remaining=f.tasks.pendingOwnerWorkReceipts(f.conversation,8);assertEquals(1,remaining.length());assertEquals(current,remaining.getJSONObject(0).getString("taskId"));assertFalse(remaining.toString().contains("WITHDRAWN-FORMAL-RESULT"));assertThrows(SecurityException.class,()->f.tasks.ownerWorkResult(withdrawn,f.conversation,oldDelivery,0,100));
            JSONObject retired=f.tasks.withOwnerWorkDelivery(withdrawn,oldRun,oldEpoch,delivery->{throw new AssertionError("Withdrawn result must not enter the original conversation mutation");});assertFalse(retired.getBoolean("available"));assertFalse(retired.getBoolean("accepted"));assertEquals("unavailable",retired.getString("deliveryState"));assertNull(f.repo.sourceByReply(oldDelivery));
            JSONArray pending=f.tasks.pendingOwnerWorkDeliveries(8);assertEquals(1,pending.length());assertEquals(current,pending.getJSONObject(0).getString("taskId"));String goodDelivery=pending.getJSONObject(0).getString("deliveryId");
            f.tasks.endAttempt(f.task,f.run,"completed","已安排两项独立工作",new JSONObject());String next=f.tasks.createCapabilityTask("继续新的主人请求",f.conversation).getString("id");f.tasks.initializeExecutionScope(next,8);JSONObject attempt=f.tasks.startAttempt(next,"capabilities",8);String nextRun=attempt.getString("runId");int nextEpoch=attempt.getInt("coordinatorEpoch");RunControl nextControl=new RunControl();nextControl.bindHostRun(next,nextRun,nextEpoch);f.tasks.authorizeTools(next,nextRun,new JSONArray().put("fixture_read"));f.tasks.openOwnerInputMailbox(next,nextRun,nextEpoch,f.conversation,nextControl);
            JSONObject input=f.saveInput(next,nextRun,nextEpoch,"继续新的主人请求",new JSONArray());f.tasks.appendOwnerGoalInput(next,nextRun,nextEpoch,f.conversation,input,"queue",nextControl);f.tasks.journal(next,nextRun,nextEpoch,nextControl).checkpoint(new JSONArray().put(new JSONObject().put("role","system").put("content","native butler")).put(f.nativeMessage(input)),0,new JSONObject());
            assertEquals(1,f.tasks.pendingOwnerWorkReceipts(f.conversation,8).length());assertTrue(f.tasks.claimOwnerWorkReceipt(current,goodDelivery,next,nextRun,nextEpoch,nextControl).getBoolean("claimed"));f.tasks.checkCapabilityCurrent(next,nextRun,nextEpoch,"fixture_read");assertEquals(nextRun,f.tasks.get(next,false).getString("runId"));assertEquals("unavailable",f.tasks.get(withdrawn,false).getJSONObject("ownerWork").getJSONArray("deliveries").getJSONObject(0).getString("deliveryState"));
            assertTrue(f.tasks.withOwnerWorkDelivery(current,goodRun,goodEpoch,delivery->f.repo.appendOwnerWorkResult(f.tasks,delivery,false)).getBoolean("accepted"));
        }
    }
    @Test public void actualLaterRunReplyConsumesFreshInputWithoutRebindingItsAcceptedRun()throws Exception {
        try(Fixture f=new Fixture()){
            var server=dev.pocketengine.testutil.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content","已按你的原补充完成工作🙂")).put("finish_reason","stop")))
                    .put("usage",new JSONObject().put("prompt_tokens",10).put("completion_tokens",5).put("total_tokens",15)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
            try{
                String work=f.admit().getString("taskId");RunControl firstControl=new RunControl();JSONObject first=f.claim(work,firstControl);String firstRun=first.getString("runId");int firstEpoch=first.getInt("epoch");
                f.tasks.journal(work,firstRun,firstEpoch,firstControl).checkpoint(f.tasks.ownerWorkInitialMessages(work),0,new JSONObject());
                JSONObject input=f.saveInput(work,firstRun,firstEpoch,"请保留这个补充的真实接收身份",new JSONArray());f.tasks.appendOwnerWorkInput(work,firstRun,firstEpoch,f.conversation,input,"queue",firstControl);
                f.tasks.endAttempt(work,firstRun,"interrupted","补充已保存，等待明确继续",new JSONObject());RunControl resumedControl=new RunControl();JSONObject resumed=f.tasks.continueOwnerWork(work,firstRun,firstEpoch,resumedControl);String nextRun=resumed.getString("runId");int nextEpoch=resumed.getInt("epoch");
                f.tasks.authorizeTools(work,nextRun,new JSONArray());dev.pocketengine.core.CapabilityPort port=new dev.pocketengine.core.CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return true;}public JSONObject execute(String name,JSONObject arguments,RunControl control){throw new AssertionError("No tool effect requested");}};
                dev.pocketengine.runtime.AgentRunner.OwnerInputs inputs=new dev.pocketengine.runtime.AgentRunner.OwnerInputs(){
                    public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl control)throws Exception{return f.tasks.pollOwnerInputs(work,nextRun,nextEpoch,included,boundary,close,control);}
                    public void completed(dev.pocketengine.runtime.AgentRunner.OwnerReply reply,RunControl control)throws Exception{f.tasks.recordOwnerWorkReply(reply,control);}
                };
                dev.pocketengine.core.ModelConfig config=new dev.pocketengine.core.ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","owner-work-native-fixture","fixture",false,8,10,"openai_chat",1000);
                dev.pocketengine.runtime.context.ContextBudgetPolicy.Source budgets=model->dev.pocketengine.runtime.context.ContextBudgetPolicy.RouteBudget.forRoute(model,new dev.pocketengine.runtime.context.ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),dev.pocketengine.runtime.context.ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
                var runner=new dev.pocketengine.runtime.AgentRunner(null,port,budgets,null,inputs,dev.pocketengine.runtime.NativeInstructionProfile.OWNER_WORK);
                var outcome=runner.run(work,resumed.getString("goal"),config,resumedControl,event->{try{f.tasks.event(work,nextRun,nextEpoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}},f.tasks.journal(work,nextRun,nextEpoch,resumedControl));
                assertEquals(outcome.summary,"needs_confirmation",outcome.status);f.tasks.finishCapabilityAttempt(work,nextRun,nextEpoch,resumedControl,outcome);
                JSONObject delivery=f.tasks.withOwnerWorkDelivery(work,nextRun,nextEpoch,value->f.repo.appendOwnerWorkResult(f.tasks,value,false));assertTrue(delivery.getBoolean("accepted"));assertEquals(1,delivery.getJSONArray("consumed").length());
                JSONObject ack=f.repo.source(input.getString("id")).getJSONObject("submission");assertEquals("consumed",ack.getString("status"));assertEquals(work,ack.getString("taskId"));assertEquals(firstRun,ack.getString("runId"));assertEquals(firstEpoch,ack.getInt("epoch"));assertEquals(nextRun,ack.getString("consumedRunId"));assertEquals(nextEpoch,ack.getInt("consumedEpoch"));assertEquals(delivery.getString("messageId"),ack.getString("replyMessageId"));
                JSONObject result=f.tasks.ownerWorkResult(work,f.conversation,"",0,8);assertTrue(result.getBoolean("available"));assertEquals(nextRun,result.getString("runId"));assertEquals(8,result.getInt("chars"));assertTrue(result.getBoolean("hasMore"));
                JSONObject tail=f.tasks.ownerWorkResult(work,f.conversation,result.getString("deliveryId"),result.getInt("nextOffset"),8000);assertFalse(tail.getBoolean("hasMore"));assertEquals("已按你的原补充完成工作🙂",result.getString("content")+tail.getString("content"));
                assertThrows(SecurityException.class,()->f.tasks.ownerWorkResult(work,f.conversation,id(),0,100));String view=f.tasks.ownerWork(work,f.conversation).toString();assertFalse(view.contains("initialMessages"));assertFalse(view.contains("consumableInputs"));assertFalse(view.contains("resultText\":"));assertFalse(view.contains(input.getString("content")));
            }finally{server.stop(0);}
        }
    }
}
