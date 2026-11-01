package dev.pocketengine.features;

import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.media.MediaModule;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class MediaUrgentMountedApplicationTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("MediaUrgentKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    private static JSONObject j(Object... pairs)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static void reply(HttpServer.Exchange e,JSONObject body)throws Exception{byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();}
    private static JSONObject call(EngineApplication app,String command,JSONObject payload)throws Exception{
        String id=UUID.randomUUID().toString();CopyOnWriteArrayList<JSONObject> replies=new CopyOnWriteArrayList<>();EngineApplication.Observer observer=event->{if(id.equals(event.optString("requestId")))replies.add(event);};app.addObserver(observer);
        try{app.features.request(j("feature","media","command",command,"requestId",id,"payload",payload));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(replies.isEmpty()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}assertFalse("media reply missing",replies.isEmpty());assertTrue(replies.get(0).optString("message"),replies.get(0).getBoolean("ok"));return replies.get(0).getJSONObject("result");}finally{app.removeObserver(observer);}
    }
    @Test public void providerCancelQueryCannotOccupyTheUrgentLocalStopLane()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();AtomicReference<JSONObject> posted=new AtomicReference<>();AtomicBoolean gate=new AtomicBoolean();AtomicInteger posts=new AtomicInteger(),remoteCancels=new AtomicInteger(),interrupts=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/prompt",e->{posts.incrementAndGet();JSONObject request=new JSONObject(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));posted.set(request);reply(e,j("prompt_id",request.getString("prompt_id")));});
        server.createContext("/history",e->{if(gate.get()){entered.countDown();release.await(6,TimeUnit.SECONDS);}reply(e,new JSONObject());});
        server.createContext("/queue",e->{if(e.getRequestMethod().equals("POST")){remoteCancels.incrementAndGet();reply(e,new JSONObject());return;}JSONObject request=posted.get();JSONArray queued=new JSONArray();if(request!=null)queued.put(new JSONArray().put(1).put(request.getString("prompt_id")).put(request.getJSONObject("prompt")).put(new JSONObject(request.getJSONObject("extra_data").toString()).put("client_id",request.getString("client_id"))));reply(e,j("queue_pending",queued,"queue_running",new JSONArray()));});
        server.createContext("/interrupt",e->{interrupts.incrementAndGet();reply(e,new JSONObject());});server.start();
        try{MediaModule module=(MediaModule)app.features.module("media");JSONObject configured=module.handle("configure",j("provider","comfyui","baseUrl","http://127.0.0.1:"+server.getAddress().getPort()));JSONObject args=j("configurationRevision",configured.getString("configurationRevision"),"workflow",j("6",j("class_type","CLIPTextEncode","inputs",j("text","urgent owner fixture"))));JSONObject prepared=call(app,"prepareSubmission",j("submissionCommand","submitWorkflow","arguments",args));JSONObject accepted=call(app,"submitWorkflow",new JSONObject(args.toString()).put("taskId",prepared.getString("taskId")));String operation=accepted.getString("operationId");
            gate.set(true);app.features.request(j("feature","media","command","cancelJob","requestId",UUID.randomUUID().toString(),"payload",j("operationId",operation)));assertTrue("provider query did not enter",entered.await(4,TimeUnit.SECONDS));long start=System.nanoTime();JSONObject stopped=call(app,"cancel",j("operationId",operation));long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            assertTrue("urgent lane waited on remote query: "+elapsed,elapsed<3000);assertEquals("native_local_control",stopped.getString("receiptSource"));assertTrue(stopped.getBoolean("localWaitCancelled"));assertFalse(stopped.getBoolean("providerStateConfirmed"));release.countDown();assertEquals(1,posts.get());assertEquals(0,remoteCancels.get());assertEquals(0,interrupts.get());assertEquals(1,app.tasks.list().length());assertNotEquals("completed",app.tasks.get(prepared.getString("taskId"),false).getString("status"));
        }finally{release.countDown();server.stop(0);app.features.close();java.security.Security.removeProvider("MediaUrgentKeyStore");}
    }
}
