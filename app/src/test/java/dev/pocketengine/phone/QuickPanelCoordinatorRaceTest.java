package dev.pocketengine.phone;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Deterministic scheduling of the real coordinator, not a substitute task executor or phone port.
 * The external runner inserts one pause before capture assignment in both exact source versions.
 * Normal builds contain no probe call, and deliberately do not claim this scheduled case ran. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class QuickPanelCoordinatorRaceTest {
    @Test public void closingReadOnlyReceiptQueryDisposesItsOriginalCaptureWithoutStoppingTheTask()throws Exception {
        RunControl original=new RunControl();original.bindHostRun("task","run",1);
        OwnerScreenContext.Target target=new OwnerScreenContext.Target("task","run",1,"capabilities",null,-1);
        OwnerScreenContext.SourceIdentity identity=new OwnerScreenContext.SourceIdentity("query-main-fixture",0);
        OwnerScreenContext capture=new OwnerScreenContext(target,original,(bound,live)->{},new OwnerScreenContext.Source(){
            public OwnerScreenContext.SourceIdentity identity(){return identity;}
            public OwnerScreenContext.Captured capture(boolean image,OwnerScreenContext.ReadCheck check)throws Exception {
                check.check();return new OwnerScreenContext.Captured(identity,"fixture.notes","原查询来源",42,SystemClock.uptimeMillis(),"只核对这份已捕获内容","available",false,"not_requested","","",0,0,null);
            }
        },SystemClock::uptimeMillis);
        OwnerScreenContext.Preview preview=capture.capture(false);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        OwnerScreenContextHost host=new OwnerScreenContextHost(){
            public OwnerScreenContext openOwnerScreenContext(JSONObject expected){throw new AssertionError("No new capture");}
            public JSONObject submitOwnerScreenContext(OwnerScreenContext c,OwnerScreenContext.Preview p,String client,String note){throw new AssertionError("A query never sends");}
            public JSONObject ownerScreenContextReceipt(JSONObject expected,String client)throws Exception {
                entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new JSONObject(expected.toString()).put("clientMessageId",client).put("status","unconfirmed");
            }
        };
        var service=Robolectric.buildService(PhoneAccessibilityService.class).create();
        JSONObject task=new JSONObject().put("id","task").put("runId","run").put("coordinatorEpoch",1).put("executionMode","capabilities").put("goal","查询原回执");
        QuickPanelCoordinator panel=new QuickPanelCoordinator(service.get(),host,task,"助手工具任务");
        Field held=QuickPanelCoordinator.class.getDeclaredField("capture");held.setAccessible(true);held.set(panel,capture);
        Field selected=QuickPanelCoordinator.class.getDeclaredField("preview");selected.setAccessible(true);selected.set(panel,preview);
        Field client=QuickPanelCoordinator.class.getDeclaredField("clientMessageId");client.setAccessible(true);client.set(panel,UUID.randomUUID().toString());
        Field pool=QuickPanelCoordinator.class.getDeclaredField("worker");pool.setAccessible(true);ExecutorService worker=(ExecutorService)pool.get(panel);
        try {
            panel.queryReceipt();assertTrue(entered.await(5,TimeUnit.SECONDS));panel.close();
            assertTrue("Read-only query must not retain a closed surface's capture",preview.isClosed());
            assertNull(held.get(panel));assertFalse(original.isCancelled());assertFalse(original.isPaused());
        } finally {
            release.countDown();panel.close();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();capture.close();service.destroy();
        }
    }

    @Test public void cancellationAtAsyncHandoffCannotLeaveOldCaptureOrBlockNextExplicitCapture()throws Exception {
        assumeTrue("Requires the separately identified scheduling-instrumented source copy",Boolean.getBoolean("pocketworld.a24.captureRaceProbe"));
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String conversation=UUID.randomUUID().toString();
        JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("只核对当前来源，不提交",conversation).getString("id"),"capabilities",8);
        String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(id,run,epoch);
        OwnerScreenContext.Target target=new OwnerScreenContext.Target(id,run,epoch,"capabilities",null,-1);AtomicInteger opens=new AtomicInteger();
        OwnerScreenContext.SourceIdentity sourceIdentity=new OwnerScreenContext.SourceIdentity("explicit-main-source-fixture",0);
        OwnerScreenContextHost host=new OwnerScreenContextHost(){
            @Override public OwnerScreenContext openOwnerScreenContext(JSONObject expected)throws Exception {
                opens.incrementAndGet();
                return new OwnerScreenContext(target,original,(bound,live)->tasks.ownerScreenContextTarget(bound.taskId,bound.runId,bound.epoch,live),new OwnerScreenContext.Source(){
                    @Override public OwnerScreenContext.SourceIdentity identity(){return sourceIdentity;}
                    @Override public OwnerScreenContext.Captured capture(boolean image,OwnerScreenContext.ReadCheck check)throws Exception {
                        check.check();assertFalse(image);
                        return new OwnerScreenContext.Captured(sourceIdentity,"fixture.orders","调度边界来源",42,SystemClock.uptimeMillis(),"只用于异步交接调度例的明确来源文字","available",false,"not_requested","","",0,0,null);
                    }
                },SystemClock::uptimeMillis);
            }
            @Override public JSONObject submitOwnerScreenContext(OwnerScreenContext c,OwnerScreenContext.Preview p,String client,String note){throw new AssertionError("No send was authorized by this cancellation journey");}
            @Override public JSONObject ownerScreenContextReceipt(JSONObject expected,String client){throw new AssertionError("No write was dispatched");}
        };
        var service=Robolectric.buildService(PhoneAccessibilityService.class).create();
        QuickPanelCoordinator panel=new QuickPanelCoordinator(service.get(),host,task,"助手工具任务");
        Field field=QuickPanelCoordinator.class.getDeclaredField("worker");field.setAccessible(true);ExecutorService worker=(ExecutorService)field.get(panel);
        QuickPanelRaceProbe.arm();
        Thread release=new Thread(()->{
            try {assertTrue(QuickPanelRaceProbe.cancelStarted.await(5,TimeUnit.SECONDS));Thread.sleep(100);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally{QuickPanelRaceProbe.release.countDown();}
        },"quick-panel-test-schedule-release");
        try {
            panel.show();worker.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();panel.capture(false);assertTrue("Worker reached the actual native capture handoff",QuickPanelRaceProbe.entered.await(5,TimeUnit.SECONDS));
            release.start();QuickPanelRaceProbe.cancelStarted.countDown();panel.cancelPreview();
            worker.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
            panel.capture(false);worker.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Cancelled handoff must release the slot for the next explicit capture",2,opens.get());
            assertFalse(original.isCancelled());assertFalse(original.isPaused());assertEquals(1,tasks.list().length());
            assertEquals(run,tasks.get(id,false).getString("runId"));assertEquals(0,tasks.get(id,false).getJSONArray("operations").length());
            JSONArray inputs=tasks.get(id,false).optJSONArray("ownerInputs");assertTrue(inputs==null||inputs.length()==0);
        } finally {
            QuickPanelRaceProbe.release.countDown();panel.close();service.destroy();release.join(5000);worker.awaitTermination(5,TimeUnit.SECONDS);
        }
    }
}

final class QuickPanelRaceProbe {
    static volatile CountDownLatch entered,release,cancelStarted;
    private static final AtomicInteger assignments=new AtomicInteger();
    static void arm(){assignments.set(0);entered=new CountDownLatch(1);release=new CountDownLatch(1);cancelStarted=new CountDownLatch(1);}
    static void beforeAssignment()throws InterruptedException {
        if(assignments.getAndIncrement()==0){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Scheduling probe was not released");}
    }
}
