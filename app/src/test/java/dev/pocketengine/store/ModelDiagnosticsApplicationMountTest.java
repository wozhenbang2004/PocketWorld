package dev.pocketengine.store;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.AnswerSourceFrames;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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

/** Actual Application installation and original run factory. Only the HTTP provider and pixels are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ModelDiagnosticsApplicationMountTest {
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5n8AAAAASUVORK5CYII=";
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final AtomicInteger posts=new AtomicInteger();
        final HttpServer server;
        boolean diagnostic;
        Fixture(boolean diagnostic)throws Exception {
            this.diagnostic=diagnostic;
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                posts.incrementAndGet();JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String prompt=input.getJSONArray("messages").getJSONObject(0).optString("content");
                String text=this.diagnostic?(input.has("tools")?"fixture ignores the declared tool":prompt.substring(prompt.lastIndexOf(' ')+1)):"PW_EVAL_NATIVE_FINAL_572";
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content",text))))
                    .put("usage",new JSONObject().put("prompt_tokens",2).put("completion_tokens",1).put("total_tokens",3)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
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
        @Override public void close(){server.stop(0);}
    }
    @Test public void applicationInstallsProbeOnItsSingleStoreAndRetainsOnlyTheExactOriginalFrame()throws Exception {
        try(Fixture f=new Fixture(true)){
            assertNotNull(f.app.modelConnections);assertNotNull(f.app.detailedTraces);assertNotNull(f.app.answerSourceFrames);
            assertFalse(f.app.detailedTraces.snapshot().getBoolean("enabled"));
            assertNotNull(f.app.settings.apiConfigForCatalog().capabilityBinding);assertNull(f.app.settings.apiCatalogBinding());assertFalse(f.app.settings.apiConfigForCatalog().catalogEnforced);
            JSONObject probe=f.work(()->f.app.modelConnections.probe(f.app.settings.apiConfigForCatalog().capabilityBinding,false,new RunControl()));
            assertEquals("passed",probe.getString("text"));assertEquals("failed",probe.getString("tools"));assertEquals(2,f.posts.get());
            String accounting=probe.getJSONObject("inferenceReference").getString("taskId");assertEquals(2,f.app.tasks.inferenceUsage(accounting).getInt("attempts"));assertEquals(0,f.app.tasks.get(accounting,false).getJSONArray("operations").length());
            JSONObject task=f.app.tasks.create("PW-EVAL-MOUNTED-FRAME synthetic pixels only");String id=task.getString("id");JSONObject attempt=f.app.tasks.startAttempt(id,"main_screen",2);RunControl original=new RunControl();original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));
            try{JSONObject observation=new JSONObject().put("ok",true).put("observationId",UUID.randomUUID().toString()).put("imageBase64",PNG).put("imageMimeType","image/png");AnswerSourceFrames.retain(id,original,observation);
                JSONObject frame=observation.getJSONObject("retainedFrame");assertEquals(id,frame.getString("taskId"));assertEquals(attempt.getString("runId"),frame.getString("runId"));assertEquals(attempt.getInt("coordinatorEpoch"),frame.getInt("epoch"));assertEquals("data:image/png;base64,"+PNG,f.app.answerSourceFrames.read(frame).getString("dataUrl"));
            }finally{original.cancel();f.app.tasks.endAttempt(id,attempt.getString("runId"),"cancelled","synthetic frame fixture closed",new JSONObject());}
        }
    }
    @Test public void actualCapabilityRunArchivesCommittedModelTimingAndItsNativeAnswerManifest()throws Exception {
        try(Fixture f=new Fixture(false)){
            assertNotNull(f.app.detailedTraces);f.app.detailedTraces.configure(true,1);
            try{ModelConfig config=f.app.settings.get();String id=f.app.tasks.createCapabilityTask("PW-EVAL-MOUNTED-TRACE read a fixture reply without actions","").getString("id");
                var scope=EngineApplication.class.getDeclaredMethod("initializeOwnerExecutionScope",String.class,ModelConfig.class,JSONObject.class);scope.setAccessible(true);scope.invoke(f.app,id,config,null);
                var run=EngineApplication.class.getDeclaredMethod("runCapabilityAttempt",String.class,JSONArray.class,ModelConfig.class,RunControl.class);run.setAccessible(true);RunControl original=new RunControl();
                f.work(()->{try{return (JSONObject)run.invoke(f.app,id,null,config,original);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}});
                JSONObject saved=f.app.tasks.get(id,false),manifest=saved.getJSONObject("evidence").getJSONObject("memoryReference").getJSONObject("answerSources");
                assertEquals(id,manifest.getString("taskId"));assertEquals(saved.getString("runId"),manifest.getString("runId"));assertEquals(saved.getInt("coordinatorEpoch"),manifest.getInt("epoch"));assertEquals("pocketworld.answer-sources.v1",manifest.getString("format"));
                JSONObject trace=f.app.detailedTraces.read(saved.getString("runId"));assertTrue(trace.getBoolean("available"));boolean model=false,root=false;JSONArray spans=trace.getJSONArray("spans");
                for(int i=0;i<spans.length();i++){String name=spans.getJSONObject(i).getString("name");model|=name.equals("model.MAIN");root|=name.equals("task.run");}
                assertTrue("Original committed inference must reach the installed diagnostic observer",model);assertTrue("Original run must close its diagnostic lifecycle",root);assertFalse(trace.toString().contains("PW_EVAL_NATIVE_FINAL_572"));assertFalse(trace.toString().contains("PW-EVAL-MOUNTED-TRACE"));assertEquals(1,f.posts.get());assertEquals(1,f.app.tasks.inferenceUsage(id).getInt("attempts"));
                JSONArray operations=saved.getJSONArray("operations");for(int i=0;i<operations.length();i++)assertEquals(dev.pocketengine.features.memory.TaskMemoryJournal.TOOL,operations.getJSONObject(i).getString("tool"));
            }finally{f.app.detailedTraces.configure(false,1);}
        }
    }
}
