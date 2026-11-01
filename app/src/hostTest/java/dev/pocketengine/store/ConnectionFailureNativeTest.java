package dev.pocketengine.store;

import android.app.Application;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.models.ModelConnectionService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real current TaskStore/Runner/HTTP and settings CAS. No provider, KeyStore or device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConnectionFailureNativeTest {
    private static final class Secrets implements SettingsStore.ApiSecrets {final Map<String,String> values=new HashMap<>();public String get(String slot){return values.getOrDefault(slot,"");}public void put(String slot,String value){values.put(slot,value);}}
    private HttpServer http;private SettingsStore settings;private ModelConnectionService models;private TaskStore tasks;private ModelConfig captured;private JSONObject profile;private Path cache;private volatile int status=429;private final AtomicInteger requests=new AtomicInteger();
    @Before public void open()throws Exception {
        var app=RuntimeEnvironment.getApplication();var preferences=app.getSharedPreferences("private_settings",0);preferences.edit().clear().commit();settings=new SettingsStore(preferences,new Secrets());
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{requests.incrementAndGet();try{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Retry-After","120");String result=status==200?"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"actual fixture result\"}}]}":"{\"error\":\"fixture-nonreal-secret must not enter audit\"}";byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception ignored){}finally{exchange.close();}});http.start();
        cache=Files.createTempDirectory("new-connection-failure-native").resolve("catalog");models=new ModelConnectionService(settings,cache,Clock.systemUTC());models.install();models.connections();profile=settings.saveApiConnection("",0,"A",input("model-a","fixture-nonreal-secret"));captured=settings.selectApiConnection(profile.getString("id"),1);tasks=new TaskStore(app);
    }
    @After public void close(){if(http!=null)http.stop(0);}
    private JSONObject input(String model,String key)throws Exception{return new JSONObject().put("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).put("provider","openai_chat").put("model",model).put("apiKey",key);}
    private JSONObject run(ModelConfig config)throws Exception {
        JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("original connection failure task","conversation").getString("id"),"capabilities",8);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(id,run,epoch);RunJournal journal=tasks.journal(id,run,epoch,original);
        CapabilityPort port=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("no effect tools offered");}};
        RunOutcome outcome=new AgentRunner(null,port).run(id,"original connection failure task",config,original,event->{try{tasks.event(id,run,epoch,event);}catch(Exception failure){throw new RuntimeException(failure);}},journal);
        return new JSONObject().put("task",tasks.get(id,false)).put("receipt",tasks.inferenceUsage(id).getJSONArray("receipts").getJSONObject(0)).put("status",outcome.status);
    }
    @Test public void actualRunnerPersistsFailureAndReopenPreservesReceiptButDoesNotInventConnectionHealth()throws Exception {
        JSONObject result=run(captured),receipt=result.getJSONObject("receipt"),failure=receipt.getJSONObject("failure");assertEquals("failed",result.getString("status"));assertEquals(1,requests.get());assertEquals(429,failure.getInt("httpStatus"));assertEquals("VALID",failure.getJSONObject("retry").getString("status"));assertFalse(receipt.toString().contains("fixture-nonreal-secret"));
        JSONObject shown=models.snapshot().getJSONObject("inferenceObservation");assertTrue(shown.getBoolean("observed"));assertEquals(receipt.getString("callId"),shown.getJSONObject("lastFailure").getString("callId"));
        TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());JSONObject stored=reopened.inferenceUsage(result.getJSONObject("task").getString("id")).getJSONArray("receipts").getJSONObject(0);assertEquals(receipt.getString("callId"),stored.getString("callId"));assertEquals(failure.toString(),stored.getJSONObject("failure").toString());assertEquals("FAILED",stored.getString("outcome"));
        ModelConnectionService newProcessProjection=new ModelConnectionService(settings,cache,Clock.systemUTC());newProcessProjection.install();assertFalse(newProcessProjection.snapshot().getJSONObject("inferenceObservation").getBoolean("observed"));assertEquals(1,requests.get());
    }
    @Test public void manualFutureSelectionAndKeyRotationCannotRebindOldCallOrReplayItsUnknownResult()throws Exception {
        JSONObject original=run(captured);String id=original.getJSONObject("task").getString("id"),before=tasks.get(id,false).toString();String call=original.getJSONObject("receipt").getString("callId");
        JSONObject other=settings.saveApiConnection("",0,"B",input("model-b","other-fixture-key"));models.selectConnection(other.getString("id"),1);assertEquals("model-a",captured.model);assertEquals(profile.getString("id"),captured.capabilityBinding.connectionId());assertEquals(before,tasks.get(id,false).toString());assertEquals(1,requests.get());assertFalse(models.snapshot().getJSONObject("inferenceObservation").getBoolean("observed"));
        status=200;JSONObject next=run(settings.get());assertNotEquals("failed",next.getString("status"));assertEquals(2,requests.get());assertEquals(other.getString("id"),next.getJSONObject("receipt").getJSONObject("route").getString("connectionId"));assertEquals(call,tasks.inferenceUsage(id).getJSONArray("receipts").getJSONObject(0).getString("callId"));assertEquals("FAILED",tasks.inferenceUsage(id).getJSONArray("receipts").getJSONObject(0).getString("outcome"));
        models.selectConnection(profile.getString("id"),1);models.saveConnection(profile.getString("id"),1,"A",input("model-a","rotated-fixture-key"));assertFalse(models.snapshot().getJSONObject("inferenceObservation").getBoolean("observed"));assertFalse(settings.matchesApiCatalogRoute(captured));assertEquals(2,requests.get());assertEquals(call,tasks.inferenceUsage(id).getJSONArray("receipts").getJSONObject(0).getString("callId"));
    }
}
