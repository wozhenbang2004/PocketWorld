package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatGptRecoveryTest {
    @Test public void usageHoldPreservesCredentialsStopsNewRequestsAndNeedsAnExplicitOwnerAction()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");String slot=rig.book.row(id).getString("slot");
            rig.fake.responseStatus=429;rig.fake.responseErrorBody="{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"private token <html>\"}}";
            try{rig.gateway.streamResponsesReviewed(id,version,ResponsesFixtures.request(),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure limit){assertEquals("CHATGPT_USAGE_LIMIT_REACHED",limit.code);assertEquals(0,limit.retryAt);}
            assertTrue(rig.gateway.planUsageLimited(id,version));assertEquals(slot,rig.book.row(id).getString("slot"));assertEquals(1,rig.vault.values.size());assertEquals(version,rig.book.row(id).getLong("version"));
            assertFalse(rig.gateway.snapshot().toString().contains("private token"));
            try{rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));fail();}catch(GatewayFailure limit){assertEquals("CHATGPT_USAGE_LIMIT_REACHED",limit.code);}
            assertEquals(1,rig.fake.responses.get());assertEquals(0,rig.fake.models.get());assertEquals(0,rig.fake.refreshes.get());
            rig.gateway.retryPlanUsageReviewed(id,version);assertFalse(rig.gateway.planUsageLimited(id,version));assertEquals(1,rig.fake.responses.get());assertEquals(0,rig.fake.refreshes.get());
            rig.fake.responseStatus=200;JSONObject message=ResponsesFixtures.message("msg","verified",false,true);rig.fake.stream=new ResponsesFixtures().item(0,ResponsesFixtures.message("msg","",false,false),false).text(0,"msg","verified",false).item(0,message,true).complete(new JSONArray().put(message)).toString();
            JSONArray events=new JSONArray();rig.gateway.streamResponsesReviewed(id,version,ResponsesFixtures.request(),new GatewayCall(5000),events::put);
            assertEquals("completed",events.getJSONObject(events.length()-1).getString("type"));assertEquals(2,rig.fake.responses.get());assertEquals("Bearer fixture-access-secret-0",rig.fake.lastAuthorization);
        }
    }
    @Test public void aLaterInFlightTemporaryFailureCannotClearAnEarlierUsageHold()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
            rig.fake.responseErrorBody="{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}";
            rig.fake.responseStatusForCall=number->{if(number==1){entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("first response was never released");}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new AssertionError(stopped);}return 503;}return 429;};
            var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{var first=worker.submit(()->{try{rig.gateway.streamResponsesReviewed(id,version,ResponsesFixtures.request(),new GatewayCall(5000),event->fail());return "unexpected";}catch(GatewayFailure failure){return failure.code;}});
                assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
                try{rig.gateway.streamResponsesReviewed(id,version,ResponsesFixtures.request(),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure limit){assertEquals("CHATGPT_USAGE_LIMIT_REACHED",limit.code);}
                assertTrue(rig.gateway.planUsageLimited(id,version));rig.fake.responseErrorBody="{\"error\":{\"code\":\"server_error\"}}";release.countDown();
                assertEquals("PROVIDER_UNAVAILABLE",first.get(2,java.util.concurrent.TimeUnit.SECONDS));assertTrue("A different in-flight failure cleared the owner's usage pause",rig.gateway.planUsageLimited(id,version));
                try{rig.gateway.streamResponsesReviewed(id,version,ResponsesFixtures.request(),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure limit){assertEquals("CHATGPT_USAGE_LIMIT_REACHED",limit.code);}
                assertEquals(2,rig.fake.responses.get());assertEquals(0,rig.fake.refreshes.get());
            }finally{release.countDown();worker.shutdownNow();}
        }
    }
    @Test public void exactOfficialRecoveryCodesKeepIdentityAndBoundOnlyTemporaryRetries()throws Exception {
        String[][] cases={{"subscription_sharing_user_not_eligible","CHATGPT_PLAN_NOT_ELIGIBLE"},{"subscription_sharing_usage_limit_exceeded","CHATGPT_USAGE_LIMIT_REACHED"},
            {"subscription_sharing_unsupported_capability","CHATGPT_UNSUPPORTED_CAPABILITY"},{"subscription_sharing_route_not_supported","CHATGPT_ROUTE_NOT_SUPPORTED"},
            {"subscription_sharing_invalid_user","CHATGPT_ACCOUNT_PERMISSION_REJECTED"},{"chatpass_v2_scope_not_authorized","CHATGPT_DIRECT_PERMISSION_REJECTED"},
            {"chatpass_v2_invalid_authorization_context","CHATGPT_DIRECT_PERMISSION_REJECTED"},{"subscription_sharing_usage_unavailable","PROVIDER_UNAVAILABLE"},{"subscription_sharing_user_unavailable","PROVIDER_UNAVAILABLE"}};
        for(String[] value:cases){GatewayFailure failure=ChatGptRecovery.failure(403,new JSONObject().put("error",new JSONObject().put("code",value[0]).put("message","private <html>")),"json_error","req_fixture","",1000);
            assertEquals(value[1],failure.code);assertEquals(value[0],failure.providerCode);assertEquals(value[0].endsWith("unavailable")?1000:0,failure.retryAt);assertFalse(failure.toString().contains("private"));assertFalse(failure.diagnostics().toString().contains("html"));}
    }
    @Test public void safeFieldAndRequestIdentitySurviveWhileArbitraryValuesAndBodiesDoNot()throws Exception {
        JSONObject error=new JSONObject().put("code","subscription_sharing_unsupported_capability").put("message","Bearer fixture-secret").put("param","input[0].content[1].image_url");
        GatewayFailure failure=ChatGptRecovery.failure(400,new JSONObject().put("error",error),"json_error","req_fixture123","",0);
        assertEquals("input[0].content[1].image_url",failure.param);assertEquals("req_fixture123",failure.requestId);assertEquals(400,failure.httpStatus);
        error.put("code","private-secret-code").put("param","fixture-secret");failure=ChatGptRecovery.failure(403,new JSONObject().put("error",error),"json_error","fixture-secret","",0);
        assertEquals("",failure.providerCode);assertEquals("",failure.param);assertEquals("",failure.requestId);assertFalse(failure.diagnostics().toString().contains("secret"));
        GatewayFailure html=ChatGptRecovery.failure(503,null,"non_json","","",1000);assertEquals("PROVIDER_UNAVAILABLE",html.code);assertEquals("non_json",html.bodyShape);
    }
}
