package dev.pocketengine.runtime.models;

import dev.pocketengine.core.AwsSessionCredentials;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.providers.ProviderCompatProfile;
import java.net.URI;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockConnectionConfigTest {
    private static final String MODEL="anthropic.claude-sonnet-4-6";
    private static final long EXPIRY=2051319845000L;
    private static ModelBinding binding(){return new ModelBinding("api-aws","bedrock_anthropic",ModelBinding.Protocol.BEDROCK_ANTHROPIC,ModelBinding.Format.NONE,URI.create(""),"key-aws",3,"install-aws",5,false);}
    private static ModelConfig config(){return new ModelConfig("","",MODEL,false,20,60,ModelConfig.BEDROCK_PROVIDER,1024).withBedrockSession("us-east-1",new AwsSessionCredentials("ASIAABCDEFGHIJKLMNOP","FixtureSecretAccessKey0123456789+/=","FixtureSessionToken0123456789+/=",EXPIRY)).withApiConnection(binding());}
    @Test public void copiesKeepExactTypedSessionAndOriginalConnection(){
        ModelConfig original=config();ModelRoutePlan plan=ModelRoutePlan.exact(original,"MAIN","APP_OWNER");
        for(ModelConfig copy:new ModelConfig[]{original.withStepCeiling(40),original.withRequestOutputLimit(512),original.withModel(MODEL),original.withModel("anthropic.claude-opus-4-6-v1"),original.withCleartextConsent(""),original.withOwnerImageInput(true),original.withLocalObservation(true,false),original.withRoutePlan(plan),original.withPurposePlans(java.util.Map.of("MAIN",plan))}){
            assertSame(original.awsSession,copy.awsSession);assertEquals("us-east-1",copy.bedrockRegion);assertEquals(binding(),copy.capabilityBinding);assertFalse(copy.catalogEnforced);
        }
    }
    @Test public void accountConversionClearsTemporaryCredentialMaterial()throws Exception{
        ModelConfig account=config().withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId","account-a").put("accountVersion",1).put("modelId","account-model")));
        assertTrue(account.usesAccount());assertFalse(account.usesBedrock());assertNull(account.awsSession);assertEquals("",account.bedrockRegion);assertFalse(account.publicJson().has("credentialExpiresAt"));
    }
    @Test public void genericJsonCannotMintSessionTargetOrNativeBinding()throws Exception{
        for(JSONObject invalid:new JSONObject[]{new JSONObject().put("provider","bedrock_anthropic"),new JSONObject().put("awsSession",new JSONObject()),new JSONObject().put("bedrockRegion","us-east-1"),new JSONObject().put("capabilityBinding",binding().toJson())})assertThrows(IllegalArgumentException.class,()->ModelConfig.fromJson(invalid));
        assertThrows(IllegalArgumentException.class,()->new ModelConfig("","api-key",MODEL,false,20,60,ModelConfig.BEDROCK_PROVIDER,1024));
    }
    @Test public void noCatalogFormatIsExclusiveAndCannotBecomeFalseModelsEndpoint()throws Exception{
        assertEquals(binding(),ModelBinding.fromJson(binding().toJson()));
        assertThrows(IllegalArgumentException.class,()->config().withNativeCatalog(binding(),ReasoningOptions.DEFAULT));
        assertThrows(IllegalArgumentException.class,()->new ModelBinding("c","openai_chat",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.NONE,URI.create(""),"k",1,"i",1,false));
        assertThrows(IllegalArgumentException.class,()->new ModelBinding("c","bedrock_anthropic",ModelBinding.Protocol.BEDROCK_ANTHROPIC,ModelBinding.Format.NONE,URI.create(""),"k",1,"i",1,true));
    }
    @Test public void exactRouteAndCurrentProtocolAreMandatory(){
        assertThrows(IllegalArgumentException.class,()->config().withBedrockSession("eu-west-1",config().awsSession));
        assertThrows(IllegalArgumentException.class,()->config().withModel("anthropic.claude-haiku-3-20240307-v1:0"));
        assertThrows(IllegalArgumentException.class,()->new ModelConfig("https://untrusted.invalid","",MODEL,false,20,60,ModelConfig.BEDROCK_PROVIDER,1024).withBedrockSession("us-east-1",config().awsSession));
    }
    @Test public void nativeWireDefaultsAllowedButCompatibilityAndCacheVariantsRejected()throws Exception{
        ModelConfig nativeWire=config().withProviderCompatibility(ProviderCompatProfile.reviewed(new JSONObject(),ModelConfig.BEDROCK_PROVIDER,MODEL,null));assertEquals(ProviderCompatProfile.CacheMode.OFF,nativeWire.compatibility.cacheMode);
        assertThrows(IllegalArgumentException.class,()->config().withProviderCompatibility(ProviderCompatProfile.reviewed(new JSONObject().put("schemaSubset","CORE_JSON"),ModelConfig.BEDROCK_PROVIDER,MODEL,null)));
    }
}
