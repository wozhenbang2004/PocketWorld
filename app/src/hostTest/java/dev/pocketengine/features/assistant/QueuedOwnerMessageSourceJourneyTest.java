package dev.pocketengine.features.assistant;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original SQLite TaskStore and Assistant source facade; no model/network/effect dispatch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class QueuedOwnerMessageSourceJourneyTest {
    private static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final TaskStore tasks;final ConversationRepository repo;
        final String conversation=UUID.randomUUID().toString(),messageId=UUID.randomUUID().toString(),client=UUID.randomUUID().toString();
        final String fingerprint="c".repeat(64),originalBody="原接收正文🙂 KEEP-AUDIT";final JSONObject attempt;final RunControl control=new RunControl();
        final FeatureContext context;final AssistantModule assistant;final AtomicInteger synchronizedSources=new AtomicInteger();final SQLiteDatabase db;
        Fixture()throws Exception{
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);repo=tasks.conversationRepository();repo.initializeEmpty();
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
            attempt=tasks.startAttempt(tasks.createCapabilityTask("原输入队列fixture",conversation).getString("id"),"capabilities",16);
            control.bindHostRun(task(),run(),epoch());tasks.openOwnerInputMailbox(task(),run(),epoch(),conversation,control);
            JSONObject ack=new JSONObject().put("status","queued").put("taskId",task()).put("runId",run()).put("epoch",epoch()).put("messageId",messageId)
                    .put("clientMessageId",client).put("conversationId",conversation).put("mode","queue").put("queueRevision",1L).put("ownerMessageRevision",1L).put("queueState","pending");
            JSONObject message=new JSONObject().put("id",messageId).put("revision",1L).put("role","user").put("content",originalBody).put("at",1L)
                    .put("clientMessageId",client).put("requestFingerprint",fingerprint).put("inputMode","queue").put("attachments",new JSONArray()).put("submission",ack);
            repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title","原队列").put("createdAt",1L));
                repo.saveSource(new SourceRef("message",messageId,1L,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false)
                        .put("clientMessageId",client).put("requestFingerprint",fingerprint).put("submission",new JSONObject(ack.toString())));repo.saveMessage(conversation,message);return null;});
            tasks.appendOwnerInput(task(),run(),epoch(),conversation,message,"queue",control);
            assistant=new AssistantModule(context,null,null,new AtomicFile(new File(android.getFilesDir(),"queue-fixture-"+conversation+".json")),tasks);
            assistant.setSourceSynchronizer(()->{assertFalse("source synchronization must be outside Tasks",Thread.holdsLock(tasks));synchronizedSources.incrementAndGet();});
        }
        String task()throws Exception{return attempt.getString("id");}String run()throws Exception{return attempt.getString("runId");}int epoch()throws Exception{return attempt.getInt("coordinatorEpoch");}
        JSONObject nativeMessage()throws Exception{return repo.read(()->repo.message(conversation,messageId));}
        JSONObject source()throws Exception{return repo.read(()->repo.source(messageId));}
        JSONObject row()throws Exception{return tasks.queuedOwnerInput(task(),conversation,messageId);}
        JSONObject edit(String content,String mode)throws Exception{return assistant.mutateQueuedOwnerInput(task(),conversation,messageId,1L,1L,content,mode,false);}
        public void close(){assistant.close();db.close();}
    }
    @Test public void editUsesSameNativeSourceAndPreservesOriginalAdmissionIdentity()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject saved=f.edit("主人新文字🙂","steer"),message=f.nativeMessage(),source=f.source(),ack=source.getJSONObject("submission");
            assertEquals(2L,saved.getLong("queueRevision"));assertEquals(2L,saved.getLong("ownerMessageRevision"));assertEquals("pending",saved.getString("queueState"));
            assertEquals("主人新文字🙂",message.getString("content"));assertEquals(2L,message.getLong("revision"));assertEquals("steer",message.getString("inputMode"));
            assertEquals(f.client,message.getString("clientMessageId"));assertEquals(f.fingerprint,message.getString("requestFingerprint"));assertEquals(f.fingerprint,source.getString("requestFingerprint"));
            assertEquals(f.task(),ack.getString("taskId"));assertEquals(f.run(),ack.getString("runId"));assertEquals(f.epoch(),ack.getInt("epoch"));assertEquals("queued",ack.getString("status"));
            assertEquals(2L,f.repo.resolveMetadata(f.messageId).state.ref.revision);assertEquals(1,f.synchronizedSources.get());
            JSONObject submission=f.assistant.handle("getSubmission",new JSONObject().put("clientMessageId",f.client));assertEquals(2L,submission.getLong("queueRevision"));assertEquals(2L,submission.getLong("ownerMessageRevision"));
        }
    }
    @Test public void withdrawalPreservesAuditBodyAndDoesNotAssertForgetOrConsumption()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject saved=f.assistant.mutateQueuedOwnerInput(f.task(),f.conversation,f.messageId,1L,1L,f.originalBody,"queue",true);
            assertEquals("withdrawn",saved.getString("queueState"));assertFalse(saved.getBoolean("editable"));assertEquals(f.originalBody,f.nativeMessage().getString("content"));
            JSONObject state=f.source(),ack=state.getJSONObject("submission");assertFalse(state.getBoolean("deleted"));assertEquals(2L,state.getLong("memorySourceRetiredThrough"));assertTrue(state.getBoolean("memoryContextRetired"));assertEquals("owner_correction",state.getString("retirementReason"));
            assertThrows(IllegalStateException.class,()->f.assistant.readMemorySource(new SourceRef("message",f.messageId,2L,"owner","private")));
            var gate=f.assistant.openHistoryConversation(f.conversation);JSONArray inspected=f.assistant.inspectHistorySources(gate,java.util.List.of(new SourceRef("message",f.messageId,2L,"owner","private")));
            assertEquals("retired",inspected.getJSONObject(0).getString("status"));assertEquals(f.originalBody,f.nativeMessage().getString("content"));
            assertEquals("withdrawn",ack.getString("status"));assertEquals("withdrawn",ack.getString("queueState"));assertFalse(ack.has("consumedAt"));assertEquals(2L,ack.getLong("ownerMessageRevision"));
            assertEquals("withdrawn",f.assistant.handle("getSubmission",new JSONObject().put("clientMessageId",f.client)).getString("status"));
        }
    }
    @Test public void taskTransactionFailureRollsBackSourceMessageAndQueueTogether()throws Exception{
        try(Fixture f=new Fixture()){
            f.db.execSQL("CREATE TRIGGER fixture_queue_failure BEFORE UPDATE ON tasks WHEN OLD.task_id='"+f.task()+"' BEGIN SELECT RAISE(ABORT,'fixture-original-transaction'); END");
            assertThrows(Exception.class,()->f.edit("不能只写消息的副本","queue"));f.db.execSQL("DROP TRIGGER fixture_queue_failure");
            assertEquals(f.originalBody,f.nativeMessage().getString("content"));assertEquals(1L,f.nativeMessage().getLong("revision"));assertEquals(1L,f.source().getLong("revision"));
            assertEquals(1L,f.row().getLong("queueRevision"));assertEquals(f.originalBody,f.row().getString("content"));assertEquals(0,f.synchronizedSources.get());
        }
    }
    @Test public void pendingNativeMessageCannotBypassQueueCasThroughOrdinaryArchiveEditOrDelete()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject selected=new JSONObject().put("conversationId",f.conversation).put("messageId",f.messageId).put("revision",1L).put("content","旁路不应写入");
            assertThrows(IllegalStateException.class,()->f.assistant.handle("editMessage",selected));assertThrows(IllegalStateException.class,()->f.assistant.handle("deleteMessage",selected));
            assertEquals(f.originalBody,f.nativeMessage().getString("content"));assertEquals("pending",f.row().getString("queueState"));assertEquals(1L,f.source().getLong("revision"));
        }
    }
    @Test public void staleCasAndChangedNativeMessageCannotClaimOrOverwriteAcceptedBody()throws Exception{
        try(Fixture f=new Fixture()){
            assertThrows(IllegalStateException.class,()->f.assistant.mutateQueuedOwnerInput(f.task(),f.conversation,f.messageId,2L,1L,"不覆盖","queue",false));
            f.repo.transaction(()->{JSONObject source=f.repo.source(f.messageId),message=f.repo.message(f.conversation,f.messageId);source.put("revision",2L);message.put("revision",2L).put("content","独立原source变化");f.repo.saveSource(source);f.repo.saveMessage(f.conversation,message);return null;});
            assertThrows(SecurityException.class,()->f.edit("旧版本不能覆盖","queue"));
            f.repo.transaction(()->{assertThrows(SecurityException.class,()->f.repo.requireQueuedOwnerMessageCurrent(f.conversation,f.row().put("acceptedRunId",f.run()).put("acceptedEpoch",f.epoch())));return null;});
            assertEquals("独立原source变化",f.nativeMessage().getString("content"));assertEquals(f.originalBody,f.row().getString("content"));
        }
    }
    @Test public void sameNativeRowsSurviveColdRepositoryAndRequireOriginalTransactionForClaim()throws Exception{
        try(Fixture f=new Fixture()){
            f.edit("冷恢复仍用真实版本","queue");ConversationRepository reopened=new ConversationRepository(f.db,f.tasks);
            assertEquals(2L,reopened.read(()->reopened.message(f.conversation,f.messageId)).getLong("revision"));
            assertThrows(SecurityException.class,()->reopened.requireQueuedOwnerMessageCurrent(f.conversation,f.row()));
            reopened.transaction(()->{JSONObject row=f.row().put("acceptedRunId",f.run()).put("acceptedEpoch",f.epoch());reopened.requireQueuedOwnerMessageCurrent(f.conversation,row);return null;});
            assertEquals("pending",f.row().getString("queueState"));assertFalse(f.source().getJSONObject("submission").has("consumedAt"));
        }
    }
    @Test public void realAcceptedRowCanBeClaimedInTheOriginalAppendBeforeAckCommitWindow()throws Exception{
        try(Fixture f=new Fixture()){
            f.repo.transaction(()->{JSONObject source=f.repo.source(f.messageId);source.put("submission",new JSONObject().put("status","unconfirmed").put("messageId",f.messageId)
                    .put("clientMessageId",f.client).put("conversationId",f.conversation).put("mode","queue").put("reconciliationRequired",true));f.repo.saveSource(source);return null;});
            JSONArray actual=f.tasks.pollOwnerInputs(f.task(),f.run(),f.epoch(),new JSONArray(),true,false,f.control);
            assertEquals(1,actual.length());assertEquals(f.originalBody,actual.getJSONObject(0).getString("content"));assertEquals("claimed",f.row().getString("queueState"));
            assertEquals("unconfirmed",f.source().getJSONObject("submission").getString("status"));assertFalse(f.source().getJSONObject("submission").has("consumedAt"));assertFalse(f.control.isCancelled());
        }
    }

    @Test public void missingAckRecoveryEditsAndWithdrawsOnlyTheSamePendingNativeRow()throws Exception{
        try(Fixture f=new Fixture()){
            f.repo.transaction(()->{JSONObject state=f.repo.source(f.messageId);state.put("submission",new JSONObject().put("status","unconfirmed").put("messageId",f.messageId)
                    .put("clientMessageId",f.client).put("conversationId",f.conversation).put("mode","queue").put("reconciliationRequired",true));f.repo.saveSource(state);return null;});
            f.edit("只恢复原已接收身份并改正文","steer");JSONObject ack=f.source().getJSONObject("submission");
            assertEquals(f.task(),ack.getString("taskId"));assertEquals(f.run(),ack.getString("runId"));assertEquals(f.epoch(),ack.getInt("epoch"));assertEquals("queued",ack.getString("status"));
            assertEquals(2L,f.nativeMessage().getLong("revision"));assertEquals(2L,f.row().getLong("queueRevision"));
            f.assistant.mutateQueuedOwnerInput(f.task(),f.conversation,f.messageId,2L,2L,"只恢复原已接收身份并改正文","steer",true);
            assertEquals("withdrawn",f.row().getString("queueState"));assertEquals("withdrawn",f.source().getJSONObject("submission").getString("status"));
            assertFalse(f.source().getJSONObject("submission").has("consumedAt"));assertFalse(f.control.isCancelled());
        }
        try(Fixture f=new Fixture()){
            f.repo.transaction(()->{JSONObject state=f.repo.source(f.messageId);state.put("submission",new JSONObject().put("status","unconfirmed").put("taskId",f.task())
                    .put("messageId",f.messageId).put("clientMessageId",f.client).put("conversationId",f.conversation));f.repo.saveSource(state);return null;});
            assertThrows(Exception.class,()->f.edit("部分tuple不能补猜","queue"));assertEquals(f.originalBody,f.nativeMessage().getString("content"));assertEquals(1L,f.row().getLong("queueRevision"));
        }
    }
    @Test public void acceptedFingerprintPinRejectsSelfConsistentButChangedNativeMetadata()throws Exception{
        try(Fixture f=new Fixture()){
            f.repo.transaction(()->{JSONObject state=f.repo.source(f.messageId),message=f.repo.message(f.conversation,f.messageId);
                state.put("requestFingerprint","e".repeat(64));message.put("requestFingerprint","e".repeat(64));f.repo.saveSource(state);f.repo.saveMessage(f.conversation,message);return null;});
            assertThrows(SecurityException.class,()->f.edit("不能以自洽副本替换原accepted pin","queue"));assertEquals(f.fingerprint,f.tasks.get(f.task(),false).getJSONArray("ownerInputs").getJSONObject(0).getString("ownerRequestFingerprint"));
            assertEquals(1L,f.row().getLong("queueRevision"));assertEquals(f.originalBody,f.nativeMessage().getString("content"));assertEquals(0,f.synchronizedSources.get());
        }
    }

}
