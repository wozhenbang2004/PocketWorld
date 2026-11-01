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
public final class SettingsProviderTest {
    @Test public void oldSettingsMigrateWithoutChangingTheirChatProtocolOrUrl()throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("private_settings",0).edit().putString("baseUrl","https://model.example/v1").putString("model","old-model").putInt("maxSteps",12).commit();SettingsStore settings=new SettingsStore(context);ModelConfig config=settings.get();assertEquals("openai_chat",config.provider);assertEquals(4096,config.maxOutputTokens);assertEquals("https://model.example/v1",config.baseUrl);assertEquals(12,config.maxSteps);
        ModelConfig saved=settings.save(new JSONObject().put("provider","openai_responses").put("maxOutputTokens",2048));assertEquals("openai_responses",saved.provider);assertEquals(2048,settings.get().maxOutputTokens);assertEquals("old-model",settings.get().model);
    }
    @Test public void unknownProviderCannotOverwriteThePreviousWorkingSettings()throws Exception {
        Context context=RuntimeEnvironment.getApplication();SettingsStore settings=new SettingsStore(context);settings.save(new JSONObject().put("baseUrl","https://model.example/v1").put("model","working").put("provider","anthropic").put("maxOutputTokens",1024));
        try{settings.save(new JSONObject().put("provider","unknown-protocol"));fail("Unknown provider was persisted");}catch(IllegalArgumentException expected){}
        assertEquals("anthropic",settings.get().provider);assertEquals(1024,settings.get().maxOutputTokens);assertFalse(settings.get().publicJson().has("apiKey"));
    }
}
