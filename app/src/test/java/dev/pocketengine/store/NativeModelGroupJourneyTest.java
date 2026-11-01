package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.*;
import dev.pocketengine.runtime.models.*;
import dev.pocketengine.runtime.usage.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Original SettingsStore + native TaskStore/Runner/Gateway + loopback HTTP, not a replacement host. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeModelGroupJourneyTest {
    private static final class Secrets implements SettingsStore.ApiSecrets {
        final Map<String,String> slots=new HashMap<>();public String get(String slot){return slots.getOrDefault(slot,"");}public void put(String slot,String key){slots.put(slot,key);}
    }
    private Application app;private SettingsStore settings;private ModelConnectionService models;private TaskStore tasks;
    private HttpServer server;private JSONObject a,b,task;private RunControl original;private RunJournal journal;
    private final AtomicInteger aPosts=new AtomicInteger(),bPosts=new AtomicInteger();
    private volatile boolean partialStream;
    private volatile int aStatus=200;private volatile String errorCode="",receivedModel="";
    @Before public void open()throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");var prefs=app.getSharedPreferences("private_settings",0);prefs.edit().clear().commit();settings=new SettingsStore(prefs,new Secrets());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{
            JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));receivedModel=input.getString("model");boolean first=exchange.getRequestURI().getPath().startsWith("/a/");if(first)aPosts.incrementAndGet();else bPosts.incrementAndGet();int status=first?aStatus:200;
            String body=status!=200?new JSONObject().put("error",new JSONObject().put("code",errorCode)).toString():input.optBoolean("stream")?"data: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"native result\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n":"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"native result\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
            if(status==200&&input.optBoolean("stream")&&partialStream)body="data: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"visible original partial text with no provider terminal\"},\"finish_reason\":null}]}\n\n";
            if(status==200&&input.has("input"))body=new JSONObject().put("id","response_fixture").put("status","completed").put("model",receivedModel).put("output",new JSONArray().put(new JSONObject().put("id","message_fixture").put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","native result"))))).put("usage",new JSONObject().put("input_tokens",2).put("output_tokens",1).put("total_tokens",3)).toString();
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",input.optBoolean("stream")&&status==200?"text/event-stream":"application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){/* Native cancellation or deliberate commit failure closes the original HTTP stream. */}finally{exchange.close();}});server.start();
        models=new ModelConnectionService(settings,Files.createTempDirectory("group-native-catalog").resolve("catalog"),Clock.systemUTC());models.install();models.connections();
        a=settings.saveApiConnection("",0,"A",input("a","model-a","fixture-key-A"));b=settings.saveApiConnection("",0,"B",input("b","model-b","fixture-key-B"));settings.selectApiConnection(a.getString("id"),1);tasks=new TaskStore(app);start();
    }
    private JSONObject input(String path,String model,String key)throws Exception{return new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/"+path+"/v1").put("provider","openai_chat").put("model",model).put("apiKey",key).put("sendScreenshots",false);}
    private void start()throws Exception{task=tasks.startAttempt(tasks.createCapabilityTask("original native group request","conversation").getString("id"),"capabilities",12);original=new RunControl();original.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));journal=tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original);}
    private SQLiteDatabase db()throws Exception{var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    @After public void close()throws Exception{if(original!=null)original.cancel();if(server!=null)server.stop(0);if(tasks!=null)db().close();}
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","Original native request"));}
    private JSONObject group(int attempts,int switches)throws Exception {
        JSONArray candidates=new JSONArray(),accounts=new JSONArray();for(JSONObject profile:List.of(a,b)){accounts.put(profile.getString("id"));candidates.put(new JSONObject().put("candidateId",profile==a?"first":"second").put("target",ModelTargetRef.exact(profile.getString("id"),profile.getString("model")).toJson()).put("enabled",true).put("allowedPurposes",new JSONArray(List.of("MAIN","SUMMARY"))).put("allowedAudiences",new JSONArray(List.of("APP_OWNER"))));}
        return settings.saveModelGroup("",0,"Native ordered group",new JSONObject().put("allowedAccounts",new JSONObject().put("mode","EXPLICIT").put("refs",accounts)).put("candidates",candidates).put("attemptPolicy",new JSONObject().put("maxAttempts",attempts).put("maxRouteSwitches",switches)));
    }
    private ModelConfig select(JSONObject group)throws Exception{settings.selectModelPurpose("MAIN",ModelTargetRef.group(group.getString("groupId"),group.getLong("revision")));return settings.get();}
    private JSONObject usage()throws Exception{return tasks.inferenceUsage(task.getString("id"));}
    private JSONObject logical()throws Exception{return usage().getJSONArray("logicalRequests").getJSONObject(0);}
    @Test public void orderedFallbackUsesOriginalPhysicalReceiptsAndOneLogicalDeadline()throws Exception {
        aStatus=503;ModelConfig selected=select(group(3,1));assertEquals("native result",ModelGateway.complete(selected,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")).getString("content"));assertEquals(1,aPosts.get());assertEquals(1,bPosts.get());
        JSONObject logical=logical();assertEquals("SUCCEEDED",logical.getString("state"));assertTrue(logical.getBoolean("semanticCommitted"));JSONArray attempts=logical.getJSONObject("control").getJSONArray("attempts"),receipts=usage().getJSONArray("receipts");assertEquals(2,attempts.length());assertEquals(2,receipts.length());assertNotEquals(attempts.getJSONObject(0).getString("callId"),attempts.getJSONObject(1).getString("callId"));assertEquals(attempts.getJSONObject(1).getString("callId"),logical.getString("chosenCallId"));
        for(int i=0;i<receipts.length();i++){JSONObject route=receipts.getJSONObject(i).getJSONObject("route");assertEquals(logical.getString("logicalRequestId"),route.getString("logicalRequestId"));assertEquals(logical.getLong("deadlineAt"),route.getLong("deadlineAt"));}assertFalse(usage().toString().contains("fixture-key"));
    }
    @Test public void authenticationQuotaPolicyAndUnclassified429NeverTryAnotherAccount()throws Exception {
        ModelConfig selected=select(group(3,1));for(int status:new int[]{401,403,402,429,500}){start();aStatus=status;errorCode=status==500?"insufficient_quota":"";int before=aPosts.get();assertThrows(Exception.class,()->ModelGateway.complete(selected,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(before+1,aPosts.get());assertEquals(0,bPosts.get());assertEquals(1,logical().getJSONObject("control").getJSONArray("attempts").length());}
        start();aStatus=503;errorCode="content_policy_violation";int before=aPosts.get();assertThrows(Exception.class,()->ModelGateway.complete(selected,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(before+1,aPosts.get());assertEquals(0,bPosts.get());
    }
    @Test public void explicitRateLimitStaysOnOriginalRouteAndRunnerCannotMultiplyAttempts()throws Exception {
        aStatus=429;errorCode="rate_limit_exceeded";ModelConfig selected=select(group(3,1));CapabilityPort port=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return false;}public boolean sideEffecting(String n){return false;}public JSONObject execute(String n,JSONObject args,RunControl c){throw new AssertionError();}};
        RunOutcome outcome=new AgentRunner(null,port).run(task.getString("id"),"original request",selected,original,e->{},journal);assertEquals("failed",outcome.status);assertEquals(3,aPosts.get());assertEquals(0,bPosts.get());assertEquals(1,usage().getJSONArray("logicalRequests").length());assertEquals(3,usage().getJSONArray("receipts").length());assertEquals(1,usage().getJSONArray("groups").length());
    }
    @Test public void nativeCommitIsDurableBeforeFirstSemanticFlushAndCommitFailureFlushesNothing()throws Exception {
        ModelConfig selected=select(group(3,1));AtomicInteger deltas=new AtomicInteger();ModelGateway.completeStreaming(selected,messages(),null,original,e->{if(e.kind==ModelGateway.StreamKind.TEXT_DELTA){JSONObject row=logical();assertTrue(row.getBoolean("semanticCommitted"));assertEquals(e.requestId,row.getString("chosenCallId"));deltas.incrementAndGet();}},journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));assertTrue(deltas.get()>0);
        start();db().execSQL("CREATE TRIGGER reject_semantic BEFORE UPDATE OF semantic_committed ON inference_requests WHEN NEW.semantic_committed=1 BEGIN SELECT RAISE(ABORT,'fixture commit storage failure'); END");AtomicInteger rejected=new AtomicInteger();assertThrows(Exception.class,()->ModelGateway.completeStreaming(selected,messages(),null,original,e->{if(e.kind==ModelGateway.StreamKind.TEXT_DELTA||e.kind==ModelGateway.StreamKind.COMPLETED)rejected.incrementAndGet();},journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(0,rejected.get());assertEquals(0,bPosts.get());assertFalse(logical().getBoolean("semanticCommitted"));
    }
    @Test public void captureFreezesGroupRevisionAndDefaultWhileRevocationRemainsCurrent()throws Exception {
        JSONObject group=group(1,0);ModelConfig captured=select(group);settings.selectApiConnection(b.getString("id"),1);settings.saveModelGroup(group.getString("groupId"),1,"Edited group",group);assertThrows(Exception.class,settings::get);assertTrue(models.snapshot().getBoolean("selectionUnavailable"));
        ModelGateway.complete(captured,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));assertEquals(1,aPosts.get());assertEquals(0,bPosts.get());
        // Revoking A cannot authorize A again, but does not revoke the already frozen B.
        start();settings.setApiConnectionEnabled(a.getString("id"),1,false);ModelGateway.complete(captured,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));assertEquals(1,aPosts.get());assertEquals(1,bPosts.get());assertEquals("second",logical().getJSONObject("control").getJSONArray("attempts").getJSONObject(0).getString("candidateId"));assertFalse(logical().getJSONObject("routeSnapshot").getJSONArray("orderedCandidates").getJSONObject(0).getString("exclusion").isEmpty());
        start();settings.setApiConnectionEnabled(b.getString("id"),1,false);assertThrows(Exception.class,()->ModelGateway.complete(captured,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(1,aPosts.get());assertEquals(1,bPosts.get());
    }
    @Test public void summaryPurposeIsCapturedOnceAndNotReboundByLaterSelection()throws Exception {
        settings.selectModelPurpose("SUMMARY",ModelTargetRef.exact(b.getString("id"),"model-b"));ModelConfig captured=settings.get();settings.selectModelPurpose("SUMMARY",ModelTargetRef.exact(a.getString("id"),"model-a"));ModelGateway.complete(captured,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.SUMMARY,""));assertEquals(0,aPosts.get());assertEquals(1,bPosts.get());assertEquals("model-b",receivedModel);assertEquals("SUMMARY",logical().getJSONObject("routeSnapshot").getString("purpose"));
    }
    @Test public void nativeBudgetPauseRetainsTypedIdentityAndNeverRetriesOrFlushes()throws Exception {
        ModelConfig captured=select(group(3,1));RunJournal.NativeBudgetPaused paused=new RunJournal.NativeBudgetPaused(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),"parent-task","parent-run","MAIN",97,97);
        InferenceCapture.Target nativeTarget=journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"");AtomicInteger semantic=new AtomicInteger();
        assertSame(paused,assertThrows(RunJournal.NativeBudgetPaused.class,()->ModelGateway.completeStreaming(captured,messages(),null,original,event->{if(event.kind==ModelGateway.StreamKind.TEXT_DELTA||event.kind==ModelGateway.StreamKind.COMPLETED)semantic.incrementAndGet();},InferenceCapture.withAdmission(nativeTarget,()->{throw paused;}))));
        assertEquals(0,aPosts.get());assertEquals(0,bPosts.get());assertEquals(0,semantic.get());assertFalse(paused.diagnostics().getBoolean("thisRequestDispatched"));assertEquals(97,paused.diagnostics().getInt("modelRequestsReservedOrUsed"));
    }
    @Test public void originalRunPinsRequestOptionsWithoutPersistingOrHashingCredentials()throws Exception {
        ModelConfig captured=select(group(1,0));ModelGateway.complete(captured,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));
        JSONObject before=logical().getJSONObject("routeSnapshot").getJSONArray("orderedCandidates").getJSONObject(0);
        settings.saveApiConnection(a.getString("id"),1,"A",new JSONObject().put("maxOutputTokens",256));ModelConfig changed=settings.get();
        assertEquals(captured.capabilityBinding,changed.capabilityBinding);assertNotEquals(ModelRoutePlan.configurationDigest(captured),ModelRoutePlan.configurationDigest(changed));
        assertThrows(SecurityException.class,()->ModelGateway.complete(changed,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(1,aPosts.get());
        ModelGateway.complete(captured,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));assertEquals(2,aPosts.get());
        assertEquals(ModelRoutePlan.configurationDigest(captured),before.getString("configurationDigest"));assertFalse(before.toString().contains("fixture-key"));
        ModelConfig differentKey=new ModelConfig(captured.baseUrl,"different-secret",captured.model,captured.sendScreenshots,captured.maxSteps,captured.requestTimeoutSeconds,captured.provider,captured.maxOutputTokens);
        assertEquals(ModelRoutePlan.configurationDigest(captured),ModelRoutePlan.configurationDigest(differentKey));
    }
    @Test public void originalRunCannotReadNewGroupDefaultOnItsNextLogicalRequest()throws Exception {
        ModelConfig selected=select(group(1,0));ModelGateway.complete(selected,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));settings.selectModelPurpose("MAIN",ModelTargetRef.exact(b.getString("id"),"model-b"));ModelConfig replacement=settings.get();assertThrows(SecurityException.class,()->ModelGateway.complete(replacement,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(1,aPosts.get());assertEquals(0,bPosts.get());
    }
    @Test public void flatBoundedGroupsRejectNestedTargetsAndEmptyAllowlistGrantsNothing()throws Exception {
        JSONObject group=group(1,0);JSONObject empty=new JSONObject(group.toString());empty.getJSONObject("allowedAccounts").put("refs",new JSONArray());JSONObject denied=settings.saveModelGroup(group.getString("groupId"),1,"No accounts",empty);assertThrows(Exception.class,()->settings.captureModelTarget(ModelTargetRef.group(denied.getString("groupId"),2),"MAIN","APP_OWNER",settings.get()));assertEquals(0,aPosts.get());
        group.getJSONArray("candidates").getJSONObject(0).put("target",ModelTargetRef.group("nested",1).toJson());assertThrows(IllegalArgumentException.class,()->settings.saveModelGroup("",0,"No nesting",group));
    }
    @Test public void versionFiveUpgradeKeepsOldEventsUnknownEffectsAndRealtimeReceipts()throws Exception {
        tasks.event(task.getString("id"),new JSONObject().put("type","fixture_original_event").put("message","preserved v5 evidence"));
        String operation=UUID.randomUUID().toString();ContentValues pending=new ContentValues();pending.put("operation_id",operation);pending.put("task_id",task.getString("id"));pending.put("run_id",task.getString("runId"));pending.put("tool","fixture_unknown_effect");pending.put("arguments","{}");pending.put("state","unknown");pending.put("created_at",System.currentTimeMillis());db().insertOrThrow("operations",null,pending);
        RunControl realtimeControl=new RunControl();TaskStore.NativeInferenceSession realtime=tasks.beginNativeInference(TaskStore.NativeInferenceKind.REALTIME,"migration-fixture",realtimeControl);InferenceCapture capture=InferenceCapture.realtime(settings.get(),1,realtimeControl,realtime.target());capture.observedServerResponse("response_fixture");capture.finish(InferenceReceipt.Outcome.COMPLETED);realtime.finish("completed");String realtimeId=realtime.taskId,receiptBefore=tasks.inferenceUsage(realtimeId).getJSONArray("receipts").toString();
        db().execSQL("DROP TABLE inference_requests");db().setVersion(5);db().close();tasks=new TaskStore(app);assertEquals(6,db().getVersion());assertTrue(tasks.hasUnresolved(task.getString("id")));assertTrue(tasks.get(task.getString("id"),false).getJSONArray("events").toString().contains("preserved v5 evidence"));assertEquals(receiptBefore,tasks.inferenceUsage(realtimeId).getJSONArray("receipts").toString());assertEquals(0,aPosts.get());assertEquals(0,bPosts.get());
    }
    @Test public void processLossLeavesOriginalLogicalAttemptUnknownAndNeverResubmits()throws Exception {
        ModelConfig selected=select(group(1,0));InferenceCapture.Target target=journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"");String id=UUID.randomUUID().toString();JSONObject plan=selected.routePlan.toJson();target.logical.begin(id,"a".repeat(64),plan,System.currentTimeMillis()+30_000);String call=UUID.randomUUID().toString();target.logical.reserve(id,call,1,"first","initial_ordered_candidate");db().close();tasks=new TaskStore(app);JSONObject recovered=tasks.inferenceUsage(task.getString("id")).getJSONArray("logicalRequests").getJSONObject(0);assertEquals(id,recovered.getString("logicalRequestId"));assertEquals("UNKNOWN",recovered.getString("state"));assertEquals("unknown",recovered.getJSONObject("control").getString("completionOutcome"));assertEquals(0,aPosts.get());assertEquals(0,bPosts.get());assertEquals(call,recovered.getJSONObject("control").getJSONArray("attempts").getJSONObject(0).getString("callId"));
    }

    @Test public void originalProxyModuleStartsOnlyExplicitRelayGroupAndJournalsActualCrossProtocolAlias()throws Exception {
        // Reuse the original explicit test-only keystore; SecretVault encryption/storage is real.
        java.security.Security.addProvider(new java.security.Provider("ModelGroupRelayFixture","1","Synthetic native test key only"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        try{
            a=settings.saveApiConnection(a.getString("id"),1,"A",input("a","model-a","fixture-key-A").put("provider","openai_responses"));b=settings.saveApiConnection(b.getString("id"),1,"B",input("b","model-b","fixture-key-B").put("provider","openai_responses"));
            JSONObject ownerOnly=group(3,1);select(ownerOnly);assertNull(settings.captureRelaySelection().routePlan);assertEquals("model-a",settings.captureRelaySelection().model);
            assertThrows(Exception.class,()->settings.selectModelPurpose("RELAY",ModelTargetRef.group(ownerOnly.getString("groupId"),1)));assertEquals(0,aPosts.get());
            JSONArray candidates=ownerOnly.getJSONArray("candidates");for(int i=0;i<candidates.length();i++)candidates.getJSONObject(i).put("allowedPurposes",new JSONArray(List.of("MAIN","SUMMARY","RELAY"))).put("allowedAudiences",new JSONArray(List.of("APP_OWNER","EXTERNAL_RELAY")));
            JSONObject external=settings.saveModelGroup(ownerOnly.getString("groupId"),1,"Explicit local-service group",ownerOnly);ModelTargetRef ref=ModelTargetRef.group(external.getString("groupId"),2);settings.selectModelPurpose("MAIN",ref);settings.selectModelPurpose("RELAY",ref);
            assertEquals("APP_OWNER",settings.get().routePlan.audience);assertEquals("EXTERNAL_RELAY",settings.captureRelaySelection().routePlan.audience);
            var context=new dev.pocketengine.features.FeatureContext(app,settings,new SecretVault(app),event->{});
            try(var proxy=new dev.pocketengine.features.proxy.ProxyModule(context,null,(config,control,session)->NativeInferenceWork.open(tasks,TaskStore.NativeInferenceKind.RELAY,session,control))){
                String token="fixture-local-service-token-at-least-32-characters";JSONObject state=proxy.handle("start",new JSONObject().put("port",0).put("token",token));String endpoint=state.getString("endpoint");assertTrue(state.getBoolean("modelGroup"));assertEquals(external.getString("groupId"),state.getString("model"));
                JSONObject models=relayHttp(endpoint+"/models",token,null);assertEquals(200,models.getInt("status"));assertEquals(external.getString("groupId"),models.getJSONObject("body").getJSONArray("data").getJSONObject(0).getString("id"));assertEquals(0,aPosts.get());
                JSONObject client=new JSONObject().put("model",external.getString("groupId")).put("max_tokens",128).put("messages",messages());JSONObject response=relayHttp(endpoint+"/messages",token,client);assertEquals(response.toString(),200,response.getInt("status"));assertEquals("message",response.getJSONObject("body").getString("type"));assertEquals(1,aPosts.get());assertEquals(0,bPosts.get());
                String nativeTask=proxy.snapshot().getJSONArray("recentInferenceReferences").getJSONObject(0).getString("taskId");JSONObject nativeUsage=tasks.inferenceUsage(nativeTask),logical=nativeUsage.getJSONArray("logicalRequests").getJSONObject(0);assertEquals("SUCCEEDED",logical.getString("state"));assertTrue(logical.getBoolean("semanticCommitted"));assertEquals(1,logical.getJSONObject("control").getInt("maxAttempts"));JSONObject route=nativeUsage.getJSONArray("receipts").getJSONObject(0).getJSONObject("route");assertEquals("anthropic",route.getString("ingressProtocol"));assertEquals("openai_responses",route.getString("egressProtocol"));assertEquals(logical.getString("chosenCallId"),nativeUsage.getJSONArray("receipts").getJSONObject(0).getString("callId"));assertFalse(nativeUsage.toString().contains("fixture-key"));
                assertEquals(400,relayHttp(endpoint+"/messages",token,new JSONObject(client.toString()).put("model","model-b")).getInt("status"));assertEquals(1,aPosts.get());
                aStatus=503;assertEquals(503,relayHttp(endpoint+"/messages",token,client).getInt("status"));assertEquals(2,aPosts.get());assertEquals(0,bPosts.get());
                settings.selectModelPurpose("RELAY",ModelTargetRef.exact(b.getString("id"),"model-b"));settings.selectModelPurpose("RELAY",ref);aStatus=200;assertNotEquals(200,relayHttp(endpoint+"/messages",token,client).getInt("status"));assertEquals(2,aPosts.get());assertFalse(proxy.snapshot().getBoolean("routeCurrent"));
                proxy.handle("stop",new JSONObject());state=proxy.handle("start",new JSONObject().put("port",0).put("token",token));assertEquals(200,relayHttp(state.getString("endpoint")+"/messages",token,client).getInt("status"));assertEquals(3,aPosts.get());
            }
        }finally{java.security.Security.removeProvider("ModelGroupRelayFixture");}
    }
    private static JSONObject relayHttp(String url,String token,JSONObject request)throws Exception {
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(5000);connection.setRequestProperty("Authorization","Bearer "+token);
        try{if(request!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");byte[] body=request.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);try(var out=connection.getOutputStream()){out.write(body);}}
            int status=connection.getResponseCode();try(var input=status>=400?connection.getErrorStream():connection.getInputStream()){return new JSONObject().put("status",status).put("body",new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8)));}
        }finally{connection.disconnect();}
    }

    @Test public void externalAliasCrossProtocolUsesOriginalRelayBudgetAndLogicalCommit()throws Exception {
        a=settings.saveApiConnection(a.getString("id"),1,"A",input("a","model-a","fixture-key-A").put("provider","openai_responses"));b=settings.saveApiConnection(b.getString("id"),1,"B",input("b","model-b","fixture-key-B").put("provider","openai_responses"));
        JSONObject group=group(3,1);JSONArray candidates=group.getJSONArray("candidates");for(int i=0;i<candidates.length();i++)candidates.getJSONObject(i).put("allowedPurposes",new JSONArray(List.of("MAIN","SUMMARY","RELAY"))).put("allowedAudiences",new JSONArray(List.of("APP_OWNER","EXTERNAL_RELAY")));
        JSONObject external=settings.saveModelGroup(group.getString("groupId"),1,"Explicit external API group",group);ModelConfig selected=settings.captureModelTarget(ModelTargetRef.group(external.getString("groupId"),2),"RELAY","EXTERNAL_RELAY",settings.get());JSONObject client=new JSONObject().put("model",external.getString("groupId")).put("max_tokens",128).put("messages",messages());
        RunControl relayControl=new RunControl();TaskStore.NativeInferenceSession relay=tasks.beginNativeInference(TaskStore.NativeInferenceKind.RELAY,"group-protocol-fixture",relayControl);AtomicInteger commits=new AtomicInteger();
        JSONObject reply=ModelGateway.completeNative(selected,dev.pocketengine.runtime.providers.ProtocolCodec.prepare(selected,"anthropic",client),relayControl,relay.target(),(call,sequence)->{JSONObject row=tasks.inferenceUsage(relay.taskId).getJSONArray("logicalRequests").getJSONObject(0);assertTrue(row.getBoolean("semanticCommitted"));assertEquals(call,row.getString("chosenCallId"));commits.incrementAndGet();},null);
        assertEquals("message",reply.getString("type"));assertEquals("native result",reply.getJSONArray("content").getJSONObject(0).getString("text"));assertEquals(1,commits.get());JSONObject usage=tasks.inferenceUsage(relay.taskId),row=usage.getJSONArray("logicalRequests").getJSONObject(0);assertEquals("SUCCEEDED",row.getString("state"));assertEquals(1,row.getJSONObject("control").getInt("maxAttempts"));JSONObject receipt=usage.getJSONArray("receipts").getJSONObject(0);assertEquals("anthropic",receipt.getJSONObject("route").getString("ingressProtocol"));assertEquals("openai_responses",receipt.getJSONObject("route").getString("egressProtocol"));relay.finish("completed");
        aStatus=503;RunControl refusedControl=new RunControl();TaskStore.NativeInferenceSession refused=tasks.beginNativeInference(TaskStore.NativeInferenceKind.RELAY,"group-protocol-failure",refusedControl);assertThrows(Exception.class,()->ModelGateway.completeNative(selected,dev.pocketengine.runtime.providers.ProtocolCodec.prepare(selected,"anthropic",client),refusedControl,refused.target(),(call,sequence)->fail("failed request must not flush"),null));assertEquals(2,aPosts.get());assertEquals(0,bPosts.get());assertEquals(1,tasks.inferenceUsage(refused.taskId).getJSONArray("receipts").length());refused.finish("failed");
    }

    @Test public void everyLegacySchemaOneThroughFourMigratesWithoutDeletingEventsOrUnknownEffects()throws Exception {
        for(int version=1;version<=4;version++){
            db().close();app.deleteDatabase("pocket-world.db");String id=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
            try(SQLiteDatabase old=SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("pocket-world.db"),null)){
                old.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");old.execSQL("INSERT INTO meta(key,value) VALUES('legacyImported','1')");
                old.execSQL("CREATE TABLE tasks(task_id TEXT PRIMARY KEY,document TEXT NOT NULL,updated_at INTEGER NOT NULL)");old.execSQL("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,tool TEXT NOT NULL,arguments TEXT NOT NULL,state TEXT NOT NULL,receipt TEXT,created_at INTEGER NOT NULL)");old.execSQL("CREATE INDEX operations_task ON operations(task_id,state)");
                old.execSQL("CREATE TABLE runs(run_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,epoch INTEGER NOT NULL,status TEXT NOT NULL,checkpoint TEXT NOT NULL,grant_json TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(task_id,epoch))");old.execSQL("CREATE TABLE leases(resource_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,epoch INTEGER NOT NULL,expires_at INTEGER NOT NULL)");
                JSONObject document=new JSONObject().put("id",id).put("kind","assistant").put("status","reconciling").put("goal","legacy original task").put("actorId","owner").put("spaceId","private").put("events",new JSONArray().put(new JSONObject().put("type","legacy_original").put("seq",42).put("message","keep original 🧭 v"+version)));
                ContentValues row=new ContentValues();row.put("task_id",id);row.put("document",document.toString());row.put("updated_at",42);old.insertOrThrow("tasks",null,row);old.execSQL("INSERT INTO operations(operation_id,task_id,run_id,tool,arguments,state,created_at) VALUES(?,?,?,?,?,?,?)",new Object[]{operation,id,run,"unknown_original_effect","{}","unknown",42});
                if(version>=2)dev.pocketengine.features.assistant.ConversationRepository.createSchema(old);
                if(version>=3){TaskAuditStore.createSchema(old);TaskAuditStore.migrateLegacy(old);}
                if(version>=4){dev.pocketengine.features.local.project.ProjectPlanRecords.createSchema(old);var schema=TaskStore.class.getDeclaredMethod("createTeamParentSchema",SQLiteDatabase.class);schema.setAccessible(true);schema.invoke(null,old);}
                old.setVersion(version);
            }
            tasks=new TaskStore(app);assertEquals(6,db().getVersion());assertTrue(tasks.hasUnresolved(id));JSONObject restored=tasks.get(id,false);assertEquals("keep original 🧭 v"+version,restored.getJSONArray("events").getJSONObject(0).getString("message"));assertEquals(42,restored.getJSONArray("events").getJSONObject(0).getLong("seq"));assertEquals(operation,restored.getJSONArray("operations").getJSONObject(0).getString("operationId"));assertEquals(0,tasks.inferenceUsage(id).getJSONArray("logicalRequests").length());
        }
        assertEquals(0,aPosts.get());assertEquals(0,bPosts.get());
    }

    @Test public void semanticPartialCannotSpliceAnotherCandidateAndUnknownToolEffectBlocksDispatch()throws Exception {
        ModelConfig selected=select(group(3,1));partialStream=true;AtomicInteger deltas=new AtomicInteger();assertThrows(Exception.class,()->ModelGateway.completeStreaming(selected,messages(),null,original,e->{if(e.kind==ModelGateway.StreamKind.TEXT_DELTA)deltas.incrementAndGet();},journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertTrue(deltas.get()>0);assertEquals(1,aPosts.get());assertEquals(0,bPosts.get());assertEquals("PARTIAL",logical().getString("state"));assertEquals("partial",logical().getJSONObject("control").getString("streamCompleteness"));
        start();ContentValues pending=new ContentValues();pending.put("operation_id",UUID.randomUUID().toString());pending.put("task_id",task.getString("id"));pending.put("run_id",task.getString("runId"));pending.put("tool","unknown_fixture_effect");pending.put("arguments","{}");pending.put("state","unknown");pending.put("created_at",System.currentTimeMillis());db().insertOrThrow("operations",null,pending);assertThrows(Exception.class,()->ModelGateway.complete(selected,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(1,aPosts.get());assertEquals(0,bPosts.get());assertTrue(tasks.hasUnresolved(task.getString("id")));
    }

    @Test public void groupPermissionRevokedAfterSelectionStillBlocksTheFrozenAttemptBeforeHttp()throws Exception {
        JSONObject group=group(3,1);ModelConfig selected=select(group);assertThrows(Exception.class,()->ModelGateway.completeStreaming(selected,messages(),null,original,event->{if(event.kind==ModelGateway.StreamKind.STARTED){group.getJSONObject("allowedAccounts").put("refs",new JSONArray());settings.saveModelGroup(group.getString("groupId"),1,"Revoked original group",group);}},journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(0,aPosts.get());assertEquals(0,bPosts.get());assertEquals("not_dispatched",logical().getJSONObject("control").getJSONArray("attempts").getJSONObject(0).getString("dispatchState"));assertEquals(0,usage().getJSONArray("receipts").length());
    }

    @Test public void opaqueNativeHistoryRequiresBothOriginalRouteAndUnchangedNativePayloadDigest()throws Exception {
        a=settings.saveApiConnection(a.getString("id"),1,"A",input("a","model-a","fixture-key-A").put("provider","openai_responses"));ModelConfig selected=settings.get();JSONObject response=ModelGateway.complete(selected,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));assertTrue(response.getJSONObject("provider_data").has("originCallId"));JSONArray history=messages().put(response);ModelGateway.complete(selected,history,null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));assertEquals(2,aPosts.get());
        JSONObject tampered=new JSONObject(response.toString());tampered.getJSONObject("provider_data").getJSONArray("items").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("text","not the original native payload");assertThrows(Exception.class,()->ModelGateway.complete(selected,messages().put(tampered),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(2,aPosts.get());assertEquals(0,bPosts.get());
    }

    @Test public void logicalRetriesStillSpendTheOriginalTaskWideRecoveryBudget()throws Exception {
        aStatus=429;errorCode="rate_limit_exceeded";ModelConfig selected=select(group(3,1));for(int request=0;request<4;request++)assertThrows(Exception.class,()->ModelGateway.complete(selected,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(10,aPosts.get());assertEquals(0,bPosts.get());assertEquals(6,tasks.get(task.getString("id"),false).getJSONObject("modelRecoveryBudget").getInt("retries"));assertEquals(4,usage().getJSONArray("logicalRequests").length());assertEquals(10,usage().getJSONArray("receipts").length());
    }

    @Test public void originalProviderHistoryCannotMoveToFrozenBWhenItsOriginalAIsRevoked()throws Exception {
        a=settings.saveApiConnection(a.getString("id"),1,"A",input("a","model-a","fixture-key-A").put("provider","openai_responses"));b=settings.saveApiConnection(b.getString("id"),1,"B",input("b","model-b","fixture-key-B").put("provider","openai_responses"));
        ModelConfig selected=select(group(3,1));JSONObject originalResponse=ModelGateway.complete(selected,messages(),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));assertTrue(originalResponse.getJSONObject("provider_data").has("originCallId"));
        settings.setApiConnectionEnabled(a.getString("id"),2,false);assertThrows(Exception.class,()->ModelGateway.complete(selected,messages().put(originalResponse),null,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"")));assertEquals(1,aPosts.get());assertEquals(0,bPosts.get());assertEquals("FAILED",logical().getString("state"));assertEquals(0,logical().getJSONObject("control").getJSONArray("attempts").length());
    }


    @Test public void nativeSnapshotIdentityQuotesStringDelimitersAndDoesNotCoerceTypes()throws Exception {
        var canonical=InferenceRequestStore.class.getDeclaredMethod("canonical",Object.class);canonical.setAccessible(true);
        assertNotEquals(canonical.invoke(null,new JSONArray().put("a,b")),canonical.invoke(null,new JSONArray().put("a").put("b")));
        assertNotEquals(canonical.invoke(null,new JSONObject().put("model","null")),canonical.invoke(null,new JSONObject().put("model",JSONObject.NULL)));
        assertNotEquals(canonical.invoke(null,new JSONObject().put("model","{\"name\":\"a,b\"}")),canonical.invoke(null,new JSONObject().put("model",new JSONObject().put("name","a,b"))));
        InferenceCapture.Target target=journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"");String first=UUID.randomUUID().toString();ModelConfig originalConfig=settings.get().withModel("a,b[\"quoted\"]");JSONObject plan=ModelRoutePlan.exact(originalConfig,"MAIN","APP_OWNER").toJson();target.logical.begin(first,"a".repeat(64),plan,System.currentTimeMillis()+30_000);target.logical.finish(first,"FAILED","failed","none");JSONObject changed=ModelRoutePlan.exact(originalConfig.withModel("a,b[\"different\"]"),"MAIN","APP_OWNER").toJson();assertThrows(SecurityException.class,()->target.logical.begin(UUID.randomUUID().toString(),"a".repeat(64),changed,System.currentTimeMillis()+30_000));assertEquals(0,aPosts.get());assertEquals(0,bPosts.get());
    }

    @Test public void memberTargetsCannotOperateAnotherNativeMembersLogicalRequest()throws Exception {
        InferenceCapture.Target first=journal.inferenceTarget(InferenceReceipt.Purpose.MEMBER,"member-a"),second=journal.inferenceTarget(InferenceReceipt.Purpose.MEMBER,"member-b");String logical=UUID.randomUUID().toString();JSONObject plan=ModelRoutePlan.exact(settings.get(),"MEMBER","APP_OWNER").toJson();first.logical.begin(logical,"a".repeat(64),plan,System.currentTimeMillis()+30_000);assertThrows(SecurityException.class,()->second.logical.reserve(logical,UUID.randomUUID().toString(),1,"exact","initial_ordered_candidate"));first.logical.reserve(logical,UUID.randomUUID().toString(),1,"exact","initial_ordered_candidate");first.logical.finish(logical,"FAILED","failed","none");assertEquals(0,aPosts.get());assertEquals(0,bPosts.get());
    }
}
