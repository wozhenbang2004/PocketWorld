package dev.pocketengine;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.Intent;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.usage.*;
import dev.pocketengine.features.remote.RemoteModule;
import dev.pocketengine.features.proxy.ProxyModule;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ServiceController;
import static org.junit.Assert.*;

/** Actual App/startAttempt/control registry + actual original Service + original Companion/CLI.
 * Only CLI peer/provider and in-memory test keystore are fixtures. No control map writes/grants. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class GatewayContinuationAppJourneyTest {
    private static final String TOKEN="fixture-only-paired-gateway-token-not-real-123456789012345",KEY="fixture-only-phone-provider-key-never-exported";
    static { installFixtureVault(); }
    @BeforeClass public static void installFixtureVault(){if(java.security.Security.getProvider("GatewayFixtureVault")==null)java.security.Security.addProvider(new java.security.Provider("GatewayFixtureVault",1.0,"Isolated virtual credentials only"){{put("KeyStore.AndroidKeyStore",FixtureKeyStore.class.getName());}});}
    public static final class FixtureKeyStore extends java.security.KeyStoreSpi {
        private static final javax.crypto.SecretKey KEY=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        public java.security.Key engineGetKey(String a,char[] p){return KEY;}public java.security.cert.Certificate[] engineGetCertificateChain(String a){return null;}public java.security.cert.Certificate engineGetCertificate(String a){return null;}public Date engineGetCreationDate(String a){return new Date(0);}public void engineSetKeyEntry(String a,java.security.Key k,char[] p,java.security.cert.Certificate[] c){throw new UnsupportedOperationException();}public void engineSetKeyEntry(String a,byte[] k,java.security.cert.Certificate[] c){throw new UnsupportedOperationException();}public void engineSetCertificateEntry(String a,java.security.cert.Certificate c){throw new UnsupportedOperationException();}public void engineDeleteEntry(String a){throw new UnsupportedOperationException();}public Enumeration<String> engineAliases(){return Collections.enumeration(List.of("pocketengine.feature-secrets.v1","pocketengine.model-key.v1"));}public boolean engineContainsAlias(String a){return a.equals("pocketengine.feature-secrets.v1")||a.equals("pocketengine.model-key.v1");}public int engineSize(){return 2;}public boolean engineIsKeyEntry(String a){return engineContainsAlias(a);}public boolean engineIsCertificateEntry(String a){return false;}public String engineGetCertificateAlias(java.security.cert.Certificate c){return null;}public void engineStore(OutputStream o,char[] p){}public void engineLoad(InputStream i,char[] p){}
    }
    @Rule public final org.junit.rules.TestName testName=new org.junit.rules.TestName();
    private JSONObject profileConnection,profileDefinition,selectedGatewayDefinition;private String fixtureWorker="codex_app_server";private String profileResolution;private boolean profileMode;private String approvalHoldMode="";private boolean heldBoundaryStopped;private long originalScopeRevision,originalScopeDeadline;private int originalModelLimit;
    private EngineApplication app;private RemoteModule remote;private Activity activity;private Process companion;private Path root;private JSONObject info,parent,page;private String child,oldParentRun;private int initialModels,initialTools;private ServiceController<PhoneRunService> service;private HttpServer peer;private final AtomicInteger calls=new AtomicInteger();private final AtomicReference<Throwable> peerFailure=new AtomicReference<>();private final CountDownLatch peerSeen=new CountDownLatch(1),peerRelease=new CountDownLatch(1);
    private JSONObject owner(String command,JSONObject extra)throws Exception {JSONObject input=new JSONObject(extra.toString()).put("windowId",page.getString("windowId")).put("taskId",child);return app.remoteOwnerWorkflows.handle(activity,command,input);}
    private JSONObject owner(String command)throws Exception{return owner(command,new JSONObject());}
    private SQLiteDatabase db()throws Exception{var field=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(app.tasks);}
    private int count(String sql,String...args)throws Exception{try(Cursor c=db().rawQuery(sql,args.length==0?null:args)){assertTrue(c.moveToFirst());return c.getInt(0);}}
    @Before public void open()throws Exception {
        fixtureWorker=testName.getMethodName().contains("Claude")?"claude":"codex_app_server";
        installFixtureVault();app=(EngineApplication)RuntimeEnvironment.getApplication();root=Files.createTempDirectory("gateway-real-app-");String source=System.getProperty("pocket.gateway.source");assertNotNull(source);
        var fixtureCommand=new java.util.ArrayList<>(java.util.List.of(System.getProperty("pocket.gateway.python","python3"),"-B",source+"/companion/gateway_fixture_server.py",root.toString(),"--prepare-prior"));if(testName.getMethodName().equals("savedGatewayProfileBindsWithinOriginalScopeThenActualDriverAppliesReadsBackAndRuns")||testName.getMethodName().startsWith("savedGatewayProfileSource"))fixtureCommand.add("--native-entrypoint");
        companion=new ProcessBuilder(fixtureCommand).redirectError(root.resolve("companion.stderr").toFile()).start();info=new JSONObject(new BufferedReader(new InputStreamReader(companion.getInputStream(),StandardCharsets.UTF_8)).readLine());
        peer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);peer.createContext("/",exchange->{try{calls.incrementAndGet();assertEquals("/v1/responses",exchange.getRequestURI().getPath());assertEquals("Bearer "+KEY,exchange.getRequestHeaders().getFirst("Authorization"));String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertFalse(request.contains(TOKEN));peerSeen.countDown();assertTrue(peerRelease.await(10,TimeUnit.SECONDS));JSONObject response=new JSONObject().put("id","controlled-native-result").put("model","fixture-model").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("id","controlled-text").put("type","message").put("role","assistant").put("status","completed").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","actual original native model result ".repeat(1000)))))).put("usage",new JSONObject().put("input_tokens",12).put("output_tokens",3));byte[] body=("event: response.created\ndata: "+new JSONObject().put("type","response.created").put("response",new JSONObject().put("id","controlled-native-result").put("status","in_progress"))+"\n\nevent: response.completed\ndata: "+new JSONObject().put("type","response.completed").put("response",response)+"\n\n").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,body.length);for(int i=0;i<body.length;i+=4096){exchange.getResponseBody().write(body,i,Math.min(4096,body.length-i));exchange.getResponseBody().flush();}}catch(Throwable error){peerFailure.set(error);}finally{exchange.close();}});peer.start();String base="http://127.0.0.1:"+peer.getAddress().getPort()+"/v1";
        JSONObject profile=app.settings.saveApiConnection("",0,"受控本地模型",new JSONObject().put("provider","openai_responses").put("baseUrl",base).put("model","fixture-model").put("apiKey",KEY).put("requestTimeoutSeconds",15).put("maxOutputTokens",4096).put("cleartextConsent",ModelConfig.cleartextConsentFor("openai_responses",base)));app.settings.selectApiConnection(profile.getString("id"),profile.getLong("revision"));profileConnection=profile;profileMode=testName.getMethodName().startsWith("profile");
        remote=(RemoteModule)app.features.module("remote");remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","受控电脑").put("baseUrl",info.getString("baseUrl")).put("token",TOKEN));
        parent=app.tasks.createCapabilityTask("Original owner task: continue same remote CLI through native gateway","");String id=parent.getString("id");app.tasks.initializeExecutionScope(id,1);RunControl old=new RunControl();
        if(profileMode){
            var profilePage=app.nativeWorkProfiles().newOwnerPage();profilePage.setForeground(true);
            try{JSONObject draft=dev.pocketengine.features.controlplane.WorkProfile.definition("gateway-original-profile",1,"Original frozen gateway task");JSONObject exact=dev.pocketengine.runtime.models.ModelTargetRef.exact(profile.getString("id"),"fixture-model").toJson();draft.getJSONObject("modelPolicies").put("MAIN",exact).put("SUMMARY",new JSONObject(exact.toString()));if(!testName.getMethodName().contains("NoRelay"))draft.getJSONObject("modelPolicies").put("RELAY",new JSONObject(exact.toString()));draft.getJSONObject("resourcePreferences").put("maxConcurrentModelCalls",1);
                profileDefinition=profilePage.save("",0,"Original frozen gateway task",draft);var inspected=profilePage.review(profileDefinition.getString("profileId"),1,new RunControl());old.restrictModelConcurrency(1);var selected=profilePage.select(inspected,inspected.digest,old);parent=app.tasks.startAttemptWithWorkProfile(id,"capabilities",1,selected,old);profileResolution=app.tasks.workProfileRunSnapshot(id,parent.getString("runId"),parent.getInt("coordinatorEpoch")).getString("resolutionDigest");assertThrows("Gateway-only SQL binding must reject an ordinary Profile run",SecurityException.class,()->app.tasks.checkGatewayWorkProfileBinding(id,parent.getString("runId"),parent.getInt("coordinatorEpoch"),profileResolution,old));
            }finally{profilePage.close();}
        }else parent=app.tasks.startAttempt(id,"capabilities",1);
        oldParentRun=parent.getString("runId");old.bindHostRun(id,oldParentRun,parent.getInt("coordinatorEpoch"));app.tasks.authorizeTools(id,oldParentRun,new JSONArray().put("remote_start_task"));
        JSONObject args=new JSONObject().put("profileId","computer").put("bindingHash",remote.profileBindingHash("computer")).put("environmentId","fixture").put("environmentBinding",info.getString("environmentBinding")).put("worker",fixtureWorker).put("prompt","Controlled prior CLI, intentionally no model").put("model","fixture-model").put("readOnly",fixtureWorker.equals("codex_app_server")).put("timeoutSeconds",30);
        var journal=app.tasks.journal(id,oldParentRun,parent.getInt("coordinatorEpoch"),old);String operation=journal.beginOperation("remote_start_task",args);JSONObject delegation=app.tasks.prepareRemoteDelegation(id,oldParentRun,parent.getInt("coordinatorEpoch"),operation,dev.pocketengine.features.remote.RemoteTaskRequest.first(args),old);child=delegation.getString("taskId");JSONObject submitted=remote.handle("submitJob",new JSONObject(args.toString()).put("operationId",child).put("serviceBase",info.getString("baseUrl")));app.tasks.updateExternal(child,submitted);journal.finishOperation(operation,new JSONObject().put("ok",true).put("hostTaskId",child).put("remoteEffectsVerified",false));
        long end=System.currentTimeMillis()+15000;JSONObject prior=submitted;Set<String> decisions=new HashSet<>();while(System.currentTimeMillis()<end){prior=remote.handle("getJob",new JSONObject().put("profileId","computer").put("serviceBase",info.getString("baseUrl")).put("jobId",submitted.getString("id")).put("jobOperationId",child));app.tasks.updateExternal(child,prior);for(int i=0;i<prior.optJSONArray("pendingApprovals").length();i++){String request=prior.getJSONArray("pendingApprovals").getJSONObject(i).getString("requestId");if(decisions.add(request))remote.handle("approveJob",new JSONObject().put("profileId","computer").put("jobId",prior.getString("id")).put("jobOperationId",child).put("approvalRequestId",request).put("decision","accept"));}if(prior.optString("status").equals("completed"))break;Thread.sleep(20);}assertEquals(prior.toString(),"completed",prior.getString("status"));assertEquals(0,calls.get());assertFalse(Files.exists(root.resolve("prior-no-model-once")));
        app.tasks.endAttempt(id,oldParentRun,"awaiting_user","原助手安全等待，继续须主人明确批准",new JSONObject());assertNull(app.activeGatewayParentRun(id,oldParentRun));JSONObject scope=app.tasks.get(id,false).getJSONObject("executionScope");initialModels=scope.getInt("modelCallsUsed");initialTools=scope.getInt("toolsUsed");originalScopeRevision=scope.getLong("revision");originalScopeDeadline=dev.pocketengine.store.TaskExecutionScope.effectiveDeadline(scope);originalModelLimit=scope.getInt("modelCallLimit");assertEquals(scope.getInt("toolLimit"),initialTools);assertTrue(scope.getInt("modelCallLimit")>initialModels);if(profileMode)assertThrows(SecurityException.class,()->app.tasks.startAttempt(id,"capabilities",1));else assertThrows(IllegalStateException.class,()->app.tasks.startAttempt(id,"capabilities",1));
        if(profileMode&&!testName.getMethodName().contains("NoRelay")){
            JSONObject unselected=app.settings.saveApiConnection("",0,"Unselected host gateway",new JSONObject().put("provider","openai_responses").put("baseUrl",base+"/wrong").put("model","must-not-be-used").put("apiKey","synthetic-unselected-key").put("requestTimeoutSeconds",15));app.settings.selectModelPurpose("RELAY",dev.pocketengine.runtime.models.ModelTargetRef.exact(unselected.getString("id"),"must-not-be-used"));
        }
        activity=Robolectric.buildActivity(Activity.class).setup().get();app.remoteOwnerWorkflows.setForeground(activity,true);page=new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",child);owner("open");
    }
    private JSONObject resumeNativeParent()throws Exception {
        int beforeModels=app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope").getInt("modelCallsUsed");boolean firstProfile=selectedGatewayDefinition!=null&&app.tasks.get(parent.getString("id"),false).optJSONObject("workProfileRef")==null;JSONObject review=owner(firstProfile?"gateway.reviewParentProfile":"gateway.reviewParentRun",firstProfile?new JSONObject().put("profileId",selectedGatewayDefinition.getString("profileId")).put("profileRevision",selectedGatewayDefinition.getLong("revision")):new JSONObject()).getJSONObject("snapshot").getJSONObject("gatewayParentRunReview");assertEquals(beforeModels,app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope").getInt("modelCallsUsed"));assertThrows(SecurityException.class,()->owner("gateway.resumeParentRun",new JSONObject().put("acceptedDigest","f".repeat(64))));JSONObject admitted=owner("gateway.resumeParentRun",new JSONObject().put("acceptedDigest",review.getString("digest"))).getJSONObject("snapshot").getJSONObject("gatewayParentAdmission");assertFalse(admitted.getBoolean("nativeServiceRegistered"));Intent intent=Shadows.shadowOf(app).getNextStartedService();while(intent!=null&&!PhoneRunService.START_GATEWAY_PARENT.equals(intent.getAction()))intent=Shadows.shadowOf(app).getNextStartedService();assertNotNull("actual App must request original foreground service",intent);if(service==null)service=Robolectric.buildService(PhoneRunService.class).create();service.get().onStartCommand(intent,0,1);var nativeRun=app.originalGatewayParentRun(admitted.getString("nativeResourceId"));assertNotNull(nativeRun);assertTrue(nativeRun.active());assertTrue(app.ownsNativeTaskControl(nativeRun.task(),nativeRun.control()));if(profileMode){assertEquals(1,nativeRun.control().modelConcurrencyLimit());nativeRun.control().check();nativeRun.checkCurrent();assertEquals(profileDefinition.getString("profileId"),admitted.getJSONObject("workProfile").getString("profileId"));assertFalse(admitted.getJSONObject("workProfile").getBoolean("configurationApplied"));assertEquals(profileResolution,app.tasks.workProfileRunSnapshot(nativeRun.task(),nativeRun.run(),nativeRun.epoch()).getString("resolutionDigest"));}if(selectedGatewayDefinition!=null){assertEquals(selectedGatewayDefinition.getString("profileId"),admitted.getJSONObject("workProfile").getString("profileId"));assertEquals(1,nativeRun.control().modelConcurrencyLimit());assertFalse(admitted.getJSONObject("workProfile").getBoolean("configurationApplied"));assertThrows(SecurityException.class,()->app.tasks.startAttempt(nativeRun.task(),"capabilities",1));}
        JSONObject scope=app.tasks.get(nativeRun.task(),false).getJSONObject("executionScope");assertEquals(originalScopeRevision,scope.getLong("revision"));assertEquals(originalScopeDeadline,dev.pocketengine.store.TaskExecutionScope.effectiveDeadline(scope));assertEquals(originalModelLimit,scope.getInt("modelCallLimit"));assertEquals(beforeModels,scope.getInt("modelCallsUsed"));assertTrue(review.getJSONObject("scope").getLong("expiresAt")<=originalScopeDeadline);assertNotEquals(oldParentRun,nativeRun.run());assertThrows(SecurityException.class,()->app.tasks.journal(nativeRun.task(),nativeRun.run(),nativeRun.epoch(),nativeRun.control()).beginOperation("phone_click",new JSONObject()));assertThrows(SecurityException.class,()->new InferenceCapture(app.settings.captureRelaySelection(),nativeRun.control(),app.tasks.journal(nativeRun.task(),nativeRun.run(),nativeRun.epoch(),nativeRun.control()).inferenceTarget(InferenceReceipt.Purpose.MAIN,"")).dispatch());assertThrows(SecurityException.class,()->app.tasks.authorizeTools(nativeRun.task(),nativeRun.run(),new JSONArray().put("remote_start_task")));assertFalse(app.attachPort(nativeRun.task(),null,nativeRun.control()));assertEquals(0,count("SELECT count(*) FROM leases WHERE resource_id='phone.local'"));return admitted;
    }
    private JSONObject prepare()throws Exception {JSONObject current=owner("refresh").getJSONObject("snapshot");assertTrue(current.toString(),current.getBoolean("canContinue"));return owner("gateway.prepareContinuation",new JSONObject().put("reviewId",current.getString("reviewId")).put("prompt","Continue the actual same CLI and return the original native gateway model result"));}
    private static boolean reviewedCompletion(JSONObject snapshot)throws Exception {
        return snapshot.optBoolean("fresh")&&"confirmed".equals(snapshot.optString("queryStatus"))
                &&"completed".equals(snapshot.getJSONObject("source").optString("executorStatus"));
    }
    private JSONObject awaitReviewedCompletion(long deadline,Set<String> decided)throws Exception {
        JSONObject current=new JSONObject();
        while(System.currentTimeMillis()<deadline){
            current=owner("refresh").getJSONObject("snapshot");boolean approved=false;
            if(current.optBoolean("fresh")){
                JSONArray approvals=current.getJSONObject("source").optJSONArray("pendingApprovals");
                for(int i=0;approvals!=null&&i<approvals.length();i++){
                    JSONObject approval=approvals.getJSONObject(i);
                    if(decided.add(approval.getString("requestId"))){
                        JSONObject decision=new JSONObject().put("reviewId",current.getString("reviewId")).put("requestId",approval.getString("requestId")).put("decision","accept");
                        if(approvalHoldMode.isEmpty())owner("approve",decision);else assertHeldApprovalBoundary(decision);
                        if(heldBoundaryStopped)return current;
                        approved=true;break; // Approval consumes this review; query again before another action.
                    }
                }
                if(!approved&&reviewedCompletion(current))return current;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("No fresh confirmed terminal review of the original gateway job: "+current);
    }
    private void assertHeldApprovalBoundary(JSONObject decision)throws Exception {
        Files.write(root.resolve("hold-next-owner-approval"),new byte[]{1});
        ExecutorService io=Executors.newFixedThreadPool(2);Future<JSONObject> sent=null;Future<Void> checked=null;
        try {
            sent=io.submit(()->owner("approve",decision));
            long until=System.currentTimeMillis()+5000;
            while(!Files.exists(root.resolve("owner-approval-response-held"))&&System.currentTimeMillis()<until){if(sent.isDone())sent.get();Thread.sleep(10);}
            assertTrue("actual native approval HTTP request must be held",Files.exists(root.resolve("owner-approval-response-held")));
            assertEquals(1,count("SELECT count(*) FROM operations WHERE task_id=? AND tool='remote_approval' AND state='remote_approval_sending'",child));
            String operation;JSONObject saved;
            try(Cursor row=db().rawQuery("SELECT operation_id,arguments FROM operations WHERE task_id=? AND tool='remote_approval' AND state='remote_approval_sending'",new String[]{child})){assertTrue(row.moveToFirst());operation=row.getString(0);saved=new JSONObject(row.getString(1));}
            JSONObject relation=app.tasks.gatewayParentTarget(child);
            var bridge=((ProxyModule)app.features.module("proxy")).existingHarnessGateway(relation.getString("parentTaskId"),relation.getString("parentRunId"));assertNotNull(bridge);
            CountDownLatch entered=new CountDownLatch(1);
            checked=io.submit(()->{
                var check=bridge.getClass().getDeclaredMethod("checkContinuous");check.setAccessible(true);entered.countDown();
                try{check.invoke(bridge);return null;}catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof Exception error)throw error;throw failure;}
            });
            assertTrue(entered.await(1,TimeUnit.SECONDS));Thread.sleep(180);
            assertFalse("same typed approval must wait, not fail or grant a new boundary",checked.isDone());
            assertTrue(bridge.running());assertEquals(1,calls.get());
            assertEquals(1,count("SELECT count(*) FROM harness_gateway_claims WHERE lease_id=?",bridge.leaseId()));
            JSONObject scope=app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope");
            assertEquals(initialModels+1,scope.getInt("modelCallsUsed"));assertEquals(initialTools,scope.getInt("toolsUsed"));
            if(approvalHoldMode.equals("release")){
                Files.write(root.resolve("release-owner-approval-response"),new byte[]{1});sent.get(5,TimeUnit.SECONDS);
                try{checked.get(5,TimeUnit.SECONDS);}catch(ExecutionException terminal){
                    assertTrue(terminal.getCause().toString(),terminal.getCause() instanceof InterruptedException);
                    assertFalse("a known normally ended native lease is not an unknown-effect recovery",bridge.snapshot().getBoolean("requiresReconcile"));
                    assertFalse(bridge.running());
                }
                assertEquals(0,count("SELECT count(*) FROM operations WHERE task_id=? AND tool='remote_approval' AND state='remote_approval_sending'",child));
            }else {
                if(approvalHoldMode.equals("cancel"))bridge.originalControl().cancel();
                else if(approvalHoldMode.equals("withdraw"))app.settings.setApiConnectionEnabled(profileConnection.getString("id"),1,false);
                else {
                    android.content.ContentValues wrong=new android.content.ContentValues();
                    if(approvalHoldMode.equals("source")){saved.getJSONObject("source").put("jobId","another-original-job");wrong.put("arguments",saved.toString());}
                    else if(approvalHoldMode.equals("unknown"))wrong.put("state","remote_approval_unknown");
                    else throw new AssertionError("unknown fixture mode");
                    synchronized(app.tasks){assertEquals(1,db().update("operations",wrong,"task_id=? AND operation_id=?",new String[]{child,operation}));}
                }
                final Future<Void> checkedResult=checked;
                ExecutionException stopped=assertThrows(ExecutionException.class,()->checkedResult.get(3,TimeUnit.SECONDS));
                assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof SecurityException||stopped.getCause() instanceof InterruptedException||stopped.getCause() instanceof java.io.IOException);
                assertEquals(1,calls.get());assertEquals(1,count("SELECT count(*) FROM harness_gateway_claims WHERE lease_id=?",bridge.leaseId()));
                heldBoundaryStopped=true;
                Files.write(root.resolve("release-owner-approval-response"),new byte[]{1});sent.get(5,TimeUnit.SECONDS);
                assertEquals(initialModels+1,app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope").getInt("modelCallsUsed"));
            }
        } finally {
            Files.write(root.resolve("release-owner-approval-response"),new byte[]{1});
            io.shutdown();if(!io.awaitTermination(6,TimeUnit.SECONDS)){io.shutdownNow();assertTrue(io.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @After public void close()throws Exception {peerRelease.countDown();if(root!=null&&parent!=null)try{JSONObject evidence=new JSONObject().put("usage",app.tasks.inferenceUsage(parent.getString("id"))).put("scope",app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope")).put("gatewaySession",app.tasks.gatewayParentSessionForUi(parent.getString("id")));if(child!=null){evidence.put("continuation",app.tasks.originalGatewayContinuation(child));var proxy=(ProxyModule)app.features.module("proxy");JSONObject scope=app.tasks.gatewayParentTarget(child);var live=proxy.existingHarnessGateway(scope.getString("parentTaskId"),scope.getString("parentRunId"));if(live!=null)evidence.put("nativeBridge",live.snapshot());}Files.write(root.resolve("native-evidence.json"),evidence.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception unavailable){Files.write(root.resolve("native-evidence-unavailable.txt"),unavailable.getClass().getName().getBytes(StandardCharsets.UTF_8));}if(app!=null&&page!=null){try{app.remoteOwnerWorkflows.close(page.getString("windowId"));}catch(Exception ignored){}}if(service!=null)service.destroy();if(peer!=null)peer.stop(0);if(companion!=null){companion.destroy();if(!companion.waitFor(5,TimeUnit.SECONDS)){companion.destroyForcibly();companion.waitFor();}}if(app!=null){app.onTerminate();try{app.features.close();}catch(Exception ignored){}}java.security.Security.removeProvider("GatewayFixtureVault");}
    private void assertOriginalAppJourney()throws Exception {
        JSONObject admission=resumeNativeParent(),prepared=prepare(),stage=prepared.getJSONObject("snapshot").getJSONObject("gatewayContinuation");String operation=stage.getString("operationId");assertTrue(stage.getBoolean("locallyPrepared"));assertFalse(stage.getBoolean("processStarted"));assertThrows(SecurityException.class,()->app.tasks.claimRemoteContinuation(child,operation));assertEquals(0,calls.get());assertEquals(2,app.tasks.list().length());
        JSONObject inspected=owner("gateway.inspect").getJSONObject("snapshot").getJSONObject("harnessGateway"),review=owner("gateway.review",new JSONObject().put("optionRef",inspected.getJSONArray("targets").getJSONObject(0).getString("optionRef"))).getJSONObject("snapshot").getJSONObject("harnessGateway");JSONObject enabled=owner("gateway.enable",new JSONObject().put("acceptedDigest",review.getString("reviewDigest"))).getJSONObject("snapshot").getJSONObject("harnessGateway");String lease=enabled.getJSONObject("status").getString("leaseId");Intent start=Shadows.shadowOf(app).getNextStartedService();assertNotNull(start);assertEquals(PhoneRunService.START_GATEWAY,start.getAction());service.get().onStartCommand(start,0,2);var bridge=((ProxyModule)app.features.module("proxy")).originalHarnessGateway(lease);long deadline=System.currentTimeMillis()+5000;while(!bridge.running()&&System.currentTimeMillis()<deadline)Thread.sleep(10);assertTrue(bridge.running());
        owner("gateway.configure",new JSONObject().put("leaseId",lease));JSONObject preview=owner("previewHarnessConfig").getJSONObject("snapshot");JSONObject committed=owner("confirmHarnessConfig",new JSONObject().put("acceptedPlanDigest",preview.getJSONObject("plan").getString("planDigest"))).getJSONObject("snapshot");assertEquals("COMMITTED",committed.getJSONObject("receipt").getString("state"));if(selectedGatewayDefinition!=null){assertTrue(committed.getBoolean("targetConfigurationReadback"));assertEquals(selectedGatewayDefinition.getString("profileId"),committed.getJSONObject("workProfileSource").getString("profileId"));assertTrue(committed.getJSONObject("receipt").getBoolean("effectiveReadback"));}assertEquals(0,calls.get());assertEquals("remote_job_prepared",app.tasks.originalGatewayContinuation(child).getString("operationState"));
        JSONObject finalReview=owner("gateway.reviewRun",new JSONObject().put("leaseId",lease)).getJSONObject("snapshot").getJSONObject("gatewayExecutionReview");owner("gateway.run",new JSONObject().put("acceptedDigest",finalReview.getString("digest")));assertTrue(peerSeen.await(10,TimeUnit.SECONDS));assertEquals("REQUEST_IN_PROGRESS",app.tasks.gatewayParentSessionForUi(parent.getString("id")).getString("state"));peerRelease.countDown();owner("gateway.run",new JSONObject().put("acceptedDigest",finalReview.getString("digest")));JSONObject unreviewedTerminal=new JSONObject().put("fresh",false).put("queryStatus","changed").put("source",new JSONObject().put("executorStatus","completed"));assertFalse("Stored completion is not a current owner review",reviewedCompletion(unreviewedTerminal));deadline=System.currentTimeMillis()+20000;Set<String> decided=new HashSet<>();JSONObject current=awaitReviewedCompletion(deadline,decided);if(heldBoundaryStopped)return;assertEquals("Native bridge status: "+bridge.snapshot(),"completed",current.getJSONObject("source").getString("executorStatus"));assertEquals(1,calls.get());assertNull(peerFailure.get());JSONObject priorReview=current,superseding=owner("refresh").getJSONObject("snapshot");assertNotEquals("Every new query replaces the previous native review",priorReview.getString("reviewId"),superseding.getString("reviewId"));assertThrows(SecurityException.class,()->owner("readArtifact",new JSONObject().put("reviewId",priorReview.getString("reviewId")).put("path","gateway-model-result.sse")));current=awaitReviewedCompletion(deadline,decided);JSONObject artifact=owner("readArtifact",new JSONObject().put("reviewId",current.getString("reviewId")).put("path","gateway-model-result.sse"));assertTrue(artifact.toString(),artifact.toString().contains("actual original native model result"));assertTrue(new String(Files.readAllBytes(root.resolve("workspace/gateway-model-result.sse")),StandardCharsets.UTF_8).contains("actual original native model result"));assertEquals(1,count("SELECT count(*) FROM harness_gateway_claims WHERE lease_id=? AND state='COMPLETE'",lease));JSONObject parentAfter=app.tasks.get(parent.getString("id"),false),scope=parentAfter.getJSONObject("executionScope");assertEquals(initialModels+1,scope.getInt("modelCallsUsed"));assertEquals(initialTools,scope.getInt("toolsUsed"));JSONArray receipts=app.tasks.inferenceUsage(parent.getString("id")).getJSONArray("receipts");assertEquals(1,receipts.length());assertEquals("RELAY",receipts.getJSONObject(0).getJSONObject("context").getString("purpose"));assertEquals(admission.getString("billingRunId"),receipts.getJSONObject(0).getJSONObject("context").getString("runId"));assertEquals(oldParentRun,app.tasks.requirePrivateRemoteChild(child).getString("parentRunId"));assertEquals(0,count("SELECT count(*) FROM leases WHERE resource_id='phone.local'"));
        // Original lease terminal projection is honest even before Android's 500ms service watch.
        deadline=System.currentTimeMillis()+5000;while(bridge.running()&&System.currentTimeMillis()<deadline)Thread.sleep(10);assertFalse(bridge.running());assertEquals("STOPPED",app.tasks.gatewayParentSessionForUi(parent.getString("id")).getString("state"));
        var retained=app.originalGatewayParentRun(admission.getString("nativeResourceId"));assertNotNull(retained);String firstBilling=retained.run();service.get().onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.STOP_GATEWAY_PARENT).putExtra("gatewayParentResourceId",retained.id()),0,3);deadline=System.currentTimeMillis()+5000;while(app.originalGatewayParentRun(retained.id())!=null&&System.currentTimeMillis()<deadline)Thread.sleep(10);assertNull(app.originalGatewayParentRun(retained.id()));assertTrue(retained.control().isCancelled());assertFalse(app.ownsNativeTaskControl(retained.task(),retained.control()));assertEquals("awaiting_user",app.tasks.get(retained.task(),false).getString("status"));assertThrows(SecurityException.class,retained::checkCurrent);assertEquals("STOPPED",app.tasks.gatewayParentSessionForUi(retained.task()).getString("state"));
        JSONObject nextReview=owner("gateway.reviewParentRun").getJSONObject("snapshot").getJSONObject("gatewayParentRunReview");assertEquals(scope.getInt("modelCallLimit")-initialModels-1,nextReview.getJSONObject("scope").getInt("remainingModels"));assertEquals(0,nextReview.getJSONObject("scope").getInt("remainingScopeTools"));assertEquals(firstBilling,nextReview.getJSONObject("scope").getString("previousParentRunId"));assertEquals(1,calls.get());assertEquals(firstBilling,app.tasks.get(retained.task(),false).getString("runId"));
        JSONObject second=resumeNativeParent();var next=app.originalGatewayParentRun(second.getString("nativeResourceId"));assertNotNull(next);assertNotEquals(firstBilling,next.run());assertEquals(initialModels+1,app.tasks.get(next.task(),false).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(initialTools,app.tasks.get(next.task(),false).getJSONObject("executionScope").getInt("toolsUsed"));service.get().onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.STOP_GATEWAY_PARENT).putExtra("gatewayParentResourceId",retained.id()),0,4);assertTrue("stale original stop cannot revoke the newly approved native run",next.active());assertEquals(2,app.tasks.list().length());assertEquals(1,calls.get());
        System.out.println("T05_STAGE2 actualApp=true actualStartAttempt=true actualForegroundService=true manualControlMapWrite=false originalCompanionDriver=true nativeModelCalls=1 toolsGranted=0 originalTaskCount=2 device=false");
    }
    private JSONObject saveGatewayProfile(boolean target,String environment,boolean unsupportedAsset)throws Exception {
        var profilePage=app.nativeWorkProfiles().newOwnerPage();profilePage.setForeground(true);
        try {
            JSONObject draft=dev.pocketengine.features.controlplane.WorkProfile.definition("gateway-selected-profile",1,"Saved original gateway configuration");
            draft.getJSONObject("modelPolicies").put("RELAY",dev.pocketengine.runtime.models.ModelTargetRef.exact(profileConnection.getString("id"),"fixture-model").toJson());
            draft.getJSONObject("resourcePreferences").put("maxConcurrentModelCalls",1);
            if(target)draft.getJSONArray("targetBindings").put(new JSONObject().put("kind","remote_harness").put("profileId","computer")
                .put("profileBinding",remote.profileBindingHash("computer")).put("environmentId",environment).put("environmentBinding",info.getString("environmentBinding")).put("worker",fixtureWorker).put("optional",false));
            if(unsupportedAsset)draft.getJSONArray("instructionRefs").put(new JSONObject().put("id","not-a-remote-permission").put("revision",1).put("digest","a".repeat(64)).put("optional",false));
            return profilePage.save("",0,"Saved original gateway configuration",draft);
        } finally {profilePage.close();}
    }
    @Test public void savedGatewayProfileBindsWithinOriginalScopeThenActualDriverAppliesReadsBackAndRuns()throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(true,"fixture",false);
        JSONObject before=app.tasks.get(parent.getString("id"),false);assertFalse(before.has("workProfileRef"));
        JSONArray choices=owner("gateway.profileChoices").getJSONObject("snapshot").getJSONArray("gatewayProfileChoices");
        assertEquals(1,choices.length());assertEquals(selectedGatewayDefinition.getString("profileId"),choices.getJSONObject(0).getString("profileId"));
        assertFalse(choices.toString().contains(KEY));assertFalse(choices.toString().contains(TOKEN));assertEquals(0,calls.get());assertEquals(oldParentRun,app.tasks.get(parent.getString("id"),false).getString("runId"));
        assertOriginalAppJourney();
    }
    @Test public void savedGatewayProfileSourceApprovalSettlementWaitsWithoutNewClaims()throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(true,"fixture",false);approvalHoldMode="release";assertOriginalAppJourney();
    }
    @Test public void savedGatewayProfileSourceApprovalWaitStopsOnOriginalCancellation()throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(true,"fixture",false);approvalHoldMode="cancel";assertOriginalAppJourney();assertTrue(heldBoundaryStopped);
    }
    @Test public void savedGatewayProfileSourceApprovalWaitRechecksLiveSourceWithdrawal()throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(true,"fixture",false);approvalHoldMode="withdraw";assertOriginalAppJourney();assertTrue(heldBoundaryStopped);
    }
    @Test public void savedGatewayProfileSourceApprovalWaitRejectsAnotherJob()throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(true,"fixture",false);approvalHoldMode="source";assertOriginalAppJourney();assertTrue(heldBoundaryStopped);
    }
    @Test public void savedGatewayProfileSourceApprovalWaitDoesNotRecoverUnknownEffect()throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(true,"fixture",false);approvalHoldMode="unknown";assertOriginalAppJourney();assertTrue(heldBoundaryStopped);
    }
    private JSONObject restoreAfterActualProfileJourney(boolean outsideEdit)throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(true,"fixture",false);assertOriginalAppJourney();
        var active=app.activeGatewayParentRun(parent.getString("id"),app.tasks.get(parent.getString("id"),false).getString("runId"));assertNotNull(active);active.close();
        // Compensation must not depend on reviving a withdrawn RELAY model or old lease.
        app.settings.setApiConnectionEnabled(profileConnection.getString("id"),1,false);
        String original;try(Cursor row=db().rawQuery("SELECT operation_id FROM operations WHERE task_id=? AND tool='harness_config' AND state='receipt' ORDER BY rowid",new String[]{child})){assertTrue(row.moveToFirst());original=row.getString(0);}
        Path config=root.resolve("codex-home/config.toml");String applied=new String(Files.readAllBytes(config),StandardCharsets.UTF_8);assertTrue(applied,applied.contains("pocketworld"));
        if(outsideEdit){String changed=applied.replaceFirst("(?m)^model_provider\\s*=.*$","model_provider = \"outside-owner\"")+"\n# external owner edit must survive\n";assertNotEquals(applied,changed);Files.write(config,changed.getBytes(StandardCharsets.UTF_8));}
        app.remoteOwnerWorkflows.close(page.getString("windowId"));page=new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",child);owner("open");
        owner("openHarnessConfig",new JSONObject().put("selection",new JSONObject().put("bindingId","fixture-codex").put("restoreOperationId",original)));
        JSONObject preview=owner("previewHarnessConfig").getJSONObject("snapshot");assertEquals(original,preview.getJSONObject("plan").getString("restoreOf"));assertEquals(1,calls.get());
        assertFalse(preview.getBoolean("executionAuthorized"));assertEquals(originalModelLimit,app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope").getInt("modelCallLimit"));
        return preview;
    }
    @Test public void savedGatewayProfileSourceUsedTargetRestoresOriginalFieldsWithoutRevivingTheGateway()throws Exception {
        JSONObject preview=restoreAfterActualProfileJourney(false),scope=app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope");
        assertEquals(0,preview.getJSONObject("plan").getJSONArray("conflicts").length());
        JSONObject restored=owner("confirmHarnessConfig",new JSONObject().put("acceptedPlanDigest",preview.getJSONObject("plan").getString("planDigest"))).getJSONObject("snapshot").getJSONObject("receipt");
        assertEquals("COMMITTED",restored.getString("state"));assertTrue(restored.getBoolean("restored"));assertTrue(restored.getBoolean("effectiveReadback"));
        assertEquals("# original controlled config\n",new String(Files.readAllBytes(root.resolve("codex-home/config.toml")),StandardCharsets.UTF_8));
        assertEquals(1,calls.get());assertNull(app.activeGatewayParentRun(parent.getString("id"),app.tasks.get(parent.getString("id"),false).getString("runId")));
        JSONObject after=app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope");assertEquals(scope.getInt("modelCallsUsed"),after.getInt("modelCallsUsed"));assertEquals(scope.getInt("toolsUsed"),after.getInt("toolsUsed"));assertEquals(originalScopeDeadline,dev.pocketengine.store.TaskExecutionScope.effectiveDeadline(after));
        assertEquals(1,count("SELECT count(*) FROM harness_gateway_claims"));
    }
    @Test public void savedGatewayProfileSourceRestoreConflictCannotDispatchOrOverwriteOutsideEdits()throws Exception {
        JSONObject preview=restoreAfterActualProfileJourney(true);assertTrue(preview.toString(),preview.getJSONObject("plan").getJSONArray("conflicts").length()>0);
        byte[] external=Files.readAllBytes(root.resolve("codex-home/config.toml"));
        assertThrows("A stale/programmatic callback must not submit a conflicting restore that the actual owner UI disabled",SecurityException.class,()->owner("confirmHarnessConfig",new JSONObject().put("acceptedPlanDigest",preview.getJSONObject("plan").getString("planDigest"))));
        assertArrayEquals(external,Files.readAllBytes(root.resolve("codex-home/config.toml")));assertEquals("PREVIEW_ONLY",app.tasks.harnessConfigOperation(child,preview.getString("operationId")).getString("nativeDispatchState"));assertEquals(1,calls.get());
    }
    @Test public void savedGatewayProfileSourceClaudeOriginalDriverUsesExactTargetAndReadback()throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(true,"fixture",false);assertOriginalAppJourney();
        assertEquals("claude",app.tasks.remoteOwnerTarget(child).getString("worker"));
        assertEquals("claude_stream_json",app.tasks.remoteOwnerTarget(child).getString("protocol"));
        assertFalse("The synthetic Read permission peer does not execute a real tool",Files.exists(root.resolve("workspace/result.txt")));
        assertTrue(new String(Files.readAllBytes(root.resolve("claude-home/settings.json")),StandardCharsets.UTF_8).contains("ANTHROPIC_BASE_URL"));
    }
    @Test public void savedGatewayProfileSourceWithdrawalAfterPlanCannotApplyOrStartTheOriginalCli()throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(true,"fixture",false);resumeNativeParent();prepare();
        JSONObject inspected=owner("gateway.inspect").getJSONObject("snapshot").getJSONObject("harnessGateway");
        JSONObject reviewed=owner("gateway.review",new JSONObject().put("optionRef",inspected.getJSONArray("targets").getJSONObject(0).getString("optionRef"))).getJSONObject("snapshot").getJSONObject("harnessGateway");
        JSONObject enabled=owner("gateway.enable",new JSONObject().put("acceptedDigest",reviewed.getString("reviewDigest"))).getJSONObject("snapshot").getJSONObject("harnessGateway");String lease=enabled.getJSONObject("status").getString("leaseId");
        Intent start=Shadows.shadowOf(app).getNextStartedService();assertNotNull(start);assertEquals(PhoneRunService.START_GATEWAY,start.getAction());service.get().onStartCommand(start,0,2);
        var bridge=((ProxyModule)app.features.module("proxy")).originalHarnessGateway(lease);long until=System.currentTimeMillis()+5000;while(!bridge.running()&&System.currentTimeMillis()<until)Thread.sleep(10);assertTrue(bridge.running());
        owner("gateway.configure",new JSONObject().put("leaseId",lease));JSONObject preview=owner("previewHarnessConfig").getJSONObject("snapshot");String digest=preview.getJSONObject("plan").getString("planDigest");
        app.settings.setApiConnectionEnabled(profileConnection.getString("id"),1,false);
        assertThrows(Exception.class,()->owner("confirmHarnessConfig",new JSONObject().put("acceptedPlanDigest",digest)));
        JSONObject observed=owner("queryHarnessConfig").getJSONObject("snapshot");assertEquals("PREPARED",observed.getJSONObject("receipt").getString("state"));assertFalse(observed.optBoolean("targetConfigurationReadback"));
        assertEquals(0,calls.get());assertEquals(initialModels,app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope").getInt("modelCallsUsed"));
        assertEquals("remote_job_prepared",app.tasks.originalGatewayContinuation(child).getString("operationState"));
    }
    @Test public void savedGatewayProfileCannotDropAnotherRequiredTargetOrUnsupportedRemoteAssets()throws Exception {
        JSONObject wrong=saveGatewayProfile(true,"another-environment",false);String before=app.tasks.get(parent.getString("id"),false).getString("runId");
        assertThrows(SecurityException.class,()->owner("gateway.reviewParentProfile",new JSONObject().put("profileId",wrong.getString("profileId")).put("profileRevision",1)));
        JSONObject optionalDraft=new JSONObject(wrong.toString());optionalDraft.getJSONArray("targetBindings").getJSONObject(0).put("optional",true);
        JSONObject optional=app.settings.saveWorkProfile(wrong.getString("profileId"),1,"Still not this target",optionalDraft);
        assertThrows("An unavailable optional target is not silently dropped by this gateway-only entrance",SecurityException.class,()->owner("gateway.reviewParentProfile",new JSONObject().put("profileId",optional.getString("profileId")).put("profileRevision",optional.getLong("revision"))));
        JSONObject assets=saveGatewayProfile(false,"fixture",true);
        SecurityException blocked=assertThrows(SecurityException.class,()->owner("gateway.reviewParentProfile",new JSONObject().put("profileId",assets.getString("profileId")).put("profileRevision",1)));
        assertTrue(blocked.getMessage().contains("资产投射"));assertFalse(app.tasks.get(parent.getString("id"),false).has("workProfileRef"));assertEquals(before,app.tasks.get(parent.getString("id"),false).getString("runId"));assertEquals(0,calls.get());
    }
    @Test public void savedGatewayProfileRevisionChangeInvalidatesOldFirstBindingApprovalWithoutBudgetRefill()throws Exception {
        selectedGatewayDefinition=saveGatewayProfile(false,"fixture",false);
        JSONObject review=owner("gateway.reviewParentProfile",new JSONObject().put("profileId",selectedGatewayDefinition.getString("profileId")).put("profileRevision",1)).getJSONObject("snapshot").getJSONObject("gatewayParentRunReview");
        app.settings.saveWorkProfile(selectedGatewayDefinition.getString("profileId"),1,"Changed saved revision",new JSONObject(selectedGatewayDefinition.toString()).put("name","Changed saved revision"));
        assertThrows(Exception.class,()->owner("gateway.resumeParentRun",new JSONObject().put("acceptedDigest",review.getString("digest"))));
        JSONObject after=app.tasks.get(parent.getString("id"),false);assertFalse(after.has("workProfileRef"));assertEquals(oldParentRun,after.getString("runId"));assertEquals(initialModels,after.getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(initialTools,after.getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(0,calls.get());
    }
    @Test public void actualAppParentResumePrepareConfigConfirmOriginalDriverAndNativeModelResult()throws Exception {assertOriginalAppJourney();}
    @Test public void profileFrozenRelayResumesSameOriginalAppGatewayAndRetainsRemainingBudgets()throws Exception {
        JSONObject reviewed=owner("gateway.reviewParentRun").getJSONObject("snapshot").getJSONObject("gatewayParentRunReview").getJSONObject("scope");assertEquals("frozen_original_work_profile_RELAY",reviewed.getString("relaySourceKind"));assertEquals("fixture-model",reviewed.getString("model"));assertEquals("must-not-be-used",app.settings.captureRelaySelection().model);
        app.settings.removeWorkProfile(profileDefinition.getString("profileId"),1);assertOriginalAppJourney();
    }
    @Test public void profileNoRelayUsesOldHostSelectionWithoutInventingAProfileRelay()throws Exception {
        JSONObject reviewed=owner("gateway.reviewParentRun").getJSONObject("snapshot").getJSONObject("gatewayParentRunReview").getJSONObject("scope");assertEquals("original_host_RELAY",reviewed.getString("relaySourceKind"));assertEquals("original_host_RELAY",reviewed.getJSONObject("workProfile").getString("relaySourceKind"));assertOriginalAppJourney();
    }
    @Test public void profileWithdrawalAfterReviewCannotStartOrRefillAnOriginalRun()throws Exception {
        JSONObject review=owner("gateway.reviewParentRun").getJSONObject("snapshot").getJSONObject("gatewayParentRunReview");int runs=count("SELECT count(*) FROM runs");JSONObject before=app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope");app.settings.setApiConnectionEnabled(profileConnection.getString("id"),1,false);
        assertThrows(Exception.class,()->owner("gateway.resumeParentRun",new JSONObject().put("acceptedDigest",review.getString("digest"))));assertEquals(runs,count("SELECT count(*) FROM runs"));assertEquals(before.toString(),app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope").toString());assertEquals(0,calls.get());
    }
    @Test public void profileOwnerWindowLossCannotReissueTheFrozenSelection()throws Exception {
        JSONObject review=owner("gateway.reviewParentRun").getJSONObject("snapshot").getJSONObject("gatewayParentRunReview");int runs=count("SELECT count(*) FROM runs");app.remoteOwnerWorkflows.setForeground(activity,false);
        assertThrows(SecurityException.class,()->owner("gateway.resumeParentRun",new JSONObject().put("acceptedDigest",review.getString("digest"))));assertEquals(runs,count("SELECT count(*) FROM runs"));assertEquals(initialModels,app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(0,calls.get());
    }
    @Test public void profileBoundSourceWithdrawalIsCheckedWithoutControlAuthorityRecursion()throws Exception {
        JSONObject admission=resumeNativeParent();var nativeRun=app.originalGatewayParentRun(admission.getString("nativeResourceId"));nativeRun.control().check();app.settings.setApiConnectionEnabled(profileConnection.getString("id"),1,false);
        assertThrows(Exception.class,nativeRun::checkCurrent);nativeRun.close();assertTrue(nativeRun.control().isCancelled());assertEquals(initialModels,app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(0,calls.get());
    }
    @Test public void cancellationBeforeFinalClaimCannotReactivateOldPreparedOrConfiguration()throws Exception {JSONObject admission=resumeNativeParent();JSONObject prepared=prepare(),stage=prepared.getJSONObject("snapshot").getJSONObject("gatewayContinuation");String operation=stage.getString("operationId");JSONObject cancelled=owner("gateway.cancelPrepared",new JSONObject().put("operationId",operation));assertTrue(app.tasks.originalGatewayContinuation(child).getBoolean("cancelRequested"));assertEquals("remote_job_receipt",app.tasks.originalGatewayContinuation(child).getString("operationState"));assertTrue(cancelled.getJSONObject("operation").getJSONObject("receipt").getBoolean("notDispatchedConfirmed"));assertThrows(SecurityException.class,()->app.tasks.claimRemoteContinuation(child,operation));assertNull(app.originalGatewayParentRun(admission.getString("nativeResourceId")));assertEquals(0,calls.get());assertEquals(initialModels,app.tasks.get(parent.getString("id"),false).getJSONObject("executionScope").getInt("modelCallsUsed"));}
}
