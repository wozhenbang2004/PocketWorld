package dev.pocketengine.features;

import static org.junit.Assert.*;

import android.app.Application;
import dev.pocketengine.features.automation.AutomationModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual FeatureHost queue, original AutomationPlanHost and real commitment source/SQLite authority. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class FeatureOwnerQueueAdmissionTest {
    private final LinkedBlockingQueue<JSONObject> events=new LinkedBlockingQueue<>();
    private FeatureHost host;
    private AutomationModule automation;
    private static JSONObject scope()throws Exception {return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    @Before public void prepare()throws Exception {
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(app);AutomationPlanHost plans=new AutomationPlanHost(tasks);
        automation=new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),events::add),plans::resolveEvidence,plans);
        host=new FeatureHost(events::add,tasks,()->false);host.setAutomationPlanHost(plans);host.register(automation);
    }
    @After public void close(){if(host!=null)host.close();}
    private static JSONObject request(String id)throws Exception {
        JSONObject declaration=new JSONObject().put("kind","explicit_user").put("title","核对原委托").put("details","只保存承诺，不运行任务").put("taskId","").put("dueAt",System.currentTimeMillis()+3_600_000L);
        return new JSONObject().put("feature","automation").put("command","createCommitment").put("requestId",id).put("payload",new JSONObject().put("commitment",declaration));
    }
    private int commitments()throws Exception {return automation.handle("listCommitments",new JSONObject().put("scope",scope())).getJSONArray("commitments").length();}
    private JSONObject receipt(String request)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){JSONObject event=events.poll(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);if(event!=null&&request.equals(event.optString("requestId")))return event;}
        throw new AssertionError("original FeatureHost receipt did not arrive");
    }
    private final class QueueGate implements AutoCloseable {
        private final CountDownLatch release=new CountDownLatch(1);
        QueueGate()throws Exception {
            Field field=FeatureHost.class.getDeclaredField("workers");field.setAccessible(true);ThreadPoolExecutor worker=(ThreadPoolExecutor)field.get(host);
            CountDownLatch entered=new CountDownLatch(worker.getCorePoolSize());
            for(int i=0;i<worker.getCorePoolSize();i++)worker.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            assertTrue("all real worker slots must be occupied before admission",entered.await(3,TimeUnit.SECONDS));
        }
        @Override public void close(){release.countDown();}
    }
    @Test public void revokedOriginalWindowRejectsAQueuedWriteEvenWhenJsonClaimsApproval()throws Exception {
        AtomicBoolean current=new AtomicBoolean(true);AtomicInteger checked=new AtomicInteger();int before=commitments();
        try(QueueGate gate=new QueueGate()){
            JSONObject input=request("revoked-original").put("originalOwnerCurrent",true).put("ownerApproved",true);
            input.getJSONObject("payload").put("originalOwnerCurrent",true).put("windowCurrent",true);
            host.request(input,()->{checked.incrementAndGet();return current.get();});
            assertEquals(0,checked.get());current.set(false);
        }
        JSONObject rejected=receipt("revoked-original");assertFalse(rejected.getBoolean("ok"));assertEquals(1,checked.get());assertEquals(before,commitments());
    }
    @Test public void retainedNativeWindowReachesTheOriginalBookAndReturnsItsActualReceipt()throws Exception {
        AtomicBoolean current=new AtomicBoolean(true);AtomicInteger checked=new AtomicInteger();int before=commitments();
        try(QueueGate gate=new QueueGate()){
            JSONObject input=request("retained-original").put("originalOwnerCurrent",false);
            host.request(input,()->{checked.incrementAndGet();return current.get();});assertEquals(0,checked.get());
        }
        JSONObject accepted=receipt("retained-original");assertTrue(accepted.toString(),accepted.getBoolean("ok"));assertEquals(1,checked.get());assertEquals(before+1,commitments());
        JSONObject record=accepted.getJSONObject("result").getJSONObject("commitment");assertFalse(record.getString("id").isEmpty());assertEquals("pending",record.getString("status"));
    }
}
