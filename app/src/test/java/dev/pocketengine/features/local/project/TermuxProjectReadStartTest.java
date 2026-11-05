package dev.pocketengine.features.local.project;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.*;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original Termux bridge/configuration, selected source, SQL and AgentRunner; no external process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TermuxProjectReadStartTest {
    private static final class NoProcess implements ProjectRuntime {
        public JSONObject describe()throws Exception{return new JSONObject().put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("identity","preview-read-fixture-v1").put("available",false).put("canExecute",false).put("phases",new JSONArray());}
        public RuntimeReceipt dispatch(RuntimeInvocation ignored){throw new AssertionError("Preview must not launch a process");}
        public RuntimeReceipt query(RuntimeExecutionRef ignored){throw new AssertionError("Preview must not query a process");}
        public RuntimeReceipt requestCancel(RuntimeExecutionRef ignored){throw new AssertionError("Preview must not cancel a process");}
    }
    @Test public void nativeTermuxBridgeCanStartReadOnlyModelTurnAndRetainsConfigurationFences()throws Exception {
        var android=RuntimeEnvironment.getApplication();var context=new FeatureContext(android,null,null,event->{});
        WorkspaceModule workspace=new WorkspaceModule(context);LocalModule local=new LocalModule(context,workspace,new NoProcess());TaskStore tasks=new TaskStore(android);RunControl control=new RunControl();ProjectCapabilityPort port=null;
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);List<JSONObject> requests=new CopyOnWriteArrayList<>(),events=new ArrayList<>();
        try {
            String html="<!doctype html><h1>Actual selected preview</h1>",project=workspace.handle("create",new JSONObject().put("name","Termux native read fixture")).getString("id");
            workspace.handle("write",new JSONObject().put("projectId",project).put("path","index.html").put("content",html).put("expectedVersion",0));
            String checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id"),digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");
            String task=tasks.createCapabilityTask("Read the selected file without launching a process","").getString("id");tasks.initializeExecutionScope(task,20);JSONObject attempt=tasks.startAttempt(task,"capabilities",20);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);
            tasks.bindDevelopmentContext(task,run,epoch,control,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime","termux").put("initialSourceDigest",digest).put("clientMessageId",UUID.randomUUID().toString()));
            var binding=new ModelCapabilityAdapters.Binding(task,run,epoch,control,false,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),control,null,local,tasks,checkout);
            JSONArray allowed=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)allowed.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(task,run,allowed);
            JSONObject args=new JSONObject().put("path","index.html");ProjectCapabilityPort actual=port;
            tasks.authorizeTools(task,run,new JSONArray().put("project_inspect"));assertThrows(SecurityException.class,()->actual.execute("project_read_file",args,control));tasks.authorizeTools(task,run,allowed);
            // Missing identity must fail only the original baseline, not mask the first-model regression.
            if(local.projectRuntimeForHost("termux").has("identity")){
                port.prepareTurn("Inspect current native source",0,0,control);assertTrue(port.responseCurrent(control));
                android.getSharedPreferences("local_development",0).edit().putBoolean("termuxEnabled",true).commit();assertFalse("Native bridge configuration change must invalidate prepared requests",port.responseCurrent(control));
                android.getSharedPreferences("local_development",0).edit().putBoolean("termuxEnabled",false).commit();assertTrue(port.responseCurrent(control));
            }
            server.createContext("/",exchange->{try {
                requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));JSONObject message=new JSONObject().put("role","assistant");
                if(requests.size()==1)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","read_1").put("type","function").put("function",new JSONObject().put("name","project_read_file").put("arguments",args.toString()))));else message.put("content","The native selected file was read without launching Termux.");
                byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",requests.size()==1?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Exception failed){throw new RuntimeException(failed);}finally{exchange.close();}});server.start();
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,8,10,"openai_chat",1000).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,128,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            RunOutcome outcome=new AgentRunner(null,port,budget).run(task,attempt.getString("goal"),config,control,events::add,tasks.journal(task,run,epoch,control));
            assertEquals("Original Termux port must send the first model request and native read receipt: "+outcome.summary+" evidence="+outcome.evidence+" events="+events,2,requests.size());JSONObject receipt=null;JSONArray messages=requests.get(1).getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if("read_1".equals(message.optString("tool_call_id")))receipt=new JSONObject(message.getString("content"));}assertNotNull(receipt);
            assertTrue(receipt.getBoolean("ok"));assertEquals(html,receipt.getString("content"));
            JSONObject facts=local.projectRuntimeForHost("termux");assertFalse(facts.getBoolean("installed"));assertFalse(facts.getBoolean("runCommandAvailable"));assertFalse(facts.getBoolean("enabled"));String identity=facts.getString("identity");assertTrue(identity.matches("[a-f0-9]{64}"));assertEquals(identity,local.projectRuntimeForHost("termux").getString("identity"));
            List<String> statuses=new ArrayList<>();for(JSONObject event:events)if("tool".equals(event.optString("type"))&&!"running".equals(event.optString("status")))statuses.add(event.getString("status"));assertEquals(List.of("completed"),statuses);
            control.cancel();assertThrows(SecurityException.class,()->actual.execute("project_read_file",args,control));
        }finally{control.cancel();if(port!=null)port.close();server.stop(0);local.close();workspace.close();org.robolectric.shadows.ShadowLooper.idleMainLooper();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
}
