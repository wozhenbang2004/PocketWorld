package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.memory.SourceRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original TaskStore/history SQL; no model, device, replacement task ledger or fake delivery token. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationPhoneStorageNativeTest {
    private static SQLiteDatabase database(TaskStore tasks)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private static JSONObject facts()throws Exception{return new JSONObject().put("modelName","fixture").put("provider","openai").put("toolLimit",4).put("modelCallLimit",12).put("scopeMinutes",30).put("modelSelectionRevision",1L);}
    private static String sha(String text)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final ConversationRepository history;
        final String conversation="original-conversation",message="original-user";final JSONObject pinned;
        Fixture()throws Exception {
            app.deleteDatabase(TaskStore.DATABASE_NAME);tasks=new TaskStore(app);db=database(tasks);history=tasks.conversationRepository();history.initializeEmpty();
            history.transaction(()->{
                history.saveConversation(new JSONObject().put("conversationId",conversation).put("title","原私人对话").put("createdAt",1L).put("updatedAt",1L));
                history.saveSource(new SourceRef("message",message,1,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false));
                history.saveMessage(conversation,new JSONObject().put("id",message).put("revision",1L).put("role","user").put("content","请在手机查看天气").put("at",1L));return null;
            });
            pinned=new JSONObject().put("format","pocketworld.phone-handoff-source.v1").put("generation",history.authority().getString("generation"))
                    .put("conversationId",conversation).put("sourceMessageId",message).put("sourceMessageRevision",1L)
                    .put("bodyFingerprint","sha256:"+sha("请在手机查看天气")).put("sourceSequence",1L).put("conversationTitle","原私人对话");
        }
        TaskStore.ConversationPhoneReview prepare()throws Exception{return tasks.prepareConversationPhone(pinned,"查看天气","background",facts());}
        String create()throws Exception{return tasks.createReviewedConversationPhone(prepare(),id->{}).getString("taskId");}
        JSONObject start(String task)throws Exception{tasks.initializeExecutionScope(task,4);return tasks.startAttempt(task,"background",4);}
        long count(String table)throws Exception{try(var row=db.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
        JSONObject publish(TaskStore.ConversationPhoneDelivery delivery)throws Exception {
            JSONObject result=delivery.snapshot(),existing=history.sourceByReply(result.getString("deliveryId"));
            if(existing!=null)return new JSONObject().put("accepted",true).put("duplicate",true);
            if(!history.exists(conversation))return new JSONObject().put("conversationDeleted",true);
            String id=UUID.randomUUID().toString();
            history.saveSource(new SourceRef("message",id,1,"owner","private").toJson().put("conversationId",conversation).put("role","assistant").put("deleted",false)
                    .put("nativeReplyId",result.getString("deliveryId")).put("nativeReplyDigest",result.getString("resultDigest"))
                    .put("nativeTaskId",result.getString("taskId")).put("nativeRunId",result.getString("runId")).put("nativeEpoch",result.getInt("epoch")));
            history.saveMessage(conversation,new JSONObject().put("id",id).put("revision",1L).put("role","assistant").put("content",result.getString("content"))
                    .put("taskId",result.getString("taskId")).put("nativeRunId",result.getString("runId")).put("nativeEpoch",result.getInt("epoch"))
                    .put("nativeDeliveryId",result.getString("deliveryId")).put("status",result.getString("status")).put("resultDigest",result.getString("resultDigest")));
            return new JSONObject().put("accepted",true).put("duplicate",false).put("messageId",id);
        }
        public void close(){if(db.isOpen())db.close();}
    }

    @Test public void prepareIsReadOnlyAndRepeatCreationReturnsTheSameStoredIdentity()throws Exception {try(Fixture f=new Fixture()){
        long tasks=f.count("tasks"),runs=f.count("runs");var review=f.prepare();assertEquals(tasks,f.count("tasks"));assertEquals(runs,f.count("runs"));
        AtomicInteger allocations=new AtomicInteger();JSONObject created=f.tasks.createReviewedConversationPhone(review,id->allocations.incrementAndGet());
        assertTrue(created.getBoolean("created"));assertFalse(created.getBoolean("admitted"));String task=created.getString("taskId");
        JSONObject run=f.start(task),duplicate=f.tasks.createReviewedConversationPhone(review,id->allocations.incrementAndGet());
        assertEquals(1,allocations.get());assertFalse(duplicate.getBoolean("created"));assertTrue(duplicate.getBoolean("duplicate"));assertTrue(duplicate.getBoolean("admitted"));
        assertEquals(run.getString("runId"),duplicate.getString("runId"));assertEquals(tasks+1,f.count("tasks"));assertEquals(runs+1,f.count("runs"));
        JSONObject stored=f.tasks.get(task,false);assertFalse(stored.has("origin"));assertFalse(stored.has("parentTaskId"));assertEquals("owner",stored.getString("actorId"));
    }}
    @Test public void sourceChangesBeforeFirstWriteAreRefusedButNeverEraseAnExistingAck()throws Exception {try(Fixture f=new Fixture()){
        var old=f.prepare();f.history.transaction(()->{JSONObject source=f.history.source(f.message);source.put("revision",2L);f.history.saveSource(source);JSONObject message=f.history.message(f.conversation,f.message);message.put("revision",2L).put("content","已更改目标");f.history.saveMessage(f.conversation,message);return null;});
        assertThrows(SecurityException.class,()->f.tasks.createReviewedConversationPhone(old,id->{}));assertFalse(f.tasks.conversationPhoneCreation(old.view().getString("requestId"),f.conversation).getBoolean("accepted"));
    }
        try(Fixture f=new Fixture()){
            var review=f.prepare();JSONObject created=f.tasks.createReviewedConversationPhone(review,id->{});
            f.history.transaction(()->{f.history.deleteConversation(f.conversation,"user_conversation_delete");return null;});
            assertEquals(created.getString("taskId"),f.tasks.createReviewedConversationPhone(review,id->{fail("must not allocate twice");}).getString("taskId"));
            assertTrue(f.tasks.conversationPhoneCreation(created.getString("requestId"),f.conversation).getBoolean("accepted"));
        }
    }
    @Test public void allocationCallbackCannotBypassTheFinalSourceCheck()throws Exception {try(Fixture f=new Fixture()){
        var review=f.prepare();AtomicReference<String> allocated=new AtomicReference<>();
        assertThrows(Exception.class,()->f.tasks.createReviewedConversationPhone(review,id->{allocated.set(id);try{f.history.deleteConversation(f.conversation,"user_conversation_delete");}catch(Exception failure){throw new RuntimeException(failure);}}));
        assertEquals(review.view().getString("requestId"),allocated.get());assertFalse(f.tasks.conversationPhoneCreation(allocated.get(),f.conversation).getBoolean("accepted"));
    }}
    @Test public void resumedRunCannotOverwriteAnEarlierPendingResult()throws Exception {try(Fixture f=new Fixture()){
        String task=f.create();JSONObject first=f.start(task);String oldRun=first.getString("runId");int oldEpoch=first.getInt("coordinatorEpoch");
        f.tasks.endAttempt(task,oldRun,"needs_confirmation","第一轮真实结果",new JSONObject().put("observed","first"));
        AtomicReference<JSONObject> captured=new AtomicReference<>();f.tasks.withConversationPhoneDelivery(task,oldRun,oldEpoch,selected->{captured.set(selected.snapshot());return new JSONObject().put("waitingForOriginalConversation",true);});
        JSONObject next=f.tasks.startOriginalPhoneAttempt(task,oldRun,oldEpoch,"main_screen",4);
        f.tasks.endAttempt(task,next.getString("runId"),"failed","第二轮失败",new JSONObject().put("observed","second"));
        assertEquals(2,f.tasks.conversationPhoneDeliveryPage("",20).getJSONArray("entries").length());
        JSONObject firstPage=f.tasks.conversationPhoneDeliveryPage("",1);assertTrue(firstPage.getBoolean("hasMore"));
        assertEquals(oldRun,firstPage.getJSONArray("entries").getJSONObject(0).getString("runId"));
        JSONObject secondPage=f.tasks.conversationPhoneDeliveryPage(firstPage.getString("nextCursor"),1);assertFalse(secondPage.getBoolean("hasMore"));
        assertEquals(next.getString("runId"),secondPage.getJSONArray("entries").getJSONObject(0).getString("runId"));assertEquals("",secondPage.getString("nextCursor"));
        f.tasks.withConversationPhoneDelivery(task,oldRun,oldEpoch,selected->{assertEquals(captured.get().toString(),selected.snapshot().toString());return f.publish(selected);});
        JSONObject second=f.tasks.withConversationPhoneDelivery(task,next.getString("runId"),next.getInt("coordinatorEpoch"),f::publish);
        assertEquals("failed",second.getString("status"));assertEquals(0,f.tasks.conversationPhoneDeliveryPage("",20).getJSONArray("entries").length());
        assertEquals(3,f.count("assistant_messages"));
    }}
    @Test public void publicationIsAtomicAndRepeatedAfterALostCallerAckUsesTheOriginalMessage()throws Exception {try(Fixture f=new Fixture()){
        String task=f.create();JSONObject run=f.start(task);String id=run.getString("runId");int epoch=run.getInt("coordinatorEpoch");
        f.tasks.endAttempt(task,id,"cancelled","已停止，已发生效果仍需核对",new JSONObject());
        assertThrows(IllegalStateException.class,()->f.tasks.withConversationPhoneDelivery(task,id,epoch,selected->{f.publish(selected);throw new IllegalStateException("writer interrupted before commit");}));
        assertEquals(1,f.count("assistant_messages"));assertEquals(1,f.tasks.conversationPhoneDeliveryPage("",20).getJSONArray("entries").length());
        AtomicReference<TaskStore.ConversationPhoneDelivery> escaped=new AtomicReference<>();
        JSONObject original=f.tasks.withConversationPhoneDelivery(task,id,epoch,selected->{escaped.set(selected);return f.publish(selected);});
        assertThrows(SecurityException.class,()->escaped.get().snapshot());
        JSONObject repeated=f.tasks.withConversationPhoneDelivery(task,id,epoch,f::publish);
        assertEquals(original.getString("messageId"),repeated.getString("messageId"));assertTrue(repeated.getBoolean("duplicate"));assertEquals(2,f.count("assistant_messages"));
    }}
    @Test public void deletedPublishedMessageIsNotRecreatedAndDeletedConversationEndsPendingDelivery()throws Exception {try(Fixture f=new Fixture()){
        String task=f.create();JSONObject run=f.start(task);String id=run.getString("runId");int epoch=run.getInt("coordinatorEpoch");
        f.tasks.endAttempt(task,id,"needs_confirmation","实际结果",new JSONObject());JSONObject published=f.tasks.withConversationPhoneDelivery(task,id,epoch,f::publish);
        f.history.transaction(()->{JSONObject source=f.history.source(published.getString("messageId"));source.put("revision",2L).put("deleted",true).put("unavailableReason","user_message_delete");f.history.saveSource(source);return null;});
        JSONObject queried=f.tasks.withConversationPhoneDelivery(task,id,epoch,f::publish);assertTrue(queried.getBoolean("accepted"));assertFalse(queried.getBoolean("available"));assertEquals(2,f.count("assistant_sources"));
    }
        try(Fixture f=new Fixture()){
            String task=f.create();JSONObject run=f.start(task);f.tasks.endAttempt(task,run.getString("runId"),"failed","真实失败",new JSONObject());
            f.history.transaction(()->{f.history.deleteConversation(f.conversation,"user_conversation_delete");return null;});
            JSONObject receipt=f.tasks.withConversationPhoneDelivery(task,run.getString("runId"),run.getInt("coordinatorEpoch"),f::publish);
            assertTrue(receipt.getBoolean("conversationDeleted"));assertEquals(0,f.tasks.conversationPhoneDeliveryPage("",20).getJSONArray("entries").length());assertEquals(1,f.count("assistant_sources"));
        }
    }
    @Test public void recoveryFreezesTheActuallyInterruptedRunWithoutRequiringAnInitializedHistoryRepository()throws Exception {try(Fixture f=new Fixture()){
        String task=f.create();JSONObject run=f.start(task);String id=run.getString("runId");int epoch=run.getInt("coordinatorEpoch");f.db.close();
        TaskStore recovered=new TaskStore(f.app);SQLiteDatabase recoveredDb=database(recovered);
        try{
            assertEquals("paused",recovered.get(task,false).getString("status"));assertEquals(1,recovered.conversationPhoneDeliveryPage("",20).getJSONArray("entries").length());
            recovered.withConversationPhoneDelivery(task,id,epoch,selected->{assertEquals("interrupted",selected.snapshot().getString("status"));return new JSONObject().put("waitingForOriginalConversation",true);});
        }finally{recoveredDb.close();}
    }}
    @Test public void aPausedLiveRunHasNoResultAndASelfReportedAckCannotMarkPublication()throws Exception {try(Fixture f=new Fixture()){
        String task=f.create();JSONObject run=f.start(task);String id=run.getString("runId");int epoch=run.getInt("coordinatorEpoch");f.tasks.status(task,"paused","主人暂停");
        assertEquals(0,f.tasks.conversationPhoneDeliveryPage("",20).getJSONArray("entries").length());assertThrows(SecurityException.class,()->f.tasks.withConversationPhoneDelivery(task,id,epoch,f::publish));
        f.tasks.endAttempt(task,id,"failed","真实失败",new JSONObject());
        assertThrows(SecurityException.class,()->f.tasks.withConversationPhoneDelivery(task,id,epoch,selected->new JSONObject().put("accepted",true).put("messageId",UUID.randomUUID().toString())));
        assertEquals(1,f.tasks.conversationPhoneDeliveryPage("",20).getJSONArray("entries").length());assertEquals(1,f.count("assistant_messages"));
    }}
    @Test public void actuallyEndedPausedRunCanPublishItsRealRecoveryNotice()throws Exception {try(Fixture f=new Fixture()){
        String task=f.create();JSONObject run=f.start(task);String id=run.getString("runId");int epoch=run.getInt("coordinatorEpoch");
        f.tasks.endAttempt(task,id,"paused","运行条件不满足，等待主人继续",new JSONObject());
        assertEquals(1,f.tasks.conversationPhoneDeliveryPage("",20).getJSONArray("entries").length());
        JSONObject receipt=f.tasks.withConversationPhoneDelivery(task,id,epoch,selected->{assertEquals("paused",selected.snapshot().getString("status"));return f.publish(selected);});
        assertEquals("paused",receipt.getString("status"));assertFalse(receipt.getBoolean("resultVerified"));
    }}
}
