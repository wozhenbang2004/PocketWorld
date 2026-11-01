package dev.pocketengine.features.proxy;

import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LoopbackResponsesTest {
    private static JSONObject request()throws Exception{return new JSONObject().put("model","fixture-model").put("input","Hello");}
    private static JSONObject delta(int index,int part,String id,String value,boolean refusal)throws Exception{return new JSONObject().put("type",refusal?"refusal_delta":"text_delta").put("response_id","response").put("output_index",index).put("content_index",part).put("item_id",id).put("delta",value);}
    private static JSONObject terminal(JSONArray items)throws Exception{return new JSONObject().put("type","completed").put("response_id","response").put("output",items).put("usage",new JSONObject().put("input_tokens",3).put("output_tokens",7).put("total_tokens",10));}
    private static JSONObject message(String id,String value)throws Exception{return ResponsesFixtures.message(id,value,false,true);}
    private static class Recorder implements LoopbackResponses.Output {
        final List<JSONObject> events=new ArrayList<>();JSONObject complete;
        public void event(JSONObject value){events.add(value);}public void complete(JSONObject value){complete=value;}
    }
    @Test public void requestSupportsStandardTextStringAndOnlyExplicitSubset()throws Exception {
        assertFalse(LoopbackResponses.input(request(),"fixture-model").streaming);
        assertTrue(LoopbackResponses.input(request().put("stream",true).put("store",false).put("tools",ResponsesFixtures.tools()),"fixture-model").streaming);
    }
    @Test public void unsupportedFieldsCannotDisappearEvenIfNull()throws Exception {
        for(String name:new String[]{"temperature","max_output_tokens","metadata","include","background","conversation","previous_response_id","tool_choice","parallel_tool_calls","reasoning","unknown"}){
            try{LoopbackResponses.input(request().put(name,JSONObject.NULL),"fixture-model");fail(name);}catch(GatewayFailure expected){assertEquals("UNSUPPORTED_PROTOCOL_FIELD",expected.code);}
        }
    }
    @Test public void mismatchedModelStoreAndNonBooleanOrNullFlagsFail()throws Exception {
        JSONObject[] invalid={request().put("model","other"),request().put("store",true),request().put("store",JSONObject.NULL),request().put("stream",1),request().put("tools",JSONObject.NULL),request().put("instructions",3)};
        for(JSONObject body:invalid)try{LoopbackResponses.input(body,"fixture-model");fail();}catch(GatewayFailure expected){}
    }
    @Test public void mediaToolsAndUnmatchedResultsFailBeforeDispatch()throws Exception {
        JSONArray image=new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","input_image").put("image_url","https://example.test/private"))));
        JSONArray result=new JSONArray().put(new JSONObject().put("type","function_call_output").put("call_id","missing").put("output","do not drop"));
        for(JSONObject body:new JSONObject[]{request().put("input",image),request().put("input",result),request().put("tools",new JSONArray().put(ResponsesFixtures.declaration("flat")))})try{LoopbackResponses.input(body,"fixture-model");fail();}catch(GatewayFailure expected){}
    }
    @Test public void nonstreamIsAResponseNotAnInternalGatewayEvent()throws Exception {
        Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",false,out);
        protocol.accept(delta(0,0,"m","hello",false));assertNull(out.complete);
        protocol.accept(terminal(new JSONArray().put(message("m","hello"))));protocol.requireCompleted();assertEquals(0,out.events.size());assertEquals("response",out.complete.getString("object"));assertEquals("completed",out.complete.getString("status"));assertFalse(out.complete.has("type"));assertEquals(10,out.complete.getJSONObject("usage").getInt("total_tokens"));
    }
    @Test public void plainTextHasIncrementalStandardLifecycleAndMonotonicSequence()throws Exception {
        Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",true,out);
        protocol.accept(delta(0,0,"m","你好🙂",false));assertEquals(4,out.events.size());assertEquals("response.output_text.delta",out.events.get(3).getString("type"));
        protocol.accept(delta(0,0,"m"," next",false));protocol.accept(terminal(new JSONArray().put(message("m","你好🙂 next"))));
        assertEquals("response.created",out.events.get(0).getString("type"));assertEquals("response.completed",out.events.get(out.events.size()-1).getString("type"));
        for(int i=0;i<out.events.size();i++)assertEquals(i,out.events.get(i).getLong("sequence_number"));
    }
    @Test public void reasoningGapDoesNotInventItemsAndOpaqueContinuationIsPreserved()throws Exception {
        Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",true,out);
        protocol.accept(delta(1,0,"m","after reasoning",false));assertEquals(1,out.events.size());
        JSONObject reasoning=new JSONObject().put("type","reasoning").put("id","r").put("summary",new JSONArray().put(new JSONObject().put("type","summary_text").put("text","summary"))).put("encrypted_content","opaque-original");
        protocol.accept(terminal(new JSONArray().put(reasoning).put(message("m","after reasoning"))));
        JSONObject firstItem=out.events.get(1);assertEquals("response.output_item.added",firstItem.getString("type"));assertEquals("reasoning",firstItem.getJSONObject("item").getString("type"));assertTrue(firstItem.getJSONObject("item").isNull("encrypted_content"));
        JSONObject finalResponse=out.events.get(out.events.size()-1).getJSONObject("response");assertEquals("opaque-original",finalResponse.getJSONArray("output").getJSONObject(0).getString("encrypted_content"));
    }
    @Test public void functionArgumentsAreOnlyReleasedAfterWholeResponseCompletion()throws Exception {
        Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",true,out);assertEquals(0,out.events.size());
        JSONObject call=ResponsesFixtures.function("f","receipt-id","observe","{}",true).put("async",false).put("caller",new JSONObject().put("type","direct"));
        protocol.accept(terminal(new JSONArray().put(call)));assertEquals(6,out.events.size());
        assertEquals("response.function_call_arguments.delta",out.events.get(2).getString("type"));assertEquals("receipt-id",out.events.get(4).getJSONObject("item").getString("call_id"));assertFalse(out.events.get(4).getJSONObject("item").getBoolean("async"));
    }
    @Test public void emptyAndRefusalPartsKeepIndexesAndFinalContent()throws Exception {
        Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",true,out);
        protocol.accept(delta(0,1,"m","cannot",true));
        JSONObject message=message("m","").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","")).put(new JSONObject().put("type","refusal").put("refusal","cannot"))).put("phase","final_answer");
        protocol.accept(terminal(new JSONArray().put(message)));JSONObject finalResponse=out.events.get(out.events.size()-1).getJSONObject("response");assertEquals("final_answer",finalResponse.getJSONArray("output").getJSONObject(0).getString("phase"));assertEquals("cannot",finalResponse.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("refusal"));
    }
    @Test public void terminalMismatchNeverEmitsSuccessOrToolOutput()throws Exception {
        Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",true,out);protocol.accept(delta(1,0,"m","seen",false));
        try{protocol.accept(terminal(new JSONArray().put(ResponsesFixtures.function("f","c","observe","{}",true)).put(message("m","changed"))));fail();}catch(GatewayFailure expected){}
        assertEquals(1,out.events.size());assertFalse(out.events.toString().contains("function_call"));assertFalse(out.events.toString().contains("response.completed"));
    }
    @Test public void incompleteStreamAndUnknownEventsHaveNoFakeSuccessTerminal()throws Exception {
        Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",true,out);protocol.accept(delta(0,0,"m","partial",false));
        try{protocol.requireCompleted();fail();}catch(GatewayFailure expected){assertEquals("PROVIDER_STREAM_INTERRUPTED",expected.code);}
        JSONObject error=protocol.failure(new GatewayFailure("PROVIDER_STREAM_INTERRUPTED"));assertEquals("error",error.getString("type"));assertFalse(error.toString().contains("[DONE]"));
        try{protocol.accept(new JSONObject().put("type","audio_delta"));fail();}catch(GatewayFailure expected){}
    }
    @Test public void errorCodesCannotEchoAnArbitraryProviderMessage()throws Exception {
        JSONObject error=LoopbackResponses.error(new GatewayFailure("provider token: secret-value"));assertFalse(error.toString().contains("secret-value"));assertEquals("ACCOUNT_REQUEST_FAILED",error.getJSONObject("error").getString("code"));
    }
}
