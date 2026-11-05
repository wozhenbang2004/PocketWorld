package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.features.proxy.providers.ResponsesFixtures.*;

public class AccountGatewayResponsesTest {
    @Test public void fixtureAccountFunctionTurnAndHostSuppliedResultRoundTrip()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();rig.fake.stream=oneCall();JSONArray events=new JSONArray();rig.gateway.streamResponses("",request(),new GatewayCall(5000),events::put);
            JSONArray context=new JSONArray().put(new JSONObject().put("role","user").put("content","Observe only"));JSONObject call=events.getJSONObject(0).getJSONArray("output").getJSONObject(0);context.put(call);
            // Synthetic host receipt only; the gateway has no executor and this test performs no phone action.
            context.put(new JSONObject().put("type","function_call_output").put("call_id",call.getString("call_id")).put("output","fixture observation"));
            JSONObject message=message("m","Received observation",false,true);rig.fake.stream=new ResponsesFixtures().item(0,message("m","",false,false),false).text(0,"m","Received observation",false).item(0,message,true).complete(new JSONArray().put(message)).toString();
            events=new JSONArray();rig.gateway.streamResponses(id,new ResponsesRequest("fixture-model","",context,tools()),new GatewayCall(5000),events::put);
            assertEquals(2,rig.fake.responses.get());assertEquals("call_0",rig.fake.lastRequest.getJSONArray("input").getJSONObject(2).getString("call_id"));assertEquals("text_delta",events.getJSONObject(0).getString("type"));assertEquals("connected",rig.book.row(id).getString("status"));assertFalse(rig.gateway.snapshot().getBoolean("phoneExecution"));
        }
    }
    @Test public void emptyAggregateFunctionAndTextTurnsPreserveTheSyntheticHostResultOnTheSecondPost()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();JSONObject expectedCall=function("fc_empty","call_empty","observe","{}",true);
            rig.fake.stream=new ResponsesFixtures().item(0,function("fc_empty","call_empty","observe","",false),false)
                    .argument(0,"fc_empty","{}",false).argument(0,"fc_empty","{}",true).item(0,expectedCall,true).complete(new JSONArray()).toString();
            JSONArray first=new JSONArray();rig.gateway.streamResponses(id,request(),new GatewayCall(5000),first::put);
            assertEquals(1,rig.fake.responses.get());assertEquals(1,first.length());assertEquals("completed",first.getJSONObject(0).getString("type"));
            assertEquals(1,first.getJSONObject(0).getJSONArray("output").length());JSONObject verifiedCall=first.getJSONObject(0).getJSONArray("output").getJSONObject(0);
            assertEquals(dev.pocketengine.core.ScopedToolCatalog.fingerprint(expectedCall),dev.pocketengine.core.ScopedToolCatalog.fingerprint(verifiedCall));
            // Explicit synthetic host data: this loopback fixture executes neither a model nor a phone action.
            String syntheticResult="synthetic host result: line one\n中文🙂\"quotes\"";
            JSONArray context=new JSONArray().put(new JSONObject().put("role","user").put("content","Observe only")).put(verifiedCall)
                    .put(new JSONObject().put("type","function_call_output").put("call_id",verifiedCall.getString("call_id")).put("output",syntheticResult));
            String reply="Synthetic result round-trip complete 中文🙂";JSONObject completed=message("m_empty",reply,false,true);
            rig.fake.stream=new ResponsesFixtures().item(0,message("m_empty","",false,false),false).text(0,"m_empty",reply,false).item(0,completed,true).complete(new JSONArray()).toString();
            JSONArray second=new JSONArray();rig.gateway.streamResponses(id,new ResponsesRequest("fixture-model","",context,tools()),new GatewayCall(5000),second::put);
            assertEquals(2,rig.fake.responses.get());JSONArray posted=rig.fake.lastRequest.getJSONArray("input");assertEquals(3,posted.length());
            assertEquals(dev.pocketengine.core.ScopedToolCatalog.fingerprint(verifiedCall),dev.pocketengine.core.ScopedToolCatalog.fingerprint(posted.getJSONObject(1)));
            assertEquals("function_call_output",posted.getJSONObject(2).getString("type"));assertEquals("call_empty",posted.getJSONObject(2).getString("call_id"));assertEquals(syntheticResult,posted.getJSONObject(2).getString("output"));
            assertEquals(2,second.length());assertEquals("text_delta",second.getJSONObject(0).getString("type"));assertEquals(reply,second.getJSONObject(0).getString("delta"));
            assertEquals("completed",second.getJSONObject(1).getString("type"));assertEquals(1,second.getJSONObject(1).getJSONArray("output").length());
            assertEquals(dev.pocketengine.core.ScopedToolCatalog.fingerprint(completed),dev.pocketengine.core.ScopedToolCatalog.fingerprint(second.getJSONObject(1).getJSONArray("output").getJSONObject(0)));
            assertEquals("connected",rig.book.row(id).getString("status"));assertFalse(rig.gateway.snapshot().getBoolean("phoneExecution"));
        }
    }
    static String textStream()throws Exception{JSONObject message=message("m","pause",false,true);return new ResponsesFixtures().item(0,message("m","",false,false),false).text(0,"m","pause",false).item(0,message,true).complete(new JSONArray().put(message)).toString();}
    @Test public void disableReenableCannotReviveStructuredStream()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();rig.fake.stream=textStream();JSONArray events=new JSONArray();
            try{rig.gateway.streamResponses(id,request(),new GatewayCall(5000),event->{events.put(event);rig.gateway.setEnabled(id,false);rig.gateway.setEnabled(id,true);});fail();}catch(GatewayFailure failure){assertEquals("CANCELLED",failure.code);}
            assertEquals(1,events.length());assertEquals(1,rig.fake.responses.get());assertEquals("connected",rig.book.row(id).getString("status"));
        }
    }
    @Test public void reauthorizationRevisionFencesOldCompletionWithoutRollingBackNewState()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();rig.fake.stream=textStream();JSONArray events=new JSONArray();
            try{rig.gateway.streamResponses(id,request(),new GatewayCall(5000),event->{events.put(event);rig.begin(id);});fail();}catch(GatewayFailure failure){assertEquals("STALE_ACCOUNT_VERSION",failure.code);}
            assertEquals(1,events.length());assertEquals("authorizing",rig.book.row(id).getString("status"));assertEquals("pending",rig.gateway.snapshot().getJSONObject("login").getString("status"));assertEquals(1,rig.fake.exchanges.get());
        }
    }
    @Test public void functionTurnUsageLimitPausesOnlyItsAccountAndNeverReplays()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String first=rig.connect();String second=rig.connect();String stream=oneCall();rig.fake.stream=stream.substring(0,stream.indexOf("event: response.completed"))+"data: {\"type\":\"response.failed\",\"sequence_number\":99,\"response\":{\"id\":\"resp_fixture\",\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"fixture-secret\"}}}\n\n";
            try{rig.gateway.streamResponses("",request(),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure failure){assertEquals("CHATGPT_USAGE_LIMIT_REACHED",failure.code);assertEquals(0,failure.retryAt);assertFalse(failure.toString().contains("fixture-secret"));}
            assertEquals(first,rig.book.selected());assertEquals("connected",rig.book.row(first).getString("status"));assertTrue(rig.book.row(first).getBoolean("usageLimited"));assertEquals("connected",rig.book.row(second).getString("status"));
            try{rig.gateway.streamResponses("",request(),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure failure){assertEquals("CHATGPT_USAGE_LIMIT_REACHED",failure.code);}assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void noGrantedPlanScopeMeansNoStructuredInference()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){rig.fake.scope="openid profile email offline_access";String id=rig.connect();try{rig.gateway.streamResponses(id,request(),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure failure){assertEquals("PLAN_USAGE_PERMISSION_REQUIRED",failure.code);}assertEquals(0,rig.fake.responses.get());}
    }
    @Test public void cancelledCallMakesNoPostAndDoesNotChangeAccountSelection()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){String id=rig.connect();GatewayCall call=new GatewayCall(5000);call.cancel();try{rig.gateway.streamResponses(id,request(),call,event->fail());fail();}catch(GatewayFailure failure){assertEquals("CANCELLED",failure.code);}assertEquals(0,rig.fake.responses.get());assertEquals(id,rig.book.selected());}
    }
}
