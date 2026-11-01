package dev.pocketengine.media;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real TaskStore SQLite, loopback HTTP, production media receipt journal and attachment repository. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class MediaWorkflowHostTest {
    private static final byte[] PNG=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");
    private Application app;private TaskStore tasks;private MediaModule media;private MediaWorkflowHost host;private HttpServer server;
    private final AtomicInteger posts=new AtomicInteger(),queries=new AtomicInteger(),cancels=new AtomicInteger(),interrupts=new AtomicInteger(),downloads=new AtomicInteger();
    private final AtomicReference<JSONObject> posted=new AtomicReference<>();private final AtomicReference<String> provider=new AtomicReference<>(),taskId=new AtomicReference<>(),state=new AtomicReference<>("queued");
    private final AtomicReference<Throwable> eventFailure=new AtomicReference<>();private final AtomicBoolean preparedBeforeHttp=new AtomicBoolean(),lostAck=new AtomicBoolean(),waitForCancel=new AtomicBoolean();
    private final CountDownLatch reached=new CountDownLatch(1),release=new CountDownLatch(1);
    @Before public void prepare()throws Exception{
        app=RuntimeEnvironment.getApplication();tasks=new TaskStore(app);host=new MediaWorkflowHost(tasks);media=module();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/proxy/prompt",exchange->{
            posts.incrementAndGet();JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));posted.set(body);provider.set(body.getString("prompt_id"));
            JSONObject actual=tasks.get(taskId.get(),false);JSONArray ops=actual.getJSONArray("operations");preparedBeforeHttp.set(ops.length()==1&&"prepared".equals(ops.getJSONObject(0).getString("state"))&&actual.getInt("coordinatorEpoch")>0);
            reached.countDown();if(waitForCancel.get())release.await(5,TimeUnit.SECONDS);if(lostAck.get()){exchange.close();return;}
            respond(exchange,new JSONObject().put("prompt_id",provider.get()));
        });
        server.createContext("/proxy/history",exchange->{queries.incrementAndGet();JSONObject result=new JSONObject();if(state.get().equals("completed"))result.put(provider.get(),new JSONObject().put("prompt",queueItem()).put("status",new JSONObject().put("completed",true).put("status_str","success"))
                    .put("outputs",new JSONObject().put("9",new JSONObject().put("images",new JSONArray().put(new JSONObject().put("filename","fixture.png").put("subfolder","").put("type","output"))))));respond(exchange,result);});
        server.createContext("/proxy/queue",exchange->{
            if(exchange.getRequestMethod().equals("POST")){JSONObject deletion=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals(provider.get(),deletion.getJSONArray("delete").getString(0));cancels.incrementAndGet();state.set("missing");respond(exchange,new JSONObject());return;}
            JSONArray pending=new JSONArray(),running=new JSONArray();if(posted.get()!=null){if(state.get().equals("queued"))pending.put(queueItem());if(state.get().equals("running"))running.put(queueItem());}
            respond(exchange,new JSONObject().put("queue_pending",pending).put("queue_running",running));
        });
        server.createContext("/proxy/view",exchange->{downloads.incrementAndGet();exchange.sendResponseHeaders(200,PNG.length);exchange.getResponseBody().write(PNG);exchange.close();});
        server.createContext("/proxy/interrupt",exchange->{interrupts.incrementAndGet();respond(exchange,new JSONObject());});server.start();configure();
    }
    private MediaModule module(){return new MediaModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{try{host.recordEvent(event);}catch(Throwable failure){eventFailure.compareAndSet(null,failure);}}));}
    private void configure()throws Exception{media.handle("configure",new JSONObject().put("provider","comfyui").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/proxy"));}
    @After public void close()throws Exception{closeStore();release.countDown();if(host!=null)host.close();if(media!=null)media.close();if(server!=null)server.stop(0);}
    private void closeStore()throws Exception{if(tasks!=null){java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}}
    private static void respond(HttpServer.Exchange exchange,JSONObject body)throws Exception{byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
    private JSONArray queueItem()throws Exception{JSONObject body=posted.get();return new JSONArray().put(1).put(provider.get()).put(body.getJSONObject("prompt")).put(new JSONObject(body.getJSONObject("extra_data").toString()).put("client_id",body.getString("client_id")));}
    private JSONObject args()throws Exception{return new JSONObject().put("configurationRevision",media.snapshot().getString("configurationRevision")).put("workflow",new JSONObject().put("6",new JSONObject().put("class_type","CLIPTextEncode").put("inputs",new JSONObject().put("text","labelled loopback fixture"))));}
    private JSONObject prepared(JSONObject args)throws Exception{JSONObject prepared=host.prepareFromOwner(media,"submitWorkflow",args);taskId.set(prepared.getString("taskId"));return new JSONObject(args.toString()).put("taskId",taskId.get());}
    private int rows(String table)throws Exception{try(SQLiteDatabase db=SQLiteDatabase.openDatabase(app.getDatabasePath("pocket-world.db").getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);Cursor cursor=db.rawQuery("SELECT count(*) FROM "+table,null)){cursor.moveToFirst();return cursor.getInt(0);}}
    @Test public void cleanFirstSuccessHasRealIntentGrantAndOriginalArtifactWithoutTaskSuccess()throws Exception{
        JSONObject input=prepared(args());assertEquals(0,posts.get());assertEquals(0,rows("runs"));assertEquals(0,rows("operations"));
        JSONObject receipt=host.submitFromOwner(media,"submitWorkflow",input);String operation=receipt.getString("operationId");
        assertTrue(preparedBeforeHttp.get());assertTrue(receipt.getBoolean("liveHostControl"));assertTrue(receipt.getBoolean("hostRunBound"));assertEquals(1,posts.get());
        assertEquals(1,rows("tasks"));assertEquals(1,rows("runs"));assertEquals(1,rows("operations"));assertEquals(taskId.get(),tasks.operationTask("media",operation));
        JSONObject task=tasks.get(taskId.get(),false);assertEquals("awaiting_user",task.getString("status"));assertEquals("media_workflow",task.getJSONArray("operations").getJSONObject(0).getString("tool"));assertEquals("consumed",task.getJSONArray("approvals").getJSONObject(0).getString("state"));
        assertTrue(host.submitFromOwner(media,"submitWorkflow",input).getBoolean("knownOperation"));assertEquals(1,posts.get());
        JSONObject query=new JSONObject().put("operationId",operation);assertEquals("queued",host.handleOriginal(media,"getJob",query).getString("status"));
        state.set("completed");JSONObject result=host.handleOriginal(media,"fetchResults",query);assertEquals("response_received",result.getString("status"));assertEquals(1,result.getJSONArray("outputs").length());assertEquals(1,result.getJSONArray("attachments").length());
        JSONObject attachment=result.getJSONArray("attachments").getJSONObject(0);assertArrayEquals(PNG,Files.readAllBytes(AttachmentRepository.get(app).book.file(attachment.getString("id")).toPath()));
        task=tasks.get(taskId.get(),false);assertEquals("needs_confirmation",task.getString("status"));assertFalse(task.getJSONObject("evidence").getBoolean("automaticallyVerified"));
        assertEquals(provider.get(),task.getJSONArray("operations").getJSONObject(0).getJSONObject("receipt").getString("providerJobId"));assertEquals(1,posts.get());assertNull(eventFailure.get());
        tasks.event(taskId.get(),new JSONObject().put("type","user_verification").put("completed",true));tasks.status(taskId.get(),"completed","主人确认");host.handleOriginal(media,"getOperation",query);assertEquals("completed",tasks.get(taskId.get(),false).getString("status"));
    }
    @Test public void changedGraphRevisionAndEpochRejectBeforeProviderDispatch()throws Exception{
        JSONObject input=prepared(args()),changed=new JSONObject(input.toString());changed.getJSONObject("workflow").getJSONObject("6").getJSONObject("inputs").put("text","different");
        assertThrows(SecurityException.class,()->host.submitFromOwner(media,"submitWorkflow",changed));assertEquals(0,posts.get());assertEquals(0,rows("runs"));
        JSONObject receipt=host.submitFromOwner(media,"submitWorkflow",input);String operation=receipt.getString("operationId");
        assertThrows(SecurityException.class,()->host.handleOriginal(media,"getJob",new JSONObject().put("operationId",operation).put("epoch",99)));assertEquals(0,queries.get());
        configure();assertThrows(SecurityException.class,()->host.handleOriginal(media,"getJob",new JSONObject().put("operationId",operation)));assertEquals(0,queries.get());assertEquals(1,posts.get());
        assertEquals("queued",host.handleOriginal(media,"getOperation",new JSONObject().put("operationId",operation)).getString("status"));
        tasks.startAttempt(taskId.get(),"capabilities",1);assertThrows(SecurityException.class,()->host.handleOriginal(media,"getJob",new JSONObject().put("operationId",operation)));assertEquals(0,queries.get());
    }
    @Test public void lostAdmissionRecoversOnlyOriginalIdentityWithoutSecondPostOrNewTask()throws Exception{
        lostAck.set(true);JSONObject input=prepared(args()),receipt=host.submitFromOwner(media,"submitWorkflow",input);assertEquals("unknown",receipt.getString("status"));assertEquals("reconciling",tasks.get(taskId.get(),false).getString("status"));
        assertTrue(host.submitFromOwner(media,"submitWorkflow",input).getBoolean("knownOperation"));assertEquals(1,posts.get());
        host.close();media.close();closeStore();tasks=new TaskStore(app);host=new MediaWorkflowHost(tasks);media=module();
        JSONObject recovered=host.handleOriginal(media,"reconcileOperation",new JSONObject().put("operationId",receipt.getString("operationId")));assertEquals("queued",recovered.getString("status"));assertTrue(recovered.getBoolean("providerJobIdConfirmed"));
        assertEquals(1,posts.get());assertEquals(1,rows("tasks"));assertEquals(1,rows("runs"));assertEquals(1,rows("operations"));assertNull(eventFailure.get());
    }
    @Test public void targetedPendingCancelNeverInterruptsOtherJobsOrClaimsRemoteTermination()throws Exception{
        JSONObject receipt=host.submitFromOwner(media,"submitWorkflow",prepared(args()));JSONObject cancelled=host.handleOriginal(media,"cancelJob",new JSONObject().put("operationId",receipt.getString("operationId")));
        assertEquals("requested",cancelled.getString("cancelStatus"));assertEquals("stopping",cancelled.getString("status"));assertEquals(1,cancels.get());assertEquals(0,interrupts.get());assertNotEquals("cancelled",tasks.get(taskId.get(),false).getString("status"));
    }
    @Test public void runningCancelIsExplicitlyUnsupportedWithoutGlobalInterrupt()throws Exception{
        JSONObject receipt=host.submitFromOwner(media,"submitWorkflow",prepared(args()));state.set("running");JSONObject cancelled=host.handleOriginal(media,"cancelJob",new JSONObject().put("operationId",receipt.getString("operationId")));
        assertEquals("unsupported_running",cancelled.getString("cancelStatus"));assertEquals(0,cancels.get());assertEquals(0,interrupts.get());
    }
    @Test public void urgentLocalWaitCancellationRetainsUnknownOriginalEffect()throws Exception{
        waitForCancel.set(true);JSONObject input=prepared(args());ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<JSONObject> result=worker.submit(()->host.submitFromOwner(media,"submitWorkflow",input));assertTrue(reached.await(5,TimeUnit.SECONDS));assertNotNull(host.currentControl(taskId.get()));
            JSONObject stopped=host.cancelTask(taskId.get());assertEquals("local_wait_stop_requested",stopped.getString("status"));assertFalse(stopped.getBoolean("providerStateConfirmed"));release.countDown();
            JSONObject receipt=result.get(5,TimeUnit.SECONDS);assertEquals("unknown",receipt.getString("status"));assertEquals("reconciling",tasks.get(taskId.get(),false).getString("status"));assertEquals(1,posts.get());assertEquals(0,interrupts.get());assertNull(eventFailure.get());
            assertTrue(host.submitFromOwner(media,"submitWorkflow",input).getBoolean("knownOperation"));assertEquals(1,posts.get());
        }finally{release.countDown();worker.shutdownNow();}
    }
}
