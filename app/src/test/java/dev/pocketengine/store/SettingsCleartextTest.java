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
public final class SettingsCleartextTest {
    @Test public void consentIsPersistedOnlyForTheConfirmedProviderAndOrigin()throws Exception{
        SettingsStore store=new SettingsStore(RuntimeEnvironment.getApplication());String base="http://192.0.2.5:11434/v1";
        JSONObject input=new JSONObject().put("baseUrl",base).put("model","test").put("clearApiKey",true);
        assertThrows(IllegalArgumentException.class,()->store.save(input));
        String consent=ModelConfig.cleartextConsentFor("openai_chat",base);store.save(input.put("cleartextConsent",consent));assertEquals(consent,store.get().cleartextConsent);
        ModelConfig changed=store.merged(new JSONObject().put("baseUrl","http://192.0.2.6:11434/v1"));assertEquals("",changed.cleartextConsent);assertThrows(IllegalArgumentException.class,()->SettingsStore.validate(changed));
        ModelConfig protocol=store.merged(new JSONObject().put("provider","anthropic"));assertEquals("",protocol.cleartextConsent);assertThrows(IllegalArgumentException.class,()->SettingsStore.validate(protocol));
        ModelConfig secure=store.save(new JSONObject().put("baseUrl","https://example.com/v1").put("cleartextConsent",consent));assertEquals("",secure.cleartextConsent);
    }
    @Test public void oldRemoteHttpPreferencesDoNotBecomeImplicitlyApproved()throws Exception{
        RuntimeEnvironment.getApplication().getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://192.0.2.8:8080/v1").putString("model","legacy").commit();
        ModelConfig migrated=new SettingsStore(RuntimeEnvironment.getApplication()).get();assertEquals("",migrated.cleartextConsent);assertThrows(IllegalArgumentException.class,()->SettingsStore.validate(migrated));
    }
}
