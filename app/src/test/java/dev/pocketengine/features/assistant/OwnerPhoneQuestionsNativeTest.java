package dev.pocketengine.features.assistant;

import android.app.Application;
import android.app.Notification;
import android.app.RemoteInput;
import android.content.ContentValues;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.notifications.OwnerPhoneQuestionNotification;
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReply;
import dev.pocketengine.features.memory.OwnerSourceRunContext;
import dev.pocketengine.phone.PhoneNotificationControls;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Original SQLite phone run/lease/mailbox, actual notification dispatch, and the original
 * Service worker queue. The physical fixture rejects all observation and effects. These
 * checks are not device, model-provider, notification-delivery or visual acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class OwnerPhoneQuestionsNativeTest {
    @Before public void nativeKeyFixture(){
        java.security.Security.addProvider(new java.security.Provider("OwnerPhoneQuestionFixtureKeys",1.0d,"Test-only AndroidKeyStore provider"){{
            put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());
        }});
    }
    @After public void removeNativeKeyFixture(){java.security.Security.removeProvider("OwnerPhoneQuestionFixtureKeys");}

    @FunctionalInterface private interface Change {void apply(JSONObject value)throws Exception;}
    private static final class NoEffectsPort implements PhonePort {
        final AtomicInteger observations=new AtomicInteger(),effects=new AtomicInteger();
        public boolean isConnected(){return true;}
        public void clearReferences(){}
        public JSONObject observe(boolean image,RunControl control){observations.incrementAndGet();throw new AssertionError("test must not observe a device");}
        public JSONObject execute(String name,JSONObject args,RunControl control){effects.incrementAndGet();throw new AssertionError("test must not dispatch a device effect");}
        void assertUnused(){assertEquals(0,observations.get());assertEquals(0,effects.get());}
    }
    private static final class Fixture implements AutoCloseable,OwnerPhoneQuestions.PhysicalOwner {
        final Application app=RuntimeEnvironment.getApplication();
        final TaskStore tasks;final SQLiteDatabase db;final boolean ownsDatabase;
        final String id,run,request=UUID.randomUUID().toString();final int epoch;final long revision=2;
        final RunControl original=new RunControl();final ModelConfig model;
        final OwnerPhoneQuestions questions=new OwnerPhoneQuestions();final NoEffectsPort physical=new NoEffectsPort();
        final OwnerPhoneQuestions.Held held;final String token;
        long generation=1;PhonePort currentPhysical=physical;ModelConfig currentModel;int continuationRequests;
        Fixture()throws Exception{this(null,null);}
        Fixture(TaskStore existing,ModelConfig captured)throws Exception{
            ownsDatabase=existing==null;
            if(ownsDatabase){app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);}else tasks=existing;
            db=(SQLiteDatabase)get(tasks,"db");tasks.conversationRepository().initializeEmpty();
            model=captured==null?new ModelConfig("http://127.0.0.1:1","","fixture",false,8,10):captured;currentModel=model;
            id=tasks.create("Native original phone question","main_screen").getString("id");tasks.initializeExecutionScope(id,8);
            JSONObject task=tasks.startAttempt(id,"main_screen",8);run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);
            tasks.openPhoneOwnerInputMailbox(id,run,epoch,original);
            tasks.journal(id,run,epoch,original).checkpoint(messages(),1,new JSONObject());
            assertEquals(0,tasks.pollPhoneOwnerInputs(id,run,epoch,new JSONArray(),true,true,original).length());original.pause();
            JSONObject question=new JSONObject().put("format","pocketworld.owner-question.v1").put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch)
                .put("requestId",request).put("waitRevision",revision).put("question","Which phone option?").put("replyKind","answer").put("approval",false).put("takeover",false);
            tasks.finish(id,"awaiting_user","Which phone option?",new JSONObject().put("ownerQuestion",question));
            assertNull(OwnerQuestionReply.reference(tasks.get(id,false)));assertNull(OwnerPhoneQuestions.reference(tasks.get(id,false)));
            held=tasks.retainOwnerPhoneQuestion(questions,id,original,physical,model,generation,this);assertNotNull(held);
            token=OwnerPhoneQuestions.noticeView(tasks.get(id,false)).getString("token");
        }
        JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","Phone fixture goal"))
            .put(new JSONObject().put("role","assistant").put("content","Which phone option?"));}
        public void requireHeld(OwnerPhoneQuestions.Held expected){
            if(expected.physicalPort()!=currentPhysical||expected.originalControl()!=original||expected.waitGeneration()!=generation||expected.capturedModel()!=currentModel)
                throw new SecurityException("fixture physical hold changed");
        }
        public void requestContinuation(OwnerPhoneQuestions.Held expected){requireHeld(expected);continuationRequests++;}
        JSONObject consume(String text)throws Exception{return tasks.consumeOwnerQuestionReply(id,run,epoch,request,revision,token,text);}
        JSONObject exact()throws Exception{
            JSONObject view=OwnerPhoneQuestions.noticeView(tasks.get(id,false));
            return new JSONObject(view.getJSONObject("ownerQuestion").toString()).put("noticeToken",view.getString("token"));
        }
        String grant()throws Exception{return column("SELECT grant_json FROM runs WHERE run_id=?",run);}
        String runState()throws Exception{return column("SELECT status FROM runs WHERE run_id=?",run);}
        String column(String sql,String...args)throws Exception{try(var row=db.rawQuery(sql,args)){assertTrue(row.moveToFirst());return row.getString(0);}}
        long count(String table)throws Exception{try(var row=db.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
        long runCount()throws Exception{try(var row=db.rawQuery("SELECT count(*) FROM runs WHERE task_id=?",new String[]{id})){assertTrue(row.moveToFirst());return row.getLong(0);}}
        long savedCount()throws Exception{JSONArray rows=tasks.get(id,false).optJSONArray("ownerInputs");return rows==null?0:rows.length();}
        void changeTask(Change mutation)throws Exception{
            synchronized(tasks){JSONObject task=tasks.get(id,false);mutation.apply(task);ContentValues changed=new ContentValues();changed.put("document",task.toString());assertEquals(1,db.update("tasks",changed,"task_id=?",new String[]{id}));}
        }
        void changeGrant(Change mutation)throws Exception{
            synchronized(tasks){JSONObject permission=new JSONObject(grant());mutation.apply(permission);ContentValues changed=new ContentValues();changed.put("grant_json",permission.toString());assertEquals(1,db.update("runs",changed,"run_id=?",new String[]{run}));}
        }
        void requireUnclaimed()throws Exception{
            JSONObject task=tasks.get(id,false);assertFalse(task.getJSONObject("ownerInputMailbox").getBoolean("open"));
            assertFalse(task.has("ownerQuestionContinuation"));assertEquals("awaiting_user",task.getString("status"));assertEquals(1,runCount());physical.assertUnused();
        }
        String linkConversation()throws Exception{
            String conversation=UUID.randomUUID().toString();ConversationRepository history=tasks.conversationRepository();
            history.transaction(()->{history.saveConversation(new JSONObject().put("conversationId",conversation).put("title","Original linked phone conversation").put("updatedAt",System.currentTimeMillis()));return null;});
            changeTask(task->task.put("conversationId",conversation));return conversation;
        }
        public void close(){physical.assertUnused();if(ownsDatabase)db.close();}
    }

    @Test public void savedAnswerUsesOnlyOriginalRunLeaseGrantAndPhoneMailbox()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant(),lease=f.column("SELECT expires_at FROM leases WHERE resource_id='phone.local'");
        assertNotNull(OwnerPhoneQuestions.reference(f.tasks.get(f.id,false)));assertNull(OwnerQuestionReply.reference(f.tasks.get(f.id,false)));
        JSONObject saved=f.consume("Original phone answer");assertEquals("native_phone_owner_input",saved.getString("sourceKind"));
        assertFalse(saved.getBoolean("formalSourceRefAvailable"));assertFalse(saved.getBoolean("executionAuthorized"));assertFalse(saved.getBoolean("continuationRequested"));
        assertEquals(0,f.count("assistant_messages"));assertEquals(0,f.count("assistant_sources"));assertEquals(0,f.count("assistant_conversations"));
        assertEquals("running",f.runState());assertTrue(f.original.isPaused());f.requireUnclaimed();
        JSONObject view=OwnerPhoneQuestions.pageQuestion(f.tasks.get(f.id,false));assertEquals("Original phone answer",view.getString("answerText"));assertFalse(view.has("sourceRef"));
        JSONObject resumed=f.tasks.resumeOwnerPhoneQuestionRun(f.held);assertEquals(f.run,resumed.getString("runId"));assertEquals(f.epoch,resumed.getInt("coordinatorEpoch"));
        assertTrue(f.original.isPaused());assertEquals(grant,f.grant());assertEquals(lease,f.column("SELECT expires_at FROM leases WHERE resource_id='phone.local'"));
        assertEquals(1,f.count("runs"));assertEquals(1,f.count("leases"));assertEquals(0,f.count("operations"));assertThrows(Exception.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));
        f.original.resumeAtPauseRevision(f.held.pauseRevision());assertTrue(f.questions.take(f.held));assertFalse(f.questions.take(f.held));
        JSONArray polled=f.tasks.pollPhoneOwnerInputs(f.id,f.run,f.epoch,new JSONArray(),true,false,f.original);assertEquals(1,polled.length());
        assertEquals(saved.getString("messageId"),polled.getJSONObject(0).getString("messageId"));assertEquals("steer",polled.getJSONObject(0).getString("mode"));
        JSONArray history=f.messages().put(new JSONObject().put("role","user").put("content","Original phone answer").put("ownerMessageId",saved.getString("messageId")).put("ownerInputMode","steer"));
        f.tasks.journal(f.id,f.run,f.epoch,f.original).checkpoint(history,1,new JSONObject());
        assertEquals(0,f.tasks.pollPhoneOwnerInputs(f.id,f.run,f.epoch,new JSONArray().put(saved.getString("messageId")),true,false,f.original).length());
        assertTrue(f.consume("Original phone answer").getBoolean("duplicate"));assertEquals(1,f.savedCount());assertEquals(0,f.continuationRequests);assertEquals(grant,f.grant());
    }}

    @Test public void answerAdmissionKeepsItsOriginalClockAcrossDuplicateAndLegacyProjection()throws Exception{
        TimeZone previous=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            try(Fixture f=new Fixture()){
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));JSONObject saved=f.consume("Keep the original admission time");
                JSONObject row=f.tasks.get(f.id,false).getJSONArray("ownerInputs").getJSONObject(0),fact=row.getJSONObject("inputOriginClock");String originalFact=fact.toString();
                assertEquals("America/New_York",fact.getString("ianaZoneId"));assertEquals(row.getLong("acceptedAt"),fact.getLong("receivedAtEpochMillis"));
                assertEquals(java.time.Instant.ofEpochMilli(row.getLong("acceptedAt")).toString(),fact.getString("receivedAtUtc"));
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));assertTrue(f.consume("Keep the original admission time").getBoolean("duplicate"));
                assertEquals(originalFact,f.tasks.get(f.id,false).getJSONArray("ownerInputs").getJSONObject(0).getJSONObject("inputOriginClock").toString());assertEquals(1,f.savedCount());f.requireUnclaimed();
                f.tasks.resumeOwnerPhoneQuestionRun(f.held);f.original.resumeAtPauseRevision(f.held.pauseRevision());assertTrue(f.questions.take(f.held));
                JSONObject mixed=f.tasks.ownerInputClockContext(f.id,f.run,f.epoch,java.util.List.of(saved.getString("messageId")),f.original,true);
                assertEquals("mixed",mixed.getString("status"));assertEquals(2,mixed.getInt("knownInputCount"));assertEquals(0,mixed.getInt("missingInputCount"));assertTrue(mixed.isNull("canonicalZoneId"));assertFalse(mixed.getBoolean("executionAuthorized"));
                f.changeTask(task->task.getJSONArray("ownerInputs").getJSONObject(0).remove("inputOriginClock"));
                JSONObject legacy=f.tasks.ownerInputClockContext(f.id,f.run,f.epoch,java.util.List.of(saved.getString("messageId")),f.original,true);
                assertEquals("missing",legacy.getString("status"));assertEquals(1,legacy.getInt("knownInputCount"));assertEquals(1,legacy.getInt("missingInputCount"));assertTrue(legacy.isNull("canonicalZoneId"));
                assertFalse(f.tasks.get(f.id,false).getJSONArray("ownerInputs").getJSONObject(0).has("inputOriginClock"));assertEquals(0,f.count("assistant_sources"));assertEquals(0,f.count("operations"));
            }
        }finally{TimeZone.setDefault(previous);}
    }

    @Test public void concurrentExactAnswersSaveOnceAndConflictingBindingsCannotReplaceTheAnswer()throws Exception{try(Fixture f=new Fixture()){
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            var one=workers.submit(()->{start.await();return f.consume("One exact phone answer");});
            var two=workers.submit(()->{start.await();return f.consume("One exact phone answer");});start.countDown();
            JSONObject a=one.get(5,TimeUnit.SECONDS),b=two.get(5,TimeUnit.SECONDS);assertNotEquals(a.getBoolean("duplicate"),b.getBoolean("duplicate"));assertEquals(a.getString("messageId"),b.getString("messageId"));
            assertEquals(1,f.savedCount());assertEquals(0,f.count("assistant_sources"));
            assertThrows(SecurityException.class,()->f.consume("Changed answer"));
            assertThrows(SecurityException.class,()->f.tasks.consumeOwnerQuestionReply(f.id,f.run,f.epoch,f.request,f.revision,"0".repeat(64),"One exact phone answer"));
            assertThrows(SecurityException.class,()->f.tasks.consumeOwnerQuestionReply(f.id,f.run,f.epoch,f.request,f.revision+1,f.token,"One exact phone answer"));
            assertThrows(SecurityException.class,()->f.tasks.consumeOwnerQuestionReply(f.id,f.run,f.epoch,UUID.randomUUID().toString(),f.revision,f.token,"One exact phone answer"));f.requireUnclaimed();
        }finally{start.countDown();workers.shutdownNow();}
    }}

    @Test public void changedPhysicalPortModelGenerationAndPauseRevisionRefuseSavedContinuation()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();f.consume("Keep the exact answer");f.generation++;
        assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertThrows(SecurityException.class,()->f.held.requestContinuation());
        f.generation--;f.currentPhysical=new NoEffectsPort();assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));
        f.currentPhysical=f.physical;f.currentModel=new ModelConfig("http://127.0.0.1:1","","replacement",false,8,10);assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));
        f.currentModel=f.model;f.original.pause();assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));
        assertTrue(f.original.isPaused());assertEquals(grant,f.grant());assertEquals(0,f.continuationRequests);f.requireUnclaimed();
    }}

    @Test public void expiredOrReassignedPhysicalLeaseNeverRenewsOriginalAuthority()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();JSONObject saved=f.consume("Saved before lease expiry");f.db.execSQL("UPDATE leases SET expires_at=1 WHERE resource_id='phone.local'");
        assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertEquals("1",f.column("SELECT expires_at FROM leases WHERE resource_id='phone.local'"));
        assertEquals("saved_phone_lease_unavailable",f.tasks.noteOwnerQuestionAnswerState(saved,"saved_execution_unavailable").getString("continuationState"));
        assertEquals(grant,f.grant());f.requireUnclaimed();
        f.db.execSQL("UPDATE leases SET run_id=?,expires_at=? WHERE resource_id='phone.local'",new Object[]{UUID.randomUUID().toString(),System.currentTimeMillis()+60000});
        assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertEquals(1,f.count("leases"));assertEquals(grant,f.grant());f.requireUnclaimed();
    }}

    @Test public void expiredGrantRejectsNewAnswersAndSavedAnswersDoNotExtendIt()throws Exception{try(Fixture f=new Fixture()){
        f.changeGrant(permission->permission.put("expiresAt",1L));String expired=f.grant();assertThrows(SecurityException.class,()->f.consume("An expired notice cannot save a new answer"));
        assertEquals(0,f.savedCount());assertEquals(expired,f.grant());f.requireUnclaimed();
    }try(Fixture f=new Fixture()){
        f.consume("Saved while original grant was valid");f.changeGrant(permission->permission.put("expiresAt",1L));String expired=f.grant();
        assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertEquals(expired,f.grant());assertEquals(1,f.savedCount());f.requireUnclaimed();
    }}

    @Test public void exhaustedBudgetAndChangedScopePreserveAnswerWithoutAnotherAttempt()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();JSONObject saved=f.consume("Keep the answer without extra budget");
        f.changeTask(task->{JSONObject scope=task.getJSONObject("executionScope");scope.put("modelCallsUsed",scope.getInt("modelCallLimit"));});
        assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));
        assertEquals("saved_budget_exhausted",f.tasks.noteOwnerQuestionAnswerState(saved,"saved_execution_unavailable").getString("continuationState"));assertEquals(grant,f.grant());f.requireUnclaimed();
    }try(Fixture f=new Fixture()){
        String grant=f.grant();f.consume("No revised authority");f.changeTask(task->{JSONObject scope=task.getJSONObject("executionScope");scope.put("revision",scope.getLong("revision")+1);});
        assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertEquals(grant,f.grant());f.requireUnclaimed();
    }}

    @Test public void savedProofAndCheckpointStepsCannotBeChangedIntoContinuationAuthority()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();f.consume("Exact saved proof");
        String checkpoint=f.column("SELECT checkpoint FROM runs WHERE run_id=?",f.run);
        for(Object invalid:new Object[]{-1,1.5,"1"}){
            ContentValues changed=new ContentValues();changed.put("checkpoint",new JSONObject(checkpoint).put("steps",invalid).toString());
            assertEquals(1,f.db.update("runs",changed,"run_id=?",new String[]{f.run}));
            assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));f.requireUnclaimed();
        }
        ContentValues restored=new ContentValues();restored.put("checkpoint",checkpoint);assertEquals(1,f.db.update("runs",restored,"run_id=?",new String[]{f.run}));
        String originalRow=f.tasks.get(f.id,false).getJSONArray("ownerInputs").getJSONObject(0).toString();
        Change[] changes={
            row->row.getJSONObject("ownerQuestionReply").put("waitRevision",f.revision+1),
            row->row.getJSONObject("ownerQuestionReply").put("noticeToken","0".repeat(64)),
            row->row.put("acceptedTaskId",UUID.randomUUID().toString()),
            row->row.put("mode","queue"),
            row->row.put("payloadSha256","0".repeat(64)),
            row->row.put("queueState","withdrawn"),
            row->row.put("queueState","materialized"),
            row->row.put("content","Changed after original save")
        };
        for(Change change:changes){
            f.changeTask(task->{JSONObject row=new JSONObject(originalRow);change.apply(row);task.getJSONArray("ownerInputs").put(0,row);});
            assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));f.requireUnclaimed();
        }
        assertEquals(grant,f.grant());assertEquals(1,f.savedCount());
    }}

    @Test public void ordinaryTextCannotApproveSensitiveWorkOrReplayAnUnknownOperation()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();f.consume("An ordinary answer grants nothing");f.changeTask(task->task.put("approvals",new JSONArray().put(new JSONObject().put("state","pending"))));
        assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertEquals("pending",f.tasks.get(f.id,false).getJSONArray("approvals").getJSONObject(0).getString("state"));f.requireUnclaimed();
        f.changeTask(task->task.put("approvals",new JSONArray()));String operation=UUID.randomUUID().toString();
        f.db.execSQL("INSERT INTO operations(operation_id,task_id,run_id,tool,arguments,state,created_at) VALUES(?,?,?,?,?,?,?)",new Object[]{operation,f.id,f.run,"phone_tap","{}","unknown",System.currentTimeMillis()});
        assertTrue(f.tasks.hasUnresolved(f.id));assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));
        assertEquals("unknown",f.column("SELECT state FROM operations WHERE operation_id=?",operation));assertEquals(grant,f.grant());assertEquals(1,f.count("operations"));f.requireUnclaimed();
    }}

    @Test public void deletedLinkedConversationBlocksSavingAndLaterContinuation()throws Exception{try(Fixture f=new Fixture()){
        String conversation=f.linkConversation();f.tasks.conversationRepository().transaction(()->{f.tasks.conversationRepository().deleteConversation(conversation,"user_conversation_delete");return null;});
        assertThrows(Exception.class,()->f.consume("Do not restore deleted original sources"));assertEquals(0,f.savedCount());f.requireUnclaimed();
    }try(Fixture f=new Fixture()){
        String conversation=f.linkConversation(),grant=f.grant();JSONObject saved=f.consume("Saved before linked conversation deletion");
        f.tasks.conversationRepository().transaction(()->{f.tasks.conversationRepository().deleteConversation(conversation,"user_conversation_delete");return null;});
        assertThrows(Exception.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertEquals(grant,f.grant());
        assertEquals("saved_source_unavailable",f.tasks.noteOwnerQuestionAnswerState(saved,"saved_execution_unavailable").getString("continuationState"));f.requireUnclaimed();
    }}

    @Test public void pendingOutputAndWithdrawnFormalDependenciesBlockTheOriginalRun()throws Exception{try(Fixture f=new Fixture()){
        String claim=UUID.randomUUID().toString();OwnerSourceRunContext.reserveOutput(f.db,f.id,f.run,claim);
        assertThrows(Exception.class,()->f.consume("An unknown source read cannot be skipped"));assertEquals(0,f.savedCount());f.requireUnclaimed();
        OwnerSourceRunContext.outputNotDispatched(f.db,f.id,f.run,claim);String grant=f.grant();f.consume("Saved before formal source withdrawal");
        String record=UUID.randomUUID().toString();OwnerSourceRunContext.formalPrepared(f.db,f.id,f.run,new JSONArray().put(new JSONObject().put("id",record).put("revision",1L)));
        assertTrue(OwnerSourceRunContext.withdrawFormalOutputs(f.db,f.id,f.run,new JSONArray().put(new JSONObject().put("recordId",record).put("revision",1L))));
        assertThrows(Exception.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));assertEquals(grant,f.grant());assertEquals(1,f.savedCount());f.requireUnclaimed();
    }}

    @Test public void cancellationReleaseAndProcessLossCannotRecreateLiveHoldFromJson()throws Exception{try(Fixture f=new Fixture()){
        f.consume("Keep a saved answer after cancellation");f.original.cancel();assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));f.requireUnclaimed();
    }try(Fixture f=new Fixture()){
        f.consume("Keep a saved answer after process loss");assertNotNull(f.tasks.get(f.id,false).getJSONObject("ownerPhoneQuestionHold"));
        OwnerPhoneQuestions restarted=new OwnerPhoneQuestions();assertNull(restarted.find(f.id,f.run,f.epoch,f.request,f.revision));
        f.questions.released(f.id,f.original);assertThrows(SecurityException.class,()->f.held.requestContinuation());assertThrows(SecurityException.class,()->f.tasks.resumeOwnerPhoneQuestionRun(f.held));
        JSONObject receipt=f.tasks.ownerQuestionReplyReceipt(f.id,f.run,f.epoch,f.request,f.revision,f.token,"Keep a saved answer after process loss");
        assertTrue(receipt.getBoolean("duplicate"));assertFalse(receipt.getBoolean("continuationRequested"));assertEquals(1,f.savedCount());f.requireUnclaimed();
    }}

    @Test public void actualPhoneRemoteInputUsesSharedIntentSchemeAndConsumesOnlyItsExactQuestion()throws Exception{try(Fixture f=new Fixture()){
        Notification.Action action=answerAction(f.app,f.tasks.get(f.id,false));assertEquals(OwnerQuestionReply.KEY,action.getRemoteInputs()[0].getResultKey());assertFalse(action.getAllowGeneratedReplies());assertTrue(action.isAuthenticationRequired());
        Intent intent=answerIntent(action,"Native phone notification answer");assertEquals("pocketworld-task-action",intent.getData().getScheme());assertEquals("owner",intent.getData().getAuthority());
        AtomicInteger acceptedCalls=new AtomicInteger();OwnerQuestionReply.Host host=new OwnerQuestionReply.Host(){
            public JSONObject answerOwnerQuestionFromNotification(JSONObject exact,String text)throws Exception{
                acceptedCalls.incrementAndGet();return f.tasks.consumeOwnerQuestionReply(exact.getString("taskId"),exact.getString("runId"),exact.getInt("coordinatorEpoch"),exact.getString("requestId"),exact.getLong("waitRevision"),exact.getString("noticeToken"),text);
            }
            public JSONObject stopOwnerTaskFromNotification(JSONObject exact){throw new AssertionError("ordinary answer must not become stop or approval");}
        };
        Intent changedScheme=new Intent(intent).setData(Uri.parse("pocketworld-phone-question://owner/old"));assertThrows(SecurityException.class,()->OwnerQuestionReply.dispatch(host,changedScheme));assertEquals(0,acceptedCalls.get());
        JSONObject saved=OwnerQuestionReply.dispatch(host,intent);assertTrue(saved.getBoolean("accepted"));assertTrue(OwnerQuestionReply.dispatch(host,intent).getBoolean("duplicate"));assertEquals(1,f.savedCount());
        Notification.Builder answered=new Notification.Builder(f.app,"fixture-phone");OwnerPhoneQuestionNotification.add(answered,f.app,f.tasks.get(f.id,false));assertNull(answered.build().actions);
        assertNull(OwnerQuestionReply.reference(f.tasks.get(f.id,false)));assertNull(OwnerPhoneQuestions.reference(f.tasks.get(f.id,false)));f.requireUnclaimed();
    }}

    @Test public void deviceOriginsApprovalTakeoverAndIncompleteCleanupNeverOfferPhoneInlineAnswer()throws Exception{try(Fixture f=new Fixture()){
        for(String origin:new String[]{"device_goal","channel","group","schedule","owner_reviewed_realtime"}){
            JSONObject task=f.tasks.get(f.id,false).put("origin",new JSONObject().put("kind",origin));assertNull(OwnerPhoneQuestions.reference(task));
            Notification.Builder builder=new Notification.Builder(f.app,"fixture-phone");OwnerPhoneQuestionNotification.add(builder,f.app,task);assertNull(builder.build().actions);
        }
        for(String flag:new String[]{"approval","takeover"}){
            JSONObject task=f.tasks.get(f.id,false);task.getJSONObject("evidence").getJSONObject("ownerQuestion").put(flag,true);assertNull(OwnerPhoneQuestions.reference(task));
        }
        for(String flag:new String[]{"extensionEndUnavailable","extensionCleanupPending","projectServiceDrainPending","projectServiceDrainEvidenceUnavailable"}){
            JSONObject task=f.tasks.get(f.id,false);task.getJSONObject("evidence").put(flag,true);
            assertNull(new OwnerPhoneQuestions().retain(task,new JSONObject(f.grant()),f.original,f.physical,f.model,f.generation,f));
        }
        assertEquals(0,f.savedCount());f.requireUnclaimed();
    }}

    private static final class ServiceFixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();final Fixture sql;
        final ServiceController<PhoneRunService> controller;final PhoneRunService service;final ExecutorService worker;
        final CountDownLatch workerStarted=new CountDownLatch(1),releaseWorker=new CountDownLatch(1);final JSONObject exact;
        ServiceFixture()throws Exception{
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:1").putString("provider","openai_chat").putString("model","phone-owner-fixture")
                .putBoolean("supportsImageInput",false).putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();
            sql=new Fixture(app.tasks,app.settings.get());controller=Robolectric.buildService(PhoneRunService.class).create();service=controller.get();
            set(app,"activeTaskId",sql.id);set(app,"activeControl",sql.original);set(app,"activeMode","main_screen");set(app,"activeConfig",sql.model);set(app,"activePort",sql.physical);
            set(service,"runningId",sql.id);set(service,"runningRunId",sql.run);set(service,"runningEpoch",sql.epoch);set(service,"ownerControl",sql.original);
            set(service,"runningGoal","Original phone fixture");set(service,"runningConfig",sql.model);set(service,"heldPort",sql.physical);set(service,"waitingForUser",true);set(service,"ownerWaitGeneration",sql.generation);
            Method host=PhoneRunService.class.getDeclaredMethod("ownerPhoneQuestionHost");host.setAccessible(true);
            app.retainOwnerPhoneQuestion(sql.id,sql.original,sql.physical,sql.model,sql.generation,(OwnerPhoneQuestions.PhysicalOwner)host.invoke(service));exact=sql.exact();
            worker=(ExecutorService)get(service,"worker");worker.execute(()->{workerStarted.countDown();try{assertTrue("worker blocker was not released",releaseWorker.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            assertTrue("original Service worker did not start",workerStarted.await(5,TimeUnit.SECONDS));
        }
        JSONObject answer(String text)throws Exception{return app.answerOwnerQuestionFromNotification(new JSONObject(exact.toString()),text);}
        void drain()throws Exception{releaseWorker.countDown();worker.submit(()->{}).get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();}
        public void close()throws Exception{
            try{releaseWorker.countDown();if(!worker.isShutdown())worker.submit(()->{}).get(5,TimeUnit.SECONDS);}
            finally{controller.destroy();shadowOf(Looper.getMainLooper()).idle();sql.close();}
        }
    }

    @Test @Config(application=EngineApplication.class)
    public void actualServiceQueueRechecksPhysicalGenerationPauseLeaseAndCancelBeforeClaim()throws Exception{
        for(String changed:new String[]{"generation","physical","pause","lease","cancel","source","model"})try(ServiceFixture f=new ServiceFixture()){
            String grant=f.sql.grant();JSONObject saved=f.answer("Saved while the original worker is occupied");
            assertTrue(changed,saved.getBoolean("accepted"));assertTrue(changed,saved.getBoolean("continuationRequested"));assertTrue(changed,f.sql.original.isPaused());f.sql.requireUnclaimed();
            assertTrue(f.answer("Saved while the original worker is occupied").getBoolean("duplicate"));assertEquals(1,f.sql.savedCount());
            switch(changed){
                case "generation" -> set(f.service,"ownerWaitGeneration",f.sql.generation+1);
                case "physical" -> set(f.service,"heldPort",null);
                case "pause" -> f.sql.original.pause();
                case "lease" -> f.sql.db.execSQL("UPDATE leases SET expires_at=1 WHERE resource_id='phone.local'");
                case "cancel" -> f.sql.original.cancel();
                case "source" -> OwnerSourceRunContext.reserveOutput(f.sql.db,f.sql.id,f.sql.run,UUID.randomUUID().toString());
                case "model" -> f.app.getSharedPreferences("private_settings",0).edit().putString("model","changed-before-original-worker-dequeue").commit();
                default -> throw new AssertionError(changed);
            }
            f.drain();assertTrue(changed,f.sql.original.isPaused());assertEquals(grant,f.sql.grant());assertEquals(1,f.sql.savedCount());f.sql.requireUnclaimed();
            JSONObject state=f.sql.tasks.get(f.sql.id,false).getJSONObject("ownerQuestionReplyState");assertNotEquals(changed,"original_worker_requested",state.getString("state"));
        }
    }

    @Test @Config(application=EngineApplication.class)
    public void uncertainSqlSaveReturnsUnconfirmedWithoutEnqueuingOrAutomaticallyRetrying()throws Exception{try(ServiceFixture f=new ServiceFixture()){
        String grant=f.sql.grant();f.sql.db.execSQL("CREATE TRIGGER reject_phone_answer BEFORE UPDATE OF document ON tasks WHEN NEW.task_id='"+f.sql.id+"' AND instr(NEW.document,'replyConsumed')>0 BEGIN SELECT RAISE(ABORT,'fixture save unavailable'); END");
        JSONObject result=f.answer("Keep this explicit save failure unconfirmed");assertFalse(result.getBoolean("accepted"));assertEquals("unconfirmed",result.getString("answerSave"));assertEquals("reply_unconfirmed",result.getString("continuationState"));
        f.sql.db.execSQL("DROP TRIGGER reject_phone_answer");f.drain();assertEquals(0,f.sql.savedCount());assertEquals(grant,f.sql.grant());assertTrue(f.sql.original.isPaused());f.sql.requireUnclaimed();
    }}

    @Test @Config(application=EngineApplication.class)
    public void rejectedOriginalServiceWorkerKeepsSavedAnswerWithoutAReplacementExecutor()throws Exception{try(ServiceFixture f=new ServiceFixture()){
        String grant=f.sql.grant();f.worker.shutdown();JSONObject saved=f.answer("Keep the answer when the original worker is unavailable");
        assertTrue(saved.getBoolean("accepted"));assertFalse(saved.optBoolean("continuationRequested"));assertEquals("saved_owner_action_required",saved.getString("continuationState"));
        f.releaseWorker.countDown();assertTrue(f.worker.awaitTermination(5,TimeUnit.SECONDS));shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,f.sql.savedCount());assertTrue(f.sql.original.isPaused());assertEquals(grant,f.sql.grant());f.sql.requireUnclaimed();
        JSONObject receipt=f.answer("Keep the answer when the original worker is unavailable");assertTrue(receipt.getBoolean("duplicate"));assertFalse(receipt.getBoolean("continuationRequested"));assertEquals(1,f.sql.savedCount());
    }}

    @Test @Config(application=EngineApplication.class)
    public void lockedDeviceOrMissingLiveApplicationHoldOnlyReturnsOriginalTaskNavigation()throws Exception{try(ServiceFixture f=new ServiceFixture()){
        var keyguard=shadowOf(f.app.getSystemService(android.app.KeyguardManager.class));keyguard.setIsDeviceLocked(true);
        try{JSONObject locked=f.answer("A locked device cannot answer the original phone question");assertFalse(locked.getBoolean("accepted"));assertTrue(locked.getBoolean("expired"));assertEquals(0,f.sql.savedCount());}
        finally{keyguard.setIsDeviceLocked(false);}
        OwnerPhoneQuestions live=(OwnerPhoneQuestions)get(f.app,"ownerPhoneQuestions");live.released(f.sql.id,f.sql.original);
        JSONObject result=f.answer("A persisted notice is not a live worker");assertFalse(result.getBoolean("accepted"));assertTrue(result.getBoolean("expired"));assertEquals("open_original_task",result.getString("ownerAction"));
        f.drain();assertEquals(0,f.sql.savedCount());f.sql.requireUnclaimed();
    }}

    @Test @Config(application=EngineApplication.class)
    public void physicalLossAfterAppClaimCannotFallBackToANewPhonePort()throws Exception{try(ServiceFixture f=new ServiceFixture()){
        String grant=f.sql.grant();OwnerPhoneQuestions live=(OwnerPhoneQuestions)get(f.app,"ownerPhoneQuestions");
        OwnerPhoneQuestions.Held held=live.find(f.sql.id,f.sql.run,f.sql.epoch,f.sql.request,f.sql.revision);assertNotNull(held);
        f.sql.tasks.consumeOwnerQuestionReply(f.sql.id,f.sql.run,f.sql.epoch,f.sql.request,f.sql.revision,f.exact.getString("noticeToken"),"Answer before the physical-loss race");
        AtomicInteger claims=new AtomicInteger();f.app.continueOwnerPhoneQuestion(held,()->{
            assertFalse(f.sql.original.isPaused());set(f.service,"waitingForUser",false);set(f.service,"ownerWaitGeneration",held.waitGeneration()+1);claims.incrementAndGet();
        });
        assertEquals(1,claims.get());assertNull(live.find(f.sql.id,f.sql.run,f.sql.epoch,f.sql.request,f.sql.revision));
        assertEquals("claimed",f.sql.tasks.get(f.sql.id,false).getJSONObject("ownerQuestionContinuation").getString("phase"));
        // This live callback runs at the actual short native-dispatch fence, after SQL/App
        // claim. It reproduces release between admission and physical-port consumption.
        f.sql.original.setAuthorityCheck(()->set(f.service,"heldPort",null));
        Method run=PhoneRunService.class.getDeclaredMethod("run",String.class,String.class,int.class,String.class,ModelConfig.class,RunControl.class,int.class,boolean.class,OwnerPhoneQuestions.Held.class);
        run.setAccessible(true);run.invoke(f.service,f.sql.id,f.sql.run,f.sql.epoch,"Original phone fixture",f.sql.model,f.sql.original,1,false,held);
        JSONObject task=f.sql.tasks.get(f.sql.id,false);assertEquals("awaiting_user",task.getString("status"));assertEquals("unavailable",task.getJSONObject("ownerQuestionContinuation").getString("phase"));
        assertTrue("a same-run physical loss must leave the original controller paused",f.sql.original.isPaused());
        assertFalse(task.getJSONObject("ownerInputMailbox").getBoolean("open"));assertNull(get(f.service,"heldPort"));assertEquals(grant,f.sql.grant());assertEquals(1,f.sql.runCount());f.sql.physical.assertUnused();
        f.sql.original.setAuthorityCheck(()->{});f.drain();
        // Deliver the existing normal resume command and the actual CONTINUE Intent that
        // App.resume emitted. Missing held-port state must remain navigation-only here too.
        Intent resume=PhoneNotificationControls.command(f.app,PhoneNotificationControls.RESUME,f.sql.tasks.get(f.sql.id,false));
        f.service.onStartCommand(resume,0,2);Intent continuation=null;
        for(int i=0;i<20;i++){Intent started=shadowOf(f.app).getNextStartedService();if(started==null)break;
            if(PhoneRunService.CONTINUE.equals(started.getAction())&&f.sql.id.equals(started.getStringExtra("taskId"))){continuation=started;break;}}
        assertNotNull("normal resume must identify the original Service continuation",continuation);f.service.onStartCommand(continuation,0,3);f.drain();
        assertTrue("ordinary CONTINUE cannot reconstruct a missing original physical port",f.sql.original.isPaused());
        assertEquals("awaiting_user",f.sql.tasks.get(f.sql.id,false).getString("status"));assertNull(get(f.service,"heldPort"));
        assertEquals(1,f.sql.runCount());assertEquals(grant,f.sql.grant());assertEquals(0,f.sql.count("operations"));f.sql.physical.assertUnused();
        Intent stop=PhoneNotificationControls.command(f.app,PhoneRunService.STOP,f.sql.tasks.get(f.sql.id,false));
        f.service.onStartCommand(stop,0,4);shadowOf(Looper.getMainLooper()).idle();f.drain();
        assertTrue(f.sql.original.isCancelled());assertNull("original stop must release Application ownership",f.app.activeId());
        assertNull("original stop must release Service ownership",get(f.service,"runningId"));assertEquals("cancelled",f.sql.runState());
        assertEquals(0,f.sql.count("leases"));assertEquals(1,f.sql.savedCount());assertEquals(grant,f.sql.grant());f.sql.physical.assertUnused();
    }}

    @Test @Config(application=EngineApplication.class)
    public void ownerPauseAfterServiceClaimRetainsLaterPauseAndOriginalStopCleanup()throws Exception{try(ServiceFixture f=new ServiceFixture()){
        String grant=f.sql.grant(),scope=TaskStore.capabilityFingerprint(f.sql.tasks.get(f.sql.id,false).getJSONObject("executionScope"));
        OwnerPhoneQuestions live=(OwnerPhoneQuestions)get(f.app,"ownerPhoneQuestions");
        OwnerPhoneQuestions.Held held=live.find(f.sql.id,f.sql.run,f.sql.epoch,f.sql.request,f.sql.revision);assertNotNull(held);
        CountDownLatch claimReached=new CountDownLatch(1),releaseFence=new CountDownLatch(1);AtomicInteger gates=new AtomicInteger();
        f.sql.original.setAuthorityCheck(()->{
            // The real worker has finished App/SQL claim and its short Service dispatch.
            // Pause from the owner thread while the later physical-entry check is pending.
            if(!f.sql.original.isPaused()&&Boolean.FALSE.equals(get(f.service,"waitingForUser"))
                    &&((Number)get(f.service,"ownerWaitGeneration")).longValue()==held.waitGeneration()+1&&gates.compareAndSet(0,1)){
                claimReached.countDown();if(!releaseFence.await(5,TimeUnit.SECONDS))throw new SecurityException("owner-pause fixture did not release the original fence");
            }
        });
        JSONObject accepted=f.answer("Keep this answer while the owner pauses after claim");assertTrue(accepted.getBoolean("continuationRequested"));
        long laterPause;
        try{
            f.releaseWorker.countDown();assertTrue("original Service never reached the post-claim entry",claimReached.await(5,TimeUnit.SECONDS));
            assertEquals("claimed",f.sql.tasks.get(f.sql.id,false).getJSONObject("ownerQuestionContinuation").getString("phase"));
            assertFalse(f.sql.original.isPaused());assertEquals(Boolean.FALSE,get(f.service,"waitingForUser"));
            Intent pause=PhoneNotificationControls.command(f.app,PhoneNotificationControls.PAUSE,f.sql.tasks.get(f.sql.id,false));
            f.service.onStartCommand(pause,0,2);laterPause=f.sql.original.pauseRevision();assertTrue(laterPause>held.pauseRevision());assertTrue(f.sql.original.isPaused());
        }finally{releaseFence.countDown();}
        f.drain();f.sql.original.setAuthorityCheck(()->{});
        JSONObject task=f.sql.tasks.get(f.sql.id,false);assertEquals("awaiting_user",task.getString("status"));
        assertEquals("unavailable",task.getJSONObject("ownerQuestionContinuation").getString("phase"));assertFalse(task.getJSONObject("ownerInputMailbox").getBoolean("open"));
        assertTrue("the later owner pause must win",f.sql.original.isPaused());assertEquals("cleanup must not replace the later pause revision",laterPause,f.sql.original.pauseRevision());
        assertEquals("a failed continuation must remain stoppable",Boolean.TRUE,get(f.service,"waitingForUser"));assertSame(f.sql.physical,get(f.service,"heldPort"));
        assertEquals(held.waitGeneration()+2,((Number)get(f.service,"ownerWaitGeneration")).longValue());
        assertEquals(grant,f.sql.grant());assertEquals(scope,TaskStore.capabilityFingerprint(task.getJSONObject("executionScope")));assertEquals(1,f.sql.runCount());assertEquals(1,f.sql.savedCount());f.sql.physical.assertUnused();
        // Queue a subsequent explicit normal resume on this same original worker, and
        // verify its generation fences the old failure callback before stopping it.
        CountDownLatch normalWorkerStarted=new CountDownLatch(1),releaseNormalWorker=new CountDownLatch(1);
        f.worker.execute(()->{normalWorkerStarted.countDown();try{if(!releaseNormalWorker.await(5,TimeUnit.SECONDS))throw new AssertionError("normal resume fixture not released");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        assertTrue(normalWorkerStarted.await(5,TimeUnit.SECONDS));
        try{
            f.service.onStartCommand(PhoneNotificationControls.command(f.app,PhoneNotificationControls.RESUME,task),0,3);Intent continuation=null;
            for(int i=0;i<20;i++){Intent started=shadowOf(f.app).getNextStartedService();if(started==null)break;
                if(PhoneRunService.CONTINUE.equals(started.getAction())&&f.sql.id.equals(started.getStringExtra("taskId"))){continuation=started;break;}}
            assertNotNull("normal resume must address the original Service",continuation);f.service.onStartCommand(continuation,0,4);
            long normalGeneration=((Number)get(f.service,"ownerWaitGeneration")).longValue();assertEquals(held.waitGeneration()+3,normalGeneration);
            assertFalse(f.sql.original.isPaused());assertEquals(Boolean.FALSE,get(f.service,"waitingForUser"));assertSame(f.sql.physical,get(f.service,"heldPort"));
            Method failed=PhoneRunService.class.getDeclaredMethod("ownerPhoneQuestionFailed",OwnerPhoneQuestions.Held.class,boolean.class);failed.setAccessible(true);failed.invoke(f.service,held,true);
            assertFalse("an older failed continuation cannot pause the newer normal resume",f.sql.original.isPaused());assertEquals(Boolean.FALSE,get(f.service,"waitingForUser"));assertEquals(normalGeneration,((Number)get(f.service,"ownerWaitGeneration")).longValue());
            f.service.onStartCommand(PhoneNotificationControls.command(f.app,PhoneRunService.STOP,f.sql.tasks.get(f.sql.id,false)),0,5);
            assertTrue(f.sql.original.isCancelled());shadowOf(Looper.getMainLooper()).idle();
        }finally{releaseNormalWorker.countDown();}
        f.drain();assertNull("original stop must release Application ownership",f.app.activeId());assertNull("original stop must release Service ownership",get(f.service,"runningId"));
        assertNull(get(f.service,"heldPort"));assertEquals("cancelled",f.sql.runState());assertEquals(0,f.sql.count("leases"));assertEquals(0,f.sql.count("operations"));
        assertEquals(1,f.sql.runCount());assertEquals(1,f.sql.savedCount());assertEquals(grant,f.sql.grant());assertEquals(scope,TaskStore.capabilityFingerprint(f.sql.tasks.get(f.sql.id,false).getJSONObject("executionScope")));f.sql.physical.assertUnused();
    }}

    @Test @Config(application=EngineApplication.class)
    public void originalPhoneQuestionUiHidesPendingAndForgottenSourcesWithoutErasingAudit()throws Exception{try(ServiceFixture f=new ServiceFixture()){
        String answer="PHONE_OWNER_ANSWER_SOURCE_SENTINEL",audit="PHONE_OWNER_AUDIT_SOURCE_SENTINEL",grant=f.sql.grant();
        JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        var memory=new dev.pocketengine.features.memory.MemoryBook(new java.io.File(f.app.getFilesDir(),"phone-question-source-"+UUID.randomUUID()+".json"));
        JSONObject used=memory.save(scope,new JSONObject().put("text","Original formal source for this phone wait"),1);
        OwnerSourceRunContext.formalPrepared(f.sql.db,f.sql.id,f.sql.run,new JSONArray().put(new JSONObject().put("id",used.getString("id")).put("revision",used.getLong("revision"))));
        f.sql.tasks.consumeOwnerQuestionReply(f.sql.id,f.sql.run,f.sql.epoch,f.sql.request,f.sql.revision,f.exact.getString("noticeToken"),answer);
        f.sql.tasks.event(f.sql.id,new JSONObject().put("type","phone_question_projection_probe").put("runId",f.sql.run).put("coordinatorEpoch",f.sql.epoch).put("text",audit));
        String originalDocument=f.sql.column("SELECT document FROM tasks WHERE task_id=?",f.sql.id);
        String originalAudit=f.sql.column("SELECT event_json FROM task_events WHERE task_id=? AND event_type='phone_question_projection_probe'",f.sql.id);
        assertEquals(answer,OwnerPhoneQuestions.pageQuestion(f.sql.tasks.getForUi(f.sql.id,false)).getString("answerText"));
        assertEquals("Which phone option?",phoneSummary(f.sql).getString("summary"));
        String pending=UUID.randomUUID().toString();OwnerSourceRunContext.reserveOutput(f.sql.db,f.sql.id,f.sql.run,pending);
        assertPhoneSourceUnavailable(f.sql.tasks.getForUi(f.sql.id,true),f.sql,answer,audit);assertPhoneSourceUnavailable(phoneSummary(f.sql),f.sql,answer,audit);
        OwnerSourceRunContext.outputNotDispatched(f.sql.db,f.sql.id,f.sql.run,pending);
        assertEquals(answer,OwnerPhoneQuestions.pageQuestion(f.sql.tasks.getForUi(f.sql.id,false)).getString("answerText"));
        JSONObject unrelated=memory.save(scope,new JSONObject().put("text","Unrelated later formal source"),2);
        memory.forgetReviewed(scope,unrelated.getString("id"),unrelated.getLong("revision"),gone->f.sql.tasks.invalidateMemoryContexts(dev.pocketengine.features.memory.MemoryBook.Scope.from(scope),gone));
        assertEquals(answer,OwnerPhoneQuestions.pageQuestion(f.sql.tasks.getForUi(f.sql.id,false)).getString("answerText"));
        memory.forgetReviewed(scope,used.getString("id"),used.getLong("revision"),gone->f.sql.tasks.invalidateMemoryContexts(dev.pocketengine.features.memory.MemoryBook.Scope.from(scope),gone));
        assertPhoneSourceUnavailable(f.sql.tasks.getForUi(f.sql.id,false),f.sql,answer,audit);assertPhoneSourceUnavailable(phoneSummary(f.sql),f.sql,answer,audit);
        JSONObject nativeRead=f.app.dispatchOwnerAction("getTask",new JSONObject().put("taskId",f.sql.id)).getJSONObject("task");assertPhoneSourceUnavailable(nativeRead,f.sql,answer,audit);
        java.util.concurrent.atomic.AtomicReference<JSONObject> observed=new java.util.concurrent.atomic.AtomicReference<>();
        EngineApplication.Observer listener=event->{JSONObject task=event.optJSONObject("task");if("task".equals(event.optString("type"))&&task!=null&&f.sql.id.equals(task.optString("id")))observed.set(task);};
        f.app.addObserver(listener);try{f.app.pushTask(f.sql.id);shadowOf(Looper.getMainLooper()).idle();assertNotNull(observed.get());assertPhoneSourceUnavailable(observed.get(),f.sql,answer,audit);}finally{f.app.removeObserver(listener);}
        JSONObject raw=f.sql.tasks.get(f.sql.id,false);assertEquals("Which phone option?",raw.getJSONObject("evidence").getJSONObject("ownerQuestion").getString("question"));assertEquals(answer,raw.getJSONArray("ownerInputs").getJSONObject(0).getString("content"));assertTrue(raw.getJSONArray("events").toString().contains(audit));
        assertEquals(originalDocument,f.sql.column("SELECT document FROM tasks WHERE task_id=?",f.sql.id));assertEquals(originalAudit,f.sql.column("SELECT event_json FROM task_events WHERE task_id=? AND event_type='phone_question_projection_probe'",f.sql.id));
        assertEquals(grant,f.sql.grant());assertEquals(1,f.sql.runCount());assertEquals(1,f.sql.savedCount());assertTrue(f.sql.original.isPaused());f.sql.physical.assertUnused();
    }}

    private static JSONObject phoneSummary(Fixture f)throws Exception{
        JSONArray tasks=f.tasks.taskSummaryPage("",20).getJSONArray("tasks");for(int i=0;i<tasks.length();i++)if(f.id.equals(tasks.getJSONObject(i).optString("id")))return tasks.getJSONObject(i);throw new AssertionError("original phone summary missing");
    }
    private static void assertPhoneSourceUnavailable(JSONObject view,Fixture f,String answer,String audit)throws Exception{
        assertEquals("source_unavailable",view.getString("taskProjection"));assertFalse(view.getBoolean("auditBodyIncluded"));assertFalse(view.getBoolean("executionAuthorized"));
        assertEquals(f.id,view.getString("id"));assertEquals(f.run,view.getString("runId"));assertEquals(f.epoch,view.getInt("coordinatorEpoch"));assertEquals("awaiting_user",view.getString("status"));
        for(String body:new String[]{"evidence","ownerInputs","events","operations","historyEventPage","historyOperationPage"})assertFalse(body,view.has(body));
        for(String body:new String[]{"Which phone option?",answer,audit})assertFalse("retired body escaped its original source gate",view.toString().contains(body));
        assertNull(OwnerPhoneQuestions.pageQuestion(view));assertNull(dev.pocketengine.features.conversation.TaskPresentation.fromTaskStore(view).ownerQuestion);
        assertEquals(f.id,PhoneNotificationControls.command(f.app,PhoneRunService.STOP,view).getStringExtra("taskId"));
    }

    private static Notification.Action answerAction(Application app,JSONObject task)throws Exception{
        Notification.Builder builder=new Notification.Builder(app,"fixture-phone");OwnerPhoneQuestionNotification.add(builder,app,task);
        Notification notification=builder.build();assertNotNull(notification.actions);assertEquals(1,notification.actions.length);return notification.actions[0];
    }
    private static Intent answerIntent(Notification.Action action,String text){
        Intent intent=new Intent(shadowOf(action.actionIntent).getSavedIntent());Bundle answer=new Bundle();answer.putCharSequence(OwnerQuestionReply.KEY,text);
        RemoteInput.addResultsToIntent(action.getRemoteInputs(),intent,answer);return intent;
    }
    private static void set(Object target,String field,Object value)throws Exception{Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
    private static Object get(Object target,String field)throws Exception{Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(target);}
}
