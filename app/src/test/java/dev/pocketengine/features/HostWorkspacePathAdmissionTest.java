package dev.pocketengine.features;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
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
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real workspace, SQL operation journal, Host and Runner; only the model uses loopback. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class HostWorkspacePathAdmissionTest {
    @Test public void writePathIsRefusedBeforePreparingAnOperation()throws Exception {run("workspace_write");}
    @Test public void readPathIsRefusedWithoutStoppingTheTask()throws Exception {run("workspace_read");}
    private void run(String firstTool)throws Exception {
        var android=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(android);
        WorkspaceModule workspace=new WorkspaceModule(new FeatureContext(android,null,null,event->{}));
        FeatureHost host=new FeatureHost(event->{},tasks,()->false);host.register(workspace);
        RunControl control=new RunControl();List<JSONObject> requests=new CopyOnWriteArrayList<>(),events=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try {
            String project=workspace.handle("create",new JSONObject().put("name","Path admission fixture")).getString("id");
            JSONObject write=new JSONObject().put("projectId",project).put("path","saved.txt").put("content","original bytes").put("expectedVersion",0);
            workspace.handle("write",write);JSONObject stale=firstTool.equals("workspace_write")?new JSONObject(write.toString()).put("path","./saved.txt").put("content","must not overwrite"):new JSONObject().put("projectId",project).put("path","./saved.txt");
            String goal="Write the requested replacement to saved.txt. If the tool rejects the request, read the original current file using a correct relative path and report without overwriting.";
            String id=tasks.createCapabilityTask(goal,"").getString("id");tasks.initializeExecutionScope(id,12);JSONObject attempt=tasks.startAttempt(id,"capabilities",12);
            String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);
            HostToolSupervisor nativePort=new HostToolSupervisor(host,tasks,id,run,epoch,true);
            Set<String> names=Set.of("workspace_write","workspace_read");JSONArray offered=new JSONArray();for(int i=0;i<nativePort.tools().length();i++){JSONObject tool=nativePort.tools().getJSONObject(i);if(names.contains(tool.getJSONObject("function").getString("name")))offered.put(tool);}
            assertEquals(2,offered.length());tasks.authorizeTools(id,run,new JSONArray(names));RunJournal journal=tasks.journal(id,run,epoch,control);
            CapabilityPort port=new CapabilityPort(){
                public JSONObject contextSnapshot(String query)throws Exception{return nativePort.contextSnapshot(query);}
                public JSONArray tools(){return offered;}public boolean supports(String name){return names.contains(name);}
                public boolean sideEffecting(String name){return nativePort.sideEffecting(name);}
                public void validate(String name,JSONObject args)throws Exception{nativePort.validate(name,args);}
                public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{return nativePort.execute(name,args,original);}
            };
            server.createContext("/",exchange->{try{
                requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));int turn=requests.size();JSONObject message=new JSONObject().put("role","assistant");
                if(turn<=2){String name=turn==1?firstTool:"workspace_read";JSONObject args=turn==1?stale:new JSONObject().put("projectId",project).put("path","saved.txt");message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","cas_"+turn).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));}
                else message.put("content","The original file remains unchanged. The invalid relative path was rejected.");
                byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",turn<=2?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Exception failed){throw new RuntimeException(failed);}finally{exchange.close();}});server.start();
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,8,10,"openai_chat",1000).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            RunOutcome outcome=new AgentRunner(null,port,budget).run(id,goal,config,control,events::add,journal);
            JSONObject stored=tasks.get(id,false);JSONArray operations=stored.getJSONArray("operations");
            assertEquals("Path syntax refusal must return before dispatch: "+outcome.summary+" operations="+operations,3,requests.size());
            JSONObject refusal=receipt(requests.get(1),"cas_1");assertFalse(refusal.getBoolean("ok"));assertFalse(refusal.getBoolean("accepted"));assertFalse(refusal.getBoolean("nativeDispatched"));assertEquals("invalid_arguments",refusal.getString("code"));assertFalse(refusal.has("content"));
            assertEquals("original bytes",receipt(requests.get(2),"cas_2").getString("content"));
            assertEquals(0,operations.length());assertTrue(!stored.has("approvals")||stored.getJSONArray("approvals").length()==0);
            assertEquals(1,workspace.fileSnapshotForHost(project,"saved.txt",0).version);assertEquals("original bytes",workspace.handle("read",new JSONObject().put("projectId",project).put("path","saved.txt")).getString("content"));
            journal.restore(); // Settled known refusal must not require reconciliation or replay.
            for(String tool:new String[]{"workspace_read","workspace_write","workspace_history","workspace_diff","workspace_create_artifact"}){
                JSONObject valid=new JSONObject().put("projectId",project).put("path","dir/saved.txt");
                if(tool.equals("workspace_write"))valid.put("content","not dispatched").put("expectedVersion",0);
                if(tool.equals("workspace_diff"))valid.put("fromVersion",1);
                nativePort.validate(tool,valid);
                for(String bad:new String[]{"./saved.txt","../saved.txt","/saved.txt","dir//saved.txt","dir\\saved.txt"})
                    assertThrows(IllegalArgumentException.class,()->nativePort.validate(tool,new JSONObject(valid.toString()).put("path",bad)));
            }
            tasks.authorizeTools(id,run,new JSONArray());assertThrows(SecurityException.class,()->nativePort.execute("workspace_write",new JSONObject(write.toString()),control));
            control.cancel();assertThrows(InterruptedException.class,()->nativePort.execute("workspace_write",new JSONObject(write.toString()),control));
        }finally{control.cancel();server.stop(0);host.close();org.robolectric.shadows.ShadowLooper.idleMainLooper();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
    private static JSONObject receipt(JSONObject request,String id)throws Exception{JSONArray rows=request.getJSONArray("messages");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(id.equals(row.optString("tool_call_id")))return new JSONObject(row.getString("content"));}throw new AssertionError("Missing receipt "+id);}
}
