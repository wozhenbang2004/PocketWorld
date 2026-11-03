package dev.pocketengine.features.assistant;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReply;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original SQLite task/run, source and ownerInputs. No model, phone or external side effect. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerQuestionInputsNativeTest {
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final ConversationRepository history;
        final String id,run,conversation=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();final int epoch;final long revision=2;
        final RunControl original=new RunControl();final OwnerQuestionInputs inputs=new OwnerQuestionInputs();final ModelConfig model=new ModelConfig("http://127.0.0.1:1","","fixture",false,8,10);
        String token;OwnerQuestionInputs.Waiting waiting;
        Fixture()throws Exception{
            app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            history=tasks.conversationRepository();history.initializeEmpty();history.transaction(()->{history.saveConversation(new JSONObject().put("conversationId",conversation).put("title","Original fixture").put("updatedAt",System.currentTimeMillis()));return null;});
            String created=tasks.createCapabilityTask("Fixture ordinary question",conversation).getString("id");tasks.initializeExecutionScope(created,8);JSONObject task=tasks.startAttempt(created,"capabilities",8);
            id=created;run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);tasks.openOwnerInputMailbox(id,run,epoch,conversation,original);
            String initial=UUID.randomUUID().toString(),client=UUID.randomUUID().toString(),fingerprint=TaskStore.capabilityFingerprint(new JSONObject().put("content","Fixture goal").put("mode","queue").put("attachments",new JSONArray()));JSONObject ack=new JSONObject().put("status","unconfirmed").put("messageId",initial).put("clientMessageId",client).put("conversationId",conversation);
            JSONObject user=new JSONObject().put("id",initial).put("revision",1L).put("role","user").put("content","Fixture goal").put("clientMessageId",client).put("conversationId",conversation).put("at",System.currentTimeMillis()).put("inputMode","queue").put("requestFingerprint",fingerprint).put("submission",ack);
            history.transaction(()->{history.saveSource(new JSONObject().put("kind","message").put("sourceId",initial).put("revision",1L).put("ownerId","owner").put("spaceId","private").put("role","user").put("conversationId",conversation).put("deleted",false).put("updatedAt",System.currentTimeMillis()).put("clientMessageId",client).put("requestFingerprint",fingerprint).put("submission",ack));history.saveMessage(conversation,user);return null;});
            tasks.appendOwnerInput(id,run,epoch,conversation,user,"queue",original);tasks.pollOwnerInputs(id,run,epoch,new JSONArray(),true,false,original);
            tasks.journal(id,run,epoch,original).checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content","Fixture goal").put("ownerMessageId",initial).put("ownerMessageRevision",1L).put("ownerInputMode","queue")).put(new JSONObject().put("role","assistant").put("content","Which original option?")),1,new JSONObject());
            JSONObject question=new JSONObject().put("format","pocketworld.owner-question.v1").put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch).put("requestId",request).put("waitRevision",revision).put("question","Which original option?").put("replyKind","answer").put("approval",false).put("takeover",false);
            tasks.endAttempt(id,run,"awaiting_user","Which original option?",new JSONObject().put("ownerQuestion",question));
            var notice=tasks.ownerTaskNotice(id);assertTrue(tasks.beginOwnerTaskNoticePosting(notice));tasks.ownerTaskNoticePhase(notice,"posted");token=notice.view().getString("token");retain();
        }
        void retain()throws Exception{waiting=tasks.retainOwnerQuestionOriginal(inputs,id,original,model);assertNotNull(waiting);}
        JSONObject consume(String answer)throws Exception{return tasks.consumeOwnerQuestionReply(id,run,epoch,request,revision,token,answer);}
        String grant()throws Exception{try(var row=db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return row.getString(0);}}
        void changeTask(java.util.function.Consumer<JSONObject> mutation)throws Exception{
            synchronized(tasks){JSONObject task=tasks.get(id,false);mutation.accept(task);ContentValues value=new ContentValues();value.put("document",task.toString());db.update("tasks",value,"task_id=?",new String[]{id});}
        }
        long count(String table)throws Exception{try(var row=db.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
        public void close(){db.close();}
    }
    @Test public void oneAnswerReopensOnlyOriginalRunAndOriginalQueueWithoutRewritingGrant()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();JSONObject accepted=f.consume("Use the original second option");assertTrue(accepted.getBoolean("accepted"));assertFalse(accepted.getBoolean("duplicate"));assertFalse(accepted.getBoolean("executionAuthorized"));
        JSONObject admitted=f.tasks.get(f.id,false).getJSONArray("ownerInputs").getJSONObject(1),clock=admitted.getJSONObject("inputOriginClock");assertEquals(admitted.getLong("acceptedAt"),clock.getLong("receivedAtEpochMillis"));assertEquals("native_phone_owner_admission",clock.getString("source"));String firstClock=clock.toString();
        assertNull(OwnerQuestionReply.reference(f.tasks.get(f.id,false)));JSONObject source=f.history.source(accepted.getString("messageId"));assertEquals("user",source.getString("role"));assertEquals(f.conversation,source.getString("conversationId"));
        assertEquals("Use the original second option",f.history.message(f.conversation,source.getString("sourceId")).getString("content"));assertEquals("awaiting_user",f.tasks.get(f.id,false).getString("status"));
        JSONObject resumed=f.tasks.resumeOwnerQuestionRun(f.waiting);assertEquals(f.run,resumed.getString("runId"));assertEquals(f.epoch,resumed.getInt("coordinatorEpoch"));assertEquals("preparing",resumed.getString("status"));assertEquals(grant,f.grant());assertEquals(1,f.count("runs"));assertEquals(0,f.count("operations"));
        assertTrue(f.tasks.ownerQuestionContinuationActive(f.id,f.run,f.epoch,f.original));assertTrue(resumed.getJSONObject("ownerInputMailbox").getBoolean("open"));
        JSONArray polled=f.tasks.pollOwnerInputs(f.id,f.run,f.epoch,new JSONArray(),true,false,f.original);assertEquals(1,polled.length());assertEquals(accepted.getString("messageId"),polled.getJSONObject(0).getString("messageId"));
        assertThrows(Exception.class,()->f.tasks.resumeOwnerQuestionRun(f.waiting));assertTrue(f.consume("Use the original second option").getBoolean("duplicate"));assertEquals(2,f.tasks.get(f.id,false).getJSONArray("ownerInputs").length());assertEquals(grant,f.grant());
        assertEquals(firstClock,f.tasks.get(f.id,false).getJSONArray("ownerInputs").getJSONObject(1).getJSONObject("inputOriginClock").toString());
    }}
    @Test public void concurrentExactNoticeConsumeCreatesOneUserSourceAndChangedReplyCannotReplaceIt()throws Exception{try(Fixture f=new Fixture()){
        var workers=Executors.newFixedThreadPool(2);try{
            var one=workers.submit(()->f.consume("Same native answer"));var two=workers.submit(()->f.consume("Same native answer"));JSONObject a=one.get(5,TimeUnit.SECONDS),b=two.get(5,TimeUnit.SECONDS);
            assertNotEquals(a.getBoolean("duplicate"),b.getBoolean("duplicate"));assertEquals(a.getString("messageId"),b.getString("messageId"));assertEquals(2,f.count("assistant_sources"));assertEquals(2,f.count("assistant_messages"));
            assertThrows(SecurityException.class,()->f.consume("Changed answer"));assertThrows(SecurityException.class,()->f.tasks.consumeOwnerQuestionReply(f.id,f.run,f.epoch,f.request,f.revision,"0".repeat(64),"Same native answer"));
        }finally{workers.shutdownNow();}
    }}
    @Test public void exhaustedOrChangedOriginalScopeOnlyKeepsAnswerAndNeverCreatesAttempt()throws Exception{try(Fixture f=new Fixture()){
        f.changeTask(task->{try{JSONObject scope=task.getJSONObject("executionScope");scope.put("modelCallsUsed",scope.getInt("modelCallLimit"));}catch(Exception invalid){throw new RuntimeException(invalid);}});f.retain();
        String grant=f.grant();JSONObject saved=f.consume("Saved despite exhausted model budget");assertTrue(saved.getBoolean("accepted"));assertThrows(SecurityException.class,()->f.tasks.resumeOwnerQuestionRun(f.waiting));assertEquals("awaiting_user",f.tasks.get(f.id,false).getString("status"));assertEquals(grant,f.grant());assertEquals(1,f.count("runs"));assertFalse(f.tasks.get(f.id,false).getJSONObject("ownerInputMailbox").getBoolean("open"));
        JSONObject state=f.tasks.noteOwnerQuestionAnswerState(saved,"saved_execution_unavailable");assertEquals("saved_budget_exhausted",state.getString("continuationState"));assertEquals("review_execution_scope",state.getString("ownerAction"));assertEquals("saved_budget_exhausted",OwnerQuestionInputs.pageQuestion(f.tasks.get(f.id,false)).getString("continuationState"));assertEquals(grant,f.grant());
        OwnerQuestionInputs restarted=new OwnerQuestionInputs();assertNull(restarted.find(f.id,f.run,f.epoch,f.request,f.revision));
    }}
    @Test public void expiredOriginalGrantRejectsAnswerBeforeCreatingAnyUserSource()throws Exception{try(Fixture f=new Fixture()){
        JSONObject expired=new JSONObject(f.grant()).put("expiresAt",System.currentTimeMillis()-1);ContentValues changed=new ContentValues();changed.put("grant_json",expired.toString());f.db.update("runs",changed,"run_id=?",new String[]{f.run});long sources=f.count("assistant_sources"),messages=f.count("assistant_messages");
        assertThrows(SecurityException.class,()->f.consume("This expired answer must remain navigation only"));assertEquals(sources,f.count("assistant_sources"));assertEquals(messages,f.count("assistant_messages"));assertEquals(1,f.tasks.get(f.id,false).getJSONArray("ownerInputs").length());assertEquals(1,f.count("runs"));assertEquals(expired.toString(),f.grant());
    }}
    @Test public void missingConversationRollsBackConsumeAndLaterWithdrawnSourceStopsSameRunCas()throws Exception{try(Fixture f=new Fixture()){
        f.history.transaction(()->{f.history.deleteConversation(f.conversation,"user_conversation_delete");return null;});assertThrows(Exception.class,()->f.consume("Must not resurrect deleted conversation"));
        assertEquals(1,f.tasks.get(f.id,false).getJSONArray("ownerInputs").length());assertNotNull(OwnerQuestionReply.reference(f.tasks.get(f.id,false)));assertEquals(1,f.count("assistant_messages"));
    }try(Fixture f=new Fixture()){
        String grant=f.grant();f.consume("Save then withdraw original conversation");f.history.transaction(()->{f.history.deleteConversation(f.conversation,"user_conversation_delete");return null;});assertThrows(Exception.class,()->f.tasks.resumeOwnerQuestionRun(f.waiting));assertEquals(grant,f.grant());assertEquals(1,f.count("runs"));assertEquals("awaiting_user",f.tasks.get(f.id,false).getString("status"));
    }}
}
