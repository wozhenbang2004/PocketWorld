package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exact selected-token catalog and real local HTTP; no account, device, or live usage. */
public final class ChatGptPhoneContractTest {
    @Test public void selectedTokenCatalogRetainsExplicitImageEvidenceWithoutGuessingModelNames()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            fake.modelsBody="{\"models\":[{\"slug\":\"neutral-name\",\"display_name\":\"Neutral\",\"visibility\":\"list\",\"input_modalities\":[\"text\",\"image\"]},{\"slug\":\"vision-sounding-name\",\"display_name\":\"Unknown\",\"visibility\":\"list\"}]}";
            var models=provider.models(ChatGptProviderTest.credentials(),new GatewayCall(5000));
            assertEquals("Bearer "+FakeChatGpt.access(0),fake.lastAuthorization);
            JSONObject known=models.getJSONObject(0).optJSONObject("imageInput");assertNotNull("Explicit catalog capability must survive parsing",known);
            assertTrue(known.getBoolean("known"));assertTrue(known.getBoolean("value"));
            JSONObject unknown=models.getJSONObject(1).getJSONObject("imageInput");assertFalse(unknown.getBoolean("known"));assertTrue(unknown.isNull("value"));
        }
    }
    @Test public void conflictingCatalogImageDeclarationsCannotGrantImages()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            fake.modelsBody="{\"models\":[{\"slug\":\"fixture-model\",\"display_name\":\"Fixture\",\"visibility\":\"list\",\"input_modalities\":[\"text\"],\"capabilities\":{\"image_input\":{\"supported\":true}}}]}";
            JSONObject image=provider.models(ChatGptProviderTest.credentials(),new GatewayCall(5000)).getJSONObject(0).optJSONObject("imageInput");
            assertNotNull(image);assertFalse(image.getBoolean("known"));assertTrue(image.isNull("value"));
        }
    }
    private static GatewayFailure failure(int status,String body)throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            fake.responseStatus=status;fake.responseErrorBody=body;
            try{provider.stream(ChatGptProviderTest.credentials(),"fixture-model","",ChatGptProviderTest.input(),new GatewayCall(5000),event->fail());throw new AssertionError("Expected actual provider failure");}
            catch(GatewayFailure failure){assertEquals(1,fake.responses.get());assertEquals(0,fake.refreshes.get());assertEquals(0,fake.exchanges.get());assertFalse(failure.toString().contains("fixture-secret"));return failure;}
        }
    }
    @Test public void appOrPlanUsageLimitDoesNotInventABalanceOrResetTime()throws Exception {
        GatewayFailure failure=failure(429,"{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"fixture-secret\"}}");
        assertEquals("CHATGPT_USAGE_LIMIT_REACHED",failure.code);assertEquals(0,failure.retryAt);
    }
    @Test public void regionOrPolicyAdmissionIsDistinctFromHavingToRepeatOAuth()throws Exception {
        assertEquals("CHATGPT_POLICY_RESTRICTED",failure(403,"{\"detail\":\"fixture-secret <html>policy</html>\"}").code);
        assertEquals("CHATGPT_ACCOUNT_PERMISSION_REJECTED",failure(401,"{\"detail\":\"fixture-secret\"}").code);
    }
    @Test public void grantExpiryIsDistinctFromAConfirmedUnusableRefreshToken()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            fake.tokenStatus=400;fake.tokenErrorBody="{\"error\":\"invalid_grant\"}";
            try{provider.exchange("oaiapp_fixture","consumed-code","verifier","http://127.0.0.1:1234/auth/callback","nonce",new GatewayCall(5000),System.currentTimeMillis());fail();}
            catch(GatewayFailure failure){assertEquals("AUTHORIZATION_CODE_EXPIRED",failure.code);}
            try{provider.refresh("oaiapp_fixture",ChatGptProviderTest.credentials(),"fixture-subject",new GatewayCall(5000),System.currentTimeMillis());fail();}
            catch(GatewayFailure failure){assertEquals("REAUTHORIZATION_REQUIRED",failure.code);}
            assertEquals(1,fake.exchanges.get());assertEquals(1,fake.refreshes.get());
        }
    }
}
