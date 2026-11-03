package dev.pocketengine.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;

/** Original Gateway/Runner over actual loopback, explicit native port/journal fixtures, not device QA. */
public final class RepeatToolRunnerTest {
    private static final String TAG="<native_repeat_tool_notice>";
    private static final class Journal implements RunJournal {
        JSONObject saved=new JSONObject();int begins,finishes;
        public JSONObject restore()throws Exception{return new JSONObject(saved.toString());}
        public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception{saved.put("messages",new JSONArray(messages.toString())).put("steps",steps).put("observation",observation);}
        public String beginOperation(String tool,JSONObject arguments){return "original-operation-"+(++begins);}
        public void finishOperation(String operation,JSONObject receipt){finishes++;}
    }
    private static final class Journey implements AutoCloseable {
        final List<JSONObject> requests=new CopyOnWriteArrayList<>();final AtomicInteger calls=new AtomicInteger();final Journal journal=new Journal();final RunControl control=new RunControl();final HttpServer server;
        final List<JSONObject> events=new ArrayList<>();final String tool;final boolean varying,sideEffecting;final int unknownAt,ownerAfter;final ModelConfig config;
        Journey(String tool,boolean varying,boolean sideEffecting,int unknownAt,int ownerAfter,int maxSteps)throws Exception{
            this.tool=tool;this.varying=varying;this.sideEffecting=sideEffecting;this.unknownAt=unknownAt;this.ownerAfter=ownerAfter;
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try {
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);int index=requests.size();
                JSONObject message=new JSONObject().put("role","assistant");
                if(index<=5)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","original-call-"+index).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",new JSONObject().put("query",varying?"query-"+index:"PRIVATE QUERY").toString()))));else message.put("content","已核对原失败回执，等待主人选择，不重放动作。");
                JSONObject response=new JSONObject().put("id","response-"+index).put("object","chat.completion").put("model","repeat-fixture").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",message).put("finish_reason",index<=5?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",10).put("completion_tokens",5).put("total_tokens",15));
                byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception invalid){try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","own-repeat-credential","repeat-fixture",false,maxSteps,10,"openai_chat",1000);
        }
        RunOutcome run()throws Exception {
            CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",tool).put("description","Original read-only search fixture").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("query",new JSONObject().put("type","string"))).put("required",new JSONArray().put("query")))));}
                public boolean supports(String name){return tool.equals(name);}public boolean sideEffecting(String name){return sideEffecting;}
                public JSONObject execute(String name,JSONObject arguments,RunControl original)throws Exception{assertSame(control,original);int index=calls.incrementAndGet();return new JSONObject().put("ok",false).put("code",index==unknownAt?"effect_unknown":"not_found").put("message","PRIVATE ORIGINAL RECEIPT");}
            };
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            AgentRunner.OwnerInputs inputs=ownerAfter<0?null:new AgentRunner.OwnerInputs(){boolean delivered;public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl original)throws Exception{
                assertSame(control,original);if(!delivered&&calls.get()==ownerAfter){delivered=true;return new JSONArray().put(new JSONObject().put("messageId","00000000-0000-4000-8000-000000000002").put("mode","steer").put("content","主人明确要求再核对原搜索；不要增加硬预算。"));}return new JSONArray();
            }};
            return new AgentRunner(null,port,budget,null,inputs).run("original-task","只核对原工具结果，不扩大授权",config,control,events::add,journal);
        }
        int notices(){int count=0;for(JSONObject request:requests)if(request.toString().contains(TAG))count++;return count;}
        public void close(){server.stop(0);}
    }
    @Test public void repeatedFailureAppearsOnlyInNextOriginalRequestOnceWithoutExtraCallsOrJournalMirror()throws Exception {
        try(Journey j=new Journey("project_search",false,false,-1,-1,8)){
            RunOutcome outcome=j.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(5,j.calls.get());assertEquals(6,j.requests.size());assertEquals(1,j.notices());
            for(int i=0;i<j.requests.size();i++)assertEquals("request "+i,i==3,j.requests.get(i).toString().contains(TAG));
            JSONArray fourth=j.requests.get(3).getJSONArray("messages");String notice="";for(int i=0;i<fourth.length();i++){String text=fourth.getJSONObject(i).optString("content");int start=text.indexOf(TAG);if(start>=0)notice=text.substring(start,text.indexOf("</native_repeat_tool_notice>",start));}assertFalse(notice.isEmpty());
            assertFalse(notice.contains("PRIVATE"));assertTrue(notice.contains("automaticReplay"));assertFalse(j.journal.saved.toString().contains(TAG));assertEquals(5,j.journal.saved.getInt("steps"));assertEquals(0,j.journal.begins);assertFalse(j.control.isCancelled());
            Set<String> ids=new HashSet<>();for(JSONObject request:j.requests){JSONArray messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONArray calls=messages.getJSONObject(i).optJSONArray("tool_calls");for(int k=0;calls!=null&&k<calls.length();k++)ids.add(calls.getJSONObject(k).getString("id"));}}assertEquals(5,ids.size());
        }
    }
    @Test public void distinctArgumentsAndLegitimatePollingDoNotProduceNoticeOrStopLongTask()throws Exception {
        for(boolean polling:new boolean[]{false,true})try(Journey j=new Journey(polling?"project_get_status":"project_search",!polling,false,-1,-1,8)){
            assertEquals("needs_confirmation",j.run().status);assertEquals(5,j.calls.get());assertEquals(6,j.requests.size());assertEquals(0,j.notices());
        }
    }
    @Test public void acceptedOwnerSteeringRestartsOnlySoftChainAndKeepsCumulativeHardCeiling()throws Exception {
        try(Journey j=new Journey("project_search",false,false,-1,2,4)){
            RunOutcome outcome=j.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(4,j.calls.get());assertEquals(4,j.journal.saved.getInt("steps"));assertEquals(0,j.notices());
            assertTrue(j.journal.saved.toString().contains("00000000-0000-4000-8000-000000000002"));assertFalse(j.control.isCancelled());assertEquals(5,j.requests.size());
        }
    }
    @Test public void unknownEffectStopsAtOriginalReceiptWithoutNoticeNewRequestOrAutomaticReplay()throws Exception {
        for(boolean effect:new boolean[]{false,true})try(Journey j=new Journey(effect?"plugin_action":"project_search",false,effect,3,-1,8)){
            RunOutcome outcome=j.run();assertEquals(outcome.summary,"awaiting_user",outcome.status);assertTrue(outcome.evidence.getBoolean("requiresReconciliation"));assertEquals(3,j.calls.get());assertEquals(3,j.requests.size());assertEquals(0,j.notices());assertEquals(effect?3:0,j.journal.begins);assertEquals(effect?3:0,j.journal.finishes);
        }
    }
}
