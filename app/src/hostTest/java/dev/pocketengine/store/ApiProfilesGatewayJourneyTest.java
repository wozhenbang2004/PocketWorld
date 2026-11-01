package dev.pocketengine.store;

import android.app.Application;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.models.*;
import dev.pocketengine.runtime.usage.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/** Real native settings/catalog/Gateway and loopback HTTP. No provider, device or credential-vault cryptography claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ApiProfilesGatewayJourneyTest {
    private SettingsStore store;private SettingsApiProfilesTest.Secrets secrets;private ModelConnectionService service;
    private HttpServer a,b;private ExecutorService workers;private final AtomicInteger aPosts=new AtomicInteger(),bPosts=new AtomicInteger(),gets=new AtomicInteger();
    private volatile String aAuth,bAuth;private volatile CountDownLatch entered,release;
    @Before public void open()throws Exception{
        var prefs=RuntimeEnvironment.getApplication().getSharedPreferences("private_settings",0);prefs.edit().clear().commit();secrets=new SettingsApiProfilesTest.Secrets();store=new SettingsStore(prefs,secrets);
        workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"api-profile-http-fixture");t.setDaemon(true);return t;});a=server(true);b=server(false);
        service=new ModelConnectionService(store,Files.createTempDirectory("native-api-profiles").resolve("catalog.json"),Clock.systemUTC());service.install();service.connections();
    }
    @After public void close(){if(release!=null)release.countDown();if(a!=null)a.stop(0);if(b!=null)b.stop(0);if(workers!=null)workers.shutdownNow();}
    private HttpServer server(boolean first)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);server.createContext("/",exchange->{try{
            String body;String type="application/json";
            if(exchange.getRequestMethod().equals("GET")){gets.incrementAndGet();if(entered!=null)entered.countDown();if(release!=null)release.await(5,TimeUnit.SECONDS);body="{\"data\":[{\"id\":\"model-a\"}]}";}
            else{if(first){aPosts.incrementAndGet();aAuth=exchange.getRequestHeaders().getFirst("Authorization");}else{bPosts.incrementAndGet();bAuth=exchange.getRequestHeaders().getFirst("Authorization");}
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                if(request.optBoolean("stream")){type="text/event-stream";body="data: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"original route\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";}
                else body="{\"id\":\"reply\",\"model\":\"model-a\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"original route\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){/* cancellation deliberately closes the original transport */}finally{exchange.close();}});server.start();return server;
    }
    private String url(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
    private JSONObject add(String name,HttpServer server,String key)throws Exception{return store.saveApiConnection("",0,name,new JSONObject().put("baseUrl",url(server)).put("model","model-a").put("provider","openai_chat").put("apiKey",key));}
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","当前新要求"));}
    @Test public void capturedAStaysOnOriginalOriginAfterSelectingBAndReceiptsRetainExactNativeConnection()throws Exception{
        JSONObject pa=add("A",a,"secret-A"),pb=add("B",b,"secret-B");ModelConfig original=store.selectApiConnection(pa.getString("id"),1);store.selectApiConnection(pb.getString("id"),1);
        InferenceRouteContinuity.requireCurrent(original,store.currentForCaptured(original),new RunControl());List<InferenceReceipt> receipts=new ArrayList<>();
        var target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){receipts.add(r);}public void finished(InferenceReceipt r){receipts.add(r);}});
        assertEquals("original route",ModelGateway.complete(original,messages(),null,new RunControl(),target).getString("content"));assertEquals(1,aPosts.get());assertEquals(0,bPosts.get());assertEquals("Bearer secret-A",aAuth);
        JSONObject route=receipts.get(receipts.size()-1).toJson().getJSONObject("route");assertEquals(pa.getString("id"),route.getString("connectionId"));assertEquals(original.capabilityBinding.credentialRevision(),route.getLong("credentialRevision"));assertFalse(route.toString().contains("secret-A"));
        ModelGateway.completeStreaming(store.get(),messages(),null,new RunControl(),event->{});assertEquals(1,bPosts.get());assertEquals("Bearer secret-B",bAuth);
    }
    @Test public void disableAtOriginalReceiptAdmissionPreventsActualHttpAndCannotFallback()throws Exception{
        JSONObject pa=add("A",a,"secret-A");add("B",b,"secret-B");ModelConfig selected=store.selectApiConnection(pa.getString("id"),1);
        var target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r)throws Exception{store.setApiConnectionEnabled(pa.getString("id"),1,false);}public void finished(InferenceReceipt r){}});
        assertThrows(Exception.class,()->ModelGateway.complete(selected,messages(),null,new RunControl(),target));assertEquals(0,aPosts.get());assertEquals(0,bPosts.get());assertEquals(pa.getString("id"),store.apiConnectionsSnapshot().getString("selectedConnectionId"));
    }
    @Test public void aToBToACannotRevivePendingCatalogTicketEvenWhenBindingIsIdentical()throws Exception{
        JSONObject pa=add("A",a,"secret-A"),pb=add("B",b,"secret-B");store.selectApiConnection(pa.getString("id"),1);service.configure(null,ModelBinding.Format.OPENAI,ModelCatalogRoutes.modelsEndpoint(store.get()));ModelBinding binding=store.apiCatalogBinding();
        JSONObject current=store.apiConnectionsSnapshot().getJSONArray("connections").getJSONObject(0);long aRevision=current.getLong("revision");long before=store.apiSelectionRevision();entered=new CountDownLatch(1);release=new CountDownLatch(1);ExecutorService caller=Executors.newSingleThreadExecutor();
        try{Future<Exception> pending=caller.submit(()->{try{service.refresh(binding,new RunControl());return null;}catch(Exception rejected){return rejected;}});assertTrue(entered.await(3,TimeUnit.SECONDS));
            store.selectApiConnection(pb.getString("id"),1);store.selectApiConnection(pa.getString("id"),aRevision);assertEquals(binding,store.apiCatalogBinding());assertTrue(store.apiSelectionRevision()>before);release.countDown();assertNotNull(pending.get(5,TimeUnit.SECONDS));
            assertFalse(service.snapshot().getJSONObject("selected").getBoolean("catalogFresh"));assertEquals(0,aPosts.get());assertEquals(0,bPosts.get());
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test public void strictProfileCannotBeDowngradedByNativeConfigCopyOrRefreshFailure()throws Exception{
        JSONObject pa=add("A",a,"secret-A");store.selectApiConnection(pa.getString("id"),1);service.configure(null,ModelBinding.Format.OPENAI,ModelCatalogRoutes.modelsEndpoint(store.get()));ModelConfig strict=store.get();assertTrue(strict.catalogEnforced);
        ModelConfig forged=new ModelConfig(strict.baseUrl,strict.apiKey,strict.model,false,4,10).withApiConnection(strict.capabilityBinding);
        assertThrows(Exception.class,()->ModelGateway.complete(forged,messages(),null,new RunControl()));assertTrue(store.get().catalogEnforced);assertEquals(0,aPosts.get());
        assertThrows(IllegalArgumentException.class,()->service.configure(strict.capabilityBinding,ModelBinding.Format.OPENAI,java.net.URI.create(url(b)+"/models")));assertTrue(store.get().catalogEnforced);assertEquals(0,gets.get());
    }
}
