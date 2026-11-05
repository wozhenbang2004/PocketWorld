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

/** Actual native memory module, supervisor, TaskStore and Runner; the model is loopback only. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class)
public final class HostMemoryQueryLimitTest {
    @Test public void overlongQueriesAreRejectedBeforeDispatchAndCanBeCorrected()throws Exception {run("x".repeat(501));}
    @Test public void normalizationExpansionIsRejectedBeforeDispatchAndCanBeCorrected()throws Exception {run("ﬃ".repeat(167));}
    private void run(String invalidQuery)throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());
        FeatureHost host=new FeatureHost(event->{},tasks,()->false);
        RunControl control=new RunControl();
        var memory=new dev.pocketengine.features.memory.MemoryModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));
        List<JSONObject> requests=new CopyOnWriteArrayList<>(),events=new ArrayList<>();
        List<String> dispatchedQueries=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            JSONObject message=new JSONObject().put("role","assistant");
            if(requests.size()<=2){JSONArray calls=new JSONArray();for(String name:new String[]{"memory_search","memory_recall"})calls.put(new JSONObject().put("id","call_"+requests.size()+"_"+name).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",new JSONObject().put("query",requests.size()==1?invalidQuery:"fixture absent term").toString())));message.put("tool_calls",calls);}
            else message.put("content","No retained memory matches the query.");
            byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Exception failed){throw new RuntimeException(failed);}finally{exchange.close();}});server.start();
        try {
            host.register(memory);
            String id=tasks.createCapabilityTask("Search and recall saved memory",UUID.randomUUID().toString()).getString("id");
            tasks.initializeExecutionScope(id,8);JSONObject attempt=tasks.startAttempt(id,"capabilities",8);
            String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);
            HostToolSupervisor nativePort=new HostToolSupervisor(host,tasks,id,run,epoch,false);
            JSONArray names=new JSONArray(),offered=new JSONArray();for(int i=0;i<nativePort.tools().length();i++){JSONObject tool=nativePort.tools().getJSONObject(i);String name=tool.getJSONObject("function").getString("name");if(name.equals("memory_search")||name.equals("memory_recall")){names.put(name);offered.put(tool);}}
            assertEquals(2,offered.length());tasks.authorizeTools(id,run,names);
            CapabilityPort port=new CapabilityPort(){
                public JSONObject contextSnapshot(String query)throws Exception{return nativePort.contextSnapshot(query);}
                public JSONArray tools(){return offered;}
                public boolean supports(String name){return name.equals("memory_search")||name.equals("memory_recall");}
                public boolean sideEffecting(String name){return nativePort.sideEffecting(name);}
                public void validate(String name,JSONObject args)throws Exception{nativePort.validate(name,args);}
                public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{dispatchedQueries.add(args.getString("query"));return nativePort.execute(name,args,original);}
            };
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,8,10,"openai_chat",1000).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            new AgentRunner(null,port,budget).run(id,"Search and recall saved memory; do not make changes",config,control,events::add);
            assertEquals(3,requests.size());assertEquals(List.of("fixture absent term","fixture absent term"),dispatchedQueries);
            // Exactly the current host's advertised limit, not a larger envelope that dies in execute.
            for(int i=0;i<offered.length();i++)assertEquals(500,offered.getJSONObject(i).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("query").getInt("maxLength"));
            nativePort.validate("memory_search",new JSONObject().put("query","x".repeat(500)));
            assertThrows(IllegalArgumentException.class,()->nativePort.validate("memory_recall",new JSONObject().put("query","x".repeat(501))));
            int receipts=0;JSONArray messages=requests.get(2).getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if(!message.optString("role").equals("tool"))continue;JSONObject receipt=new JSONObject(message.getString("content"));if(receipt.has("records")){assertEquals(0,receipt.getJSONArray("records").length());receipts++;}}assertEquals(2,receipts);
            assertThrows(SecurityException.class,()->nativePort.execute("memory_search",new JSONObject().put("query","fixture").put("scope",new JSONObject().put("ownerId","other").put("spaceId","private")),control));
            tasks.authorizeTools(id,run,new JSONArray());
            assertThrows(SecurityException.class,()->nativePort.execute("memory_search",new JSONObject().put("query","fixture"),control));
            control.cancel();assertThrows(InterruptedException.class,()->nativePort.execute("memory_search",new JSONObject().put("query","fixture"),control));
        }finally{control.cancel();server.stop(0);host.close();var db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((SQLiteDatabase)db.get(tasks)).close();}
    }
}
