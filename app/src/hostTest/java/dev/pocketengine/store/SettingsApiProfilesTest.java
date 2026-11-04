package dev.pocketengine.store;

import android.app.Application;
import android.content.SharedPreferences;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ModelCatalogRoutes;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.runtime.models.ReasoningOptions;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real SharedPreferences/settings; explicit in-memory credential seam, not AndroidKeyStore/device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class SettingsApiProfilesTest {
    static final class Secrets implements SettingsStore.ApiSecrets {
        final Map<String,String> values=new HashMap<>();boolean failPut,failGet;
        public String get(String slot){if(failGet)throw new IllegalStateException("fixture credential unavailable");return values.getOrDefault(slot,"");}
        public void put(String slot,String value){if(failPut)throw new IllegalStateException("fixture credential write failed");values.put(slot,value);}
    }
    private SharedPreferences prefs;private Secrets secrets;private SettingsStore store;
    @Before public void open(){prefs=RuntimeEnvironment.getApplication().getSharedPreferences("private_settings",0);prefs.edit().clear().commit();secrets=new Secrets();store=new SettingsStore(prefs,secrets);}
    private static JSONObject input(String host,String model,String key)throws Exception{return new JSONObject().put("provider","openai_chat").put("baseUrl",host).put("model",model).put("apiKey",key);}
    private JSONObject add(String name,String host,String model,String key)throws Exception{return store.saveApiConnection("",0,name,input(host,model,key));}
    private static JSONObject account()throws Exception{return new JSONObject().put("provider","chatgpt_plan").put("accountId","native-account").put("accountVersion",7).put("modelId","account-model");}
    @Test public void savingMultipleConnectionsDoesNotSelectAndRestartKeepsExactChoiceAndVaultReferences()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","model-a","a-secret-key"),b=add("B","https://b.fixture/v1","model-b","b-secret-key");assertEquals("",store.get().model);
        ModelConfig captured=store.selectApiConnection(a.getString("id"),a.getLong("revision"));assertEquals("a-secret-key",captured.apiKey);assertFalse(captured.catalogEnforced);assertNotNull(captured.capabilityBinding);
        ModelConfig second=store.selectApiConnection(b.getString("id"),b.getLong("revision"));assertEquals("b-secret-key",second.apiKey);assertNotEquals(captured.capabilityBinding.connectionId(),second.capabilityBinding.connectionId());
        SettingsStore reopened=new SettingsStore(prefs,secrets);assertEquals(second.capabilityBinding,reopened.get().capabilityBinding);assertEquals("model-b",reopened.get().model);assertEquals("a-secret-key",reopened.currentForCaptured(captured).apiKey);
        String metadata=prefs.getAll().toString()+store.apiConnectionsSnapshot().toString();assertFalse(metadata.contains("a-secret-key"));assertFalse(metadata.contains("b-secret-key"));assertEquals(2,secrets.values.size());
    }
    @Test public void keyRotationHasIndependentCredentialRevisionAndOriginChangeNeverInheritsAnotherSecret()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","model-a","original-secret"),b=add("B","https://b.fixture/v1","model-b","foreign-secret");ModelConfig old=store.selectApiConnection(a.getString("id"),1);
        JSONObject edited=store.saveApiConnection(a.getString("id"),1,"A renamed",new JSONObject().put("apiKey","replacement-secret"));ModelConfig rotated=store.get();assertEquals("replacement-secret",rotated.apiKey);assertTrue(rotated.capabilityBinding.credentialRevision()>old.capabilityBinding.credentialRevision());assertThrows(SecurityException.class,()->store.currentForCaptured(old));
        store.saveApiConnection(a.getString("id"),edited.getLong("revision"),"A moved",new JSONObject().put("baseUrl","https://new-origin.fixture/v1"));assertEquals("",store.get().apiKey);assertTrue(store.get().capabilityBinding.installationRevision()>rotated.capabilityBinding.installationRevision());assertEquals("foreign-secret",secrets.values.values().stream().filter("foreign-secret"::equals).findFirst().orElseThrow());
        assertThrows(IllegalStateException.class,()->store.saveApiConnection(b.getString("id"),0,"stale",new JSONObject()));
    }
    @Test public void disableAndReenableCannotReviveOldBindingOrFallBackToAnotherProfile()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","model-a","a-secret"),b=add("B","https://b.fixture/v1","model-b","b-secret");ModelConfig old=store.selectApiConnection(a.getString("id"),1);
        JSONObject disabled=store.setApiConnectionEnabled(a.getString("id"),1,false);assertEquals(a.getString("id"),store.apiConnectionsSnapshot().getString("selectedConnectionId"));assertEquals("model-a",store.get().model);assertEquals("",store.get().apiKey);assertThrows(SecurityException.class,()->store.currentForCaptured(old));assertThrows(IllegalStateException.class,()->store.useApiKeyModel());
        JSONObject enabled=store.setApiConnectionEnabled(a.getString("id"),disabled.getLong("revision"),true);assertThrows(SecurityException.class,()->store.currentForCaptured(old));assertEquals("a-secret",store.selectApiConnection(a.getString("id"),enabled.getLong("revision")).apiKey);assertEquals(1,b.getLong("revision"));
    }
    @Test public void accountSelectionAndRefreshDoNotDecryptOrOverwriteRetainedApiProfile()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","model-a","a-secret");store.selectApiConnection(a.getString("id"),1);ModelConfig account=store.selectAccountModel(account());secrets.failGet=true;
        assertEquals(account.accountModel.accountId,store.get().accountModel.accountId);assertTrue(store.advanceAccountModel(account.accountModel,9));assertEquals(9,store.get().accountModel.accountVersion);assertEquals(a.getString("id"),store.apiConnectionsSnapshot().getString("selectedConnectionId"));
        secrets.failGet=false;store.saveApiConnection(a.getString("id"),1,"A edited",new JSONObject().put("model","model-a2"));assertTrue(store.get().usesAccount());assertEquals(9,store.get().accountModel.accountVersion);assertEquals("model-a2",store.useApiKeyModel().model);
    }
    @Test public void legacyMigrationIsExplicitAtomicAndRetainsStrictCatalogContractAndAccountRoute()throws Exception{
        store.save(input("https://legacy.fixture/v1","legacy-model",""));ModelConfig legacy=store.configureApiCatalog(null,ModelBinding.Format.OPENAI,ModelCatalogRoutes.modelsEndpoint(store.get()));store.selectAccountModel(account());assertFalse(store.apiConnectionsSnapshot().getBoolean("migrated"));assertTrue(prefs.contains("baseUrl"));
        JSONObject migrated=store.apiConnections();assertEquals(1,migrated.getJSONArray("connections").length());assertTrue(store.get().usesAccount());assertFalse(prefs.contains("baseUrl"));assertFalse(prefs.contains("encryptedKey"));ModelConfig retained=store.useApiKeyModel();assertTrue(retained.catalogEnforced);assertEquals(legacy.capabilityBinding,retained.capabilityBinding);
        JSONObject row=migrated.getJSONArray("connections").getJSONObject(0);assertThrows(SecurityException.class,()->store.saveApiConnection(row.getString("id"),1,"cannot downgrade",new JSONObject().put("catalogEnforced",false)));assertTrue(store.get().catalogEnforced);
        store.saveApiConnection(row.getString("id"),1,"edited",new JSONObject().put("model","another-model"));assertTrue(store.get().catalogEnforced);
    }
    @Test public void genericNativeIdentityJsonAndCorruptProfilesCannotResetOrDowngradeAuthority()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","model-a","");store.selectApiConnection(a.getString("id"),1);String before=prefs.getString("apiConnections","");
        for(String field:new String[]{"catalogEnforced","capabilityBinding","secretSlot","connectionId","credentialId","installationId"})assertThrows(SecurityException.class,()->store.saveApiConnection(a.getString("id"),1,"A",new JSONObject().put(field,false)));
        assertEquals(before,prefs.getString("apiConnections",""));prefs.edit().putString("apiConnections","{broken").commit();assertThrows(Exception.class,()->store.apiConnections());assertEquals("{broken",prefs.getString("apiConnections",""));
    }
    @Test public void vaultFailureBeforeMetadataCommitKeepsOriginalSelectionAndNoSecretIsAdopted()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","model-a","original-secret");ModelConfig old=store.selectApiConnection(a.getString("id"),1);String before=prefs.getString("apiConnections","");secrets.failPut=true;
        assertThrows(IllegalStateException.class,()->store.saveApiConnection(a.getString("id"),1,"A",new JSONObject().put("apiKey","failed-secret")));assertEquals(before,prefs.getString("apiConnections",""));assertEquals(old.capabilityBinding,store.get().capabilityBinding);assertEquals("original-secret",store.get().apiKey);
    }
    @Test public void failedMigrationCommitPreservesLegacyBytesAndFencesUncertainInMemorySettings()throws Exception{
        store.save(input("https://legacy.fixture/v1","legacy-model",""));String before=prefs.getAll().toString();
        SharedPreferences denied=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            if(method.getName().equals("edit")){SharedPreferences.Editor edit=prefs.edit();return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(p,m,a)->{if(m.getName().equals("commit"))return false;Object result=m.invoke(edit,a);return result==edit?p:result;});}return method.invoke(prefs,args);
        });
        SettingsStore uncertain=new SettingsStore(denied,secrets);assertThrows(SettingsStore.PersistenceUncertain.class,uncertain::apiConnections);assertEquals(before,prefs.getAll().toString());assertThrows(SettingsStore.PersistenceUncertain.class,uncertain::get);assertFalse(prefs.contains("apiConnections"));assertEquals("legacy-model",store.get().model);
    }
    @Test public void profileLimitAndExplicitSelectionRevisionRemainBoundedAndMonotonic()throws Exception{
        store.apiConnections();JSONObject a=null;for(int i=0;i<32;i++){JSONObject row=add("C"+i,"https://a.fixture/v1","model-"+i,"");if(i==0)a=row;}
        assertThrows(IllegalStateException.class,()->add("overflow","https://a.fixture/v1","overflow",""));assertEquals(32,store.apiConnectionsSnapshot().getJSONArray("connections").length());long before=store.apiSelectionRevision();store.selectApiConnection(a.getString("id"),1);assertTrue(store.apiSelectionRevision()>before);
    }
    @Test public void rotatingOneCredentialReusesOnlyInactiveSlotsAndUnreadableCurrentKeyCanBeExplicitlyReplaced()throws Exception{
        store.apiConnections();JSONObject row=add("A","https://a.fixture/v1","model-a","key-initial");store.selectApiConnection(row.getString("id"),1);
        for(int i=1;i<=4;i++){row=store.saveApiConnection(row.getString("id"),row.getLong("revision"),"A",new JSONObject().put("apiKey","key-"+i));assertEquals("key-"+i,store.get().apiKey);}
        assertEquals(2,secrets.values.size());secrets.failGet=true;JSONObject current=row;
        assertThrows(IllegalStateException.class,()->store.saveApiConnection(current.getString("id"),current.getLong("revision"),"A",new JSONObject()));
        store.saveApiConnection(current.getString("id"),current.getLong("revision"),"A",new JSONObject().put("apiKey","new-owner-replacement"));secrets.failGet=false;assertEquals("new-owner-replacement",store.get().apiKey);assertEquals(2,secrets.values.size());
    }
    @Test public void explicitApiAndAccountChoicesReplaceOnlyTheOldMainPurpose()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","model-a","a-secret"),b=add("B","https://b.fixture/v1","model-b","b-secret");
        store.selectApiConnection(a.getString("id"),1);
        ModelTargetRef target=ModelTargetRef.exact(b.getString("id"),"model-b");
        store.selectModelPurpose("MAIN",target);store.selectModelPurpose("SUMMARY",target);assertEquals("model-b",store.get().model);
        store.selectAccountModel(account());assertTrue(store.get().usesAccount());assertNull(store.modelPurposeTarget("MAIN"));assertEquals(target,store.modelPurposeTarget("SUMMARY"));
        store.selectModelPurpose("MAIN",target);store.useApiKeyModel();assertEquals("model-a",store.get().model);assertNull(store.modelPurposeTarget("MAIN"));
        store.selectModelPurpose("MAIN",target);store.selectApiConnection(a.getString("id"),1);assertEquals("model-a",store.get().model);assertNull(store.modelPurposeTarget("MAIN"));
        store.selectModelPurpose("MAIN",target);store.save(new JSONObject().put("model","api-saved"));assertEquals("api-saved",store.get().model);assertNull(store.modelPurposeTarget("MAIN"));
        ModelConfig catalog=store.configureApiCatalog(null,ModelBinding.Format.OPENAI,ModelCatalogRoutes.modelsEndpoint(store.get()));
        store.selectModelPurpose("MAIN",target);store.selectCatalogModel(catalog.capabilityBinding,"catalog-selected",4096,ReasoningOptions.DEFAULT,0);
        assertEquals("catalog-selected",store.get().model);assertNull(store.modelPurposeTarget("MAIN"));assertEquals(target,store.modelPurposeTarget("SUMMARY"));
    }
    @Test public void addingAndSelectingNeverEditsOrInheritsTheOldProfile()throws Exception{
        store.apiConnections();JSONObject old=add("Original","https://a.fixture/v1","old-model","original-secret");store.selectApiConnection(old.getString("id"),1);
        JSONObject added=store.addAndSelectApiConnection("New",input("https://a.fixture/v1","new-model",""));
        assertNotEquals(old.getString("id"),added.getString("id"));assertEquals("new-model",store.get().model);assertEquals("",store.get().apiKey);
        JSONObject original=store.apiConnectionsSnapshot().getJSONArray("connections").getJSONObject(0);
        assertEquals(old.getString("id"),original.getString("id"));assertEquals(1,original.getLong("revision"));assertEquals("old-model",original.getString("model"));
        store.selectApiConnection(old.getString("id"),1);assertEquals("original-secret",store.get().apiKey);
    }
    @Test public void unsavedAddTestCannotInheritTheCurrentConnectionsSecret()throws Exception{
        store.apiConnections();JSONObject old=add("Original","https://a.fixture/v1","old-model","original-secret");store.selectApiConnection(old.getString("id"),1);
        String before=prefs.getAll().toString();JSONObject input=input("https://a.fixture/v1","test-model","");
        ModelConfig added=store.previewApiConnection("",0,input);assertEquals("",added.apiKey);assertNull(added.capabilityBinding);
        ModelConfig edited=store.previewApiConnection(old.getString("id"),1,input);assertEquals("original-secret",edited.apiKey);assertNotNull(edited.capabilityBinding);
        assertThrows(IllegalStateException.class,()->store.previewApiConnection(old.getString("id"),0,input));
        assertThrows(IllegalStateException.class,()->store.previewApiConnection("",1,input));assertEquals(before,prefs.getAll().toString());
    }
    @Test public void anExistingRunKeepsItsOptionsWhenTheNextTaskModelChanges()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","original-model","original-secret");store.selectApiConnection(a.getString("id"),1);
        ModelConfig captured=store.get().withStepCeiling(80);
        store.save(new JSONObject().put("model","next-model").put("maxOutputTokens",8192).put("requestTimeoutSeconds",110).put("sendScreenshots",false));
        assertEquals("next-model",store.get().model);ModelConfig current=store.currentForCaptured(captured);
        assertEquals(captured.model,current.model);assertEquals(captured.maxOutputTokens,current.maxOutputTokens);assertEquals(captured.requestTimeoutSeconds,current.requestTimeoutSeconds);
        assertEquals(captured.sendScreenshots,current.sendScreenshots);assertEquals(80,current.maxSteps);assertEquals(captured.capabilityBinding,current.capabilityBinding);
        dev.pocketengine.runtime.models.InferenceRouteContinuity.requireCurrent(captured,current,new dev.pocketengine.core.RunControl());
    }
    @Test public void pendingWriteIsSharedByActivitiesAndCannotCaptureThePreviousChoice()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","old-model","original-secret");store.selectApiConnection(a.getString("id"),1);ModelConfig original=store.get();
        SettingsStore other=new SettingsStore(prefs,secrets);assertSame(store.modelSelection(),other.modelSelection());
        var write=store.modelSelection().beginMutation();assertThrows(IllegalStateException.class,other::get);
        assertEquals("old-model",other.currentForCaptured(original).model);
        write.run(()->{store.save(new JSONObject().put("model","new-model"));return null;});
        assertEquals("new-model",other.get().model);assertEquals("old-model",other.currentForCaptured(original).model);
    }
    @Test public void originalAccountConfigDoesNotFollowALaterDefaultRoute()throws Exception{
        store.apiConnections();JSONObject a=add("A","https://a.fixture/v1","api-model","api-secret");store.selectApiConnection(a.getString("id"),1);
        ModelConfig captured=store.selectAccountModel(account());store.useApiKeyModel();
        assertFalse(store.get().usesAccount());assertEquals(captured.accountModel,store.currentForCaptured(captured).accountModel);
        // This is configuration identity only; AccountGateway still checks live account revocation and renewal.
    }
}
