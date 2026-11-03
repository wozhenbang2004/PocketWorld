package dev.pocketengine.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;

/** Actual loopback Gateway/Runner, explicit native journal fixture; no phone/network time authority. */
public final class RequestClockRunnerTest {
    private static JSONObject clock(JSONArray messages)throws Exception {
        for(int i=0;i<messages.length();i++){String text=messages.getJSONObject(i).optString("content");String open="<native_request_clock>\n",close="\n</native_request_clock>";int start=text.indexOf(open);if(start>=0)return new JSONObject(text.substring(start+open.length(),text.indexOf(close,start)));}
        throw new AssertionError("Actual provider request did not contain the request-only native clock");
    }
    @Test public void sameTaskAcrossMidnightRefreshesActualWireButNeverOriginalCheckpointOrBudgets()throws Exception {
        List<JSONObject> requests=new CopyOnWriteArrayList<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));byte[] body=new JSONObject().put("id","own-clock-response").put("object","chat.completion").put("model","clock-fixture").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",new JSONObject().put("role","assistant").put("content","仅核对当前原任务，不派发动作。")).put("finish_reason","stop"))).put("usage",new JSONObject().put("prompt_tokens",10).put("completion_tokens",5).put("total_tokens",15)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}catch(Exception failed){try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        try {
            AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-02T23:59:59Z"));Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            CapabilityPort port=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return true;}public JSONObject execute(String name,JSONObject input,RunControl control){throw new AssertionError("A clock must never execute a tool");}};
            final JSONObject[] stored={new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content","Original policy")).put(new JSONObject().put("role","user").put("content","ORIGINAL OWNER GOAL"))).put("steps",0)};
            RunJournal journal=new RunJournal(){public JSONObject restore()throws Exception{return new JSONObject(stored[0].toString());}public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception{stored[0]=new JSONObject().put("messages",new JSONArray(messages.toString())).put("steps",steps).put("observation",observation);}public String beginOperation(String tool,JSONObject input){throw new AssertionError("Clock cannot create an operation");}public void finishOperation(String op,JSONObject receipt){throw new AssertionError("Clock cannot finish an operation");}};
            ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","own-clock-credential","clock-fixture",false,4,10,"openai_chat",1000);
            AgentRunner runner=new AgentRunner(null,port,budget,null,null,new RequestClockContext(clock,()->ZoneId.of("UTC")));
            for(int attempt=0;attempt<2;attempt++){
                if(attempt==1)now.set(Instant.parse("2026-11-03T00:00:01Z"));RunControl original=new RunControl();
                RunOutcome result=runner.run("same-original-task","ORIGINAL OWNER GOAL",config,original,event->{},journal);
                assertEquals(result.summary,"needs_confirmation",result.status);assertFalse(original.isCancelled());
                String checkpoint=stored[0].getJSONArray("messages").toString();assertTrue(checkpoint.contains("ORIGINAL OWNER GOAL"));assertFalse(checkpoint.contains("native_request_clock"));assertFalse(checkpoint.contains("pocketworld.request-clock.v1"));assertEquals(0,stored[0].getInt("steps"));
            }
            assertEquals(2,requests.size());JSONObject first=clock(requests.get(0).getJSONArray("messages")),second=clock(requests.get(1).getJSONArray("messages"));assertEquals("2026-11-02",first.getString("localDate"));assertEquals("2026-11-03",second.getString("localDate"));assertEquals("native_host_os_clock",second.getString("source"));assertFalse(second.getBoolean("externalTimeVerified"));assertEquals("UTC",second.getString("zoneId"));
            for(JSONObject request:requests){assertTrue(request.toString().getBytes(StandardCharsets.UTF_8).length<2L*1024*1024);assertFalse(request.toString().contains("own-clock-credential"));JSONArray declarations=request.getJSONArray("tools");Set<String> names=new HashSet<>();for(int i=0;i<declarations.length();i++)assertTrue("No duplicate native declaration",names.add(declarations.getJSONObject(i).getJSONObject("function").getString("name")));assertEquals(Set.of("run_handle_obstacle","run_end_not_done","run_ask_user"),names);}
        }finally {server.stop(0);}
    }
}
