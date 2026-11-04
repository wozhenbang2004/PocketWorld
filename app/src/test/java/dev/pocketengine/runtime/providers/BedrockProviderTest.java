package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.AwsSessionCredentials;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.usage.ProviderUsage;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real production codec and signer entry. No network or AWS account is used. */
public final class BedrockProviderTest {
    private static final String MODEL="anthropic.claude-sonnet-4-6";
    private static final String PROTOCOL="bedrock_anthropic";
    private static ModelConfig config()throws Exception {
        return new ModelConfig("","",MODEL,false,20,60,PROTOCOL,512).withBedrockSession("us-east-1",
                new AwsSessionCredentials("ASIAABCDEFGHIJKLMNOP","s".repeat(40),"fixtureSessionToken0123456789",System.currentTimeMillis()+600_000));
    }
    private static JSONArray messages()throws Exception {
        return new JSONArray().put(new JSONObject().put("role","system").put("content","Use the offered tools."))
                .put(new JSONObject().put("role","user").put("content","Inspect the current result."));
    }
    private static JSONArray tools()throws Exception {
        return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject()
                .put("name","inspect").put("parameters",new JSONObject().put("type","object"))));
    }
    private static JSONObject response()throws Exception {
        return new JSONObject().put("type","message").put("id","message-one").put("role","assistant").put("stop_reason","tool_use")
                .put("content",new JSONArray().put(new JSONObject().put("type","thinking").put("thinking","private reasoning").put("signature","native-signature"))
                        .put(new JSONObject().put("type","text").put("text","Checking."))
                        .put(new JSONObject().put("type","tool_use").put("id","tool-one").put("name","inspect").put("input",new JSONObject().put("n",1))))
                .put("usage",new JSONObject().put("input_tokens",3).put("output_tokens",6).put("cache_read_input_tokens",100).put("cache_creation_input_tokens",20));
    }
    @Test public void requestUsesTheNativeMessagesBodyWithoutDirectApiModelOrStreamFields()throws Exception {
        ModelConfig config=config();ProviderAdapter provider=ProviderAdapters.selected(PROTOCOL);
        JSONObject body=provider.request(config,messages(),tools());
        assertEquals("bedrock-2023-05-31",body.getString("anthropic_version"));
        assertFalse(body.has("model"));assertFalse(body.has("stream"));assertEquals(512,body.getInt("max_tokens"));
        assertEquals("Use the offered tools.",body.getJSONArray("system").getJSONObject(0).getString("text"));
        assertEquals("user",body.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals("object",body.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema").getString("type"));
        JSONObject streaming=ApiTurnStream.request(PROTOCOL,body,config.maxOutputTokens);
        assertFalse(streaming.has("model"));assertFalse(streaming.has("stream"));
        assertEquals("bedrock-2023-05-31",streaming.getString("anthropic_version"));
        assertFalse(body.toString().contains(config.awsSession.sessionToken()));
        assertFalse(body.toString().contains(config.awsSession.secretAccessKey()));
    }
    @Test public void nativeHistoryRetainsItsBedrockIdentityAndOpaqueThinkingSignature()throws Exception {
        ProviderAdapter provider=ProviderAdapters.selected(PROTOCOL);JSONObject assistant=provider.response(response());
        assertEquals(PROTOCOL,assistant.getJSONObject("provider_data").getString("provider"));
        assertEquals("Checking.",assistant.getString("content"));assertFalse(assistant.getString("content").contains("private reasoning"));
        assertEquals("tool-one",assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        JSONArray history=messages().put(assistant).put(new JSONObject().put("role","tool").put("tool_call_id","tool-one").put("content","actual result"));
        JSONArray nativeMessages=provider.request(config(),history,tools()).getJSONArray("messages");
        JSONObject thinking=nativeMessages.getJSONObject(1).getJSONArray("content").getJSONObject(0);
        assertEquals("thinking",thinking.getString("type"));assertEquals("native-signature",thinking.getString("signature"));
        assertEquals("private reasoning",thinking.getString("thinking"));
        assertEquals("tool-one",nativeMessages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("tool_use_id"));
        assertEquals("anthropic",ProviderAdapters.selected("anthropic").response(response()).getJSONObject("provider_data").getString("provider"));
    }
    @Test public void sameRequestJsonUsesTheSharedOfferedToolAndTerminalValidation()throws Exception {
        ProviderAdapter provider=ProviderAdapters.selected(PROTOCOL);ProviderUsage.Collector usage=new ProviderUsage.Collector(PROTOCOL);
        JSONObject result=ApiTurnStream.completeJson(PROTOCOL,provider,response().toString(),tools(),delta->{},usage);
        assertEquals("tool-one",result.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        assertEquals(PROTOCOL,usage.toJson().getString("protocol"));assertEquals("tool_use",usage.toJson().getString("finishReason"));
        assertEquals(3,usage.get(ProviderUsage.Counter.INPUT).value);
        assertThrows(Exception.class,()->ApiTurnStream.completeJson(PROTOCOL,provider,response().toString(),new JSONArray(),delta->{}));
        JSONObject incomplete=response();incomplete.remove("stop_reason");
        assertThrows(Exception.class,()->ApiTurnStream.completeJson(PROTOCOL,provider,incomplete.toString(),tools(),delta->{}));
        JSONObject mismatched=response().put("stop_reason","end_turn");
        assertThrows(Exception.class,()->ApiTurnStream.completeJson(PROTOCOL,provider,mismatched.toString(),tools(),delta->{}));
    }
    @Test public void headersRequireTheActualFinalPayloadAndBindItToTheExactNativeEndpoint()throws Exception {
        ModelConfig config=config();ProviderAdapter provider=ProviderAdapters.selected(PROTOCOL);URL endpoint=provider.endpoint(config);
        assertEquals("https",endpoint.getProtocol());assertEquals("bedrock-runtime.us-east-1.amazonaws.com",endpoint.getHost());
        assertTrue(endpoint.getPath().endsWith("/invoke"));assertThrows(SecurityException.class,()->provider.headers(config));
        byte[] bytes=provider.request(config,messages(),tools()).toString().getBytes(StandardCharsets.UTF_8);
        Map<String,String> signed=provider.headers(config,endpoint,bytes);
        assertTrue(header(signed,"authorization").startsWith("AWS4-HMAC-SHA256 "));
        assertEquals(config.awsSession.sessionToken(),header(signed,"x-amz-security-token"));
        assertEquals("application/json",header(signed,"content-type"));assertNull(header(signed,"anthropic-version"));
        byte[] changed=(new String(bytes,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8);
        assertNotEquals(header(signed,"x-amz-content-sha256"),header(provider.headers(config,endpoint,changed),"x-amz-content-sha256"));
        URL wrong=new URL(endpoint.toString().replace("us-east-1","us-west-2"));
        assertThrows(Exception.class,()->provider.headers(config,wrong,bytes));
    }
    @Test public void selectedBedrockCodecRejectsAnUnrelatedOrUnconfiguredNativeRoute()throws Exception {
        ProviderAdapter provider=ProviderAdapters.selected(PROTOCOL);
        ModelConfig direct=new ModelConfig("https://example.invalid","fixture-key","fixture-model",false,4,10,"anthropic",512);
        assertThrows(Exception.class,()->provider.request(direct,messages(),tools()));
        assertThrows(Exception.class,()->provider.endpoint(direct));
        ModelConfig missing=new ModelConfig("","",MODEL,false,4,10,PROTOCOL,512);
        assertThrows(Exception.class,()->provider.request(missing,messages(),tools()));
    }
    @Test public void defaultCompatibilityDoesNotOpenUnsupportedWireOrCacheVariants()throws Exception {
        ProviderCompatProfile nativeProfile=ProviderCompatProfile.reviewed(new JSONObject(),PROTOCOL,MODEL,null);
        assertEquals(ProviderCompatProfile.TokenField.NATIVE,nativeProfile.tokenField);
        assertEquals(ProviderCompatProfile.ToolShape.NATIVE,nativeProfile.toolWireShape);
        assertEquals(ProviderCompatProfile.CacheMode.OFF,nativeProfile.cacheMode);
        for(JSONObject option:new JSONObject[]{new JSONObject().put("tokenField","MAX_TOKENS"),new JSONObject().put("streamUsage","OMIT"),
                new JSONObject().put("toolWireShape","ANTHROPIC_INPUT_SCHEMA"),new JSONObject().put("cacheMode","EPHEMERAL")})
            assertThrows(IllegalArgumentException.class,()->ProviderCompatProfile.reviewed(option,PROTOCOL,MODEL,null));
    }
    private static String header(Map<String,String> headers,String name){
        for(Map.Entry<String,String> entry:headers.entrySet())if(entry.getKey().equalsIgnoreCase(name))return entry.getValue();return null;
    }
}
