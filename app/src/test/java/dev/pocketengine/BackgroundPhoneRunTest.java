package dev.pocketengine;

import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.assistant.OwnerPhoneQuestions;
import dev.pocketengine.phone.PhoneNotificationControls;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

/** Real Application/Service and TaskStore identities, with already-admitted native controls.
 * No model, display helper or physical-screen isolation is simulated by these lifecycle tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BackgroundPhoneRunTest {
    private EngineApplication app;
    private ServiceController<PhoneRunService> controller;
    private final ScheduledExecutorService leases=Executors.newSingleThreadScheduledExecutor();
    private final List<Admitted> admissions=new ArrayList<>();
    private final List<BackgroundPhoneRun> sessions=new ArrayList<>();
    private int notificationId=1001;

    @Before public void setUp(){
        java.security.Security.addProvider(new java.security.Provider("BackgroundPhoneRunFixtureKeys",1.0d,"Test-only AndroidKeyStore"){{
            put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());
        }});
        app=(EngineApplication)RuntimeEnvironment.getApplication();
        controller=Robolectric.buildService(PhoneRunService.class).create();
    }
    @After public void tearDown()throws Exception {
        controller.destroy();
        for(BackgroundPhoneRun session:sessions)session.destroy();
        for(Admitted admitted:admissions)if(app.control(admitted.id)==admitted.control){
            admitted.control.cancel();app.tasks.endAttempt(admitted.id,admitted.run,"cancelled","test ended",new JSONObject());app.released(admitted.id,admitted.control);
        }
        leases.shutdownNow();shadowOf(Looper.getMainLooper()).idle();java.security.Security.removeProvider("BackgroundPhoneRunFixtureKeys");
    }

    @Test public void pausedAndReleasedRunCannotChangeAnotherRunsControlModelOrPort()throws Exception {
        Admitted a=admit(null),b=admit(null);Port portA=new Port(),portB=new Port();
        assertTrue(app.attachPort(a.id,portA,a.control));assertTrue(app.attachPort(b.id,portB,b.control));
        assertEquals(2,app.activePhoneRunCount());assertEquals(2,app.activePhoneRunIds().length());
        app.pause(a.id);
        assertTrue(a.control.isPaused());assertFalse(b.control.isPaused());
        assertEquals(1,portA.clears);assertEquals(0,portB.clears);
        assertSame(a.model,app.config(a.id));assertSame(b.model,app.config(b.id));
        a.control.cancel();app.tasks.endAttempt(a.id,a.run,"cancelled","A stopped",new JSONObject());app.released(a.id,a.control);
        assertNull(app.control(a.id));assertSame(b.control,app.control(b.id));assertFalse(b.control.isCancelled());
        assertEquals(1,app.activePhoneRunCount());assertTrue(app.resources.ownsBackgroundPhone(b.token));
        assertFalse(app.resources.acquirePhone("main-screen"));
        assertSame(portB,physical(b.id));
    }

    @Test public void anUnstartedDuplicateOwnsNoCancellationOrReleaseAuthority()throws Exception {
        Admitted a=admit(null),b=admit(null);BackgroundPhoneRun retained=session(a);
        BackgroundPhoneRun duplicate=new BackgroundPhoneRun(controller.get(),app,a.id,a.control,9991,2,leases,Runnable::run);
        duplicate.discardUnstarted();
        assertFalse(a.control.isCancelled());assertSame(a.control,app.control(a.id));assertSame(b.control,app.control(b.id));
        assertSame(retained,serviceRuns().get(a.id));
        assertTrue(app.resources.ownsBackgroundPhone(a.token));assertTrue(app.resources.ownsBackgroundPhone(b.token));
        assertTrue(((java.util.concurrent.ExecutorService)get(duplicate,"worker")).isShutdown());
    }

    @Test public void finishingOneRunRetainsTheOthersForegroundSessionAndAdmission()throws Exception {
        Admitted a=admit(null),b=admit(null);BackgroundPhoneRun first=session(a),second=session(b);
        finish(first,new RunOutcome("needs_confirmation","A result",new JSONObject()));
        assertSame(a.control,app.control(a.id)); // Admission stays until its main-thread ownership callback.
        assertSame(first,serviceRuns().get(a.id));assertEquals(2,app.activePhoneRunCount());
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(app.control(a.id));assertFalse(serviceRuns().containsKey(a.id));
        assertSame(second,serviceRuns().get(b.id));assertSame(b.control,app.control(b.id));
        assertFalse(b.control.isCancelled());assertFalse(((java.util.concurrent.ExecutorService)get(second,"worker")).isShutdown());
        assertEquals("preparing",app.tasks.get(b.id,false).getString("status"));
        assertTrue(app.resources.ownsBackgroundPhone(b.token));
    }

    @Test public void lateOldCleanupCannotFinishOrReleaseAReplacementOrASibling()throws Exception {
        Admitted old=admit(null),sibling=admit(null);BackgroundPhoneRun oldSession=session(old),other=session(sibling);
        oldSession.destroy();
        Admitted replacement=admit(old.id);BackgroundPhoneRun next=session(replacement);
        finish(oldSession,new RunOutcome("cancelled","old worker completion",new JSONObject().put("displayRelease",new JSONObject().put("releaseConfirmed",true))));
        Method complete=BackgroundPhoneRun.class.getDeclaredMethod("completeRuntime",int.class);complete.setAccessible(true);complete.invoke(oldSession,1);
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(replacement.control,app.control(old.id));assertFalse(replacement.control.isCancelled());
        assertSame(next,serviceRuns().get(old.id));assertSame(other,serviceRuns().get(sibling.id));
        JSONObject current=app.tasks.get(old.id,false);
        assertEquals(replacement.run,current.getString("runId"));assertEquals("preparing",current.getString("status"));
        assertTrue(app.resources.ownsBackgroundPhone(replacement.token));assertTrue(app.resources.ownsBackgroundPhone(sibling.token));
    }

    @Test public void notificationPausesOnlyTheIdentifiedBackgroundRun()throws Exception {
        Admitted a=admit(null),b=admit(null);session(a);session(b);
        Intent pause=PhoneNotificationControls.command(app,PhoneNotificationControls.PAUSE,app.tasks.get(b.id,false));
        controller.get().onStartCommand(pause,0,2);
        assertTrue(b.control.isPaused());assertFalse(a.control.isPaused());
        assertSame(a.control,app.control(a.id));assertSame(b.control,app.control(b.id));
        Intent mismatched=PhoneNotificationControls.command(app,PhoneNotificationControls.PAUSE,app.tasks.get(a.id,false)).putExtra("phoneRunId",b.run);
        controller.get().onStartCommand(mismatched,0,3);
        assertFalse(a.control.isPaused());assertFalse(a.control.isCancelled());
    }

    @Test public void twoOrdinaryQuestionsKeepTheirOriginalHeldPortsAndAnswerMailboxes()throws Exception {
        Admitted a=admit(null),b=admit(null);Question questionA=question(a),questionB=question(b);
        questionA.held.requireOriginal();questionB.held.requireOriginal();
        assertNotSame(questionA.held.physicalPort(),questionB.held.physicalPort());
        Method answer=EngineApplication.class.getDeclaredMethod("answerOwnerPhoneQuestion",JSONObject.class,String.class);answer.setAccessible(true);
        JSONObject receipt=(JSONObject)answer.invoke(app,questionA.exact,"只给第一个任务的回答");
        assertTrue(receipt.getBoolean("answerSaved"));assertTrue(receipt.getBoolean("continuationRequested"));
        assertEquals(1,questionA.continuations.get());assertEquals(0,questionB.continuations.get());
        assertEquals("只给第一个任务的回答",app.tasks.get(a.id,false).getJSONArray("ownerInputs").getJSONObject(0).getString("content"));
        JSONArray secondInputs=app.tasks.get(b.id,false).optJSONArray("ownerInputs");assertTrue(secondInputs==null||secondInputs.length()==0);
        a.control.cancel();app.tasks.endAttempt(a.id,a.run,"cancelled","A ended",new JSONObject());app.released(a.id,a.control);
        questionB.held.requireOriginal();assertSame(b.control,app.control(b.id));assertTrue(b.control.isPaused());
        assertFalse(b.control.isCancelled());assertEquals(0,questionB.continuations.get());
    }

    @Test public void automationReadUnderTaskStoreDoesNotWaitForApplicationAdmissionMonitor()throws Exception {
        Admitted admitted=admit(null);JSONObject task=app.tasks.get(admitted.id,false);
        // Reach the real model-selection branch; an earlier invalid-run rejection
        // would hide its separate config/control lock-order regression.
        app.tasks.checkExtensionRun(admitted.id,admitted.run,admitted.epoch,admitted.control);
        var plans=(dev.pocketengine.features.AutomationPlanHost)get(app,"automationPlans");
        var controls=(dev.pocketengine.features.AutomationPlanHost.CurrentControl)get(plans,"controls");
        Method config=EngineApplication.class.getDeclaredMethod("currentPhoneConfig",String.class,RunControl.class);config.setAccessible(true);
        var reader=Executors.newSingleThreadExecutor();java.util.concurrent.Future<Boolean> result=null;
        var entered=new java.util.concurrent.CountDownLatch(1);boolean blocked=false;
        try{
            synchronized(app){
                result=reader.submit(()->{
                    synchronized(app.tasks){
                        entered.countDown();assertSame(admitted.control,controls.get(admitted.id));
                        assertSame(admitted.model,config.invoke(app,admitted.id,admitted.control));
                        assertNull(config.invoke(app,admitted.id,new RunControl()));
                        // This fixture has no owner-selected live API connection. Its
                        // model may be refused; both actual native callbacks must return.
                        try{return controls.authorizationValid(task);}catch(Exception unavailableModel){return false;}
                    }
                });
                assertTrue("reader did not enter the original TaskStore monitor",entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
                try{result.get(1,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.TimeoutException inversion){blocked=true;}
            }
            // The App monitor is released before joining even when the regression
            // returns. A failure must not leave the test process in a deadlock.
            result.get(3,java.util.concurrent.TimeUnit.SECONDS);
        }finally{reader.shutdownNow();assertTrue(reader.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
        assertFalse("TaskStore control/model lookup attempted to acquire the Application monitor",blocked);
    }

    private record Admitted(String id,String run,int epoch,RunControl control,ModelConfig model,String token){}
    /** Only the Android admission shell is replaced; the TaskStore run and resource fence are real. */
    @SuppressWarnings("unchecked") private Admitted admit(String existing)throws Exception {
        String id=existing==null?app.tasks.create("后台生命周期回归","background").getString("id"):existing;
        if(existing==null)app.tasks.initializeExecutionScope(id,4);
        JSONObject task=app.tasks.startAttempt(id,"background",4);RunControl original=new RunControl();
        original.bindHostRun(id,task.getString("runId"),task.getInt("coordinatorEpoch"));
        ModelConfig model=new ModelConfig("https://model.invalid","fixture-key","fixture-model",false,4,5);String token=java.util.UUID.randomUUID().toString();
        assertTrue(app.resources.acquireBackgroundPhone(token));
        Class<?> type=Class.forName("dev.pocketengine.EngineApplication$BackgroundRun");Constructor<?> ctor=type.getDeclaredConstructor(RunControl.class,ModelConfig.class,String.class);ctor.setAccessible(true);
        ((Map<String,Object>)get(app,"backgroundRuns")).put(id,ctor.newInstance(original,model,token));
        Admitted admitted=new Admitted(id,task.getString("runId"),task.getInt("coordinatorEpoch"),original,model,token);admissions.add(admitted);return admitted;
    }
    private BackgroundPhoneRun session(Admitted admitted)throws Exception {
        BackgroundPhoneRun session=new BackgroundPhoneRun(controller.get(),app,admitted.id,admitted.control,notificationId++,1,leases,Runnable::run);
        serviceRuns().put(admitted.id,session);sessions.add(session);return session;
    }
    @SuppressWarnings("unchecked") private Map<String,BackgroundPhoneRun> serviceRuns()throws Exception{return (Map<String,BackgroundPhoneRun>)get(controller.get(),"backgroundRuns");}
    private Object physical(String id)throws Exception{Method port=EngineApplication.class.getDeclaredMethod("phonePort",String.class);port.setAccessible(true);return port.invoke(app,id);}
    private record Question(OwnerPhoneQuestions.Held held,JSONObject exact,AtomicInteger continuations){}
    private Question question(Admitted admitted)throws Exception {
        app.tasks.conversationRepository().initializeEmpty();Port port=new Port();assertTrue(app.attachPort(admitted.id,port,admitted.control));
        app.tasks.openPhoneOwnerInputMailbox(admitted.id,admitted.run,admitted.epoch,admitted.control);
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","original phone goal"))
                .put(new JSONObject().put("role","assistant").put("content","Which choice?"));
        app.tasks.journal(admitted.id,admitted.run,admitted.epoch,admitted.control).checkpoint(messages,1,new JSONObject());
        assertEquals(0,app.tasks.pollPhoneOwnerInputs(admitted.id,admitted.run,admitted.epoch,new JSONArray(),true,true,admitted.control).length());
        admitted.control.pause();String request=java.util.UUID.randomUUID().toString();
        JSONObject question=new JSONObject().put("format","pocketworld.owner-question.v1").put("taskId",admitted.id).put("runId",admitted.run).put("coordinatorEpoch",admitted.epoch)
                .put("requestId",request).put("waitRevision",2).put("question","Which choice?").put("replyKind","answer").put("approval",false).put("takeover",false);
        app.tasks.finish(admitted.id,"awaiting_user","Which choice?",new JSONObject().put("ownerQuestion",question));
        AtomicInteger continuations=new AtomicInteger();
        app.retainOwnerPhoneQuestion(admitted.id,admitted.control,port,admitted.model,1,new OwnerPhoneQuestions.PhysicalOwner(){
            public void requireHeld(OwnerPhoneQuestions.Held expected){
                if(expected.originalControl()!=admitted.control||expected.physicalPort()!=port||expected.capturedModel()!=admitted.model||expected.waitGeneration()!=1)throw new SecurityException("wrong original hold");
            }
            public void requestContinuation(OwnerPhoneQuestions.Held expected){requireHeld(expected);continuations.incrementAndGet();}
        });
        Method hosts=EngineApplication.class.getDeclaredMethod("phoneQuestions",String.class,RunControl.class);hosts.setAccessible(true);
        OwnerPhoneQuestions host=(OwnerPhoneQuestions)hosts.invoke(app,admitted.id,admitted.control);
        OwnerPhoneQuestions.Held held=host.find(admitted.id,admitted.run,admitted.epoch,request,2);assertNotNull(held);
        JSONObject view=OwnerPhoneQuestions.noticeView(app.tasks.get(admitted.id,false));
        return new Question(held,new JSONObject(view.getJSONObject("ownerQuestion").toString()).put("noticeToken",view.getString("token")),continuations);
    }
    private static void finish(BackgroundPhoneRun session,RunOutcome result)throws Exception {
        Method finish=BackgroundPhoneRun.class.getDeclaredMethod("finishAttempt",String.class,String.class,RunControl.class,RunOutcome.class,int.class);finish.setAccessible(true);
        finish.invoke(session,session.runningId,session.runningRunId,session.ownerControl,result,1);
    }
    private static Object get(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static final class Port implements PhonePort {
        int clears;
        public boolean isConnected(){return true;}
        public JSONObject observe(boolean image,RunControl original){throw new AssertionError("lifecycle fixture must not read a screen");}
        public JSONObject execute(String tool,JSONObject args,RunControl original){throw new AssertionError("lifecycle fixture must not dispatch an action");}
        public void clearReferences(){clears++;}
    }
}
