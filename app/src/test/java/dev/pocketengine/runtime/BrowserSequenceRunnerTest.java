package dev.pocketengine.runtime;

import dev.pocketengine.core.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Runner/Gateway/journal ordering; explicit native plan/port fixtures, not DOM or SQL. */
public final class BrowserSequenceRunnerTest {
    enum WaitResult { MATCHED, UNMET, MISSING, UNKNOWN, CANCELLED }
    private static final class Journal implements RunJournal {
        JSONArray history=new JSONArray();int steps;final List<String> tools=new ArrayList<>();
        public JSONObject restore()throws Exception{return new JSONObject().put("messages",history).put("steps",steps);}
        public void checkpoint(JSONArray messages,int count,JSONObject observation)throws Exception{history=new JSONArray(messages.toString());steps=count;}
        public String beginOperation(String tool,JSONObject args){tools.add(tool);return UUID.randomUUID().toString();}
        public void finishOperation(String id,JSONObject receipt){}
    }
    private static final class NativePort implements CapabilityPort {
        final WaitResult wait;final RunControl original;String parent;NativeToolPlan plan;int waits,writes;
        NativePort(WaitResult wait,RunControl original){this.wait=wait;this.original=original;}
        public JSONArray tools()throws Exception {
            JSONArray out=new JSONArray();for(String name:List.of("browser_task_prepare_sequence","browser_task_wait","browser_task_click"))
                out.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()))));
            return out;
        }
        public boolean supports(String name){return List.of("browser_task_prepare_sequence","browser_task_wait","browser_task_click").contains(name);}
        public boolean sideEffecting(String name){return true;}
        public JSONObject execute(String name,JSONObject args,RunControl actual)throws Exception {
            assertSame(original,actual);
            if(name.equals("browser_task_prepare_sequence")){
                parent=args.getString("_hostOperationId");
                var identity=new NativeToolPlan.BrowserIdentity("task","run",1,"profile",1,"session","space","tab",1,"document",0);
                plan=NativeToolPlan.captureBrowser(parent,actual,identity,2,(index,control)->index==0
                        ?new NativeToolPlan.Resolved(NativeToolPlan.Tool.BROWSER_WAIT,new JSONObject().put("tabId","tab").put("condition","load"),()->{})
                        :new NativeToolPlan.Resolved(NativeToolPlan.Tool.BROWSER_CLICK,new JSONObject().put("tabId","tab").put("observationId","fresh").put("ref","n0"),new JSONObject().put("observationId","fresh"),()->{}),()->{});
                return new JSONObject().put("ok",true).put("accepted",true).put("status","sequence_prepared");
            }
            if(name.equals("browser_task_wait")){
                waits++;JSONObject receipt=new JSONObject().put("ok",true).put("accepted",wait==WaitResult.UNKNOWN?JSONObject.NULL:true);
                // Original browser load/text waits use conditionMet; phone matched is not its proof.
                receipt.put("matched",wait!=WaitResult.MATCHED);
                if(wait!=WaitResult.MISSING)receipt.put("conditionMet",wait==WaitResult.MATCHED);
                if(wait==WaitResult.UNKNOWN)receipt.put("status","unknown");
                return receipt;
            }
            writes++;return new JSONObject().put("ok",true).put("accepted",true);
        }
        public NativeToolPlan takeToolPlan(String tool,String operation,RunControl actual){assertSame(original,actual);if(!tool.equals("browser_task_prepare_sequence"))return null;assertEquals(parent,operation);NativeToolPlan taken=plan;plan=null;return taken;}
    }
    private void journey(WaitResult result)throws Exception {
        RunControl control=new RunControl();control.bindHostRun("task","run",1);NativePort port=new NativePort(result,control);Journal journal=new Journal();List<JSONObject> events=new ArrayList<>();AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            exchange.getRequestBody().readAllBytes();JSONObject message=new JSONObject().put("role","assistant");
            if(requests.getAndIncrement()==0)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","model-parent-id").put("type","function").put("function",new JSONObject().put("name","browser_task_prepare_sequence").put("arguments","{}"))));else message.put("content","已停止本组合，请核对原结果。");
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";ModelConfig config=new ModelConfig(url,"","fixture",false,8,10,"openai_chat",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",url));
            RunOutcome outcome=new AgentRunner(null,port,selected->null).run("task","在原网页等待后点击已展示目标",config,control,event->{events.add(event);if(result==WaitResult.CANCELLED&&"browser_task_wait".equals(event.optString("tool"))&&"completed".equals(event.optString("status")))control.cancel();},journal);
            assertEquals(1,port.waits);assertEquals(result==WaitResult.MATCHED?1:0,port.writes);
            assertEquals(result==WaitResult.MATCHED?3:2,journal.tools.size());
            if(result==WaitResult.MATCHED)assertTrue(events.stream().noneMatch(event->"not_dispatched".equals(event.optString("status"))));
            else {
                JSONObject remaining=events.stream().filter(event->"native_sequence".equals(event.optString("type"))&&"not_dispatched".equals(event.optString("status"))).findFirst().orElseThrow();
                assertEquals("browser_task_prepare_sequence",remaining.getString("tool"));assertEquals(port.parent,remaining.getJSONObject("receipt").getString("parentOperationId"));
                assertEquals(1,remaining.getJSONObject("receipt").getInt("remainingSteps"));assertFalse(remaining.getJSONObject("receipt").getBoolean("automaticReplay"));
            }
            if(result==WaitResult.UNKNOWN)assertEquals("awaiting_user",outcome.status);
            if(result==WaitResult.CANCELLED)assertEquals("cancelled",outcome.status);
        }finally{control.cancel();server.stop(0);}
    }
    @Test public void matchedWaitContinuesOnlyItsOriginalNativePlan()throws Exception{journey(WaitResult.MATCHED);}
    @Test public void unmetWaitKeepsOriginalParentAndExplicitUnexecutedTail()throws Exception{journey(WaitResult.UNMET);}
    @Test public void absentMatchedEvidenceCannotContinueTheTail()throws Exception{journey(WaitResult.MISSING);}
    @Test public void unknownWaitNeverStartsAnotherOperation()throws Exception{journey(WaitResult.UNKNOWN);}
    @Test public void cancellationAfterCommittedWaitNeverReplaysTheTail()throws Exception{journey(WaitResult.CANCELLED);}
}
