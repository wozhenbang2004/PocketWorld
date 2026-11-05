package dev.pocketengine;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

/** Actual admission, pickup, RunControl cancellation and SQL settlement; no model request. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows={AssistantInputCancellationNativeTest.Presentation.class,AssistantInputCancellationNativeTest.RelatedStops.class})
public final class AssistantInputCancellationNativeTest {
    @Implements(EngineApplication.class)
    public static final class Presentation extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){}
        @Implementation protected void pushTask(String id){}
    }
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object invoke(EngineApplication app,String name,Class<?>[] types,Object... args)throws Exception{
        Method m=EngineApplication.class.getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(app,args);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception e)throw e;throw failed;}
    }
    @Test public void cancellationAfterPickupBeforeAttemptAssignmentSettlesTheReservedRun()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();app.tasks=new TaskStore(app);ReadyApplicationFixture.complete(app);
        SQLiteDatabase db=(SQLiteDatabase)field(app.tasks,"db");RunControl original=new RunControl();
        try{
            String conversation=UUID.randomUUID().toString();ModelConfig model=new ModelConfig("http://127.0.0.1:1","","fixture",false,8,100);
            JSONObject message=new JSONObject().put("id",UUID.randomUUID().toString()).put("clientMessageId",UUID.randomUUID().toString()).put("content","原输入").put("attachments",new JSONArray());
            JSONObject accepted=(JSONObject)invoke(app,"startAssistantInput",new Class<?>[]{String.class,String.class,JSONObject.class,ModelConfig.class,RunControl.class},conversation,"原输入",message,model,original);
            Map<?,?> bindings=(Map<?,?>)field(app,"assistantInputs");Object binding=bindings.get(original);AtomicInteger phase=(AtomicInteger)field(binding,"phase");
            original.retainAuthorityCheck(()->{
                if(phase.get()==1){app.tasks.requestCancel(accepted.getString("taskId"),accepted.getString("runId"));original.cancel();}
            });
            assertThrows(InterruptedException.class,()->invoke(app,"runAssistantTurn",new Class<?>[]{String.class,String.class,JSONArray.class,ModelConfig.class,RunControl.class},conversation,"原输入",new JSONArray(),model,original));
            JSONObject task=app.tasks.get(accepted.getString("taskId"),false);assertEquals("cancelled",task.getString("status"));assertEquals(accepted.getString("runId"),task.getString("runId"));
            try(Cursor row=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=?",new String[]{accepted.getString("taskId"),accepted.getString("runId")})){assertTrue(row.moveToFirst());assertEquals("cancelled",row.getString(0));}
            assertTrue(bindings.isEmpty());assertTrue(((Map<?,?>)field(app,"capabilityControls")).isEmpty());assertEquals(1,app.tasks.list().length());
        }finally{original.cancel();((ExecutorService)field(app,"capabilityWorkers")).shutdownNow();db.close();}
    }

    @Implements(dev.pocketengine.features.FeatureHost.class)
    public static final class RelatedStops {
        @org.robolectric.annotation.RealObject private dev.pocketengine.features.FeatureHost host;
        int captures,deliveries;dev.pocketengine.features.FeatureHost.RelatedTaskCancellation captured,delivered;
        @Implementation protected dev.pocketengine.features.FeatureHost.RelatedTaskCancellation captureRelatedTaskCancellation(String id,String run,int epoch){
            captures++;captured=org.robolectric.shadow.api.Shadow.directlyOn(host,dev.pocketengine.features.FeatureHost.class,"captureRelatedTaskCancellation",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,id),org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,run),org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,epoch));return captured;
        }
        @Implementation protected void cancelRelatedTasks(dev.pocketengine.features.FeatureHost.RelatedTaskCancellation ticket){
            if(ticket!=null){deliveries++;delivered=ticket;}
            org.robolectric.shadow.api.Shadow.directlyOn(host,dev.pocketengine.features.FeatureHost.class,"cancelRelatedTasks",org.robolectric.util.ReflectionHelpers.ClassParameter.from(dev.pocketengine.features.FeatureHost.RelatedTaskCancellation.class,ticket));
        }
    }
    private static final class StopFixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final SQLiteDatabase db;final RunControl original=new RunControl();final JSONObject attempt,input;final String id;
        final Map<String,RunControl> live,pending;final RelatedStops related;final AtomicInteger queued=new AtomicInteger();
        @SuppressWarnings("unchecked") StopFixture()throws Exception{
            app.tasks=new TaskStore(app);ReadyApplicationFixture.complete(app);db=(SQLiteDatabase)field(app.tasks,"db");
            app.features=new dev.pocketengine.features.FeatureHost(event->{},app.tasks,()->false);
            related=org.robolectric.shadow.api.Shadow.extract(app.features);
            id=app.tasks.createCapabilityTask("原停止任务","").getString("id");attempt=app.tasks.startAttempt(id,"capabilities",20);
            original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));
            input=new JSONObject().put("taskId",id).put("runId",attempt.getString("runId")).put("epoch",attempt.getInt("coordinatorEpoch"));
            live=(Map<String,RunControl>)field(app,"capabilityControls");pending=(Map<String,RunControl>)field(app,"pendingCapabilityStops");live.put(id,original);
            app.setOriginalDeviceChildStops((task,control)->{assertEquals(id,task);assertSame(original,control);queued.incrementAndGet();});
        }
        void stop()throws Exception{invoke(app,"stopReviewedPrivateTask",new Class<?>[]{JSONObject.class},input);}
        public void close()throws Exception{original.cancel();app.features.close();((ExecutorService)field(app,"capabilityWorkers")).shutdownNow();db.close();}
    }
    @Test public void privateStopRetriesOriginalChildTicketAfterStateTransactionRollsBack()throws Exception{
        try(StopFixture f=new StopFixture()){
            JSONObject before=f.app.tasks.get(f.id,false);
            f.db.execSQL("CREATE TEMP TRIGGER fail_stop_summary BEFORE UPDATE ON tasks WHEN NEW.document LIKE '%正在停止后续操作%' BEGIN SELECT RAISE(ABORT,'fixture_stop_write'); END");
            assertThrows(Exception.class,f::stop);assertTrue(f.original.isCancelled());assertSame(f.original,f.pending.get(f.id));
            JSONObject rolledBack=f.app.tasks.get(f.id,false);assertEquals(before.getString("status"),rolledBack.getString("status"));assertFalse(rolledBack.optBoolean("cancelRequested"));
            assertEquals(1,f.related.captures);assertNotNull(f.related.captured);assertEquals(0,f.related.deliveries);assertEquals(0,f.queued.get());
            f.db.execSQL("DROP TRIGGER fail_stop_summary");f.stop();
            assertEquals(1,f.related.captures);assertEquals(1,f.related.deliveries);assertSame(f.related.captured,f.related.delivered);assertEquals(1,f.queued.get());assertFalse(f.pending.containsKey(f.id));
            assertEquals(f.input.getString("runId"),f.app.tasks.get(f.id,false).getString("runId"));assertEquals("stopping",f.app.tasks.get(f.id,false).getString("status"));
        }
    }
    @Test public void privateStopQueueRetryDoesNotRepeatConsumedRelatedTicket()throws Exception{
        try(StopFixture f=new StopFixture()){
            f.app.setOriginalDeviceChildStops((task,control)->{assertSame(f.original,control);if(f.queued.getAndIncrement()==0)throw new java.util.concurrent.RejectedExecutionException("fixture_queue_full");});
            assertThrows(IllegalStateException.class,f::stop);assertSame(f.original,f.pending.get(f.id));assertEquals(1,f.related.deliveries);
            f.stop();assertEquals(1,f.related.captures);assertEquals(1,f.related.deliveries);assertEquals(2,f.queued.get());assertFalse(f.pending.containsKey(f.id));
        }
    }
    @Test public void privateStopPreparingCallbackStillCompletesWithoutQueueing()throws Exception{
        try(StopFixture f=new StopFixture()){
            f.app.tasks.status(f.id,"preparing","原执行尚未接手");
            f.original.onCancel(()->{try{f.app.tasks.endAttempt(f.id,f.input.getString("runId"),"cancelled","原任务未开始",new JSONObject());f.live.remove(f.id,f.original);}catch(Exception failed){throw new AssertionError(failed);}});
            f.stop();assertEquals("cancelled",f.app.tasks.get(f.id,false).getString("status"));assertEquals(0,f.queued.get());assertFalse(f.pending.containsKey(f.id));assertEquals(0,f.related.deliveries);
        }
    }
    @Test public void privateStopFailureDoesNotReplaceReentrantSuccessorPendingControl()throws Exception{
        try(StopFixture f=new StopFixture()){
            RunControl successor=new RunControl();
            f.original.onCancel(()->{try{f.app.tasks.endAttempt(f.id,f.input.getString("runId"),"awaiting_user","原等待",new JSONObject());JSONObject next=f.app.tasks.startAttempt(f.id,"capabilities",20);successor.bindHostRun(f.id,next.getString("runId"),next.getInt("coordinatorEpoch"));f.live.put(f.id,successor);f.pending.put(f.id,successor);}catch(Exception failed){throw new AssertionError(failed);}});
            assertThrows(SecurityException.class,f::stop);assertSame(successor,f.live.get(f.id));assertSame(successor,f.pending.get(f.id));assertFalse(successor.isCancelled());assertEquals(0,f.queued.get());assertEquals(0,f.related.deliveries);
        }
    }
}
