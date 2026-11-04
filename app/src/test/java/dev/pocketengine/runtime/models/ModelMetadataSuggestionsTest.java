package dev.pocketengine.runtime.models;

import static org.junit.Assert.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ModelMetadataSuggestionsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Path file(){return temp.getRoot().toPath().resolve("advice.json");}
    private static ModelBinding binding(long revision){return new ModelBinding("connection","provider",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENAI,URI.create("https://provider.invalid/v1/models"),"credential",revision,"installation",1,false);}
    private static String document(){return "{\"example\":{\"api\":\"https://provider.invalid/v1\",\"models\":{\"model-a\":{\"id\":\"model-a\",\"name\":\"Model A\",\"tool_call\":true,\"reasoning\":false,\"limit\":{\"context\":12000,\"output\":1024},\"cost\":{\"input\":0.25,\"output\":1.5},\"modalities\":{\"input\":[\"text\",\"image\"]}}}}}";}
    @Test public void exactProviderModelReferenceHasSourceTimeAndContentVersionButNoCapabilityAuthority()throws Exception{
        JSONObject advice=ModelMetadataSuggestions.parse(binding(1),"model-a","example",document(),1000);assertTrue(advice.getBoolean("adviceOnly"));assertFalse(advice.getBoolean("capabilityMetadataChanged"));assertEquals(1000,advice.getLong("observedAtMillis"));assertTrue(advice.getString("sourceVersion").matches("sha256:[a-f0-9]{64}"));
        assertEquals(12000,advice.getJSONObject("hints").getInt("limit.context"));assertEquals("0.25",advice.getJSONObject("hints").getString("cost.input"));assertTrue(advice.getJSONObject("hints").getBoolean("image_input"));assertEquals("same_origin_only_not_model_verification",advice.getString("originMatch"));
    }
    @Test public void namesAliasesAndAnotherProviderCannotSubstituteForExactIds(){assertThrows(IllegalArgumentException.class,()->ModelMetadataSuggestions.parse(binding(1),"Model A","example",document(),1000));assertThrows(IllegalArgumentException.class,()->ModelMetadataSuggestions.parse(binding(1),"model-a","another",document(),1000));}
    @Test public void missingFieldsAndAttachmentFlagDoNotInventToolsImagesOrPrices()throws Exception{
        String body="{\"example\":{\"models\":{\"model-a\":{\"attachment\":true,\"tool_call\":\"true\",\"limit\":{\"context\":\"12000\"},\"cost\":{\"input\":-1}}}}}";
        JSONObject advice=ModelMetadataSuggestions.parse(binding(1),"model-a","example",body,1000);assertEquals(0,advice.getJSONObject("hints").length());assertEquals("provider_origin_unverified",advice.getString("originMatch"));
    }
    @Test public void offlineCacheIsExactBindingAndStaleDoesNotBecomeFreshOnRead()throws Exception{
        var cache=new ModelMetadataSuggestions(file());cache.save(binding(1),"model-a",ModelMetadataSuggestions.parse(binding(1),"model-a","example",document(),1000));
        var reopened=new ModelMetadataSuggestions(file());assertTrue(reopened.snapshot(binding(1),"model-a",1001).getBoolean("fresh"));assertFalse(reopened.snapshot(binding(1),"model-a",1001+ModelMetadataSuggestions.MAX_AGE_MILLIS).getBoolean("fresh"));assertFalse(reopened.snapshot(binding(2),"model-a",1001).getBoolean("available"));
        reopened.clear(binding(1),"model-a");assertFalse(new ModelMetadataSuggestions(file()).snapshot(binding(1),"model-a",1002).getBoolean("available"));
    }
    @Test public void credentialBearingOrOtherOriginMetadataNeverSuppliesARoute()throws Exception{
        JSONObject different=ModelMetadataSuggestions.parse(binding(1),"model-a","example",document().replace("https://provider.invalid/v1","https://other.invalid/v1"),1000);assertEquals("different_origin",different.getString("originMatch"));assertTrue(different.getBoolean("adviceOnly"));
        JSONObject secret=ModelMetadataSuggestions.parse(binding(1),"model-a","example",document().replace("https://provider.invalid/v1","https://user:secret@other.invalid/v1?key=private"),1000);assertEquals("",secret.getString("providerApi"));assertFalse(secret.toString().contains("secret"));
    }
    @Test public void bedrockWithoutCatalogEndpointKeepsMetadataOriginUnverified()throws Exception{
        ModelBinding bedrock=new ModelBinding("connection","bedrock",ModelBinding.Protocol.BEDROCK_ANTHROPIC,ModelBinding.Format.NONE,URI.create(""),"credential",1,"installation",1,false);
        String model="anthropic.claude-sonnet-4-6",api="https://bedrock-runtime.us-east-1.amazonaws.com";
        String body=new JSONObject().put("amazon-bedrock",new JSONObject().put("api",api).put("models",new JSONObject().put(model,new JSONObject().put("id",model).put("tool_call",true)))).toString();
        JSONObject advice=ModelMetadataSuggestions.parse(bedrock,model,"amazon-bedrock",body,1000);
        assertEquals("provider_origin_unverified",advice.getString("originMatch"));assertEquals(api,advice.getString("providerApi"));
        assertTrue(advice.getBoolean("adviceOnly"));assertFalse(advice.getBoolean("capabilityMetadataChanged"));
        assertEquals(bedrock,ModelBinding.fromJson(advice.getJSONObject("binding")));assertEquals("",advice.getJSONObject("binding").getString("modelsEndpoint"));
    }
    @Test public void damagedCacheIsPreservedAndCannotOverwriteAWorkingOwnerSetting()throws Exception{
        Files.writeString(file(),"invalid-cache");var cache=new ModelMetadataSuggestions(file());assertFalse(cache.snapshot(binding(1),"model-a",1000).getBoolean("cacheReadable"));assertThrows(IllegalStateException.class,()->cache.clear(binding(1),"model-a"));assertEquals("invalid-cache",Files.readString(file()));
    }
}
