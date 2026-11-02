package dev.pocketengine.store;

import android.app.Application;
import android.content.SharedPreferences;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.models.InferenceRouteContinuity;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual SharedPreferences/profile CAS/catalog/Gateway loopback; no vault crypto, device or live-vendor claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ProviderCompatibilityNativeTest {
    private SettingsStore settings;private SharedPreferences prefs;private ModelConnectionService service;private Path cache;
    private HttpServer http;private final AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger();
    private volatile CountDownLatch entered,release;private volatile JSONObject body;
    @Before public void open()throws Exception {
        prefs=RuntimeEnvironment.getApplication().getSharedPreferences("private_settings",0);prefs.edit().clear().commit();settings=new SettingsStore(prefs,null);
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{try{String text;if(exchange.getRequestMethod().equals("GET")){gets.incrementAndGet();text="{\"data\":[{\"id\":\"fixture-model\"}]}";}else{posts.incrementAndGet();body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));if(entered!=null)entered.countDown();if(release!=null)release.await(5,TimeUnit.SECONDS);text="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}";}
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){/* cancelled original HTTP requests may close */}finally{exchange.close();}});http.start();
        cache=Files.createTempDirectory("provider-native-catalog").resolve("catalog.json");service=new ModelConnectionService(settings,cache,Clock.systemUTC());service.install();service.connections();
    }
    @After public void close(){if(release!=null)release.countDown();if(http!=null)http.stop(0);}
    private String base(){return "http://127.0.0.1:"+http.getAddress().getPort()+"/v1";}
    private JSONObject options(String field)throws Exception{return new JSONObject().put("tokenField",field).put("streamUsage","OMIT");}
    private JSONObject add()throws Exception {
        service.saveConnection("",0,"original API",new JSONObject().put("baseUrl",base()).put("model","fixture-model").put("sendScreenshots",false).put("maxOutputTokens",512).put("compatibility",options("MAX_COMPLETION_TOKENS")));
        JSONObject row=settings.apiConnectionsSnapshot().getJSONArray("connections").getJSONObject(0);service.selectConnection(row.getString("id"),row.getLong("revision"));return row;
    }
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","current input"));}

    @Test public void nativeOwnerProfilePersistsAndActualConnectionTestHasOneOriginalReceiptPair()throws Exception {
        JSONObject row=add();assertEquals(0,posts.get());assertEquals(0,gets.get());ModelConfig selected=settings.get();assertNotNull(selected.compatibility);
        List<InferenceReceipt> receipts=new ArrayList<>();InferenceCapture.Target target=new InferenceCapture.Target(new InferenceReceipt.Context("probe-task","probe-run",1,InferenceReceipt.Purpose.CONNECTION_TEST,""),new InferenceCapture.Sink(){public void started(InferenceReceipt r){receipts.add(r);}public void finished(InferenceReceipt r){receipts.add(r);}});RunControl original=new RunControl();original.bindHostRun("probe-task","probe-run",1);
        JSONObject probe=ModelGateway.testConnection(selected,original,target);assertTrue(probe.getBoolean("ok"));assertFalse(probe.getBoolean("visionVerified"));assertFalse(probe.getBoolean("toolsVerified"));assertEquals(1,posts.get());assertEquals(0,gets.get());assertEquals(512,body.getInt("max_completion_tokens"));assertEquals(2,receipts.size());
        assertEquals(row.getString("id"),receipts.get(1).toJson().getJSONObject("route").getString("connectionId"));assertEquals("CONNECTION_TEST",receipts.get(1).context.purpose.name());
        SettingsStore restored=new SettingsStore(prefs,null);ModelConfig reopened=restored.get();assertTrue(selected.compatibility.matches(reopened.compatibility));assertEquals(selected.capabilityBinding,reopened.capabilityBinding);
        assertFalse(service.snapshot().getJSONObject("inferenceObservation").getBoolean("healthVerified"));
    }
    @Test public void profileCasUpdateInvalidatesOldRouteAndForgedProfileWithoutChangingSelectedModel()throws Exception {
        JSONObject row=add();ModelConfig captured=settings.get();String oldId=captured.compatibility.id;
        service.saveConnection(row.getString("id"),row.getLong("revision"),"original API",new JSONObject().put("compatibility",options("MAX_TOKENS")));ModelConfig live=settings.get();
        assertEquals(captured.model,live.model);assertEquals(oldId,live.compatibility.id);assertEquals(captured.compatibility.revision+1,live.compatibility.revision);assertTrue(live.capabilityBinding.installationRevision()>captured.capabilityBinding.installationRevision());
        assertFalse(settings.matchesApiCatalogRoute(captured));assertThrows(Exception.class,()->ModelGateway.complete(captured,messages(),null,new RunControl()));assertThrows(SecurityException.class,()->InferenceRouteContinuity.requireCurrent(captured,live,new RunControl()));assertEquals(0,posts.get());
        ModelConfig forged=live.withProviderCompatibility(null);assertFalse(settings.matchesApiCatalogRoute(forged));assertThrows(ModelRequestPolicy.Rejected.class,()->ModelRequestPolicy.resolve(forged));
        String before=prefs.getString("apiConnections","");assertThrows(IllegalStateException.class,()->service.saveConnection(row.getString("id"),1,"stale",new JSONObject().put("compatibility",options("MAX_COMPLETION_TOKENS"))));assertEquals(before,prefs.getString("apiConnections",""));
    }
    @Test public void lateProbeAfterCompatibilityChangeRemainsOnOldBindingAndCannotReportNewConnectionSuccess()throws Exception {
        JSONObject row=add();ModelConfig captured=settings.get();entered=new CountDownLatch(1);release=new CountDownLatch(1);ExecutorService caller=Executors.newSingleThreadExecutor();
        try{Future<JSONObject> pending=caller.submit(()->ModelGateway.testConnection(captured));assertTrue(entered.await(3,TimeUnit.SECONDS));
            service.saveConnection(row.getString("id"),row.getLong("revision"),"original API",new JSONObject().put("compatibility",options("MAX_TOKENS")));release.countDown();assertFalse(pending.get(5,TimeUnit.SECONDS).getBoolean("ok"));
            assertEquals(1,posts.get());assertFalse(service.snapshot().getJSONObject("inferenceObservation").getBoolean("observed"));assertEquals("MAX_TOKENS",settings.get().compatibility.tokenField.name());
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test public void compatibilityEditKeepsExactCatalogUrlAndFormatAndUnknownOptionsLeaveBytesUntouched()throws Exception {
        JSONObject row=add();URI exact=URI.create(base()+"/owner-model-list");service.configure(null,ModelBinding.Format.OPENAI,exact);JSONObject current=settings.apiConnectionsSnapshot().getJSONArray("connections").getJSONObject(0);
        service.saveConnection(current.getString("id"),current.getLong("revision"),"original API",new JSONObject().put("compatibility",options("MAX_TOKENS")));assertEquals(exact,settings.apiCatalogBinding().modelsEndpoint());assertEquals(ModelBinding.Format.OPENAI,settings.apiCatalogBinding().format());assertTrue(settings.get().catalogEnforced);
        JSONObject latest=settings.apiConnectionsSnapshot().getJSONArray("connections").getJSONObject(0);String before=prefs.getString("apiConnections","");assertThrows(Exception.class,()->service.saveConnection(latest.getString("id"),latest.getLong("revision"),"invalid",new JSONObject().put("compatibility",new JSONObject().put("stripMessages",true))));assertEquals(before,prefs.getString("apiConnections",""));assertEquals(0,posts.get());assertEquals(0,gets.get());
    }
}
