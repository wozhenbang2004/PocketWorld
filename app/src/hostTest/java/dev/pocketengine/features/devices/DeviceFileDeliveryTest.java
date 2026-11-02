package dev.pocketengine.features.devices;

import com.sun.net.httpserver.HttpServer;
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
import static org.junit.Assert.*;

/** Actual EngineApplication outer factory -> original SQL -> verified cache bytes/Bitmap -> Runner ->
 * controlled HTTP image request. Remote desktop execution is an EXPLICIT authenticated native
 * receipt fixture, not a real PC/Windows capture, remote SQL acceptance or cross-network AC. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=dev.pocketengine.EngineApplication.class)
public final class DeviceFileDeliveryTest {
    @org.junit.Before public void keys(){java.security.Security.addProvider(new java.security.Provider("DeviceJourneyKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    private static Object get(Object source,String field)throws Exception{Field f=source.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(source);}
    private static Object invoke(Object source,String method)throws Exception{Method m=source.getClass().getDeclaredMethod(method);m.setAccessible(true);return m.invoke(source);}
    @Test public void originalFactoryPublishesBinaryAndParentReceivesIdentity()throws Exception{scenario(false);}
    @Test public void corruptBytesCannotPublishButAcceptedOperationSurvives()throws Exception{scenario(true);}
    private void scenario(boolean corrupt)throws Exception{
        var app=(dev.pocketengine.EngineApplication)org.robolectric.RuntimeEnvironment.getApplication();
        DeviceIngressNativeTest fixture=new DeviceIngressNativeTest();fixture.open(app.tasks);HttpServer http=null;
        try{
            TaskStore tasks=(TaskStore)get(fixture,"tasks");PairingCoordinator pairing=(PairingCoordinator)get(fixture,"pairing");DeviceIdentity.AuthenticatedPeer remote=(DeviceIdentity.AuthenticatedPeer)get(fixture,"caller");String local=(String)get(fixture,"target");DeviceIdentity.LocalIdentity identity=(DeviceIdentity.LocalIdentity)invoke(fixture,"syntheticLocalIdentity");long deadline=System.currentTimeMillis()+240000;
            JSONObject task=tasks.createCapabilityTask("Receive one selected workspace binary","");String id=task.getString("id");tasks.configureExecutionScope(id,0,deadline,20,20);RunControl original=new RunControl();
            JSONObject resource=new JSONObject().put("kind","workspace").put("environmentId","native-workspace").put("binding","a".repeat(64));JSONObject grant=new JSONObject().put("grantId","visual-native-grant").put("peerDeviceId",local).put("localDeviceId",remote.deviceId()).put("authorizationEpoch",1).put("issuedAt",System.currentTimeMillis()-1000).put("expiresAt",deadline).put("capabilities",new JSONArray().put("tool.invoke").put("operation.get").put("artifact.describe").put("artifact.readChunk")).put("toolNames",new JSONArray().put("workspace.export")).put("resources",new JSONArray().put(resource)).put("allowedOrigins",new JSONArray().put(local)).put("allowDelegation",false).put("maxDelegationDepth",0).put("toolBudget",20).put("modelBudget",0);
            Object material=get(fixture,"callerMaterial");X509Certificate certificate=(X509Certificate)invoke(material,"cert");KeyPair keys=(KeyPair)invoke(material,"keys");JSONObject receipt=new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","PairGrantReceipt").put("issuerDeviceId",remote.deviceId()).put("issuerCertificateFingerprint",remote.certificateFingerprint()).put("body",new JSONObject().put("grant",grant).put("targetInstanceId","caller-fixture").put("issuedAt",System.currentTimeMillis()));Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(keys.getPrivate());signature.update(DeviceProtocol.canonicalBytes(receipt));receipt.put("signature",Base64.getEncoder().encodeToString(signature.sign()));var outgoing=pairing.acceptOutgoingGrant(remote,receipt,System.currentTimeMillis());
            byte[] bytes=new byte[300_017];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31);String hash=DeviceProtocol.sha256(bytes);
            AtomicInteger dispatched=new AtomicInteger(),chunks=new AtomicInteger();AtomicReference<JSONObject> published=new AtomicReference<>(),typedResult=new AtomicReference<>();
            var book=dev.pocketengine.features.attachments.AttachmentRepository.get(app).book;
            DeviceCapabilityPort.ReceivedFiles files=evidence->{
                evidence.requireCurrent();DeviceArtifactTransfer cache=new DeviceArtifactTransfer(Files.createTempDirectory("native-file-cache").toFile());
                AtomicReference<DeviceArtifactFetch> holder=new AtomicReference<>();
                try(DeviceArtifactFetch fetch=new DeviceArtifactFetch(cache,new DeviceArtifactSession(cache),(peer,exact,offset,length,end)->{
                    chunks.incrementAndGet();var query=evidence.historyQuery(identity,offset,length,System.currentTimeMillis());byte[] chunk=java.util.Arrays.copyOfRange(bytes,(int)offset,(int)offset+length);if(corrupt)chunk[0]^=1;
                    holder.get().onChunk(remote,UUID.fromString(exact.transferId),offset,chunk);
                    JSONObject ready=new JSONObject().put("artifactId",exact.artifactId).put("transferId",exact.transferId).put("contentDigest",exact.sha256).put("offset",offset).put("length",length).put("status","ready");
                    return new DeviceArtifactFetch.ReadReply(query.envelope(),new DeviceCapabilityPort.Reply(remote,response(query.envelope(),remote.deviceId(),ready)));
                })){
                    holder.set(fetch);var verified=fetch.fetch(evidence.actualControlPeer(),evidence.descriptor(),evidence.deadline(),evidence::requireCurrent);
                    JSONObject result=new dev.pocketengine.features.devices.transport.DeviceReceivedAttachmentPublisher(cache,book).publish(verified.reference(),evidence.operationId(),evidence.receivingTaskId(),evidence.frozenRunId(),evidence.frozenEpoch(),evidence.originalControl(),evidence::requireCurrent);
                    published.set(result);return result;
                }
            };
            JSONArray declarations=new JSONArray().put(new JSONObject().put("toolName","workspace.export").put("effect","read"));
            JSONObject description=DeviceProtocol.deviceDescription(remote.deviceId(),"caller-fixture",System.currentTimeMillis(),System.currentTimeMillis()+5000,new JSONArray().put(new JSONObject().put("id","workspace").put("version","workspace-v1").put("resources",new JSONArray().put(resource)).put("toolDeclarations",declarations)),new JSONArray(),new JSONObject());
            var host=new NativeDeviceTaskScopeHost(tasks,new NativeDeviceTaskScopeHost.NativeOptionSource(){public Iterable<PairingCoordinator.OutgoingGrant> originalOutgoingGrants(){return List.of(outgoing);}public JSONObject authenticatedCapabilities(String target){return description;}},new DeviceTaskCoordinator.NativeHost(){public void acceptTools(String task,DeviceExecutionAdmission admission){throw new AssertionError();}public void acceptGoal(String task,DeviceExecutionAdmission admission){throw new AssertionError();}public void controlRequested(String task,String action){throw new AssertionError();}},null,files);
            String route;try(var window=host.open(id)){route=window.view().getJSONArray("options").getJSONObject(0).getString("optionId");window.confirm(window.review(new NativeDeviceTaskScopeHost.Selection(List.of(new NativeDeviceTaskScopeHost.RouteChoice(route,4,0,deadline,4,0,List.of(),NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY)))));}
            task=tasks.startAttempt(id,"capabilities",20);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);
            var exchange=new DeviceNativeRoute.Exchange(){
                public DeviceCapabilityPort.Reply once(String target,DeviceTaskEnvelope request,RunControl io)throws Exception{
                    assertEquals("operation.get",request.method());return new DeviceCapabilityPort.Reply(remote,response(request,remote.deviceId(),typedResult.get()));
                }
                public DeviceCapabilityPort.Reply once(String target,DeviceTaskEnvelope request,dev.pocketengine.store.TaskStoreDeviceTasks.OutboundSendTicket ticket,RunControl io)throws Exception{
                    assertArrayEquals(DeviceProtocol.canonicalBytes(request.json()),ticket.claimForWrite(remote));dispatched.incrementAndGet();assertEquals("workspace.export",request.toolInvocation().toolName());
                    JSONObject descriptor=new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","ArtifactDescriptor").put("executorDeviceId",remote.deviceId()).put("instanceId","caller-fixture").put("artifactId",UUID.randomUUID().toString()).put("transferId",UUID.randomUUID().toString()).put("versionId",hash).put("revision",1).put("taskId",request.localTaskId()).put("requestRunId",request.runId()).put("requestExecutorEpoch",request.executorEpoch()).put("operationId",request.operationId()).put("kind","file").put("mediaType","application/octet-stream").put("name","native-result.bin").put("size",bytes.length).put("contentDigest",hash);
                    DeviceProtocol.validateMessage(descriptor);long accepted=System.currentTimeMillis();JSONObject typed=DeviceProtocol.operationReceipt(remote.deviceId(),"caller-fixture",request.operationId(),request.argumentsDigest(),"succeeded",true,"reported",descriptor,new JSONObject().put("acceptedAt",accepted).put("audience",audience(request,resource,accepted,deadline)).put("jobId","native-file-job"));typedResult.set(typed);
                    return new DeviceCapabilityPort.Reply(remote,response(request,remote.deviceId(),typed));
                }
            };
            List<String> bodies=new java.util.concurrent.CopyOnWriteArrayList<>();AtomicInteger requests=new AtomicInteger();http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final String selectedRoute=route;
            http.createContext("/",request->{try{int round=requests.incrementAndGet();bodies.add(new String(request.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));JSONObject message=new JSONObject().put("role","assistant");
                if(round==1){JSONObject args=new JSONObject().put("routeId",selectedRoute).put("targetDeviceId",remote.deviceId()).put("toolName","workspace.export").put("arguments",new JSONObject().put("path","result.bin")).put("toolBudget",4);message.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","file-call").put("type","function").put("function",new JSONObject().put("name","device_invoke_tool").put("arguments",args.toString()))));}else if(round==2&&!corrupt){JSONObject args=new JSONObject().put("routeId",selectedRoute).put("targetDeviceId",remote.deviceId()).put("operationId",typedResult.get().getString("operationId"));message.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","file-read-again").put("type","function").put("function",new JSONObject().put("name","device_operation_get").put("arguments",args.toString()))));}else message.put("content","Read the original file receipt, not a claimed external business outcome");
                byte[] encoded=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",message.has("tool_calls")?"tool_calls":"stop").put("message",message))).toString().getBytes(StandardCharsets.UTF_8);request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,encoded.length);request.getResponseBody().write(encoded);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{request.close();}});http.start();
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).putString("model","native-file-fixture").putInt("maxSteps",4).putInt("requestTimeoutSeconds",60).commit();
            ((java.util.Map<String,RunControl>)get(app,"capabilityControls")).put(id,original);app.setOriginalDeviceSourceRoutes((selected,live)->host.actualHostTools(live,identity,exchange));
            CapabilityPort port=app.capabilitiesForTask(id,original);tasks.status(id,"running","native file fixture");assertTrue(port instanceof dev.pocketengine.features.memory.ToolOutputSourcePort);
            var outcome=new AgentRunner(null,port).run(id,task.getString("goal"),app.settings.get(),original,event->{},tasks.journal(id,run,epoch,original));
            assertEquals(outcome.summary,corrupt?2:3,requests.get());assertEquals(1,dispatched.get());assertTrue(chunks.get()>0);String sent=bodies.get(1);assertTrue(sent.contains("nativeFileDelivery"));
            if(corrupt){assertNull(published.get());assertEquals(0,book.list().length());assertTrue(sent.contains("original_file_receive_unavailable"));assertFalse(sent.contains("device_effect_unknown"));}
            else{JSONObject attachment=published.get().getJSONObject("attachment");String attachmentId=attachment.getString("id");assertArrayEquals(bytes,Files.readAllBytes(book.file(attachmentId).toPath()));assertEquals(hash,attachment.getString("sha256"));assertTrue(sent.contains(attachmentId));assertTrue(sent.contains(hash));assertTrue(sent.contains("device_received_file"));assertTrue(sent.contains(remote.deviceId()));
                assertTrue(bodies.get(2).contains(attachmentId));assertEquals(1,book.list().length());assertEquals(1,dispatched.get());
            }
        }finally{if(http!=null)http.stop(0);fixture.close();}
    }
    private static JSONObject audience(DeviceTaskEnvelope request,JSONObject resource,long accepted,long deadline)throws Exception{return new JSONObject().put("kind","operation").put("objectId",request.operationId()).put("originDeviceId",request.originDeviceId()).put("callerDeviceId",request.callerDeviceId()).put("targetDeviceId",request.targetDeviceId()).put("instanceId","caller-fixture").put("rootTaskId",request.rootTaskId()).put("localTaskId",request.localTaskId()).put("requestRunId",request.runId()).put("requestExecutorEpoch",request.executorEpoch()).put("grantId",request.grantId()).put("authorizationEpoch",request.authorizationEpoch()).put("resourceBinding",resource).put("acceptedAt",accepted).put("retainedUntil",deadline+60000);}
    private static JSONObject response(DeviceTaskEnvelope request,String executor,JSONObject result)throws Exception{return new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceResponse").put("requestId",request.requestId()).put("method",request.method()).put("executorDeviceId",executor).put("instanceId","caller-fixture").put("result",result);}
}
