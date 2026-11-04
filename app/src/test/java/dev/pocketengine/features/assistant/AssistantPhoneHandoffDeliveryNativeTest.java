package dev.pocketengine.features.assistant;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real TaskStore review/result/outbox + Assistant/SQLite publication. Phone work and
 * the bounded busy-conversation executor are explicit native fixtures, not device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AssistantPhoneHandoffDeliveryNativeTest {
    private record Result(String task,String run,int epoch){}
    private static final class Fixture implements AutoCloseable {
        final ConversationPhoneHandoffNativeTest.Rig original=new ConversationPhoneHandoffNativeTest.Rig();
        final List<JSONObject> events=new CopyOnWriteArrayList<>();final FeatureContext context;
        final AssistantModule module;
        volatile Runnable idleObserver=()->{};
        Fixture()throws Exception{this((conversation,goal,history,model,control)->new JSONObject().put("content","已完成本次普通对话回复"));}
        Fixture(AssistantModule.TurnExecutor executor)throws Exception{
            original.android.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:12345/v1").putString("provider","openai_chat").putString("model","fixture").putBoolean("sendScreenshots",false).commit();
            context=new FeatureContext(original.android,new SettingsStore(original.android),new SecretVault(original.android),event->{events.add(event);JSONObject payload=event.optJSONObject("payload");if(payload!=null&&"idle".equals(payload.optString("phase")))idleObserver.run();});
            module=new AssistantModule(context,null,executor,original.tasks);
        }
        Result result(String status,String summary)throws Exception{
            JSONObject source=module.preparePhoneHandoffSource(original.conversation);
            JSONObject facts=new JSONObject().put("modelName","fixture").put("provider","openai").put("toolLimit",4).put("modelCallLimit",12).put("scopeMinutes",30).put("modelSelectionRevision",1L);
            var review=original.tasks.prepareConversationPhone(source,"明确审阅的原手机目标","background",facts);
            JSONObject created=original.tasks.createReviewedConversationPhone(review,id->{});String task=created.getString("taskId");original.tasks.initializeExecutionScope(task,4);
            JSONObject running=original.tasks.startAttempt(task,"background",4);String run=running.getString("runId");int epoch=running.getInt("coordinatorEpoch");
            original.tasks.endAttempt(task,run,status,summary,new JSONObject().put("fixture",true).put("rawToolBody","RAW-TOOL-BODY-MUST-STAY-IN-ORIGINAL-TASK"));return new Result(task,run,epoch);
        }
        JSONObject append(Result result)throws Exception{return module.appendPhoneHandoffResult(result.task,result.run,result.epoch);}
        JSONObject query(Result result)throws Exception{return module.queryPhoneHandoffResult(original.conversation,result.task,result.run,result.epoch);}
        JSONObject message(JSONObject ack)throws Exception{return original.repo.message(original.conversation,ack.getString("messageId"));}
        long events(String phase){return events.stream().filter(event->event.optJSONObject("payload")!=null&&phase.equals(event.optJSONObject("payload").optString("phase"))).count();}
        public void close(){module.close();original.close();}
    }
    private static void close(TaskStore tasks)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);if(db.isOpen())db.close();}
    private static RunControl retainOriginalConversation(Fixture f,boolean failFirstCancellation)throws Exception{
        AtomicReference<RunControl> original=new AtomicReference<>();AtomicBoolean failCancel=new AtomicBoolean(failFirstCancellation);CountDownLatch idle=new CountDownLatch(1);f.idleObserver=idle::countDown;
        f.module.setNativeInputHost(new AssistantModule.NativeInputHost(){
            public JSONObject start(String cid,String goal,JSONObject message,ModelConfig config,RunControl control)throws Exception{
                String id=f.original.tasks.createCapabilityTask(goal,cid).getString("id");JSONObject run=f.original.tasks.startAttempt(id,"capabilities",config.maxSteps);control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));original.set(control);
                return new JSONObject().put("taskId",id).put("runId",run.getString("runId")).put("epoch",run.getInt("coordinatorEpoch"));
            }
            public JSONObject append(String cid,JSONObject message,String mode,JSONObject expected,RunControl control){throw new AssertionError("release must not resume a retained run");}
            public boolean retainsDevelopment(RunControl control){return original.get()==control&&!control.isCancelled();}
            public void cancel(RunControl control){control.cancel();if(failCancel.getAndSet(false))throw new IllegalStateException("fixture original stop is not yet confirmed");}
        });
        f.module.handle("send",new JSONObject().put("conversationId",f.original.conversation).put("message","保留原开发执行等待资源释放").put("clientMessageId",java.util.UUID.randomUUID().toString()));
        assertTrue(idle.await(3,TimeUnit.SECONDS));assertNotNull(original.get());assertFalse(f.module.snapshot().getBoolean("running"));assertEquals(1,f.module.snapshot().getJSONArray("retainedTurns").length());return original.get();
    }
    private static void assertIdleEventOutsideAdmission(Fixture f)throws Exception{
        var field=AssistantModule.class.getDeclaredField("admission");field.setAccessible(true);Object admission=field.get(f.module);f.idleObserver=()->assertFalse("idle callbacks must leave assistant admission",Thread.holdsLock(admission));
    }

    @Test public void idleOriginalConversationCanPrepareWithoutAnAssistantRun()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject source=f.module.preparePhoneHandoffSource(f.original.conversation);assertEquals(f.original.user,source.getString("sourceMessageId"));assertFalse(f.module.snapshot().getBoolean("running"));assertEquals(0,f.original.tasks.list().length());
            assertThrows(IllegalArgumentException.class,()->f.module.handle("preparePhoneHandoffSource",new JSONObject().put("conversationId",f.original.conversation)));
        }
    }
    @Test public void realResultIsIdempotentAfterReopeningTheOriginalDatabaseWithoutAnotherPhoneRun()throws Exception{
        try(Fixture f=new Fixture()){
            Result result=f.result("needs_confirmation","真实操作已结束，仍需主人核对");JSONObject saved=f.append(result);assertTrue(saved.getBoolean("accepted"));assertFalse(saved.getBoolean("duplicate"));
            JSONObject message=f.message(saved);assertEquals(result.task,message.getString("taskId"));assertEquals(result.run,message.getString("nativeRunId"));assertEquals(result.epoch,message.getInt("nativeEpoch"));assertFalse(message.getBoolean("resultVerified"));assertFalse(message.getBoolean("executionAuthorized"));assertEquals("needs_confirmation",message.getString("status"));
            assertFalse(message.toString().contains("RAW-TOOL-BODY-MUST-STAY-IN-ORIGINAL-TASK"));assertEquals(result.task,message.getJSONObject("taskResultEvidence").getString("taskId"));
            assertEquals(1,f.events("phone_handoff_result"));assertEquals(0,f.events("idle"));assertTrue(f.append(result).getBoolean("duplicate"));assertTrue(f.query(result).getBoolean("found"));assertEquals(1,f.events("phone_handoff_result"));
            f.module.close();f.original.close();TaskStore reopened=new TaskStore(f.original.android);AssistantModule after=new AssistantModule(f.context,null,null,reopened);
            try{
                JSONObject duplicate=after.appendPhoneHandoffResult(result.task,result.run,result.epoch);assertTrue(duplicate.getBoolean("duplicate"));assertEquals(saved.getString("messageId"),duplicate.getString("messageId"));
                assertEquals(1,reopened.list().length());JSONObject task=reopened.get(result.task,false);assertEquals(result.run,task.getString("runId"));assertEquals(result.epoch,task.getInt("coordinatorEpoch"));
                assertEquals(2,after.readOriginalConversation(f.original.conversation,"",80).getJSONArray("messages").length());assertEquals(1,f.events("phone_handoff_result"));
            }finally{after.close();close(reopened);}
        }
    }
    @Test public void everyFrozenOutcomeKeepsItsActualStatusAndNeverClaimsVerification()throws Exception{
        for(String status:List.of("completed","needs_confirmation","awaiting_user","failed","cancelled","interrupted","reconciling","paused"))try(Fixture f=new Fixture()){
            Result result=f.result(status,"原结束回执："+status);JSONObject message=f.message(f.append(result));
            assertEquals(status,message.getString("status"));assertEquals(status,message.getString("nativeStatus"));assertEquals("原结束回执："+status,message.getString("content"));assertFalse(message.getBoolean("resultVerified"));
            JSONObject source=f.original.repo.source(message.getString("id"));assertEquals(message.getString("resultDigest"),source.getString("nativeReplyDigest"));assertEquals(status,source.getString("nativeStatus"));
        }
    }
    @Test public void emptyAndLongSummariesKeepExplicitDisplayMetadataWithoutBlockingDelivery()throws Exception{
        for(String summary:new String[]{"", " ", "🙂".repeat(13000)})try(Fixture f=new Fixture()){
            Result result=f.result("failed",summary);JSONObject message=f.message(f.append(result));
            assertFalse(message.getString("content").isBlank());assertTrue(message.getString("content").length()<=24000);assertEquals("failed",message.getString("status"));assertFalse(message.getBoolean("resultVerified"));
            assertEquals(summary.length(),message.getInt("summaryChars"));assertEquals(summary.length()>24000,message.getBoolean("summaryTruncated"));assertTrue(message.getString("summaryDigest").matches("[a-f0-9]{64}"));
            assertTrue(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(message.getString("content")));assertTrue(message.getString("content").contains("原手机任务"));assertTrue(f.append(result).getBoolean("duplicate"));
        }
    }
    @Test public void laterSourceEditOrDeletionDoesNotSuppressAnAlreadyRecordedPhoneOutcome()throws Exception{
        for(boolean deleted:new boolean[]{false,true})try(Fixture f=new Fixture()){
            Result result=f.result("failed","实际未完成，原失败回执保留");JSONObject change=new JSONObject().put("conversationId",f.original.conversation).put("messageId",f.original.user).put("revision",1L);
            if(deleted)f.module.handle("deleteMessage",change);else f.module.handle("editMessage",change.put("content","后来改写的主人消息"));
            JSONObject ack=f.append(result);assertTrue(ack.getBoolean("accepted"));assertEquals("failed",f.message(ack).getString("status"));assertEquals(1,f.original.tasks.list().length());
        }
    }
    @Test public void deletedResultIsDeduplicatedAndNeverResurrected()throws Exception{
        try(Fixture f=new Fixture()){
            Result result=f.result("cancelled","主人已停止原任务");JSONObject first=f.append(result);
            f.module.handle("deleteMessage",new JSONObject().put("conversationId",f.original.conversation).put("messageId",first.getString("messageId")).put("revision",1L));
            JSONObject replay=f.append(result);assertTrue(replay.getBoolean("accepted"));assertTrue(replay.getBoolean("duplicate"));assertFalse(replay.getBoolean("available"));
            JSONObject query=f.query(result);assertTrue(query.getBoolean("found"));assertFalse(query.getBoolean("available"));assertFalse(query.getBoolean("bodyIncluded"));assertNull(f.original.repo.message(f.original.conversation,first.getString("messageId")));assertEquals(1,f.events("phone_handoff_result"));
        }
    }
    @Test public void wholeConversationDeletionPreventsFirstDeliveryWithoutCreatingAReplacement()throws Exception{
        try(Fixture f=new Fixture()){
            Result result=f.result("interrupted","原执行已中断，未自动重跑");f.module.handle("delete",new JSONObject().put("conversationId",f.original.conversation));
            JSONObject ack=f.append(result);assertFalse(ack.getBoolean("accepted"));assertTrue(ack.getBoolean("conversationDeleted"));assertFalse(f.original.repo.exists(f.original.conversation));assertFalse(f.query(result).getBoolean("found"));assertEquals(0,f.events("phone_handoff_result"));assertEquals(1,f.original.tasks.list().length());
        }
    }
    @Test public void busyOriginalConversationDefersNewResultButRecognizesAnExistingDelivery()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        try(Fixture f=new Fixture((conversation,goal,history,model,control)->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("busy conversation gate timed out");return new JSONObject().put("content","原对话正常结束");})){
            Result already=f.result("needs_confirmation","已有结果"),pending=f.result("failed","待回投的原失败结果");JSONObject first=f.append(already);
            Future<JSONObject> active=worker.submit(()->f.module.handle("send",new JSONObject().put("conversationId",f.original.conversation).put("message","这次正常聊天暂未结束")));
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertThrows(IllegalStateException.class,()->f.module.preparePhoneHandoffSource(f.original.conversation));
            JSONObject waiting=f.append(pending);assertFalse(waiting.getBoolean("accepted"));assertTrue(waiting.getBoolean("waitingForOriginalConversation"));assertFalse(f.query(pending).getBoolean("found"));
            JSONObject duplicate=f.append(already);assertTrue(duplicate.getBoolean("duplicate"));assertEquals(first.getString("messageId"),duplicate.getString("messageId"));
            release.countDown();active.get(3,TimeUnit.SECONDS);JSONObject delivered=f.append(pending);assertTrue(delivered.getBoolean("accepted"));assertFalse(delivered.getBoolean("duplicate"));assertEquals("failed",f.message(delivered).getString("status"));assertEquals(2,f.original.tasks.list().length());
        }finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test public void retainedDevelopmentReleaseWakesWaitingDeliveryOnceOutsideAdmission()throws Exception{
        try(Fixture f=new Fixture((cid,goal,history,model,control)->new JSONObject().put("content","开发执行已让出聊天位置，原资源仍保留").put("developmentRetained",true))){
            Result result=f.result("needs_confirmation","等待原对话可写的真实结果");RunControl original=retainOriginalConversation(f,false);assertTrue(f.append(result).getBoolean("waitingForOriginalConversation"));long before=f.events("idle");assertIdleEventOutsideAdmission(f);
            f.module.releaseDevelopmentTurn(original);assertEquals(before+1,f.events("idle"));f.module.releaseDevelopmentTurn(original);assertEquals(before+1,f.events("idle"));assertTrue(f.append(result).getBoolean("accepted"));assertEquals(1,f.events("phone_handoff_result"));
        }
    }
    @Test public void confirmedPendingCancellationWakesOnlyAfterOriginalBusyGateIsRemoved()throws Exception{
        try(Fixture f=new Fixture((cid,goal,history,model,control)->new JSONObject().put("content","开发执行已让出聊天位置，原资源仍保留").put("developmentRetained",true))){
            Result result=f.result("cancelled","已停止的手机任务原回执");retainOriginalConversation(f,true);long before=f.events("idle");assertIdleEventOutsideAdmission(f);
            JSONObject cancel=new JSONObject().put("conversationId",f.original.conversation);assertThrows(IllegalStateException.class,()->f.module.handle("cancel",cancel));assertEquals(before,f.events("idle"));assertTrue(f.append(result).getBoolean("waitingForOriginalConversation"));
            assertTrue(f.module.handle("cancel",cancel).getBoolean("cancellationRequested"));assertEquals(before+1,f.events("idle"));assertTrue(f.append(result).getBoolean("accepted"));assertFalse(f.module.handle("cancel",cancel).getBoolean("cancellationRequested"));assertEquals(before+1,f.events("idle"));
        }
    }
    @Test public void failedMessageInsertRollsBackUniqueIndexAndCanRetryOnlyDelivery()throws Exception{
        try(Fixture f=new Fixture()){
            Result result=f.result("failed","同一个真实失败结果");f.original.db.execSQL("CREATE TRIGGER fixture_abort_phone_result BEFORE INSERT ON assistant_messages WHEN instr(NEW.meta_json,'phone_handoff:')>0 BEGIN SELECT RAISE(ABORT,'phone_result_fixture');END");
            assertThrows(Exception.class,()->f.append(result));assertFalse(f.query(result).getBoolean("found"));f.original.db.execSQL("DROP TRIGGER fixture_abort_phone_result");
            JSONObject ack=f.append(result);assertTrue(ack.getBoolean("accepted"));assertFalse(ack.getBoolean("duplicate"));assertEquals(1,f.original.tasks.list().length());assertEquals(2,f.module.readOriginalConversation(f.original.conversation,"",80).getJSONArray("messages").length());
        }
    }
    @Test public void postCommitSourceSyncFailureDoesNotRepublishTheAlreadySavedResult()throws Exception{
        try(Fixture f=new Fixture()){
            Result result=f.result("needs_confirmation","已保存的真实结果");AtomicBoolean fail=new AtomicBoolean(true);f.module.setSourceSynchronizer(()->{if(fail.getAndSet(false))throw new IllegalStateException("fixture source sync unavailable");});
            assertThrows(IllegalStateException.class,()->f.append(result));assertEquals(1,f.events("phone_handoff_result"));JSONObject query=f.query(result);assertTrue(query.getBoolean("found"));JSONObject replay=f.append(result);assertTrue(replay.getBoolean("duplicate"));assertEquals(query.getString("messageId"),replay.getString("messageId"));assertEquals(2,f.module.readOriginalConversation(f.original.conversation,"",80).getJSONArray("messages").length());assertEquals(1,f.original.tasks.list().length());assertEquals(1,f.events("phone_handoff_result"));
        }
    }
}
