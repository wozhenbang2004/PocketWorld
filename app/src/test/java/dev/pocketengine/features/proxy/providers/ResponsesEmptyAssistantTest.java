package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.proxy.accounts.Credentials;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.runtime.ModelGateway;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static dev.pocketengine.features.proxy.providers.ResponsesFixtures.*;
import static org.junit.Assert.*;

/** The gateway's own verified assistant output must remain replayable on the next turn. */
public final class ResponsesEmptyAssistantTest {
    @Test public void verifiedEmptyAssistantBesideCallSurvivesTheToolResultTurn()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            JSONObject empty=emptyMessage("assistant").put("id","m").put("phase","commentary");
            JSONObject call=function("f","call","observe","{}",true).put("namespace","pocketworld");
            fake.stream=new ResponsesFixtures().item(0,new JSONObject(empty.toString()).put("status","in_progress"),false).item(0,empty,true)
                    .item(1,function("f","call","observe","",false).put("namespace","pocketworld"),false)
                    .argument(1,"f","{}",false).argument(1,"f","{}",true).item(1,call,true)
                    .complete(new JSONArray().put(empty).put(call)).toString();
            Credentials credentials=new Credentials("fixture-access","fixture-refresh","fixture-id",ChatGptProvider.SCOPES,System.currentTimeMillis()+3600000);
            ModelGateway.installAccountInferencePort((ref,request,operation,sink)->provider.streamResponses(credentials,request,operation,sink));
            ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan")
                    .put("accountId","00000000-0000-0000-0000-000000000001").put("accountVersion",1).put("modelId","fixture-model"));
            ModelConfig config=new ModelConfig("","","",false,3,10).withAccountModel(ref);RunControl control=new RunControl();
            JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","Observe only"));
            JSONObject function=new JSONObject(declaration("observe").toString());function.remove("type");
            JSONArray tools=new JSONArray().put(new JSONObject().put("type","function").put("function",function));
            JSONObject first=ModelGateway.complete(config,history,tools,control);
            assertEquals("",first.getString("content"));assertEquals(1,first.getJSONArray("tool_calls").length());
            history.put(first).put(new JSONObject().put("role","tool").put("tool_call_id","call").put("content","Fixture observation"));
            JSONObject answer=message("m2","Done",false,true);
            fake.stream=new ResponsesFixtures().item(0,message("m2","",false,false),false).text(0,"m2","Done",false)
                    .item(0,answer,true).complete(new JSONArray().put(answer)).toString();
            assertEquals("Done",ModelGateway.complete(config,history,tools,control).getString("content"));assertEquals(2,fake.responses.get());
            JSONArray input=fake.lastRequest.getJSONArray("input");assertEquals(4,input.length());
            JSONObject restored=input.getJSONObject(1);assertEquals("m",restored.getString("id"));
            assertEquals("commentary",restored.getString("phase"));assertEquals(0,restored.getJSONArray("content").length());
            assertEquals("call",input.getJSONObject(2).getString("call_id"));assertEquals("call",input.getJSONObject(3).getString("call_id"));
        }finally{ModelGateway.installAccountInferencePort(null);}
    }
    @Test public void onlyCompletedAssistantPlaceholdersMayHaveEmptyContent()throws Exception {
        new ResponsesRequest("fixture-model","",new JSONArray().put(emptyMessage("assistant")),new JSONArray());
        for(String role:new String[]{"user","developer"})assertThrows(GatewayFailure.class,()->new ResponsesRequest("fixture-model","",new JSONArray().put(emptyMessage(role)),new JSONArray()));
        for(String status:new String[]{"in_progress","incomplete"})assertThrows(GatewayFailure.class,()->new ResponsesRequest("fixture-model","",new JSONArray().put(emptyMessage("assistant").put("status",status)),new JSONArray()));
        JSONObject missing=emptyMessage("assistant");missing.remove("status");
        assertThrows(GatewayFailure.class,()->new ResponsesRequest("fixture-model","",new JSONArray().put(missing),new JSONArray()));
    }
    private static JSONObject emptyMessage(String role)throws Exception {
        return new JSONObject().put("type","message").put("role",role).put("status","completed").put("content",new JSONArray());
    }
}
