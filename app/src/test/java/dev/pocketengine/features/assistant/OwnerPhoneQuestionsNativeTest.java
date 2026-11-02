package dev.pocketengine.features.assistant;

import android.app.Application;
import android.app.Notification;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.notifications.OwnerPhoneQuestionNotification;
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReply;
import dev.pocketengine.store.TaskStore;
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

/** Actual original phone SQL/lease/mailbox and native hold identity. The physical fixture
 * rejects observation/effects, so these checks cannot operate a phone or paid model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerPhoneQuestionsNativeTest {
    private static final class Fixture implements AutoCloseable,OwnerPhoneQuestions.PhysicalOwner {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final String id,run,request=UUID.randomUUID().toString();final int epoch;final long revision=2;
        final RunControl original=new RunControl();final ModelConfig model=new ModelConfig("http://127.0.0.1:1","","fixture",false,8,10);final OwnerPhoneQuestions questions=new OwnerPhoneQuestions();
        final PhonePort physical=new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){}public JSONObject observe(boolean image,RunControl control){throw new AssertionError("test must not observe a device");}public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("test must not execute a device effect");}};
        long generation=1;PhonePort currentPhysical=physical;int requests;final OwnerPhoneQuestions.Held held;final String token;
        Fixture()throws Exception{
            app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);tasks.conversationRepository().initializeEmpty();
            String created=tasks.create("Native original phone question","main_screen").getString("id");tasks.initializeExecutionScope(created,8);JSONObject task=tasks.startAttempt(created,"main_screen",8);id=created;run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);
            tasks.openPhoneOwnerInputMailbox(id,run,epoch,original);tasks.journal(id,run,epoch,original).checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content","Phone fixture goal")).put(new JSONObject().put("role","assistant").put("content","Which phone option?")),1,new JSONObject());
            assertEquals(0,tasks.pollPhoneOwnerInputs(id,run,epoch,new JSONArray(),true,true,original).length());original.pause();
            JSONObject question=new JSONObject().put("format","pocketworld.owner-question.v1").put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch).put("requestId",request).put("waitRevision",revision).put("question","Which phone option?").put("replyKind","answer").put("approval",false).put("takeover",false);
            tasks.finish(id,"awaiting_user","Which phone option?",new JSONObject().put("ownerQuestion",question));
            assertNull(OwnerQuestionReply.reference(tasks.get(id,false)));held=tasks.retainOwnerPhoneQuestion(questions,id,original,physical,model,generation,this);assertNotNull(held);token=OwnerPhoneQuestions.noticeView(tasks.get(id,false)).getString("token");
        }
        public void requireHeld(OwnerPhoneQuestions.Held expected){if(expected.physicalPort()!=currentPhysical||expected.originalControl()!=original||expected.waitGeneration()!=generation||expected.capturedModel()!=model)throw new SecurityException("fixture physical hold changed");}
        public void requestContinuation(OwnerPhoneQuestions.Held expected){requireHeld(expected);requests++;}
        JSONObject consume(String text)throws Exception{return tasks.consumeOwnerQuestionReply(id,run,epoch,request,revision,token,text);}
        String grant()throws Exception{try(var row=db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return row.getString(0);}}
        long count(String table)throws Exception{try(var row=db.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
        String runState()throws Exception{try(var row=db.rawQuery("SELECT status FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return row.getString(0);}}
        public void close(){db.close();}
    }
    @Test public void savedPhoneAnswerUsesOriginalRunningRunLeaseAndMailboxWithoutInventingUserSource()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();assertNotNull(OwnerQuestionReply.reference(f.tasks.get(f.id,false)));JSONObject saved=f.consume("Original phone answer");assertEquals("native_phone_owner_input",saved.getString("sourceKind"));assertFalse(saved.getBoolean("formalSourceRefAvailable"));assertFalse(saved.getBoolean("executionAuthorized"));
        assertEquals(0,f.count("assistant_messages"));assertEquals(0,f.count("assistant_sources"));assertEquals(0,f.count("assistant_conversations"));assertEquals("running",f.runState());assertTrue(f.original.isPaused());
        JSONObject view=OwnerPhoneQuestions.pageQuestion(f.tasks.get(f.id,false));assertEquals("Original phone answer",view.getString("answerText"));assertFalse(view.has("sourceRef"));
        JSONObject resumed=f.tasks.resumeOwnerPhoneQuestionRun(f.held);assertEquals(f.run,resumed.getString("runId"));assertEquals(f.epoch,resumed.getInt("coordinatorEpoch"));assertTrue(f.original.isPaused());assertEquals(grant,f.grant());assertEquals(1,f.count("runs"));assertEquals(1,f.count("leases"));assertEquals(0,f.count("operations"));
        f.original.resumeAtPauseRevision(f.held.pauseRevision());assertTrue(f.questions.take(f.held));JSONArray rows=f.tasks.pollPhoneOwnerInputs(f.id,f.run,f.epoch,new JSONArray(),true,false,f.original);assertEquals(1,rows.length());assertEquals(saved.getString("messageId"),rows.getJSONObject(0).getString("messageId"));assertEquals("steer",rows.getJSONObject(0).getString("mode"));
        assertTrue(f.consume("Original phone answer").getBoolean("duplicate"));assertThrows(SecurityException.class,()->f.consume("Changed answer"));assertEquals(0,f.requests);assertEquals(grant,f.grant());
    }}
    @Test public void newPauseWaitGenerationOrPhysicalPortBlocksContinuationAfterSaving()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();f.consume("Keep the original answer");f.generation++;
        assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertThrows(SecurityException.class,()->f.held.requestContinuation());assertTrue(f.original.isPaused());assertEquals(grant,f.grant());assertFalse(f.tasks.get(f.id,false).getJSONObject("ownerInputMailbox").getBoolean("open"));
        f.generation--;f.currentPhysical=null;assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));f.currentPhysical=f.physical;f.original.pause();assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertEquals(0,f.requests);assertEquals(1,f.count("runs"));
    }}
    @Test public void expiredPhysicalLeasePreservesDataWithoutResumingOrRenewingTheGrant()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();f.db.execSQL("UPDATE leases SET expires_at=1 WHERE resource_id='phone.local'");JSONObject saved=f.consume("Save even with no live lease");assertTrue(saved.getBoolean("accepted"));
        assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertTrue(f.original.isPaused());assertEquals("awaiting_user",f.tasks.get(f.id,false).getString("status"));assertEquals("running",f.runState());assertEquals(grant,f.grant());assertEquals(1,f.count("runs"));assertEquals(0,f.count("assistant_sources"));
        assertEquals("saved_phone_lease_unavailable",f.tasks.noteOwnerQuestionAnswerState(saved,"saved_execution_unavailable").getString("continuationState"));
    }}
    @Test public void foregroundReplyActionExistsOnlyForActualRegisteredOrdinaryHoldAndDisappearsAfterConsume()throws Exception{try(Fixture f=new Fixture()){
        Notification.Builder builder=new Notification.Builder(f.app,"fixture-phone");OwnerPhoneQuestionNotification.add(builder,f.app,f.tasks.get(f.id,false));Notification notice=builder.build();assertNotNull(notice.actions);assertEquals(1,notice.actions.length);assertEquals(OwnerQuestionReply.KEY,notice.actions[0].getRemoteInputs()[0].getResultKey());
        JSONObject deviceOrigin=f.tasks.get(f.id,false).put("origin",new JSONObject().put("kind","device_goal"));Notification.Builder excluded=new Notification.Builder(f.app,"fixture-phone");OwnerPhoneQuestionNotification.add(excluded,f.app,deviceOrigin);assertNull(excluded.build().actions);
        f.consume("One foreground answer");Notification.Builder answered=new Notification.Builder(f.app,"fixture-phone");OwnerPhoneQuestionNotification.add(answered,f.app,f.tasks.get(f.id,false));assertNull(answered.build().actions);assertNull(OwnerQuestionReply.reference(f.tasks.get(f.id,false)));
    }}
}
