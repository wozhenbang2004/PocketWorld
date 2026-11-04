package dev.pocketengine;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.preview.ProjectPreviewBundle;
import dev.pocketengine.features.local.preview.ProjectPreviewDocument;
import dev.pocketengine.features.local.preview.ProjectPreviewReferences;
import dev.pocketengine.features.local.project.ProjectOwnerWorkflows;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.runtime.ProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import dev.pocketengine.features.local.runtime.RuntimeInvocation;
import dev.pocketengine.features.local.runtime.RuntimeReceipt;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.RepeatToolNotice;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;

/** Real Engine callbacks, Assistant slots, SQLite and Local sources. Only startup presentation,
 * worker pickup and model HTTP are fixture boundaries; no source-tracking proof is fabricated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=ProjectConversationContinuationNativeTest.PresentationBoundary.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectConversationContinuationNativeTest {
    @Implements(EngineApplication.class)
    public static final class PresentationBoundary extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){}
        @Implementation protected void pushTask(String id){}
    }
    private static final String HTML="<!doctype html><button id='original'>Original element</button>";
    private static final String OLD_HISTORY="ORIGINAL-CHECKPOINT-MUST-SURVIVE-CONTINUE";
    private static final String FEEDBACK="Continue this original project; inspect before changing anything.";
    private static final class NoProcess implements ProjectRuntime {
        public JSONObject describe()throws Exception{return new JSONObject().put("runtimeId","continuation_fixture").put("available",false).put("canExecute",false).put("phases",new JSONArray());}
        public RuntimeReceipt dispatch(RuntimeInvocation ignored){throw new AssertionError("Continue must not launch a local process");}
        public RuntimeReceipt query(RuntimeExecutionRef ignored){throw new AssertionError("Continue must not query a local process");}
        public RuntimeReceipt requestCancel(RuntimeExecutionRef ignored){throw new AssertionError("No local process was admitted");}
    }
    private EngineApplication app;
    private SQLiteDatabase db;
    private AssistantModule assistant;
    private LocalModule local;
    private WorkspaceModule workspace;
    private NativePluginHost plugins;
    private ProjectPreviewDocument document;
    private ProjectPreviewReferences references;
    private ThreadPoolExecutor turns;
    private final CountDownLatch releaseWorkers=new CountDownLatch(1);
    private final ExecutorService pickup=Executors.newSingleThreadExecutor();
    private final List<JSONObject> events=new CopyOnWriteArrayList<>();
    private Consumer<JSONObject> eventHook=event->{};
    private final String conversation=UUID.randomUUID().toString();
    private String task,producingRun,project,checkout;
    private int producingEpoch;
    private JSONObject reference,reviewed,oldCheckpoint;
    private final RunControl producer=new RunControl();
    private final List<RunControl> additionalControls=new ArrayList<>();

    private static Object field(Object target,String name)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void field(Object target,String name,Object value)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private Object engine(String name,Class<?>[] parameters,Object... arguments)throws Exception {
        Method method=EngineApplication.class.getDeclaredMethod(name,parameters);method.setAccessible(true);
        try{return method.invoke(app,arguments);}catch(InvocationTargetException failed){
            Throwable original=failed.getCause();if(original instanceof Exception e)throw e;if(original instanceof Error e)throw e;throw failed;
        }
    }
    @SuppressWarnings("unchecked") private java.util.concurrent.ConcurrentHashMap<String,RunControl> controls()throws Exception {
        return (java.util.concurrent.ConcurrentHashMap<String,RunControl>)field(app,"capabilityControls");
    }
    @SuppressWarnings("unchecked") private java.util.Map<RunControl,Object> bindings()throws Exception {
        return (java.util.Map<RunControl,Object>)field(app,"assistantInputs");
    }
    private void emit(JSONObject event){events.add(event);eventHook.accept(event);}

    @Before public void prepare()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.tasks=new TaskStore(app);ReadyApplicationFixture.complete(app);
        db=(SQLiteDatabase)field(app.tasks,"db");app.settings=new SettingsStore(app);
        app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","https://example.invalid").putString("model","fixture")
                .putString("provider","openai_chat").putBoolean("sendScreenshots",false).putInt("maxSteps",20).putInt("requestTimeoutSeconds",5).commit();
        app.features=new FeatureHost(this::emit,app.tasks,()->false);
        FeatureContext context=new FeatureContext(app,app.settings,new SecretVault(app),this::emit);
        MemorySourceBridge sources=new MemorySourceBridge(app.features,app.tasks);app.features.setMemorySynchronizer(sources::synchronize);field(app,"memorySources",sources);
        MemoryModule memory=new MemoryModule(context,sources.assertionResolver());app.features.register(memory);
        assistant=new AssistantModule(context,memory,(cid,goal,history,config,control)->(JSONObject)engine("runAssistantTurn",
                new Class<?>[]{String.class,String.class,JSONArray.class,ModelConfig.class,RunControl.class},cid,goal,history,config,control),app.tasks);
        app.features.register(assistant);sources.bindAssistant(assistant);
        assistant.setSourceSynchronizer(()->sources.synchronize(new JSONObject().put("ownerId","owner").put("spaceId","private")));
        assistant.setNativeInputHost((AssistantModule.NativeInputHost)engine("assistantInputHost",new Class<?>[]{}));
        workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,new NoProcess());app.features.register(workspace);app.features.register(local);sources.bindWorkspace(workspace);
        references=new ProjectPreviewReferences(app.tasks,local);app.ownerPreviewReferences=references;assistant.setPreviewReferences(references);
        field(app,"projectOwnerWorkflows",new ProjectOwnerWorkflows(local,app.tasks,assistant));
        PluginsModule module=new PluginsModule(context);app.features.register(module);
        plugins=new NativePluginHost(app,module,app.tasks,(binding,control)->engine("checkLiveExtensionControl",
                new Class<?>[]{ExtensionRuntime.Binding.class,RunControl.class},binding,control),ignored->{});field(app,"pluginHost",plugins);
        app.setOriginalDeviceChildStops((id,original)->{assertTrue(original.isCancelled());assertEquals(id,original.hostRunIdentity().taskId());});
        app.tasks.conversationRepository().transaction(()->{
            app.tasks.conversationRepository().saveConversation(new JSONObject().put("conversationId",conversation).put("title","Original project").put("messages",new JSONArray()));return null;
        });
        project=workspace.handle("create",new JSONObject().put("name","Original continuation source")).getString("id");
        workspace.handle("write",new JSONObject().put("projectId",project).put("path","index.html").put("expectedVersion",0).put("content",HTML));
        checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");
        String digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");
        task=app.tasks.createCapabilityTask("Continue the same original project",conversation).getString("id");app.tasks.initializeExecutionScope(task,20);
        JSONObject attempt=app.tasks.startAttempt(task,"capabilities",20);producingRun=attempt.getString("runId");producingEpoch=attempt.getInt("coordinatorEpoch");producer.bindHostRun(task,producingRun,producingEpoch);
        app.tasks.bindDevelopmentContext(task,producingRun,producingEpoch,producer,new JSONObject().put("format","pocketworld.development-context.v1")
                .put("projectId",project).put("checkoutId",checkout).put("runtime","termux").put("initialSourceDigest",digest).put("clientMessageId",UUID.randomUUID().toString()));
        app.tasks.authorizeTools(task,producingRun,new JSONArray().put("project_preview").put("project_read_file").put("project_status"));
        JSONObject preview=local.handle("project.preview",new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",digest).put("entryPath","index.html"));
        app.tasks.recordDevelopmentPreview(task,producingRun,producingEpoch,producer,preview);ProjectPreviewBundle.Verified bundle=local.projectPreviewForOwner(preview.getString("previewId"),checkout);
        document=new ProjectPreviewDocument(bundle);document.loading();document.loaded(bundle.entryUrl);long selection=document.beginSelection();
        reference=references.register(task,document.select(document.generation(),selection,bundle.entryUrl,new JSONObject().put("selectorHint","button#original")
                .put("text","Original element").put("htmlSnippet","<button id=\"original\">Original element</button>")
                .put("bounds",new JSONObject().put("x",1).put("y",1).put("width",100).put("height",20))
                .put("truncated",new JSONObject().put("selectorHint",false).put("text",false).put("htmlSnippet",false))));document.endSelection();
        Method snapshot=RepeatToolNotice.class.getDeclaredMethod("snapshot");snapshot.setAccessible(true);
        app.tasks.journal(task,producingRun,producingEpoch,producer).checkpoint(new JSONArray().put(new JSONObject().put("role","system").put("content",OLD_HISTORY))
                .put(new JSONObject().put("role","assistant").put("content","The original project has already been inspected.")),3,new JSONObject(),(JSONObject)snapshot.invoke(new RepeatToolNotice()));
        oldCheckpoint=checkpoint(producingRun);app.tasks.endAttempt(task,producingRun,"completed","Original project finished",new JSONObject());reviewed=app.tasks.get(task,false);
        turns=(ThreadPoolExecutor)field(assistant,"turns");CountDownLatch entered=new CountDownLatch(2);
        for(int i=0;i<2;i++)turns.execute(()->{entered.countDown();try{releaseWorkers.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
        assertTrue("Original Assistant workers did not reach the pickup boundary",entered.await(3,TimeUnit.SECONDS));
    }
    @After public void close()throws Exception {
        eventHook=ignored->{};
        try{if(assistant!=null)assistant.close();}finally{
            releaseWorkers.countDown();pickup.shutdownNow();if(turns!=null){turns.shutdownNow();assertTrue(turns.awaitTermination(3,TimeUnit.SECONDS));}
            for(RunControl control:additionalControls)control.cancel();producer.cancel();
            if(references!=null)references.close();if(document!=null)document.release();if(plugins!=null)plugins.close();
            if(app!=null){((ExecutorService)field(app,"capabilityWorkers")).shutdownNow();if(app.features!=null)app.features.close();}
            shadowOf(Looper.getMainLooper()).idle();if(db!=null)db.close();
        }
    }
    private JSONObject checkpoint(String run)throws Exception {
        try(Cursor row=db.rawQuery("SELECT checkpoint FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}
    }
    private void noLease(String run){try(Cursor row=db.rawQuery("SELECT 1 FROM leases WHERE run_id=?",new String[]{run})){assertFalse(row.moveToFirst());}}
    private JSONObject proceed(BooleanSupplier current)throws Exception{return app.continueDevelopmentTaskForOwner(new JSONObject(reviewed.toString()),FEEDBACK,current);}
    private void pickUp()throws Exception {
        Runnable original=turns.getQueue().poll();assertNotNull("The original Assistant queue must contain the continuation",original);
        pickup.submit(original).get(15,TimeUnit.SECONDS);
    }
    private void assertNoNewQueue()throws Exception {
        ThreadPoolExecutor other=(ThreadPoolExecutor)field(app,"capabilityWorkers");assertEquals(0,other.getTaskCount());assertEquals(1,app.tasks.list().length());
    }
    private JSONObject sendInput(JSONObject receipt)throws Exception {
        return new JSONObject().put("clientMessageId",UUID.randomUUID().toString()).put("message","Inspect this selected element separately")
                .put("conversationId",conversation).put("taskId",task).put("runId",receipt.getString("runId")).put("epoch",receipt.getInt("epoch"))
                .put("mode","queue").put("attachments",new JSONArray()).put("ownerSourceRefs",new JSONArray()).put("ownerPreviewRefs",new JSONArray().put(new JSONObject(reference.toString())));
    }
    private static String fingerprint(JSONObject value)throws Exception{return TaskStore.capabilityFingerprint(value);}

    @Test public void completedProjectRejoinsTheSameConversationWithoutCreatingOrSendingAMessage()throws Exception {
        JSONObject before=assistant.snapshot();JSONObject receipt=app.dispatchReviewedOwnerAction("continueDevelopmentTask",
                new JSONObject().put("taskId",task).put("reviewedTask",new JSONObject(reviewed.toString())).put("feedback",FEEDBACK),()->true),actual=app.tasks.get(task,false);
        assertTrue(receipt.getBoolean("continued"));assertEquals(task,receipt.getString("taskId"));assertEquals(conversation,receipt.getString("conversationId"));
        assertNotEquals(producingRun,receipt.getString("runId"));assertEquals(producingEpoch+1,receipt.getInt("epoch"));assertEquals("queued",receipt.getString("resourceState"));
        assertFalse(receipt.has("messageId"));assertFalse(receipt.has("clientMessageId"));assertEquals(0,actual.getJSONArray("ownerInputs").length());
        assertEquals(before.getJSONArray("conversations").toString(),assistant.snapshot().getJSONArray("conversations").toString());
        assertEquals(fingerprint(oldCheckpoint),fingerprint(checkpoint(receipt.getString("runId"))));assertEquals(FEEDBACK,actual.getString("feedback"));
        assertEquals(reviewed.getInt("stepCeiling"),actual.getInt("stepCeiling"));
        for(String key:new String[]{"revision","deadlineAt","toolLimit","modelCallLimit","toolsUsed","modelCallsUsed"})assertEquals(key,reviewed.getJSONObject("executionScope").get(key),actual.getJSONObject("executionScope").get(key));
        assertEquals(task,assistant.snapshot().getJSONObject("activeTurn").getString("taskId"));assertTrue(actual.getJSONObject("ownerInputMailbox").getBoolean("open"));assertNoNewQueue();
    }
    @Test public void separateSendUsesTheNewReceiverAndOriginalNativePreviewExactlyOnce()throws Exception {
        JSONObject receipt=proceed(()->true),input=sendInput(receipt),accepted=assistant.handle("send",input),duplicate=assistant.handle("send",new JSONObject(input.toString()));
        assertEquals("queued",accepted.getString("status"));assertTrue(duplicate.getBoolean("duplicate"));assertEquals(accepted.getString("messageId"),duplicate.getString("messageId"));
        JSONObject actual=app.tasks.get(task,false);assertEquals(1,actual.getJSONArray("ownerInputs").length());JSONObject stored=actual.getJSONArray("ownerInputs").getJSONObject(0);
        assertEquals(fingerprint(reference),fingerprint(stored.getJSONArray("ownerPreviewRefs").getJSONObject(0)));assertEquals(producingRun,stored.getJSONArray("ownerPreviewRefs").getJSONObject(0).getString("runId"));
        assertEquals(receipt.getString("runId"),actual.getString("runId"));assertEquals(receipt.getInt("epoch"),actual.getInt("coordinatorEpoch"));assertNoNewQueue();
    }
    @Test public void originalModelRequestRestoresCheckpointAfterAcceptedReviewWindowCloses()throws Exception {
        List<JSONObject> bodies=new CopyOnWriteArrayList<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{bodies.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] response="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Original project inspected; no local action dispatched.\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }finally{exchange.close();}});server.start();
        try{
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).commit();
            AtomicBoolean window=new AtomicBoolean(true);JSONObject receipt=proceed(window::get);window.set(false);pickUp();
            assertEquals("The production Engine/Runner must reach exactly one loopback model request",1,bodies.size());
            String body=bodies.get(0).toString();assertTrue(body.contains(OLD_HISTORY));assertTrue(body.contains(FEEDBACK));assertFalse(body.contains("Inspect this selected element separately"));
            JSONObject after=checkpoint(receipt.getString("runId"));assertTrue(after.getInt("steps")>=3);assertTrue(after.getJSONObject("progressGuard").getLong("progressGuardRevision")>=oldCheckpoint.getJSONObject("progressGuard").getLong("progressGuardRevision"));
            assertTrue(after.getJSONArray("messages").toString().contains(OLD_HISTORY));assertNoNewQueue();
        }finally{server.stop(0);}
    }
    @Test public void aClosedReviewCannotReserveARunOrWriteFeedback()throws Exception {
        String before=fingerprint(app.tasks.get(task,false));assertThrows(SecurityException.class,()->proceed(()->false));
        assertThrows(IllegalArgumentException.class,()->app.continueDevelopmentTaskForOwner(new JSONObject(reviewed.toString()),"  ",()->true));
        assertEquals(before,fingerprint(app.tasks.get(task,false)));assertTrue(turns.getQueue().isEmpty());assertFalse(assistant.snapshot().getBoolean("running"));
    }
    @Test public void closingTheWindowAfterSqlReservationSettlesOnlyThatReservation()throws Exception {
        AtomicBoolean current=new AtomicBoolean(true);eventHook=event->{JSONObject payload=event.optJSONObject("payload");if(payload!=null&&"resource_state".equals(payload.optString("phase"))&&"queued".equals(payload.optString("resourceState")))current.set(false);};
        assertThrows(SecurityException.class,()->proceed(current::get));JSONObject actual=app.tasks.get(task,false);
        assertNotEquals(producingRun,actual.getString("runId"));assertEquals(FEEDBACK,actual.getString("feedback"));assertEquals("cancelled",actual.getString("status"));
        noLease(actual.getString("runId"));assertTrue(controls().isEmpty());assertTrue(bindings().isEmpty());assertFalse(assistant.snapshot().getBoolean("running"));assertTrue(turns.getQueue().isEmpty());
    }
    @Test public void cancellingBeforePickupClosesTheOriginalRunAndSlot()throws Exception {
        JSONObject receipt=proceed(()->true);RunControl original=controls().get(task);assertNotNull(original);original.cancel();
        assertEquals("cancelled",app.tasks.get(task,false).getString("status"));assertEquals(receipt.getString("runId"),app.tasks.get(task,false).getString("runId"));
        noLease(receipt.getString("runId"));assertTrue(turns.getQueue().isEmpty());assertTrue(controls().isEmpty());assertTrue(bindings().isEmpty());assertFalse(assistant.snapshot().getBoolean("running"));
    }
    @Test public void realExecutorRejectionRetainsTheCommittedFailureAndReleasesTheSlot()throws Exception {
        turns.shutdown();assertThrows(IllegalStateException.class,()->proceed(()->true));JSONObject actual=app.tasks.get(task,false);
        assertNotEquals(producingRun,actual.getString("runId"));assertEquals(FEEDBACK,actual.getString("feedback"));assertEquals("cancelled",actual.getString("status"));
        noLease(actual.getString("runId"));assertTrue(controls().isEmpty());assertTrue(bindings().isEmpty());assertFalse(assistant.snapshot().getBoolean("running"));assertNoNewQueue();
    }
    @Test public void changedScopeBeforePickupIsRejectedWithoutResettingHistory()throws Exception {
        JSONObject receipt=proceed(()->true),scope=app.tasks.get(task,false).getJSONObject("executionScope");
        app.tasks.configureExecutionScope(task,scope.getLong("revision"),scope.getLong("deadlineAt"),scope.getInt("toolLimit"),scope.getInt("modelCallLimit"));pickUp();
        JSONObject actual=app.tasks.get(task,false);assertEquals("failed",actual.getString("status"));assertEquals(receipt.getString("runId"),actual.getString("runId"));
        assertEquals(fingerprint(oldCheckpoint),fingerprint(checkpoint(receipt.getString("runId"))));noLease(receipt.getString("runId"));assertTrue(controls().isEmpty());assertTrue(bindings().isEmpty());assertFalse(assistant.snapshot().getBoolean("running"));
    }
    @Test public void nativeRevocationAfterPickupStillSettlesAReservationBeforeRunnerAttemptAssignment()throws Exception {
        JSONObject receipt=proceed(()->true);RunControl original=controls().get(task);Object binding=bindings().get(original);AtomicInteger phase=(AtomicInteger)field(binding,"phase");
        original.retainAuthorityCheck(()->{if(phase.get()==1)throw new SecurityException("Fixture revokes this native control after pickup");});pickUp();
        assertTrue(original.isCancelled());assertEquals("cancelled",app.tasks.get(task,false).getString("status"));noLease(receipt.getString("runId"));
        assertEquals(fingerprint(oldCheckpoint),fingerprint(checkpoint(receipt.getString("runId"))));assertTrue(controls().isEmpty());assertTrue(bindings().isEmpty());assertFalse(assistant.snapshot().getBoolean("running"));
    }
    @Test public void stalePickupCannotCancelOrRewriteARealSuccessor()throws Exception {
        JSONObject reserved=proceed(()->true);RunControl old=controls().get(task);app.tasks.endAttempt(task,reserved.getString("runId"),"awaiting_user","Native successor fixture",new JSONObject());
        RunControl replacement=new RunControl();additionalControls.add(replacement);
        JSONObject next=app.tasks.startOriginalCapabilityAttempt(task,reserved.getString("runId"),reserved.getInt("epoch"),20,replacement,"");controls().put(task,replacement);
        String before=fingerprint(app.tasks.get(task,false));pickUp();assertTrue(old.isCancelled());assertFalse(replacement.isCancelled());assertSame(replacement,controls().get(task));
        assertEquals(next.getString("runId"),app.tasks.get(task,false).getString("runId"));assertEquals(before,fingerprint(app.tasks.get(task,false)));assertFalse(assistant.snapshot().getBoolean("running"));
    }
    @Test public void endedPreviewSendAndLegacyResumeNeverAutoContinue()throws Exception {
        JSONObject stale=new JSONObject().put("runId",producingRun).put("epoch",producingEpoch);String before=fingerprint(app.tasks.get(task,false));
        assertThrows(IllegalStateException.class,()->assistant.handle("send",sendInput(stale)));
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("continueDevelopmentTask",new JSONObject().put("taskId",task).put("feedback",FEEDBACK)));
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("resumeTask",new JSONObject().put("taskId",task).put("runId",producingRun).put("epoch",producingEpoch).put("feedback",FEEDBACK)));
        assertEquals(before,fingerprint(app.tasks.get(task,false)));assertFalse(assistant.snapshot().getBoolean("running"));assertTrue(turns.getQueue().isEmpty());
    }
    @Test public void changedPreviewSourceAfterContinuePreservesTheUnsentInput()throws Exception {
        JSONObject receipt=proceed(()->true);Files.writeString(app.getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files/index.html"),HTML+"changed");
        JSONObject input=sendInput(receipt);String exact=input.toString();assertThrows(SecurityException.class,()->assistant.handle("send",input));
        assertEquals(exact,input.toString());assertEquals(0,app.tasks.get(task,false).getJSONArray("ownerInputs").length());assertEquals(receipt.getString("runId"),app.tasks.get(task,false).getString("runId"));assertNoNewQueue();
    }
}
