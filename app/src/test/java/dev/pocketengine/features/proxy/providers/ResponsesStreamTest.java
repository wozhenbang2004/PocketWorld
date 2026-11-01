package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.features.proxy.providers.ResponsesFixtures.*;

public class ResponsesStreamTest {
    static JSONArray read(String stream,String... secrets)throws Exception{JSONArray events=new JSONArray();ResponsesStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(1,len));}},request(),new GatewayCall(5000),events::put,secrets);return events;}
    static void reject(String stream,String expected)throws Exception{JSONArray events=new JSONArray();try{ResponsesStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),request(),new GatewayCall(5000),events::put);fail();}catch(GatewayFailure failure){assertEquals(expected,failure.code);}for(int i=0;i<events.length();i++)assertNotEquals("completed",events.getJSONObject(i).getString("type"));}
    @Test public void interleavedFunctionsTextAndUnicodeUseAllThreeIdentities()throws Exception {
        String first="{\"城市\":\"杭州🙂\"}",second="{\"x\":2}";String id="调用_🙂",callId="关联_一";
        JSONObject a=function(id,callId,"observe",first,true),b=function("fc_2","call_2","tap",second,true),m=message("msg_1","你好🙂",false,true).put("phase","commentary");
        ResponsesFixtures stream=new ResponsesFixtures().item(0,function(id,callId,"observe","",false),false).item(1,message("msg_1","",false,false),false).item(2,function("fc_2","call_2","tap","",false),false);
        stream.argument(0,id,"{\"城市\":\"杭",false).argument(2,"fc_2","{\"x\":",false).argument(0,id,"州\ud83d",false).text(1,"msg_1","你好🙂",false).argument(2,"fc_2","2}",false).argument(0,id,"\ude42\"}",false);
        stream.argument(2,"fc_2",second,true).item(2,b,true).argument(0,id,first,true).item(0,a,true).item(1,m,true).complete(new JSONArray().put(a).put(m).put(b));
        JSONArray events=read(stream.toString());assertEquals(2,events.length());assertEquals("text_delta",events.getJSONObject(0).getString("type"));assertEquals(1,events.getJSONObject(0).getInt("output_index"));
        JSONObject completed=events.getJSONObject(1);assertEquals("completed",completed.getString("type"));JSONArray output=completed.getJSONArray("output");assertEquals(first,output.getJSONObject(0).getString("arguments"));assertEquals(callId,output.getJSONObject(0).getString("call_id"));assertEquals("phone",output.getJSONObject(2).getString("namespace"));assertEquals("commentary",output.getJSONObject(1).getString("phase"));assertEquals(10,completed.getJSONObject("usage").getInt("total_tokens"));
    }
    @Test public void callArgumentsAreNotReleasedBeforeResponseTerminal()throws Exception {
        String complete=oneCall();String partial=complete.substring(0,complete.indexOf("event: response.completed"));reject(partial,"PROVIDER_STREAM_INTERRUPTED");
        reject(partial+"data: {\"type\":\"response.failed\",\"sequence_number\":100,\"response\":{\"status\":\"failed\"}}\n\n","INVALID_PROVIDER_STREAM");
        for(String type:new String[]{"response.failed","response.incomplete"})reject(partial+"data: "+new JSONObject().put("type",type).put("sequence_number",100).put("response",new JSONObject().put("id","resp_fixture").put("status",type.substring(9)))+"\n\n",type.endsWith("failed")?"PROVIDER_RESPONSE_FAILED":"PROVIDER_RESPONSE_INCOMPLETE");
    }
    @Test public void mismatchedIdsArgumentsAndIncompleteItemNeverComplete()throws Exception {
        String good=oneCall();
        reject(good.replace("\"item_id\":\"fc_0\"","\"item_id\":\"other\""),"INVALID_PROVIDER_STREAM");
        reject(good.replace("\"output_index\":0","\"output_index\":1"),"INVALID_PROVIDER_STREAM");
        reject(good.replace("\"arguments\":\"{}\"","\"arguments\":\"{ }\""),"INVALID_PROVIDER_STREAM");
        reject(good.replace("\"status\":\"completed\"","\"status\":\"incomplete\""),"INVALID_PROVIDER_STREAM");
        reject(good.replace("\"status\":\"completed\"","\"status\":null"),"INVALID_PROVIDER_STREAM");
    }
    @Test public void duplicateCallsIndexesAndUndeclaredFunctionsAreRejected()throws Exception {
        ResponsesFixtures duplicate=new ResponsesFixtures().item(0,function("fc_0","same","observe","",false),false).item(1,function("fc_1","same","tap","",false),false);reject(duplicate.toString(),"INVALID_PROVIDER_STREAM");
        reject(new ResponsesFixtures().item(0,function("fc_0","a","observe","",false),false).item(0,function("fc_1","b","observe","",false),false).toString(),"INVALID_PROVIDER_STREAM");
        reject(new ResponsesFixtures().item(0,function("fc_0","a","delete_everything","",false),false).toString(),"UNDECLARED_FUNCTION_CALL");
    }
    @Test public void terminalOutputCannotInventOrChangeCalls()throws Exception {
        JSONObject call=function("fc_0","call_0","observe","{}",true);
        ResponsesFixtures base=new ResponsesFixtures().item(0,function("fc_0","call_0","observe","",false),false).argument(0,"fc_0","{}",false).argument(0,"fc_0","{}",true).item(0,call,true);
        reject(base.complete(new JSONArray()).toString(),"INVALID_PROVIDER_STREAM");
        reject(new ResponsesFixtures().complete(new JSONArray().put(call)).toString(),"INVALID_PROVIDER_STREAM");
    }
    @Test public void refusalIsNormalSeparateModelOutputAndRoundTrips()throws Exception {
        JSONObject refusal=message("msg_ref","不能协助该请求",true,true).put("phase","final_answer");
        JSONArray events=read(new ResponsesFixtures().item(0,message("msg_ref","",true,false),false).text(0,"msg_ref","不能协助该请求",true).item(0,refusal,true).complete(new JSONArray().put(refusal)).toString());
        assertEquals("refusal_delta",events.getJSONObject(0).getString("type"));assertEquals("refusal",events.getJSONObject(1).getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"));
        new ResponsesRequest("fixture-model","",events.getJSONObject(1).getJSONArray("output"),new JSONArray());
    }
    @Test public void unknownToolCompactionAndNonDirectModesFailExplicitly()throws Exception {
        reject(new ResponsesFixtures().item(0,new JSONObject().put("type","compaction").put("id","r").put("status","in_progress"),false).toString(),"UNSUPPORTED_PROVIDER_OUTPUT");
        reject(new ResponsesFixtures().item(0,function("fc","call","observe","",false).put("async",true),false).toString(),"UNSUPPORTED_FUNCTION_MODE");
        reject(new ResponsesFixtures().add(new JSONObject().put("type","response.web_search_call.completed")).toString(),"UNSUPPORTED_PROVIDER_OUTPUT");
    }
    @Test public void argumentAndAggregateOutputBudgetsAreBounded()throws Exception {
        ResponsesFixtures stream=new ResponsesFixtures().item(0,function("fc","call","observe","",false),false);for(int i=0;i<4;i++)stream.argument(0,"fc","x".repeat(20*1024),false);reject(stream.toString(),"PROVIDER_RESPONSE_TOO_LARGE");
        ResponsesFixtures total=new ResponsesFixtures();for(int i=0;i<5;i++){total.item(i,function("fc"+i,"call"+i,"observe","",false),false);for(int j=0;j<3;j++)total.argument(i,"fc"+i,"x".repeat(20*1024),false);}reject(total.toString(),"PROVIDER_RESPONSE_TOO_LARGE");
        reject("data: "+"x".repeat(64*1024)+"\n\n","PROVIDER_RESPONSE_TOO_LARGE");
    }
    @Test public void sequenceNumbersMustBeIntegerMonotonicAndEventNamesAgree()throws Exception {
        String good=oneCall();reject(good.replace("\"sequence_number\":1","\"sequence_number\":0"),"INVALID_PROVIDER_STREAM");reject(good.replace("\"sequence_number\":1","\"sequence_number\":1.5"),"INVALID_PROVIDER_STREAM");reject(good.replace("event: response.function_call_arguments.delta","event: response.output_text.delta"),"INVALID_PROVIDER_STREAM");
    }
    @Test public void credentialsInFunctionDataFailInsteadOfMutatingAnExecutableArgument()throws Exception {
        String secret="fixture-secret",args="{\"value\":\"fixture-secret\"}";JSONObject done=function("fc","call","observe",args,true);
        String stream=new ResponsesFixtures().item(0,function("fc","call","observe","",false),false).argument(0,"fc","{\"value\":\"fixture-",false).argument(0,"fc","secret\"}",false).argument(0,"fc",args,true).item(0,done,true).complete(new JSONArray().put(done)).toString();
        JSONArray events=new JSONArray();try{ResponsesStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),request(),new GatewayCall(5000),events::put,secret);fail();}catch(GatewayFailure failure){assertEquals("PROVIDER_CREDENTIAL_ECHO",failure.code);}assertEquals(0,events.length());
    }
    @Test public void cancellationAfterTextDeliveryCannotPublishPendingCalls()throws Exception {
        JSONObject message=message("m","pause",false,true);String stream=new ResponsesFixtures().item(0,message("m","",false,false),false).text(0,"m","pause",false).item(0,message,true).complete(new JSONArray().put(message)).toString();GatewayCall call=new GatewayCall(5000);JSONArray events=new JSONArray();
        try{ResponsesStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),request(),call,event->{events.put(event);call.cancel();});fail();}catch(GatewayFailure failure){assertEquals("CANCELLED",failure.code);}assertEquals(1,events.length());
    }
    @Test public void missingArgumentDoneAndItemDoneCannotBecomeComplete()throws Exception {
        JSONObject done=function("fc","call","observe","{}",true);
        reject(new ResponsesFixtures().item(0,function("fc","call","observe","",false),false).argument(0,"fc","{}",false).item(0,done,true).complete(new JSONArray().put(done)).toString(),"INVALID_PROVIDER_STREAM");
        reject(new ResponsesFixtures().item(0,function("fc","call","observe","",false),false).argument(0,"fc","{}",false).argument(0,"fc","{}",true).complete(new JSONArray().put(done)).toString(),"INVALID_PROVIDER_STREAM");
        JSONObject missing=function("fc","call","observe","{}",true);missing.remove("status");
        reject(new ResponsesFixtures().item(0,function("fc","call","observe","",false),false).argument(0,"fc","{}",false).argument(0,"fc","{}",true).item(0,missing,true).toString(),"INVALID_PROVIDER_STREAM");
    }
    @Test public void functionCountAndWireBudgetAreHardLimits()throws Exception {
        ResponsesFixtures count=new ResponsesFixtures();for(int i=0;i<ResponsesRequest.MAX_FUNCTIONS+1;i++)count.item(i,function("f"+i,"c"+i,"observe","",false),false);reject(count.toString(),"PROVIDER_RESPONSE_TOO_LARGE");
        reject(":"+"x".repeat(64*1024)+"\n\n","PROVIDER_RESPONSE_TOO_LARGE");
        reject(": heartbeat\n\n".repeat(170000),"PROVIDER_RESPONSE_TOO_LARGE");
    }
    @Test public void malformedUtf8AndExcessiveJsonNestingFailSafely()throws Exception {
        byte[] malformed={(byte)0xc3,(byte)0x28,10,10};try{ResponsesStream.read(new ByteArrayInputStream(malformed),request(),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure failure){assertEquals("INVALID_PROVIDER_STREAM",failure.code);}
        reject("data: {\"x\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}\n\n","INVALID_PROVIDER_STREAM");
    }
    @Test public void splitSurrogatesInTextAreHeldUntilWholeUnicodeCharacter()throws Exception {
        JSONObject message=message("m","🙂",false,true);ResponsesFixtures stream=new ResponsesFixtures().item(0,message("m","",false,false),false);
        stream.add(new JSONObject().put("type","response.content_part.added").put("item_id","m").put("output_index",0).put("content_index",0).put("part",new JSONObject().put("type","output_text").put("text","")));
        for(String delta:new String[]{"\ud83d","\ude42"})stream.add(new JSONObject().put("type","response.output_text.delta").put("item_id","m").put("output_index",0).put("content_index",0).put("delta",delta));
        stream.add(new JSONObject().put("type","response.output_text.done").put("item_id","m").put("output_index",0).put("content_index",0).put("text","🙂"));
        stream.add(new JSONObject().put("type","response.content_part.done").put("item_id","m").put("output_index",0).put("content_index",0).put("part",new JSONObject().put("type","output_text").put("text","🙂")));
        stream.item(0,message,true).complete(new JSONArray().put(message));JSONArray events=read(stream.toString());assertEquals(2,events.length());assertEquals("🙂",events.getJSONObject(0).getString("delta"));
    }
    @Test public void legacyTextEntryAlsoTreatsRefusalAsNormalOutput()throws Exception {
        JSONArray events=new JSONArray();String stream="data: {\"type\":\"response.refusal.delta\",\"delta\":\"No\"}\n\ndata: {\"type\":\"response.refusal.done\",\"refusal\":\"No\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n";
        ResponsesTextStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),new GatewayCall(5000),events::put);assertEquals("refusal_delta",events.getJSONObject(0).getString("type"));assertEquals("completed",events.getJSONObject(1).getString("type"));
    }
    @Test public void officialTopLevelErrorCodesKeepCooldownAndNeverEchoProviderMessage()throws Exception {
        for(String code:new String[]{"subscription_sharing_usage_limit_exceeded","subscription_sharing_usage_unavailable","subscription_sharing_user_unavailable","unknown-private-code"}){
            String stream="data: "+new JSONObject().put("type","error").put("code",code).put("message","private-error-message").put("param",JSONObject.NULL).put("sequence_number",0)+"\n\n";
            for(boolean legacy:new boolean[]{false,true}){
                try{if(legacy)ResponsesTextStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),new GatewayCall(5000),event->fail());
                    else ResponsesStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),request(),new GatewayCall(5000),event->fail());fail();}
                catch(GatewayFailure failure){assertEquals(code.endsWith("limit_exceeded")?"CHATGPT_USAGE_LIMIT_REACHED":code.startsWith("unknown")?"PROVIDER_RESPONSE_FAILED":"PROVIDER_UNAVAILABLE",failure.code);assertEquals(code.endsWith("unavailable"),failure.retryAt>0);assertFalse(failure.toString().contains("private"));}
            }
        }
    }
    @Test public void structuredHttpRequestIsOneShotAndPreservesSchema()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            fake.stream=oneCall();JSONArray events=new JSONArray();provider.streamResponses(ChatGptProviderTest.credentials(),request(),new GatewayCall(5000),events::put);assertEquals(1,fake.responses.get());assertFalse(fake.lastRequest.getBoolean("store"));assertTrue(fake.lastRequest.getBoolean("stream"));assertEquals("namespace",fake.lastRequest.getJSONArray("tools").getJSONObject(0).getString("type"));assertEquals("completed",events.getJSONObject(0).getString("type"));
            fake.responseStatus=503;try{provider.streamResponses(ChatGptProviderTest.credentials(),request(),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure failure){assertEquals("PROVIDER_UNAVAILABLE",failure.code);}assertEquals(2,fake.responses.get());
        }
    }
}
