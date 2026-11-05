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

/** Actual Runner argument refusal and next-request reference filtering, with loopback HTTP only. */
public final class ReferenceRefusalRunnerTest {
 @Test public void invalidReferenceArgumentsRemainCorrectableInTheNextModelRequest()throws Exception{
  List<JSONObject> requests=new CopyOnWriteArrayList<>();List<String> dispatched=new ArrayList<>();
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{try{
   requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));int turn=requests.size();
   JSONObject message=new JSONObject().put("role","assistant");if(turn<=2)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","read_"+turn).put("type","function").put("function",new JSONObject().put("name","memory_search").put("arguments",turn==1?"{}":new JSONObject().put("query","fixed").toString()))));else message.put("content","检索已完成，没有匹配记录。");
   byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",turn<=2?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
  }catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}});server.start();
  try{
   CapabilityPort port=new CapabilityPort(){
    public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","memory_search").put("description","Read fixture memory").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("query",new JSONObject().put("type","string"))).put("required",new JSONArray().put("query")).put("additionalProperties",false))));}
    public JSONObject contextSnapshot(String query)throws Exception{return new JSONObject().put("referenceValidation",new JSONObject().put("memory",new JSONArray()).put("skills",new JSONArray()));}
    public boolean supports(String name){return "memory_search".equals(name);}
    public boolean sideEffecting(String name){return false;}
    public void validate(String name,JSONObject args)throws Exception{if(!(args.opt("query") instanceof String))throw new IllegalArgumentException("fixture: missing required query");}
    public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{dispatched.add(args.getString("query"));return new JSONObject().put("ok",true).put("records",new JSONArray());}
   };
   String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
   ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,8,10,"openai_chat",1000).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
   ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
   new AgentRunner(null,port,budget).run("task","只查询记忆，不做修改",config,new RunControl(),event->{});
   assertEquals(3,requests.size());assertEquals(List.of("fixed"),dispatched);
   JSONObject correctedInput=receipt(requests.get(1),"read_1");
   assertEquals("invalid_arguments",correctedInput.getString("code"));assertFalse(correctedInput.getBoolean("accepted"));assertFalse(correctedInput.getBoolean("nativeDispatched"));
   assertEquals("correctable_failure",correctedInput.getJSONObject(ToolOutcome.KEY).getString("kind"));
   assertFalse(correctedInput.toString().contains("fixture: missing required query"));
   assertTrue(receipt(requests.get(2),"read_2").getBoolean("ok"));
  }finally{server.stop(0);}
 }
 private static JSONObject receipt(JSONObject request,String id)throws Exception{JSONArray messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject row=messages.getJSONObject(i);if(id.equals(row.optString("tool_call_id")))return new JSONObject(row.getString("content"));}throw new AssertionError("Missing "+id);}
}
