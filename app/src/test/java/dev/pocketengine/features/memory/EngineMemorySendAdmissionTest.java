package dev.pocketengine.features.memory;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class EngineMemorySendAdmissionTest {
    @org.junit.Before public void keys(){java.security.Security.addProvider(new java.security.Provider("MemoryJourneyKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @org.junit.After public void reset(){java.security.Security.removeProvider("MemoryJourneyKeyStore");InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){}});}
    @Test public void originalApplicationShortcutRejectsForgottenParentsAfterStartedBeforeHttp()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();MemoryModule memory=(MemoryModule)app.features.module("memory");JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");JSONArray selected=new JSONArray();
        for(String text:new String[]{"first parent","second parent"}){JSONObject row=memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("text",text))).getJSONObject("record");selected.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")));}
        AtomicInteger http=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{http.incrementAndGet();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"null\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("model","memory-model-fixture").putInt("maxSteps",8).commit();
            InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r)throws Exception{memory.handle("forget",new JSONObject().put("scope",scope).put("id",selected.getJSONObject(0).getString("id")));}public void finished(InferenceReceipt r){}});
            try{app.proposeMemoryConsolidation(selected,new RunControl());fail("forgotten selection accepted");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}
            assertEquals(0,http.get());assertEquals(0,memory.handle("listCandidates",new JSONObject().put("scope",scope)).getJSONArray("candidates").length());
        }finally{server.stop(0);}
    }
    @Test public void originalSourceShortcutRejectsRetiredSnapshotAfterStartedBeforeHttp()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();MemoryModule memory=(MemoryModule)app.features.module("memory");JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        JSONObject task=app.tasks.create("original confirmed task");String id=task.getString("id");app.tasks.status(id,"completed","checked source result");app.tasks.event(id,new JSONObject().put("type","user_verification").put("completed",true));SourceRef source=app.tasks.resolveMemorySource(MemoryBook.Scope.from(scope),"task_evidence","task:"+id+":verified_result").ref;
        AtomicInteger http=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{http.incrementAndGet();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"null\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("model","memory-model-fixture").putInt("maxSteps",8).commit();
            InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r)throws Exception{app.tasks.forgetMemorySourceSnapshot(source);}public void finished(InferenceReceipt r){}});
            try{app.proposeSourceMemoryLearning(source,new RunControl());fail("retired selection accepted");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}
            assertEquals(0,http.get());assertEquals(0,memory.handle("listCandidates",new JSONObject().put("scope",scope)).getJSONArray("candidates").length());
        }finally{server.stop(0);}
    }

}
