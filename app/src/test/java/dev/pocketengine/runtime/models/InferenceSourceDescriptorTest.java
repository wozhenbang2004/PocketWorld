package dev.pocketengine.runtime.models;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InferenceSourceDescriptorTest {
    private static JSONObject source(String provider,String url)throws Exception {
        return InferenceSourceDescriptor.forApi(provider,url,"","saved_unverified");
    }

    @Test public void officialIdentityRequiresTheExactHttpsHostAndMatchingProtocol()throws Exception {
        String[][] pairs={{"openai_chat","https://api.openai.com/v1","openai"},
            {"openai_responses","https://API.OPENAI.COM:443/v1","openai"},
            {"anthropic","https://api.anthropic.com","anthropic"},
            {"gemini","https://generativelanguage.googleapis.com/v1beta","gemini"}};
        for(String[] pair:pairs){
            JSONObject source=source(pair[0],pair[1]);
            assertEquals("official_api",source.getString("kind"));assertEquals(pair[2],source.getString("providerId"));
            assertEquals("api_key",source.getString("authentication"));assertTrue(source.getBoolean("appInference"));
            assertTrue(source.getBoolean("externalRelayEligible"));assertEquals("saved_unverified",source.getString("credentialState"));
        }
    }

    @Test public void lookalikesCredentialsAndWrongProtocolNeverClaimOfficialIdentity()throws Exception {
        String[][] pairs={{"openai_chat","https://api.openai.com.attacker.invalid/v1"},
            {"openai_chat","https://api.openai.com@attacker.invalid/v1"},
            {"openai_chat","https://user@api.openai.com/v1"},
            {"openai_chat","http://api.openai.com/v1"},
            {"openai_chat","https://api.openai.com:8443/v1"},
            {"openai_chat","https://api.openai.com./v1"},
            {"openai_chat","https://api.openai.com/v1#fragment"},
            {"openai_chat","https://api.anthropic.com"},
            {"anthropic","https://api.openai.com/v1"},
            {"gemini","https://www.googleapis.com/v1beta"},
            {"openai_responses","https://gateway.invalid/api.openai.com/v1"},
            {"openai_chat","not a URL"}};
        for(String[] pair:pairs){
            JSONObject source=source(pair[0],pair[1]);
            assertEquals(pair[1],"third_party_gateway",source.getString("kind"));
            assertEquals("custom",source.getString("providerId"));
        }
    }

    @Test public void localIdentityUsesLiteralLoopbackAddressesWithoutResolvingDns()throws Exception {
        for(String host:new String[]{"localhost","LOCALHOST","127.0.0.1","127.17.23.45","[::1]","[0::0001]",
                "[0:0:0:0:0:0:0:1]","[::ffff:127.0.0.1]","[0:0:0:0:0:ffff:7f11:172d]"}){
            JSONObject source=source("openai_chat","http://"+host+":8000/v1");
            assertEquals(host,"local_gateway",source.getString("kind"));assertEquals("custom",source.getString("providerId"));
        }
        for(String host:new String[]{"localhost.attacker.invalid","127.0.0.1.attacker.invalid","128.0.0.1","192.168.1.2",
                "[::]","[::2]","[2001:db8::1]","[::ffff:192.168.1.2]"}){
            assertEquals(host,"third_party_gateway",source("openai_chat","http://"+host+":8000/v1").getString("kind"));
        }
    }

    @Test public void nativeBedrockSourceKeepsItsCredentialKindAndCannotBeRelayed()throws Exception {
        String url="https://bedrock-runtime.us-east-1.amazonaws.com";
        JSONObject source=InferenceSourceDescriptor.forApi("bedrock_anthropic",url,"us-east-1","not_configured");
        assertEquals("cloud_provider",source.getString("kind"));assertEquals("aws_bedrock",source.getString("providerId"));
        assertEquals("aws_sts_session",source.getString("authentication"));assertFalse(source.getBoolean("externalRelayEligible"));
        assertEquals("not_configured",source.getString("credentialState"));
        assertEquals("third_party_gateway",source("anthropic",url).getString("kind"));
        assertEquals("third_party_gateway",InferenceSourceDescriptor.forApi("bedrock_anthropic",url,"us-west-2","saved_unverified").getString("kind"));
    }

    @Test public void subscriptionAndUnrecognizedProtocolsCannotEnterTheApiProjection() {
        assertThrows(IllegalArgumentException.class,()->source("chatgpt_plan","https://api.openai.com/v1"));
        assertThrows(IllegalArgumentException.class,()->source("future_protocol","https://api.openai.com/v1"));
    }

    @Test public void subscriptionIdentityNeverClaimsQuotaOrRelayAuthorization()throws Exception {
        for(String status:new String[]{"connected","refreshing","cooldown","reauthorization_required","signed_out"}){
            JSONObject source=InferenceSourceDescriptor.forChatGptAccount(status,false);
            assertEquals("subscription_account",source.getString("kind"));assertEquals("chatgpt",source.getString("providerId"));
            assertEquals("oauth",source.getString("authentication"));assertTrue(source.getBoolean("appInference"));
            assertFalse(source.getBoolean("externalRelayEligible"));assertEquals(status,source.getString("credentialState"));
            assertEquals("unknown",source.getJSONObject("quota").getString("state"));
            assertEquals(1,source.getJSONObject("quota").length());
        }
        assertEquals("limited",InferenceSourceDescriptor.forChatGptAccount("connected",true).getJSONObject("quota").getString("state"));
    }
}
