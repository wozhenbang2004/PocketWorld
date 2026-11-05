package dev.pocketengine.features;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.*;
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

/** Actual native skill module, supervisor, TaskStore and Runner; the model is loopback only. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class)
public final class HostSkillCatalogueArgumentsTest {
    @Test public void defaultQueryModeCannotUseCataloguePageSize()throws Exception {run(new JSONObject().put("pageSize",8));}
    @Test public void explicitQueryModeCannotUseCatalogueCursor()throws Exception {run(new JSONObject().put("mode","query").put("cursor",new JSONObject().put("catalogueDigest","a".repeat(64)).put("offset",0)));}
    private void run(JSONObject invalidArguments)throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());
        FeatureHost host=new FeatureHost(event->{},tasks,()->false);
        RunControl control=new RunControl();
        var skills=new dev.pocketengine.features.skills.SkillsModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));
        List<JSONObject> requests=new CopyOnWriteArrayList<>(),events=new ArrayList<>();
        List<String> dispatchedModes=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            JSONObject message=new JSONObject().put("role","assistant");
            if(requests.size()<=2){JSONArray calls=new JSONArray();for(String name:new String[]{"skills_list"})calls.put(new JSONObject().put("id","call_"+requests.size()+"_"+name).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",(requests.size()==1?new JSONObject(invalidArguments.toString()):new JSONObject().put("mode","catalogue").put("pageSize",8)).toString())));message.put("tool_calls",calls);}
            else message.put("content","No enabled skills are installed.");
            byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Exception failed){throw new RuntimeException(failed);}finally{exchange.close();}});server.start();
        try {
            host.register(skills);
            String id=tasks.createCapabilityTask("Inspect available skill metadata",UUID.randomUUID().toString()).getString("id");
            tasks.initializeExecutionScope(id,8);JSONObject attempt=tasks.startAttempt(id,"capabilities",8);
            String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);
            HostToolSupervisor nativePort=new HostToolSupervisor(host,tasks,id,run,epoch,false);
            JSONArray names=new JSONArray(),offered=new JSONArray();for(int i=0;i<nativePort.tools().length();i++){JSONObject tool=nativePort.tools().getJSONObject(i);String name=tool.getJSONObject("function").getString("name");if(name.equals("skills_list")){names.put(name);offered.put(tool);}}
            assertEquals(1,offered.length());tasks.authorizeTools(id,run,names);
            CapabilityPort port=new CapabilityPort(){
                public JSONObject contextSnapshot(String query)throws Exception{return nativePort.contextSnapshot(query);}
                public JSONArray tools(){return offered;}
                public boolean supports(String name){return name.equals("skills_list");}
                public boolean sideEffecting(String name){return nativePort.sideEffecting(name);}
                public void validate(String name,JSONObject args)throws Exception{nativePort.validate(name,args);}
                public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{dispatchedModes.add(args.optString("mode","query"));return nativePort.execute(name,args,original);}
            };
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,8,10,"openai_chat",1000).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            RunOutcome outcome=new AgentRunner(null,port,budget).run(id,"Inspect available skill metadata; do not make changes",config,control,events::add);
            assertEquals("The model must get a correctable admission refusal: "+outcome.summary,3,requests.size());assertEquals(List.of("catalogue"),dispatchedModes);
            JSONObject result=null;JSONArray messages=requests.get(2).getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject row=messages.getJSONObject(i);if("call_2_skills_list".equals(row.optString("tool_call_id")))result=new JSONObject(row.getString("content"));}assertNotNull(result);assertEquals(0,result.getJSONArray("skills").length());assertEquals(0,result.getInt("totalCount"));
            assertThrows(IllegalArgumentException.class,()->nativePort.validate("skills_list",invalidArguments));nativePort.validate("skills_list",new JSONObject().put("mode","catalogue").put("pageSize",8));
            assertThrows(SecurityException.class,()->nativePort.validate("skills_list",new JSONObject().put("scope",new JSONObject())));
            tasks.authorizeTools(id,run,new JSONArray());assertThrows(SecurityException.class,()->nativePort.execute("skills_list",new JSONObject().put("mode","catalogue"),control));
            control.cancel();assertThrows(InterruptedException.class,()->nativePort.execute("skills_list",new JSONObject().put("mode","catalogue"),control));
        }finally{control.cancel();server.stop(0);host.close();var db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((SQLiteDatabase)db.get(tasks)).close();}
    }
}
