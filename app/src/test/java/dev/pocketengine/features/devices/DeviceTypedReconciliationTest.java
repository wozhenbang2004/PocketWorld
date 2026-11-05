package dev.pocketengine.features.devices;

import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real typed protocol, source projection and Runner. No paired device or real model is contacted. */
public final class DeviceTypedReconciliationTest {
    private static final String OPERATION="original-operation",DEVICE="pw1_"+"b".repeat(64),CALLER="pw1_"+"a".repeat(64),INSTANCE="original-instance";
    @Test public void missingOriginalReceiptStopsBeforeAnotherModelDecision()throws Exception {run(typed("not_found",false,"unknown",new JSONObject()));}
    @Test public void requiredReconciliationStopsEvenIfNestedPayloadClaimsSuccess()throws Exception {run(typed("needs_reconcile",true,"reported",new JSONObject().put("receipt",new JSONObject().put("ok",true))));}
    @Test public void ordinaryPendingAndConfirmedOutcomesKeepTheirMeaning()throws Exception {
        for(String state:new String[]{"accepted","waiting_resource","running"}){JSONObject result=project(typed(state,true,"unknown",new JSONObject()));assertFalse(result.getBoolean("ok"));assertFalse(result.optBoolean("requiresReconciliation"));assertEquals(state,result.getString("status"));assertEquals("device_request_accepted_waiting_result",result.getString("code"));}
        JSONObject completed=project(typed("succeeded",true,"reported",new JSONObject()));assertTrue(completed.getBoolean("ok"));assertFalse(completed.optBoolean("requiresReconciliation"));
        JSONObject rejected=project(typed("rejected",false,"not_dispatched",new JSONObject()));assertFalse(rejected.getBoolean("ok"));assertFalse(rejected.optBoolean("requiresReconciliation"));
    }
    private static JSONObject typed(String state,boolean accepted,String effect,JSONObject payload)throws Exception {
        JSONObject facts=null;
        if(accepted){JSONObject audience=new JSONObject().put("kind","operation").put("objectId",OPERATION).put("originDeviceId",CALLER).put("callerDeviceId",CALLER).put("targetDeviceId",DEVICE).put("instanceId",INSTANCE).put("rootTaskId","original-root").put("localTaskId","original-task").put("grantId","original-grant").put("authorizationEpoch",1).put("resourceBinding",new JSONObject()).put("acceptedAt",1).put("retainedUntil",2);facts=new JSONObject().put("acceptedAt",1).put("audience",audience);}
        return DeviceProtocol.operationReceipt(DEVICE,INSTANCE,OPERATION,accepted?"c".repeat(64):null,state,accepted,effect,payload,facts);
    }
    private static JSONObject project(JSONObject typed)throws Exception {var method=DeviceCapabilityPort.class.getDeclaredMethod("localAgentReceipt",JSONObject.class);method.setAccessible(true);return (JSONObject)method.invoke(null,typed);}
    private static void run(JSONObject typed)throws Exception {
        JSONObject receipt=project(typed);AtomicInteger requests=new AtomicInteger(),queries=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            exchange.getRequestBody().readAllBytes();int turn=requests.incrementAndGet();JSONObject message=new JSONObject().put("role","assistant");
            if(turn==1)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","query_original").put("type","function").put("function",new JSONObject().put("name","device_operation_get").put("arguments","{}"))));else message.put("content","Unexpected further decision");
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",turn==1?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
        RunControl control=new RunControl();control.bindHostRun("device-task","device-run",1);
        try {
            CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","device_operation_get").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false))));}
                public boolean supports(String name){return name.equals("device_operation_get");}
                public boolean sideEffecting(String name){return false;}
                public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{queries.incrementAndGet();return new JSONObject(receipt.toString());}
            };
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,4,10,"openai_chat",1000).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            RunOutcome outcome=new AgentRunner(null,port,budget).run("device-task","Check the original device operation; never resubmit it",config,control,event->{});
            assertEquals("Explicit unknown original state must stop the decision loop",1,requests.get());assertEquals(1,queries.get());assertEquals("awaiting_user",outcome.status);
            assertFalse(receipt.getBoolean("ok"));assertTrue(receipt.getBoolean("requiresReconciliation"));assertTrue(dev.pocketengine.store.TaskStoreDeviceTasks.unknownReceipt(receipt));assertEquals("device_original_state_unknown",receipt.getString("code"));assertEquals(OPERATION,receipt.getString("operationId"));assertEquals(typed.getString("status"),receipt.getString("status"));assertEquals(typed.getBoolean("accepted"),receipt.getBoolean("accepted"));assertFalse(receipt.getBoolean("retrySubmitted"));assertEquals("device_operation_get",receipt.getString("nextAction"));assertEquals(typed.toString(),receipt.getJSONObject("deviceReceipt").toString());
        }finally{control.cancel();server.stop(0);}
    }
}
