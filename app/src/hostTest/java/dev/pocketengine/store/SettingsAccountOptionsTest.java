package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ReasoningOptions;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class SettingsAccountOptionsTest {
    private JSONObject reference()throws Exception{return new JSONObject().put("provider","chatgpt_plan").put("accountId","fixture-account").put("accountVersion",7).put("modelId","fixture-model");}
    @Test public void NativeOptionsPersistAcrossRestartAndRenewalWithoutChangingRetainedApiSettings()throws Exception {
        var context=RuntimeEnvironment.getApplication();SettingsStore store=new SettingsStore(context);
        store.save(new JSONObject().put("baseUrl","https://api.fixture/v1").put("model","api-model").put("provider","openai_chat").put("maxOutputTokens",2048));
        ReasoningOptions low=new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"low",0);
        ModelConfig captured=store.selectAccountModel(reference(),low,1400,256);assertEquals(low,captured.reasoning);assertEquals(256,captured.maxOutputTokens);
        SettingsStore restarted=new SettingsStore(context);ModelConfig read=restarted.get();assertEquals(low,read.reasoning);assertEquals(1400,read.imageTokenReserve);assertEquals(256,read.maxOutputTokens);
        assertTrue(restarted.advanceAccountModel(captured.accountModel,9));assertEquals(low,restarted.get().reasoning);assertEquals(1400,restarted.get().imageTokenReserve);
        assertEquals(9,restarted.get().accountModel.accountVersion);assertEquals(7,captured.accountModel.accountVersion);
        ModelConfig api=restarted.useApiKeyModel();assertEquals("api-model",api.model);assertEquals(2048,api.maxOutputTokens);assertEquals(ReasoningOptions.DEFAULT,api.reasoning);assertEquals(0,api.imageTokenReserve);
    }
    @Test public void NewNativeSelectionDoesNotInheritAnotherAccountsReasoningOrImageReserve()throws Exception {
        SettingsStore store=new SettingsStore(RuntimeEnvironment.getApplication());
        store.save(new JSONObject().put("baseUrl","https://api.fixture/v1").put("model","retained-api"));
        store.selectAccountModel(reference(),new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0),1000,512);
        ModelConfig next=store.selectAccountModel(reference().put("accountId","second-account"));
        assertEquals("second-account",next.accountModel.accountId);assertEquals(ReasoningOptions.DEFAULT,next.reasoning);assertEquals(0,next.imageTokenReserve);
        try{store.selectAccountModel(reference(),new ReasoningOptions(ReasoningOptions.Mode.GEMINI_BUDGET,"",1000),0,512);fail();}catch(IllegalArgumentException expected){}
        assertEquals("second-account",store.get().accountModel.accountId);
    }
    @Test public void GenericJsonCannotInstallNativeAccountOptionsOrSilentlyClampInvalidReserves()throws Exception {
        SettingsStore store=new SettingsStore(RuntimeEnvironment.getApplication());
        store.save(new JSONObject().put("baseUrl","https://api.fixture/v1").put("model","retained-api"));store.selectAccountModel(reference());
        try{store.save(new JSONObject().put("reasoning",new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0).toJson()));fail();}catch(IllegalArgumentException expected){}
        for(int invalid:new int[]{-1,0,127,32769})try{store.selectAccountModel(reference(),ReasoningOptions.DEFAULT,0,invalid);fail();}catch(IllegalArgumentException expected){}
        assertTrue(store.get().usesAccount());assertEquals(ReasoningOptions.DEFAULT,store.get().reasoning);assertEquals(4096,store.get().maxOutputTokens);
    }
}
