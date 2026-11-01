package dev.pocketengine.media;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

/** Actual original SQLite approval, provider HTTP and original MediaJobs file journal.
 * No model fixture grant, new executor authority, UI observer or paid provider account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class MediaReadContinuationTest {
    private Application app;private TaskStore tasks;private MediaModule module;private MediaWorkflowHost host;private ExecutorService workers;private HttpServer server;
    private final AtomicInteger posts=new AtomicInteger(),histories=new AtomicInteger(),queues=new AtomicInteger(),deletions=new AtomicInteger();
    private final AtomicReference<JSONObject> submitted=new AtomicReference<>();private final AtomicReference<Throwable> callbackFailure=new AtomicReference<>();private final AtomicBoolean complete=new AtomicBoolean();
    @Before public void setup()throws Exception{
        app=RuntimeEnvironment.getApplication();tasks=new TaskStore(app);workers=Executors.newSingleThreadExecutor();host=new MediaWorkflowHost(tasks,workers);
        module=new MediaModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{try{host.recordEvent(event);}catch(Throwable failed){callbackFailure.compareAndSet(null,failed);}}));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/prompt",exchange->{posts.incrementAndGet();JSONObject value=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));submitted.set(value);reply(exchange,new JSONObject().put("prompt_id",value.getString("prompt_id")));});
        server.createContext("/history",exchange->{histories.incrementAndGet();JSONObject result=new JSONObject(),body=submitted.get();if(complete.get()&&body!=null)result.put(body.getString("prompt_id"),new JSONObject().put("prompt",queueItem()).put("status",new JSONObject().put("completed",true).put("status_str","success")).put("outputs",new JSONObject()));reply(exchange,result);});
        server.createContext("/queue",exchange->{if(exchange.getRequestMethod().equals("POST")){deletions.incrementAndGet();reply(exchange,new JSONObject());return;}queues.incrementAndGet();reply(exchange,new JSONObject().put("queue_pending",complete.get()?new JSONArray():new JSONArray().put(queueItem())).put("queue_running",new JSONArray()));});server.start();
        module.handle("configure",new JSONObject().put("provider","comfyui").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()));
    }
    @After public void teardown()throws Exception{
        if(host!=null)host.close();if(workers!=null){workers.shutdownNow();assertTrue("original host worker must really drain before SQLite closes",workers.awaitTermination(5,TimeUnit.SECONDS));}
        if(module!=null)module.close();if(server!=null)server.stop(0);if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
    private JSONArray queueItem()throws Exception{JSONObject body=submitted.get();if(body==null)return new JSONArray();return new JSONArray().put(1).put(body.getString("prompt_id")).put(body.getJSONObject("prompt")).put(new JSONObject(body.getJSONObject("extra_data").toString()).put("client_id",body.getString("client_id")));}
    private static void reply(HttpServer.Exchange exchange,JSONObject result)throws Exception{byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
    private JSONObject submit(boolean auto)throws Exception{
        JSONObject args=new JSONObject().put("configurationRevision",module.snapshot().getString("configurationRevision")).put("observeUntilComplete",auto).put("workflow",new JSONObject().put("6",new JSONObject().put("class_type","CLIPTextEncode").put("inputs",new JSONObject().put("text","explicit local test workflow"))));
        JSONObject preview=host.prepareFromOwner(module,"submitWorkflow",args);assertEquals(0,posts.get());return host.submitFromOwner(module,"submitWorkflow",new JSONObject(args.toString()).put("taskId",preview.getString("taskId")));
    }
    private static void await(Callable<Boolean> condition,String why)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<until){if(condition.call())return;Thread.sleep(25);}fail(why);}
    @Test public void ownerApprovedOriginalReadContinuesWithoutAnyUiObserverAndEndsWithoutAnotherPost()throws Exception{
        JSONObject receipt=submit(true);String task=receipt.getString("taskId"),operation=receipt.getString("operationId");assertEquals("queued",receipt.getString("status"));
        await(()->histories.get()>0&&host.continuationSnapshot().getInt("activeOriginalReadContinuations")==1,"confirmed original job must enter actual host-only GET continuation");
        JSONObject source=tasks.ownerMediaOperationSource(task,operation);assertTrue(tasks.ownerMediaReadContinuationExpiry(source)>System.currentTimeMillis());assertTrue(source.getJSONObject("arguments").getBoolean("appReadContinuation"));
        assertFalse(host.continuationSnapshot().getBoolean("osBackgroundGuaranteed"));assertEquals(1,posts.get());complete.set(true);
        await(()->host.continuationSnapshot().getInt("activeOriginalReadContinuations")==0,"real terminal provider GET must drain original worker");
        JSONObject actual=module.originalReceiptForHost(operation),stored=tasks.get(task,false);assertEquals("response_received",actual.getString("status"));assertEquals(submitted.get().getString("prompt_id"),actual.getString("providerJobId"));assertEquals("needs_confirmation",stored.getString("status"));assertEquals(1,stored.getJSONArray("operations").length());assertEquals(1,posts.get());assertEquals(0,deletions.get());assertNull(callbackFailure.get());
        int count=histories.get()+queues.get();host.startConfirmedReadContinuation(module,task,operation);Thread.sleep(250);assertEquals("terminal/once-marked original job must not acquire a new continuation",count,histories.get()+queues.get());
        JSONArray events=stored.getJSONArray("events");boolean started=false,ended=false;for(int i=0;i<events.length();i++){String type=events.getJSONObject(i).optString("type");started|=type.equals("media_read_continuation_started");ended|=type.equals("media_read_continuation_ended");}assertTrue(started);assertTrue(ended);
    }
    @Test public void noReviewedPolicyDoesNotPollAndStoppedOriginalPolicyCannotResetOnDuplicateOrColdHost()throws Exception{
        JSONObject receipt=submit(false);String task=receipt.getString("taskId"),operation=receipt.getString("operationId");Thread.sleep(250);assertEquals(0,histories.get()+queues.get());assertThrows(SecurityException.class,()->tasks.ownerMediaReadContinuationExpiry(tasks.ownerMediaOperationSource(task,operation)));host.startConfirmedReadContinuation(module,task,operation);Thread.sleep(250);assertEquals(0,histories.get()+queues.get());
        // A second fresh owner task may explicitly review its own finite continuation. The
        // first original job remains separate; no original POST is replayed.
        JSONObject args=new JSONObject().put("configurationRevision",module.snapshot().getString("configurationRevision")).put("observeUntilComplete",true).put("workflow",new JSONObject().put("6",new JSONObject().put("class_type","CLIPTextEncode").put("inputs",new JSONObject().put("text","second original workflow"))));
        JSONObject preview=host.prepareFromOwner(module,"submitWorkflow",args);JSONObject second=host.submitFromOwner(module,"submitWorkflow",new JSONObject(args.toString()).put("taskId",preview.getString("taskId")));String secondTask=second.getString("taskId"),secondOp=second.getString("operationId");
        await(()->histories.get()>0,"second explicit owner policy must really perform its own GET");JSONObject oldSource=tasks.ownerMediaOperationSource(secondTask,secondOp);JSONObject stopped=host.cancelTask(secondTask);assertNotNull(stopped);assertFalse(stopped.getBoolean("remoteTerminationConfirmed"));await(()->host.continuationSnapshot().getInt("activeOriginalReadContinuations")==0,"cancel must actually drain the original GET worker");
        int count=histories.get()+queues.get();assertThrows(SecurityException.class,()->tasks.ownerMediaReadContinuationExpiry(oldSource));host.startConfirmedReadContinuation(module,secondTask,secondOp);Thread.sleep(250);assertEquals(count,histories.get()+queues.get());
        host.close();host=new MediaWorkflowHost(tasks,workers);host.startConfirmedReadContinuation(module,secondTask,secondOp);Thread.sleep(250);assertEquals(count,histories.get()+queues.get());assertEquals(2,posts.get());assertEquals(0,deletions.get());assertEquals("queued",module.originalReceiptForHost(secondOp).getString("status"));assertNull(callbackFailure.get());
    }
}
