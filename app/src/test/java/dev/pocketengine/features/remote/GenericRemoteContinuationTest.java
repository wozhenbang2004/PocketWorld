package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class GenericRemoteContinuationTest {
    private void reply(HttpServer.Exchange exchange,JSONObject value)throws Exception {byte[] bytes=new JSONObject().put("ok",true).put("result",value).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
    @Test public void originalGrantedHostProbeRoutesActualSchemaToSelectionEvidenceWithoutCreatingJob()throws Exception {
        AtomicInteger probes=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String service="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/v1/workers/probe",exchange->{JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("acp_fixture",input.getString("worker"));assertEquals("c".repeat(64),input.getString("environmentBinding"));assertEquals("model-1",input.getString("model"));assertEquals("review",input.getString("modeId"));assertFalse(input.has("argv"));assertFalse(input.has("_taskId"));probes.incrementAndGet();reply(exchange,RemoteHarnessSelectionTest.probe("acp_fixture").put("harnessSelection",RemoteHarnessSelectionTest.selection("acp_fixture")));});server.start();
        Context context=RuntimeEnvironment.getApplication();AtomicBoolean ready=new AtomicBoolean(true);dev.pocketengine.store.TaskStore tasks=new dev.pocketengine.store.TaskStore(context,ready::get,(stage,version)->{});
        try(dev.pocketengine.features.FeatureHost host=new dev.pocketengine.features.FeatureHost(event->{})){
            RemoteModule module=new RemoteModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}),(id,base)->"fixture-token-"+"x".repeat(32),path->{});host.register(module);JSONObject profile=module.handle("saveProfile",new JSONObject().put("id","computer").put("name","Fixture computer").put("baseUrl",service));
            JSONObject started=tasks.startAttempt(tasks.createCapabilityTask("Inspect the selected harness","").getString("id"),"capabilities",8);String task=started.getString("id"),run=started.getString("runId");
            try(dev.pocketengine.features.HostToolSupervisor port=new dev.pocketengine.features.HostToolSupervisor(host,tasks,task,run,started.getInt("coordinatorEpoch"),false)){
                JSONObject definition=null;org.json.JSONArray catalog=port.tools();for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getJSONObject("function").getString("name").equals("remote_probe_worker"))definition=catalog.getJSONObject(i).getJSONObject("function");assertNotNull(definition);assertFalse(port.sideEffecting("remote_probe_worker"));assertFalse(definition.getJSONObject("parameters").getBoolean("additionalProperties"));
                JSONObject args=new JSONObject().put("profileId","computer").put("bindingHash",profile.getString("bindingHash")).put("environmentId","project").put("environmentBinding","c".repeat(64)).put("worker","acp_fixture").put("model","model-1").put("modeId","review");
                assertThrows(SecurityException.class,()->port.execute("remote_probe_worker",args,new dev.pocketengine.core.RunControl()));assertEquals(0,probes.get());tasks.authorizeTools(task,run,new org.json.JSONArray().put("remote_probe_worker"));
                assertEquals(dev.pocketengine.core.ExecutionPlanning.Access.READ,port.planningAccess("remote_probe_worker",args));assertThrows(IllegalArgumentException.class,()->port.validate("remote_probe_worker",new JSONObject(args.toString()).put("argv",new org.json.JSONArray().put("sh"))));
                JSONObject observed=port.execute("remote_probe_worker",args,new dev.pocketengine.core.RunControl());RemoteHarnessSelection.requireSame(RemoteHarnessSelectionTest.selection("acp_fixture"),observed.getJSONObject("harnessSelection"),"acp_fixture");assertEquals(1,probes.get());assertEquals(0,module.snapshot().getJSONArray("operations").length());
            }
        }finally{server.stop(0);ready.set(false);tasks.closeForMaintenance();}
    }
    @Test public void continuationRefreshesSameSelectionAndKnownOperationNeverSubmitsAgain()throws Exception {
        String oldOperation=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();AtomicInteger probes=new AtomicInteger(),posts=new AtomicInteger();AtomicBoolean changed=new AtomicBoolean();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String service="http://127.0.0.1:"+server.getAddress().getPort();
        JSONObject old=RemoteHarnessSelectionTest.terminal("acp_fixture").put("id","old-job").put("operationId",oldOperation).put("environmentId","project").put("environmentBinding","c".repeat(64)).put("serviceBase",service).put("controls",new JSONObject().put("threadResume",true));
        JSONObject resumed=new JSONObject(old.toString()).put("id","new-job").put("operationId",operation).put("resumeJobId","old-job");
        server.createContext("/v1/workers/probe",exchange->{JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("acp_fixture",input.getString("worker"));assertEquals("c".repeat(64),input.getString("environmentBinding"));assertFalse(input.has("modeId"));assertFalse(input.has("model"));probes.incrementAndGet();JSONObject result=RemoteHarnessSelectionTest.probe("acp_fixture");if(changed.get())result.put("capabilityRevision","d".repeat(64));reply(exchange,result);});
        server.createContext("/v1/jobs",exchange->{if(exchange.getRequestMethod().equals("POST")){assertTrue(probes.get()>0);JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("old-job",input.getString("resumeJobId"));assertEquals("session-1",input.getString("threadId"));assertFalse(input.has("model"));assertFalse(input.has("modeId"));posts.incrementAndGet();reply(exchange,resumed);}else reply(exchange,exchange.getRequestURI().getPath().endsWith("old-job")?old:resumed);});
        server.createContext("/v1/operations",exchange->reply(exchange,resumed));server.start();Context context=RuntimeEnvironment.getApplication();
        try(RemoteModule module=new RemoteModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}),(id,base)->"fixture-token-"+"x".repeat(32),path->{})){
            module.handle("saveProfile",new JSONObject().put("id","computer").put("name","Fixture computer").put("baseUrl",service));
            module.handle("getJob",new JSONObject().put("profileId","computer").put("jobId","old-job").put("jobOperationId",oldOperation).put("serviceBase",service));
            JSONObject request=new JSONObject().put("profileId","computer").put("serviceBase",service).put("environmentId","project").put("environmentBinding","c".repeat(64)).put("worker","acp_fixture").put("operationId",operation).put("jobOperationId",oldOperation).put("resumeJobId","old-job").put("threadId","session-1").put("prompt","Continue the original task").put("readOnly",false).put("externalExecutionRiskAcknowledged",true).put("harnessSelection",RemoteHarnessSelectionTest.selection("acp_fixture"));
            assertEquals("new-job",module.handle("submitJob",request).getString("id"));assertEquals(1,posts.get());assertEquals(1,probes.get());module.handle("submitJob",request);assertEquals(1,posts.get());assertEquals(1,probes.get());
            assertThrows(IllegalStateException.class,()->module.handle("submitJob",new JSONObject(request.toString()).put("operationId",UUID.randomUUID().toString())));assertEquals(1,probes.get());
            changed.set(true);assertThrows(SecurityException.class,()->module.handle("submitJob",new JSONObject(request.toString()).put("operationId",UUID.randomUUID().toString()).put("resumeJobId","new-job").put("jobOperationId",operation)));assertEquals(2,probes.get());assertEquals(1,posts.get());
        }finally{server.stop(0);}
    }
}
