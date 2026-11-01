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

/** Synthetic protocol-only reasoning. No real reasoning tokens, credentials, or tool executions. */
public class ResponsesReasoningTest {
    static JSONObject reasoning(String id,String encrypted,String summary,String status)throws Exception {
        JSONObject item=new JSONObject().put("id",id).put("type","reasoning").put("summary",new JSONArray());
        if(encrypted!=null)item.put("encrypted_content",encrypted);
        if(summary!=null)item.getJSONArray("summary").put(new JSONObject().put("type","summary_text").put("text",summary));
        if(status!=null)item.put("status",status);return item;
    }
    static JSONObject summaryEvent(String type,String id,int output,int index)throws Exception {
        return new JSONObject().put("type","response.reasoning_summary_"+type).put("item_id",id).put("output_index",output).put("summary_index",index);
    }
    static JSONObject summaryPart(String text)throws Exception{return new JSONObject().put("type","summary_text").put("text",text);}
    static void summary(ResponsesFixtures f,String id,int output,int index,String... deltas)throws Exception {
        f.add(summaryEvent("part.added",id,output,index).put("part",summaryPart("")));StringBuilder text=new StringBuilder();
        for(String delta:deltas){text.append(delta);f.add(summaryEvent("text.delta",id,output,index).put("delta",delta));}
        f.add(summaryEvent("text.done",id,output,index).put("text",text.toString()));
        f.add(summaryEvent("part.done",id,output,index).put("part",summaryPart(text.toString())));
    }
    static ResponsesFixtures reasoningTurn(JSONObject done)throws Exception {
        // The added ciphertext is intentionally different and incomplete, as permitted by the official API.
        ResponsesFixtures f=new ResponsesFixtures().item(0,reasoning(done.getString("id"),"incomplete-added-fragment",null,done.has("status")?"in_progress":null),false);
        JSONArray parts=done.getJSONArray("summary");for(int i=0;i<parts.length();i++)summary(f,done.getString("id"),0,i,parts.getJSONObject(i).getString("text"));
        return f.item(0,done,true);
    }
    static String callTurn(JSONObject done)throws Exception {
        JSONObject function=function("fc","call","observe","{}",true);
        return reasoningTurn(done).item(1,function("fc","call","observe","",false),false).argument(1,"fc","{}",false)
            .argument(1,"fc","{}",true).item(1,function,true).complete(new JSONArray().put(done).put(function)).toString();
    }
    static JSONObject completed(String stream)throws Exception {JSONArray events=ResponsesStreamTest.read(stream);assertEquals(1,events.length());return events.getJSONObject(0);}
    static void reject(String stream,String code)throws Exception{ResponsesStreamTest.reject(stream,code);}
    static JSONArray input()throws Exception{return ResponsesRequestTest.input();}
    static void assertJson(Object expected,Object actual)throws Exception {
        if(expected instanceof JSONObject){assertTrue(actual instanceof JSONObject);JSONObject a=(JSONObject)expected,b=(JSONObject)actual;assertEquals(a.length(),b.length());java.util.Iterator<String> keys=a.keys();while(keys.hasNext()){String key=keys.next();assertTrue(b.has(key));assertJson(a.get(key),b.get(key));}}
        else if(expected instanceof JSONArray){assertTrue(actual instanceof JSONArray);JSONArray a=(JSONArray)expected,b=(JSONArray)actual;assertEquals(a.length(),b.length());for(int i=0;i<a.length();i++)assertJson(a.get(i),b.get(i));}
        else assertEquals(expected,actual);
    }
    static void rejectInput(JSONObject item,String code)throws Exception{ResponsesRequestTest.reject(input().put(item),tools(),code);}

    @Test public void onlyCompletedCiphertextSurvivesWithOptionalStatusAndEmptyContent()throws Exception {
        for(String status:new String[]{null,"completed"}){
            JSONObject done=reasoning("rs","opaque-final-密文🙂==",null,status).put("content",new JSONArray());
            JSONObject saved=completed(callTurn(done)).getJSONArray("output").getJSONObject(0);
            assertJson(done,saved);assertEquals(status!=null,saved.has("status"));
            assertFalse(saved.toString().contains("incomplete-added-fragment"));assertEquals(0,saved.getJSONArray("summary").length());
        }
    }
    @Test public void summaryIsPreservedAsSummaryNotAssistantTextAndRequestSnapshotIsImmutable()throws Exception {
        JSONObject done=reasoning("rs","opaque-final","准备观察🙂","completed");JSONObject turn=completed(callTurn(done));
        JSONArray history=input();for(int i=0;i<turn.getJSONArray("output").length();i++)history.put(turn.getJSONArray("output").getJSONObject(i));
        history.put(new JSONObject().put("type","function_call_output").put("call_id","call").put("output","actual fixture receipt"));
        ResponsesRequest request=new ResponsesRequest("fixture-model","",history,tools());
        history.getJSONObject(1).put("encrypted_content","changed");history.getJSONObject(1).getJSONArray("summary").getJSONObject(0).put("text","changed");
        JSONObject body=new JSONObject(new String(request.body(),StandardCharsets.UTF_8));JSONObject saved=body.getJSONArray("input").getJSONObject(1);
        assertEquals("opaque-final",saved.getString("encrypted_content"));assertEquals("准备观察🙂",saved.getJSONArray("summary").getJSONObject(0).getString("text"));
        assertFalse(request.toString().contains("opaque"));assertFalse(body.has("previous_response_id"));assertFalse(body.has("include"));assertFalse(body.has("reasoning"));
    }
    @Test public void realLoopbackTwoTurnsTransportAllHistoryOnceWithoutExecuting()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            JSONObject done=reasoning("rs","opaque-one","Preparing an observation",null);fake.stream=callTurn(done);JSONArray events=new JSONArray();
            provider.streamResponses(ChatGptProviderTest.credentials(),request(),new GatewayCall(5000),events::put);
            JSONArray history=input(),output=events.getJSONObject(0).getJSONArray("output");for(int i=0;i<output.length();i++)history.put(output.getJSONObject(i));
            history.put(new JSONObject().put("type","function_call_output").put("call_id","call").put("output","fixture host receipt"));
            JSONObject next=reasoning("rs_next","opaque-two",null,"completed"),message=message("m","Observation received",false,true);
            fake.stream=reasoningTurn(next).item(1,message("m","",false,false),false).text(1,"m","Observation received",false).item(1,message,true).complete(new JSONArray().put(next).put(message)).toString();
            events=new JSONArray();provider.streamResponses(ChatGptProviderTest.credentials(),new ResponsesRequest("fixture-model","",history,tools()),new GatewayCall(5000),events::put);
            assertEquals(2,fake.responses.get());JSONArray received=fake.lastRequest.getJSONArray("input");assertEquals(4,received.length());
            assertJson(done,received.getJSONObject(1));assertEquals("call",received.getJSONObject(3).getString("call_id"));
            assertFalse(fake.lastRequest.getBoolean("store"));assertTrue(fake.lastRequest.getBoolean("stream"));assertEquals("opaque-two",events.getJSONObject(1).getJSONArray("output").getJSONObject(0).getString("encrypted_content"));
        }
    }
    @Test public void interleavedReasoningAndFunctionsKeepOutputAndSummaryIndexes()throws Exception {
        JSONObject a=reasoning("rs_a","opaque-a","第一🙂","completed"),b=reasoning("rs_b","opaque-b","second",null);
        a.getJSONArray("summary").put(summaryPart("another"));JSONObject fn=function("fc","call","observe","{}",true);
        ResponsesFixtures f=new ResponsesFixtures().item(2,reasoning("rs_b",null,null,null),false).item(0,reasoning("rs_a",null,null,"in_progress"),false).item(1,function("fc","call","observe","",false),false);
        summary(f,"rs_a",0,0,"第","一\ud83d","\ude42");f.argument(1,"fc","{}",false);summary(f,"rs_b",2,0,"second");summary(f,"rs_a",0,1,"another");
        f.item(2,b,true).argument(1,"fc","{}",true).item(1,fn,true).item(0,a,true).complete(new JSONArray().put(a).put(fn).put(b));
        JSONArray output=completed(f.toString()).getJSONArray("output");assertEquals("rs_a",output.getJSONObject(0).getString("id"));assertEquals("call",output.getJSONObject(1).getString("call_id"));assertEquals("rs_b",output.getJSONObject(2).getString("id"));
    }
    @Test public void noReasoningOrCallEscapesMissingFailedIncompleteOrCancelledTerminal()throws Exception {
        String good=callTurn(reasoning("rs","opaque","summary",null));String partial=good.substring(0,good.indexOf("event: response.completed"));
        reject(partial,"PROVIDER_STREAM_INTERRUPTED");
        for(String type:new String[]{"response.failed","response.incomplete"})reject(partial+"data: "+new JSONObject().put("type",type).put("sequence_number",99).put("response",new JSONObject().put("id","resp_fixture").put("status",type.substring(9)))+"\n\n",type.endsWith("failed")?"PROVIDER_RESPONSE_FAILED":"PROVIDER_RESPONSE_INCOMPLETE");
        GatewayCall call=new GatewayCall(5000);byte[] bytes=good.getBytes(StandardCharsets.UTF_8);int terminalBytes=good.substring(good.indexOf("event: response.completed")).getBytes(StandardCharsets.UTF_8).length;JSONArray events=new JSONArray();
        ByteArrayInputStream input=new ByteArrayInputStream(bytes){@Override public synchronized int read(byte[] b,int off,int len){if(available()<=terminalBytes)call.cancel();return super.read(b,off,Math.min(1,len));}};
        try{ResponsesStream.read(input,request(),call,events::put);fail();}catch(GatewayFailure e){assertEquals("CANCELLED",e.code);}assertEquals(0,events.length());
    }
    @Test public void terminalMustMatchDoneCiphertextSummaryStatusAndContentPresence()throws Exception {
        JSONObject done=reasoning("rs","opaque","summary",null);
        for(String field:new String[]{"encrypted_content","summary","status","content"}){
            JSONObject changed=new JSONObject(done.toString());
            if(field.equals("encrypted_content"))changed.put(field,"changed");else if(field.equals("summary"))changed.put(field,new JSONArray().put(summaryPart("changed")));
            else if(field.equals("status"))changed.put(field,"completed");else changed.put(field,new JSONArray());
            reject(reasoningTurn(done).complete(new JSONArray().put(changed)).toString(),"INVALID_PROVIDER_STREAM");
        }
        reject(new ResponsesFixtures().item(0,reasoning("rs",null,null,null),false).complete(new JSONArray().put(reasoning("rs","opaque",null,null))).toString(),"INVALID_PROVIDER_STREAM");
    }
    @Test public void missingNullAndEmptyCiphertextCannotPretendStatelessContinuation()throws Exception {
        for(Object encrypted:new Object[]{null,JSONObject.NULL,""}){
            JSONObject done=reasoning("rs",null,null,null);if(encrypted!=null)done.put("encrypted_content",encrypted);
            String expected="".equals(encrypted)?"UNSUPPORTED_OR_OVERSIZED_INPUT":"MISSING_REASONING_CONTINUATION";
            rejectInput(done,expected);
            reject(reasoningTurn(done).complete(new JSONArray().put(done)).toString(),expected.equals("UNSUPPORTED_OR_OVERSIZED_INPUT")?"INVALID_PROVIDER_STREAM":expected);
        }
    }
    @Test public void unknownFieldsRawReasoningAndCompactionFailExplicitly()throws Exception {
        JSONObject valid=reasoning("rs","opaque",null,"completed");
        rejectInput(new JSONObject(valid.toString()).put("future_field",true),"UNSUPPORTED_PROTOCOL_FIELD");
        rejectInput(new JSONObject(valid.toString()).put("summary",new JSONArray().put(summaryPart("s").put("future",true))),"UNSUPPORTED_PROTOCOL_FIELD");
        rejectInput(new JSONObject(valid.toString()).put("content",new JSONArray().put(new JSONObject().put("type","reasoning_text").put("text","not supported"))),"UNSUPPORTED_REASONING_CONTENT");
        rejectInput(new JSONObject(valid.toString()).put("summary",new JSONArray().put(new JSONObject().put("type","reasoning_text").put("text","not summary"))),"UNSUPPORTED_REASONING_CONTENT");
        reject(reasoningTurn(valid).add(new JSONObject().put("type","response.reasoning_text.delta").put("output_index",0).put("item_id","rs").put("content_index",0).put("delta","not supported")).toString(),"UNSUPPORTED_PROVIDER_OUTPUT");
        reject(reasoningTurn(new JSONObject(valid.toString()).put("future_field",true)).toString(),"UNSUPPORTED_PROTOCOL_FIELD");
    }
    @Test public void incompleteAndWrongStatusNeverPromoteReasoningToComplete()throws Exception {
        for(Object status:new Object[]{"incomplete","in_progress",JSONObject.NULL,4}){
            JSONObject done=reasoning("rs","opaque",null,null).put("status",status);rejectInput(done,"UNSUPPORTED_OR_OVERSIZED_INPUT");
            reject(new ResponsesFixtures().item(0,reasoning("rs",null,null,null),false).item(0,done,true).complete(new JSONArray().put(done)).toString(),"INVALID_PROVIDER_STREAM");
        }
        JSONObject done=reasoning("rs","opaque","s",null);
        String good=reasoningTurn(done).complete(new JSONArray().put(done)).toString();
        reject(good.replace("\"type\":\"response.reasoning_summary_part.done\"","\"status\":\"incomplete\",\"type\":\"response.reasoning_summary_part.done\""),"INVALID_PROVIDER_STREAM");
    }
    @Test public void summaryLifecycleIdentityAndDoneTextAreStrict()throws Exception {
        JSONObject done=reasoning("rs","opaque","s",null);String good=reasoningTurn(done).complete(new JSONArray().put(done)).toString();
        reject(good.replace("\"item_id\":\"rs\"","\"item_id\":\"wrong\""),"INVALID_PROVIDER_STREAM");
        reject(good.replace("\"summary_index\":0","\"summary_index\":1"),"INVALID_PROVIDER_STREAM");
        reject(good.replace("\"text\":\"s\"","\"text\":\"changed\""),"INVALID_PROVIDER_STREAM");
        reject(new ResponsesFixtures().item(0,reasoning("rs",null,null,null),false).item(0,done,true).complete(new JSONArray().put(done)).toString(),"INVALID_PROVIDER_STREAM");
        ResponsesFixtures f=new ResponsesFixtures().item(0,reasoning("rs",null,null,null),false);summary(f,"rs",0,0,"s");
        reject(f.add(summaryEvent("text.delta","rs",0,0).put("delta","late")).toString(),"INVALID_PROVIDER_STREAM");
    }
    @Test public void missingSummaryDoneAndDuplicatePartCannotBeAccepted()throws Exception {
        ResponsesFixtures f=new ResponsesFixtures().item(0,reasoning("rs",null,null,null),false);
        JSONObject added=summaryEvent("part.added","rs",0,0).put("part",summaryPart(""));f.add(added);
        reject(f.add(new JSONObject(added.toString())).toString(),"INVALID_PROVIDER_STREAM");
        f=new ResponsesFixtures().item(0,reasoning("rs",null,null,null),false);f.add(summaryEvent("part.added","rs",0,0).put("part",summaryPart("")));
        f.add(summaryEvent("text.delta","rs",0,0).put("delta","s"));
        reject(f.add(summaryEvent("part.done","rs",0,0).put("part",summaryPart("s"))).toString(),"INVALID_PROVIDER_STREAM");
    }
    @Test public void exactCredentialEchoesInOpaqueSummaryAndIdsFailWithoutMutationOrLeak()throws Exception {
        for(int where=0;where<3;where++){
            JSONObject done=reasoning(where==0?"fixture-secret":"rs",where==1?"opaque-fixture-secret":"opaque",where==2?"fixture-secret":null,null);
            JSONArray events=new JSONArray();try{ResponsesStream.read(new ByteArrayInputStream(callTurn(done).getBytes(StandardCharsets.UTF_8)),request(),new GatewayCall(5000),events::put,"fixture-secret");fail();}
            catch(GatewayFailure e){assertEquals("PROVIDER_CREDENTIAL_ECHO",e.code);assertFalse(e.toString().contains("fixture-secret"));}assertEquals(0,events.length());
        }
        ResponsesFixtures f=new ResponsesFixtures().item(0,reasoning("rs",null,null,null),false);summary(f,"rs",0,0,"fixture-","secret");JSONObject done=reasoning("rs","opaque","fixture-secret",null);f.item(0,done,true).complete(new JSONArray().put(done));
        try{ResponsesStreamTest.read(f.toString(),"fixture-secret");fail();}catch(GatewayFailure e){assertEquals("PROVIDER_CREDENTIAL_ECHO",e.code);}
    }
    @Test public void reasoningUtf8ByteCountPartsAndWholeRequestRemainBounded()throws Exception {
        rejectInput(reasoning("rs","界".repeat(22000),null,null),"UNSUPPORTED_OR_OVERSIZED_INPUT");
        JSONObject tooMany=reasoning("rs","opaque",null,null);for(int i=0;i<33;i++)tooMany.getJSONArray("summary").put(summaryPart("s"));rejectInput(tooMany,"UNSUPPORTED_OR_OVERSIZED_INPUT");
        JSONObject tooBig=reasoning("rs","opaque","界".repeat(100000),null);rejectInput(tooBig,"UNSUPPORTED_OR_OVERSIZED_INPUT");
        ResponsesFixtures f=new ResponsesFixtures().item(0,reasoning("rs",null,null,null),false);
        f.add(summaryEvent("part.added","rs",0,0).put("part",summaryPart("")));for(int i=0;i<14;i++)f.add(summaryEvent("text.delta","rs",0,0).put("delta","s".repeat(20000)));
        reject(f.toString(),"PROVIDER_RESPONSE_TOO_LARGE");
    }
    @Test public void cipherContributesToAggregateOutputBudgetEvenWithoutSummaries()throws Exception {
        ResponsesFixtures f=new ResponsesFixtures();for(int i=0;i<14;i++){JSONObject done=reasoning("rs"+i,"x".repeat(20000),null,null);f.item(i,reasoning("rs"+i,null,null,null),false).item(i,done,true);}reject(f.toString(),"PROVIDER_RESPONSE_TOO_LARGE");
    }
    @Test public void malformedUnicodeAndDuplicateReasoningIdsAreRejected()throws Exception {
        rejectInput(reasoning("rs","\ud800",null,null),"UNSUPPORTED_OR_OVERSIZED_INPUT");rejectInput(reasoning("rs","opaque","\ud800",null),"UNSUPPORTED_OR_OVERSIZED_INPUT");
        JSONObject done=reasoning("rs","opaque",null,null);ResponsesRequestTest.reject(input().put(done).put(done),tools(),"UNSUPPORTED_OR_OVERSIZED_INPUT");
        reject(new ResponsesFixtures().item(0,reasoning("rs",null,null,null),false).item(1,reasoning("rs",null,null,null),false).toString(),"INVALID_PROVIDER_STREAM");
    }
    @Test public void reasoningTurnUsageLimitNeverRetriesOrInventsReset()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            String good=callTurn(reasoning("rs","opaque",null,null));fake.stream=good.substring(0,good.indexOf("event: response.completed"))+"data: {\"type\":\"error\",\"sequence_number\":99,\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"private\"}\n\n";
            try{provider.streamResponses(ChatGptProviderTest.credentials(),request(),new GatewayCall(5000),event->fail());fail();}
            catch(GatewayFailure e){assertEquals("CHATGPT_USAGE_LIMIT_REACHED",e.code);assertEquals(0,e.retryAt);assertFalse(e.toString().contains("private"));}assertEquals(1,fake.responses.get());
        }
    }
}
