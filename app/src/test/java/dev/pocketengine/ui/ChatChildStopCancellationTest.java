package dev.pocketengine.ui;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.assistant.AssistantModule;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real native chat/task-detail -> original Task/Runner/SQLite; remote stop is a queue probe. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class ChatChildStopCancellationTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("ChatChildStopKeyStore","1","Synthetic local fixture key"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void removeKeys(){java.security.Security.removeProvider("ChatChildStopKeyStore");}
    private interface Check {boolean done()throws Exception;}
    private static void await(Check check)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(!check.done()&&System.nanoTime()<until){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue("native condition not reached",check.done());
    }
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final AssistantModule module=(AssistantModule)app.features.module("assistant");
        final List<JSONObject> bodies=new CopyOnWriteArrayList<>();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final HttpServer server;

        Fixture()throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                int n=bodies.size();bodies.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                if(n==0){entered.countDown();if(!release.await(9,TimeUnit.SECONDS))throw new AssertionError("fixture gate timed out");}
                byte[] reply=("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""+(n<2?"SAME_COMPLETE_ANSWER":"NEW_COMPLETE_ANSWER")+"\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
            }catch(Exception failure){if(!moduleRunningQuietly())return;throw new IllegalStateException(failure);}finally{exchange.close();}});
            server.start();app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("model","fixture").putString("provider","openai_chat").putInt("maxSteps",8).commit();
            assertTrue(module.snapshot().getBoolean("sendAcknowledgements"));
        }
        private boolean moduleRunningQuietly(){try{return module.snapshot().getBoolean("running");}catch(Exception ignored){return false;}}
        JSONObject queue()throws Exception {
            JSONObject receipt=module.handle("send",new JSONObject().put("message","FIRST-OWNER-INPUT").put("clientMessageId",java.util.UUID.randomUUID().toString()).put("mode","queue"));
            assertTrue(entered.await(5,TimeUnit.SECONDS));return receipt;
        }
        void idle()throws Exception {await(()->!module.snapshot().getBoolean("running"));}
        public void close(){release.countDown();module.close();server.stop(0);}
    }
    @Test public void chatCancellationEnqueuesOriginalChildrenOnceAfterSql()throws Exception {cancelThroughBothEntrypoints(false);}
    @Test public void taskDetailCancellationUsesSameOriginalChildStopPath()throws Exception {cancelThroughBothEntrypoints(true);}
    private void cancelThroughBothEntrypoints(boolean taskFirst)throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.queue();java.util.concurrent.atomic.AtomicInteger stops=new java.util.concurrent.atomic.AtomicInteger();
            f.app.setOriginalDeviceChildStops((id,control)->{assertEquals(original.getString("taskId"),id);assertTrue(control.isCancelled());assertTrue(control.matchesHostRun(id,original.getString("runId"),original.getInt("epoch")));assertTrue(f.app.tasks.get(id,false).getBoolean("cancelRequested"));f.app.tasks.deviceTasks().originalStopCandidates(id,control);stops.incrementAndGet();});
            if(taskFirst)f.app.dispatchOwnerAction("stopTask",new JSONObject().put("taskId",original.getString("taskId")));
            else f.module.handle("cancel",new JSONObject());
            f.module.handle("cancel",new JSONObject());f.app.dispatchOwnerAction("stopTask",new JSONObject().put("taskId",original.getString("taskId")));f.idle();assertEquals("Chat stop must enqueue original child stop after SQL cancellation",1,stops.get());
            f.module.handle("cancel",new JSONObject());assertEquals("Repeated cancellation must not enqueue twice",1,stops.get());
            assertEquals("cancelled",f.app.tasks.get(original.getString("taskId"),false).getString("status"));assertEquals(1,f.bodies.size());
        }
    }
    @Test public void childStopQueueFailureIsNotReportedAsSuccessfulCancellationDelivery()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.queue();java.util.concurrent.atomic.AtomicInteger stops=new java.util.concurrent.atomic.AtomicInteger();
            f.app.setOriginalDeviceChildStops((id,control)->{stops.incrementAndGet();assertTrue(control.isCancelled());assertTrue(f.app.tasks.get(id,false).getBoolean("cancelRequested"));throw new java.util.concurrent.RejectedExecutionException("fixture queue unavailable");});
            try{f.module.handle("cancel",new JSONObject());fail("Queue failure must reach the native caller instead of a success reply");}
            catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("不能视为远端已停止"));}
            f.idle();assertEquals(1,stops.get());assertEquals("cancelled",f.app.tasks.get(original.getString("taskId"),false).getString("status"));
            f.app.setOriginalDeviceChildStops((id,control)->{assertEquals(original.getString("taskId"),id);assertTrue(control.isCancelled());assertTrue(control.matchesHostRun(id,original.getString("runId"),original.getInt("epoch")));assertEquals("Retry must preserve settled local state","cancelled",f.app.tasks.get(id,false).getString("status"));f.app.tasks.deviceTasks().originalStopCandidates(id,control);stops.incrementAndGet();});
            assertTrue(f.module.handle("cancel",new JSONObject()).getBoolean("cancellationRequested"));assertEquals(2,stops.get());
            assertFalse(f.module.handle("cancel",new JSONObject()).getBoolean("cancellationRequested"));f.app.dispatchOwnerAction("stopTask",new JSONObject().put("taskId",original.getString("taskId")));assertEquals("Accepted delivery must not be enqueued again",2,stops.get());
        }
    }
    @Test public void failedChatStopCanBeRetriedFromTaskDetailAfterRunnerExits()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.queue();java.util.concurrent.atomic.AtomicInteger stops=new java.util.concurrent.atomic.AtomicInteger();
            f.app.setOriginalDeviceChildStops((id,control)->{if(stops.incrementAndGet()==1)throw new java.util.concurrent.RejectedExecutionException("fixture queue unavailable");assertEquals(original.getString("taskId"),id);assertEquals("cancelled",f.app.tasks.get(id,false).getString("status"));});
            try{f.module.handle("cancel",new JSONObject());fail("Expected first enqueue failure");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("不能视为远端已停止"));}
            f.idle();f.app.dispatchOwnerAction("stopTask",new JSONObject().put("taskId",original.getString("taskId")));assertEquals(2,stops.get());
            f.module.handle("cancel",new JSONObject());assertEquals("Chat retry must observe already accepted task-detail delivery",2,stops.get());
        }
    }
}
