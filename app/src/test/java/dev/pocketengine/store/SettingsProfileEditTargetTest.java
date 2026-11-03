package dev.pocketengine.store;

import android.app.Application;
import android.content.SharedPreferences;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.nio.file.Files;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original settings/service transactions; no provider request, mounted Compose or device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class SettingsProfileEditTargetTest {
    private static final class Fixture {
        final SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences("profile-edit-"+java.util.UUID.randomUUID(),0);
        final Map<String,String> secrets=new HashMap<>();
        final SettingsStore settings=new SettingsStore(prefs,new SettingsStore.ApiSecrets(){
            public String get(String slot){return secrets.getOrDefault(slot,"");}
            public void put(String slot,String value){secrets.put(slot,value);}
        });
        final ModelConnectionService service;
        final JSONObject a,b;
        Fixture()throws Exception {
            settings.apiConnections();
            a=settings.saveApiConnection("",0,"Retained A",input("a","openai_chat","key-for-a"));
            b=settings.saveApiConnection("",0,"Active B",input("b","anthropic","key-for-b"));
            settings.selectApiConnection(a.getString("id"),a.getLong("revision"));
            settings.selectModelPurpose("MAIN",ModelTargetRef.exact(b.getString("id"),"same-model"));
            service=new ModelConnectionService(settings,Files.createTempDirectory("settings-profile-target-").resolve("catalog.json"),Clock.systemUTC());
        }
        JSONObject row(String id)throws Exception {
            JSONArray rows=settings.apiConnectionsSnapshot().getJSONArray("connections");
            for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);
            throw new AssertionError("original profile missing");
        }
        void save(JSONObject original,JSONObject values)throws Exception {
            service.saveConnection(original.getString("id"),original.getLong("revision"),original.getString("name"),values);
        }
    }
    private static JSONObject input(String host,String provider,String key)throws Exception {
        return new JSONObject().put("baseUrl","https://"+host+".fixture/v1").put("provider",provider)
            .put("model","same-model").put("apiKey",key).put("sendScreenshots",false).put("maxOutputTokens",512);
    }
    private static JSONObject preferences()throws Exception {
        return new JSONObject().put("sendScreenshots",true).put("supportsImageInput",true).put("maxOutputTokens",768);
    }
    @Test public void activeBEditCannotWriteRetainedAOrReplaceMainWithTheSameNamedModel()throws Exception {
        Fixture f=new Fixture();JSONObject aBefore=f.row(f.a.getString("id"));JSONObject purposes=f.settings.modelGroupsSnapshot();
        ModelConfig captured=f.settings.get();assertEquals("key-for-b",captured.apiKey);
        f.save(f.b,preferences());
        assertEquals(aBefore.toString(),f.row(f.a.getString("id")).toString());
        JSONObject b=f.row(f.b.getString("id"));assertEquals(2,b.getLong("revision"));
        JSONObject config=b.getJSONObject("configuration");assertTrue(config.getBoolean("sendScreenshots"));assertTrue(config.getBoolean("supportsImageInput"));assertEquals(768,config.getInt("maxOutputTokens"));
        assertEquals(f.a.getString("id"),f.settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
        assertEquals(purposes.toString(),f.settings.modelGroupsSnapshot().toString());
        assertEquals(f.b.getString("id"),f.settings.get().capabilityBinding.connectionId());assertEquals("key-for-b",f.settings.get().apiKey);
        assertEquals("key-for-a",f.settings.apiConfigForCatalog().apiKey);
        assertFalse(captured.sendScreenshots);assertEquals(512,captured.maxOutputTokens);assertEquals("key-for-b",captured.apiKey);
        ModelConfig live=f.settings.currentForCaptured(captured);assertEquals(captured.capabilityBinding,live.capabilityBinding);assertSame(captured.routePlan,live.routePlan);
    }
    @Test public void laterRetainedSelectionDoesNotRetargetAnAlreadyOpenedEditor()throws Exception {
        Fixture f=new Fixture();JSONObject aDraft=f.row(f.a.getString("id"));
        f.settings.selectApiConnection(f.b.getString("id"),f.b.getLong("revision"));JSONObject bBefore=f.row(f.b.getString("id"));
        f.save(aDraft,preferences());
        assertEquals(bBefore.toString(),f.row(f.b.getString("id")).toString());
        assertEquals(f.b.getString("id"),f.settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
        assertEquals(f.b.getString("id"),f.settings.get().capabilityBinding.connectionId());
        assertTrue(f.row(f.a.getString("id")).getJSONObject("configuration").getBoolean("sendScreenshots"));
    }
    @Test public void untouchedExactEditorFieldsPreserveOriginalConfigurationAndCredential()throws Exception {
        Fixture f=new Fixture();JSONObject original=f.row(f.b.getString("id")),config=original.getJSONObject("configuration");
        JSONObject values=new JSONObject().put("apiKey","").put("clearApiKey",false);
        for(String field:new String[]{"provider","baseUrl","model","cleartextConsent","sendScreenshots","supportsImageInput","maxOutputTokens"})values.put(field,config.get(field));
        f.save(original,values);
        assertEquals(config.toString(),f.row(f.b.getString("id")).getJSONObject("configuration").toString());
        assertEquals("key-for-b",f.settings.get().apiKey);assertEquals("key-for-a",f.settings.apiConfigForCatalog().apiKey);
        assertEquals(f.a.getString("id"),f.settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
    }
    @Test public void staleEditorRevisionIsRejectedBeforeAnyProfileOrCredentialWrite()throws Exception {
        Fixture f=new Fixture();f.save(f.b,new JSONObject().put("model","new-default"));
        String before=f.prefs.getAll().toString();Map<String,String> secretsBefore=new HashMap<>(f.secrets);
        assertThrows(IllegalStateException.class,()->f.save(f.b,input("c","gemini","must-not-be-saved")));
        assertEquals(before,f.prefs.getAll().toString());assertEquals(secretsBefore,f.secrets);
    }
    @Test public void blankKeyOnOriginOrProtocolChangeNeverBorrowsEitherProfilesSecret()throws Exception {
        for(boolean protocol:new boolean[]{false,true}){
            Fixture f=new Fixture();ModelConfig captured=f.settings.get();JSONObject aBefore=f.row(f.a.getString("id"));
            JSONObject values=new JSONObject().put("apiKey","");
            if(protocol)values.put("provider","gemini");else values.put("baseUrl","https://new-origin.fixture/v1");
            f.save(f.b,values);
            assertEquals(aBefore.toString(),f.row(f.a.getString("id")).toString());
            assertEquals("",f.settings.get().apiKey);assertEquals("key-for-a",f.settings.apiConfigForCatalog().apiKey);
            assertFalse(f.row(f.b.getString("id")).getBoolean("hasApiKey"));
            assertThrows(SecurityException.class,()->f.settings.currentForCaptured(captured));
            assertEquals("key-for-b",captured.apiKey);
        }
    }
    @Test public void unchangedOriginBlankKeyPreservesOnlyTheReviewedProfilesCredential()throws Exception {
        Fixture f=new Fixture();f.save(f.b,new JSONObject().put("baseUrl","https://b.fixture/other-path").put("apiKey",""));
        assertEquals("key-for-b",f.settings.get().apiKey);assertEquals("key-for-a",f.settings.apiConfigForCatalog().apiKey);
        assertEquals(f.a.getString("id"),f.settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
    }
    @Test public void savingApiPreferencesWhileAnAccountIsActiveDoesNotSwitchMain()throws Exception {
        Fixture f=new Fixture();f.settings.selectAccountModel(new JSONObject().put("provider","chatgpt_plan").put("accountId","fixture-account").put("accountVersion",7).put("modelId","account-model"));
        ModelConfig captured=f.settings.get();f.save(f.b,preferences());
        assertTrue(f.settings.get().usesAccount());assertTrue(captured.accountModel.matches(f.settings.get().accountModel));
        assertEquals(f.a.getString("id"),f.settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
    }
}
