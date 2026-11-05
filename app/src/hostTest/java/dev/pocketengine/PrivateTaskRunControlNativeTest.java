package dev.pocketengine;

import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=PrivateTaskRunControlNativeTest.PresentationBoundary.class)
public final class PrivateTaskRunControlNativeTest {
    @Implements(EngineApplication.class)
    public static final class PresentationBoundary extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){}
        @Implementation protected void pushTask(String id){}
    }
    /** Controls pickup, not the native dispatch, TaskStore, run admission or RunControl. */
    private static final class Queue extends AbstractExecutorService {
        final List<Runnable> pending=new ArrayList<>();boolean closed;
        @Override public void execute(Runnable work){if(closed)throw new java.util.concurrent.RejectedExecutionException();pending.add(work);}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;var result=new ArrayList<>(pending);pending.clear();return result;}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed&&pending.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
        void pickUp(){assertEquals(1,pending.size());pending.remove(0).run();}
    }
    private EngineApplication app;private Queue workers;private final List<JSONObject> events=new ArrayList<>();private final List<RunControl> childStops=new ArrayList<>();
    private static void set(EngineApplication app,String name,Object value)throws Exception{Field field=EngineApplication.class.getDeclaredField(name);field.setAccessible(true);field.set(app,value);}
    private static Object get(EngineApplication app,String name)throws Exception{Field field=EngineApplication.class.getDeclaredField(name);field.setAccessible(true);return field.get(app);}
    @Before public void prepare()throws Exception{
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.tasks=new TaskStore(app);dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);app.settings=new SettingsStore(app);app.features=new FeatureHost(ignored->{},app.tasks,()->false);
        app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","https://example.invalid").putString("model","fixture-no-network").putString("provider","openai_chat").commit();
        app.setOriginalDeviceChildStops((id,original)->{assertTrue(original.isCancelled());assertTrue(original.hasHostRun());assertEquals(id,original.hostRunIdentity().taskId());childStops.add(original);});
        app.addObserver(events::add);((ExecutorService)get(app,"capabilityWorkers")).shutdownNow();workers=new Queue();set(app,"capabilityWorkers",workers);
    }
    @After public void close()throws Exception{workers.shutdownNow();app.features.close();shadowOf(Looper.getMainLooper()).idle();Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(app.tasks)).close();}
    private JSONObject start(boolean phone)throws Exception{
        JSONObject task=phone?app.tasks.create("original private phone","background"):app.tasks.createCapabilityTask("original private assistant","");
        app.tasks.initializeExecutionScope(task.getString("id"),4);
        return app.tasks.startAttempt(task.getString("id"),phone?"background":"capabilities",4);
    }
    private JSONObject review(JSONObject task)throws Exception{return new JSONObject().put("taskId",task.getString("id")).put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch")).put("screenConsent",true).put("executionMode",task.getString("executionMode")).put("feedback","original reviewed feedback");}
    private void settle(JSONObject task)throws Exception{app.tasks.endAttempt(task.getString("id"),task.getString("runId"),"awaiting_user","waiting for owner",new JSONObject());}
    private JSONObject successor(JSONObject original,boolean phone)throws Exception{settle(original);return app.tasks.startAttempt(original.getString("id"),phone?"background":"capabilities",4);}
    private RunControl phoneControl(JSONObject task)throws Exception{RunControl control=new RunControl();control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));control.pause();set(app,"activeTaskId",task.getString("id"));set(app,"activeControl",control);app.tasks.status(task.getString("id"),"paused","original phone paused");return control;}
    @SuppressWarnings("unchecked") private RunControl capabilityControl(JSONObject task)throws Exception{RunControl control=new RunControl();control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));((java.util.concurrent.ConcurrentHashMap<String,RunControl>)get(app,"capabilityControls")).put(task.getString("id"),control);return control;}
    private void assertIdentity(JSONObject expected)throws Exception{JSONObject actual=app.tasks.get(expected.getString("id"),false);assertEquals(expected.getString("runId"),actual.getString("runId"));assertEquals(expected.getInt("coordinatorEpoch"),actual.getInt("coordinatorEpoch"));}
    private void runReserved(JSONObject task,RunControl original)throws Exception{
        var run=EngineApplication.class.getDeclaredMethod("runCapabilityAttempt",String.class,org.json.JSONArray.class,dev.pocketengine.core.ModelConfig.class,RunControl.class);run.setAccessible(true);
        try{run.invoke(app,task.getString("id"),null,app.settings.get(),original);}
        catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception cause)throw cause;if(failed.getCause() instanceof Error cause)throw cause;throw failed;}
    }
    private void assertReservedRunSettled(JSONObject task,String status)throws Exception{
        assertIdentity(task);assertEquals(status,app.tasks.get(task.getString("id"),false).getString("status"));
        Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)field.get(app.tasks);
        try(var rows=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=?",new String[]{task.getString("id"),task.getString("runId")})){assertTrue(rows.moveToFirst());assertEquals(status,rows.getString(0));}
        try(var leases=db.rawQuery("SELECT COUNT(*) FROM leases WHERE run_id=?",new String[]{task.getString("runId")})){assertTrue(leases.moveToFirst());assertEquals(0,leases.getInt(0));}
        assertNull(((java.util.Map<?,?>)get(app,"capabilityControls")).get(task.getString("id")));
    }

    @Test public void cancellationBeforeRunnerAdmissionSettlesTheAlreadyReservedRun()throws Exception{
        JSONObject task=start(false);RunControl original=capabilityControl(task);app.tasks.requestCancel(task.getString("id"),task.getString("runId"));original.cancel();
        assertThrows(InterruptedException.class,()->runReserved(task,original));
        assertReservedRunSettled(task,"cancelled");assertEquals(0,app.tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length());
    }
    @Test public void revokedAuthorityBeforeRunnerCannotLeaveAnOrphanPreparingRun()throws Exception{
        JSONObject task=start(false);RunControl original=capabilityControl(task);original.setAuthorityCheck(()->{throw new SecurityException("fixture original admission revoked");});
        assertThrows(InterruptedException.class,()->runReserved(task,original));
        assertReservedRunSettled(task,"cancelled");assertEquals(0,app.tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length());
    }
    @Test public void preRunnerCleanupPreservesUnknownEffectsForReconciliation()throws Exception{
        JSONObject task=start(false);String id=task.getString("id"),run=task.getString("runId");RunControl original=capabilityControl(task);
        app.tasks.authorizeTools(id,run,new org.json.JSONArray().put("workspace_write"));
        String operation=app.tasks.journal(id,run,task.getInt("coordinatorEpoch"),original).beginOperation("workspace_write",new JSONObject().put("path","unconfirmed.txt"));
        original.cancel();assertThrows(InterruptedException.class,()->runReserved(task,original));
        assertReservedRunSettled(task,"reconciling");assertTrue(app.tasks.hasUnresolved(id));
        assertEquals(operation,app.tasks.get(id,false).getJSONArray("operations").getJSONObject(0).getString("operationId"));
    }
    @Test public void lateReservationCleanupCannotSettleOrCancelItsSuccessor()throws Exception{
        JSONObject task=start(false);RunControl original=capabilityControl(task);JSONObject next=successor(task,false);RunControl replacement=capabilityControl(next);
        String before=TaskStore.capabilityFingerprint(app.tasks.get(next.getString("id"),false));
        var close=EngineApplication.class.getDeclaredMethod("closeReservedAssistantAttempt",RunControl.class,String.class);close.setAccessible(true);close.invoke(app,original,"late original cleanup");
        assertTrue(original.isCancelled());assertFalse(replacement.isCancelled());assertIdentity(next);
        assertSame(replacement,((java.util.Map<?,?>)get(app,"capabilityControls")).get(next.getString("id")));
        assertEquals(before,TaskStore.capabilityFingerprint(app.tasks.get(next.getString("id"),false)));
    }

    @Test public void stalePrivatePhoneStopCannotCancelTheSuccessor()throws Exception{
        JSONObject original=start(true),input=review(original);JSONObject next=successor(original,true);RunControl replacement=phoneControl(next);String before=TaskStore.capabilityFingerprint(app.tasks.get(next.getString("id"),false));
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",input));
        assertFalse(replacement.isCancelled());assertTrue(replacement.isPaused());assertTrue(childStops.isEmpty());assertIdentity(next);assertEquals(before,TaskStore.capabilityFingerprint(app.tasks.get(next.getString("id"),false)));
    }
    @Test public void stalePrivateAssistantStopCannotSelectTheSuccessorControl()throws Exception{
        JSONObject original=start(false),input=review(original);JSONObject next=successor(original,false);RunControl replacement=capabilityControl(next);String before=TaskStore.capabilityFingerprint(app.tasks.get(next.getString("id"),false));
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",input));
        assertFalse(replacement.isCancelled());assertTrue(childStops.isEmpty());assertIdentity(next);assertEquals(before,TaskStore.capabilityFingerprint(app.tasks.get(next.getString("id"),false)));
    }
    @Test public void stalePrivatePhoneResumeCannotResumeOrWriteFeedbackIntoTheSuccessor()throws Exception{
        JSONObject original=start(true),input=review(original);JSONObject next=successor(original,true);RunControl replacement=phoneControl(next);
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("resumeTask",input));
        assertTrue(replacement.isPaused());assertIdentity(next);assertEquals("",app.tasks.get(next.getString("id"),false).getString("feedback"));assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void stalePrivateAssistantResumeCannotWriteFeedbackOrQueueTheSuccessor()throws Exception{
        JSONObject original=start(false),input=review(original);JSONObject next=successor(original,false);settle(next);
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("resumeTask",input));
        assertTrue(workers.pending.isEmpty());assertIdentity(next);assertEquals("",app.tasks.get(next.getString("id"),false).getString("feedback"));
    }
    @Test public void aSecondQueuePickupCannotCreateAnotherRunAfterNativeResumeAdmissionChanges()throws Exception{
        JSONObject original=start(false);settle(original);String id=original.getString("id");app.dispatchOwnerAction("resumeTask",review(original));assertEquals(1,workers.pending.size());
        JSONObject admitted=app.tasks.get(id,false);JSONObject next=successor(admitted,false);settle(next);String before=TaskStore.capabilityFingerprint(app.tasks.get(id,false));workers.pickUp();shadowOf(Looper.getMainLooper()).idle();
        assertIdentity(next);assertEquals(before,TaskStore.capabilityFingerprint(app.tasks.get(id,false)));assertEquals(0,app.tasks.get(id,false).getJSONArray("operations").length());assertFalse(app.tasks.hasUnresolved(id));
    }
    @Test public void currentPrivateAssistantResumeReservesAndBindsItsActualRunBeforeWorkerPickup()throws Exception{
        JSONObject original=start(false);settle(original);String id=original.getString("id");app.dispatchOwnerAction("resumeTask",review(original));JSONObject admitted=app.tasks.get(id,false);
        assertNotEquals(original.getString("runId"),admitted.getString("runId"));assertEquals(original.getInt("coordinatorEpoch")+1,admitted.getInt("coordinatorEpoch"));assertEquals("preparing",admitted.getString("status"));
        JSONObject beforeScope=original.getJSONObject("executionScope"),afterScope=admitted.getJSONObject("executionScope");
        for(String key:new String[]{"revision","deadlineAt","toolLimit","modelCallLimit","toolsUsed","modelCallsUsed"})assertEquals(key,beforeScope.getLong(key),afterScope.getLong(key));
        assertFalse(afterScope.getBoolean("autoRefill"));
        RunControl control=(RunControl)((java.util.Map<?,?>)get(app,"capabilityControls")).get(id);assertNotNull(control);assertTrue(control.matchesHostRun(id,admitted.getString("runId"),admitted.getInt("coordinatorEpoch")));assertEquals("original reviewed feedback",admitted.getString("feedback"));assertEquals(1,workers.pending.size());
        control.cancel();workers.pickUp();assertIdentity(admitted);assertEquals(0,app.tasks.get(id,false).getJSONArray("operations").length());
    }
    @Test public void currentPrivatePhoneResumeKeepsItsCapturedRunAndController()throws Exception{
        JSONObject original=start(true);RunControl control=phoneControl(original);app.dispatchOwnerAction("resumeTask",review(original));assertFalse(control.isPaused());assertFalse(control.isCancelled());assertIdentity(original);
        Intent service=shadowOf(app).getNextStartedService();assertNotNull(service);assertEquals(PhoneRunService.CONTINUE,service.getAction());assertEquals("original reviewed feedback",app.tasks.get(original.getString("id"),false).getString("feedback"));
    }
    @Test public void currentPrivateStopsRetainTheSameOriginalChildCleanupControl()throws Exception{
        JSONObject phone=start(true);RunControl originalPhone=phoneControl(phone);app.dispatchOwnerAction("stopTask",review(phone));assertTrue(originalPhone.isCancelled());assertIdentity(phone);assertSame(originalPhone,childStops.get(0));
        JSONObject assistant=start(false);RunControl originalAssistant=capabilityControl(assistant);app.dispatchOwnerAction("stopTask",review(assistant));assertTrue(originalAssistant.isCancelled());assertIdentity(assistant);assertSame(originalAssistant,childStops.get(1));assertEquals(2,childStops.size());
        assertTrue(app.tasks.get(phone.getString("id"),false).getBoolean("cancelRequested"));assertTrue(app.tasks.get(assistant.getString("id"),false).getBoolean("cancelRequested"));
    }
    @Test public void matchingStoredTupleCannotCancelOrResumeAForeignPhoneController()throws Exception{
        JSONObject original=start(true);RunControl foreign=new RunControl();foreign.bindHostRun(original.getString("id"),"foreign-control-run",original.getInt("coordinatorEpoch")+1);foreign.pause();
        set(app,"activeTaskId",original.getString("id"));set(app,"activeControl",foreign);app.tasks.status(original.getString("id"),"paused","waiting");
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",review(original)));
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("resumeTask",review(original)));
        assertFalse(foreign.isCancelled());assertTrue(foreign.isPaused());assertIdentity(original);assertEquals("",app.tasks.get(original.getString("id"),false).getString("feedback"));
    }
    @Test public void missingPrivateIdentityNeverFallsBackToTheCurrentRun()throws Exception{
        JSONObject original=start(true);RunControl control=phoneControl(original);String id=original.getString("id");
        for(String action:List.of("stopTask","resumeTask")){
            assertThrows(SecurityException.class,()->app.dispatchOwnerAction(action,new JSONObject().put("taskId",id)));
        }
        assertFalse(control.isCancelled());assertTrue(control.isPaused());assertIdentity(original);
    }
    @Test public void aCoercedPrivateEpochIsNotAnOriginalNativeIdentity()throws Exception{
        JSONObject original=start(true);RunControl control=phoneControl(original);
        for(String action:List.of("stopTask","resumeTask"))assertThrows(SecurityException.class,()->app.dispatchOwnerAction(action,review(original).put("epoch",String.valueOf(original.getInt("coordinatorEpoch")))));
        assertFalse(control.isCancelled());assertTrue(control.isPaused());assertIdentity(original);
    }
    @Test public void anUnstartedPrivateReviewCannotInventAnOriginalRun()throws Exception{
        JSONObject original=app.tasks.createCapabilityTask("unstarted private review","");String id=original.getString("id");
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("resumeTask",review(original)));
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",review(original)));
        assertTrue(workers.pending.isEmpty());JSONObject actual=app.tasks.get(id,false);assertEquals("",actual.optString("runId"));assertEquals(0,actual.optInt("coordinatorEpoch"));assertEquals("",actual.getString("feedback"));
    }
    @Test public void legacyPrivateAssistantRequiresReviewedCumulativeScopeBeforeResume()throws Exception{
        JSONObject task=app.tasks.createCapabilityTask("legacy original without cumulative scope","");
        JSONObject original=app.tasks.startAttempt(task.getString("id"),"capabilities",4);settle(original);
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("resumeTask",review(original)));
        assertThrows(SecurityException.class,()->app.tasks.startReviewedPrivateTaskAttempt(review(original),"capabilities",4,"must not be saved",new RunControl()));
        assertTrue(workers.pending.isEmpty());assertIdentity(original);
        JSONObject actual=app.tasks.get(original.getString("id"),false);assertFalse(actual.has("executionScope"));assertEquals("",actual.getString("feedback"));
    }

    @Test public void exactPrivateResumeKeepsTheOriginalUnresolvedEffectAndDoesNotQueue()throws Exception{
        JSONObject original=start(false);String id=original.getString("id");RunControl control=new RunControl();control.bindHostRun(id,original.getString("runId"),original.getInt("coordinatorEpoch"));
        app.tasks.authorizeTools(id,original.getString("runId"),new org.json.JSONArray().put("workspace_write"));String operation=app.tasks.journal(id,original.getString("runId"),original.getInt("coordinatorEpoch"),control).beginOperation("workspace_write",new JSONObject().put("path","unresolved.txt"));
        assertThrows(IllegalStateException.class,()->app.dispatchOwnerAction("resumeTask",review(original)));assertTrue(workers.pending.isEmpty());assertIdentity(original);assertTrue(app.tasks.hasUnresolved(id));
        assertEquals(operation,app.tasks.get(id,false).getJSONArray("operations").getJSONObject(0).getString("operationId"));assertEquals("",app.tasks.get(id,false).getString("feedback"));
    }

    @Test public void delayedServiceCleanupCannotStopAReplacementPhoneControl()throws Exception{
        JSONObject first=start(true);RunControl original=phoneControl(first);original.cancel();
        JSONObject next=successor(first,true);RunControl replacement=phoneControl(next);
        String before=TaskStore.capabilityFingerprint(app.tasks.get(next.getString("id"),false));
        app.stop(first.getString("id"),original);
        assertFalse(replacement.isCancelled());assertTrue(replacement.isPaused());assertIdentity(next);
        assertEquals(before,TaskStore.capabilityFingerprint(app.tasks.get(next.getString("id"),false)));assertTrue(childStops.isEmpty());
    }
    @Test public void serviceStopRechecksItsOriginalControlAfterSynchronousCancelReentry()throws Exception{
        JSONObject first=start(true);RunControl original=phoneControl(first);JSONObject[] next={null};RunControl[] replacement={null};
        original.onCancel(()->{try{next[0]=successor(first,true);replacement[0]=phoneControl(next[0]);}catch(Exception failure){throw new AssertionError(failure);}});
        app.stop(first.getString("id"),original);
        assertTrue(original.isCancelled());assertNotNull(replacement[0]);assertFalse(replacement[0].isCancelled());assertTrue(replacement[0].isPaused());assertIdentity(next[0]);
        JSONObject current=app.tasks.get(first.getString("id"),false);assertFalse(current.optBoolean("cancelRequested"));assertEquals("paused",current.getString("status"));assertTrue(childStops.isEmpty());
    }

    @Test public void synchronousPhoneCancelCannotRewriteOrEnqueueTheSuccessorControl()throws Exception{
        JSONObject original=start(true);RunControl owner=phoneControl(original);JSONObject[] next={null};RunControl[] replacement={null};
        owner.onCancel(()->{try{next[0]=successor(original,true);replacement[0]=phoneControl(next[0]);}catch(Exception failure){throw new AssertionError(failure);}});
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",review(original)));
        assertTrue(owner.isCancelled());assertNotNull(replacement[0]);assertFalse(replacement[0].isCancelled());assertTrue(replacement[0].isPaused());assertIdentity(next[0]);
        JSONObject current=app.tasks.get(original.getString("id"),false);assertFalse(current.optBoolean("cancelRequested"));assertEquals("paused",current.getString("status"));assertTrue(childStops.isEmpty());
    }
    @Test public void sameTaskMapDoesNotAuthorizeStoppingAnUnboundCapabilityControl()throws Exception{
        JSONObject original=start(false);RunControl unbound=new RunControl();
        @SuppressWarnings("unchecked") var controls=(java.util.concurrent.ConcurrentHashMap<String,RunControl>)get(app,"capabilityControls");controls.put(original.getString("id"),unbound);
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",review(original)));
        assertFalse(unbound.isCancelled());assertIdentity(original);assertFalse(app.tasks.get(original.getString("id"),false).optBoolean("cancelRequested"));assertTrue(childStops.isEmpty());
    }
    @Test public void synchronousCapabilityCancelCannotDeliverCleanupThroughTheSuccessor()throws Exception{
        JSONObject original=start(false);RunControl owner=capabilityControl(original);JSONObject[] next={null};RunControl[] replacement={null};
        owner.onCancel(()->{try{next[0]=successor(original,false);replacement[0]=capabilityControl(next[0]);}catch(Exception failure){throw new AssertionError(failure);}});
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",review(original)));
        assertTrue(owner.isCancelled());assertNotNull(replacement[0]);assertFalse(replacement[0].isCancelled());assertIdentity(next[0]);assertFalse(app.tasks.get(original.getString("id"),false).optBoolean("cancelRequested"));assertTrue(childStops.isEmpty());
    }

    @Test public void stoppingAReservedAssistantBeforePickupKeepsItsRealCancelledSettlement()throws Exception{
        JSONObject original=start(false);settle(original);String id=original.getString("id");app.dispatchOwnerAction("resumeTask",review(original));JSONObject admitted=app.tasks.get(id,false);
        app.dispatchOwnerAction("stopTask",review(admitted));JSONObject stopped=app.tasks.get(id,false);assertIdentity(admitted);assertEquals("cancelled",stopped.getString("status"));assertTrue(childStops.isEmpty());
        workers.pickUp();assertIdentity(admitted);assertEquals("cancelled",app.tasks.get(id,false).getString("status"));assertEquals(0,app.tasks.get(id,false).getJSONArray("operations").length());
    }

    @Test public void originalChildStopRetryNeverRewritesAnAlreadySettledOriginalResult()throws Exception{
        for(boolean phone:new boolean[]{false,true}){
            JSONObject original=start(phone);RunControl owner=phone?phoneControl(original):capabilityControl(original);String id=original.getString("id");
            app.setOriginalDeviceChildStops((task,cancelled)->{assertSame(owner,cancelled);throw new java.util.concurrent.RejectedExecutionException("controlled child queue refusal");});
            assertThrows(IllegalStateException.class,()->app.dispatchOwnerAction("stopTask",review(original)));assertTrue(owner.isCancelled());
            app.tasks.endAttempt(id,original.getString("runId"),"cancelled","original runner settled",new JSONObject());assertEquals("cancelled",app.tasks.get(id,false).getString("status"));
            app.setOriginalDeviceChildStops((task,cancelled)->{assertEquals(id,task);assertSame(owner,cancelled);childStops.add(cancelled);});
            app.dispatchOwnerAction("stopTask",review(original));assertIdentity(original);assertEquals("cancelled",app.tasks.get(id,false).getString("status"));assertSame(owner,childStops.get(childStops.size()-1));
        }
        assertEquals(2,childStops.size());
    }

    @Test public void synchronousSettlementOfTheSameRunKeepsItsRealTerminalResult()throws Exception{
        for(boolean phone:new boolean[]{false,true}){
            JSONObject original=start(phone);RunControl owner=phone?phoneControl(original):capabilityControl(original);String id=original.getString("id");
            owner.onCancel(()->{try{app.tasks.endAttempt(id,original.getString("runId"),"completed","original settled in cancellation callback",new JSONObject());}catch(Exception failure){throw new AssertionError(failure);}});
            app.dispatchOwnerAction("stopTask",review(original));assertIdentity(original);assertEquals("completed",app.tasks.get(id,false).getString("status"));assertSame(owner,childStops.get(childStops.size()-1));
        }
        assertEquals(2,childStops.size());
    }

    @Test public void synchronousControlReplacementIsRejectedEvenWhenTheStoredTupleIsUnchanged()throws Exception{
        for(boolean phone:new boolean[]{false,true}){
            JSONObject original=start(phone);RunControl owner=phone?phoneControl(original):capabilityControl(original);RunControl[] replacement={null};String id=original.getString("id");
            owner.onCancel(()->{try{replacement[0]=phone?phoneControl(original):capabilityControl(original);}catch(Exception failure){throw new AssertionError(failure);}});
            assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",review(original)));
            assertTrue(owner.isCancelled());assertNotNull(replacement[0]);assertFalse(replacement[0].isCancelled());assertIdentity(original);assertFalse(app.tasks.get(id,false).optBoolean("cancelRequested"));assertTrue(childStops.isEmpty());
        }
    }

}
