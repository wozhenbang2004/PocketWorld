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
    @Test public void ownerDownloadsOnlyThePaidOriginalAfterPolicyToggleWithExactTaskStoreProof()throws Exception{
        String url="https://cdn.example.test/original.png?signed_secret=owner-fixture";
        server.createContext("/v1/images/generations",exchange->{posts.incrementAndGet();exchange.getRequestBody().readAllBytes();respond(exchange,new JSONObject().put("data",new JSONArray().put(new JSONObject().put("url",url))));});
        server.createContext("/original.png",exchange->{assertEquals("GET",exchange.getRequestMethod());assertEquals("signed_secret=owner-fixture",exchange.getRequestURI().getRawQuery());downloads.incrementAndGet();exchange.sendResponseHeaders(200,PNG.length);exchange.getResponseBody().write(PNG);exchange.close();});
        JSONObject configuration=new JSONObject().put("provider","openai_compatible").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("imageModel","fixture-model").put("allowResultUrls",false);
        media.handle("configure",configuration);JSONObject args=new JSONObject().put("configurationRevision",media.snapshot().getString("configurationRevision")).put("prompt","exact fixture owner input");
        JSONObject prepared=host.prepareFromOwner(media,"generateImage",args);taskId.set(prepared.getString("taskId"));JSONObject receipt=host.submitFromOwner(media,"generateImage",new JSONObject(args.toString()).put("taskId",taskId.get()));String operation=receipt.getString("operationId");
        assertEquals("owner_download_policy_required",receipt.getString("resultStatus"));assertEquals(1,posts.get());assertEquals(0,downloads.get());
        configuration.remove("apiKey");configuration.put("allowResultUrls",true);media.handle("configure",configuration);
        JSONObject binding=new JSONObject().put("operationId",operation).put("taskId",taskId.get()).put("runId",receipt.getString("runId")).put("epoch",receipt.getInt("hostEpoch")).put("configurationRevision",receipt.getString("configurationRevision"));
        assertThrows(SecurityException.class,()->host.handleOriginal(media,"fetchResults",binding));
        assertThrows(SecurityException.class,()->host.handleOriginal(media,"reviewResultDownload",binding,new dev.pocketengine.core.RunControl()));
        assertThrows(SecurityException.class,()->media.handle("reviewResultDownload",binding));
        assertThrows(SecurityException.class,()->host.handleOriginal(media,"reviewResultDownload",new JSONObject(binding.toString()).put("epoch",999)));
        java.lang.reflect.Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)database.get(tasks);String originalDocument;
        try(Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{taskId.get()})){assertTrue(row.moveToFirst());originalDocument=row.getString(0);}
        JSONObject denied=new JSONObject(originalDocument);denied.getJSONArray("approvals").getJSONObject(0).put("state","denied");db.execSQL("UPDATE tasks SET document=? WHERE task_id=?",new Object[]{denied.toString(),taskId.get()});
        assertThrows(SecurityException.class,()->host.handleOriginal(media,"reviewResultDownload",binding));db.execSQL("UPDATE tasks SET document=? WHERE task_id=?",new Object[]{originalDocument,taskId.get()});
        JSONObject reviewed=host.handleOriginal(media,"reviewResultDownload",binding),review=reviewed.getJSONObject("resultDownloadReview");assertFalse(reviewed.toString().contains("signed_secret"));assertEquals(0,downloads.get());
        JSONObject confirmation=new JSONObject(binding.toString()).put("downloadReview",review);
        assertThrows(SecurityException.class,()->host.handleOriginal(media,"confirmResultDownload",confirmation,new dev.pocketengine.core.RunControl()));
        assertThrows(SecurityException.class,()->host.handleOriginal(media,"confirmResultDownload",new JSONObject(confirmation.toString()).put("url","https://attacker.example/new")));
        JSONObject tampered=new JSONObject(confirmation.toString());tampered.getJSONObject("downloadReview").put("providerResponseSha256","c".repeat(64));assertThrows(SecurityException.class,()->host.handleOriginal(media,"confirmResultDownload",tampered));
        try(MediaResultLoopbackFixture network=new MediaResultLoopbackFixture(url,"http://127.0.0.1:"+server.getAddress().getPort())){
            JSONObject fetched=host.handleOriginal(media,"confirmResultDownload",confirmation);assertEquals("saved",fetched.getString("resultStatus"));assertEquals(1,downloads.get());assertEquals(1,posts.get());assertNull(network.auth.get(0));assertEquals(java.util.List.of(url),network.requested);
            JSONObject artifact=fetched.getJSONObject("attachment");assertArrayEquals(PNG,Files.readAllBytes(AttachmentRepository.get(app).book.file(artifact.getString("id")).toPath()));assertEquals(operation,artifact.getJSONObject("source").getString("operationId"));
            assertThrows(SecurityException.class,()->host.handleOriginal(media,"confirmResultDownload",confirmation));assertEquals(1,downloads.get());
        }
        JSONObject task=tasks.get(taskId.get(),false);assertEquals(1,rows("tasks"));assertEquals(1,rows("runs"));assertEquals(1,rows("operations"));assertEquals("consumed",task.getJSONArray("approvals").getJSONObject(0).getString("state"));assertEquals(operation,task.getJSONArray("operations").getJSONObject(0).getString("operationId"));assertEquals("needs_confirmation",task.getString("status"));assertNull(eventFailure.get());
    }

    @Test public void fullProviderJournalSettlesUnsentOwnerOperationWithoutLosingOldReceipts()throws Exception{
        JSONObject input=prepared(args());media.close();
        JSONObject receipts=new JSONObject();for(int i=0;i<dev.pocketengine.media.jobs.MediaJobJournal.MAX_RECEIPTS;i++)receipts.put("old-"+i,new JSONObject().put("operationId","old-"+i).put("status","unknown").put("dispatched",true));
        java.io.File file=new java.io.File(app.getFilesDir(),"media-receipts-v1.json");
        Files.writeString(file.toPath(),new JSONObject().put("version",1).put("receipts",receipts).toString());byte[] before=Files.readAllBytes(file.toPath());media=module();
        assertThrows(IllegalStateException.class,()->host.submitFromOwner(media,"submitWorkflow",input));
        JSONObject task=tasks.get(taskId.get(),false);assertEquals("failed",task.getString("status"));assertFalse(tasks.hasUnresolved(taskId.get()));
        JSONObject operation=task.getJSONArray("operations").getJSONObject(0),receipt=operation.getJSONObject("receipt");
        assertEquals("failed",receipt.getString("status"));assertFalse(receipt.getBoolean("dispatched"));assertEquals("receipt_capacity",receipt.getString("failureReason"));
        assertEquals(operation.getString("operationId"),receipt.getString("operationId"));assertEquals(taskId.get(),receipt.getString("taskId"));assertEquals(task.getString("runId"),receipt.getString("runId"));
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals(256,media.snapshot().getJSONArray("jobs").length());
        host.close();media.close();closeStore();tasks=new TaskStore(app);host=new MediaWorkflowHost(tasks);media=module();
        JSONObject original=host.submitFromOwner(media,"submitWorkflow",input);assertTrue(original.getBoolean("knownOperation"));assertFalse(original.getBoolean("dispatched"));
        assertEquals(receipt.getString("operationId"),original.getString("operationId"));assertEquals(0,posts.get());assertEquals(0,queries.get());assertEquals(0,downloads.get());
        assertEquals(1,rows("tasks"));assertEquals(1,rows("runs"));assertEquals(1,rows("operations"));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }
    @Test public void unclassifiedJournalWriteFailureKeepsUnknownOriginalEffect()throws Exception{
        JSONObject input=prepared(args());java.io.File file=new java.io.File(app.getFilesDir(),"media-receipts-v1.json");
        assertTrue(file.mkdir());Files.writeString(new java.io.File(file,"unrelated").toPath(),"preserve");
        assertThrows(Exception.class,()->host.submitFromOwner(media,"submitWorkflow",input));
        JSONObject task=tasks.get(taskId.get(),false),receipt=task.getJSONArray("operations").getJSONObject(0).getJSONObject("receipt");
        assertEquals("reconciling",task.getString("status"));assertEquals("unknown",receipt.getString("status"));assertFalse(receipt.has("dispatched"));
        assertTrue(host.submitFromOwner(media,"submitWorkflow",input).getBoolean("knownOperation"));assertEquals(0,posts.get());assertEquals(1,rows("operations"));
        assertEquals("preserve",Files.readString(new java.io.File(file,"unrelated").toPath()));
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
    @Test public void lateOriginalReceiptSurvivesANewerRunWithoutSettlingOrChargingIt()throws Exception{
        JSONObject first=host.submitFromOwner(media,"submitWorkflow",prepared(args()));
        String id=first.getString("taskId"),operation=first.getString("operationId"),oldRun=first.getString("runId");
        JSONObject oldSource=tasks.ownerMediaOperationSource(id,operation);
        JSONObject newer=tasks.startAttempt(id,"capabilities",1);String newRun=newer.getString("runId");int newEpoch=newer.getInt("coordinatorEpoch");
        assertNotEquals(oldRun,newRun);assertTrue(newEpoch>oldSource.getInt("epoch"));
        tasks.status(id,"reconciling","The newer run is awaiting its own owner decision");
        JSONObject before=tasks.get(id,false);String grantAndCheckpoint=runGrantAndCheckpoint(newRun);
        JSONObject late=new JSONObject(first.toString()).put("status","running").put("delayedNativeFixture",true);
        host.recordEvent(new JSONObject().put("type","feature_event").put("feature","media").put("payload",late));
        JSONObject after=tasks.get(id,false),original=after.getJSONArray("operations").getJSONObject(0);
        assertEquals(oldRun,original.getString("runId"));assertEquals("running",original.getJSONObject("receipt").getString("status"));assertTrue(original.getJSONObject("receipt").getBoolean("delayedNativeFixture"));
        assertEquals(newRun,after.getString("runId"));assertEquals(newEpoch,after.getInt("coordinatorEpoch"));assertEquals("reconciling",after.getString("status"));assertEquals(before.getString("summary"),after.getString("summary"));
        assertEquals(String.valueOf(before.opt("executionScope")),String.valueOf(after.opt("executionScope")));assertEquals(grantAndCheckpoint,runGrantAndCheckpoint(newRun));
        assertEquals(2,rows("runs"));assertEquals(1,rows("operations"));assertEquals(1,posts.get());assertEquals(0,queries.get());
        JSONObject forged=new JSONObject(late.toString()).put("runId",newRun);
        assertThrows(SecurityException.class,()->host.recordEvent(new JSONObject().put("type","feature_event").put("feature","media").put("payload",forged)));
        assertEquals(oldRun,tasks.get(id,false).getJSONArray("operations").getJSONObject(0).getJSONObject("receipt").getString("runId"));
        assertEquals(grantAndCheckpoint,runGrantAndCheckpoint(newRun));assertNull(eventFailure.get());
    }
    private String runGrantAndCheckpoint(String run)throws Exception{
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(app.getDatabasePath("pocket-world.db").getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);Cursor cursor=db.rawQuery("SELECT status,grant_json,checkpoint FROM runs WHERE run_id=?",new String[]{run})){
            assertTrue(cursor.moveToFirst());return new JSONArray().put(cursor.getString(0)).put(cursor.getString(1)).put(cursor.getString(2)).toString();
        }
    }
}
