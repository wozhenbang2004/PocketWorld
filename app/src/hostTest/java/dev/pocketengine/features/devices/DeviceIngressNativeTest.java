package dev.pocketengine.features.devices;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.models.InferenceAudiencePolicy;
import dev.pocketengine.runtime.models.NativeInferenceScope;
import dev.pocketengine.store.SettingsStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original SQLite and real loopback mTLS identity; PhonePort is an EXPLICIT host fixture.
 * This is NOT proof of Android GUI, permission, background display, or cross-network device success. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class DeviceIngressNativeTest {
    private TaskStore tasks;
    private SQLiteDatabase sql;
    private DeviceIdentity.AuthenticatedPeer caller;
    private Material callerMaterial,targetMaterial;
    private String target;
    private PairingCoordinator pairing;
    private JSONObject binding, nativeGrant;
    private long deadline;
    private final AtomicInteger starts = new AtomicInteger();
    @Before public void open() throws Exception { open(null); }
    void open(TaskStore existing) throws Exception {
        var context = RuntimeEnvironment.getApplication(); if(existing==null)context.deleteDatabase("pocket-world.db");
        context.getSharedPreferences("private_settings", 0).edit().clear().commit();
        tasks = existing==null?new TaskStore(context):existing; var field = TaskStore.class.getDeclaredField("db"); field.setAccessible(true); sql = (SQLiteDatabase) field.get(tasks);
        // Explicitly exercise the exact static schema adapters. The root-owned v8 migration is a separate gate.
        TaskStoreDeviceTasks.createSchema(sql); TaskStoreDeviceTrust.createSchema(sql);
        Peers peers = tls(); caller = peers.caller;callerMaterial=peers.source;targetMaterial=peers.local; target = peers.target; deadline = System.currentTimeMillis() + 300_000;
        binding = new JSONObject().put("screenMode", "main_screen").put("packageNames", new JSONArray().put("test.pocket.app"));
        nativeGrant = new JSONObject().put("grantId", "grant-test").put("peerDeviceId", caller.deviceId()).put("localDeviceId", target)
                .put("authorizationEpoch", 1).put("issuedAt", System.currentTimeMillis() - 1000).put("expiresAt", deadline)
                .put("capabilities", new JSONArray().put("device.describe").put("tool.invoke").put("operation.get").put("task.get").put("task.events").put("task.cancel").put("task.acquireControl"))
                .put("toolNames", new JSONArray().put("phone_observe").put("phone_click_node").put("phone_type").put("phone_list_apps"))
                .put("resources", new JSONArray().put(binding)).put("allowedOrigins", new JSONArray().put(caller.deviceId()))
                .put("allowDelegation", false).put("maxDelegationDepth", 0).put("toolBudget", 20).put("modelBudget", 0);
        ContentValues peer = new ContentValues(); peer.put("device_id", caller.deviceId()); peer.put("certificate_der", caller.certificateDer()); peer.put("fingerprint", caller.certificateFingerprint()); peer.put("instance_id", "caller-fixture"); peer.put("authorization_epoch", 1); peer.put("revoked", 0); peer.put("updated_at", System.currentTimeMillis()); sql.insertOrThrow("device_peers", null, peer);
        ContentValues grant = new ContentValues(); grant.put("grant_id", "grant-test"); grant.put("peer_device_id", caller.deviceId()); grant.put("local_device_id", target); grant.put("authorization_epoch", 1); grant.put("expires_at", deadline); grant.put("body_json", nativeGrant.toString()); grant.put("revoked", 0); sql.insertOrThrow("device_grants", null, grant);
        pairing = new PairingCoordinator(new TaskStoreDeviceTrust(sql), target, "receiver-fixture");
    }
    @After public void close() { if (sql != null && sql.isOpen()) sql.close(); }
    private DeviceTaskCoordinator coordinator(TaskStore store) {
        return new DeviceTaskCoordinator(store, new DeviceTaskCoordinator.NativeHost() {
            public void acceptTools(String id, DeviceExecutionAdmission admission) { starts.incrementAndGet(); }
            public void acceptGoal(String id, DeviceExecutionAdmission admission) { fail("tools fixture must not start an Agent/model"); }
            public void controlRequested(String id, String action) { }
        }, target, "receiver-fixture");
    }
    private JSONObject wire(String operation, String name, JSONObject args, JSONObject ref) throws Exception {
        JSONObject envelope = new JSONObject().put("protocolVersion", "1.0").put("protocolProfile",DeviceProtocol.PROFILE).put("messageType", "ToolInvocation").put("method", "tool.invoke")
                .put("requestId", UUID.randomUUID().toString()).put("operationId", operation).put("rootTaskId", "root-fixture").put("parentTaskId", "parent-fixture").put("localTaskId", "phone-session")
                .put("originDeviceId", caller.deviceId()).put("callerDeviceId", caller.deviceId()).put("targetDeviceId", target)
                .put("runId", "source-run").put("attempt", 1).put("executorEpoch", 1).put("instanceId", "receiver-fixture")
                .put("grantId", "grant-test").put("authorizationEpoch", 1).put("deadline", deadline).put("capabilityVersion", "1.0").put("resourceBinding", binding)
                .put("budget", new JSONObject().put("allocationId", DeviceProtocol.allocationId("parent-fixture", target, binding, "tool_session", null)).put("toolCalls", 20).put("modelCalls", 0)).put("visitedDevices", new JSONArray().put(caller.deviceId())).put("maxDelegationDepth", 1).put("delegationChain", new JSONArray())
                .put("arguments", new JSONObject().put("toolName", name).put("effect", DeviceToolsRuntime.effect(name)).put("arguments", args));
        if (ref != null) envelope.put("observationRef", ref); envelope.put("argumentsDigest", DeviceProtocol.argumentsDigest(envelope)); return envelope;
    }
    private DeviceExecutionAdmission admission(JSONObject wire) throws Exception {
        DeviceTaskEnvelope envelope = DeviceProtocol.validateRequest(DeviceProtocol.canonicalBytes(wire), caller, target, System.currentTimeMillis());
        return DeviceExecutionAdmission.issue(pairing.checkPermission(caller, envelope, System.currentTimeMillis()), (request, kind) -> { });
    }
    private static void refused(Throwing action) throws Exception { try { action.run(); fail("must refuse before effect"); } catch (SecurityException expected) { } }
    private static void refusedReply(Throwing action)throws Exception{try{action.run();fail("malformed or wrongly bound response must refuse");}catch(SecurityException|DeviceProtocol.ProtocolException expected){}}
    private interface Throwing { void run() throws Exception; }
    private long count(String table) { try (Cursor rows = sql.rawQuery("SELECT COUNT(*) FROM " + table, null)) { rows.moveToFirst(); return rows.getLong(0); } }
    @Test public void acceptedCommitsBeforeHostPickupAndChangedDigestCannotReplaceIt() throws Exception {
        DeviceExecutionAdmission first = admission(wire("observe-a", "phone_observe", new JSONObject(), null));
        JSONObject accepted = coordinator(tasks).submit(first); assertTrue(accepted.getBoolean("accepted")); assertFalse(accepted.getBoolean("knownOperation"));
        assertEquals(1, starts.get()); assertEquals(1, count("device_inbound")); assertEquals(1, count("tasks"));
        JSONObject persisted = tasks.get(accepted.getString("localTaskId"), false); assertEquals("paired_device", persisted.getJSONObject("origin").getString("kind"));
        assertNotEquals("owner", persisted.getString("actorId")); assertNotEquals("private", persisted.getString("spaceId"));
        assertFalse(persisted.has("executionScope")); assertTrue(persisted.has("deviceExecutionScope"));
        assertTrue(coordinator(tasks).submit(first).getBoolean("knownOperation")); assertEquals(1, starts.get());
        refused(() -> coordinator(tasks).submit(admission(wire("observe-a", "phone_observe", new JSONObject().put("includeImage", true), null))));
        refused(() -> tasks.startAttempt(persisted.getString("id"), "main_screen", 20)); assertEquals(0, count("runs"));
    }
    private record Running(DeviceToolsRuntime runtime, RunControl control, FakePhone phone, JSONObject accepted) { }
    private Running running() throws Exception {
        DeviceExecutionAdmission first = admission(wire("observe-a", "phone_observe", new JSONObject(), null)); JSONObject accepted = tasks.deviceTasks().accept(first);
        RunControl original = new RunControl(); tasks.deviceTasks().startAttempt(accepted.getString("localTaskId"), first, original); tasks.status(accepted.getString("localTaskId"), "running", "host fixture");
        FakePhone physical = new FakePhone(); DeviceToolsRuntime runtime = new DeviceToolsRuntime(target, first, original, physical, tasks.deviceTasks(), (mode, control) -> { });
        return new Running(runtime, original, physical, accepted);
    }
    @Test public void zeroModelToolsUseOriginalOperationAndDuplicateHasNoSecondEffect() throws Exception {
        Running active = running(); JSONObject observed = active.runtime.invoke(admission(wire("observe-a", "phone_observe", new JSONObject(), null)));
        JSONObject frame = observed.getJSONObject("observationRef"); assertEquals(target, frame.getString("deviceId")); assertEquals("native-session", frame.getString("sessionId")); assertEquals(0, frame.getInt("displayId")); assertTrue(frame.has("windowDigest")); assertEquals(1, frame.getInt("executorEpoch"));
        DeviceExecutionAdmission click = admission(wire("click-a", "phone_click_node", new JSONObject().put("nodeId", "n0"), frame)); tasks.deviceTasks().accept(click);
        JSONObject result = active.runtime.invoke(click); assertTrue(result.getBoolean("accepted")); assertEquals(0, result.getInt("modelRequests")); assertEquals(1, active.phone.actions.get());
        assertTrue(coordinator(tasks).submit(click).getBoolean("knownOperation")); assertEquals(1, active.phone.actions.get());
        try (Cursor calls = sql.rawQuery("SELECT COUNT(*) FROM task_events WHERE event_type='inference_receipt'", null)) { calls.moveToFirst(); assertEquals(0, calls.getInt(0)); }
        JSONObject receipt = tasks.deviceTasks().snapshot(caller.deviceId(), "click-a"); assertEquals("receipt", receipt.getString("operationState")); assertEquals(2, count("operations"));
    }
    @Test public void newObservationExpiresOldDisplayWindowEpochRefBeforeAnyAction() throws Exception {
        Running active = running(); JSONObject first = active.runtime.invoke(admission(wire("observe-a", "phone_observe", new JSONObject(), null))).getJSONObject("observationRef");
        DeviceExecutionAdmission again = admission(wire("observe-b", "phone_observe", new JSONObject(), null)); tasks.deviceTasks().accept(again); active.runtime.invoke(again);
        DeviceExecutionAdmission stale = admission(wire("stale-click", "phone_click_node", new JSONObject().put("nodeId", "n0"), first)); tasks.deviceTasks().accept(stale);
        refused(() -> active.runtime.invoke(stale)); tasks.deviceTasks().rejectPending(stale, "stale_observation"); assertEquals(0, active.phone.actions.get());
        JSONObject changed = new JSONObject(first.toString()).put("displayId", 9); DeviceObservationRef nativeRef = DeviceObservationRef.capture(target, active.control, active.phone.observe(false, active.control), System.currentTimeMillis());
        refused(() -> nativeRef.consume(changed, active.control, System.currentTimeMillis()));
    }
    @Test public void nativeRevocationAndForgedWireCannotAcquirePhoneAuthority() throws Exception {
        long tasksBefore = count("tasks"); JSONObject forged = wire("bad-owner", "phone_observe", new JSONObject(), null).put("actorId", "owner");
        try { admission(forged); fail(); } catch (DeviceProtocol.ProtocolException expected) { }
        assertEquals(tasksBefore, count("tasks"));
        Running active = running(); JSONObject frame = active.runtime.invoke(admission(wire("observe-a", "phone_observe", new JSONObject(), null))).getJSONObject("observationRef");
        DeviceExecutionAdmission click = admission(wire("click-after-revoke", "phone_click_node", new JSONObject().put("nodeId", "n0"), frame)); tasks.deviceTasks().accept(click);
        new TaskStoreDeviceTrust(sql).revoke(caller.deviceId(), System.currentTimeMillis()); refused(() -> active.runtime.invoke(click)); assertEquals(0, active.phone.actions.get());
    }
    @Test public void actionAfterDispatchWithoutReceiptStaysUnknownAcrossOriginalSqlReopen() throws Exception {
        Running active = running(); JSONObject frame = active.runtime.invoke(admission(wire("observe-a", "phone_observe", new JSONObject(), null))).getJSONObject("observationRef");
        DeviceExecutionAdmission click = admission(wire("unknown-click", "phone_click_node", new JSONObject().put("nodeId", "n0"), frame)); tasks.deviceTasks().accept(click); active.phone.failAfterAction = true;
        JSONObject unknown = active.runtime.invoke(click); assertTrue(unknown.isNull("accepted")); assertEquals(1, active.phone.actions.get());
        assertEquals("unknown", tasks.deviceTasks().snapshot(caller.deviceId(), "unknown-click").getString("operationState"));
        TaskStore reopened = new TaskStore(RuntimeEnvironment.getApplication()); JSONObject same = coordinator(reopened).submit(click);
        assertTrue(same.getBoolean("knownOperation")); assertEquals("needs_reconcile", same.getString("status")); assertEquals(1, active.phone.actions.get()); assertEquals(2, count("operations"));
    }
    @Test public void concurrentChildrenCannotCopyLastParentBudgetAndUnknownDoesNotRefund() throws Exception {
        JSONObject parent = tasks.createCapabilityTask("original private parent budget", ""); String id = parent.getString("id"); tasks.configureExecutionScope(id, 0, deadline, 6, 6);
        parent = tasks.startAttempt(id, "capabilities", 6); String run = parent.getString("runId"); int epoch = parent.getInt("coordinatorEpoch"); RunControl original = new RunControl(); original.bindHostRun(id, run, epoch);
        tasks.authorizeTools(id, run, new JSONArray().put("device_delegate_goal")); tasks.status(id, "running", "native parent fixture"); var journal = tasks.journal(id, run, epoch, original);
        JSONObject args = new JSONObject().put("targetDeviceId", caller.deviceId()).put("goal", "child").put("toolBudget", 4).put("modelBudget", 4);
        String a = journal.beginOperation("device_delegate_goal", args); journal.finishOperation(a, new JSONObject().put("ok", true)); String b = journal.beginOperation("device_delegate_goal", args); journal.finishOperation(b, new JSONObject().put("ok", true));
        sql.execSQL("UPDATE operations SET state='prepared' WHERE operation_id IN (?,?)", new Object[]{a,b});
        var executor = Executors.newFixedThreadPool(2); AtomicInteger accepted = new AtomicInteger();
        try {
            var futures = executor.invokeAll(List.of(() -> reserve(id, run, epoch, original, a, accepted), () -> reserve(id, run, epoch, original, b, accepted)));
            for (var future : futures) future.get();
        } finally { executor.shutdownNow(); }
        assertEquals(1, accepted.get()); assertEquals(4, tasks.deviceTasks().heldBudget(id, run, false));
        String reserved; try (Cursor row = sql.rawQuery("SELECT parent_operation_id FROM device_child_budget", null)) { assertTrue(row.moveToFirst()); reserved = row.getString(0); }
        tasks.deviceTasks().childPending(id, reserved, "unknown"); tasks.deviceTasks().childPending(id, reserved, "cancel_requested"); assertEquals(4, tasks.deviceTasks().heldBudget(id, run, false));
        JSONObject duplicate = tasks.deviceTasks().reserveChild(id, run, epoch, original, reserved, childDraft(id, reserved)); assertTrue(duplicate.getBoolean("knownReservation")); assertEquals(1, count("device_child_budget"));
        refused(() -> tasks.configureExecutionScope(id, 1, deadline, 5, 6));
    }
    private Boolean reserve(String id, String run, int epoch, RunControl original, String operation, AtomicInteger accepted) throws Exception {
        try { tasks.deviceTasks().reserveChild(id, run, epoch, original, operation, childDraft(id, operation)); accepted.incrementAndGet(); return true; } catch (SecurityException noBudget) { return false; }
    }
    private JSONObject childDraft(String parent, String operation) throws Exception {
        return new JSONObject().put("method", "task.submitGoal").put("operationId", operation).put("parentTaskId", parent).put("rootTaskId", parent)
                .put("originDeviceId", target).put("callerDeviceId", target).put("targetDeviceId", caller.deviceId()).put("grantId", "child-grant").put("authorizationEpoch", 1)
                .put("resourceBinding", binding).put("deadline", deadline)
                .put("budget", new JSONObject().put("allocationId", DeviceProtocol.allocationId(parent, caller.deviceId(), binding, "goal", operation)).put("toolCalls", 4).put("modelCalls", 4));
    }
    @Test public void selectedContextRejectsCredentialsPathsAndAmbientMemory() throws Exception {
        JSONObject context = new JSONObject().put("format", "pocketworld.device-context.v1").put("originDeviceId", caller.deviceId()).put("snapshotId", "context-one").put("revision", 1).put("summary", "selected summary").put("artifacts", new JSONArray()).put("retention", "task_only");
        assertFalse(DeviceContextSnapshot.validate(context, caller.deviceId()).getBoolean("authorityTransferred"));
        refused(() -> DeviceContextSnapshot.validate(new JSONObject(context.toString()).put("apiKey", "test-only-never-model"), caller.deviceId()));
        refused(() -> DeviceContextSnapshot.validate(new JSONObject(context.toString()).put("memory", new JSONArray()), caller.deviceId()));
    }
    private JSONObject goalWire(String operation,int modelCalls)throws Exception{
        binding=new JSONObject().put("screenMode","capabilities");
        nativeGrant.put("capabilities",new JSONArray().put("task.submitGoal").put("operation.get").put("task.respond").put("task.acquireControl"))
                .put("toolNames",new JSONArray()).put("resources",new JSONArray().put(binding)).put("modelBudget",20);
        ContentValues update=new ContentValues();update.put("body_json",nativeGrant.toString());sql.update("device_grants",update,"grant_id=?",new String[]{"grant-test"});
        JSONObject context=new JSONObject().put("format","pocketworld.device-context.v1").put("originDeviceId",caller.deviceId()).put("snapshotId","selected-context").put("revision",1).put("summary","SELECTED_DEVICE_CONTEXT").put("artifacts",new JSONArray()).put("retention","task_only");
        JSONObject value=wire(operation,"phone_observe",new JSONObject(),null).put("messageType","GoalSubmission").put("method","task.submitGoal")
                .put("arguments",new JSONObject().put("goal","Summarize only this selected delegated context").put("executionMode","agent").put("contextSnapshot",context).put("modelSource",new JSONObject().put("kind","local")))
                .put("budget",new JSONObject().put("allocationId",DeviceProtocol.allocationId("parent-fixture",target,binding,"goal",operation)).put("toolCalls",4).put("modelCalls",modelCalls));
        return value.put("argumentsDigest",DeviceProtocol.argumentsDigest(value));
    }
    private static CapabilityPort noNativeTools(){return new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return true;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("No fixture effect tool is advertised");}};}
    private JSONObject goalWithLocalAndForwardShares(String operation)throws Exception{
        JSONObject value=goalWire(operation,4),resource=value.getJSONObject("resourceBinding");String future=DeviceIdentity.id(certificate().cert),source=value.getJSONObject("budget").getString("allocationId");
        JSONObject forwarding=new JSONObject().put("targetResources",new JSONArray().put(new JSONObject().put("targetDeviceId",future).put("resourceBinding",resource))).put("capabilities",new JSONArray().put("task.submitGoal")).put("toolNames",new JSONArray()).put("toolBudget",0).put("modelBudget",2).put("maxDelegationDepth",2);nativeGrant.put("forwardingScope",forwarding);ContentValues revised=new ContentValues();revised.put("body_json",nativeGrant.toString());sql.update("device_grants",revised,"grant_id=?",new String[]{"grant-test"});
        JSONObject statement=new JSONObject().put("protocolVersion","1.0").put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DelegationProof").put("issuerDeviceId",caller.deviceId()).put("delegateDeviceId",target).put("targetResources",forwarding.getJSONArray("targetResources")).put("rootTaskId",value.getString("rootTaskId")).put("parentTaskId",DeviceProtocol.receiverTaskId(value)).put("grantId",value.getString("grantId")).put("authorizationEpoch",1).put("capabilities",forwarding.getJSONArray("capabilities")).put("toolNames",forwarding.getJSONArray("toolNames")).put("toolBudget",4).put("modelBudget",4).put("deadline",deadline).put("remainingDepth",0).put("previousProofDigest","").put("sourceAllocationId",source).put("targetShares",new JSONArray().put(DelegationGrant.targetShare(caller.deviceId(),source,target,resource,4,2)).put(DelegationGrant.targetShare(caller.deviceId(),source,future,resource,0,2)));
        value.put("maxDelegationDepth",2);value.getJSONObject("arguments").put("delegationAuthorization",signedProof(callerMaterial,statement));value.put("argumentsDigest",DeviceProtocol.argumentsDigest(value));return value;
    }
    private static final class NativeSecrets implements SettingsStore.ApiSecrets {
        final java.util.Map<String,String> values=new java.util.HashMap<>();public String get(String key){return values.getOrDefault(key,"");}public void put(String key,String value){values.put(key,value);}
    }
    @Test public void originalRunnerGoalNeedsNativeBModelChoiceAndApprovalResumesSameRunWithoutPrivateContext()throws Exception{
        tasks.createCapabilityTask("AMBIENT_PRIVATE_TASK_MUST_NOT_LEAK","");
        AtomicInteger requests=new AtomicInteger();java.util.List<String> bodies=new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{try{requests.incrementAndGet();bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] response="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Controlled local model fixture response\"}}]}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}finally{exchange.close();}});http.start();
        try{
            var constructor=SettingsStore.class.getDeclaredConstructor(android.content.SharedPreferences.class,SettingsStore.ApiSecrets.class);constructor.setAccessible(true);SettingsStore settings=constructor.newInstance(RuntimeEnvironment.getApplication().getSharedPreferences("private_settings",0),new NativeSecrets());
            var models=new dev.pocketengine.runtime.models.ModelConnectionService(settings,Files.createTempDirectory("device-owner-model-fixture").resolve("catalog"),java.time.Clock.systemUTC());models.install();models.connections();
            JSONObject profile=settings.saveApiConnection("",0,"B owner fixture API",new JSONObject().put("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).put("model","device-fixture-model").put("apiKey","not-a-real-fixture-key"));ModelConfig captured=settings.selectApiConnection(profile.getString("id"),1);
            DeviceExecutionAdmission admission=admission(goalWithLocalAndForwardShares("goal-model-choice"));String id=tasks.deviceTasks().accept(admission).getString("localTaskId");
            refused(()->tasks.deviceTasks().startAttempt(id,admission,new RunControl()));assertEquals(0,count("runs"));assertEquals(0,requests.get());
            JSONObject pending=tasks.get(id,false);var review=tasks.deviceTasks().reviewGoalModel(id,pending.getLong("deviceStateRevision"),admission,captured);assertFalse(review.view().toString().contains("not-a-real-fixture-key"));
            var approved=tasks.deviceTasks().confirmGoalModel(review);tasks.deviceTasks().persistGoalModelPolicy(approved);refused(()->tasks.deviceTasks().confirmGoalModel(review));
            RunControl original=new RunControl();JSONObject task=tasks.deviceTasks().startGoalAttempt(id,admission,original,approved);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");String grant=sqlString("SELECT grant_json FROM runs WHERE run_id=?",run);
            original.setAuthorityCheck(()->approved.checkCurrent(original));NativeInferenceScope scope=NativeInferenceScope.pairedDeviceGoal(approved,original);assertEquals("PAIRED_DEVICE_GOAL",scope.publicJson().getString("entry"));assertNotEquals("owner",scope.publicJson().getString("actorId"));
            ModelConfig account=captured.withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId","fixture-account").put("accountVersion",1).put("modelId","fixture-account-model")));
            try{InferenceAudiencePolicy.require(account,scope,original);fail("PairGrant cannot mint owner App subscription identity");}catch(InferenceAudiencePolicy.Rejected expected){assertEquals("CHATGPT_DEVICE_GOAL_AUDIENCE_UNPROVEN",expected.code);}
            var check=(DeviceGoalCapabilityPort.CurrentModel)()->{dev.pocketengine.runtime.models.InferenceRouteContinuity.requireCurrent(captured,settings.currentForCaptured(captured),original);InferenceAudiencePolicy.require(captured,scope,original);};
            var nativeSession=new DeviceGoalSession(id,admission,original,approved);nativeSession.runnerStarted();var first=new AgentRunner(null,new DeviceGoalCapabilityPort(noNativeTools(),admission,original,check)).run(id,task.getString("goal"),captured,original,event->{try{tasks.event(id,run,epoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}},tasks.journal(id,run,epoch,original));
            nativeSession.runnerFinished(first);assertEquals(first.summary,"needs_confirmation",first.status);assertEquals(1,requests.get());assertTrue(bodies.get(0).contains("SELECTED_DEVICE_CONTEXT"));assertFalse(bodies.get(0).contains("AMBIENT_PRIVATE_TASK_MUST_NOT_LEAK"));
            tasks.status(id,"awaiting_user","exact owner approval wait");original.pause();JSONObject approval=tasks.requireCapabilityApproval(id,run,"fixture_approval_only",new JSONObject().put("exact",1),new JSONObject().put("fixture",true));
            refused(()->tasks.deviceTasks().resumeGoal(id,admission,original));tasks.decideCapabilityApproval(id,approval.getString("approvalId"),true);tasks.deviceTasks().resumeGoal(id,admission,original);
            assertEquals(run,tasks.get(id,false).getString("runId"));assertEquals(epoch,tasks.get(id,false).getInt("coordinatorEpoch"));assertEquals(grant,sqlString("SELECT grant_json FROM runs WHERE run_id=?",run));
            nativeSession.runnerStarted();var second=new AgentRunner(null,new DeviceGoalCapabilityPort(noNativeTools(),admission,original,check)).run(id,task.getString("goal"),captured,original,event->{try{tasks.event(id,run,epoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}},tasks.journal(id,run,epoch,original));assertEquals(second.summary,"needs_confirmation",second.status);assertEquals(2,requests.get());
            assertEquals(2,tasks.get(id,false).getJSONObject("deviceExecutionScope").getInt("modelCallsUsed"));assertEquals(2,tasks.get(id,false).getJSONObject("deviceExecutionScope").getInt("localModelCallsUsed"));assertEquals(4,tasks.get(id,false).getJSONObject("deviceExecutionScope").getInt("modelCallLimit"));tasks.status(id,"awaiting_user","local model share spent; downstream share cannot be reused");original.pause();refused(()->tasks.deviceTasks().resumeGoal(id,admission,original));assertEquals(2,requests.get());assertEquals(1,count("runs"));
            var actualFinished=nativeSession.runnerFinished(second);tasks.deviceTasks().recordGoalIdle(actualFinished);assertEquals("needs_confirmation",tasks.get(id,false).getString("status"));retainedChildTerminalGuardFixture(id,run,epoch);var resultReview=tasks.deviceTasks().reviewGoalResult(id,run,epoch);assertEquals(second.summary,resultReview.view().getString("summary"));assertTrue(resultReview.view().getBoolean("historicalReviewOnly"));long beforeOps=count("operations"),beforeRuns=count("runs");JSONObject closed=tasks.deviceTasks().confirmGoalResult(resultReview,true);assertEquals("completed",closed.getString("status"));assertEquals(run,closed.getString("runId"));assertFalse(closed.getBoolean("newEffectsAllowed"));assertEquals(beforeOps,count("operations"));assertEquals(beforeRuns,count("runs"));assertEquals(2,requests.get());assertEquals(2,tasks.deviceTasks().heldBudget(id,run,true));assertEquals(2,tasks.get(id,false).getJSONObject("deviceExecutionScope").getInt("modelCallsUsed"));refused(()->tasks.deviceTasks().confirmGoalResult(resultReview,true));original.cancel();tasks.deviceTasks().release(run,original);
            DeviceExecutionAdmission later=admission(goalWire("goal-model-policy-next",1));String next=tasks.deviceTasks().accept(later).getString("localTaskId");JSONObject policy=tasks.deviceTasks().goalModelPolicy(later);assertNotNull(policy);assertFalse(policy.toString().contains("not-a-real-fixture-key"));
            var inherited=tasks.deviceTasks().authorizeGoalModelPolicy(next,later,settings.currentForCaptured(settings.apiMetadataForPricing(profile.getString("id"),captured.model)));RunControl nextOriginal=new RunControl();tasks.deviceTasks().startGoalAttempt(next,later,nextOriginal,inherited);inherited.checkCurrent(nextOriginal);
            tasks.deviceTasks().revokeGoalModelPolicy(later);refused(()->inherited.checkCurrent(nextOriginal));assertEquals("policy revocation makes no new model request",2,requests.get());
        }finally{http.stop(0);}
    }
    private String sqlString(String query,String... args){try(Cursor row=sql.rawQuery(query,args)){assertTrue(row.moveToFirst());return row.getString(0);}}
    @Test public void persistentBModelPolicyCannotBeMintedFromJsonAndRevocationStopsOriginalProof()throws Exception{
        DeviceExecutionAdmission first=admission(goalWire("goal-policy-a",2));String id=tasks.deviceTasks().accept(first).getString("localTaskId");
        ModelConfig model=new ModelConfig("http://127.0.0.1:43210","fixture-never-sent-key","policy-fixture",false,8,10);
        assertNull(tasks.deviceTasks().goalModelPolicy(first));refused(()->tasks.deviceTasks().authorizeGoalModelPolicy(id,first,model));
        var review=tasks.deviceTasks().reviewGoalModel(id,tasks.get(id,false).getLong("deviceStateRevision"),first,model);var proof=tasks.deviceTasks().confirmGoalModel(review);
        refused(()->tasks.deviceTasks().persistGoalModelPolicy(proof)); // no native profile identity; key text is never persisted.
        RunControl original=new RunControl();tasks.deviceTasks().startGoalAttempt(id,first,original,proof);proof.checkCurrent(original);
        TaskStore other=new TaskStore(RuntimeEnvironment.getApplication());refused(()->other.deviceTasks().startGoalAttempt(id,first,new RunControl(),proof));
        original.cancel();refused(()->proof.checkCurrent(original));assertFalse(sqlString("SELECT value FROM meta WHERE key='legacyImported'").contains("fixture-never-sent-key"));
    }
    @Test public void receiverDisableFencesNewAcceptButAlreadyAcceptedToolsKeepOriginalNativeControl()throws Exception{
        java.util.concurrent.atomic.AtomicBoolean enabled=new java.util.concurrent.atomic.AtomicBoolean(true),listening=new java.util.concurrent.atomic.AtomicBoolean();
        var resource=new DeviceReceiverServiceResource(new DeviceReceiverServiceResource.Backend(){public void start(){listening.set(true);}public boolean isListening(){return listening.get();}public void close(){listening.set(false);}},new DeviceReceiverServiceResource.OwnerPolicy(){public void requireEnabledCurrent(){if(!enabled.get())throw new SecurityException("local receiver disabled");}public void disableByOwner(){enabled.set(false);}});
        resource.start();Running active=running();JSONObject frame=active.runtime.invoke(admission(wire("observe-a","phone_observe",new JSONObject(),null))).getJSONObject("observationRef");
        DeviceExecutionAdmission accepted=admission(wire("accepted-before-receiver-disable","phone_click_node",new JSONObject().put("nodeId","n0"),frame));coordinator(tasks).submit(accepted,resource::acceptNewRequest);
        resource.disableByOwner();resource.close();assertFalse(active.control.isCancelled());assertFalse(active.control.isPaused());assertFalse(tasks.get(active.accepted.getString("localTaskId"),false).optBoolean("cancelRequested"));
        assertTrue(active.runtime.invoke(accepted).getBoolean("accepted"));assertEquals(1,active.phone.actions.get());assertEquals(2,count("device_inbound"));
        refused(()->coordinator(tasks).submit(admission(wire("new-after-receiver-disable","phone_observe",new JSONObject(),null)),resource::acceptNewRequest));assertEquals(2,count("device_inbound"));assertEquals(1,active.phone.actions.get());
    }
    @Test public void grantLifetimeRemainingDoesNotMistakeLimitOrCancelledUnknownForAvailable()throws Exception{
        Running active=running();JSONObject frame=active.runtime.invoke(admission(wire("observe-a","phone_observe",new JSONObject(),null))).getJSONObject("observationRef");
        JSONObject budget=tasks.deviceTasks().grantBudgetStatus(caller.deviceId(),"grant-test");assertEquals(20,budget.getInt("toolLimit"));assertEquals(1,budget.getInt("toolsActuallyUsed"));assertEquals(20,budget.getInt("toolsReservedOrUsed"));assertEquals(0,budget.getInt("toolsAvailableForNewAllocations"));
        DeviceExecutionAdmission click=admission(wire("grant-unknown-effect","phone_click_node",new JSONObject().put("nodeId","n0"),frame));tasks.deviceTasks().accept(click);active.phone.failAfterAction=true;active.runtime.invoke(click);active.control.cancel();String id=active.accepted.getString("localTaskId"),run=active.control.hostRunIdentity().runId();tasks.requestCancel(id,run);tasks.endAttempt(id,run,"cancelled","cancel not an effect rollback",new JSONObject());tasks.deviceTasks().release(run,active.control);
        budget=tasks.deviceTasks().grantBudgetStatus(caller.deviceId(),"grant-test");assertEquals(2,budget.getInt("toolsActuallyUsed"));assertEquals(20,budget.getInt("toolsReservedOrUsed"));assertEquals(0,budget.getInt("toolsAvailableForNewAllocations"));assertEquals(1,budget.getInt("unknownOrUnconfirmedAllocations"));assertFalse(budget.getBoolean("unknownRefunded"));
        JSONObject fresh=wire("new-root-after-unknown","phone_observe",new JSONObject(),null).put("rootTaskId","fresh-root").put("parentTaskId","fresh-parent").put("localTaskId","fresh-session");fresh.getJSONObject("budget").put("allocationId",DeviceProtocol.allocationId("fresh-parent",target,binding,"tool_session",null));fresh.put("argumentsDigest",DeviceProtocol.argumentsDigest(fresh));refused(()->tasks.deviceTasks().accept(admission(fresh)));assertEquals(1,count("tasks"));
    }
    @Test public void capabilityManifestNeverTreatsDefaultModelOrAnotherCallersPolicyAsGoalAuthorization()throws Exception{
        JSONObject facts=new JSONObject().put("receiverEnabled",true).put("goalOwnerReviewAvailable",true).put("model",new JSONObject().put("ready",true)).put("main_screen",new JSONObject().put("installed",true).put("authorized",true).put("ready",true));
        DeviceCapabilityRegistry registry=new DeviceCapabilityRegistry(target,"receiver-fixture",()->facts);JSONObject manifest=registry.describe();JSONArray caps=manifest.getJSONArray("capabilities");assertTrue(caps.getJSONObject(0).getBoolean("ready"));assertFalse(caps.getJSONObject(0).getBoolean("modelRequired"));assertEquals(0,caps.getJSONObject(0).getJSONArray("toolDeclarations").length());assertEquals(9,caps.getJSONObject(0).getJSONArray("supportedToolDeclarations").length());JSONObject goal=caps.getJSONObject(2);assertFalse(goal.getBoolean("ready"));assertTrue(goal.getBoolean("canSubmitForLocalReview"));assertFalse(goal.getJSONObject("account").getBoolean("ready"));assertEquals("owner_selected_authorized_api",goal.getString("modelLocation"));
        var request=DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceRequest").put("method","device.describe").put("requestId",UUID.randomUUID().toString()).put("originDeviceId",caller.deviceId()).put("callerDeviceId",caller.deviceId()).put("targetDeviceId",target).put("instanceId","receiver-fixture").put("deadline",System.currentTimeMillis()+5000).put("arguments",new JSONObject())));var permission=pairing.checkDiscoveryPermission(caller,request,System.currentTimeMillis());facts.put("goalPolicy",new JSONObject().put("callerDeviceId",caller.deviceId()).put("configured",true).put("apiModelReady",true));assertTrue(registry.describe(permission).getJSONArray("capabilities").getJSONObject(2).getBoolean("ready"));facts.getJSONObject("goalPolicy").put("callerDeviceId","pw1_"+"f".repeat(64));assertFalse(registry.describe(permission).getJSONArray("capabilities").getJSONObject(2).getBoolean("ready"));
    }
    @Test public void nativeToolEffectAndCapabilityVersionRejectBeforeAcceptedOrOriginalOperation()throws Exception{
        JSONObject disguised=wire("effect-disguised-as-read","phone_click_node",new JSONObject().put("nodeId","n0"),null);disguised.getJSONObject("arguments").put("effect","read");disguised.remove("operationId");disguised.remove("argumentsDigest");
        DeviceExecutionAdmission parsed=admission(disguised);refused(()->coordinator(tasks).submit(parsed));assertEquals(0,count("tasks"));assertEquals(0,count("device_inbound"));assertEquals(0,count("operations"));
        JSONObject unknown=wire("unsupported-native-version","phone_observe",new JSONObject(),null).put("capabilityVersion","unsupported-version");unknown.put("argumentsDigest",DeviceProtocol.argumentsDigest(unknown));assertThrows(DeviceProtocol.ProtocolException.class,()->admission(unknown));assertEquals(0,count("tasks"));
        JSONObject missing=wire("missing-write-original-op","phone_click_node",new JSONObject().put("nodeId","n0"),null);missing.remove("operationId");assertThrows(DeviceProtocol.ProtocolException.class,()->admission(missing));assertEquals(0,count("operations"));
    }
    @Test public void hiddenOverlayNodeAndFocusFallbackCannotEscapeExactAuthorizedObservation()throws Exception{
        Running active=running();active.phone.foreignOverlay=true;JSONObject observed=active.runtime.invoke(admission(wire("observe-a","phone_observe",new JSONObject(),null)));
        assertFalse(observed.toString().contains("foreign overlay secret"));assertEquals(1,observed.getJSONArray("windows").length());assertEquals(1,observed.getJSONArray("nodes").length());JSONObject frame=observed.getJSONObject("observationRef");long prepared=count("operations");
        DeviceExecutionAdmission guessed=admission(wire("guessed-overlay-node","phone_click_node",new JSONObject().put("nodeId","n-hidden"),frame));tasks.deviceTasks().accept(guessed);refused(()->active.runtime.invoke(guessed));assertEquals(prepared,count("operations"));assertEquals(0,active.phone.actions.get());
        DeviceExecutionAdmission focus=admission(wire("implicit-focused-type","phone_type",new JSONObject().put("text","must not type into overlay"),frame));refused(()->coordinator(tasks).submit(focus));assertEquals(prepared,count("operations"));assertEquals(0,active.phone.actions.get());
        DeviceExecutionAdmission allowed=admission(wire("allowed-node-after-guess","phone_click_node",new JSONObject().put("nodeId","n0"),frame));tasks.deviceTasks().accept(allowed);assertTrue(active.runtime.invoke(allowed).getBoolean("accepted"));assertEquals(1,active.phone.actions.get());
        JSONArray granted=new JSONArray().put("phone_tap").put("phone_back").put("phone_type").put("phone_click_node");assertEquals(2,DeviceToolsRuntime.toolDeclarations(granted,binding).length());assertEquals(4,DeviceToolsRuntime.toolDeclarations(granted,new JSONObject(binding.toString()).put("allowWholeDisplayInput",true)).length());
        JSONObject tap=wire("whole-input-not-granted","phone_tap",new JSONObject().put("x",10).put("y",20),frame);assertThrows(SecurityException.class,()->DeviceToolsRuntime.validateInvocation(DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(tap))));
    }
    private record Outbound(String id,String run,int epoch,RunControl control,String operation,JSONObject draft,PairingCoordinator.OutgoingGrant grant){ }
    private Outbound outbound()throws Exception{return outbound(true);}
    private Outbound outbound(boolean prepareOperation)throws Exception{
        JSONObject parent=tasks.createCapabilityTask("synthetic original outbound CAS fixture","");String id=parent.getString("id");tasks.configureExecutionScope(id,0,deadline,20,20);parent=tasks.startAttempt(id,"capabilities",10);String run=parent.getString("runId");int epoch=parent.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(id,run,epoch);if(prepareOperation){tasks.authorizeTools(id,run,new JSONArray().put("device_delegate_goal"));tasks.status(id,"running","fixture");}
        String operation=prepareOperation?tasks.journal(id,run,epoch,control).beginOperation("device_delegate_goal",new JSONObject().put("targetDeviceId",caller.deviceId()).put("goal","child").put("toolBudget",4).put("modelBudget",4)):UUID.randomUUID().toString();JSONObject resource=new JSONObject().put("screenMode","capabilities");
        JSONObject grant=new JSONObject(nativeGrant.toString()).put("grantId","outgoing-fixture").put("localDeviceId",caller.deviceId()).put("peerDeviceId",target).put("allowedOrigins",new JSONArray().put(target)).put("resources",new JSONArray().put(resource)).put("capabilities",new JSONArray().put("task.submitGoal").put("operation.get").put("operation.cancel")).put("modelBudget",20).put("toolNames",new JSONArray().put("project_inspect"));
        JSONObject signed=new JSONObject().put("protocolVersion","1.0").put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","PairGrantReceipt").put("issuerDeviceId",caller.deviceId()).put("issuerCertificateFingerprint",caller.certificateFingerprint()).put("body",new JSONObject().put("grant",grant).put("targetInstanceId","caller-fixture").put("issuedAt",System.currentTimeMillis()));java.security.Signature signature=java.security.Signature.getInstance("SHA256withECDSA");signature.initSign(callerMaterial.keys.getPrivate());signature.update(DeviceProtocol.canonicalBytes(signed));signed.put("signature",java.util.Base64.getEncoder().encodeToString(signature.sign()));var receiving=pairing.acceptOutgoingGrant(caller,signed,System.currentTimeMillis());
        JSONObject draft=new JSONObject().put("protocolVersion","1.0").put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","GoalSubmission").put("method","task.submitGoal").put("requestId",UUID.randomUUID().toString()).put("operationId",operation).put("rootTaskId",id).put("parentTaskId",id).put("localTaskId","outgoing-session").put("originDeviceId",target).put("callerDeviceId",target).put("targetDeviceId",caller.deviceId()).put("runId",run).put("attempt",epoch).put("executorEpoch",epoch).put("instanceId","caller-fixture").put("grantId",receiving.grantId()).put("authorizationEpoch",receiving.authorizationEpoch()).put("deadline",deadline).put("capabilityVersion","1.0").put("resourceBinding",resource).put("budget",new JSONObject().put("allocationId",DeviceProtocol.allocationId(id,caller.deviceId(),resource,"goal",operation)).put("toolCalls",4).put("modelCalls",4)).put("visitedDevices",new JSONArray().put(target)).put("maxDelegationDepth",1).put("delegationChain",new JSONArray()).put("arguments",new JSONObject().put("goal","child").put("executionMode","agent").put("modelSource",new JSONObject().put("kind","local")));draft.put("argumentsDigest",DeviceProtocol.argumentsDigest(draft));return new Outbound(id,run,epoch,control,operation,draft,receiving);
    }
    private String originalOperationState(Outbound source)throws Exception{try(Cursor row=sql.rawQuery("SELECT state FROM operations WHERE operation_id=?",new String[]{source.operation})){assertTrue(row.moveToFirst());return row.getString(0);}}
    @Test public void nativeReservationRechecksOriginalPauseScopeAndPreparedPayloadAfterSigningGap()throws Exception{
        Outbound source=outbound();var held=tasks.deviceTasks().reserveChildNative(source.id,source.run,source.epoch,source.control,source.operation,source.draft);held.requireIntent(source.draft);source.control.pause();refused(held::requireCurrent);refused(()->tasks.deviceTasks().bindReservedWire(held,DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(source.draft)),source.grant));assertFalse(tasks.deviceTasks().childAllocationForOperation(source.id,source.operation).has("boundWire"));assertEquals("prepared",originalOperationState(source));assertEquals(4,tasks.deviceTasks().heldBudget(source.id,source.run,false));
        Outbound changed=outbound();JSONObject tampered=new JSONObject(changed.draft.toString());tampered.getJSONObject("arguments").put("goal","not the original prepared goal");tampered.put("argumentsDigest",DeviceProtocol.argumentsDigest(tampered));refused(()->tasks.deviceTasks().reserveChildNative(changed.id,changed.run,changed.epoch,changed.control,changed.operation,tampered));assertEquals("prepared",originalOperationState(changed));
        for(var ctor:TaskStoreDeviceTasks.ChildReservation.class.getDeclaredConstructors())assertTrue(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()));for(var ctor:TaskStoreDeviceTasks.OutboundSendTicket.class.getDeclaredConstructors())assertTrue(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()));
    }
    @Test public void originalSendTicketClaimsExactlyOnceAtActualPeerAndPreservesUnknownAfterReopen()throws Exception{
        Outbound source=outbound();var held=tasks.deviceTasks().reserveChildNative(source.id,source.run,source.epoch,source.control,source.operation,source.draft);var ticket=tasks.deviceTasks().bindReservedWire(held,DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(source.draft)),source.grant);assertFalse(ticket.isClaimed());assertEquals("prepared",originalOperationState(source));assertEquals(DeviceProtocol.canonicalBytes(source.draft).length,ticket.size());DeviceIdentity.AuthenticatedPeer unrelated=tls().caller;refused(()->ticket.claimForWrite(unrelated));assertFalse(ticket.isClaimed());assertEquals("prepared",originalOperationState(source));
        assertArrayEquals(DeviceProtocol.canonicalBytes(source.draft),ticket.claimForWrite(caller));assertTrue(ticket.isClaimed());assertEquals("unknown",originalOperationState(source));refused(()->ticket.claimForWrite(caller));refused(ticket::abandonBeforeWrite);assertEquals(4,tasks.deviceTasks().heldBudget(source.id,source.run,false));
        tasks.journal(source.id,source.run,source.epoch,source.control).finishOperation(source.operation,new JSONObject().put("accepted",JSONObject.NULL).put("code","device_effect_unknown").put("nativeDeviceSend",new JSONObject().put("state","not_claimed")));try(Cursor row=sql.rawQuery("SELECT receipt FROM operations WHERE operation_id=?",new String[]{source.operation})){assertTrue(row.moveToFirst());assertEquals("write_claimed",new JSONObject(row.getString(0)).getJSONObject("nativeDeviceSend").getString("state"));}
        TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());assertEquals(4,reopened.deviceTasks().heldBudget(source.id,source.run,false));assertEquals("unknown",originalOperationState(source));
    }
    @Test public void queuedTicketCanBeAbandonedOnlyBeforeOriginalClaimAndNeverReturnsChildHold()throws Exception{
        Outbound source=outbound();var held=tasks.deviceTasks().reserveChildNative(source.id,source.run,source.epoch,source.control,source.operation,source.draft);var ticket=tasks.deviceTasks().bindReservedWire(held,DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(source.draft)),source.grant);ticket.abandonBeforeWrite();assertEquals("prepared",originalOperationState(source));refused(()->ticket.claimForWrite(caller));assertEquals(4,tasks.deviceTasks().heldBudget(source.id,source.run,false));
        Outbound paused=outbound();var reservation=tasks.deviceTasks().reserveChildNative(paused.id,paused.run,paused.epoch,paused.control,paused.operation,paused.draft);var queued=tasks.deviceTasks().bindReservedWire(reservation,DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(paused.draft)),paused.grant);paused.control.pause();refused(()->queued.claimForWrite(caller));assertEquals("prepared",originalOperationState(paused));assertEquals(4,tasks.deviceTasks().heldBudget(paused.id,paused.run,false));
    }
    private static JSONObject signedProof(Material issuer,JSONObject statement)throws Exception{
        JSONObject proof=new JSONObject(statement.toString()).put("issuerCertificateDer",java.util.Base64.getEncoder().encodeToString(issuer.cert.getEncoded())).put("issuerCertificateFingerprint",DeviceProtocol.sha256(issuer.cert.getEncoded()));java.security.Signature signer=java.security.Signature.getInstance("SHA256withECDSA");signer.initSign(issuer.keys.getPrivate());signer.update(DeviceProtocol.canonicalBytes(proof));return proof.put("signature",java.util.Base64.getEncoder().encodeToString(signer.sign()));
    }
    @Test public void verifiedASourceShareCannotBeCopiedByNewRootAllocationOrResigning()throws Exception{
        Material origin=certificate();String originId=DeviceIdentity.id(origin.cert);nativeGrant.put("allowedOrigins",new JSONArray().put(caller.deviceId()).put(originId)).put("allowDelegation",true).put("maxDelegationDepth",2);ContentValues revised=new ContentValues();revised.put("body_json",nativeGrant.toString());sql.update("device_grants",revised,"grant_id=?",new String[]{"grant-test"});
        String sourceAllocation=DeviceProtocol.allocationId("original-A-task",caller.deviceId(),new JSONObject().put("screenMode","capabilities"),"goal","original-A-prepared");
        JSONObject statement=new JSONObject().put("protocolVersion","1.0").put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DelegationProof").put("issuerDeviceId",originId).put("delegateDeviceId",caller.deviceId()).put("targetResources",new JSONArray().put(new JSONObject().put("targetDeviceId",target).put("resourceBinding",binding))).put("rootTaskId","root-fixture").put("parentTaskId","parent-fixture").put("grantId","original-A-to-B").put("authorizationEpoch",1).put("capabilities",new JSONArray().put("tool.invoke")).put("toolNames",new JSONArray().put("phone_observe").put("phone_click_node")).put("toolBudget",2).put("modelBudget",1).put("deadline",deadline).put("remainingDepth",0).put("previousProofDigest","").put("sourceAllocationId",sourceAllocation).put("targetShares",new JSONArray().put(DelegationGrant.targetShare(originId,sourceAllocation,caller.deviceId(),new JSONObject().put("screenMode","capabilities"),0,1)).put(DelegationGrant.targetShare(originId,sourceAllocation,target,binding,2,0)));
        JSONObject first=wire("source-share-observe","phone_observe",new JSONObject(),null).put("originDeviceId",originId).put("visitedDevices",new JSONArray().put(originId).put(caller.deviceId())).put("maxDelegationDepth",2).put("delegationChain",new JSONArray().put(signedProof(origin,statement)));first.getJSONObject("budget").put("toolCalls",2);first.put("argumentsDigest",DeviceProtocol.argumentsDigest(first));DeviceExecutionAdmission admitted=admission(first);assertNotNull(admitted.sourceShare());JSONObject accepted=tasks.deviceTasks().accept(admitted);String id=accepted.getString("localTaskId");RunControl original=new RunControl();tasks.deviceTasks().startAttempt(id,admitted,original);tasks.status(id,"running","verified synthetic source pool fixture");FakePhone phone=new FakePhone();DeviceToolsRuntime runtime=new DeviceToolsRuntime(target,admitted,original,phone,tasks.deviceTasks(),(mode,control)->{});
        JSONObject frame=runtime.invoke(admitted).getJSONObject("observationRef");JSONObject click=new JSONObject(first.toString()).put("requestId",UUID.randomUUID().toString()).put("operationId","source-share-click").put("arguments",new JSONObject().put("toolName","phone_click_node").put("effect","write").put("arguments",new JSONObject().put("nodeId","n0"))).put("observationRef",frame);click.put("argumentsDigest",DeviceProtocol.argumentsDigest(click));DeviceExecutionAdmission action=admission(click);tasks.deviceTasks().accept(action);assertTrue(runtime.invoke(action).getBoolean("accepted"));assertEquals(1,phone.actions.get());assertEquals(2,tasks.get(id,false).getJSONObject("deviceExecutionScope").getInt("localToolsUsed"));
        original.cancel();tasks.endAttempt(id,original.hostRunIdentity().runId(),"cancelled","actual fixture resource released",new JSONObject());tasks.deviceTasks().release(original.hostRunIdentity().runId(),original);runtime.close();
        JSONObject next=new JSONObject(first.toString()).put("requestId",UUID.randomUUID().toString()).put("operationId","same-source-new-allocation").put("parentTaskId","fresh-B-parent").put("localTaskId","fresh-B-session");next.getJSONObject("budget").put("allocationId",DeviceProtocol.allocationId("fresh-B-parent",target,binding,"tool_session",null));JSONObject resigned=new JSONObject(statement.toString()).put("parentTaskId","fresh-B-parent");next.put("delegationChain",new JSONArray().put(signedProof(origin,resigned)));next.put("argumentsDigest",DeviceProtocol.argumentsDigest(next));refused(()->tasks.deviceTasks().accept(admission(next)));assertEquals(1,count("tasks"));assertEquals(2,count("operations"));
        JSONObject inflated=new JSONObject(first.toString());inflated.getJSONObject("budget").put("toolCalls",3);inflated.put("argumentsDigest",DeviceProtocol.argumentsDigest(inflated));refused(()->admission(inflated));
        JSONObject duplicated=new JSONObject(statement.toString());duplicated.getJSONArray("targetShares").put(DelegationGrant.targetShare(originId,sourceAllocation,target,new JSONObject(binding.toString()).put("allowWholeDisplayInput",false),2,0));JSONObject fourPrepares=new JSONObject(first.toString()).put("delegationChain",new JSONArray().put(signedProof(origin,duplicated)));fourPrepares.put("argumentsDigest",DeviceProtocol.argumentsDigest(fourPrepares));assertThrows(DeviceProtocol.ProtocolException.class,()->admission(fourPrepares));
        refused(()->pairing.checkHistoryPermission(caller,historyQuery("operation.get","source-share-observe",1),tasks.deviceTasks().historyAudiences(),System.currentTimeMillis())); // Native A-source withdrawal visibility is unknown; pairing B alone cannot clear A.
    }
    @Test public void nativeOwnerRevisionNarrowsFiveSpentOneToThreeWithoutResettingOriginalAllocation()throws Exception{
        JSONObject first=wire("narrow-first","phone_observe",new JSONObject(),null);first.getJSONObject("budget").put("toolCalls",5);first.put("argumentsDigest",DeviceProtocol.argumentsDigest(first));DeviceExecutionAdmission initial=admission(first);String id=tasks.deviceTasks().accept(initial).getString("localTaskId");RunControl original=new RunControl();tasks.deviceTasks().startAttempt(id,initial,original);tasks.status(id,"running","original allocation five fixture");FakePhone phone=new FakePhone();var runtime=new DeviceToolsRuntime(target,initial,original,phone,tasks.deviceTasks(),(mode,control)->{});assertTrue(runtime.invoke(initial).getBoolean("ok"));JSONObject before=tasks.get(id,false);String run=before.getString("runId"),allocation=before.getJSONObject("deviceExecutionScope").getString("allocationId");assertEquals(1,before.getJSONObject("deviceExecutionScope").getInt("toolsUsed"));
        nativeGrant.put("authorizationEpoch",2).put("toolBudget",3);pairing.replaceGrantLocally(nativeGrant,System.currentTimeMillis());JSONObject unchanged=new JSONObject(first.toString()).put("operationId","old-five-after-owner-three").put("requestId",UUID.randomUUID().toString()).put("authorizationEpoch",2);unchanged.put("argumentsDigest",DeviceProtocol.argumentsDigest(unchanged));refused(()->admission(unchanged));
        for(int n=0;n<2;n++){JSONObject wire=wire("narrow-remaining-"+n,"phone_observe",new JSONObject(),null).put("authorizationEpoch",2);wire.getJSONObject("budget").put("toolCalls",3);wire.put("argumentsDigest",DeviceProtocol.argumentsDigest(wire));DeviceExecutionAdmission narrowed=admission(wire);tasks.deviceTasks().accept(narrowed);assertTrue(runtime.invoke(narrowed).getBoolean("ok"));}
        JSONObject after=tasks.get(id,false);assertEquals(run,after.getString("runId"));assertEquals(allocation,after.getJSONObject("deviceExecutionScope").getString("allocationId"));assertEquals(3,after.getJSONObject("deviceExecutionScope").getInt("toolLimit"));assertEquals(3,after.getJSONObject("deviceExecutionScope").getInt("toolsUsed"));assertEquals(2,after.getJSONObject("origin").getInt("authorizationEpoch"));assertEquals(3,count("operations"));assertEquals(1,count("runs"));
        JSONObject exhausted=wire("narrow-exhausted","phone_observe",new JSONObject(),null).put("authorizationEpoch",2);exhausted.getJSONObject("budget").put("toolCalls",3);exhausted.put("argumentsDigest",DeviceProtocol.argumentsDigest(exhausted));DeviceExecutionAdmission noMore=admission(exhausted);tasks.deviceTasks().accept(noMore);refused(()->runtime.invoke(noMore));assertEquals(3,count("operations"));assertEquals(0,phone.actions.get());
        nativeGrant.put("authorizationEpoch",3).put("toolBudget",4);pairing.replaceGrantLocally(nativeGrant,System.currentTimeMillis());JSONObject increase=new JSONObject(exhausted.toString()).put("operationId","unsigned-increase-is-not-amendment").put("requestId",UUID.randomUUID().toString()).put("authorizationEpoch",3);increase.getJSONObject("budget").put("toolCalls",4);increase.put("argumentsDigest",DeviceProtocol.argumentsDigest(increase));refused(()->tasks.deviceTasks().accept(admission(increase)));assertEquals(3,tasks.get(id,false).getJSONObject("deviceExecutionScope").getInt("toolsUsed"));
    }
    private DeviceTaskEnvelope historyQuery(String method,String object,long epoch)throws Exception{
        return DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(new JSONObject().put("protocolVersion","1.0").put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceRequest").put("method",method).put("requestId",UUID.randomUUID().toString()).put("originDeviceId",caller.deviceId()).put("callerDeviceId",caller.deviceId()).put("targetDeviceId",target).put("instanceId","receiver-fixture").put("grantId","grant-test").put("authorizationEpoch",epoch).put("resourceBinding",binding).put("deadline",System.currentTimeMillis()+5000).put("arguments",new JSONObject().put(method.equals("operation.get")?"operationId":"taskId",object))));
    }
    @Test public void historyUsesAcceptedSqlAudienceWithoutInventingTargetRunAndOldHandleCannotCrossStartOrGrantRevision()throws Exception{
        DeviceExecutionAdmission accepted=admission(wire("pending-no-target-run","phone_observe",new JSONObject(),null));String id=tasks.deviceTasks().accept(accepted).getString("localTaskId");var pending=pairing.checkHistoryPermission(caller,historyQuery("operation.get","pending-no-target-run",1),tasks.deviceTasks().historyAudiences(),System.currentTimeMillis());assertFalse(pending.audience().has("runId"));assertEquals("source-run",pending.audience().getString("requestRunId"));assertEquals("phone-session",pending.audience().getString("localTaskId"));assertEquals(id,pending.audience().getString("taskId"));assertTrue(coordinator(tasks).operation(pending).getBoolean("accepted"));assertEquals(0,count("runs"));
        RunControl original=new RunControl();tasks.deviceTasks().startAttempt(id,accepted,original);tasks.status(id,"running","actual target run now bound");refused(()->pending.checkCurrent(System.currentTimeMillis()));var fresh=pairing.checkHistoryPermission(caller,historyQuery("task.get",id,1),tasks.deviceTasks().historyAudiences(),System.currentTimeMillis());assertEquals(original.hostRunIdentity().runId(),fresh.audience().getString("runId"));assertEquals(id,coordinator(tasks).task(fresh).getString("taskId"));
        nativeGrant.put("authorizationEpoch",2).put("historyRead",new JSONObject().put("expiresAt",deadline+60000).put("methods",new JSONArray().put("operation.get").put("task.get")));pairing.replaceGrantLocally(nativeGrant,System.currentTimeMillis());refused(()->fresh.checkCurrent(System.currentTimeMillis()));var renewed=pairing.checkHistoryPermission(caller,historyQuery("operation.get","pending-no-target-run",2),tasks.deviceTasks().historyAudiences(),System.currentTimeMillis());assertEquals(2,renewed.authorizationEpoch());assertEquals(1,renewed.acceptedAuthorizationEpoch());assertTrue(coordinator(tasks).operation(renewed).getBoolean("accepted"));assertEquals(0,count("operations"));
    }
    @Test public void executionExpiryDoesNotEraseReceiptButFreshBoundedHistoryNeedsExplicitCurrentPolicy()throws Exception{
        deadline=System.currentTimeMillis()+1500;nativeGrant.put("expiresAt",deadline).put("historyRead",new JSONObject().put("expiresAt",deadline+60000).put("methods",new JSONArray().put("operation.get").put("task.get")));ContentValues policy=new ContentValues();policy.put("body_json",nativeGrant.toString());policy.put("expires_at",deadline);sql.update("device_grants",policy,"grant_id=?",new String[]{"grant-test"});Running active=running();assertTrue(active.runtime.invoke(admission(wire("observe-a","phone_observe",new JSONObject(),null))).getBoolean("ok"));long ops=count("operations"),before=count("runs");Thread.sleep(Math.max(1,deadline-System.currentTimeMillis()+20));
        JSONObject expired=wire("expired-new-effect","phone_click_node",new JSONObject().put("nodeId","n0"),null);assertThrows(DeviceProtocol.ProtocolException.class,()->admission(expired));var read=pairing.checkHistoryPermission(caller,historyQuery("operation.get","observe-a",1),tasks.deviceTasks().historyAudiences(),System.currentTimeMillis());JSONObject receipt=coordinator(tasks).operation(read);assertTrue(receipt.getBoolean("accepted"));assertEquals("receipt",receipt.getString("operationState"));assertEquals(ops,count("operations"));assertEquals(before,count("runs"));assertEquals(0,active.phone.actions.get());
        new TaskStoreDeviceTrust(sql).revoke(caller.deviceId(),System.currentTimeMillis());refused(()->read.checkCurrent(System.currentTimeMillis()));assertEquals(ops,count("operations"));
    }
    @Test public void sourceRepliesRequireExactRequestMethodInstanceOperationAndDigestAndEmptyObjectIsNotSuccess()throws Exception{
        Outbound source=outbound();DeviceTaskEnvelope request=DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(source.draft));DeviceCapabilityPort.NativeRoute route=new DeviceCapabilityPort.NativeRoute(){public String targetDeviceId(){return source.grant.targetDeviceId();}public void requireCurrent(long now)throws Exception{source.grant.checkCurrent(now);}public String effect(String tool){return "write";}public PairingCoordinator.OutgoingGrant receivingGrant(){return source.grant;}public DeviceTaskEnvelope request(String method,JSONObject args,String op,int tools,int models,JSONObject ref,RunControl control){throw new AssertionError("not a sending fixture");}public DeviceCapabilityPort.Reply exchange(DeviceTaskEnvelope value,RunControl original){throw new AssertionError("no transport write in validation fixture");}};
        long acceptedAt=System.currentTimeMillis();JSONObject audience=new JSONObject().put("kind","operation").put("objectId",request.operationId()).put("instanceId","caller-fixture").put("rootTaskId",request.rootTaskId()).put("localTaskId",request.localTaskId()).put("originDeviceId",request.originDeviceId()).put("callerDeviceId",request.callerDeviceId()).put("targetDeviceId",request.targetDeviceId()).put("grantId",request.grantId()).put("authorizationEpoch",request.authorizationEpoch()).put("resourceBinding",request.resourceBinding()).put("acceptedAt",acceptedAt).put("retainedUntil",deadline+60000);
        JSONObject accepted=DeviceProtocol.operationReceipt(caller.deviceId(),"caller-fixture",request.operationId(),request.argumentsDigest(),"accepted",true,"not_dispatched",new JSONObject(),new JSONObject().put("acceptedAt",acceptedAt).put("audience",audience).put("localTaskId",DeviceProtocol.receiverTaskId(request)));JSONObject response=new JSONObject().put("protocolVersion","1.0").put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceResponse").put("requestId",request.requestId()).put("method",request.method()).put("executorDeviceId",caller.deviceId()).put("instanceId","caller-fixture").put("result",accepted);assertTrue(DeviceCapabilityPort.reply(route,new DeviceCapabilityPort.Reply(caller,response),request).getBoolean("accepted"));
        for(String field:new String[]{"requestId","method","instanceId"}){JSONObject wrong=new JSONObject(response.toString()).put(field,field.equals("method")?"operation.get":"different");refusedReply(()->DeviceCapabilityPort.reply(route,new DeviceCapabilityPort.Reply(caller,wrong),request));}
        for(String field:new String[]{"operationId","argumentsDigest"}){JSONObject wrong=new JSONObject(response.toString());wrong.getJSONObject("result").put(field,"not-original");refusedReply(()->DeviceCapabilityPort.reply(route,new DeviceCapabilityPort.Reply(caller,wrong),request));}JSONObject empty=new JSONObject(response.toString()).put("result",new JSONObject());refusedReply(()->DeviceCapabilityPort.reply(route,new DeviceCapabilityPort.Reply(caller,empty),request));assertEquals("prepared",originalOperationState(source));
    }
    @Test public void localPendingGoalCancelNeedsExactAcceptedNativeAdmissionButNotFreshExecutionGrant()throws Exception{
        DeviceExecutionAdmission accepted=admission(goalWire("pending-owner-cancel",2));String id=tasks.deviceTasks().accept(accepted).getString("localTaskId");assertEquals(0,count("runs"));assertEquals(0,count("operations"));new TaskStoreDeviceTrust(sql).revoke(caller.deviceId(),System.currentTimeMillis());JSONObject stopped=tasks.deviceTasks().cancelPendingGoal(id,accepted);assertEquals("cancelled",stopped.getString("status"));assertTrue(stopped.getBoolean("stopped"));assertTrue(stopped.getBoolean("notDispatched"));assertEquals(0,count("runs"));assertEquals(0,count("operations"));assertEquals("paired_device",stopped.getJSONObject("origin").getString("kind"));refused(()->tasks.deviceTasks().cancelPendingGoal(id,accepted));
    }
    private NativeDeviceTaskScopeHost reviewHost(Outbound original,JSONObject manifest,AtomicInteger refreshes){return new NativeDeviceTaskScopeHost(tasks,new NativeDeviceTaskScopeHost.NativeOptionSource(){public Iterable<PairingCoordinator.OutgoingGrant> originalOutgoingGrants(){return List.of(original.grant,original.grant);}public JSONObject authenticatedCapabilities(String target){refreshes.incrementAndGet();return manifest;}});}
    private JSONObject goalManifest(Outbound original)throws Exception{return DeviceProtocol.deviceDescription(caller.deviceId(),"caller-fixture",System.currentTimeMillis(),System.currentTimeMillis()+5000,new JSONArray().put(new JSONObject().put("id","agent.goal").put("version","1.0").put("implemented",true).put("installed",true).put("ready",true).put("toolDeclarations",new JSONArray().put(new JSONObject().put("toolName","project_inspect").put("effect","read")))),new JSONArray(),new JSONObject());}
    @Test public void sourceOwnerScopeUsesNativeTargetOptionsAndOpaqueSameWindowWithoutSpendingOrNewRun()throws Exception{
        Outbound source=outbound();AtomicInteger refreshes=new AtomicInteger();var host=reviewHost(source,goalManifest(source),refreshes);long runs=count("runs"),operations=count("operations"),allocations=count("device_child_budget");try(var window=host.open(source.id)){
            JSONObject view=window.view();assertEquals(1,refreshes.get());assertEquals("goal_only",view.getString("contextMode"));assertEquals(0,view.getInt("sharedArtifactCount"));assertFalse(view.getBoolean("ambientMemoryProvided"));JSONObject option=view.getJSONArray("options").getJSONObject(0);
            var choice=new NativeDeviceTaskScopeHost.RouteChoice(option.getString("optionId"),4,4,deadline,4,4,List.of(),NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY);var review=window.review(new NativeDeviceTaskScopeHost.Selection(List.of(choice)));assertFalse(review.view().getBoolean("budgetReserved"));assertEquals(allocations,count("device_child_budget"));try(var other=host.open(source.id)){refused(()->other.confirm(review));}
            JSONObject confirmed=window.confirm(review);assertFalse(confirmed.getBoolean("executionAuthorized"));assertFalse(confirmed.getBoolean("budgetReserved"));assertEquals(source.run,tasks.get(source.id,false).getString("runId"));assertEquals(runs,count("runs"));assertEquals(operations,count("operations"));assertEquals(allocations,count("device_child_budget"));refused(()->window.confirm(review));var routes=host.routeApprovalsForOriginalRun(source.id,source.control);assertEquals(1,routes.size());assertEquals(caller.deviceId(),routes.get(0).receivingGrant().targetDeviceId());routes.get(0).checkCurrent(source.control);
        }
    }
    @Test public void installedVisualGoalKeepsAcceptedRunningAndSettlesOriginalOnce()throws Exception {
        java.util.concurrent.atomic.AtomicBoolean unavailable=new java.util.concurrent.atomic.AtomicBoolean();Outbound source=outbound(false);AtomicInteger imageReads=new AtomicInteger(),submissions=new AtomicInteger(),queries=new AtomicInteger();
        var host=new NativeDeviceTaskScopeHost(tasks,new NativeDeviceTaskScopeHost.NativeOptionSource(){
            public Iterable<PairingCoordinator.OutgoingGrant> originalOutgoingGrants(){return List.of(source.grant);}
            public JSONObject authenticatedCapabilities(String target)throws Exception{return goalManifest(source);}
        },new DeviceTaskCoordinator.NativeHost(){public void acceptTools(String id,DeviceExecutionAdmission admission){fail("source only");}public void acceptGoal(String id,DeviceExecutionAdmission admission){fail("source only");}public void controlRequested(String id,String action){fail("source only");}},evidence->{imageReads.incrementAndGet();throw new AssertionError("GoalSubmission is not an image tool");});
        String route;try(var window=host.open(source.id)){route=window.view().getJSONArray("options").getJSONObject(0).getString("optionId");window.confirm(window.review(new NativeDeviceTaskScopeHost.Selection(List.of(new NativeDeviceTaskScopeHost.RouteChoice(route,4,4,deadline,4,4,List.of(),NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY)))));}
        java.util.concurrent.atomic.AtomicReference<DeviceTaskEnvelope> sent=new java.util.concurrent.atomic.AtomicReference<>();long acceptedAt=System.currentTimeMillis();String childRun=UUID.randomUUID().toString();
        var exchange=new DeviceNativeRoute.Exchange(){
            private DeviceCapabilityPort.Reply reply(DeviceTaskEnvelope request,String status)throws Exception {
                DeviceTaskEnvelope original=sent.get();JSONObject wire=original.json();String child=DeviceProtocol.receiverTaskId(wire);boolean terminal=status.equals("succeeded");
                JSONObject audience=new JSONObject().put("kind","operation").put("objectId",original.operationId()).put("runId",childRun).put("executorEpoch",1).put("originDeviceId",original.originDeviceId()).put("callerDeviceId",original.callerDeviceId()).put("targetDeviceId",original.targetDeviceId()).put("instanceId","caller-fixture").put("rootTaskId",original.rootTaskId()).put("localTaskId",original.localTaskId()).put("requestRunId",original.runId()).put("requestExecutorEpoch",original.executorEpoch()).put("grantId",original.grantId()).put("authorizationEpoch",original.authorizationEpoch()).put("resourceBinding",original.resourceBinding()).put("acceptedAt",acceptedAt).put("retainedUntil",deadline+60000);
                JSONObject body=new JSONObject().put("terminal",terminal).put("stopped",terminal).put("status",status).put("hasUnresolved",false).put("executorEpoch",1).put("toolsUsed",1).put("modelCallsUsed",2);
                JSONObject typed=DeviceProtocol.operationReceipt(caller.deviceId(),"caller-fixture",original.operationId(),original.argumentsDigest(),status,true,"reported",body,new JSONObject().put("acceptedAt",acceptedAt).put("audience",audience).put("localTaskId",child).put("runId",childRun).put("executorEpoch",1));
                return new DeviceCapabilityPort.Reply(caller,new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceResponse").put("requestId",request.requestId()).put("method",request.method()).put("executorDeviceId",caller.deviceId()).put("instanceId","caller-fixture").put("result",typed));
            }
            public DeviceCapabilityPort.Reply once(String target,DeviceTaskEnvelope request,RunControl original)throws Exception{assertEquals("operation.get",request.method());assertEquals(sent.get().operationId(),request.arguments().getString("operationId"));if(unavailable.get())throw new java.io.IOException("explicit query transport failure fixture");return reply(request,queries.incrementAndGet()==1?"running":"succeeded");}
            public DeviceCapabilityPort.Reply once(String target,DeviceTaskEnvelope request,TaskStoreDeviceTasks.OutboundSendTicket ticket,RunControl original)throws Exception{assertEquals("GoalSubmission",request.json().getString("messageType"));assertFalse(request.arguments().has("toolName"));assertArrayEquals(DeviceProtocol.canonicalBytes(request.json()),ticket.claimForWrite(caller));sent.set(request);submissions.incrementAndGet();return reply(request,"accepted");}
        };
        try(CapabilityPort port=host.actualHostTools(source.control,syntheticLocalIdentity(),exchange)){
            JSONArray names=new JSONArray();JSONArray definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(source.id,source.run,names);tasks.status(source.id,"running","fixture");
            JSONObject args=new JSONObject().put("routeId",route).put("targetDeviceId",caller.deviceId()).put("goal",tasks.get(source.id,false).getString("goal")).put("toolBudget",4).put("modelBudget",4);String operation=tasks.journal(source.id,source.run,source.epoch,source.control).beginOperation("device_delegate_goal",args);
            JSONObject accepted=port.execute("device_delegate_goal",new JSONObject(args.toString()).put("_hostOperationId",operation),source.control);assertEquals(accepted.toString(),true,accepted.opt("accepted"));assertEquals("accepted",accepted.getString("status"));assertFalse(accepted.getBoolean("ok"));
            JSONObject query=new JSONObject().put("routeId",route).put("targetDeviceId",caller.deviceId()).put("operationId",operation);
            JSONObject running=port.execute("device_operation_get",query,source.control);assertEquals(running.toString(),"running",running.getString("status"));assertEquals(4,tasks.deviceTasks().heldBudget(source.id,source.run,true));
            JSONObject terminal=port.execute("device_operation_get",query,source.control);assertEquals(terminal.toString(),"succeeded",terminal.getString("status"));assertTrue(terminal.getJSONObject("deviceReceipt").getJSONObject("payload").getJSONObject("nativeParent").getBoolean("settled"));
            JSONObject scope=tasks.get(source.id,false).getJSONObject("executionScope");int tools=scope.getInt("toolsUsed"),models=scope.getInt("modelCallsUsed");
            JSONObject repeated=port.execute("device_operation_get",query,source.control);assertTrue(repeated.toString(),repeated.getJSONObject("deviceReceipt").getJSONObject("payload").getJSONObject("nativeParent").getBoolean("knownSettlement"));assertEquals(tools,tasks.get(source.id,false).getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(models,tasks.get(source.id,false).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(0,tasks.deviceTasks().heldBudget(source.id,source.run,true));assertEquals(1,submissions.get());assertEquals(3,queries.get());assertEquals(0,imageReads.get());unavailable.set(true);JSONObject unknown=port.execute("device_operation_get",query,source.control);assertEquals("needs_reconcile",unknown.getString("status"));assertFalse(unknown.optBoolean("ok"));assertFalse(unknown.getBoolean("retrySubmitted"));assertEquals(1,submissions.get());
        }
    }
    @Test public void disabledProfilesAndClosedNativeReviewNeverCreateDeviceSourceAuthority()throws Exception{
        Outbound source=outbound();JSONObject disabled=goalManifest(source);disabled.getJSONArray("capabilities").getJSONObject(0).put("implemented",false);var host=reviewHost(source,disabled,new AtomicInteger());try(var window=host.open(source.id)){assertEquals(0,window.view().getJSONArray("options").length());}
        var live=reviewHost(source,goalManifest(source),new AtomicInteger());var window=live.open(source.id);JSONObject option=window.view().getJSONArray("options").getJSONObject(0);var review=window.review(new NativeDeviceTaskScopeHost.Selection(List.of(new NativeDeviceTaskScopeHost.RouteChoice(option.getString("optionId"),4,4,deadline,4,4,List.of(),NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY))));window.close();refused(()->window.confirm(review));assertFalse(tasks.get(source.id,false).has("deviceSourceScope"));assertEquals(0,count("device_child_budget"));
    }
    @Test public void deviceFrameKeepsRealOriginalRunAndNeverPretendsToBeBrowserOrReusableCapture()throws Exception{
        RunControl original=new RunControl();original.bindHostRun("native-a-task","native-a-run",7);AtomicInteger authority=new AtomicInteger(1);JSONObject reference=new JSONObject().put("sessionId","native-pc-session").put("displayId","primary").put("windowId",17).put("executorEpoch",3).put("capturedAt",System.currentTimeMillis());JSONObject resource=new JSONObject().put("kind","desktop").put("sessionId",1).put("displayId","primary").put("windowId",17);byte[] raw=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4c8AAAAASUVORK5CYII=");String digest=DeviceProtocol.sha256(raw);var identity=new dev.pocketengine.core.NativeToolObservation.DeviceFrameIdentity("native-a-task","native-a-run",7,"actual-source-op",caller.deviceId(),"actual-pc-instance","actual-pc-op",reference,resource,digest,1,1,reference.getLong("capturedAt"));var capture=dev.pocketengine.core.NativeToolObservation.captureDevice("device_operation_get","actual-tool-call-token",original,identity,raw,"image/png",()->{if(authority.get()!=1)throw new SecurityException("fixture native source withdrawn");});
        try(var actual=capture.consume("device_operation_get","actual-tool-call-token",original)){JSONObject metadata=actual.metadata();assertEquals("paired_device_visual_snapshot",metadata.getString("kind"));assertEquals("actual-source-op",metadata.getString("sourceOperationId"));assertEquals("actual-pc-op",metadata.getString("targetOperationId"));assertEquals("paired_device_verified_artifact",metadata.getString("captureMethod"));assertFalse(metadata.has("profileId"));assertFalse(metadata.has("tabId"));assertFalse(metadata.getBoolean("coordinateAuthority"));assertEquals(java.util.Base64.getEncoder().encodeToString(raw),actual.base64(original));refused(()->capture.consume("device_operation_get","actual-tool-call-token",original));RunControl other=new RunControl();other.bindHostRun("native-a-task","native-a-run",7);refused(()->actual.base64(other));authority.set(0);refused(()->actual.base64(original));}java.util.Arrays.fill(raw,(byte)0);
    }

    /** Explicit original-ledger state fixture, NOT evidence that a physical remote process stopped.
     * Native receipt authentication has separate mTLS/digest tests; this checks the terminal SQL
     * guard against unknown/unstopped vs retained authorization without falsifying usage. */
    private String retainedChildTerminalGuardFixture(String id,String run,int epoch)throws Exception{
        String operation="terminal-guard-"+UUID.randomUUID(),allocation="guard-allocation-"+UUID.randomUUID();JSONObject wire=new JSONObject().put("messageType","GoalSubmission").put("arguments",new JSONObject().put("effect","write"));
        ContentValues budget=new ContentValues();budget.put("parent_task_id",id);budget.put("parent_run_id",run);budget.put("parent_epoch",epoch);budget.put("parent_operation_id",operation);budget.put("allocation_id",allocation);budget.put("scope_digest","a".repeat(64));budget.put("target_device_id",caller.deviceId());budget.put("arguments_digest","b".repeat(64));budget.put("tools_reserved",0);budget.put("models_reserved",2);budget.put("state","unknown");budget.put("created_at",System.currentTimeMillis());sql.insertOrThrow("device_child_budget",null,budget);
        ContentValues link=new ContentValues();link.put("parent_task_id",id);link.put("parent_operation_id",operation);link.put("allocation_id",allocation);link.put("intent_digest","c".repeat(64));link.put("wire_digest","b".repeat(64));link.put("wire_json",wire.toString());sql.insertOrThrow("device_child_links",null,link);
        ContentValues op=new ContentValues();op.put("task_id",id);op.put("run_id",run);op.put("operation_id",operation);op.put("tool","device_delegate_goal");op.put("arguments","{}");op.put("state","unknown");op.put("receipt",new JSONObject().put("nativeDeviceSend",new JSONObject().put("state","write_claimed")).toString());op.put("created_at",System.currentTimeMillis());sql.insertOrThrow("operations",null,op);
        assertTrue(tasks.deviceTasks().hasUnstoppedChildOperations(id,run));assertTrue(tasks.hasUnresolved(id));
        JSONObject actual=new JSONObject().put("accepted",true).put("effectState","reported").put("status","succeeded").put("payload",new JSONObject().put("terminal",true).put("stopped",false));JSONObject receipt=new JSONObject().put("nativeDeviceSend",new JSONObject().put("state","write_claimed")).put("nativeDeviceAcceptedBinding",new JSONObject().put("verifiedReceipt",actual));ContentValues update=new ContentValues();update.put("state","receipt");update.put("receipt",receipt.toString());sql.update("operations",update,"task_id=? AND operation_id=?",new String[]{id,operation});assertFalse(tasks.hasUnresolved(id));assertTrue("completed text without actual stopped remains reconciling",tasks.deviceTasks().hasUnstoppedChildOperations(id,run));
        actual.getJSONObject("payload").put("stopped",true);update.put("receipt",receipt.toString());sql.update("operations",update,"task_id=? AND operation_id=?",new String[]{id,operation});ContentValues retained=new ContentValues();retained.put("state","committed_allocation");retained.put("receipt_json",new JSONObject().put("terminal",true).put("stopped",true).put("authorizationAllocationRetained",true).put("reportedActualModelCalls",0).toString());sql.update("device_child_budget",retained,"parent_task_id=? AND parent_operation_id=?",new String[]{id,operation});assertFalse(tasks.deviceTasks().hasUnstoppedChildOperations(id,run));assertEquals(2,tasks.deviceTasks().heldBudget(id,run,true));assertEquals(2,tasks.get(id,false).getJSONObject("deviceExecutionScope").getInt("modelCallsUsed"));return operation;
    }

    private DeviceIdentity.LocalIdentity syntheticLocalIdentity()throws Exception{
        // Explicit host fixture: real synthetic TLS key/certificate, never Android user credentials.
        KeyStore store=KeyStore.getInstance("PKCS12");store.load(null);var alias=DeviceIdentity.class.getDeclaredField("ALIAS");alias.setAccessible(true);store.setKeyEntry((String)alias.get(null),targetMaterial.keys.getPrivate(),null,new java.security.cert.Certificate[]{targetMaterial.cert});var constructor=DeviceIdentity.LocalIdentity.class.getDeclaredConstructor(KeyStore.class,X509Certificate.class,String.class);constructor.setAccessible(true);return constructor.newInstance(store,targetMaterial.cert,"receiver-fixture");
    }
    @Test public void ownerHistoryQueriesOriginalUnknownAfterPauseAndExecutionExpiryWithoutRunModelReplayOrRefund()throws Exception{
        deadline=System.currentTimeMillis()+2200;nativeGrant.put("expiresAt",deadline).put("historyRead",new JSONObject().put("expiresAt",deadline+60000).put("methods",new JSONArray().put("operation.get")));Outbound source=outbound();var held=tasks.deviceTasks().reserveChildNative(source.id,source.run,source.epoch,source.control,source.operation,source.draft);var ticket=tasks.deviceTasks().bindReservedWire(held,DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(source.draft)),source.grant);ticket.claimForWrite(caller);source.control.pause();AtomicInteger reads=new AtomicInteger();var host=new NativeDeviceTaskHistoryHost(tasks,syntheticLocalIdentity(),new NativeDeviceTaskHistoryHost.NativeReads(){public PairingCoordinator.HistoryRoute currentHistoryRoute(String target,String grant)throws Exception{return pairing.historyRoute(caller,grant,System.currentTimeMillis());}public DeviceCapabilityPort.Reply readHistory(DeviceTaskEnvelope query,RunControl io)throws Exception{reads.incrementAndGet();assertEquals(source.operation,query.arguments().getString("operationId"));assertEquals(target,query.originDeviceId());assertFalse(query.json().has("budget"));JSONObject typed=DeviceProtocol.operationReceipt(caller.deviceId(),"caller-fixture",source.operation,null,"not_found",false,"unknown",new JSONObject(),null);return new DeviceCapabilityPort.Reply(caller,new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceResponse").put("requestId",query.requestId()).put("method",query.method()).put("executorDeviceId",caller.deviceId()).put("instanceId","caller-fixture").put("result",typed));}});
        long runs=count("runs"),operations=count("operations");try(var window=host.open(source.id,source.operation)){assertTrue(window.view().getBoolean("readOnly"));Thread.sleep(Math.max(1,deadline-System.currentTimeMillis()+20));refused(()->source.grant.checkCurrent(System.currentTimeMillis()));JSONObject result=window.query();assertTrue(result.getBoolean("unknown"));assertFalse(result.getBoolean("retrySubmitted"));assertFalse(result.getBoolean("unknownRefunded"));assertEquals("unknown",originalOperationState(source));assertEquals(4,tasks.deviceTasks().heldBudget(source.id,source.run,true));assertEquals(runs,count("runs"));assertEquals(operations,count("operations"));assertEquals(1,reads.get());new TaskStoreDeviceTrust(sql).revoke(caller.deviceId(),System.currentTimeMillis());refused(window::query);assertEquals(1,reads.get());}
    }
    private JSONObject managementWire(JSONObject targetGoal,String cancelId,long epoch)throws Exception{
        JSONObject value=new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceRequest").put("method","operation.cancel").put("requestId",UUID.randomUUID().toString()).put("operationId",cancelId).put("originDeviceId",caller.deviceId()).put("callerDeviceId",caller.deviceId()).put("targetDeviceId",target).put("instanceId","receiver-fixture").put("grantId","grant-test").put("authorizationEpoch",epoch).put("resourceBinding",binding).put("deadline",System.currentTimeMillis()+5000).put("arguments",new JSONObject().put("operationId",targetGoal.getString("operationId")).put("argumentsDigest",targetGoal.getString("argumentsDigest")));return value.put("argumentsDigest",DeviceProtocol.argumentsDigest(value));
    }
    @Test public void managementCancelsOriginalAcceptedGoalWithZeroExecutionBudgetAndNoLeaseOrInventedRun()throws Exception{
        JSONObject goal=goalWire("original-pending-management-goal",2);String id=tasks.deviceTasks().accept(admission(goal)).getString("localTaskId");nativeGrant.getJSONArray("capabilities").put("operation.cancel");nativeGrant.put("authorizationEpoch",2).put("toolBudget",0).put("modelBudget",0);pairing.replaceGrantLocally(nativeGrant,System.currentTimeMillis());JSONObject cancel=managementWire(goal,"native-management-cancel",2);var registry=new DeviceCapabilityRegistry(target,"receiver-fixture",()->new JSONObject());var ingress=new DeviceIngress(target,"receiver-fixture",pairing,(request,kind)->{throw new AssertionError("management is not execution admission");},registry,coordinator(tasks));JSONObject receipt=ingress.receive(caller,DeviceProtocol.canonicalBytes(cancel)).getJSONObject("result");assertEquals("OperationReceipt",receipt.getString("messageType"));assertEquals(id,receipt.getString("localTaskId"));assertFalse(receipt.has("runId"));assertEquals(1,receipt.getJSONObject("payload").length());assertTrue(receipt.getJSONObject("payload").getBoolean("managementOnly"));assertTrue(receipt.getJSONObject("cancellation").getBoolean("requested"));assertTrue(receipt.getJSONObject("cancellation").getBoolean("stopped"));assertEquals("cancelled",tasks.get(id,false).getString("status"));assertEquals(0,count("runs"));assertEquals(1,count("operations"));ingress.receive(caller,DeviceProtocol.canonicalBytes(cancel));assertEquals(1,count("operations"));JSONObject wrong=managementWire(goal,"wrong-target-digest",2);wrong.getJSONObject("arguments").put("argumentsDigest","f".repeat(64));wrong.put("argumentsDigest",DeviceProtocol.argumentsDigest(wrong));refused(()->ingress.receive(caller,DeviceProtocol.canonicalBytes(wrong)));assertEquals(1,count("operations"));
    }

    @Test public void nativeParentStopUsesOriginalCancelTicketAfterExecutionCancellationWithoutQuotaOrUnknownRefund()throws Exception{
        Outbound source=outbound();var held=tasks.deviceTasks().reserveChildNative(source.id,source.run,source.epoch,source.control,source.operation,source.draft);var business=tasks.deviceTasks().bindReservedWire(held,DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(source.draft)),source.grant);business.claimForWrite(caller);source.control.cancel();tasks.requestCancel(source.id,source.run);ContentValues revokeSource=new ContentValues();revokeSource.put("revoked",1);sql.update("device_grants",revokeSource,"grant_id=?",new String[]{"grant-test"});var candidates=tasks.deviceTasks().originalStopCandidates(source.id,source.control);assertEquals(1,candidates.size());JSONObject before=tasks.get(source.id,false).getJSONObject("executionScope");long tools=before.getLong("toolsUsed"),models=before.getLong("modelCallsUsed");var stop=tasks.deviceTasks().prepareOriginalStop(candidates.get(0),syntheticLocalIdentity(),source.grant);assertFalse(stop.knownOperation());assertEquals("operation.cancel",stop.request().method());assertEquals(target,stop.request().originDeviceId());assertEquals(source.operation,stop.request().arguments().getString("operationId"));assertEquals(source.draft.getString("argumentsDigest"),stop.request().arguments().getString("argumentsDigest"));assertFalse(stop.request().json().has("budget"));refused(()->tasks.resolveOperation(source.id,source.operation,"not_occurred"));assertEquals("unknown",originalOperationState(source));refused(()->stop.ticket().claimForWrite(tls().caller));byte[] actual=stop.ticket().claimForWrite(caller);assertArrayEquals(DeviceProtocol.canonicalBytes(stop.request().json()),actual);refused(()->stop.ticket().claimForWrite(caller));assertTrue(tasks.deviceTasks().prepareOriginalStop(candidates.get(0),syntheticLocalIdentity(),source.grant).knownOperation());assertEquals(4,tasks.deviceTasks().heldBudget(source.id,source.run,true));JSONObject after=tasks.get(source.id,false).getJSONObject("executionScope");assertEquals(tools,after.getLong("toolsUsed"));assertEquals(models,after.getLong("modelCallsUsed"));
        long acceptedAt=System.currentTimeMillis();JSONObject audience=new JSONObject().put("kind","operation").put("objectId",stop.request().operationId()).put("originDeviceId",target).put("callerDeviceId",target).put("targetDeviceId",caller.deviceId()).put("instanceId","caller-fixture").put("rootTaskId",source.id).put("localTaskId","outgoing-session").put("grantId",source.grant.grantId()).put("authorizationEpoch",source.grant.authorizationEpoch()).put("resourceBinding",source.draft.getJSONObject("resourceBinding")).put("acceptedAt",acceptedAt).put("retainedUntil",deadline+60000);JSONObject cancellation=new JSONObject().put("targetOperationId",source.operation).put("targetArgumentsDigest",source.draft.getString("argumentsDigest")).put("requested",true).put("stopped",false);JSONObject receipt=DeviceProtocol.operationReceipt(caller.deviceId(),"caller-fixture",stop.request().operationId(),stop.request().argumentsDigest(),"cancel_requested",true,"reported",new JSONObject().put("managementOnly",true),new JSONObject().put("acceptedAt",acceptedAt).put("audience",audience).put("cancellation",cancellation));JSONObject response=new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceResponse").put("method","operation.cancel").put("requestId",stop.request().requestId()).put("executorDeviceId",caller.deviceId()).put("instanceId","caller-fixture").put("result",receipt);JSONObject result=tasks.deviceTasks().recordOriginalStop(stop,new DeviceCapabilityPort.Reply(caller,response));assertTrue(result.getBoolean("requested"));assertFalse(result.getBoolean("stopped"));assertEquals("unknown",originalOperationState(source));assertEquals(4,tasks.deviceTasks().heldBudget(source.id,source.run,true));
    }

    private TaskStore reopenColdStopStore()throws Exception{
        sql.close();TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);sql=(SQLiteDatabase)field.get(reopened);
        pairing=new PairingCoordinator(new TaskStoreDeviceTrust(sql),target,"receiver-fixture");return reopened;
    }
    private PairingCoordinator.OutgoingGrant coldStopGrant(String grantId)throws Exception{
        return pairing.acceptOutgoingGrant(caller,new TaskStoreDeviceTrust(sql).outgoingGrant(caller.deviceId(),grantId),System.currentTimeMillis());
    }
    @Test public void coldOwnerStopUsesDurableOriginalOperationWithoutRevivingRunOrRefundingUnknown()throws Exception{
        Outbound source=outbound();var held=tasks.deviceTasks().reserveChildNative(source.id,source.run,source.epoch,source.control,source.operation,source.draft);
        tasks.deviceTasks().bindReservedWire(held,DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(source.draft)),source.grant).claimForWrite(caller);
        long taskCount=count("tasks"),runCount=count("runs");
        TaskStore reopened=reopenColdStopStore();var freshGrant=coldStopGrant(source.grant.grantId());
        var original=reopened.deviceTasks();
        refused(()->original.originalStopCandidates(source.id,null));
        refused(()->original.reviewOwnerStop("another-task",source.operation));
        refused(()->original.reviewOwnerStop(source.id,"another-operation"));
        var review=original.reviewOwnerStop(source.id,source.operation);
        var candidate=original.confirmOwnerStop(review);
        refused(()->tasks.deviceTasks().confirmOwnerStop(review));
        var dispatch=original.prepareOriginalStop(candidate,syntheticLocalIdentity(),freshGrant);
        assertEquals("operation.cancel",dispatch.request().method());assertEquals(source.operation,dispatch.request().arguments().getString("operationId"));
        assertFalse(dispatch.request().json().has("budget"));assertFalse(dispatch.request().json().has("runId"));
        refused(()->dispatch.ticket().claimForWrite(tls().caller));
        dispatch.ticket().claimForWrite(caller);
        refused(()->dispatch.ticket().claimForWrite(caller));
        String cancelId=dispatch.request().operationId();
        // A second cold process owns no in-memory review, control or ticket from the first.
        TaskStore again=reopenColdStopStore();var secondGrant=coldStopGrant(source.grant.grantId());
        var second=again.deviceTasks().confirmOwnerStop(again.deviceTasks().reviewOwnerStop(source.id,source.operation));
        var duplicate=again.deviceTasks().prepareOriginalStop(second,syntheticLocalIdentity(),secondGrant);
        assertTrue(duplicate.knownOperation());assertEquals(cancelId,duplicate.request().operationId());refused(duplicate::ticket);
        assertEquals(taskCount,count("tasks"));assertEquals(runCount,count("runs"));assertEquals("unknown",originalOperationState(source));assertEquals(4,again.deviceTasks().heldBudget(source.id,source.run,true));
        refused(()->again.resolveOperation(source.id,source.operation,"not_occurred"));
        JSONObject task=again.get(source.id,false);assertNotEquals("running",task.optString("status"));
        JSONObject nextReceipt=new TaskStoreDeviceTrust(sql).outgoingGrant(caller.deviceId(),secondGrant.grantId());
        nextReceipt.remove("signature");nextReceipt.getJSONObject("body").getJSONObject("grant").put("authorizationEpoch",2);
        java.security.Signature signer=java.security.Signature.getInstance("SHA256withECDSA");signer.initSign(callerMaterial.keys.getPrivate());signer.update(DeviceProtocol.canonicalBytes(nextReceipt));nextReceipt.put("signature",java.util.Base64.getEncoder().encodeToString(signer.sign()));
        var changedEpoch=pairing.acceptOutgoingGrant(caller,nextReceipt,System.currentTimeMillis());
        refused(()->again.deviceTasks().prepareOriginalStop(second,syntheticLocalIdentity(),changedEpoch));
        var stale=again.deviceTasks().reviewOwnerStop(source.id,source.operation);
        try(Cursor row=sql.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{source.id})){
            assertTrue(row.moveToFirst());JSONObject changed=new JSONObject(row.getString(0));changed.put("coordinatorEpoch",changed.optInt("coordinatorEpoch")+1);ContentValues update=new ContentValues();update.put("document",changed.toString());sql.update("tasks",update,"task_id=?",new String[]{source.id});
        }
        refused(()->again.deviceTasks().confirmOwnerStop(stale));refused(()->again.deviceTasks().prepareOriginalStop(second,syntheticLocalIdentity(),secondGrant));
    }

    @Test public void coldOwnerStopHostTimeoutStaysUnknownAndRepeatedOwnerClickNeverResubmits()throws Exception{
        Outbound source=outbound();var held=tasks.deviceTasks().reserveChildNative(source.id,source.run,source.epoch,source.control,source.operation,source.draft);
        tasks.deviceTasks().bindReservedWire(held,DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(source.draft)),source.grant).claimForWrite(caller);
        TaskStore reopened=reopenColdStopStore();var freshGrant=coldStopGrant(source.grant.grantId());AtomicInteger sends=new AtomicInteger();
        NativeDeviceChildStopHost host=new NativeDeviceChildStopHost(reopened,syntheticLocalIdentity(),new NativeDeviceChildStopHost.NativeStops(){
            public PairingCoordinator.OutgoingGrant currentManagementGrant(String peer,String grant)throws Exception{assertEquals(caller.deviceId(),peer);assertEquals(source.grant.grantId(),grant);return freshGrant;}
            public DeviceCapabilityPort.Reply sendManagement(DeviceTaskEnvelope request,TaskStoreDeviceTasks.OutboundSendTicket ticket,RunControl io)throws Exception{
                assertEquals("operation.cancel",request.method());assertEquals(source.operation,request.arguments().getString("operationId"));assertNull(io.hostRunIdentity());ticket.claimForWrite(caller);sends.incrementAndGet();throw new java.io.IOException("synthetic lost cancellation response");
            }
        });
        var review=reopened.deviceTasks().reviewOwnerStop(source.id,source.operation);
        JSONObject first=host.stopReviewedOperation(review),second=host.stopReviewedOperation(review);
        assertTrue(first.getBoolean("unknown"));assertFalse(first.getBoolean("stopped"));assertTrue(second.getBoolean("knownOperation"));assertEquals(first.getString("operationId"),second.getString("operationId"));assertEquals(1,sends.get());assertEquals("unknown",originalOperationState(source));assertEquals(4,reopened.deviceTasks().heldBudget(source.id,source.run,true));
    }

    private static final class FakePhone implements PhonePort {
        final AtomicInteger actions = new AtomicInteger(); int frames; boolean failAfterAction,foreignOverlay;
        public boolean isConnected() { return true; }
        public JSONObject observe(boolean image, RunControl control) throws Exception {
            JSONObject result=new JSONObject().put("ok", true).put("sessionId", "native-session").put("displayId", 0).put("observationId", "native-frame-" + (++frames))
                    .put("packageName", "test.pocket.app").put("treeFingerprint", "b".repeat(64)).put("windows", new JSONArray().put(new JSONObject().put("windowId", 7).put("displayId", 0).put("packageName", "test.pocket.app")))
                    .put("nodes", new JSONArray().put(new JSONObject().put("id", "n0").put("windowId", 7).put("text", "fixture button")));
            if(foreignOverlay){result.getJSONArray("windows").put(new JSONObject().put("windowId",8).put("displayId",0).put("packageName","test.other.overlay"));result.getJSONArray("nodes").put(new JSONObject().put("id","n-hidden").put("windowId",8).put("text","foreign overlay secret"));}return result;
        }
        public JSONObject execute(String tool, JSONObject args, RunControl control) throws Exception { actions.incrementAndGet(); if (failAfterAction) throw new java.io.IOException("fixture after native effect"); return new JSONObject().put("ok", true).put("accepted", true); }
        public void clearReferences() { }
    }
    private record Peers(DeviceIdentity.AuthenticatedPeer caller, String target,Material source,Material local) { }
    private record Material(KeyPair keys, X509Certificate cert) { }
    private static Material certificate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); KeyPair keys = generator.generateKeyPair(); long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=PocketWorld Synthetic Host Fixture"); var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name, new java.math.BigInteger(120, new SecureRandom()).add(java.math.BigInteger.ONE), new Date(now-60_000), new Date(now+3_600_000), name, keys.getPublic()).build(signer));
        return new Material(keys, cert);
    }
    private static SSLContext context(Material local, Material trusted) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12"); keys.load(null); keys.setKeyEntry("fixture", local.keys.getPrivate(), "fixture-only".toCharArray(), new java.security.cert.Certificate[]{local.cert});
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, "fixture-only".toCharArray()); KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null); trust.setCertificateEntry("fixture-peer", trusted.cert); TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
        SSLContext tls = SSLContext.getInstance("TLSv1.3"); tls.init(km.getKeyManagers(), tm.getTrustManagers(), new SecureRandom()); return tls;
    }
    private static Peers tls() throws Exception {
        Material a = certificate(), b = certificate(); SSLContext serverContext = context(b,a), clientContext = context(a,b); var worker = Executors.newSingleThreadExecutor();
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setNeedClientAuth(true); var receiving = worker.submit(() -> { try (SSLSocket socket = (SSLSocket) server.accept()) { socket.startHandshake(); return DeviceIdentity.authenticatedPeer(socket.getSession()); } });
            try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(), server.getLocalPort())) { client.startHandshake(); return new Peers(receiving.get(10, TimeUnit.SECONDS), DeviceIdentity.id(b.cert),a,b); }
        } finally { worker.shutdownNow(); }
    }
}
