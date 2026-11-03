package dev.pocketengine.features.devices;

import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.devices.transport.DeviceConnection;
import dev.pocketengine.features.devices.transport.LanDeviceListener;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ModelFacts;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import dev.pocketengine.testutil.HttpServer;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreSpi;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowApplication;
import static org.junit.Assert.*;

/** Cross-language production chain: CLI MCP/Client -> Companion HTTP/router -> native mTLS ingress ->
 * original App owner review -> PhoneRunService/AgentRunner -> same-task query and answer.
 * The ordinary answer traverses an independent stdio process; Task Desk HTTP MCP reads the same result.
 * Only OS startup, temporary paired identity and model HTTP responses are explicit fixtures.
 * No physical phone, paid model, production credential or public listener is used or claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=PhoneTaskApplicationFlowTest.NoUnrelatedStartup.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneTaskApplicationFlowTest {
    @Implements(EngineApplication.class) public static class NoUnrelatedStartup extends ShadowApplication {
        @Implementation protected void onCreate(){}
    }
    private static volatile KeyStore syntheticDeviceKeys;
    public static final class FixtureKeyStore extends KeyStoreSpi {
        public Key engineGetKey(String alias,char[] password){try{return syntheticDeviceKeys.getKey(alias,null);}catch(Exception failure){throw new IllegalStateException(failure);}}
        public Certificate engineGetCertificate(String alias){try{return syntheticDeviceKeys.getCertificate(alias);}catch(Exception failure){throw new IllegalStateException(failure);}}
        public Certificate[] engineGetCertificateChain(String alias){Certificate cert=engineGetCertificate(alias);return cert==null?null:new Certificate[]{cert};}
        public boolean engineContainsAlias(String alias){try{return syntheticDeviceKeys!=null&&syntheticDeviceKeys.containsAlias(alias);}catch(Exception failure){throw new IllegalStateException(failure);}}
        public Date engineGetCreationDate(String alias){return new Date(0);}
        public void engineSetKeyEntry(String alias,Key key,char[] password,Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetKeyEntry(String alias,byte[] key,Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetCertificateEntry(String alias,Certificate cert){throw new UnsupportedOperationException();}
        public void engineDeleteEntry(String alias){throw new UnsupportedOperationException();}
        public Enumeration<String> engineAliases(){try{return syntheticDeviceKeys.aliases();}catch(Exception failure){throw new IllegalStateException(failure);}}
        public int engineSize(){return syntheticDeviceKeys==null?0:1;}
        public boolean engineIsKeyEntry(String alias){return engineContainsAlias(alias);}
        public boolean engineIsCertificateEntry(String alias){return false;}
        public String engineGetCertificateAlias(Certificate cert){return null;}
        public void engineStore(OutputStream stream,char[] password){}
        public void engineLoad(InputStream stream,char[] password){}
    }
    private EngineApplication app;
    private SQLiteDatabase sql;
    private DeviceWorkspaceNativeOwner owner;
    private DeviceReceiverServiceResource receiver;
    private ServiceController<PhoneRunService> serviceController;
    private PhoneRunService service;
    private HttpServer model;
    private ExecutorService ingressWorkers,processOutput;
    private Process python;
    private Path temporary;
    private int serviceStartId;
    private String diagnosticTaskId;
    private final BlockingQueue<String> lines=new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<String> modelBodies=new CopyOnWriteArrayList<>();
    private final AtomicInteger modelRequests=new AtomicInteger();

    @After public void close()throws Exception{
        if(python!=null&&python.isAlive()){python.destroy();if(!python.waitFor(3,TimeUnit.SECONDS))python.destroyForcibly();}
        if(receiver!=null)receiver.close();
        if(app!=null&&app.activeId()!=null){RunControl original=app.control(app.activeId());if(original!=null)original.cancel();}
        if(serviceController!=null){set(service,"runningId",null);set(service,"runningRunId",null);serviceController.destroy();}
        if(owner!=null)owner.close();
        if(ingressWorkers!=null)ingressWorkers.shutdownNow();
        if(processOutput!=null)processOutput.shutdownNow();
        if(model!=null)model.stop(0);
        if(app!=null){app.onTerminate();if(app.features!=null)app.features.close();}
        if(sql!=null&&sql.isOpen())sql.close();
        Security.removeProvider("PhoneTaskSyntheticKeyStore");syntheticDeviceKeys=null;
        if(temporary!=null)try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private Path reportPath;
    private record Scenario(String taskId,String sourceDeviceId,String targetDeviceId){}
    private record Started(String runId,int epoch,String allocationId,RunControl original){}
    private Scenario startScenario(String scenario)throws Exception{
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.tasks=new TaskStore(app);
        sql=(SQLiteDatabase)get(app.tasks,"db");app.features=new FeatureHost(event->{});app.settings=new SettingsStore(app);
        // Mirror the original onCreate selector's volatile reads. The public
        // convenience getters take App's monitor, which is forbidden while the
        // original TaskStore invokes this identity lookup under its own monitor.
        EngineApplication originalApp=app;Field activeTask=EngineApplication.class.getDeclaredField("activeTaskId"),activeControl=EngineApplication.class.getDeclaredField("activeControl");
        activeTask.setAccessible(true);activeControl.setAccessible(true);
        set(app,"deviceOwnerInputs",new DeviceOwnerInputs(app.tasks,id->{
            DeviceGoalSession session=originalApp.originalDeviceGoalSession(id);
            try{return session!=null&&session.originalControl()==activeControl.get(originalApp)&&id.equals(activeTask.get(originalApp))?session:null;}
            catch(IllegalAccessException unavailable){throw new AssertionError("Original native selector fields unavailable",unavailable);}
        }));
        temporary=Files.createTempDirectory("phone-task-native-fixture-");Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rwx------"));
        Material source=certificate(),target=certificate();String sourceInstance=UUID.randomUUID().toString(),targetInstance=UUID.randomUUID().toString();
        installLocalIdentity(target,targetInstance);
        TaskStoreDeviceTrust trust=new TaskStoreDeviceTrust(sql);
        JSONObject binding=new JSONObject().put("screenMode","capabilities");long now=System.currentTimeMillis(),deadline=now+180000;
        JSONObject nativeGrant=new JSONObject().put("grantId","native-phone-fixture-grant").put("peerDeviceId",DeviceIdentity.id(source.cert)).put("localDeviceId",DeviceIdentity.id(target.cert))
                .put("authorizationEpoch",1).put("issuedAt",now-1000).put("expiresAt",deadline)
                .put("capabilities",new JSONArray().put("device.describe").put("task.submitGoal").put("task.get").put("task.events").put("operation.get").put("task.acquireControl").put("task.answerQuestion").put("task.steer").put("task.requestResume").put("task.cancel").put("operation.cancel"))
                .put("toolNames",new JSONArray()).put("resources",new JSONArray().put(binding)).put("allowedOrigins",new JSONArray().put(DeviceIdentity.id(source.cert)))
                .put("allowDelegation",false).put("maxDelegationDepth",0).put("toolBudget",8).put("modelBudget",4);
        ContentValues peer=new ContentValues();peer.put("device_id",DeviceIdentity.id(source.cert));peer.put("certificate_der",source.cert.getEncoded());peer.put("fingerprint",DeviceProtocol.sha256(source.cert.getEncoded()));peer.put("instance_id",sourceInstance);peer.put("authorization_epoch",1);peer.put("revoked",0);peer.put("updated_at",now);sql.insertOrThrow("device_peers",null,peer);
        ContentValues grant=new ContentValues();grant.put("grant_id",nativeGrant.getString("grantId"));grant.put("peer_device_id",DeviceIdentity.id(source.cert));grant.put("local_device_id",DeviceIdentity.id(target.cert));grant.put("authorization_epoch",1);grant.put("expires_at",deadline);grant.put("body_json",nativeGrant.toString());grant.put("revoked",0);sql.insertOrThrow("device_grants",null,grant);
        startModel();
        app.getSharedPreferences("private_settings",0).edit().clear().putString("baseUrl","http://127.0.0.1:"+model.getAddress().getPort()).putString("model","native-phone-fixture").putString("provider","openai_chat").putBoolean("sendScreenshots",false).putInt("maxSteps",8).putInt("requestTimeoutSeconds",20).commit();
        installSyntheticModelCatalog();
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        serviceController=Robolectric.buildService(PhoneRunService.class).create();service=serviceController.get();
        ingressWorkers=Executors.newFixedThreadPool(2);owner=new DeviceWorkspaceNativeOwner(app,trust,ingressWorkers);
        DeviceIngress[] ingress=new DeviceIngress[1];LanDeviceListener[] listeners=new LanDeviceListener[2];AtomicBoolean enabled=new AtomicBoolean(true);
        SSLContext tls=mutualContext(target,source);
        receiver=new DeviceReceiverServiceResource(new DeviceReceiverServiceResource.Backend(){
            public void start()throws Exception{
                listeners[0]=new LanDeviceListener(true,new InetSocketAddress(InetAddress.getLoopbackAddress(),0),tls,DeviceConnection.Lane.CONTROL,socket->new DeviceConnection.Listener(){
                    private DeviceConnection exact;
                    public void onConnection(DeviceConnection connection,DeviceIdentity.AuthenticatedPeer actual){exact=connection;}
                    public void onOnline(DeviceIdentity.AuthenticatedPeer actual){}
                    public void onControl(DeviceIdentity.AuthenticatedPeer actual,byte[] bytes)throws Exception{
                        JSONObject response=ingress[0].receive(actual,bytes);
                        if(!exact.sendControl(DeviceProtocol.canonicalBytes(response)))throw new IllegalStateException("Original native response queue is full");
                    }
                    public void onChunk(DeviceIdentity.AuthenticatedPeer actual,UUID transfer,long offset,byte[] bytes){throw new AssertionError("Control cannot import artifact bytes");}
                    public void onClosed(DeviceConnection.State state,boolean unknown){}
                });
                listeners[1]=new LanDeviceListener(true,new InetSocketAddress(InetAddress.getLoopbackAddress(),0),tls,DeviceConnection.Lane.BULK,socket->new DeviceConnection.Listener(){
                    public void onOnline(DeviceIdentity.AuthenticatedPeer actual){}
                    public void onControl(DeviceIdentity.AuthenticatedPeer actual,byte[] bytes){throw new AssertionError("No bulk control fixture");}
                    public void onChunk(DeviceIdentity.AuthenticatedPeer actual,UUID transfer,long offset,byte[] bytes){throw new AssertionError("No artifact fixture");}
                    public void onClosed(DeviceConnection.State state,boolean unknown){}
                });
            }
            public boolean isListening(){return listeners[0]!=null&&listeners[1]!=null&&listeners[0].isListening()&&listeners[1].isListening();}
            public void close()throws Exception{for(LanDeviceListener listener:listeners)if(listener!=null)listener.close();}
        },new DeviceReceiverServiceResource.OwnerPolicy(){public void requireEnabledCurrent(){if(!enabled.get())throw new SecurityException("Fixture owner disabled receiver");}public void disableByOwner(){enabled.set(false);}});
        ingress[0]=owner.ingress(receiver);receiver.start();
        JSONObject receipt=owner.pairing().issueGrantReceipt(owner.existingIdentity(),nativeGrant.getString("grantId"),System.currentTimeMillis());
        Path report=temporary.resolve("report.json");reportPath=report;
        JSONObject manifest=new JSONObject().put("format","pocketworld.phone-native-fixture.v1").put("host","127.0.0.1").put("port",listeners[0].port())
                .put("sourceDeviceId",DeviceIdentity.id(source.cert)).put("sourceInstanceId",sourceInstance).put("targetDeviceId",DeviceIdentity.id(target.cert)).put("targetInstanceId",targetInstance)
                .put("sourceCertificatePem",pem("source-cert.pem","CERTIFICATE",source.cert.getEncoded()).toString()).put("sourcePrivateKeyPem",pem("source-key.pem","PRIVATE KEY",source.keys.getPrivate().getEncoded()).toString())
                .put("targetCertificatePem",pem("target-cert.pem","CERTIFICATE",target.cert.getEncoded()).toString()).put("grantReceipt",receipt).put("resourceBinding",binding)
                .put("scenario",scenario).put("capabilityVersion","1.0").put("goal","Ask which exact fixture section to use, then finish with its answer.").put("answer","NATIVE-PHONE-EXACT-ANSWER").put("reportPath",report.toString());
        Path manifestPath=temporary.resolve("manifest.json");Files.write(manifestPath,manifest.toString().getBytes(StandardCharsets.UTF_8));Files.setPosixFilePermissions(manifestPath,PosixFilePermissions.fromString("rw-------"));
        Path checkout=checkout();python=new ProcessBuilder("python3","-B",checkout.resolve("companion/phone_task_native_fixture.py").toString(),"--manifest",manifestPath.toString()).directory(checkout.toFile()).redirectErrorStream(true).start();
        processOutput=Executors.newSingleThreadExecutor();processOutput.submit(()->{try(BufferedReader reader=new BufferedReader(new InputStreamReader(python.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)lines.add(line);}catch(Exception failure){lines.add("fixture output unavailable: "+failure.getClass().getSimpleName());}});
        JSONObject submitted=phase("submitted",45);String id=submitted.getString("taskId");diagnosticTaskId=id;
        JSONObject waiting=app.tasks.get(id,false);assertEquals("awaiting_user",waiting.getString("status"));assertTrue(waiting.optString("runId").isEmpty());assertTrue(app.hasPendingDeviceGoal(id));assertEquals(0,modelRequests.get());assertEquals(0,count("runs"));
        return new Scenario(id,DeviceIdentity.id(source.cert),DeviceIdentity.id(target.cert));
    }
    private void installSyntheticModelCatalog()throws Exception{
        // Re-enter the same production mounting skipped by the isolated onCreate
        // shadow: original catalog, owner TaskStore, gateway policy and probe host.
        var initialize=EngineApplication.class.getDeclaredMethod("initializeModelConnections");initialize.setAccessible(true);initialize.invoke(app);
        assertNotNull("Original model connection host did not initialize",app.modelConnections);
        app.modelConnections.connections();
        app.modelConnections.configure(null,ModelBinding.Format.OPENAI,java.net.URI.create(app.settings.apiConfigForCatalog().baseUrl+"/models"));
        var selected=app.settings.apiConfigForCatalog();ModelBinding binding=selected.capabilityBinding;
        // These are explicitly native-owner facts for this synthetic HTTP model,
        // not a fabricated provider fetch/probe result or a request-policy bypass.
        app.modelConnections.editField(binding,selected.model,0,ModelFacts.Field.CHAT_OUTPUT_PARAMETER,ModelFacts.OwnerValue.known(ModelFacts.ChatOutputParameter.MAX_TOKENS),false);
        app.modelConnections.editField(binding,selected.model,1,ModelFacts.Field.TOOL_CALLS,ModelFacts.OwnerValue.known(true),false);
        assertTrue(app.settings.get().catalogEnforced);assertTrue(app.settings.matchesApiCatalogRoute(app.settings.get()));
        assertEquals(ModelFacts.Source.OWNER_OVERRIDE,app.modelConnections.resolve(app.settings.get()).facts().toolCalls().source);
        ModelGateway.validate(app.settings.get());assertEquals(0,modelRequests.get());
    }
    private Started approve(Scenario scenario)throws Exception{
        // This is the native owner action used by the phone task UI, not a network grant.
        try(NativeDeviceWorkspaceHost.Window window=owner.host().openOwnerWindow()){
            NativeDeviceWorkspaceHost.GoalReview reviewed=window.reviewGoal(scenario.taskId);window.confirmGoal(reviewed,false);
        }
        JSONObject started=app.tasks.get(scenario.taskId,false);
        assertNotNull(app.originalDeviceGoalSession(scenario.taskId));assertEquals(1,count("runs"));
        requireLockFreeOriginalInputView(started);
        return new Started(started.getString("runId"),started.getInt("coordinatorEpoch"),started.getJSONObject("deviceExecutionScope").getString("allocationId"),app.control(scenario.taskId));
    }
    private void requireLockFreeOriginalInputView(JSONObject capturedTask)throws Exception{
        // Capture actual SQL before taking App's monitor. The worker only reads
        // the real input view; no TaskStore/Session monitor or task write is needed.
        ExecutorService probe=Executors.newSingleThreadExecutor();
        try{
            synchronized(app){
                JSONObject view=probe.submit(()->app.deviceOwnerInputs().view(capturedTask)).get(2,TimeUnit.SECONDS);
                assertTrue(view.getBoolean("originalSessionPresent"));assertEquals(capturedTask.getString("id"),view.getString("taskId"));
                assertEquals(capturedTask.getString("runId"),view.getString("runId"));assertEquals(capturedTask.getInt("coordinatorEpoch"),view.getInt("executorEpoch"));
            }
        }finally{probe.shutdownNow();}
    }
    private JSONObject completeReport()throws Exception{
        JSONObject finalLine=phase("complete",60);
        assertTrue("Python client did not finish",python.waitFor(5,TimeUnit.SECONDS));assertEquals("Native bridge client failed: "+finalLine,0,python.exitValue());
        return new JSONObject(new String(Files.readAllBytes(reportPath),StandardCharsets.UTF_8));
    }
    private void signal(String command)throws Exception{
        byte[] bytes=(new JSONObject().put("command",command).toString()+"\n").getBytes(StandardCharsets.UTF_8);
        python.getOutputStream().write(bytes);python.getOutputStream().flush();
    }
    private static void sameOriginal(Scenario scenario,Started started,JSONObject actual)throws Exception{
        assertEquals(scenario.taskId,actual.getString("taskId"));assertEquals(started.runId,actual.getString("runId"));assertEquals(started.epoch,actual.getInt("executorEpoch"));assertEquals(started.allocationId,actual.getString("allocationId"));
        assertEquals(scenario.targetDeviceId,actual.getString("actualPeerDeviceId"));
    }
    private JSONObject requireOriginalNativeResult(Scenario scenario,Started started)throws Exception{
        JSONObject task=app.tasks.get(scenario.taskId,false),result=task.getJSONObject("deviceGoalResult");
        assertEquals("needs_confirmation",task.getString("status"));assertFalse(task.optBoolean("cancelRequested"));
        assertSame(started.original,app.control(scenario.taskId));assertFalse(started.original.isCancelled());assertTrue(started.original.isPaused());
        DeviceGoalSession session=app.originalDeviceGoalSession(scenario.taskId);assertNotNull(session);assertSame(started.original,session.originalControl());assertFalse(session.ownerResultClosed());
        assertEquals(scenario.taskId,session.taskId());assertEquals(started.runId,task.getString("runId"));assertEquals(started.epoch,task.getInt("coordinatorEpoch"));
        assertEquals(started.allocationId,task.getJSONObject("deviceExecutionScope").getString("allocationId"));
        assertTrue(result.getBoolean("nativeRunnerExited"));assertTrue(result.getBoolean("nativeDispatchClosed"));assertFalse(result.getBoolean("closed"));
        assertEquals(started.runId,result.getString("runId"));assertEquals(started.epoch,result.getInt("executorEpoch"));
        assertTrue(result.getString("summary").contains("NATIVE-PHONE-RESULT"));assertEquals(result.getString("summary"),task.getString("summary"));
        // Read the original native result review; do not mark it confirmed or
        // construct a Finished object, completion receipt, new grant or run.
        assertEquals(started.runId,app.tasks.deviceTasks().reviewGoalResult(scenario.taskId,started.runId,started.epoch).runId());
        assertThrows(SecurityException.class,()->app.tasks.taskExecutionDeadline(scenario.taskId,started.runId,started.epoch));
        assertThrows(SecurityException.class,()->session.modelApproval().checkCurrent(started.original));
        return task;
    }
    private void awaitStableOriginalNativeResult(Scenario scenario,Started started)throws Exception{
        long readyBy=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<readyBy){
            pumpServices();JSONObject task=app.tasks.get(scenario.taskId,false),proof=task.optJSONObject("deviceGoalResult");
            assertFalse("Original run cancelled while publishing its native result",started.original.isCancelled());
            if(proof!=null&&proof.optBoolean("nativeRunnerExited")&&proof.optBoolean("nativeDispatchClosed")&&started.original.isPaused())break;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        JSONObject original=requireOriginalNativeResult(scenario,started);String result=DeviceProtocol.canonical(original.getJSONObject("deviceGoalResult"));
        long revision=original.getLong("deviceStateRevision"),until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
        // Exercise the real one-second lease watchdog after native completion.
        // Its old deadline fence cancelled needs_confirmation on the next tick.
        do{
            pumpServices();JSONObject current=requireOriginalNativeResult(scenario,started);
            assertEquals(result,DeviceProtocol.canonical(current.getJSONObject("deviceGoalResult")));assertEquals(original.getString("summary"),current.getString("summary"));
            assertTrue("Native result revision regressed",current.getLong("deviceStateRevision")>=revision);revision=current.getLong("deviceStateRevision");
            TimeUnit.MILLISECONDS.sleep(20);
        }while(System.nanoTime()<until);
        assertEquals(2,modelRequests.get());assertEquals(1,count("runs"));signal("original-result-stable");
    }
    @Test public void externalHttpClientReachesOriginalPhoneApprovalRunnerAndSameTaskResult()throws Exception{
        Scenario scenario=startScenario("happy");Started started=approve(scenario);JSONObject resultObserved=phase("result_observed",60);
        assertEquals("phone-api-native-submit",resultObserved.getString("operationId"));assertEquals(scenario.taskId,resultObserved.getString("taskId"));assertEquals(started.runId,resultObserved.getString("runId"));assertEquals(started.epoch,resultObserved.getInt("executorEpoch"));
        awaitStableOriginalNativeResult(scenario,started);JSONObject actual=completeReport();sameOriginal(scenario,started,actual);
        assertTrue(actual.getBoolean("nativeResultBarrierAcknowledged"));requireOriginalNativeResult(scenario,started);
        assertEquals(2,modelRequests.get());assertTrue(modelBodies.get(1).contains("NATIVE-PHONE-EXACT-ANSWER"));
        JSONObject done=app.tasks.get(scenario.taskId,false),result=done.getJSONObject("deviceGoalResult");assertEquals("needs_confirmation",done.getString("status"));assertTrue(result.getBoolean("nativeRunnerExited"));assertTrue(result.getBoolean("nativeDispatchClosed"));assertEquals(started.runId,result.getString("runId"));assertEquals(started.epoch,result.getInt("executorEpoch"));
        assertEquals(2,done.getJSONObject("deviceExecutionScope").getInt("modelCallsUsed"));assertEquals(started.allocationId,done.getJSONObject("deviceExecutionScope").getString("allocationId"));assertEquals(1,count("runs"));assertEquals(1,done.getJSONArray("ownerInputs").length());
        assertEquals(DeviceProtocol.canonical(actual.getJSONObject("initialReceipt")),DeviceProtocol.canonical(actual.getJSONObject("duplicateReceipt")));
        assertEquals(DeviceProtocol.canonical(actual.getJSONObject("answerReceipt")),DeviceProtocol.canonical(actual.getJSONObject("duplicateAnswerReceipt")));
        assertEquals("phone-api-native-submit",actual.getJSONObject("duplicateReceipt").getString("operationId"));
        assertEquals(scenario.taskId,actual.getJSONObject("duplicateReceipt").getString("taskId"));
        JSONObject controlled=actual.getJSONObject("controlSnapshot").getJSONObject("task"),projectedLease=controlled.getJSONObject("result").getJSONObject("controlLease"),controlReceipt=actual.getJSONObject("controlReceipt");
        assertEquals(controlReceipt.getString("controlLeaseId"),projectedLease.getString("controlLeaseId"));assertEquals(controlReceipt.getLong("controlLeaseExpiresAt"),projectedLease.getLong("expiresAt"));
        assertEquals(scenario.taskId,controlled.getString("taskId"));assertEquals(started.runId,controlled.getString("runId"));assertEquals(started.epoch,controlled.getInt("executorEpoch"));
        assertEquals(started.allocationId,controlled.getJSONObject("result").getJSONObject("ownerInput").getString("allocationId"));assertTrue(controlled.getLong("revision")>actual.getJSONObject("questionSnapshot").getLong("revision"));
        assertEquals(DeviceProtocol.canonical(actual.getJSONObject("questionSnapshot").getJSONObject("result").getJSONObject("ownerInput").getJSONObject("ordinaryQuestion")),DeviceProtocol.canonical(controlled.getJSONObject("result").getJSONObject("ownerInput").getJSONObject("ordinaryQuestion")));
        JSONObject desk=actual.getJSONObject("deskNativeContract");assertEquals("pocketworld.task-desk-native-contract.v1",desk.getString("format"));assertTrue(desk.getBoolean("answerEnabled"));assertTrue(desk.getBoolean("requestMatchesNativeScope"));assertFalse(desk.getBoolean("nativeDispatchPerformed"));assertEquals(1,desk.getInt("capturedAnswerRequests"));
        JSONObject stdio=actual.getJSONObject("mcpStdio"),http=actual.getJSONObject("mcpHttp"),stdioAnswer=stdio.getJSONObject("result");
        assertEquals("2025-11-25",stdio.getString("protocolVersion"));assertEquals(stdio.getString("protocolVersion"),http.getString("protocolVersion"));
        assertEquals(0,stdio.getInt("processExitCode"));assertEquals("phone_task_answer",stdio.getString("tool"));assertEquals("phone_task_status",http.getString("tool"));
        assertEquals(scenario.targetDeviceId,stdio.getString("selectedPeerDeviceId"));assertEquals(scenario.targetDeviceId,http.getString("selectedPeerDeviceId"));
        assertEquals(DeviceProtocol.canonical(actual.getJSONObject("answerReceipt")),DeviceProtocol.canonical(stdioAnswer));
        assertEquals("phone-api-native-answer",stdioAnswer.getString("operationId"));assertEquals("phone-api-native-submit",stdioAnswer.getString("targetOperationId"));
        assertEquals(scenario.taskId,stdioAnswer.getString("taskId"));assertEquals(started.runId,stdioAnswer.getString("runId"));assertEquals(started.epoch,stdioAnswer.getInt("executorEpoch"));assertEquals(started.allocationId,stdioAnswer.getString("allocationId"));
        for(JSONObject observed:new JSONObject[]{http.getJSONObject("result"),actual.getJSONObject("finalStatus")}){
            assertEquals("phone-api-native-submit",observed.getString("operationId"));assertEquals(scenario.targetDeviceId,observed.getString("peerDeviceId"));assertEquals(scenario.taskId,observed.getString("taskId"));
            JSONObject snapshot=observed.getJSONObject("task");assertEquals(scenario.targetDeviceId,snapshot.getString("executorDeviceId"));assertEquals(actual.getJSONObject("questionSnapshot").getString("instanceId"),snapshot.getString("instanceId"));
            assertEquals(scenario.taskId,snapshot.getString("taskId"));assertEquals(started.runId,snapshot.getString("runId"));assertEquals(started.epoch,snapshot.getInt("executorEpoch"));
            assertTrue(snapshot.getJSONObject("result").getString("summary").contains("NATIVE-PHONE-RESULT"));
        }
        assertTrue(actual.getBoolean("deskClosedPhoneResultAvailable"));
    }
    @Test public void phoneOwnerDenialAndWrongDeviceCannotLaunchTheAcceptedGoal()throws Exception{
        Scenario scenario=startScenario("owner_denied");
        try(NativeDeviceWorkspaceHost.Window window=owner.host().openOwnerWindow()){window.cancelPendingGoal(scenario.taskId);}
        signal("owner-denied");JSONObject actual=completeReport();
        assertTrue(actual.getBoolean("wrongPhoneRejected"));assertFalse(actual.getBoolean("runStarted"));assertEquals("cancelled",actual.getString("actualWireState"));
        JSONObject task=app.tasks.get(scenario.taskId,false);assertEquals("cancelled",task.getString("status"));assertTrue(task.optString("runId").isEmpty());assertFalse(app.hasPendingDeviceGoal(scenario.taskId));
        assertEquals(0,count("runs"));assertEquals(0,modelRequests.get());assertEquals(0,task.optJSONArray("ownerInputs")==null?0:task.getJSONArray("ownerInputs").length());
        assertEquals(scenario.taskId,actual.getJSONObject("repeatedAfterDenial").getString("taskId"));assertEquals("phone-api-native-submit",actual.getJSONObject("repeatedAfterDenial").getString("operationId"));
    }
    @Test public void actualReconnectExternalCancelAndNativeRevocationNeverDispatchAnOldAnswer()throws Exception{
        Scenario scenario=startScenario("reconnect_cancelled");Started started=approve(scenario);
        JSONObject reconnected=phase("reconnected",45);assertEquals(scenario.taskId,reconnected.getString("taskId"));assertEquals(started.runId,reconnected.getString("runId"));
        assertEquals("awaiting_user",app.tasks.get(scenario.taskId,false).getString("status"));assertEquals(1,modelRequests.get());assertEquals(1,count("runs"));
        // The Python client, not a local stop shortcut, now sends the original operation.cancel.
        signal("cancel-original");phase("cancel_verified",45);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(app.originalDeviceGoalSession(scenario.taskId)!=null&&System.nanoTime()<until){pumpServices();Thread.sleep(10);}
        assertTrue(started.original.isCancelled());assertNull(app.originalDeviceGoalSession(scenario.taskId));
        JSONObject stopped=app.tasks.get(scenario.taskId,false);assertEquals("cancelled",stopped.getString("status"));assertEquals(started.runId,stopped.getString("runId"));assertEquals(0,stopped.getJSONArray("ownerInputs").length());
        long acceptedCancels=cancelOperations(scenario.taskId);assertEquals(1,acceptedCancels);
        signal("original-stop-confirmed");phase("cancel_reconciled",30);assertEquals(acceptedCancels,cancelOperations(scenario.taskId));
        try(NativeDeviceWorkspaceHost.Window window=owner.host().openOwnerWindow()){window.revoke(scenario.sourceDeviceId);}
        assertTrue(owner.trust.peer(scenario.sourceDeviceId).getBoolean("revoked"));signal("owner-revoked");
        JSONObject actual=completeReport();sameOriginal(scenario,started,actual);
        assertTrue(actual.getBoolean("wrongPhoneRejected"));assertTrue(actual.getBoolean("answerAfterCancelRejected"));
        assertEquals("unavailable",actual.getJSONObject("offlineStatus").getString("currentRead"));assertEquals("unavailable",actual.getJSONObject("revokedReadStatus").getString("currentRead"));
        assertEquals("phone-api-native-submit",actual.getJSONObject("reconnectedStatus").getString("operationId"));
        JSONObject firstCancel=actual.getJSONObject("cancelReceipt"),reconciled=actual.getJSONObject("reconciledCancelStatus"),latestCancellation=reconciled.getJSONObject("cancellation");
        assertEquals("phone-api-native-cancel",reconciled.getString("operationId"));assertEquals(firstCancel.getString("operationId"),reconciled.getString("operationId"));assertEquals(scenario.taskId,reconciled.getString("taskId"));
        assertEquals(firstCancel.getJSONObject("cancellation").getString("targetOperationId"),latestCancellation.getString("targetOperationId"));assertEquals(firstCancel.getJSONObject("cancellation").getString("targetArgumentsDigest"),latestCancellation.getString("targetArgumentsDigest"));
        assertEquals(firstCancel.getJSONObject("cancellation").getBoolean("stopped"),actual.getBoolean("cancelAcknowledgementStopped"));assertTrue(latestCancellation.getBoolean("requested"));assertTrue(latestCancellation.getBoolean("stopped"));
        assertEquals("cancelled",actual.getString("actualWireState"));assertEquals(1,modelRequests.get());assertEquals(1,count("runs"));
        assertEquals(1,app.tasks.get(scenario.taskId,false).getJSONObject("deviceExecutionScope").getInt("modelCallsUsed"));
    }
    private long cancelOperations(String taskId)throws Exception{
        try(Cursor row=sql.rawQuery("SELECT COUNT(*) FROM device_inbound WHERE local_task_id=? AND method='operation.cancel'",new String[]{taskId})){assertTrue(row.moveToFirst());return row.getLong(0);}
    }

    private JSONObject phase(String expected,int timeoutSeconds)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);StringBuilder diagnostic=new StringBuilder();
        while(System.nanoTime()<end){
            pumpServices();String line=lines.poll(20,TimeUnit.MILLISECONDS);
            if(line!=null)try{JSONObject event=new JSONObject(line);if(diagnostic.length()<12000)diagnostic.append(safePhaseFact(event)).append('\n');if(expected.equals(event.optString("phase"))||expected.equals("complete")&&event.optString("phase").matches("complete|completed|final"))return event;}catch(org.json.JSONException ignored){/* Unknown stdout/stderr bodies are not diagnostic facts. */}
            if(!python.isAlive()&&lines.isEmpty())break;
        }
        fail("Native Python phase "+expected+" missing. Phase facts: "+diagnostic+"; native state="+nativeWaitMetadata()+"; model requests="+modelRequests.get());return null;
    }
    private static JSONObject safePhaseFact(JSONObject event)throws Exception{
        var phases=java.util.Set.of("submitted","reconnected","cancel_verified","cancel_reconciled","result_observed","complete","completed","final","failed","diagnostic");
        var stages=java.util.Set.of("ordinary_question_wait","ordinary_question_first_snapshot","ordinary_question_observed","original_control_acquired","reconnect_close_requested","reconnect_closed","offline_snapshot_observed","reconnect_open_requested","reconnect_opened","reconnect_snapshot_observed");
        JSONObject safe=new JSONObject().put("phase",phases.contains(event.optString("phase"))?event.optString("phase"):"unrecognized");if(stages.contains(event.optString("stage")))safe.put("stage",event.getString("stage"));
        for(String key:new String[]{"taskPresent","currentReadUnavailable","phoneStateUnconfirmed","originalSessionPresent","ordinaryQuestionPresent"})if(event.opt(key) instanceof Boolean)safe.put(key,event.getBoolean(key));
        if(event.has("taskState"))safe.put("taskState",safeTaskState(event.optString("taskState")));return safe;
    }
    private static String safeTaskState(String state){return java.util.Set.of("preparing","running","pausing","paused","awaiting_user","needs_confirmation","cancelled","failed","completed","expired","interrupted").contains(state)?state:"unknown";}
    private String nativeWaitMetadata(){
        // Timeout diagnostics must not try to acquire the very App/SQL monitors
        // being diagnosed, and never include answer text, model config or grants.
        try{
            String task=(String)get(app,"activeTaskId");RunControl control=(RunControl)get(app,"activeControl");
            DeviceGoalSession session=task==null?null:app.originalDeviceGoalSession(task);
            return new JSONObject().put("pythonAlive",python!=null&&python.isAlive()).put("reportPresent",reportPath!=null&&Files.isRegularFile(reportPath))
                    .put("taskKnown",diagnosticTaskId!=null).put("activeTaskPresent",task!=null).put("activeTaskMatches",diagnosticTaskId!=null&&diagnosticTaskId.equals(task)).put("controlPresent",control!=null)
                    .put("cancelled",control!=null&&control.isCancelled()).put("paused",control!=null&&control.isPaused())
                    .put("originalSessionPresent",session!=null).put("sameOriginalControl",session!=null&&session.originalControl()==control).toString();
        }catch(Exception unavailable){return "metadata_unavailable";}
    }
    private void pumpServices()throws Exception{
        Shadows.shadowOf(Looper.getMainLooper()).idle();Intent intent;
        while((intent=Shadows.shadowOf(app).getNextStartedService())!=null){
            assertEquals(PhoneRunService.class.getName(),intent.getComponent().getClassName());service.onStartCommand(intent,0,++serviceStartId);
        }
    }
    private void startModel()throws Exception{
        model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/",exchange->{try{
            modelBodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int call=modelRequests.incrementAndGet();
            JSONObject message=call==1?new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","native-phone-question").put("type","function").put("function",new JSONObject().put("name","run_ask_user").put("arguments",new JSONObject().put("question","Which exact native fixture section?").toString()))))
                    :new JSONObject().put("role","assistant").put("content","NATIVE-PHONE-RESULT");
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",call==1?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});model.start();
    }
    private void installLocalIdentity(Material target,String instance)throws Exception{
        syntheticDeviceKeys=KeyStore.getInstance("PKCS12");syntheticDeviceKeys.load(null);Field alias=DeviceIdentity.class.getDeclaredField("ALIAS");alias.setAccessible(true);
        syntheticDeviceKeys.setKeyEntry((String)alias.get(null),target.keys.getPrivate(),null,new Certificate[]{target.cert});
        Security.addProvider(new java.security.Provider("PhoneTaskSyntheticKeyStore",1.0,"Synthetic native phone fixture"){{put("KeyStore.AndroidKeyStore",FixtureKeyStore.class.getName());}});
        Files.write(app.getNoBackupFilesDir().toPath().resolve("device-instance-v1"),instance.getBytes(StandardCharsets.UTF_8));
    }
    private Path pem(String filename,String kind,byte[] bytes)throws Exception{
        Path path=temporary.resolve(filename);Files.write(path,("-----BEGIN "+kind+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+kind+"-----\n").getBytes(StandardCharsets.UTF_8));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));return path;
    }
    private static Path checkout(){
        Path current=Path.of(System.getProperty("user.dir")).toAbsolutePath();while(current!=null){if(Files.isRegularFile(current.resolve("companion/phone_task_native_fixture.py")))return current;current=current.getParent();}throw new AssertionError("Original companion fixture entrypoint missing from checkout");
    }
    private long count(String table){try(Cursor rows=sql.rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(rows.moveToFirst());return rows.getLong(0);}}
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private record Material(KeyPair keys,X509Certificate cert){}
    private static Material certificate()throws Exception{
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));KeyPair keys=generator.generateKeyPair();long now=System.currentTimeMillis();X500Name subject=new X500Name("CN=PocketWorld Native Phone Synthetic Fixture");
        X509Certificate cert=new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(subject,new java.math.BigInteger(120,new SecureRandom()).add(java.math.BigInteger.ONE),new Date(now-60000),new Date(now+3600000),subject,keys.getPublic()).build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));return new Material(keys,cert);
    }
    private static SSLContext mutualContext(Material local,Material peer)throws Exception{
        KeyStore keys=KeyStore.getInstance("PKCS12");keys.load(null);keys.setKeyEntry("fixture",local.keys.getPrivate(),"fixture-only".toCharArray(),new Certificate[]{local.cert});KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"fixture-only".toCharArray());
        KeyStore trusted=KeyStore.getInstance(KeyStore.getDefaultType());trusted.load(null);trusted.setCertificateEntry("peer",peer.cert);TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trusted);SSLContext tls=SSLContext.getInstance("TLSv1.3");tls.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());return tls;
    }
}
