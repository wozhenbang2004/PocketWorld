package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static dev.pocketengine.features.proxy.providers.ResponsesFixtures.*;
import static org.junit.Assert.*;

/** Incomplete items are not completed calls; only the later terminal supplies failure/usage. */
public final class ResponsesIncompleteItemTest {
    @Test public void incompleteTextPreservesTheRealTerminalAndUsage()throws Exception {
        Recorder out=failStream(textPrefix("incomplete"),"response.incomplete","PROVIDER_RESPONSE_INCOMPLETE");
        assertEquals(1,out.events.length());assertEquals("partial",out.events.getJSONObject(0).getString("delta"));
    }
    @Test public void incompleteArgumentsNeverBecomeExecutableButStillReportUsage()throws Exception {
        Recorder out=failStream(functionPrefix("incomplete","{\"x\":"),"response.incomplete","PROVIDER_RESPONSE_INCOMPLETE");
        assertEquals(0,out.events.length());
    }
    @Test public void incompleteReasoningDoesNotRequireReplayableCiphertext()throws Exception {
        JSONObject added=new JSONObject().put("type","reasoning").put("id","r").put("summary",new JSONArray()).put("encrypted_content",JSONObject.NULL).put("status","in_progress");
        ResponsesFixtures wire=new ResponsesFixtures().item(0,added,false).item(0,new JSONObject(added.toString()).put("status","incomplete"),true);
        assertEquals(0,failStream(wire,"response.incomplete","PROVIDER_RESPONSE_INCOMPLETE").events.length());
    }
    @Test public void failedTerminalAfterAnIncompleteItemRemainsAFailureWithUsage()throws Exception {
        failStream(textPrefix("incomplete"),"response.failed","PROVIDER_RESPONSE_FAILED");
    }
    @Test public void incompleteItemCanNeverBecomeACompletedTurn()throws Exception {
        JSONObject item=message("m","partial",false,true).put("status","incomplete");
        Recorder out=readFailure(textPrefix("incomplete").complete(new JSONArray().put(item)).toString(),"INVALID_PROVIDER_STREAM");
        assertEquals(1,out.events.length());assertEquals(1,out.usages.length());
        Recorder calls=readFailure(functionPrefix("incomplete","{}").complete(new JSONArray().put(function("f","call","observe","{}",true))).toString(),"INVALID_PROVIDER_STREAM");
        assertEquals(0,calls.events.length());
        // The verified-done fallback may restore completed items, never an incomplete one.
        Recorder emptyAggregate=readFailure(functionPrefix("incomplete","{}").complete(new JSONArray()).toString(),"INVALID_PROVIDER_STREAM");
        assertEquals(0,emptyAggregate.events.length());assertEquals(1,emptyAggregate.usages.length());
    }
    @Test public void malformedCompletedCallAndWrongIncompleteIdentityStillFailClosed()throws Exception {
        readFailure(functionPrefix("completed","{\"x\":").toString(),"INVALID_PROVIDER_STREAM");
        ResponsesFixtures wrong=new ResponsesFixtures().item(0,message("m","",false,false),false).text(0,"m","partial",false)
                .item(0,message("other","partial",false,true).put("status","incomplete"),true);
        assertEquals(0,readFailure(wrong.toString(),"INVALID_PROVIDER_STREAM").usages.length());
    }
    @Test public void incompleteItemWithoutTerminalRemainsInterruptedAndUnpublished()throws Exception {
        Recorder out=readFailure(functionPrefix("incomplete","{\"x\":").toString(),"PROVIDER_STREAM_INTERRUPTED");
        assertEquals(0,out.events.length());assertEquals(0,out.usages.length());
    }
    @Test public void incompleteHttpTurnRetainsUsageInItsFailedNativeReceipt()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            fake.stream=textPrefix("incomplete").add(new JSONObject().put("type","response.incomplete").put("response",
                    new JSONObject().put("id","resp_fixture").put("status","incomplete").put("usage",new JSONObject().put("input_tokens",4).put("output_tokens",6)))).toString();
            var credentials=new dev.pocketengine.features.proxy.accounts.Credentials("fixture-access","fixture-refresh","fixture-id",ChatGptProvider.SCOPES,System.currentTimeMillis()+3600000);
            dev.pocketengine.runtime.ModelGateway.installAccountInferencePort((ref,request,operation,sink)->provider.streamResponses(credentials,request,operation,sink));
            var ref=dev.pocketengine.core.ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan")
                    .put("accountId","00000000-0000-0000-0000-000000000001").put("accountVersion",1).put("modelId","fixture-model"));
            var config=new dev.pocketengine.core.ModelConfig("","","",false,3,10).withAccountModel(ref);
            java.util.List<dev.pocketengine.runtime.usage.InferenceReceipt> receipts=new java.util.ArrayList<>();
            var target=new dev.pocketengine.runtime.usage.InferenceCapture.Target(dev.pocketengine.runtime.usage.InferenceReceipt.Context.unattributed(),new dev.pocketengine.runtime.usage.InferenceCapture.Sink(){
                public void started(dev.pocketengine.runtime.usage.InferenceReceipt value){}public void finished(dev.pocketengine.runtime.usage.InferenceReceipt value){receipts.add(value);}});
            JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","hello"));
            assertThrows(dev.pocketengine.runtime.ModelGateway.GatewayException.class,()->dev.pocketengine.runtime.ModelGateway.completeStreaming(config,history,null,new dev.pocketengine.core.RunControl(),event->{},target));
            assertEquals(1,fake.responses.get());assertEquals(1,receipts.size());
            assertEquals(dev.pocketengine.runtime.usage.InferenceReceipt.Outcome.FAILED,receipts.get(0).outcome);
            JSONObject counts=receipts.get(0).toJson().getJSONObject("usage").getJSONObject("counters");
            assertEquals(4,counts.getJSONObject("INPUT").getLong("value"));assertEquals(6,counts.getJSONObject("OUTPUT").getLong("value"));
        }finally{dev.pocketengine.runtime.ModelGateway.installAccountInferencePort(null);}
    }
    private ResponsesFixtures textPrefix(String status)throws Exception {
        return new ResponsesFixtures().item(0,message("m","",false,false),false).text(0,"m","partial",false)
                .item(0,message("m","partial",false,true).put("status",status),true);
    }
    private ResponsesFixtures functionPrefix(String status,String arguments)throws Exception {
        return new ResponsesFixtures().item(0,function("f","call","observe","",false),false).argument(0,"f",arguments,false)
                .argument(0,"f",arguments,true).item(0,function("f","call","observe",arguments,true).put("status",status),true);
    }
    private Recorder failStream(ResponsesFixtures wire,String type,String code)throws Exception {
        JSONObject response=new JSONObject().put("id","resp_fixture").put("status",type.substring(9))
                .put("usage",new JSONObject().put("input_tokens",4).put("output_tokens",6).put("total_tokens",10));
        Recorder out=readFailure(wire.add(new JSONObject().put("type",type).put("response",response)).toString(),code);
        assertEquals(1,out.usages.length());assertEquals(10,out.usages.getJSONObject(0).getJSONObject("usage").getLong("total_tokens"));return out;
    }
    private Recorder readFailure(String wire,String code)throws Exception {
        Recorder out=new Recorder();GatewayFailure failure=assertThrows(GatewayFailure.class,()->ResponsesStream.read(
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),request(),new GatewayCall(5000),out));
        assertEquals(code,failure.code);for(int i=0;i<out.events.length();i++)assertNotEquals("completed",out.events.getJSONObject(i).optString("type"));return out;
    }
    private static final class Recorder implements ChatGptProvider.EventSink {
        final JSONArray events=new JSONArray(),usages=new JSONArray();
        public void event(JSONObject value){events.put(value);}public void usage(JSONObject value){usages.put(value);}
    }
}
