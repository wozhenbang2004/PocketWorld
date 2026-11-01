package dev.pocketengine.store;

import android.app.Application;
import android.content.Context;
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
public final class SettingsAccountRouteTest {
    private JSONObject reference()throws Exception{return new JSONObject().put("provider","chatgpt_plan").put("accountId","fixture-account").put("accountVersion",7).put("modelId","fixture-model");}
    @Test public void selectingAccountKeepsApiSettingsAndDoesNotDecryptTheirSavedKey()throws Exception {
        Context context=RuntimeEnvironment.getApplication();SettingsStore settings=new SettingsStore(context);
        settings.save(new JSONObject().put("baseUrl","https://api.fixture/v1").put("model","saved-api-model").put("provider","openai_responses"));
        context.getSharedPreferences("private_settings",0).edit().putString("encryptedKey","intentionally-unreadable-fixture").commit();
        ModelConfig account=settings.selectAccountModel(reference());assertTrue(account.usesAccount());assertEquals("",account.apiKey);assertTrue(settings.get().usesAccount());
        assertEquals("intentionally-unreadable-fixture",context.getSharedPreferences("private_settings",0).getString("encryptedKey",""));
        try{settings.useApiKeyModel();fail();}catch(Exception expected){}assertTrue(settings.get().usesAccount());
        context.getSharedPreferences("private_settings",0).edit().putString("encryptedKey","").commit();
        ModelConfig api=settings.useApiKeyModel();assertFalse(api.usesAccount());assertEquals("saved-api-model",api.model);assertEquals("openai_responses",api.provider);
    }
    @Test public void genericSettingsCannotSelectAccountButExplicitApiSaveCanSwitchBack()throws Exception {
        SettingsStore settings=new SettingsStore(RuntimeEnvironment.getApplication());settings.save(new JSONObject().put("baseUrl","https://api.fixture/v1").put("model","retained"));settings.selectAccountModel(reference());
        try{settings.save(new JSONObject().put("nativeRef",reference()));fail();}catch(IllegalArgumentException expected){}assertTrue(settings.get().usesAccount());
        settings.save(new JSONObject().put("maxSteps",8));assertFalse(settings.get().usesAccount());assertEquals("retained",settings.get().model);
    }
    @Test public void nativeRenewalCasCannotOverwriteAnOwnerAccountOrApiRouteChange()throws Exception {
        SettingsStore settings=new SettingsStore(RuntimeEnvironment.getApplication());settings.save(new JSONObject().put("baseUrl","https://api.fixture/v1").put("model","retained"));
        settings.selectAccountModel(reference());ModelConfig.AccountModelRef original=settings.selectedAccountModel();
        assertTrue(settings.advanceAccountModel(original,9));assertEquals(7,original.accountVersion);assertEquals(9,settings.selectedAccountModel().accountVersion);
        assertFalse(settings.advanceAccountModel(original,11));assertEquals(9,settings.selectedAccountModel().accountVersion);
        ModelConfig.AccountModelRef renewed=settings.selectedAccountModel();settings.selectAccountModel(reference().put("accountId","other-account"));
        assertFalse(settings.advanceAccountModel(renewed,11));assertEquals("other-account",settings.selectedAccountModel().accountId);
        settings.useApiKeyModel();assertFalse(settings.advanceAccountModel(renewed,11));assertNull(settings.selectedAccountModel());
        try{settings.advanceAccountModel(renewed,8);fail();}catch(IllegalArgumentException expected){}
    }

}
