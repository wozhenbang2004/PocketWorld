package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.providers.ProviderCompatProfile;
import dev.pocketengine.runtime.providers.ProviderCompatibility;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Execute the actual pure final-wire projector; fixtures are input bytes, not a replacement Gateway. */
public final class ProviderWireProjectionTest {
    private ModelConfig config(String protocol,JSONObject options)throws Exception{return new ModelConfig("https://fixture.invalid/v1","fixture-secret","fixture-model",false,4,10,protocol,512).withProviderCompatibility(ProviderCompatProfile.reviewed(options,protocol,"fixture-model",null));}
    private JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path")).put("additionalProperties",false))));}
    @Test public void projectionPreservesOriginalHistoryAndConstraintsAndIsDeterministic()throws Exception {
        ModelConfig c=config("openai_chat",new JSONObject().put("tokenField","MAX_COMPLETION_TOKENS").put("streamUsage","OMIT").put("toolWireShape","ANTHROPIC_INPUT_SCHEMA").put("schemaSubset","CORE_JSON"));
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","assistant").put("content","unchanged").put("tool_calls",new JSONArray().put(new JSONObject().put("id","original-call-id"))));JSONArray tools=tools();JSONObject ordinary=new JSONObject().put("messages",messages).put("tools",tools).put("max_tokens",512).put("stream",true).put("stream_options",new JSONObject().put("include_usage",true));String before=ordinary.toString(),history=messages.toString();
        JSONObject projected=ProviderCompatibility.project(c,messages,tools,ordinary);assertEquals(before,ordinary.toString());assertEquals(history,messages.toString());assertEquals("original-call-id",projected.getJSONArray("messages").getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id"));assertEquals(512,projected.getInt("max_completion_tokens"));assertFalse(projected.has("max_tokens"));assertFalse(projected.has("stream_options"));assertEquals("path",projected.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema").getJSONArray("required").getString(0));assertEquals(projected.toString(),ProviderCompatibility.project(c,messages,tools,ordinary).toString());
    }
    @Test public void unsupportedOrStrictSchemaCannotBeSilentlyWeakened()throws Exception {
        ModelConfig c=config("openai_chat",new JSONObject().put("schemaSubset","CORE_JSON").put("toolWireShape","ANTHROPIC_INPUT_SCHEMA"));JSONArray tools=tools();JSONObject ordinary=new JSONObject().put("tools",tools);tools.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").put("oneOf",new JSONArray());
        assertEquals("compat_schema_unsupported",assertThrows(ProviderCompatibility.Rejected.class,()->ProviderCompatibility.project(c,new JSONArray(),tools,ordinary)).code);assertTrue(tools.toString().contains("oneOf"));
        tools.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").remove("oneOf");tools.getJSONObject(0).getJSONObject("function").put("strict",true);assertEquals("compat_tool_strict_unsupported",assertThrows(ProviderCompatibility.Rejected.class,()->ProviderCompatibility.project(c,new JSONArray(),tools,ordinary)).code);
    }
    @Test public void cachePreparationIsNotDeliveryOrUsageAndChangedSourceChangesPrefixHash()throws Exception {
        JSONObject options=new JSONObject().put("cacheMode","EPHEMERAL").put("cacheSupport","SUPPORTED");ModelConfig c=config("anthropic",options);
        JSONObject ordinary=new JSONObject().put("system",new JSONArray().put(new JSONObject().put("type","text").put("text","source revision 1"))).put("tools",new JSONArray().put(new JSONObject().put("name","inspect").put("input_schema",new JSONObject().put("type","object")))).put("messages",new JSONArray().put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","thinking").put("signature","exact-opaque-signature")))));
        JSONObject projected=ProviderCompatibility.project(c,new JSONArray(),tools(),ordinary),diagnostic=ProviderCompatibility.diagnostics(c,projected);assertTrue(diagnostic.getBoolean("hintPrepared"));assertFalse(diagnostic.getBoolean("dispatchAttempted"));assertEquals("not_dispatched",diagnostic.getString("hintDelivery"));assertFalse(diagnostic.has("hintSent"));assertFalse(diagnostic.getBoolean("cacheHitKnown"));assertEquals("exact-opaque-signature",projected.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("signature"));assertFalse(ordinary.toString().contains("cache_control"));
        ordinary.getJSONArray("system").getJSONObject(0).put("text","source revision 2");assertNotEquals(diagnostic.getString("prefixHash"),ProviderCompatibility.diagnostics(c,ProviderCompatibility.project(c,new JSONArray(),tools(),ordinary)).getString("prefixHash"));
        ModelConfig unknown=config("anthropic",new JSONObject().put("cacheMode","EPHEMERAL"));assertFalse(ProviderCompatibility.project(unknown,new JSONArray(),tools(),ordinary).toString().contains("cache_control"));
    }
    @Test public void finalBytesAndToolCountAreCheckedWithoutModifyingInput()throws Exception {
        JSONArray tools=tools();tools.put(new JSONObject(tools.getJSONObject(0).toString()));JSONObject ordinary=new JSONObject().put("tools",tools);
        assertEquals("compat_tool_limit",assertThrows(ProviderCompatibility.Rejected.class,()->ProviderCompatibility.project(config("openai_chat",new JSONObject().put("maxTools",1)),new JSONArray(),tools,ordinary)).code);
        assertEquals("compat_request_byte_limit",assertThrows(ProviderCompatibility.Rejected.class,()->ProviderCompatibility.project(config("openai_chat",new JSONObject().put("maxRequestBytes",256)),new JSONArray(),new JSONArray(),new JSONObject().put("messages","x".repeat(512)))).code);
    }
}
