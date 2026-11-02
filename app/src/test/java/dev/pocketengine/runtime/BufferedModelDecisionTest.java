package dev.pocketengine.runtime;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual buffered HTTP transport and original physical receipts; no live provider/account. */
public final class BufferedModelDecisionTest {
    private static final List<String> PROTOCOLS=List.of("openai_chat","openai_responses","anthropic","gemini");
    private static final String KEY="fixture-buffered-key";
    private static JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","inspect fixture"));}
    private static JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect").put("parameters",new JSONObject().put("type","object"))));}
    private static JSONObject call()throws Exception{return new JSONObject().put("id","call-one").put("type","function").put("function",new JSONObject().put("name","inspect").put("arguments","{}"));}
    private static JSONObject result(String protocol,boolean tool)throws Exception{
        JSONObject usage=new JSONObject().put("input_tokens",11).put("output_tokens",7).put("total_tokens",18);
        switch(protocol){
            case "openai_chat":{
                JSONObject message=new JSONObject().put("role","assistant").put("content",tool?"":"actual answer");if(tool)message.put("tool_calls",new JSONArray().put(call()));
                return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",tool?"tool_calls":"stop").put("message",message)))
                        .put("usage",new JSONObject().put("prompt_tokens",11).put("completion_tokens",7).put("total_tokens",18));
            }
            case "openai_responses":{
                JSONObject item=tool?new JSONObject().put("type","function_call").put("call_id","call-one").put("name","inspect").put("arguments","{}").put("status","completed")
                        :new JSONObject().put("type","message").put("role","assistant").put("status","completed").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","actual answer")));
                return new JSONObject().put("status","completed").put("output",new JSONArray().put(item)).put("usage",usage);
            }
            case "anthropic":return new JSONObject().put("type","message").put("role","assistant").put("stop_reason",tool?"tool_use":"end_turn")
                    .put("content",new JSONArray().put(tool?new JSONObject().put("type","tool_use").put("id","call-one").put("name","inspect").put("input",new JSONObject())
                            :new JSONObject().put("type","text").put("text","actual answer"))).put("usage",usage);
            default:return new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("finishReason","STOP")
                    .put("content",new JSONObject().put("role","model").put("parts",new JSONArray().put(tool?new JSONObject().put("functionCall",new JSONObject().put("id","call-one").put("name","inspect").put("args",new JSONObject()))
                            :new JSONObject().put("text","actual answer")))))).put("usageMetadata",new JSONObject().put("promptTokenCount",11).put("candidatesTokenCount",7).put("totalTokenCount",18));
        }
    }
    private static void terminal(JSONObject wire,String protocol,String value)throws Exception{
        JSONObject owner=protocol.equals("openai_chat")?wire.getJSONArray("choices").getJSONObject(0):protocol.equals("gemini")?wire.getJSONArray("candidates").getJSONObject(0):wire;
        String key=protocol.equals("openai_chat")?"finish_reason":protocol.equals("gemini")?"finishReason":protocol.equals("anthropic")?"stop_reason":"status";
        if(value==null)owner.remove(key);else owner.put(key,value);
    }
    private static final class Rig implements AutoCloseable {
        final HttpServer http;final AtomicInteger requests=new AtomicInteger();final String protocol;final List<InferenceReceipt> started=new ArrayList<>(),finished=new ArrayList<>();
        volatile byte[] body;volatile int extraLength;volatile boolean expectedDisconnect;volatile Runnable during=()->{};volatile Throwable handlerFailure;
        Rig(String protocol,JSONObject result)throws Exception{
            this.protocol=protocol;body=result.toString().getBytes(StandardCharsets.UTF_8);http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/",exchange->{try{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();during.run();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length+extraLength);exchange.getResponseBody().write(body);}catch(Exception error){if(extraLength==0&&!expectedDisconnect)handlerFailure=error;}finally{exchange.close();}});http.start();
        }
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+http.getAddress().getPort()+"/v1",KEY,"fixture-model",false,4,10,protocol,512);}
        JSONObject invoke(JSONArray offered,RunControl control)throws Exception{return ModelGateway.complete(config(),messages(),offered,control,new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){started.add(r);}public void finished(InferenceReceipt r){finished.add(r);}}));}
        JSONObject receipt(InferenceReceipt.Outcome outcome)throws Exception{
            assertEquals(1,requests.get());assertEquals(1,started.size());assertEquals(1,finished.size());assertEquals(started.get(0).callId,finished.get(0).callId);assertEquals(outcome,finished.get(0).outcome);assertNull(handlerFailure);return finished.get(0).toJson();
        }
        public void close(){http.stop(0);}
    }
    private static void rejected(String protocol,JSONObject wire,String expectedTerminal)throws Exception{
        try(Rig rig=new Rig(protocol,wire)){
            assertThrows(ModelGateway.GatewayException.class,()->rig.invoke(tools(),new RunControl()));JSONObject receipt=rig.receipt(InferenceReceipt.Outcome.FAILED);
            JSONObject usage=receipt.getJSONObject("usage"),counters=usage.getJSONObject("counters");assertEquals(11,counters.getJSONObject("INPUT").getLong("value"));assertEquals(7,counters.getJSONObject("OUTPUT").getLong("value"));
            if(expectedTerminal==null)assertTrue(usage.isNull("finishReason"));else assertEquals(expectedTerminal,usage.getString("finishReason"));
            assertEquals("PROTOCOL",receipt.getJSONObject("failure").getString("kind"));assertEquals(200,receipt.getJSONObject("failure").getInt("httpStatus"));assertFalse(receipt.getJSONObject("failure").getBoolean("automaticRetry"));
        }
    }
    @Test public void allFourProtocolsKeepSuccessfulTextAndLegitimateToolCalls()throws Exception{
        for(String protocol:PROTOCOLS)for(boolean tool:new boolean[]{false,true})try(Rig rig=new Rig(protocol,result(protocol,tool))){
            JSONObject answer=rig.invoke(tools(),new RunControl());assertEquals("assistant",answer.getString("role"));if(tool)assertEquals("inspect",answer.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"));else assertEquals("actual answer",answer.getString("content"));rig.receipt(InferenceReceipt.Outcome.COMPLETED);
        }
    }
    @Test public void missingEmptyRefusedAndTruncatedTerminalsNeverComplete()throws Exception{
        for(String protocol:PROTOCOLS){
            for(String bad:new String[]{null,"",protocol.equals("openai_chat")?"content_filter":protocol.equals("anthropic")?"refusal":protocol.equals("gemini")?"SAFETY":"failed",protocol.equals("openai_chat")?"length":protocol.equals("anthropic")?"max_tokens":protocol.equals("gemini")?"MAX_TOKENS":"incomplete"}){
                JSONObject wire=result(protocol,false);terminal(wire,protocol,bad);rejected(protocol,wire,bad==null||bad.isEmpty()?null:bad);
            }
        }
    }
    @Test public void wrongRolesAndMultipleCandidatesCannotBecomeBufferedAnswers()throws Exception{
        for(String protocol:PROTOCOLS){JSONObject wire=result(protocol,false);
            if(protocol.equals("openai_chat"))wire.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("role","user");
            else if(protocol.equals("openai_responses"))wire.getJSONArray("output").getJSONObject(0).put("role","user");
            else if(protocol.equals("anthropic"))wire.put("role","user");else wire.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").put("role","user");
            rejected(protocol,wire,protocol.equals("openai_chat")?"stop":protocol.equals("anthropic")?"end_turn":protocol.equals("gemini")?"STOP":"completed");
        }
        for(String protocol:List.of("openai_chat","gemini")){JSONObject wire=result(protocol,false);JSONArray choices=wire.getJSONArray(protocol.equals("openai_chat")?"choices":"candidates");choices.put(new JSONObject(choices.getJSONObject(0).toString()));rejected(protocol,wire,protocol.equals("openai_chat")?"stop":"STOP");}
    }
    @Test public void unofferedToolsDuplicateIdsAndIllegalArgumentsNeverLeaveTheGateway()throws Exception{
        for(String protocol:PROTOCOLS){JSONObject wire=result(protocol,true);try(Rig rig=new Rig(protocol,wire)){assertThrows(ModelGateway.GatewayException.class,()->rig.invoke(null,new RunControl()));rig.receipt(InferenceReceipt.Outcome.FAILED);}}
        for(String kind:List.of("duplicate","arguments","mismatch","malformedCalls")){
            JSONObject wire=result("openai_chat",true),choice=wire.getJSONArray("choices").getJSONObject(0),message=choice.getJSONObject("message");
            if(kind.equals("duplicate"))message.getJSONArray("tool_calls").put(call());
            else if(kind.equals("arguments"))message.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments","{} trailing");
            else if(kind.equals("mismatch"))choice.put("finish_reason","stop");
            else {message.put("tool_calls",new JSONObject()).put("content","not a valid decision");choice.put("finish_reason","stop");}
            rejected("openai_chat",wire,choice.getString("finish_reason"));
        }
        JSONObject anthropic=result("anthropic",true);anthropic.put("stop_reason","end_turn");rejected("anthropic",anthropic,"end_turn");
    }
    @Test public void interruptedTransportAndCancellationCannotPublishOrReplay()throws Exception{
        try(Rig rig=new Rig("openai_chat",result("openai_chat",false))){rig.body="{\"usage\":{\"prompt_tokens\":11},\"choices\":[".getBytes(StandardCharsets.UTF_8);rig.extraLength=100;
            assertThrows(ModelGateway.GatewayException.class,()->rig.invoke(null,new RunControl()));rig.receipt(InferenceReceipt.Outcome.FAILED);
        }
        try(Rig rig=new Rig("openai_chat",result("openai_chat",false))){RunControl control=new RunControl();rig.expectedDisconnect=true;rig.during=control::cancel;
            assertThrows(InterruptedException.class,()->rig.invoke(null,control));rig.receipt(InferenceReceipt.Outcome.CANCELLED);
        }
    }
}
