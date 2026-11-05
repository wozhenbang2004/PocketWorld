package dev.pocketengine.runtime;

import dev.pocketengine.core.*;
import dev.pocketengine.features.local.project.*;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Runner and native file read receipts, with a local fake model endpoint. */
public final class ProjectReadReceiptRunnerTest {
  @Test public void existingFileCompletesAndMissingFileReturnsFailureToModel()throws Exception {
    Path root=Files.createTempDirectory("pw-read-receipt-");
    try{
      Files.writeString(root.resolve("README.md"),"PocketWorld fixture content\n");
      Method view=ProjectCapabilityPort.class.getDeclaredMethod("view",String.class,JSONObject.class);view.setAccessible(true);
      for(String tool:new String[]{"project_inspect","project_read_file","project_search","project_list_files"}){
        assertTrue(((JSONObject)view.invoke(null,tool,new JSONObject())).getBoolean("ok"));
        assertFalse(((JSONObject)view.invoke(null,tool,new JSONObject().put("ok",false))).getBoolean("ok"));
      }
      assertFalse(((JSONObject)view.invoke(null,"project_execute",new JSONObject())).has("ok"));
      run(root,false,view);run(root,true,view);
    }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
  }
  private static void run(Path root,boolean missing,Method view)throws Exception {
    List<JSONObject> requests=new CopyOnWriteArrayList<>(),events=new ArrayList<>();
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{try{
      JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);
      JSONObject message=requests.size()==1?new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call_read").put("type","function").put("function",new JSONObject().put("name","project_read_file").put("arguments",new JSONObject().put("path",missing?"missing.txt":"README.md").toString())))):new JSONObject().put("role","assistant").put("content","原读取结果已收到。");
      byte[] response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",8).put("completion_tokens",4).put("total_tokens",12)).toString().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
    }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
    try {
      RunControl control=new RunControl();control.bindHostRun("review-task","review-run",1);
      CapabilityPort port=new CapabilityPort(){
        public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","project_read_file").put("description","Read fixture source").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path")).put("additionalProperties",false))));}
        public boolean supports(String name){return name.equals("project_read_file");}
        public boolean sideEffecting(String name){return false;}
        public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{return (JSONObject)view.invoke(null,name,ProjectFileQueries.read(root.toFile(),"checkout",args,original::check));}
      };
      String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
      ModelConfig config=new ModelConfig(endpoint,"fixture-only-key","review-fixture",false,6,10,"openai_chat",1000).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
      ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
      RunJournal journal=new RunJournal(){JSONArray history=new JSONArray();int steps;public JSONObject restore()throws Exception{return new JSONObject().put("messages",history).put("steps",steps);}public void checkpoint(JSONArray rows,int count,JSONObject observation)throws Exception{history=new JSONArray(rows.toString());steps=count;}public String beginOperation(String name,JSONObject args){throw new AssertionError("No effects permitted");}public void finishOperation(String id,JSONObject receipt){throw new AssertionError("No effects permitted");}};
      RunOutcome outcome=new AgentRunner(null,port,budget).run("review-task","只读取指定文件并告诉我结果，不做任何修改",config,control,events::add,journal);
      JSONArray toolEvents=new JSONArray();for(JSONObject e:events)if(e.optString("type").equals("tool"))toolEvents.put(e);
      JSONArray wireReceipts=new JSONArray();if(requests.size()>1){JSONArray messages=requests.get(1).getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if(m.optString("role").equals("tool"))wireReceipts.put(m);}}
      assertEquals("The model must receive the actual read result",2,requests.size());
      assertEquals(1,wireReceipts.length());
      JSONObject actual=new JSONObject(wireReceipts.getJSONObject(0).getString("content"));
      assertEquals(!missing,actual.getBoolean("ok"));
      if(missing){assertEquals("project_file_not_found",actual.getString("code"));assertFalse(actual.has("content"));}
      else assertEquals("PocketWorld fixture content\n",actual.getString("content"));
      assertEquals(missing?"failed":"completed",toolEvents.getJSONObject(toolEvents.length()-1).getString("status"));
    }finally{server.stop(0);}
  }
}
