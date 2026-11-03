package dev.pocketengine.store;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual Application installation and original run factory. Only the HTTP provider is a fixture; no frame/tracing scope is adopted here. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ModelDiagnosticsApplicationMountTest {
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final AtomicInteger posts=new AtomicInteger();
        final HttpServer server;
        Fixture()throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                posts.incrementAndGet();JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String prompt=input.getJSONArray("messages").getJSONObject(0).optString("content");
                String text=input.has("tools")?"fixture ignores the declared tool":prompt.substring(prompt.lastIndexOf(' ')+1);
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content",text))))
                    .put("usage",new JSONObject().put("prompt_tokens",2).put("completion_tokens",1).put("total_tokens",3)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){throw new IllegalStateException(failure);}finally{exchange.close();}});
            server.start();
            app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("model","fixture-native-mounted-model").put("sendScreenshots",false).put("maxSteps",4));
            app.settings.apiConnections();
        }
        JSONObject work(Callable<JSONObject> action)throws Exception {
            var worker=Executors.newSingleThreadExecutor();
            try{var future=worker.submit(action);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                while(!future.isDone()&&System.nanoTime()<deadline){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}
                return future.get(1,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();}
        }
        @Override public void close(){server.stop(0);app.onTerminate();try{app.features.close();}catch(Exception ignored){}}
    }
    @Test public void applicationInstallsProbeOnItsSingleStoreWithoutExecutingDeviceTools()throws Exception {
        try(Fixture f=new Fixture()){
            assertNotNull(f.app.modelConnections);
            assertNotNull(f.app.settings.apiConfigForCatalog().capabilityBinding);
            assertNull(f.app.settings.apiCatalogBinding());assertFalse(f.app.settings.apiConfigForCatalog().catalogEnforced);
            JSONObject probe=f.work(()->f.app.modelConnections.probe(f.app.settings.apiConfigForCatalog().capabilityBinding,false,new RunControl()));
            assertEquals("passed",probe.getString("text"));assertEquals("unconfirmed",probe.getString("tools"));assertEquals(2,f.posts.get());
            String accounting=probe.getJSONObject("inferenceReference").getString("taskId");
            assertEquals(2,f.app.tasks.inferenceUsage(accounting).getInt("attempts"));
            assertEquals(0,f.app.tasks.get(accounting,false).getJSONArray("operations").length());
            assertFalse(probe.getBoolean("deviceToolsExecuted"));assertFalse(probe.getBoolean("capabilityMetadataChanged"));
            assertEquals("single_synthetic_echo_schema_not_all_tools_or_schemas",probe.getString("toolVerificationScope"));
            assertEquals(probe.toString(),f.app.modelConnections.snapshot().getJSONObject("capabilityProbe").toString());
        }
    }
}
