package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import java.net.URI;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ApiConnectionProfileTest {
    private static ModelBinding binding(){return new ModelBinding("api-native","openai_chat",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENAI,URI.create("https://a.fixture/v1/models"),"key-native",3,"install-native",5,false);}
    private static ModelConfig manual(){return new ModelConfig("https://a.fixture/v1","","actual-model",false,12,60).withOwnerImageInput(true).withApiConnection(binding());}
    @Test public void identityOnlyProfileRoundTripDoesNotInventCatalogOrCredential()throws Exception{
        ApiConnectionProfile original=new ApiConnectionProfile("原 API",2,true,"model.api."+"a".repeat(64)+".1",manual());ApiConnectionProfile restored=ApiConnectionProfile.fromJson(original.toJson());
        assertEquals(original.toJson().toString(),restored.toJson().toString());assertFalse(restored.configuration.catalogEnforced);assertTrue(restored.configuration.supportsImageInput);assertEquals("",restored.configuration.apiKey);assertTrue(restored.publicJson().getBoolean("hasApiKey"));assertFalse(restored.publicJson().has("secretSlot"));
    }
    @Test public void strictProfileAndEveryNativeCopyKeepTheStrictBitAndChosenOptions()throws Exception{
        ReasoningOptions options=new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0);ModelConfig config=manual().withNativeCatalog(binding(),options,2000);
        for(ModelConfig copy:new ModelConfig[]{config.withModel("actual-model"),config.withModel("next-model"),config.withOwnerImageInput(false),config.withStepCeiling(300),config.withRequestOutputLimit(512),config.withCleartextConsent("")}){assertTrue(copy.catalogEnforced);assertEquals(binding(),copy.capabilityBinding);}
        ApiConnectionProfile restored=ApiConnectionProfile.fromJson(new ApiConnectionProfile("strict",1,true,"",config).toJson());assertTrue(restored.configuration.catalogEnforced);assertEquals(options,restored.configuration.reasoning);assertEquals(2000,restored.configuration.imageTokenReserve);
    }
    @Test public void identityOnlyNativeCopiesStayIdentityOnlyAndJsonCannotInstallEitherPolicy()throws Exception{
        ModelConfig config=manual();for(ModelConfig copy:new ModelConfig[]{config.withModel("next-model"),config.withStepCeiling(300),config.withOwnerImageInput(false),config.withRequestOutputLimit(512),config.withCleartextConsent("")}){assertFalse(copy.catalogEnforced);assertEquals(binding(),copy.capabilityBinding);}
        assertThrows(IllegalArgumentException.class,()->ModelConfig.fromJson(new JSONObject().put("catalogEnforced",false)));assertThrows(IllegalArgumentException.class,()->ModelConfig.fromJson(new JSONObject().put("capabilityBinding",binding().toJson())));
    }
    @Test public void secretBytesAccountRouteAndNonIntegerRevisionCannotEnterProfileStorage()throws Exception{
        assertThrows(IllegalArgumentException.class,()->new ApiConnectionProfile("bad",1,true,"",new ModelConfig("https://a.fixture/v1","not-storable-secret","actual-model",false,1,60).withApiConnection(binding())));
        JSONObject invalid=new ApiConnectionProfile("valid",1,true,"",manual()).toJson().put("revision",1.5);assertThrows(IllegalArgumentException.class,()->ApiConnectionProfile.fromJson(invalid));
        JSONObject downgrade=new ApiConnectionProfile("valid",1,true,"",manual()).toJson();downgrade.getJSONObject("config").put("catalogEnforced","false");assertThrows(IllegalArgumentException.class,()->ApiConnectionProfile.fromJson(downgrade));
    }
}
