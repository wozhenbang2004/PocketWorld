package dev.pocketengine.features.devices;

import android.graphics.Bitmap;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.devices.transport.DeviceArtifactFetch;
import dev.pocketengine.features.devices.transport.DeviceArtifactSession;
import dev.pocketengine.features.devices.transport.DeviceArtifactTransfer;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Actual EngineApplication outer factory -> original SQL -> verified cache bytes/Bitmap -> Runner ->
 * controlled HTTP image request. OS startup and host-only SecretVault keys are explicit fixtures.
 * Remote desktop execution is an EXPLICIT authenticated native
 * receipt fixture, not a real PC/Windows capture, remote SQL acceptance or cross-network AC. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=35,manifest=Config.NONE,application=dev.pocketengine.EngineApplication.class,shadows=DeviceVisualDeliveryTest.NoUnrelatedStartup.class)
public final class DeviceVisualDeliveryTest {
    // App startup/resource packaging is a separate gate. Use the real app and original
    // feature composition deterministically; the actual task/admission gates stay installed.
    @org.robolectric.annotation.Implements(dev.pocketengine.EngineApplication.class)
    public static class NoUnrelatedStartup extends org.robolectric.shadows.ShadowApplication {
        @org.robolectric.annotation.Implementation protected void onCreate(){}
    }
    private dev.pocketengine.EngineApplication fixtureApplication;
    private void prepareApplication() throws Exception {
        var app = (dev.pocketengine.EngineApplication)org.robolectric.RuntimeEnvironment.getApplication();
        fixtureApplication = app;
        app.deleteDatabase("pocket-world.db");
        app.tasks = new TaskStore(app);
        app.settings = new dev.pocketengine.store.SettingsStore(app);
        invoke(app, "initializeModelConnections");
        invoke(app, "initializeFeatures");
        dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);
        RunControl.installHostAdmission((original,id,run,epoch)->{
            if(!app.businessReady())throw new SecurityException("Fixture application is not ready");
            return app.tasks.admitExecutionControl(original,id,run,epoch);
        });
        dev.pocketengine.runtime.usage.InferenceCapture.installBudgetAttribution((capture,identity)->{
            if(!app.businessReady())throw new SecurityException("Fixture application is not ready");
            app.tasks.requireTokenBudgetAttribution(capture,identity);
        });
    }
    @org.junit.Before public void keys(){java.security.Security.addProvider(new java.security.Provider("DeviceJourneyKeyStore",1.0,"Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",FixtureKeyStore.class.getName());}});}
    @org.junit.After public void removeKeys(){if(fixtureApplication!=null){fixtureApplication.onTerminate();if(fixtureApplication.features!=null)fixtureApplication.features.close();}java.security.Security.removeProvider("DeviceJourneyKeyStore");}
    // Explicit host-only SecretVault key fixture, identical to ChannelTaskHostTest.
    // This proves real encryption paths but makes no Android hardware-keystore claim.
    public static final class FixtureKeyStore extends java.security.KeyStoreSpi {
        private static final javax.crypto.SecretKey KEY=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        public java.security.Key engineGetKey(String alias,char[] password){return KEY;}
        public java.security.cert.Certificate[] engineGetCertificateChain(String alias){return null;}
        public java.security.cert.Certificate engineGetCertificate(String alias){return null;}
        public java.util.Date engineGetCreationDate(String alias){return new java.util.Date(0);}
        public void engineSetKeyEntry(String alias,java.security.Key key,char[] password,java.security.cert.Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetKeyEntry(String alias,byte[] key,java.security.cert.Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetCertificateEntry(String alias,java.security.cert.Certificate cert){throw new UnsupportedOperationException();}
        public void engineDeleteEntry(String alias){throw new UnsupportedOperationException();}
        public java.util.Enumeration<String> engineAliases(){return java.util.Collections.enumeration(java.util.Collections.singleton("pocketengine.feature-secrets.v1"));}
        public boolean engineContainsAlias(String alias){return alias.equals("pocketengine.feature-secrets.v1");}
        public int engineSize(){return 1;}
        public boolean engineIsKeyEntry(String alias){return engineContainsAlias(alias);}
        public boolean engineIsCertificateEntry(String alias){return false;}
        public String engineGetCertificateAlias(java.security.cert.Certificate cert){return null;}
        public void engineStore(java.io.OutputStream out,char[] password){}
        public void engineLoad(java.io.InputStream in,char[] password){}
    }
    private static Object get(Object source,String field)throws Exception{Field f=source.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(source);}
    private static Object invoke(Object source,String method)throws Exception{Method m=source.getClass().getDeclaredMethod(method);m.setAccessible(true);return m.invoke(source);}
    @Test public void verifiedDevicePixelsAndIdentityReachRealModelHttpBeforeNextDesktopAction()throws Exception{scenario(true);}
    @Test public void modelWithoutExplicitImageSupportNeverDispatchesDesktopCapture()throws Exception{scenario(false);}
    private void scenario(boolean imageSupport)throws Exception{
        prepareApplication();
        DeviceIngressNativeTest fixture=new DeviceIngressNativeTest();fixture.open(((dev.pocketengine.EngineApplication)org.robolectric.RuntimeEnvironment.getApplication()).tasks);HttpServer http=null;byte[] png=null;
        try{
            TaskStore tasks=(TaskStore)get(fixture,"tasks");PairingCoordinator pairing=(PairingCoordinator)get(fixture,"pairing");DeviceIdentity.AuthenticatedPeer remote=(DeviceIdentity.AuthenticatedPeer)get(fixture,"caller");String local=(String)get(fixture,"target");DeviceIdentity.LocalIdentity identity=(DeviceIdentity.LocalIdentity)invoke(fixture,"syntheticLocalIdentity");long deadline=System.currentTimeMillis()+240000;
            JSONObject task=tasks.createCapabilityTask("Read one fixture desktop frame and perform one accurately observed fixture action","");String id=task.getString("id");tasks.configureExecutionScope(id,0,deadline,20,20);RunControl original=new RunControl();
            JSONObject resource=new JSONObject().put("kind","desktop").put("sessionId",1).put("displayId","primary");JSONObject grant=new JSONObject().put("grantId","visual-native-grant").put("peerDeviceId",local).put("localDeviceId",remote.deviceId()).put("authorizationEpoch",1).put("issuedAt",System.currentTimeMillis()-1000).put("expiresAt",deadline).put("capabilities",new JSONArray().put("tool.invoke").put("operation.get").put("artifact.describe").put("artifact.readChunk")).put("toolNames",new JSONArray().put("desktop.observe").put("desktop.control").put("desktop.act")).put("resources",new JSONArray().put(resource)).put("allowedOrigins",new JSONArray().put(local)).put("allowDelegation",false).put("maxDelegationDepth",0).put("toolBudget",20).put("modelBudget",0);
            Object material=get(fixture,"callerMaterial");X509Certificate certificate=(X509Certificate)invoke(material,"cert");KeyPair keys=(KeyPair)invoke(material,"keys");JSONObject receipt=new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","PairGrantReceipt").put("issuerDeviceId",remote.deviceId()).put("issuerCertificateFingerprint",remote.certificateFingerprint()).put("body",new JSONObject().put("grant",grant).put("targetInstanceId","caller-fixture").put("issuedAt",System.currentTimeMillis()));Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(keys.getPrivate());signature.update(DeviceProtocol.canonicalBytes(receipt));receipt.put("signature",Base64.getEncoder().encodeToString(signature.sign()));var outgoing=pairing.acceptOutgoingGrant(remote,receipt,System.currentTimeMillis());
            Bitmap bitmap=Bitmap.createBitmap(2,3,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff1a6588);try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes));png=bytes.toByteArray();}finally{bitmap.recycle();}final byte[] actualPng=png;String imageHash=DeviceProtocol.sha256(actualPng);AtomicInteger remoteDispatches=new AtomicInteger(),readChunks=new AtomicInteger();AtomicReference<JSONObject> latest=new AtomicReference<>(),latestLease=new AtomicReference<>();
            DeviceCapabilityPort.VerifiedImages images=evidence->{evidence.requireCurrent();DeviceArtifactTransfer cache=new DeviceArtifactTransfer(Files.createTempDirectory("native-device-frame-fixture").toFile());DeviceArtifactSession session=new DeviceArtifactSession(cache);AtomicReference<DeviceArtifactFetch> fetchRef=new AtomicReference<>();DeviceArtifactFetch fetch=new DeviceArtifactFetch(cache,session,(peer,exact,offset,length,end)->{readChunks.incrementAndGet();var query=evidence.historyQuery(identity,offset,length,System.currentTimeMillis());fetchRef.get().onChunk(remote,UUID.fromString(exact.transferId),offset,java.util.Arrays.copyOfRange(actualPng,(int)offset,(int)offset+length));JSONObject ready=new JSONObject().put("artifactId",exact.artifactId).put("transferId",exact.transferId).put("contentDigest",exact.sha256).put("offset",offset).put("length",length).put("status","ready");return new DeviceArtifactFetch.ReadReply(query.envelope(),new DeviceCapabilityPort.Reply(remote,response(query.envelope(),remote.deviceId(),ready)));});fetchRef.set(fetch);return fetch.fetch(evidence.actualControlPeer(),evidence.descriptor(),evidence.deadline(),evidence::requireCurrent);};
            JSONArray declarations=new JSONArray().put(new JSONObject().put("toolName","desktop.observe").put("effect","read")).put(new JSONObject().put("toolName","desktop.control").put("effect","write")).put(new JSONObject().put("toolName","desktop.act").put("effect","write"));JSONObject description=DeviceProtocol.deviceDescription(remote.deviceId(),"caller-fixture",System.currentTimeMillis(),System.currentTimeMillis()+5000,new JSONArray().put(new JSONObject().put("id","desktop").put("version","desktop-v2").put("resources",new JSONArray().put(resource)).put("toolDeclarations",declarations)),new JSONArray(),new JSONObject());
            var host=new NativeDeviceTaskScopeHost(tasks,new NativeDeviceTaskScopeHost.NativeOptionSource(){public Iterable<PairingCoordinator.OutgoingGrant> originalOutgoingGrants(){return List.of(outgoing);}public JSONObject authenticatedCapabilities(String target){return description;}},new DeviceTaskCoordinator.NativeHost(){public void acceptTools(String task,DeviceExecutionAdmission admission){throw new AssertionError("not a receiver Runner fixture");}public void acceptGoal(String task,DeviceExecutionAdmission admission){throw new AssertionError("no remote model fixture");}public void controlRequested(String task,String action){throw new AssertionError("no control fixture");}},images);
            String route;try(var window=host.open(id)){route=window.view().getJSONArray("options").getJSONObject(0).getString("optionId");window.confirm(window.review(new NativeDeviceTaskScopeHost.Selection(List.of(new NativeDeviceTaskScopeHost.RouteChoice(route,4,0,deadline,4,0,List.of(),NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY)))));}
            task=tasks.startAttempt(id,"capabilities",20);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);
            var exchange=new DeviceNativeRoute.Exchange(){public DeviceCapabilityPort.Reply once(String target,DeviceTaskEnvelope request,RunControl io){throw new AssertionError("no raw effect or operation polling in this fixture");}public DeviceCapabilityPort.Reply once(String target,DeviceTaskEnvelope request,dev.pocketengine.store.TaskStoreDeviceTasks.OutboundSendTicket ticket,RunControl io)throws Exception{assertArrayEquals(DeviceProtocol.canonicalBytes(request.json()),ticket.claimForWrite(remote));String tool=request.toolInvocation().toolName();int sequence=remoteDispatches.incrementAndGet();if(tool.equals("desktop.act")){assertEquals(3,sequence);assertTrue(DeviceProtocol.resourceEquals(latest.get(),request.observationRef()));}
                long captured=System.currentTimeMillis();if(tool.equals("desktop.control")){JSONObject lease=new JSONObject().put("id","12345678123456781234567812345678").put("fence",1).put("expiresAt",captured+30000).put("resource","fixture-native-desktop-resource");latestLease.set(lease);JSONObject audience=audience(request,resource,captured,deadline);JSONObject typed=DeviceProtocol.operationReceipt(remote.deviceId(),"caller-fixture",request.operationId(),request.argumentsDigest(),"succeeded",true,"reported",new JSONObject().put("desktopReceipt",new JSONObject().put("lease",lease)),new JSONObject().put("acceptedAt",captured).put("audience",audience).put("jobId","native-fixture-job-"+sequence));return new DeviceCapabilityPort.Reply(remote,response(request,remote.deviceId(),typed));}JSONObject reference=new JSONObject().put("id",UUID.randomUUID().toString().replace("-","")).put("sessionId",1).put("displayId","primary").put("windowId",JSONObject.NULL).put("binding",new JSONObject().put("nativeFixtureWindow",true)).put("driverGeneration","1".repeat(32)).put("capturedAt",captured).put("expiresAt",captured+30000);latest.set(reference);JSONObject descriptor=new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","ArtifactDescriptor").put("executorDeviceId",remote.deviceId()).put("instanceId","caller-fixture").put("artifactId",UUID.randomUUID().toString()).put("transferId",UUID.randomUUID().toString()).put("versionId",imageHash).put("revision",1).put("taskId",request.localTaskId()).put("requestRunId",request.runId()).put("requestExecutorEpoch",request.executorEpoch()).put("operationId",request.operationId()).put("kind","screenshot").put("mediaType","image/png").put("name","native-fixture.png").put("size",actualPng.length).put("contentDigest",imageHash).put("observationRef",reference).put("resourceBinding",resource).put("expiresAt",captured+300000);DeviceProtocol.validateMessage(descriptor);
                JSONObject observation=new JSONObject().put("observationRef",reference).put("capture",descriptor).put("image",new JSONObject().put("width",2).put("height",3).put("mediaType","image/png"));JSONObject body=tool.equals("desktop.observe")?observation:new JSONObject().put("desktopReceipt",new JSONObject().put("afterObservation",observation));JSONObject audience=new JSONObject().put("kind","operation").put("objectId",request.operationId()).put("originDeviceId",request.originDeviceId()).put("callerDeviceId",request.callerDeviceId()).put("targetDeviceId",request.targetDeviceId()).put("instanceId","caller-fixture").put("rootTaskId",request.rootTaskId()).put("localTaskId",request.localTaskId()).put("requestRunId",request.runId()).put("requestExecutorEpoch",request.executorEpoch()).put("grantId",request.grantId()).put("authorizationEpoch",request.authorizationEpoch()).put("resourceBinding",resource).put("acceptedAt",captured).put("retainedUntil",deadline+60000);JSONObject typed=DeviceProtocol.operationReceipt(remote.deviceId(),"caller-fixture",request.operationId(),request.argumentsDigest(),"succeeded",true,"reported",body,new JSONObject().put("acceptedAt",captured).put("audience",audience).put("jobId","native-fixture-job-"+sequence));return new DeviceCapabilityPort.Reply(remote,response(request,remote.deviceId(),typed));}};
            AtomicInteger unseenActionsBlocked=new AtomicInteger();AtomicReference<CapabilityPort> factoryPort=new AtomicReference<>();List<String> bodies=new java.util.concurrent.CopyOnWriteArrayList<>();AtomicInteger requests=new AtomicInteger();http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final String selectedRoute=route;http.createContext("/",request->{try{int round=requests.incrementAndGet();if(round==1&&imageSupport)try{factoryPort.get().beforeTool("device_invoke_tool",new JSONObject().put("routeId",selectedRoute).put("targetDeviceId",remote.deviceId()).put("toolName","desktop.act").put("observationRef",new JSONObject()),original);}catch(SecurityException unseen){unseenActionsBlocked.incrementAndGet();}bodies.add(new String(request.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));JSONObject message=new JSONObject().put("role","assistant");if(round==1||round<=3&&imageSupport){JSONObject args=new JSONObject().put("routeId",selectedRoute).put("targetDeviceId",remote.deviceId()).put("toolName",round==1?"desktop.observe":round==2?"desktop.control":"desktop.act").put("arguments",round==1?new JSONObject().put("pid",77).put("windowId",99).put("observationMode","hybrid"):round==2?new JSONObject().put("action","acquire").put("durationMs",30000):new JSONObject().put("kind","click").put("elementToken","s1234abcd:1").put("lease",latestLease.get()).put("verification",new JSONObject().put("kind","element_present").put("selector",new JSONObject().put("role","button").put("label","fixture")))).put("toolBudget",4);if(round==3)args.put("observationRef",latest.get());message.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","native-call-"+round).put("type","function").put("function",new JSONObject().put("name","device_invoke_tool").put("arguments",args.toString()))));}else message.put("content","Controlled model fixture finished");byte[] encoded=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",message.has("tool_calls")?"tool_calls":"stop").put("message",message))).toString().getBytes(StandardCharsets.UTF_8);request.getResponseHeaders().add("Content-Type","application/json");request.sendResponseHeaders(200,encoded.length);request.getResponseBody().write(encoded);}catch(Exception failure){throw new RuntimeException(failure);}finally{request.close();}});http.start();
            dev.pocketengine.EngineApplication app=(dev.pocketengine.EngineApplication)org.robolectric.RuntimeEnvironment.getApplication();
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).putString("model","native-visual-fixture-model").putBoolean("sendScreenshots",true).putBoolean("supportsImageInput",imageSupport).putInt("maxSteps",8).putInt("requestTimeoutSeconds",60).commit();
            ((java.util.Map<String,RunControl>)get(app,"capabilityControls")).put(id,original);
            app.setOriginalDeviceSourceRoutes((selected,live)->host.actualHostTools(live,identity,exchange));
            ModelConfig model=app.settings.get();CapabilityPort port=app.capabilitiesForTask(id,original);tasks.status(id,"running","native visual fixture");
            assertTrue("test must use the production outer factory, not a bare device port",port instanceof dev.pocketengine.features.memory.ToolOutputSourcePort);
            factoryPort.set(port);
            List<String> events=new ArrayList<>();var outcome=new AgentRunner(null,port).run(id,task.getString("goal"),model,original,event->events.add(event.toString()),tasks.journal(id,run,epoch,original));
            if(imageSupport){assertEquals(outcome.summary,4,requests.get());assertEquals(3,remoteDispatches.get());assertEquals(2,readChunks.get());String sent=bodies.get(1);assertTrue(sent.contains("image_url"));assertTrue(sent.contains(Base64.getEncoder().encodeToString(actualPng)));assertTrue(sent.contains(imageHash));assertEquals(1,unseenActionsBlocked.get());assertTrue(sent.contains("paired_device_visual_snapshot"));assertTrue(sent.contains(id));assertFalse(sent.contains("android_window_pixelcopy"));assertTrue(bodies.get(3).contains("image_url"));assertTrue(bodies.get(0).contains("anyOf"));assertTrue(bodies.get(0).contains("foregroundAfterOperationId"));assertEquals(4,tasks.deviceTasks().heldBudget(id,run,false));}
            else{assertEquals(outcome.summary,2,requests.get());assertEquals(outcome.summary,0,remoteDispatches.get());assertEquals(0,readChunks.get());assertFalse(bodies.toString().contains("data:image/png;base64,"));assertEquals(0,tasks.deviceTasks().heldBudget(id,run,false));}
        }finally{if(http!=null)http.stop(0);if(png!=null)java.util.Arrays.fill(png,(byte)0);fixture.close();}
    }
    private static JSONObject audience(DeviceTaskEnvelope request,JSONObject resource,long accepted,long deadline)throws Exception{return new JSONObject().put("kind","operation").put("objectId",request.operationId()).put("originDeviceId",request.originDeviceId()).put("callerDeviceId",request.callerDeviceId()).put("targetDeviceId",request.targetDeviceId()).put("instanceId","caller-fixture").put("rootTaskId",request.rootTaskId()).put("localTaskId",request.localTaskId()).put("requestRunId",request.runId()).put("requestExecutorEpoch",request.executorEpoch()).put("grantId",request.grantId()).put("authorizationEpoch",request.authorizationEpoch()).put("resourceBinding",resource).put("acceptedAt",accepted).put("retainedUntil",deadline+60000);}
    private static JSONObject response(DeviceTaskEnvelope request,String executor,JSONObject result)throws Exception{return new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceResponse").put("requestId",request.requestId()).put("method",request.method()).put("executorDeviceId",executor).put("instanceId","caller-fixture").put("result",result);}
}
