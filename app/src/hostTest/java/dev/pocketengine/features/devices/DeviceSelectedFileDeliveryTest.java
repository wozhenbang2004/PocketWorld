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

/** Real EngineApplication factory, original Runner/SQL and Book; authenticated remote receipt
 * and bulk writer are explicit fixtures, not real Windows/TLS/device execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=dev.pocketengine.EngineApplication.class)
public final class DeviceSelectedFileDeliveryTest {
    @org.junit.Before public void keys(){java.security.Security.addProvider(new java.security.Provider("DeviceJourneyKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    private static Object get(Object source,String field)throws Exception{Field f=source.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(source);}
    private static Object invoke(Object source,String method)throws Exception{Method m=source.getClass().getDeclaredMethod(method);m.setAccessible(true);return m.invoke(source);}
    @Test public void originalFactorySendsOnlySelectedBytesAndReadsSameOperation()throws Exception{scenario(false,false);}
    @Test public void sourceRetirementPreventsBulkButPreservesAcceptedReceipt()throws Exception{scenario(true,false);}
    @Test public void disconnectedBulkResumesSameTransferFromReadOnlyOperation()throws Exception{scenario(false,true);}
    private void scenario(boolean corrupt,boolean disconnect)throws Exception{
        var app=(dev.pocketengine.EngineApplication)org.robolectric.RuntimeEnvironment.getApplication();
        DeviceIngressNativeTest fixture=new DeviceIngressNativeTest();fixture.open(app.tasks);HttpServer http=null;
        try{
            TaskStore tasks=(TaskStore)get(fixture,"tasks");PairingCoordinator pairing=(PairingCoordinator)get(fixture,"pairing");DeviceIdentity.AuthenticatedPeer remote=(DeviceIdentity.AuthenticatedPeer)get(fixture,"caller");String local=(String)get(fixture,"target");DeviceIdentity.LocalIdentity identity=(DeviceIdentity.LocalIdentity)invoke(fixture,"syntheticLocalIdentity");long deadline=System.currentTimeMillis()+240000;
            JSONObject task=tasks.createCapabilityTask("Receive one selected workspace binary","");String id=task.getString("id");tasks.configureExecutionScope(id,0,deadline,20,20);RunControl original=new RunControl();
            JSONObject resource=new JSONObject().put("kind","workspace").put("environmentId","native-workspace").put("binding","a".repeat(64));JSONObject grant=new JSONObject().put("grantId","visual-native-grant").put("peerDeviceId",local).put("localDeviceId",remote.deviceId()).put("authorizationEpoch",1).put("issuedAt",System.currentTimeMillis()-1000).put("expiresAt",deadline).put("capabilities",new JSONArray().put("tool.invoke").put("operation.get").put("artifact.describe").put("artifact.readChunk")).put("toolNames",new JSONArray().put("workspace.receive")).put("resources",new JSONArray().put(resource)).put("allowedOrigins",new JSONArray().put(local)).put("allowDelegation",false).put("maxDelegationDepth",0).put("toolBudget",20).put("modelBudget",0);
            Object material=get(fixture,"callerMaterial");X509Certificate certificate=(X509Certificate)invoke(material,"cert");KeyPair keys=(KeyPair)invoke(material,"keys");JSONObject receipt=new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","PairGrantReceipt").put("issuerDeviceId",remote.deviceId()).put("issuerCertificateFingerprint",remote.certificateFingerprint()).put("body",new JSONObject().put("grant",grant).put("targetInstanceId","caller-fixture").put("issuedAt",System.currentTimeMillis()));Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(keys.getPrivate());signature.update(DeviceProtocol.canonicalBytes(receipt));receipt.put("signature",Base64.getEncoder().encodeToString(signature.sign()));var outgoing=pairing.acceptOutgoingGrant(remote,receipt,System.currentTimeMillis());
            byte[] bytes=new byte[123_017];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31);String hash=DeviceProtocol.sha256(bytes);
            AtomicInteger dispatched=new AtomicInteger(),chunks=new AtomicInteger(),sendAttempts=new AtomicInteger();AtomicReference<JSONObject> published=new AtomicReference<>(),typedResult=new AtomicReference<>();
            var book=dev.pocketengine.features.attachments.AttachmentRepository.get(app).book;
            JSONObject imported=book.importBytes(new java.io.ByteArrayInputStream(bytes),"selected.bin","application/octet-stream",new JSONObject().put("kind","owner_import"));String attachmentId=imported.getString("id");
            JSONArray declarations=new JSONArray().put(new JSONObject().put("toolName","workspace.receive").put("effect","write"));
            JSONObject description=DeviceProtocol.deviceDescription(remote.deviceId(),"caller-fixture",System.currentTimeMillis(),System.currentTimeMillis()+5000,new JSONArray().put(new JSONObject().put("id","workspace").put("version","workspace-v1").put("resources",new JSONArray().put(resource)).put("toolDeclarations",declarations)),new JSONArray(),new JSONObject());
            var host=new NativeDeviceTaskScopeHost(tasks,new NativeDeviceTaskScopeHost.NativeOptionSource(){
                public Iterable<PairingCoordinator.OutgoingGrant> originalOutgoingGrants(){return List.of(outgoing);}public JSONObject authenticatedCapabilities(String target){return description;}
                public dev.pocketengine.features.attachments.AttachmentBook attachmentBook(){return book;}
                public void sendSelectedFile(DeviceIdentity.AuthenticatedPeer peer,UUID transfer,long offset,byte[] raw,long end,DeviceArtifactTransfer.Current current)throws Exception{
                    current.require();if(disconnect&&sendAttempts.getAndIncrement()==0)throw new java.io.IOException("fixture bulk disconnected before write");assertEquals(remote.deviceId(),peer.deviceId());assertEquals(0,offset);assertArrayEquals(bytes,raw);chunks.incrementAndGet();
                    JSONObject accepted=typedResult.get();assertEquals(accepted.getJSONObject("payload").getString("transferId"),transfer.toString());
                    JSONObject done=new JSONObject(accepted.toString()).put("status","succeeded").put("effectState","reported").put("payload",new JSONObject().put("status","succeeded").put("delivered",true).put("sha256",hash).put("path","selected.bin").put("nativeToolResult",new JSONObject().put("ok",true).put("version","sha256:"+hash)));typedResult.set(done);
                }
            },new DeviceTaskCoordinator.NativeHost(){public void acceptTools(String task,DeviceExecutionAdmission admission){throw new AssertionError();}public void acceptGoal(String task,DeviceExecutionAdmission admission){throw new AssertionError();}public void controlRequested(String task,String action){throw new AssertionError();}});
            String route;try(var window=host.open(id)){assertEquals(1,window.view().getJSONArray("materialOptions").length());route=window.view().getJSONArray("options").getJSONObject(0).getString("optionId");window.confirm(window.review(new NativeDeviceTaskScopeHost.Selection(List.of(new NativeDeviceTaskScopeHost.RouteChoice(route,4,0,deadline,4,0,List.of(),NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY,List.of(new NativeDeviceTaskScopeHost.FileChoice(attachmentId,hash,"selected.bin","absent")))))));}
            task=tasks.startAttempt(id,"capabilities",20);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);
            var exchange=new DeviceNativeRoute.Exchange(){
                public DeviceCapabilityPort.Reply once(String target,DeviceTaskEnvelope request,RunControl io)throws Exception{
                    assertEquals("operation.get",request.method());return new DeviceCapabilityPort.Reply(remote,response(request,remote.deviceId(),typedResult.get()));
                }
                public DeviceCapabilityPort.Reply once(String target,DeviceTaskEnvelope request,dev.pocketengine.store.TaskStoreDeviceTasks.OutboundSendTicket ticket,RunControl io)throws Exception{
                    assertArrayEquals(DeviceProtocol.canonicalBytes(request.json()),ticket.claimForWrite(remote));dispatched.incrementAndGet();assertEquals("workspace.receive",request.toolInvocation().toolName());
                    JSONObject args=request.toolInvocation().arguments(),descriptor=args.getJSONObject("artifact");assertEquals("selected.bin",args.getString("path"));assertEquals("absent",args.getString("expectedVersion"));assertEquals(hash,descriptor.getString("contentDigest"));assertEquals(request.operationId(),descriptor.getString("operationId"));assertEquals(request.localTaskId(),descriptor.getString("taskId"));assertEquals(local,descriptor.getString("executorDeviceId"));
                    long accepted=System.currentTimeMillis();JSONObject waiting=new JSONObject().put("status","running").put("phase","awaiting_bytes").put("transferId",descriptor.getString("transferId")).put("sha256",hash).put("bytes",bytes.length).put("verifiedOffset",0).put("maximumBytes",262144).put("delivered",false);
                    JSONObject typed=DeviceProtocol.operationReceipt(remote.deviceId(),"caller-fixture",request.operationId(),request.argumentsDigest(),"running",true,"reported",waiting,new JSONObject().put("acceptedAt",accepted).put("audience",audience(request,resource,accepted,deadline)).put("jobId","native-file-job"));typedResult.set(typed);
                    if(corrupt)book.remove(attachmentId);
                    return new DeviceCapabilityPort.Reply(remote,response(request,remote.deviceId(),typed));
                }
            };
            List<String> bodies=new java.util.concurrent.CopyOnWriteArrayList<>();AtomicInteger requests=new AtomicInteger();http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final String selectedRoute=route;
            http.createContext("/",request->{try{int round=requests.incrementAndGet();bodies.add(new String(request.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));JSONObject message=new JSONObject().put("role","assistant");
                if(round==1){JSONObject args=new JSONObject().put("routeId",selectedRoute).put("targetDeviceId",remote.deviceId()).put("toolName","workspace.receive").put("arguments",new JSONObject().put("materialId",attachmentId)).put("toolBudget",4);message.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","file-call").put("type","function").put("function",new JSONObject().put("name","device_invoke_tool").put("arguments",args.toString()))));}else if((round==2||disconnect&&round==3)&&!corrupt){JSONObject args=new JSONObject().put("routeId",selectedRoute).put("targetDeviceId",remote.deviceId()).put("operationId",typedResult.get().getString("operationId"));message.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","file-read-again-"+round).put("type","function").put("function",new JSONObject().put("name","device_operation_get").put("arguments",args.toString()))));}else message.put("content","Read the original file receipt, not a claimed external business outcome");
                byte[] encoded=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",message.has("tool_calls")?"tool_calls":"stop").put("message",message))).toString().getBytes(StandardCharsets.UTF_8);request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,encoded.length);request.getResponseBody().write(encoded);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{request.close();}});http.start();
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).putString("model","native-file-fixture").putInt("maxSteps",4).putInt("requestTimeoutSeconds",60).commit();
            ((java.util.Map<String,RunControl>)get(app,"capabilityControls")).put(id,original);app.setOriginalDeviceSourceRoutes((selected,live)->host.actualHostTools(live,identity,exchange));
            CapabilityPort port=app.capabilitiesForTask(id,original);tasks.status(id,"running","native file fixture");assertTrue(port instanceof dev.pocketengine.features.memory.ToolOutputSourcePort);
            var outcome=new AgentRunner(null,port).run(id,task.getString("goal"),app.settings.get(),original,event->{},tasks.journal(id,run,epoch,original));
            assertEquals(outcome.summary,corrupt?2:disconnect?4:3,requests.get());assertEquals(1,dispatched.get());String sent=bodies.get(1);assertTrue(sent.contains("nativeFileSend"));assertFalse(sent.contains("device_effect_unknown"));
            if(corrupt){assertEquals(0,chunks.get());assertTrue(sent.contains("retrySubmitted"));}
            else{if(disconnect)assertEquals(2,sendAttempts.get());assertEquals(1,chunks.get());assertTrue(bodies.get(2).contains("delivered"));assertTrue(bodies.get(2).contains(hash));assertArrayEquals(bytes,Files.readAllBytes(book.file(attachmentId).toPath()));}
        }finally{if(http!=null)http.stop(0);fixture.close();}
    }
    @Test public void nativePickerRejectsOversizeUnlistedChangedAndInvalidTargets()throws Exception{
        var app=(dev.pocketengine.EngineApplication)org.robolectric.RuntimeEnvironment.getApplication();var book=dev.pocketengine.features.attachments.AttachmentRepository.get(app).book;
        JSONObject a=book.importBytes(new java.io.ByteArrayInputStream(new byte[]{1,2,3}),"one.bin","application/octet-stream",new JSONObject()),large=book.importBytes(new java.io.ByteArrayInputStream(new byte[262145]),"large.bin","application/octet-stream",new JSONObject());JSONArray catalog=DeviceSelectedFileTransfer.catalog(book);
        assertEquals(2,catalog.length());for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("id").equals(large.getString("id")))assertFalse(catalog.getJSONObject(i).getBoolean("available"));
        var valid=new NativeDeviceTaskScopeHost.FileChoice(a.getString("id"),a.getString("sha256"),"one.bin","absent");
        assertEquals(1,DeviceSelectedFileTransfer.resolve(book,catalog,List.of(valid)).length());
        assertThrows(SecurityException.class,()->DeviceSelectedFileTransfer.resolve(book,catalog,List.of(new NativeDeviceTaskScopeHost.FileChoice(large.getString("id"),large.getString("sha256"),"large.bin","absent"))));
        assertThrows(SecurityException.class,()->DeviceSelectedFileTransfer.resolve(book,catalog,List.of(new NativeDeviceTaskScopeHost.FileChoice(UUID.randomUUID().toString(),a.getString("sha256"),"one.bin","absent"))));
        assertThrows(SecurityException.class,()->DeviceSelectedFileTransfer.resolve(book,catalog,List.of(new NativeDeviceTaskScopeHost.FileChoice(a.getString("id"),a.getString("sha256"),"../outside","absent"))));
        JSONArray selected=DeviceSelectedFileTransfer.resolve(book,catalog,List.of(valid));book.remove(a.getString("id"));assertThrows(Exception.class,()->DeviceSelectedFileTransfer.resolve(book,catalog,List.of(valid)));assertThrows(Exception.class,()->DeviceSelectedFileTransfer.revalidateReview(book,new JSONObject().put("routes",new JSONArray().put(new JSONObject().put("files",selected)))));
    }
    private static JSONObject audience(DeviceTaskEnvelope request,JSONObject resource,long accepted,long deadline)throws Exception{return new JSONObject().put("kind","operation").put("objectId",request.operationId()).put("originDeviceId",request.originDeviceId()).put("callerDeviceId",request.callerDeviceId()).put("targetDeviceId",request.targetDeviceId()).put("instanceId","caller-fixture").put("rootTaskId",request.rootTaskId()).put("localTaskId",request.localTaskId()).put("requestRunId",request.runId()).put("requestExecutorEpoch",request.executorEpoch()).put("grantId",request.grantId()).put("authorizationEpoch",request.authorizationEpoch()).put("resourceBinding",resource).put("acceptedAt",accepted).put("retainedUntil",deadline+60000);}
    private static JSONObject response(DeviceTaskEnvelope request,String executor,JSONObject result)throws Exception{return new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType","DeviceResponse").put("requestId",request.requestId()).put("method",request.method()).put("executorDeviceId",executor).put("instanceId","caller-fixture").put("result",result);}
}
