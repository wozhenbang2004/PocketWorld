package dev.pocketengine.runtime;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original Gateway/HTTP/SQLite; no external provider, credentials or phone execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ModelConcurrencyGatewayTest {
    private static final class Ticket {final boolean stream,responses;final CountDownLatch release=new CountDownLatch(1);volatile int status=200;Ticket(boolean stream,boolean responses){this.stream=stream;this.responses=responses;}}
    private record Original(JSONObject task,RunControl control,RunJournal journal) { }
    private TaskStore tasks;private SettingsStore settings;private HttpServer server;private ExecutorService workers;
    private JSONObject connection;private ModelConfig config;private ModelCallAdmission gate;
    private final BlockingQueue<Ticket> requests=new LinkedBlockingQueue<>();
    private final List<Ticket> allTickets=new CopyOnWriteArrayList<>();
    private final List<Original> originals=new ArrayList<>();
    private final AtomicInteger posts=new AtomicInteger(),live=new AtomicInteger(),maximumLive=new AtomicInteger();
    @Before public void open()throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.getSharedPreferences("private_settings",0).edit().clear().commit();settings=new SettingsStore(app);tasks=new TaskStore(app);workers=Executors.newCachedThreadPool();
        var field=ModelGateway.class.getDeclaredField("MODEL_CALLS");field.setAccessible(true);gate=(ModelCallAdmission)field.get(null);assertEquals(0,gate.activeCount());assertEquals(0,gate.waitingCount());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{Ticket ticket=null;boolean counted=false;try{
            JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));ticket=new Ticket(body.optBoolean("stream"),body.has("input"));allTickets.add(ticket);posts.incrementAndGet();maximumLive.accumulateAndGet(live.incrementAndGet(),Math::max);counted=true;requests.add(ticket);
            if(!ticket.release.await(6,TimeUnit.SECONDS))return;
            String response=ticket.status!=200?"{\"error\":{\"code\":\"temporary_unavailable\"}}":ticket.responses?
                "{\"id\":\"response_fixture\",\"status\":\"completed\",\"model\":\"original-model\",\"output\":[{\"id\":\"message_fixture\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"original result\"}]}],\"usage\":{\"input_tokens\":2,\"output_tokens\":1,\"total_tokens\":3}}":ticket.stream?
                "data: {\"id\":\"original\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"original result\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"original\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n":
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"original result\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);live.decrementAndGet();counted=false;exchange.getResponseHeaders().add("Content-Type",ticket.stream&&ticket.status==200?"text/event-stream":"application/json");exchange.sendResponseHeaders(ticket.status,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{if(counted)live.decrementAndGet();exchange.close();}});server.start();
        var models=new ModelConnectionService(settings,Files.createTempDirectory("profile-concurrency-catalog").resolve("catalog"),Clock.systemUTC());models.install();models.connections();
        connection=settings.saveApiConnection("",0,"Original",new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("provider","openai_chat").put("model","original-model").put("sendScreenshots",false).put("requestTimeoutSeconds",5));settings.selectApiConnection(connection.getString("id"),1);config=settings.get();
    }
    private Original original(int limit,int modelBudget)throws Exception {
        return original(limit,modelBudget,0);
    }
    private Original original(int limit,int modelBudget,long deadlineAt)throws Exception {
        JSONObject task=tasks.createCapabilityTask("Original native resource request","");tasks.configureExecutionScope(task.getString("id"),0,deadlineAt,20,modelBudget);RunControl control=new RunControl();if(limit>0)control.restrictModelConcurrency(limit);task=tasks.startAttempt(task.getString("id"),"capabilities",20);control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));Original original=new Original(task,control,tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));originals.add(original);return original;
    }
    private static JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","Original native request"));}
    private InferenceCapture.Target target(Original original)throws Exception{return original.journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"");}
    private Future<JSONObject> buffered(Original original)throws Exception{var target=target(original);return workers.submit(()->ModelGateway.complete(config,messages(),null,original.control,target));}
    private Ticket take()throws Exception{Ticket ticket=requests.poll(3,TimeUnit.SECONDS);assertNotNull("Actual HTTP request required",ticket);return ticket;}
    private JSONObject task(Original original)throws Exception{return tasks.get(original.task.getString("id"),false);}
    private JSONObject usage(Original original)throws Exception{return tasks.inferenceUsage(original.task.getString("id"));}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue("Expected actual model occupancy",condition.getAsBoolean());}
    private static Throwable failed(Future<?> result)throws Exception{try{result.get(3,TimeUnit.SECONDS);fail("Expected stopped original request");return null;}catch(ExecutionException expected){return expected.getCause();}}
    @After public void close()throws Exception {
        for(Original original:originals)original.control.cancel();for(Ticket ticket:allTickets)ticket.release.countDown();if(server!=null)server.stop(0);if(workers!=null){workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);}if(gate!=null)await(()->gate.activeCount()==0&&gate.waitingCount()==0);if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
    @Test public void emptyProfilePreferencePreservesThreeIndependentRealHttpRequests()throws Exception{
        List<Future<JSONObject>> replies=new ArrayList<>();for(int i=0;i<3;i++)replies.add(buffered(original(0,4)));List<Ticket> accepted=List.of(take(),take(),take());assertEquals(3,posts.get());assertEquals(3,gate.activeCount());assertEquals(3,maximumLive.get());accepted.forEach(ticket->ticket.release.countDown());for(Future<JSONObject> reply:replies)assertEquals("original result",reply.get(5,TimeUnit.SECONDS).getString("content"));
    }
    @Test public void oneTaskSerializesBufferedAndStreamingWithoutChargingTheQueuedRequest()throws Exception{
        Original original=original(1,2);JSONObject scope=new JSONObject(task(original).getJSONObject("executionScope").toString());Future<JSONObject> first=buffered(original);Ticket firstHttp=take();var nativeTarget=target(original);List<ModelGateway.StreamEvent> events=new CopyOnWriteArrayList<>();Future<JSONObject> second=workers.submit(()->ModelGateway.completeStreaming(config,messages(),null,original.control,events::add,nativeTarget));await(()->gate.waitingCount()==1||posts.get()>1);assertEquals("Frozen one-call preference must prevent a second HTTP dispatch",1,posts.get());assertEquals(1,gate.waitingCount());
        assertEquals(1,posts.get());assertEquals(1,task(original).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(1,usage(original).getJSONArray("receipts").length());assertTrue(events.isEmpty());JSONArray logical=usage(original).getJSONArray("logicalRequests");assertEquals(2,logical.length());int attempts=0;for(int i=0;i<logical.length();i++)attempts+=logical.getJSONObject(i).getJSONObject("control").getJSONArray("attempts").length();assertEquals(1,attempts);
        firstHttp.release.countDown();first.get(5,TimeUnit.SECONDS);Ticket secondHttp=take();assertTrue(secondHttp.stream);secondHttp.release.countDown();assertEquals("original result",second.get(5,TimeUnit.SECONDS).getString("content"));assertTrue(events.stream().anyMatch(event->event.kind==ModelGateway.StreamKind.COMPLETED));assertEquals(2,task(original).getJSONObject("executionScope").getInt("modelCallsUsed"));for(String field:new String[]{"revision","deadlineAt","toolLimit","modelCallLimit"})assertEquals(scope.get(field),task(original).getJSONObject("executionScope").get(field));assertThrows(Exception.class,()->ModelGateway.complete(config,messages(),null,original.control,target(original)));assertEquals(2,posts.get());
    }
    @Test public void twoTaskSlotsQueueOnlyTheThirdAndNeverEnlargeTheOriginalBudget()throws Exception{
        Original original=original(2,3);List<Future<JSONObject>> replies=List.of(buffered(original),buffered(original),buffered(original));Ticket first=take(),second=take();await(()->gate.waitingCount()==1);assertEquals(2,posts.get());assertEquals(2,task(original).getJSONObject("executionScope").getInt("modelCallsUsed"));first.release.countDown();Ticket third=take();assertEquals(3,posts.get());assertTrue(maximumLive.get()<=2);second.release.countDown();third.release.countDown();for(Future<JSONObject> reply:replies)reply.get(5,TimeUnit.SECONDS);assertEquals(3,task(original).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(3,task(original).getJSONObject("executionScope").getInt("modelCallLimit"));
    }
    @Test public void originalCancellationBeforeQueuedDispatchCreatesNoSecondPhysicalReceiptOrConsumption()throws Exception{
        Original original=original(1,4);Future<?> first=buffered(original);Ticket firstHttp=take();Future<?> second=buffered(original);await(()->gate.waitingCount()==1);original.control.cancel();assertTrue(failed(first) instanceof InterruptedException);assertTrue(failed(second) instanceof InterruptedException);assertEquals(1,posts.get());assertEquals(1,task(original).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(1,usage(original).getJSONArray("receipts").length());firstHttp.release.countDown();await(()->gate.activeCount()==0&&gate.waitingCount()==0);
    }
    @Test public void originalDeadlineWhileQueuedCreatesNoPhysicalAttemptOrInferenceCharge()throws Exception{
        Original original=original(1,4,System.currentTimeMillis()+800);String id=original.task.getString("id");try(var occupied=gate.enter(original.control,id,System.currentTimeMillis()+5000)){
            Throwable stopped=failed(buffered(original));assertTrue(stopped instanceof ModelGateway.GatewayException);assertEquals(0,posts.get());assertEquals(0,task(original).getJSONObject("executionScope").getInt("modelCallsUsed"));JSONObject usage=usage(original);assertEquals(0,usage.getJSONArray("receipts").length());JSONObject logical=usage.getJSONArray("logicalRequests").getJSONObject(0);assertEquals(0,logical.getJSONObject("control").getJSONArray("attempts").length());assertEquals("FAILED",logical.getString("state"));assertEquals("failed",logical.getJSONObject("control").getString("completionOutcome"));assertEquals(1,gate.activeCount());
        }
    }
    @Test public void nativeProtocolIngressSharesTheSamePhysicalTaskSlotAndOriginalUsageTarget()throws Exception{
        JSONObject responseConnection=settings.saveApiConnection("",0,"Original Responses API",new JSONObject().put("baseUrl",config.baseUrl).put("provider","openai_responses").put("model","original-model").put("sendScreenshots",false).put("requestTimeoutSeconds",5));settings.selectApiConnection(responseConnection.getString("id"),1);config=settings.get();
        RunControl control=new RunControl();control.restrictModelConcurrency(1);var nativeSession=tasks.beginNativeInference(TaskStore.NativeInferenceKind.RELAY,"profile-resource-native-ingress",control);var nativeTarget=nativeSession.target();JSONObject body=new JSONObject().put("model","original-model").put("max_tokens",128).put("messages",messages());
        var request=dev.pocketengine.runtime.providers.ProtocolCodec.prepare(config,"anthropic",body);Future<JSONObject> first=workers.submit(()->ModelGateway.completeNative(config,request,control,nativeTarget,(call,sequence)->{},null));Ticket firstHttp=requests.poll(3,TimeUnit.SECONDS);if(firstHttp==null){first.get(1,TimeUnit.SECONDS);fail("Actual native ingress HTTP required");}Future<JSONObject> second=workers.submit(()->ModelGateway.completeNative(config,request,control,nativeTarget,(call,sequence)->{},null));try{await(()->gate.waitingCount()==1);assertEquals(1,posts.get());assertEquals(1,tasks.inferenceUsage(nativeSession.taskId).getJSONArray("receipts").length());firstHttp.release.countDown();assertEquals("message",first.get(5,TimeUnit.SECONDS).getString("type"));Throwable exhausted=failed(second);assertTrue(exhausted instanceof SecurityException||exhausted instanceof ModelGateway.GatewayException);assertEquals(1,posts.get());assertEquals(1,tasks.inferenceUsage(nativeSession.taskId).getJSONArray("receipts").length());assertEquals(1,tasks.get(nativeSession.taskId,false).getJSONObject("nativeInference").getInt("maxCalls"));assertEquals(1,maximumLive.get());nativeSession.finish("completed");}finally{control.cancel();firstHttp.release.countDown();}
    }
    @Test public void trustedAccountPortAlsoWaitsBeforePhysicalAdmissionAndOriginalSqlConsumption()throws Exception{
        var field=ModelGateway.class.getDeclaredField("accountInference");field.setAccessible(true);var previous=(ModelGateway.AccountInferencePort)field.get(null);AtomicInteger entered=new AtomicInteger();Semaphore release=new Semaphore(0);Original original=original(1,3);
        ModelGateway.installAccountInferencePort((reference,request,operation,sink)->{try{entered.incrementAndGet();if(!release.tryAcquire(3,TimeUnit.SECONDS))throw new dev.pocketengine.features.proxy.accounts.GatewayFailure("DEADLINE_EXCEEDED");operation.check();sink.event(new JSONObject().put("type","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","original account result"))))));}catch(dev.pocketengine.features.proxy.accounts.GatewayFailure failed){throw failed;}catch(Exception failed){throw new dev.pocketengine.features.proxy.accounts.GatewayFailure("FIXTURE_FAILURE");}});
        try{ModelConfig account=new ModelConfig("","","",false,4,5).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","fixture-account").put("accountVersion",1L).put("modelId","fixture-model")));var firstTarget=target(original);Future<JSONObject> first=workers.submit(()->ModelGateway.complete(account,messages(),null,original.control,firstTarget));await(()->entered.get()==1);var secondTarget=target(original);Future<JSONObject> second=workers.submit(()->ModelGateway.complete(account,messages(),null,original.control,secondTarget));await(()->gate.waitingCount()==1);assertEquals(1,entered.get());assertEquals(1,task(original).getJSONObject("executionScope").getInt("modelCallsUsed"));release.release();assertEquals("original account result",first.get(5,TimeUnit.SECONDS).getString("content"));await(()->entered.get()==2);release.release();assertEquals("original account result",second.get(5,TimeUnit.SECONDS).getString("content"));assertEquals(2,usage(original).getJSONArray("receipts").length());assertEquals(2,task(original).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(0,posts.get());}finally{release.release(4);original.control.cancel();await(()->gate.activeCount()==0&&gate.waitingCount()==0);ModelGateway.installAccountInferencePort(previous);}
    }
    @Test public void retryBackoffReleasesThePhysicalSlotAndTheRetryReacquiresWithinTheSameBudget()throws Exception{
        JSONObject target=ModelTargetRef.exact(connection.getString("id"),"original-model").toJson();JSONObject group=settings.saveModelGroup("",0,"Original bounded retry",new JSONObject().put("allowedAccounts",new JSONObject().put("mode","EXPLICIT").put("refs",new JSONArray().put(connection.getString("id")))).put("candidates",new JSONArray().put(new JSONObject().put("candidateId","original").put("target",target).put("enabled",true).put("allowedPurposes",new JSONArray().put("MAIN")).put("allowedAudiences",new JSONArray().put("APP_OWNER")))).put("attemptPolicy",new JSONObject().put("maxAttempts",2).put("maxRouteSwitches",0)));settings.selectModelPurpose("MAIN",ModelTargetRef.group(group.getString("groupId"),1));config=settings.get();ModelConfig selected=config;Original original=original(1,4);CountDownLatch backingOff=new CountDownLatch(1),otherEntered=new CountDownLatch(1);var nativeTarget=target(original);
        Future<JSONObject> retried=workers.submit(()->ModelGateway.completeStreaming(selected,messages(),null,original.control,event->{},nativeTarget,new ModelGateway.RetryObserver(){public void beforeWait(String logical,String failedCall,int number,long delay)throws Exception{assertEquals(0,gate.activeCount());backingOff.countDown();assertTrue(otherEntered.await(3,TimeUnit.SECONDS));}public void afterWait(){}}));Ticket failed=take();failed.status=503;failed.release.countDown();if(!backingOff.await(3,TimeUnit.SECONDS)){retried.get(1,TimeUnit.SECONDS);fail("Native backoff callback did not arrive");}Future<JSONObject> other=buffered(original);Ticket independent=take();otherEntered.countDown();await(()->gate.waitingCount()==1);assertEquals(2,posts.get());independent.release.countDown();other.get(5,TimeUnit.SECONDS);Ticket recovered=take();recovered.release.countDown();assertEquals("original result",retried.get(5,TimeUnit.SECONDS).getString("content"));assertEquals(3,posts.get());assertEquals(3,task(original).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(3,usage(original).getJSONArray("receipts").length());assertEquals(1,maximumLive.get());
    }
}
