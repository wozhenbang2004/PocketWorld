package dev.pocketengine.browser;

import android.app.Application;
import dev.pocketengine.core.ExecutionResources;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Host/real persistent-state race regressions. Test modules do not claim real page execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class BrowserAdmissionRaceTest {
    @Test public void lateObserveAfterTakeoverCannotReacquireResourcesOrReachTheBrowser()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String task=UUID.randomUUID().toString();long expires=System.currentTimeMillis()+600000L;
        tasks.beginExternal("browser","acquire",task,new JSONObject().put("goal","读取网页"));tasks.updateExternal(task,new JSONObject().put("status","running").put("runId",task).put("expiresAt",expires));
        ExecutionResources resources=new ExecutionResources();assertTrue(resources.acquireBrowser(task,expires));CountDownLatch replied=new CountDownLatch(1);AtomicReference<JSONObject> reply=new AtomicReference<>();AtomicInteger dispatched=new AtomicInteger();
        try(FeatureHost host=new FeatureHost(event->{if(event.optString("type").equals("feature_result")){reply.set(event);replied.countDown();}},tasks,()->false,resources)){
            host.register(new EngineModule(){public String id(){return "browser";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input){dispatched.incrementAndGet();throw new SecurityException("原生浏览器租约已由主人接管");}});
            host.recordEvent(takeover(task,task));assertEquals("cancelled",tasks.get(task,false).getString("status"));
            host.request(new JSONObject().put("feature","browser").put("command","observe").put("payload",new JSONObject().put("taskId",task).put("runId",task).put("leaseId","old-lease")));
            assertTrue(replied.await(5,TimeUnit.SECONDS));assertFalse(reply.get().getBoolean("ok"));assertEquals(0,dispatched.get());assertEquals("cancelled",tasks.get(task,false).getString("status"));assertTrue(resources.acquirePhone("next-phone"));resources.releasePhone("next-phone");
        }
    }
    @Test public void takeoverBeforeAcquireReceiptCannotReviveTheReleasedAdmission()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());ExecutionResources resources=new ExecutionResources();FeatureHost[] hostRef=new FeatureHost[1];AtomicReference<String> task=new AtomicReference<>();AtomicReference<JSONObject> reply=new AtomicReference<>();CountDownLatch replied=new CountDownLatch(1);
        try(FeatureHost host=new FeatureHost(event->{if(event.optString("type").equals("feature_result")){reply.set(event);replied.countDown();}},tasks,()->false,resources)){
            hostRef[0]=host;host.register(new EngineModule(){public String id(){return "browser";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{
                task.set(input.getString("taskId"));String run=input.getString("runId");
                // The native main thread accepted acquisition, then the owner took over before the host worker adopted its receipt.
                hostRef[0].recordEvent(takeover(task.get(),run));
                return new JSONObject().put("status","leased").put("active",true).put("taskId",task.get()).put("runId",run).put("leaseId","already-revoked").put("expiresAt",System.currentTimeMillis()+600000L);
            }});
            host.request(new JSONObject().put("feature","browser").put("command","acquire").put("payload",new JSONObject().put("goal","读取网页")));
            assertTrue(replied.await(5,TimeUnit.SECONDS));assertFalse(reply.get().getBoolean("ok"));assertTrue(resources.acquirePhone("next-phone"));resources.releasePhone("next-phone");assertEquals("cancelled",tasks.get(task.get(),false).getString("status"));
        }
    }
    private static JSONObject takeover(String task,String run)throws Exception{return new JSONObject().put("type","feature_event").put("feature","browser").put("payload",new JSONObject().put("taskId",task).put("runId",run).put("status","taken_over").put("active",false));}
}
