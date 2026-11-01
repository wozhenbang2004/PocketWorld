package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Local HTTP receipts, not a real CLI, account, SSH connection or business-effect proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteContinuationBoundaryTest {
    private static final String TOKEN="fixture-only-companion-token-longer-than-32-characters";
    private FeatureContext context(boolean clean){
        Context android=RuntimeEnvironment.getApplication();
        if(clean){android.deleteFile("remote-profiles.json");android.deleteFile("remote-operations.json");}
        return new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
    }
    private RemoteModule module(Server server,boolean clean)throws Exception {
        RemoteModule module=new RemoteModule(context(clean),(id,base)->TOKEN,path->{});
        module.handle("saveProfile",new JSONObject().put("id","computer").put("name","电脑").put("baseUrl",server.base()));
        return module;
    }
    private JSONObject first(String worker)throws Exception {
        return new JSONObject().put("profileId","computer").put("operationId","original-operation")
                .put("environmentId","workspace").put("worker",worker).put("prompt","original owner request").put("model","requested-alias");
    }
    private JSONObject continuation(Server server,String worker)throws Exception {
        return new JSONObject().put("profileId","computer").put("serviceBase",server.base()).put("jobOperationId","original-operation")
                .put("resumeJobId","original-job").put("threadId",worker.equals("claude")?Server.SESSION:"codex-thread")
                .put("environmentId","workspace").put("worker",worker).put("operationId","continuation-operation").put("prompt","new explicit owner input");
    }
    private void rejected(RemoteModule module,JSONObject input)throws Exception {
        try{module.handle("submitJob",input);fail("Unbound continuation was dispatched");}
        catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}
    }
    @Test public void claudeContinuationForwardsExactSourceAndExposesRealProtocolMetadata()throws Exception {
        try(Server server=new Server()){
            RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first("claude"));
                JSONObject source=module.snapshot().getJSONArray("jobs").getJSONObject(0);
                assertEquals(Server.SESSION,source.getString("sessionId"));
                assertEquals("prompt-request",source.getString("promptRequestId"));
                assertEquals("reported-concrete-model",source.getString("reportedModel"));
                assertEquals("requested-alias",source.getString("model"));
                assertTrue(source.getBoolean("protocolVerified"));assertTrue(source.getBoolean("controlProtocolVerified"));assertTrue(source.getBoolean("sessionIdentityConfirmed"));
                assertFalse(source.getBoolean("interruptAcknowledged"));assertFalse(source.getBoolean("remoteEffectsVerified"));
                assertEquals("sent",source.getJSONArray("approvalReceipts").getJSONObject(0).getString("status"));
                assertFalse(source.getJSONObject("capabilities").getBoolean("readOnlySandbox"));
                JSONObject result=module.handle("submitJob",continuation(server,"claude"));
                assertEquals("original-job",server.lastPost.getString("resumeJobId"));
                assertEquals(Server.SESSION,server.lastPost.getString("threadId"));
                assertEquals("continuation-operation",result.getString("operationId"));
                assertEquals("original-job",result.getString("resumeJobId"));
                JSONObject intent=module.snapshot().getJSONArray("operations").getJSONObject(1);
                assertEquals("original-operation",intent.getString("jobOperationId"));
                assertEquals(server.base(),intent.getString("sourceServiceBase"));
                assertFalse(server.lastPost.has("jobOperationId"));assertFalse(server.lastPost.has("serviceBase"));
            }finally{module.close();}
        }
    }
    @Test public void sourceProfileOriginJobOperationThreadEnvironmentAndWorkerAreNotInterchangeable()throws Exception {
        try(Server server=new Server();Server replacement=new Server()){
            RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first("claude"));
                String[][] changed={{"profileId","other"},{"serviceBase",replacement.base()},{"resumeJobId","another-job"},
                        {"jobOperationId","another-operation"},{"threadId","another-thread"},{"environmentId","another-workspace"},{"worker","codex_app_server"}};
                module.handle("saveProfile",new JSONObject().put("id","other").put("name","other").put("baseUrl",replacement.base()));
                for(String[] pair:changed)rejected(module,continuation(server,"claude").put(pair[0],pair[1]));
                assertEquals(1,server.posts.get());assertEquals(0,replacement.requests.get());
                module.handle("saveProfile",new JSONObject().put("id","computer").put("name","replacement").put("baseUrl",replacement.base()));
                rejected(module,continuation(server,"claude"));
                assertEquals(0,replacement.requests.get());
            }finally{module.close();}
        }
    }
    @Test public void continuationNeedsAnExplicitSourceAndCurrentResumableReceipt()throws Exception {
        try(Server server=new Server()){
            RemoteModule module=module(server,true);
            try{
                rejected(module,continuation(server,"claude"));
                module.handle("submitJob",first("claude"));
                for(String key:new String[]{"resumeJobId","jobOperationId","serviceBase","threadId"}){
                    JSONObject input=continuation(server,"claude");input.remove(key);rejected(module,input);
                }
                for(String status:new String[]{"running","unknown","interrupted"}){
                    server.sourceStatus=status;module.handle("getJob",new JSONObject().put("profileId","computer").put("jobId","original-job"));
                    rejected(module,continuation(server,"claude"));
                }
                server.sourceStatus="completed";server.canResume=false;
                module.handle("getJob",new JSONObject().put("profileId","computer").put("jobId","original-job"));rejected(module,continuation(server,"claude"));
                assertEquals(1,server.posts.get());
            }finally{module.close();}
        }
    }
    @Test public void onlyOneNewOperationMayConsumeTheSameSourceAndChangedResumeArgumentsCannotReuseIt()throws Exception {
        try(Server server=new Server()){
            RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first("codex_app_server"));JSONObject input=continuation(server,"codex_app_server");
                module.handle("submitJob",input);
                rejected(module,new JSONObject(input.toString()).put("operationId","second-continuation"));
                rejected(module,new JSONObject(input.toString()).put("resumeJobId","another-job"));
                rejected(module,new JSONObject(input.toString()).put("jobOperationId","another-operation"));
                assertEquals(2,server.posts.get());
                JSONObject same=module.handle("submitJob",input);assertEquals("continuation-job",same.getString("id"));
                assertEquals(2,server.posts.get());assertEquals(1,server.operationQueries.get());
                JSONObject codex=module.snapshot().getJSONArray("jobs").getJSONObject(0);
                // The fixture's false start ACK is not converted into a synthetic success ACK.
                assertFalse(codex.getBoolean("turnStartAcknowledged"));
            }finally{module.close();}
        }
    }
    @Test public void lostContinuationReplyAndRestartOnlyQueryTheOriginalOperation()throws Exception {
        try(Server server=new Server()){
            RemoteModule module=module(server,true);JSONObject input=continuation(server,"claude");
            try{
                module.handle("submitJob",first("claude"));server.dropNextPost=true;
                assertEquals("unknown",module.handle("submitJob",input).getString("status"));
            }finally{module.close();}
            module=module(server,false);
            try{
                assertEquals("continuation-job",module.handle("submitJob",input).getString("id"));
                assertEquals(2,server.posts.get());assertEquals(1,server.operationQueries.get());
            }finally{module.close();}
        }
    }
    @Test public void anUnknownIndependentSteerReceiptBlocksACompletedCodexSourceWithoutChangingItsTerminalState()throws Exception {
        try(Server server=new Server()){
            RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first("codex_app_server"));
                module.handle("steerJob",new JSONObject().put("profileId","computer").put("jobId","original-job").put("jobOperationId","original-operation")
                        .put("threadId","codex-thread").put("expectedTurnId","codex-turn").put("messageId","unknown-message").put("text","original-turn input"));
                rejected(module,continuation(server,"codex_app_server"));
                assertEquals(2,server.posts.get());
                assertEquals("completed",module.snapshot().getJSONArray("jobs").getJSONObject(0).getString("status"));
                assertEquals("unknown",module.snapshot().getJSONArray("operations").getJSONObject(1).getString("status"));
            }finally{module.close();}
        }
    }
    @Test public void archivedUnknownContinuationReceiptStaysQueryOnlyWithoutInventingSessionAcknowledgements()throws Exception {
        try(Server server=new Server()){
            RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first("claude"));JSONObject input=continuation(server,"claude");module.handle("submitJob",input);
                server.archiveOperation=true;JSONObject archived=module.handle("submitJob",input);
                assertEquals("unknown",archived.getString("status"));assertTrue(archived.getBoolean("archived"));
                assertFalse(archived.has("sessionId"));assertFalse(archived.has("protocolVerified"));
                assertEquals("original-operation",archived.getString("jobOperationId"));
                assertEquals(2,server.posts.get());assertEquals(1,server.operationQueries.get());
                rejected(module,new JSONObject(input.toString()).put("operationId","another-continuation"));
            }finally{module.close();}
        }
    }
    @Test public void returnedObjectsCannotTurnARealDisabledResumeControlIntoPermission()throws Exception {
        try(Server server=new Server()){
            RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first("claude"));server.canResume=false;
                JSONObject received=module.handle("getJob",new JSONObject().put("profileId","computer").put("jobId","original-job"));
                received.getJSONObject("controls").put("threadResume",true);
                JSONObject snapshot=module.snapshot().getJSONArray("jobs").getJSONObject(0);snapshot.getJSONObject("controls").put("threadResume",true);
                rejected(module,continuation(server,"claude"));assertEquals(1,server.posts.get());
            }finally{module.close();}
        }
    }
    @Test public void codexReadOnlyBoundaryCannotBeElevatedAndClaudeDoesNotClaimForcedReadOnlySandbox()throws Exception {
        for(String worker:new String[]{"codex_app_server","claude"})try(Server server=new Server()){
            server.sourceReadOnly=true;RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first(worker));
                rejected(module,continuation(server,worker).put("readOnly",worker.equals("claude")));
                module.handle("submitJob",continuation(server,worker));
                assertEquals(2,server.posts.get());assertFalse(server.lastPost.has("readOnly"));
                if(worker.equals("claude"))assertFalse(module.snapshot().getJSONArray("jobs").getJSONObject(0).getJSONObject("capabilities").getBoolean("readOnlySandbox"));
            }finally{module.close();}
        }
    }
    @Test public void reviewedControlBindingCannotReachAReplacementOriginOrAnotherOperation()throws Exception {
        try(Server server=new Server();Server replacement=new Server()){
            RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first("claude"));
                JSONObject input=new JSONObject().put("profileId","computer").put("jobId","original-job").put("jobOperationId","original-operation")
                        .put("serviceBase",replacement.base()).put("approvalRequestId","approval-request").put("decision","accept");
                for(String command:new String[]{"getJob","cancelJob","approveJob"})try{module.handle(command,input);fail("Control ignored reviewed origin");}catch(SecurityException expected){}
                input.put("serviceBase",server.base()).put("jobOperationId","another-operation");
                for(String command:new String[]{"getJob","cancelJob","approveJob"})try{module.handle(command,input);fail("Control ignored reviewed operation");}catch(SecurityException expected){}
                assertEquals(1,server.posts.get());assertEquals(0,replacement.requests.get());
            }finally{module.close();}
        }
    }
    @Test public void aJobLookupCannotReplaceItsRegisteredOperationWithAnotherReceipt()throws Exception {
        try(Server server=new Server()){
            RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first("claude"));server.wrongOperation=true;
                try{module.handle("getJob",new JSONObject().put("profileId","computer").put("jobId","original-job"));fail("Another operation replaced the source receipt");}catch(SecurityException expected){}
                assertEquals("original-operation",module.snapshot().getJSONArray("jobs").getJSONObject(0).getString("operationId"));
            }finally{module.close();}
        }
    }
    @Test public void anArtifactPayloadRemainsAnArtifactRatherThanASyntheticJobReceipt()throws Exception {
        try(Server server=new Server()){
            RemoteModule module=module(server,true);
            try{
                module.handle("submitJob",first("claude"));
                JSONObject artifact=module.handle("readArtifact",new JSONObject().put("profileId","computer").put("serviceBase",server.base())
                        .put("jobId","original-job").put("jobOperationId","original-operation").put("path","result.txt"));
                assertEquals("result.txt",artifact.getString("path"));assertEquals("b2s=",artifact.getString("base64"));
                assertFalse(artifact.has("id"));assertFalse(artifact.has("operationId"));
                assertEquals(1,module.snapshot().getJSONArray("jobs").length());
            }finally{module.close();}
        }
    }
    private static final class Server implements AutoCloseable {
        static final String SESSION="5bb8d91f-5d3a-4fd9-93db-667904e061b2";
        final ServerSocket listener;final Thread worker;final Map<String,JSONObject> jobs=new ConcurrentHashMap<>();
        final AtomicInteger requests=new AtomicInteger(),posts=new AtomicInteger(),operationQueries=new AtomicInteger();
        volatile boolean closed,dropNextPost,archiveOperation,sourceReadOnly,wrongOperation,canResume=true;volatile String sourceStatus="completed";volatile JSONObject lastPost;
        Server()throws Exception{listener=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));worker=new Thread(this::serve,"remote-continuation-fixture");worker.setDaemon(true);worker.start();}
        String base(){return "http://127.0.0.1:"+listener.getLocalPort();}
        private JSONObject receipt(JSONObject body)throws Exception {
            boolean resumed=body.has("resumeJobId"),claude=body.optString("worker").equals("claude");
            JSONObject value=new JSONObject().put("id",resumed?"continuation-job":"original-job").put("operationId",body.getString("operationId"))
                    .put("environmentId",body.getString("environmentId")).put("worker",body.getString("worker")).put("status","completed")
                    .put("protocol",claude?"claude_stream_json":"codex_app_server").put("threadId",claude?SESSION:"codex-thread")
                    .put("protocolVerified",true).put("model",body.optString("model","requested-alias")).put("readOnly",sourceReadOnly)
                    .put("turnStartAcknowledged",false).put("remoteEffectsVerified",false).put("controls",new JSONObject().put("threadResume",true))
                    .put("capabilities",new JSONObject().put("readOnlySandbox",!claude))
                    .put("approvalReceipts",new JSONArray().put(new JSONObject().put("requestId","approval-request").put("decision","accept").put("status","sent")));
            if(claude)value.put("sessionId",SESSION).put("promptRequestId","prompt-request").put("reportedModel","reported-concrete-model")
                    .put("controlProtocolVerified",true).put("sessionIdentityConfirmed",true).put("interruptAcknowledged",false);
            else value.put("turnId","codex-turn");
            if(resumed)value.put("resumeJobId",body.getString("resumeJobId"));return value;
        }
        private void serve(){while(!closed)try(Socket socket=listener.accept()){
            socket.setSoTimeout(3000);InputStream in=socket.getInputStream();String request=line(in),header;int length=0;
            while(!(header=line(in)).isEmpty())if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
            byte[] bytes=in.readNBytes(length);requests.incrementAndGet();JSONObject result=new JSONObject().put("status","not_found");
            if(request.startsWith("POST ")){
                lastPost=new JSONObject(new String(bytes,StandardCharsets.UTF_8));posts.incrementAndGet();
                if(request.contains("/steer "))result.put("messageId",lastPost.getString("messageId")).put("operationId",lastPost.getString("messageId")).put("jobId","original-job")
                        .put("jobOperationId","original-operation").put("threadId","codex-thread").put("expectedTurnId","codex-turn").put("status","unknown");
                else{result=receipt(lastPost);jobs.put(result.getString("id"),result);if(dropNextPost){dropNextPost=false;continue;}}
            }else if(request.contains("/operations/")){
                operationQueries.incrementAndGet();String operation=request.split("/operations/",2)[1].split(" ",2)[0];
                for(JSONObject value:jobs.values())if(operation.equals(value.optString("operationId")))result=new JSONObject(value.toString());
                if(archiveOperation)result=new JSONObject().put("id",result.getString("id")).put("operationId",operation).put("status","unknown").put("archived",true).put("lastKnownStatus","completed");
            }else if(request.contains("/jobs/original-job/artifact?")){
                result=new JSONObject().put("name","result.txt").put("path","result.txt").put("bytes",2).put("base64","b2s=").put("sha256","fixture-hash");
            }else if(request.contains("/jobs/original-job ")){
                result=new JSONObject(jobs.get("original-job").toString()).put("status",sourceStatus).put("controls",new JSONObject().put("threadResume",canResume));
                if(wrongOperation)result.put("operationId","unrelated-operation");
            }
            byte[] response=new JSONObject().put("ok",true).put("result",result).toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(response);out.flush();
        }catch(Exception error){if(!closed)throw new RuntimeException(error);}}
        private static String line(InputStream in)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1,c;while((c=in.read())!=-1){if(previous=='\r'&&c=='\n'){byte[] raw=bytes.toByteArray();return new String(raw,0,raw.length-1,StandardCharsets.US_ASCII);}bytes.write(c);previous=c;if(bytes.size()>8192)throw new IllegalStateException("Fixture line limit");}throw new IllegalStateException("Incomplete fixture headers");}
        @Override public void close()throws Exception{closed=true;listener.close();worker.join(1000);}
    }
}
