package dev.pocketengine.runtime;

import dev.pocketengine.core.*;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Runner/checkpoint/recovery and loopback model; tool port records dispatch boundaries. */
public final class ToolBatchTailReceiptTest {
    private static final class Journal implements RunJournal {
        JSONArray messages=new JSONArray();int steps;
        public JSONObject restore()throws Exception{return new JSONObject().put("messages",new JSONArray(messages.toString())).put("steps",steps);}
        public void checkpoint(JSONArray rows,int count,JSONObject observation)throws Exception{messages=new JSONArray(rows.toString());steps=count;}
        public String beginOperation(String name,JSONObject args){throw new AssertionError("This read-only fixture cannot prepare effects");}
        public void finishOperation(String id,JSONObject receipt){throw new AssertionError("This read-only fixture cannot finish effects");}
    }
    private static JSONObject call(String name)throws Exception{return new JSONObject().put("id","call_"+name).put("type","function").put("function",new JSONObject().put("name",name).put("arguments","{}"));}
    private static JSONObject receipt(JSONArray messages,String id)throws Exception{
        for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if("tool".equals(message.optString("role"))&&id.equals(message.optString("tool_call_id")))return new JSONObject(message.getString("content"));}
        throw new AssertionError("Missing receipt "+id);
    }
    private void run(JSONObject stop)throws Exception {
        List<JSONObject> requests=new CopyOnWriteArrayList<>();List<String> dispatched=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            JSONObject message=new JSONObject().put("role","assistant");
            if(requests.size()==1)message.put("tool_calls",new JSONArray().put(call("first_read")).put(call("second_read")));
            else message.put("content","已核对原记录，没有重放历史调用。");
            byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}});server.start();
        try {
            CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{JSONArray all=new JSONArray();for(String name:new String[]{"first_read","second_read"})all.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description","Read fixture result").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("required",new JSONArray()).put("additionalProperties",false))));return all;}
                public boolean supports(String name){return name.equals("first_read")||name.equals("second_read");}
                public boolean sideEffecting(String name){return false;}
                public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{dispatched.add(name);if(!name.equals("first_read"))throw new AssertionError("Tail must not enter dispatch");return new JSONObject(stop.toString());}
            };
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,8,10,"openai_chat",1000).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            Journal journal=new Journal();RunControl control=new RunControl();control.bindHostRun("task","run",1);
            RunOutcome stopped=new AgentRunner(null,port,budget).run("task","只读取当前记录，不做任何修改",config,control,event->{},journal);
            assertEquals("awaiting_user",stopped.status);assertEquals(1,requests.size());assertEquals(List.of("first_read"),dispatched);assertEquals(1,journal.steps);
            JSONObject first=receipt(journal.messages,"call_first_read"),tail=receipt(journal.messages,"call_second_read");
            assertEquals(stop.getString("code"),first.getString("code"));
            if(stop.has("requiresReconciliation"))assertTrue(first.getBoolean("requiresReconciliation"));
            assertEquals("batch_tail_not_dispatched",tail.getString("code"));assertFalse(tail.getBoolean("accepted"));assertFalse(tail.getBoolean("nativeDispatched"));
            // A separately requested recovery retains the known boundary, without replay.
            RunControl resumed=new RunControl();resumed.bindHostRun("task","next-run",2);
            new AgentRunner(null,port,budget).run("task","只读取当前记录，不做任何修改",config,resumed,event->{},journal);
            assertEquals(2,requests.size());assertEquals(List.of("first_read"),dispatched);
            JSONObject recovered=receipt(requests.get(1).getJSONArray("messages"),"call_second_read");
            assertEquals("batch_tail_not_dispatched",recovered.getString("code"));assertFalse(recovered.getBoolean("nativeDispatched"));
        }finally{server.stop(0);}
    }
    @Test public void approvalGateRetainsTheUndispatchedTail()throws Exception{run(new JSONObject().put("ok",false).put("code","approval_required").put("accepted",false));}
    @Test public void needsUserRetainsTheUndispatchedTail()throws Exception{run(new JSONObject().put("ok",false).put("code","needs_user").put("accepted",false));}
    @Test public void unknownFirstResultDoesNotMakeTheTailUnknown()throws Exception{run(new JSONObject().put("ok",false).put("code","fixture_result_unknown").put("status","unknown").put("accepted",JSONObject.NULL).put("requiresReconciliation",true));}
    @Test public void unverifiedProjectRunningRetainsTheUndispatchedTail()throws Exception{run(new JSONObject().put("ok",true).put("code","project_running"));}
    @Test public void nativePauseRetainsTheUndispatchedTail()throws Exception{run(new JSONObject().put("ok",false).put("code","fixture_native_pause").put("requiresPause",true));}
    @Test public void observationReconciliationRetainsTheUndispatchedTail()throws Exception{run(new JSONObject().put("ok",false).put("code","fixture_observation").put("nextObservationAction","reconcile"));}
    @Test public void lostAccessibilityRetainsTheUndispatchedTail()throws Exception{run(new JSONObject().put("ok",false).put("code","accessibility_unavailable"));}

    private void phoneStop(boolean failedPostObservation)throws Exception {
        List<JSONObject> requests=new CopyOnWriteArrayList<>();List<String> dispatched=new ArrayList<>();
        String first=failedPostObservation?"phone_wait":"phone_request_user";
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            JSONObject message=new JSONObject().put("role","assistant");
            if(requests.size()==1){JSONObject head=call(first);head.getJSONObject("function").put("arguments",(failedPostObservation?new JSONObject().put("ms",1):new JSONObject().put("reason","请核对手机上的选择")).toString());message.put("tool_calls",new JSONArray().put(head).put(call("second_read")));}
            else message.put("content","已核对原记录，没有重放历史调用。");
            byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}});server.start();
        try {
            PhonePort phone=new PhonePort(){int observations;
                public boolean isConnected(){return true;}
                public void clearReferences(){}
                public JSONObject observe(boolean image,RunControl control)throws Exception{control.check();return ++observations==2&&failedPostObservation?new JSONObject().put("ok",false).put("code","fixture_observation_unavailable"):new JSONObject().put("ok",true).put("nodes",new JSONArray());}
                public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("No phone action should enter native execution");}
            };
            CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","second_read").put("description","Read fixture result").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false))));}
                public boolean supports(String name){return name.equals("second_read");}
                public boolean sideEffecting(String name){return false;}
                public JSONObject execute(String name,JSONObject args,RunControl control){dispatched.add(name);throw new AssertionError("Tail must not enter dispatch");}
            };
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,8,10,"openai_chat",1000).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            Journal journal=new Journal();RunControl control=new RunControl();control.bindHostRun("task","run",1);
            RunOutcome stopped=new AgentRunner(phone,port,budget).run("task","在手机上操作并在需要时让我接管",config,control,event->{},journal);
            assertEquals(stopped.summary,"awaiting_user",stopped.status);assertEquals(1,requests.size());assertTrue(dispatched.isEmpty());assertEquals(1,journal.steps);
            JSONObject head=receipt(journal.messages,"call_"+first);assertTrue(head.getBoolean("ok"));
            if(!failedPostObservation)assertEquals("awaiting_user",head.getString("code"));
            JSONObject tail=receipt(journal.messages,"call_second_read");assertEquals("batch_tail_not_dispatched",tail.getString("code"));assertFalse(tail.getBoolean("accepted"));assertFalse(tail.getBoolean("nativeDispatched"));
            RunControl resumed=new RunControl();resumed.bindHostRun("task","next-run",2);new AgentRunner(phone,port,budget).run("task","在手机上操作并在需要时让我接管",config,resumed,event->{},journal);
            assertEquals(2,requests.size());assertTrue(dispatched.isEmpty());assertEquals("batch_tail_not_dispatched",receipt(requests.get(1).getJSONArray("messages"),"call_second_read").getString("code"));
        }finally{server.stop(0);}
    }
    @Test public void phoneOwnerHandoffRetainsTheUndispatchedTail()throws Exception{phoneStop(false);}
    @Test public void failedAutomaticObservationRetainsTheUndispatchedTail()throws Exception{phoneStop(true);}
}
