package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.features.controlplane.NativeWorkProfileHost;
import dev.pocketengine.features.controlplane.NativeWorkProfileSources;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.proxy.NativeHarnessGatewayBridge;
import dev.pocketengine.features.proxy.ProxyModule;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual Settings/SQL frozen run -> original bridge -> native Gateway -> loopback model HTTP.
 * Only the paired transport is a fixture. No target configuration/readback, CLI or live provider claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkProfileRelayNativeTest {
    private static final String KEY="synthetic-profile-relay-key-not-real";
    private Application app;private SettingsStore settings;private TaskStore tasks;private FeatureHost features;
    private NativePluginHost plugins;private FeatureContext context;private NativeWorkProfileHost host;
    private NativeWorkProfileHost.Page page;private RunControl original;private JSONObject connection,other,definition,task;
    private HttpServer peer;private NativeHarnessGatewayBridge bridge;
    private final AtomicInteger posts=new AtomicInteger();private final AtomicReference<Throwable> peerFailure=new AtomicReference<>();
    private SQLiteDatabase db()throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    @Before public void open()throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);
        settings=new SettingsStore(app.getSharedPreferences("relay-profile-"+java.util.UUID.randomUUID(),0),new SettingsStore.ApiSecrets(){final java.util.Map<String,String> values=new java.util.HashMap<>();public String get(String slot){return values.getOrDefault(slot,"");}public void put(String slot,String key){values.put(slot,key);}});
        context=new FeatureContext(app,settings,new SecretVault(app),event->{});features=new FeatureHost(event->{},tasks,()->false);var registry=new PluginsModule(context);features.register(registry);
        plugins=new NativePluginHost(app,registry,tasks,(binding,control)->tasks.checkExtensionRun(binding.taskId,binding.runId,binding.epoch,control),id->{});
        peer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);peer.createContext("/",exchange->{try{
            posts.incrementAndGet();assertEquals("/v1/chat/completions",exchange.getRequestURI().getPath());assertEquals("Bearer "+KEY,exchange.getRequestHeaders().getFirst("Authorization"));
            JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("profile-relay",input.getString("model"));
            byte[] response=new JSONObject().put("id","relay-fixture").put("object","chat.completion").put("created",1).put("model","profile-relay")
                .put("choices",new JSONArray().put(new JSONObject().put("index",0).put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content","PROFILE_RELAY_ACTUAL_REPLY"))))
                .put("usage",new JSONObject().put("prompt_tokens",1).put("completion_tokens",2).put("total_tokens",3)).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }catch(Throwable failed){peerFailure.set(failed);}finally{exchange.close();}});peer.start();
        new ModelConnectionService(settings,Files.createTempDirectory("profile-relay-models").resolve("catalog"),Clock.systemUTC()).install();
        String endpoint="http://127.0.0.1:"+peer.getAddress().getPort();
        connection=settings.saveApiConnection("",0,"Frozen profile RELAY",new JSONObject().put("baseUrl",endpoint+"/v1").put("provider","openai_chat").put("model","profile-relay").put("apiKey",KEY).put("sendScreenshots",false));
        other=settings.saveApiConnection("",0,"Unselected host RELAY",new JSONObject().put("baseUrl",endpoint+"/wrong/v1").put("provider","openai_chat").put("model","must-not-be-used").put("apiKey","unselected-synthetic-key").put("sendScreenshots",false));
        settings.selectApiConnection(connection.getString("id"),1);settings.selectModelPurpose("RELAY",ModelTargetRef.exact(other.getString("id"),"must-not-be-used"));
        host=new NativeWorkProfileHost(settings,new NativeWorkProfileSources(settings,features,plugins,Set.of(),null));page=host.newOwnerPage();page.setForeground(true);
    }
    private void start(boolean relay)throws Exception {start(relay,null);}
    private void start(boolean relay,JSONObject selectedRelay)throws Exception {
        JSONObject draft=WorkProfile.definition("relay-draft",1,"Exact relay source");JSONObject target=ModelTargetRef.exact(connection.getString("id"),"profile-relay").toJson();
        draft.getJSONObject("modelPolicies").put("MAIN",target).put("SUMMARY",new JSONObject(target.toString()));if(relay)draft.getJSONObject("modelPolicies").put("RELAY",selectedRelay==null?new JSONObject(target.toString()):selectedRelay);
        definition=page.save("",0,"Exact relay source",draft);var review=page.review(definition.getString("profileId"),1,new RunControl());assertTrue(review.view().toString(),review.view().getBoolean("ready"));
        original=new RunControl();var selected=page.select(review,review.digest,original);task=tasks.createCapabilityTask("Original native relay source task","");tasks.configureExecutionScope(task.getString("id"),0,System.currentTimeMillis()+60000,4,2);
        task=tasks.startAttemptWithWorkProfile(task.getString("id"),"capabilities",4,selected,original);
    }
    private NativeWorkProfileHost.RelaySource source()throws Exception{return host.relayForRun(tasks,task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original);}
    @After public void close()throws Exception{if(bridge!=null)bridge.close();if(original!=null)original.cancel();if(peer!=null)peer.stop(0);if(page!=null)page.close();if(plugins!=null)plugins.close();if(features!=null)features.close();if(tasks!=null)db().close();}
    @Test public void originalBridgeActuallyDispatchesTheFrozenProfileRelayNotTheChangedGlobalSelection()throws Exception {
        start(true);var source=source();assertEquals(0,posts.get());assertEquals("profile-relay",source.get().model);page.remove(definition.getString("profileId"),1);
        assertEquals("must-not-be-used",settings.captureRelaySelection().model);assertEquals("profile-relay",source.get().model);
        JSONObject facts=source.view();assertFalse(facts.toString().contains(KEY));assertFalse(facts.getBoolean("configurationApplied"));assertFalse(facts.getBoolean("runtimeVerified"));
        PairedTransport transport=new PairedTransport();String digest=ModelRoutePlan.configurationDigest(source.get());
        bridge=NativeHarnessGatewayBridge.forTask(tasks,original,source,transport,"fixture","fixture-environment-binding","fixture-codex",digest,System.currentTimeMillis()+15000);
        assertTrue(bridge.matchesReview(digest,source.selectionRevision()));bridge.start();assertTrue("Original native bridge must deliver actual model bytes",transport.eof.await(5,TimeUnit.SECONDS));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean complete=false;while(!complete&&System.nanoTime()<end){try(var row=db().rawQuery("SELECT state FROM harness_gateway_claims WHERE lease_id=?",new String[]{bridge.leaseId()})){complete=row.moveToFirst()&&"COMPLETE".equals(row.getString(0));}if(!complete)Thread.sleep(5);}
        assertTrue("Original SQL claim must complete, not a fixture receipt",complete);assertNull(peerFailure.get());assertEquals(1,posts.get());assertTrue(transport.output.toString(StandardCharsets.UTF_8).contains("PROFILE_RELAY_ACTUAL_REPLY"));
        JSONObject usage=tasks.inferenceUsage(task.getString("id"));assertEquals(1,usage.getJSONArray("receipts").length());JSONObject receipt=usage.getJSONArray("receipts").getJSONObject(0);
        assertEquals("RELAY",receipt.getJSONObject("context").getString("purpose"));assertEquals(task.getString("runId"),receipt.getJSONObject("context").getString("runId"));assertTrue(receipt.toString().contains(connection.getString("id")));assertFalse(receipt.toString().contains(KEY));
        JSONObject scope=tasks.get(task.getString("id"),false).getJSONObject("executionScope");assertEquals(1,scope.getInt("modelCallsUsed"));assertEquals(2,scope.getInt("modelCallLimit"));assertEquals(0,scope.getInt("toolsUsed"));
    }
    @Test public void anExplicitExternalRelayGroupKeepsItsLogicalAliasAndExactRevisionInTheOriginalBridge()throws Exception {
        JSONObject spec=new JSONObject().put("allowedAccounts",new JSONObject().put("mode","EXPLICIT").put("refs",new JSONArray().put(connection.getString("id"))))
            .put("candidates",new JSONArray().put(new JSONObject().put("candidateId","profile-native").put("target",ModelTargetRef.exact(connection.getString("id"),"profile-relay").toJson()).put("enabled",true).put("allowedPurposes",new JSONArray().put("RELAY")).put("allowedAudiences",new JSONArray().put("EXTERNAL_RELAY"))))
            .put("attemptPolicy",new JSONObject().put("maxAttempts",1).put("maxRouteSwitches",0));
        JSONObject group=settings.saveModelGroup("",0,"Profile external relay group",spec);String groupId=group.getString("groupId");start(true,ModelTargetRef.group(groupId,1).toJson());var source=source();
        assertEquals(1,source.get().routePlan.groupRevision);assertEquals(groupId,source.get().routePlan.requestedAlias);
        PairedTransport transport=new PairedTransport();bridge=NativeHarnessGatewayBridge.forTask(tasks,original,source,transport,"fixture","fixture-environment-binding","fixture-codex",ModelRoutePlan.configurationDigest(source.get()),System.currentTimeMillis()+15000);
        bridge.start();assertTrue(transport.eof.await(5,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length()<1&&System.nanoTime()<end)Thread.sleep(5);
        JSONObject usage=tasks.inferenceUsage(task.getString("id"));assertEquals(1,usage.getJSONArray("receipts").length());assertTrue("Original logical request must retain the chosen group alias",usage.getJSONArray("logicalRequests").toString().contains(groupId));assertEquals(1,posts.get());assertNull(peerFailure.get());
    }
    @Test public void missingProfileRelayDoesNotMintAReplacementSourceOrChangeTheOldHostSelection()throws Exception {
        start(false);assertNull(source());assertEquals("must-not-be-used",settings.captureRelaySelection().model);assertEquals(0,posts.get());assertEquals(0,tasks.originalHarnessGatewayRecords(task.getString("id"),task.getString("runId")).length());
    }
    @Test public void copiedControlAndPauseResumeCannotReuseAnOpaqueOriginalSourceOrRegisterATarget()throws Exception {
        start(true);var source=source();RunControl copied=new RunControl();copied.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
        assertThrows(SecurityException.class,()->source.requireOriginal(tasks,copied));assertThrows(SecurityException.class,()->host.relayForRun(tasks,task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),new RunControl()));
        JSONObject target=new JSONObject().put("profileId","fixture").put("_profileBinding","fixture-binding").put("serviceBase","http://127.0.0.1:1").put("environmentId","fixture").put("environmentBinding","fixture-binding").put("bindingId","fixture-codex").put("profileDigest","a".repeat(64)).put("taskId",task.getString("id"));
        try(var proxy=new ProxyModule(context)){assertThrows(SecurityException.class,()->proxy.prepareHarnessGateway(tasks,copied,null,target,System.currentTimeMillis()+10000,null,null,source));}
        original.pause();original.resume();assertThrows(SecurityException.class,source::get);assertEquals("profile-relay",source().get().model);assertEquals(0,posts.get());
    }
    @Test public void revokedExactSourceNeverFallsBackToTheUnrelatedHostRelay()throws Exception {
        start(true);var source=source();settings.setApiConnectionEnabled(connection.getString("id"),1,false);
        assertThrows(Exception.class,source::get);assertEquals("must-not-be-used",settings.captureRelaySelection().model);assertEquals(0,posts.get());assertEquals(0,tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length());
    }
    @Test public void aNewOriginalProfileEpochCannotReviveTheOldRelaySource()throws Exception {
        start(true);var old=source();tasks.endAttempt(task.getString("id"),task.getString("runId"),"awaiting_user","Native fixture safe checkpoint",new JSONObject());
        var review=page.reviewForTask(tasks,task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),new RunControl());RunControl next=new RunControl();var selected=page.select(review,review.digest,next);
        task=tasks.startAttemptWithWorkProfile(task.getString("id"),"capabilities",4,selected,next);assertThrows(Exception.class,old::get);original=next;assertEquals("profile-relay",source().get().model);assertEquals(1,tasks.list().length());assertEquals(0,posts.get());
    }
    /** The existing bridge owns polling/claims/protocol/model dispatch. This fixture only transports
     * one bounded body and ACKs exact frame identities; it is not a Companion/driver acceptance test. */
    private static final class PairedTransport implements NativeHarnessGatewayBridge.Transport {
        JSONObject lease,request;boolean delivered,closed;final byte[] body;final CountDownLatch eof=new CountDownLatch(1),stop=new CountDownLatch(1);final java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        PairedTransport()throws Exception {body=new JSONObject().put("model","profile-relay").put("stream",false).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","Original authorized relay request"))).toString().getBytes(StandardCharsets.UTF_8);}
        @Override public JSONObject request(String path,JSONObject input)throws Exception {
            if(path.equals("/v1/gateway/leases")){lease=new JSONObject(input.toString());lease.remove("accessToken");lease.remove("protocols");lease.put("state","ACTIVE").put("gatewayBridge",new JSONObject().put("version",1));
                request=new JSONObject().put("leaseId",lease.getString("leaseId")).put("generation",lease.getString("generation")).put("clientBindingId",lease.getString("clientBindingId")).put("bridgeRequestId","frozen-profile-request").put("requestDigest",dev.pocketengine.features.local.project.ProjectSources.sha256(body)).put("bodyBytes",body.length).put("path","/v1/chat/completions").put("clientAlive",true).put("state","WAITING");return new JSONObject(lease.toString());}
            if(path.endsWith("/cancel")){closed=true;stop.countDown();return new JSONObject(lease.toString()).put("state","REVOKED");}
            if(path.contains("/requests?generation=")){if(!delivered){delivered=true;return new JSONObject().put("leaseId",lease.getString("leaseId")).put("generation",lease.getString("generation")).put("requests",new JSONArray().put(new JSONObject(request.toString())));}stop.await(10,TimeUnit.SECONDS);return new JSONObject().put("leaseId",lease.getString("leaseId")).put("generation",lease.getString("generation")).put("requests",new JSONArray());}
            if(path.endsWith("/claim")){request.put("claimId",input.getString("claimId")).put("state","CLAIMED");return new JSONObject(request.toString());}
            if(path.contains("/body?"))return new JSONObject(request.toString()).put("offset",0).put("body",Base64.getEncoder().encodeToString(body));
            if(path.endsWith("/frames")){output.write(Base64.getDecoder().decode(input.getString("payload")));if(input.getBoolean("eof"))eof.countDown();return new JSONObject().put("acceptedThrough",new JSONObject().put("eventSeq",input.getLong("eventSeq")).put("offset",input.getInt("offset")));}
            if(path.contains("/v1/gateway/requests/"))return new JSONObject(request.toString());
            if(path.startsWith("/v1/gateway/leases/"))return new JSONObject(lease.toString()).put("state",closed?"REVOKED":"ACTIVE");
            throw new AssertionError("Unexpected paired transport path: "+path);
        }
    }
}
