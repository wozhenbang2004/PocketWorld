package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.providers.ProviderCompatProfile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native immutable configuration boundaries only; HTTP/receipt behavior has separate journey tests. */
public final class ProviderCompatProfileTest {
    private ModelConfig config(){return new ModelConfig("https://fixture.invalid/v1","private-fixture-key","fixture-model",false,4,10,"openai_chat",512);}
    @Test public void nativeProfileRoundTripPreservesRevisionAndUnknownFieldsCannotBeSilentlyIgnored()throws Exception {
        var p=ProviderCompatProfile.reviewed(new JSONObject().put("tokenField","MAX_COMPLETION_TOKENS"),"openai_chat","fixture-model",null);
        assertTrue(p.matches(ProviderCompatProfile.fromJson(p.toJson())));assertThrows(Exception.class,()->ProviderCompatProfile.fromJson(p.toJson().put("revision",1.5)));assertThrows(Exception.class,()->ProviderCompatProfile.reviewed(new JSONObject().put("dropRequired",true),"openai_chat","fixture-model",p));
    }
    @Test public void repeatedDefinitionIsNoChangeAndChangedDefinitionAdvancesTheOriginalId()throws Exception {
        JSONObject options=new JSONObject().put("maxTools",32);var p=ProviderCompatProfile.reviewed(options,"openai_chat","fixture-model",null);assertSame(p,ProviderCompatProfile.reviewed(options,"openai_chat","fixture-model",p));
        var next=ProviderCompatProfile.reviewed(options.put("maxTools",31),"openai_chat","fixture-model",p);assertEquals(p.id,next.id);assertEquals(p.revision+1,next.revision);assertFalse(p.matches(next));
    }
    @Test public void compatibilityCopiesStayOnExactModelAndNeverEnterGenericJson()throws Exception {
        var p=ProviderCompatProfile.reviewed(new JSONObject(),"openai_chat","fixture-model",null);ModelConfig c=config().withProviderCompatibility(p);
        assertSame(p,c.withStepCeiling(20).withRequestOutputLimit(128).withCleartextConsent("").withOwnerImageInput(false).withPurposePlans(java.util.Map.of()).withLocalObservation(true,false).compatibility);
        assertNull(c.withModel("another-model").compatibility);assertThrows(IllegalArgumentException.class,()->ModelConfig.fromJson(c.publicJson()));assertFalse(c.publicJson().toString().contains(c.apiKey));
    }
    @Test public void profileChangeParticipatesInOriginalConfigurationDigest()throws Exception {
        var p=ProviderCompatProfile.reviewed(new JSONObject().put("maxTools",32),"openai_chat","fixture-model",null);var next=ProviderCompatProfile.reviewed(new JSONObject().put("maxTools",31),"openai_chat","fixture-model",p);
        assertNotEquals(ModelRoutePlan.configurationDigest(config()),ModelRoutePlan.configurationDigest(config().withProviderCompatibility(p)));
        assertNotEquals(ModelRoutePlan.configurationDigest(config().withProviderCompatibility(p)),ModelRoutePlan.configurationDigest(config().withProviderCompatibility(next)));
    }
    @Test public void defaultConfigurationKeepsTheC055DigestBytesForFrozenPlansAndRecovery()throws Exception {
        // c055 original 14-item array for config(): no compatibility item is present.
        String c055Bytes="[\"openai_chat\",\"https://fixture.invalid/v1\",\"fixture-model\",512,10,\"DEFAULT\",\"\",0,0,false,false,false,false,false]";
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(c055Bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder expected=new StringBuilder();for(byte b:hash)expected.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        assertEquals(expected.toString(),ModelRoutePlan.configurationDigest(config()));
    }
    @Test public void unsupportedProtocolCombinationsAndLimitsAreRejectedWithoutGuessingCapabilities()throws Exception {
        for(JSONObject options:new JSONObject[]{new JSONObject().put("maxTools",0),new JSONObject().put("maxRequestBytes",25*1024*1024),new JSONObject().put("maxTools","32"),new JSONObject().put("apiMode","anthropic"),new JSONObject().put("cacheMode","EPHEMERAL")})assertThrows(Exception.class,()->ProviderCompatProfile.reviewed(options,"openai_chat","fixture-model",null));
        var p=ProviderCompatProfile.reviewed(new JSONObject().put("cacheMode","EPHEMERAL"),"anthropic","fixture-model",null);assertEquals(ProviderCompatProfile.CacheSupport.UNKNOWN,p.cacheSupport);
    }
}
