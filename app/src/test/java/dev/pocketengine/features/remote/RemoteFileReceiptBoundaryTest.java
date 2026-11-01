package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.Context;
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

/** Real loopback receipt delivery. No remote computer filesystem or CLI effect is represented. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteFileReceiptBoundaryTest {
    private RemoteModule module(Server server)throws Exception {
        Context android=RuntimeEnvironment.getApplication();android.deleteFile("remote-profiles.json");android.deleteFile("remote-operations.json");
        RemoteModule result=new RemoteModule(new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{}),(id,base)->"fixture-companion-token-longer-than-32-characters",path->{});
        result.handle("saveProfile",new JSONObject().put("id","computer").put("name","original computer").put("baseUrl",server.base()));return result;
    }
    private JSONObject write()throws Exception{return new JSONObject().put("profileId","computer").put("environmentId","workspace").put("operationId","file-operation").put("path","src/actual.txt").put("content","reviewed text").put("expectedVersion","absent");}
    private JSONObject query(String operation)throws Exception{return new JSONObject().put("profileId","computer").put("operationId",operation);}
    private JSONObject operation(RemoteModule module,String id)throws Exception {JSONArray rows=module.snapshot().getJSONArray("operations");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).optString("operationId")))return rows.getJSONObject(i);throw new AssertionError("Missing original intent");}
    @Test public void anUnboundSubmitReceiptStaysUnknownAndTheSameOperationOnlyQueriesItsReceipt()throws Exception {
        for(String field:new String[]{"operationId","environmentId","path"})try(Server server=new Server();RemoteModule module=module(server)){
            server.changedField=field;JSONObject first=module.handle("writeFile",write());
            assertEquals(first.toString(),"unknown",first.getString("status"));assertEquals("file-operation",first.getString("operationId"));assertEquals("unknown",operation(module,"file-operation").getString("status"));
            server.changedField="";JSONObject same=module.handle("writeFile",write());
            assertEquals("completed",same.getString("status"));assertEquals(1,server.writes.get());assertEquals(1,server.fileQueries.get());
        }
    }
    @Test public void wrongOrIncompleteTerminalFileReceiptsCannotSettleTheOriginalIntent()throws Exception {
        try(Server server=new Server();RemoteModule module=module(server)){
            server.dropWrite=true;assertEquals("unknown",module.handle("writeFile",write()).getString("status"));
            for(String field:new String[]{"operationId","environmentId","path"}){
                server.changedField=field;assertThrows(SecurityException.class,()->module.handle("reconcileFileWrite",query("file-operation")));
                assertEquals("unknown",operation(module,"file-operation").getString("status"));
            }
            server.changedField="";server.missingTarget=true;assertThrows(SecurityException.class,()->module.handle("reconcileFileWrite",query("file-operation")));
            server.missingTarget=false;assertEquals("completed",module.handle("reconcileFileWrite",query("file-operation")).getString("status"));assertEquals(1,server.writes.get());
        }
    }
    @Test public void aGenericOperationQueryUsesTheOriginalFileReceiptRouteWithoutPostingAgain()throws Exception {
        try(Server server=new Server();RemoteModule module=module(server)){
            server.dropWrite=true;assertEquals("unknown",module.handle("writeFile",write()).getString("status"));
            JSONObject actual=module.handle("reconcileOperation",query("file-operation"));
            assertEquals("completed",actual.getString("status"));assertEquals("src/actual.txt",actual.getString("path"));assertEquals(1,server.fileQueries.get());assertEquals(0,server.jobQueries.get());assertEquals(1,server.writes.get());
        }
    }
    @Test public void fileQueriesCannotReinterpretAJobOrIgnoreTheReviewedServiceAddress()throws Exception {
        try(Server server=new Server();RemoteModule module=module(server)){
            module.handle("submitJob",new JSONObject().put("profileId","computer").put("environmentId","workspace").put("operationId","job-operation").put("worker","command").put("argv",new JSONArray().put("pwd")));
            assertThrows(SecurityException.class,()->module.handle("reconcileFileWrite",query("job-operation")));assertEquals(0,server.fileQueries.get());assertEquals("settled",operation(module,"job-operation").getString("status"));
            server.dropWrite=true;module.handle("writeFile",write());
            assertThrows(SecurityException.class,()->module.handle("reconcileFileWrite",query("file-operation").put("serviceBase",server.base()+"/another-service")));assertEquals(0,server.fileQueries.get());assertEquals(1,server.writes.get());
        }
    }
    private static final class Server implements AutoCloseable {
        final HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final AtomicInteger writes=new AtomicInteger(),fileQueries=new AtomicInteger(),jobQueries=new AtomicInteger();
        volatile boolean dropWrite,missingTarget;volatile String changedField="";volatile JSONObject original;
        Server()throws Exception {http.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath();JSONObject result;
            if(exchange.getRequestMethod().equals("POST")){
                JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                if(path.endsWith("/file")){
                    writes.incrementAndGet();original=new JSONObject().put("operationId",body.getString("operationId")).put("environmentId","workspace").put("path",body.getString("path")).put("status","completed");
                    if(dropWrite){dropWrite=false;exchange.close();return;}result=reply();
                }else result=new JSONObject().put("id","job-id").put("operationId",body.getString("operationId")).put("environmentId",body.getString("environmentId")).put("worker",body.getString("worker")).put("status","completed");
            }else if(path.startsWith("/v1/file-operations/")){fileQueries.incrementAndGet();result=original==null?new JSONObject().put("operationId",path.substring(path.lastIndexOf('/')+1)).put("status","not_found"):reply();}
            else{jobQueries.incrementAndGet();result=new JSONObject().put("operationId",path.substring(path.lastIndexOf('/')+1)).put("status","not_found");}
            byte[] bytes=new JSONObject().put("ok",true).put("result",result).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        }catch(Exception failure){exchange.close();throw new IllegalStateException(failure);}});http.start();}
        JSONObject reply()throws Exception {JSONObject result=new JSONObject(original.toString());if(!changedField.isEmpty())result.put(changedField,"different-receipt-target");if(missingTarget){result.remove("environmentId");result.remove("path");}return result;}
        String base(){return "http://127.0.0.1:"+http.getAddress().getPort();}@Override public void close(){http.stop(0);}
    }
}
