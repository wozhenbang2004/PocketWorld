package dev.pocketengine.features.remote;

import android.app.Application;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real HTTP requests. No real worker or process-stop success is implied by the fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteOriginalCancellationTest {
    private static final String PROTOCOL="pocketworld.original-job-cancel.v1";
    static final class Server implements AutoCloseable {
        final HttpServer http;
        final AtomicInteger legacy=new AtomicInteger(),original=new AtomicInteger(),reads=new AtomicInteger();
        volatile JSONObject capability=capability(),lastBody;
        volatile String operation="original-operation",responseOperation="original-operation",status="stopping";
        volatile boolean loseResponse;
        volatile Runnable duringStatus=()->{};
        Server()throws Exception {
            http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/",exchange->{try{
                String path=exchange.getRequestURI().getPath();JSONObject result;int code=200;
                if(path.equals("/v1/status")){duringStatus.run();result=new JSONObject();if(capability!=null)result.put("originalJobCancellation",capability);}
                else if(path.endsWith("/cancel-original")){
                    original.incrementAndGet();lastBody=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    if(!operation.equals(lastBody.optString("jobOperationId"))){code=409;result=new JSONObject().put("error","Original operation changed");}
                    else {result=receipt(responseOperation,status).put("originalCancellation",new JSONObject().put("protocol",PROTOCOL).put("jobId","original-job").put("jobOperationId",responseOperation).put("identityMatched",true).put("effectsReversed",false));if(loseResponse){loseResponse=false;exchange.close();return;}}
                }else if(path.endsWith("/cancel")){legacy.incrementAndGet();result=receipt(operation,"cancelled");}
                else {reads.incrementAndGet();result=receipt(operation,"running");}
                byte[] bytes=(code==200?new JSONObject().put("ok",true).put("result",result):new JSONObject().put("ok",false).put("error",result.optString("error"))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}});http.start();
        }
        String base(){return "http://127.0.0.1:"+http.getAddress().getPort();}
        public void close(){http.stop(0);}
    }
    static JSONObject capability()throws Exception{return new JSONObject().put("protocol",PROTOCOL).put("supported",true).put("endpoint","/v1/jobs/{jobId}/cancel-original").put("requiredFields",new JSONArray().put("jobOperationId")).put("identityCheck","locked_job_operation_before_mutation").put("scope","job_manager_cli_jobs_only");}
    static JSONObject receipt(String operation,String status)throws Exception{return new JSONObject().put("id","original-job").put("operationId",operation).put("environmentId","workspace").put("worker","command").put("status",status);}
    private FeatureContext context(boolean clean){var app=RuntimeEnvironment.getApplication();if(clean){app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");}return new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});}
    private RemoteModule module(Server server,boolean clean)throws Exception {
        RemoteModule module=new RemoteModule(context(clean),(id,base)->"fixture-only-token-longer-than-32-characters",path->{});
        java.lang.reflect.Field field=RemoteModule.class.getDeclaredField("poller");field.setAccessible(true);((java.util.concurrent.ScheduledExecutorService)field.get(module)).shutdownNow();
        if(clean)module.handle("saveProfile",new JSONObject().put("id","computer").put("name","computer").put("baseUrl",server.base()));return module;
    }
    private JSONObject target(Server server)throws Exception{return new JSONObject().put("profileId","computer").put("serviceBase",server.base()).put("jobId","original-job").put("jobOperationId","original-operation");}
    @Test public void cancellationUsesLockedOriginalOperationAndDoesNotClaimProcessExit()throws Exception {
        try(Server server=new Server();RemoteModule module=module(server,true)){
            JSONObject actual=module.handle("cancelJob",target(server));assertEquals(0,server.legacy.get());assertEquals(1,server.original.get());assertEquals(1,server.lastBody.length());assertEquals("original-operation",server.lastBody.getString("jobOperationId"));assertEquals("stopping",actual.getString("status"));assertFalse(actual.optBoolean("dispatchFenceFinal"));assertFalse(actual.getJSONObject("originalCancellation").getBoolean("effectsReversed"));
        }
    }
    @Test public void unsupportedOrIncompleteCapabilityNeverFallsBackToLegacyCancellation()throws Exception {
        for(String missing:new String[]{"all","protocol","supported","endpoint","requiredFields","identityCheck","scope"})try(Server server=new Server();RemoteModule module=module(server,true)){
            if(missing.equals("all"))server.capability=null;else server.capability.remove(missing);
            JSONObject actual=module.handle("cancelJob",target(server));assertEquals(missing,0,server.legacy.get());assertEquals(missing,0,server.original.get());assertEquals("unknown",actual.getString("status"));assertEquals(Boolean.FALSE,actual.opt("cancellationDispatched"));
        }
    }
    @Test public void operationMismatchIsRejectedInsideServerBeforeMutation()throws Exception {
        try(Server server=new Server();RemoteModule module=module(server,true)){
            server.operation="new-operation";JSONObject actual=module.handle("cancelJob",target(server));assertEquals(0,server.legacy.get());assertEquals(1,server.original.get());assertEquals("original-operation",server.lastBody.getString("jobOperationId"));assertEquals("unknown",actual.getString("status"));assertEquals(0,module.snapshot().getJSONArray("jobs").length());
        }
    }
    @Test public void missingOriginalIdentityNeverSendsAnyCancellation()throws Exception {
        try(Server server=new Server();RemoteModule module=module(server,true)){
            JSONObject input=target(server);input.remove("jobOperationId");assertThrows(IllegalArgumentException.class,()->module.handle("cancelJob",input));
            assertEquals(0,server.legacy.get());assertEquals(0,server.original.get());
        }
    }
    @Test public void changedProfileAfterCapabilityReadCannotReachEitherComputer()throws Exception {
        try(Server server=new Server();Server replacement=new Server();RemoteModule module=module(server,true)){
            server.duringStatus=()->{try{module.handle("saveProfile",new JSONObject().put("id","computer").put("name","replacement").put("baseUrl",replacement.base()));}catch(Exception error){throw new RuntimeException(error);}};
            try{module.handle("cancelJob",target(server));}catch(SecurityException|IllegalStateException expected){}
            assertEquals(0,server.original.get());assertEquals(0,server.legacy.get());assertEquals(0,replacement.original.get());assertEquals(0,replacement.legacy.get());assertEquals(0,replacement.reads.get());
        }
    }
    @Test public void lostOrForeignCancellationReceiptRemainsUnknownAndIsNeverRepostedAfterRestart()throws Exception {
        for(boolean lost:new boolean[]{true,false})try(Server server=new Server()){
            RemoteModule first=module(server,true);try{if(lost)server.loseResponse=true;else server.responseOperation="foreign-operation";
                JSONObject actual=first.handle("cancelJob",target(server));assertEquals("unknown",actual.getString("status"));assertEquals(0,first.snapshot().getJSONArray("jobs").length());assertEquals(1,server.original.get());
            }finally{first.close();}
            try(RemoteModule recovered=module(server,false)){JSONObject actual=recovered.handle("cancelJob",target(server));assertEquals(1,server.original.get());assertEquals(0,server.legacy.get());assertTrue(actual.getBoolean("queryOnly"));assertEquals("original-operation",actual.getString("operationId"));}
        }
    }
    private JSONObject target()throws Exception{return new JSONObject().put("jobId","job-original").put("jobOperationId","operation-original").put("serviceBase","http://127.0.0.1:8766");}
    private JSONObject receipt()throws Exception{return new JSONObject().put("id","job-original").put("operationId","operation-original").put("serviceBase","http://127.0.0.1:8766").put("status","cancelled")
            .put("originalCancellation",new JSONObject().put("protocol",RemoteOriginalCancellation.PROTOCOL).put("jobId","job-original").put("jobOperationId","operation-original").put("identityMatched",true).put("effectsReversed",false));}
    @Test public void capabilityRequiresTheExactServerMutationContract()throws Exception {
        RemoteOriginalCancellation.requireSupport(new JSONObject().put("originalJobCancellation",capability()));
        for(String field:new String[]{"supported","protocol","endpoint","identityCheck","scope","requiredFields"}){
            JSONObject missing=capability();missing.remove(field);assertThrows(field,SecurityException.class,()->RemoteOriginalCancellation.requireSupport(new JSONObject().put("originalJobCancellation",missing)));
        }
        for(JSONObject bad:new JSONObject[]{capability().put("supported","true"),capability().put("endpoint","/v1/jobs/{jobId}/cancel"),capability().put("requiredFields",new JSONArray()),capability().put("requiredFields",new JSONArray().put("jobOperationId").put("profileId"))})
            assertThrows(SecurityException.class,()->RemoteOriginalCancellation.requireSupport(new JSONObject().put("originalJobCancellation",bad)));
    }
    @Test public void proofMustMatchTheOriginalJobOperationAndOriginWithStrictBooleans()throws Exception {
        RemoteOriginalCancellation.requireReceipt(target(),receipt());
        for(String field:new String[]{"id","operationId","serviceBase"}){
            JSONObject wrong=receipt().put(field,"successor");assertThrows(field,SecurityException.class,()->RemoteOriginalCancellation.requireReceipt(target(),wrong));
        }
        for(String field:new String[]{"protocol","jobId","jobOperationId","identityMatched","effectsReversed"}){
            JSONObject missing=receipt();missing.getJSONObject("originalCancellation").remove(field);assertThrows(field,SecurityException.class,()->RemoteOriginalCancellation.requireReceipt(target(),missing));
        }
        for(String field:new String[]{"identityMatched","effectsReversed"}){
            JSONObject coerced=receipt();coerced.getJSONObject("originalCancellation").put(field,field.equals("identityMatched")?"true":"false");assertThrows(SecurityException.class,()->RemoteOriginalCancellation.requireReceipt(target(),coerced));
        }
    }
}
