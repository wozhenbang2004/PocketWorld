package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ModelImageCapabilityTest {
    private SettingsStore settings(){return new SettingsStore(RuntimeEnvironment.getApplication());}
    private JSONObject config()throws Exception{return new JSONObject().put("baseUrl","https://model.invalid/v1").put("provider","openai_chat").put("model","fixture-model").put("sendScreenshots",true).put("supportsImageInput",true);}
    @Test public void explicitNativeDeclarationIsPersistedWithoutClaimingLiveVisionVerification()throws Exception{
        SettingsStore store=settings();store.save(config());ModelConfig saved=store.get();assertTrue(saved.supportsImageInput);assertEquals("owner_configured",saved.publicJson().getString("imageInputCapabilitySource"));assertFalse(saved.publicJson().has("visionVerified"));assertTrue(store.merged(new JSONObject().put("maxSteps",5)).supportsImageInput);
    }
    @Test public void changedProviderUrlOrModelDoesNotInheritOldDeclaration()throws Exception{
        SettingsStore store=settings();store.save(config());for(JSONObject changed:new JSONObject[]{new JSONObject().put("baseUrl","https://model.invalid/other"),new JSONObject().put("provider","openai_responses"),new JSONObject().put("model","other-model")})assertFalse(store.merged(changed).supportsImageInput);
        assertTrue(store.merged(new JSONObject().put("model","other-model").put("supportsImageInput",true)).supportsImageInput);
    }
    @Test public void metadataCannotCoerceAStringIntoOwnerImageCapability()throws Exception{
        SettingsStore store=settings();assertThrows(IllegalArgumentException.class,()->store.merged(config().put("supportsImageInput","true")));assertThrows(IllegalArgumentException.class,()->ModelConfig.fromJson(config().put("supportsImageInput","true")));
    }
    @Test public void accountTransportNeverInheritsImageDeclarationAndRetainedApiChoiceRemainsSeparate()throws Exception{
        SettingsStore store=settings();store.save(config());JSONObject ref=new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","fixture-account").put("accountVersion",1L).put("modelId","fixture-account-model");ModelConfig account=store.selectAccountModel(ref);
        assertFalse(account.supportsImageInput);assertFalse(account.withOwnerImageInput(true).supportsImageInput);assertEquals("account_text_only",account.publicJson().getString("imageInputCapabilitySource"));assertTrue(store.useApiKeyModel().supportsImageInput);
    }
    @Test public void immutableRunCopiesPreserveOnlyTheSameDeclaredRouteModel()throws Exception{
        ModelConfig config=ModelConfig.fromJson(config());assertTrue(config.withStepCeiling(80).supportsImageInput);assertTrue(config.withCleartextConsent("").supportsImageInput);assertTrue(config.withModel("fixture-model").supportsImageInput);assertFalse(config.withModel("other-model").supportsImageInput);assertFalse(new ModelConfig("https://model.invalid","","image-sounding-name",true,2,10).supportsImageInput);
    }
}
