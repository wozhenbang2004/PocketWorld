package dev.pocketengine.store;

import android.app.Application;
import android.content.SharedPreferences;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ApiConnectionProfile;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.runtime.models.ReasoningOptions;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AiSourceSettingsTest {
    private SharedPreferences prefs;
    private SettingsStore settings;
    private final Map<String,String> secrets=new HashMap<>();
    private final java.util.List<String> secretSlotsRead=new java.util.ArrayList<>();
    private int secretReads;
    private String unreadableSlot="";
    @Before public void open(){
        prefs=RuntimeEnvironment.getApplication().getSharedPreferences("source-settings-tests",0);prefs.edit().clear().commit();
        settings=new SettingsStore(prefs,new SettingsStore.ApiSecrets(){
            public String get(String slot){secretReads++;secretSlotsRead.add(slot);if(slot.equals(unreadableSlot))throw new IllegalStateException("unreadable original credential fixture");return secrets.getOrDefault(slot,"");}
            public void put(String slot,String value){secrets.put(slot,value);}
        });
    }
    private JSONObject source(String model)throws Exception{return settings.saveApiConnection("",0,"来源",new JSONObject().put("baseUrl","https://api.fixture/v1").put("model",model).put("provider","openai_chat").put("apiKey","fixture-private-key"));}
    private JSONObject group(JSONObject api)throws Exception {
        return group(api.getString("id"),"manual-model");
    }
    private JSONObject group(String connection,String model)throws Exception {
        JSONObject candidate=new JSONObject().put("candidateId","first").put("target",ModelTargetRef.exact(connection,model).toJson()).put("enabled",true)
                .put("allowedPurposes",new JSONArray().put("MAIN").put("SUMMARY")).put("allowedAudiences",new JSONArray().put("APP_OWNER"));
        return settings.saveModelGroup("",0,"顺序模型组",new JSONObject().put("allowedAccounts",new JSONObject().put("mode","EXPLICIT").put("refs",new JSONArray().put(connection)))
                .put("candidates",new JSONArray().put(candidate)).put("attemptPolicy",new JSONObject().put("maxAttempts",1).put("maxRouteSwitches",0)));
    }
    @Test public void emptyModelSourceCanBeArchivedAndRestoredWithoutSecretsOrSelection()throws Exception {
        JSONObject saved=source("");int reads=secretReads;
        JSONObject archived=settings.exportBackupForOwner(new RunControl());
        assertEquals(reads,secretReads);assertFalse(archived.toString().contains("fixture-private-key"));
        JSONObject restored=OwnerSettingsBackup.prepare(archived,"3f431e60-6122-4d86-900a-79972d126735");
        ApiConnectionProfile row=ApiConnectionProfile.fromJson(restored.getJSONArray("apiConnections").getJSONObject(0));
        assertEquals("",row.configuration.model);assertFalse(row.enabled);assertEquals("",row.secretSlot);
        assertFalse(row.publicJson().getBoolean("selectionReady"));assertEquals("",settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
        assertNotEquals(saved.getString("id"),row.id());
        JSONObject corrupt=new JSONObject(archived.toString());corrupt.getJSONObject("content").getJSONArray("apiConnections").getJSONObject(0).getJSONObject("configuration").put("model",JSONObject.NULL);
        JSONObject invalid=dev.pocketengine.features.workspace.OwnerConfigurationBackupSections.create("settings",corrupt.getJSONObject("content"),corrupt.getJSONArray("omitted"));
        assertThrows(Exception.class,()->OwnerSettingsBackup.checked(invalid));
    }
    @Test public void reviewedAccountCaptureWorksWithoutAnyApiOrGlobalSelection()throws Exception {
        prefs.edit().putBoolean("sendScreenshots",false).putBoolean("localCaptureAllowed",true).putBoolean("localOcrAllowed",false).putInt("maxSteps",12).putInt("requestTimeoutSeconds",50).commit();
        Map<String,?> before=prefs.getAll();
        ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId","native-account").put("accountVersion",7).put("modelId","catalog-model"));
        ModelConfig captured=settings.captureAccountModel(ref,ReasoningOptions.DEFAULT,0,2048);
        assertTrue(captured.usesAccount());assertEquals(ref,captured.accountModel);assertEquals(2048,captured.maxOutputTokens);assertEquals(12,captured.maxSteps);assertEquals(50,captured.requestTimeoutSeconds);
        assertFalse(captured.sendScreenshots);assertTrue(captured.localCaptureAllowed);assertFalse(captured.localOcrAllowed);
        assertEquals(before,prefs.getAll());assertEquals(0,secretReads);assertNull(settings.selectedAccountModel());
    }
    @Test public void groupDeleteChecksRevisionAndEveryPurposeBindingBeforeCommit()throws Exception {
        JSONObject api=source("manual-model");String connection=api.getString("id");settings.selectApiConnection(connection,1);
        JSONObject group=group(api);
        String id=group.getString("groupId");settings.selectModelPurpose("SUMMARY",ModelTargetRef.group(id,1));String before=settings.modelGroupsSnapshot().toString();
        assertThrows(IllegalStateException.class,()->settings.deleteModelGroup(id,2));assertEquals(before,settings.modelGroupsSnapshot().toString());
        IllegalStateException selected=assertThrows(IllegalStateException.class,()->settings.deleteModelGroup(id,1));assertTrue(selected.getMessage().contains("SUMMARY"));assertEquals(before,settings.modelGroupsSnapshot().toString());
        settings.selectModelPurpose("SUMMARY",null);settings.deleteModelGroup(id,1);assertEquals(0,settings.modelGroupsSnapshot().getJSONArray("groups").length());
        assertEquals("manual-model",settings.get().model);assertThrows(IllegalStateException.class,()->settings.deleteModelGroup(id,1));
    }
    @Test public void firstMainGroupCapturesUsablePurposeFallbacksWithoutWritingADefaultSource()throws Exception {
        JSONObject api=source("");JSONObject group=group(api);String id=group.getString("groupId");
        assertEquals("",settings.apiConnectionsSnapshot().getString("selectedConnectionId"));
        settings.selectModelPurpose("MAIN",ModelTargetRef.group(id,1));ModelConfig captured=settings.get();
        assertEquals("manual-model",captured.model);assertEquals(id,captured.routePlan.requestedAlias);
        for(String purpose:new String[]{"SUMMARY","DEFAULT"}){
            ModelConfig fallback=captured.purposePlans.get(purpose).primary();assertEquals("manual-model",fallback.model);assertEquals(api.getString("id"),fallback.capabilityBinding.connectionId());assertNull(fallback.routePlan);SettingsStore.validate(fallback);
        }
        assertEquals("",settings.apiConnectionsSnapshot().getString("selectedConnectionId"));assertEquals("",settings.apiConnectionMetadata(api.getString("id"),1).configuration.model);
        settings.selectModelPurpose("SUMMARY",ModelTargetRef.exact(api.getString("id"),"summary-model"));assertEquals("summary-model",settings.get().purposePlans.get("SUMMARY").primary().model);
        settings.setApiConnectionEnabled(api.getString("id"),1,false);assertThrows(IllegalStateException.class,settings::get);
    }
    @Test public void conversationApiGroupDoesNotDecryptAnUnrelatedBrokenGlobalCredential()throws Exception {
        prefs.edit().putInt("maxSteps",17).putBoolean("localCaptureAllowed",false).putBoolean("localOcrAllowed",true).commit();
        JSONObject api=source("manual-model"),unrelated=source("global-model");settings.selectApiConnection(unrelated.getString("id"),1);
        JSONObject group=group(api);String id=group.getString("groupId");unreadableSlot=settings.apiConnectionMetadata(unrelated.getString("id"),1).secretSlot;
        assertThrows(IllegalStateException.class,settings::get);secretSlotsRead.clear();Map<String,?> before=prefs.getAll();
        ModelConfig captured=settings.captureConversationGroup(id,1);
        assertEquals("manual-model",captured.model);assertEquals(api.getString("id"),captured.capabilityBinding.connectionId());assertEquals(id,captured.routePlan.requestedAlias);assertEquals(1,captured.routePlan.groupRevision);
        assertEquals(17,captured.maxSteps);assertFalse(captured.localCaptureAllowed);assertTrue(captured.localOcrAllowed);
        assertFalse(secretSlotsRead.contains(unreadableSlot));assertTrue(secretSlotsRead.contains(settings.apiConnectionMetadata(api.getString("id"),1).secretSlot));assertEquals(before,prefs.getAll());
        assertEquals(unrelated.getString("id"),settings.apiConnectionsSnapshot().getString("selectedConnectionId"));assertThrows(IllegalStateException.class,()->settings.captureConversationGroup(id,2));
    }
    @Test public void conversationGroupRejectsAccountsWithoutChangingOriginalGlobalGroupBehavior()throws Exception {
        settings.selectAccountModel(new JSONObject().put("provider","chatgpt_plan").put("accountId","native-account").put("accountVersion",3).put("modelId","account-model"));
        JSONObject group=group("account:native-account","account-model");String id=group.getString("groupId");
        assertTrue(settings.captureModelTarget(ModelTargetRef.group(id,1),"MAIN","APP_OWNER",settings.get()).usesAccount());
        Map<String,?> before=prefs.getAll();int reads=secretReads;
        IllegalArgumentException denied=assertThrows(IllegalArgumentException.class,()->settings.captureConversationGroup(id,1));assertTrue(denied.getMessage().contains("ChatGPT"));
        assertEquals(reads,secretReads);assertEquals(before,prefs.getAll());
    }
    @Test public void conversationGroupRejectsBedrockFromMetadataBeforeDecryptingItsSession()throws Exception {
        String model="anthropic.claude-sonnet-4-6";
        JSONObject session=new JSONObject().put("accessKeyId","ASIA"+"A".repeat(16)).put("secretAccessKey","s".repeat(40)).put("sessionToken","t".repeat(32)).put("expiresAt",java.time.Instant.now().plusSeconds(3600).toString());
        JSONObject api=settings.saveApiConnection("",0,"Bedrock 来源",new JSONObject().put("provider",ModelConfig.BEDROCK_PROVIDER).put("bedrockRegion","us-east-1").put("model",model).put("awsSession",session));
        JSONObject group=group(api.getString("id"),model);String id=group.getString("groupId");
        assertTrue(settings.captureModelTarget(ModelTargetRef.group(id,1),"MAIN","APP_OWNER",new ModelConfig("","","",false,20,60)).usesBedrock());
        Map<String,?> before=prefs.getAll();int reads=secretReads;
        IllegalArgumentException denied=assertThrows(IllegalArgumentException.class,()->settings.captureConversationGroup(id,1));assertTrue(denied.getMessage().contains("Bedrock"));
        assertEquals(reads,secretReads);assertEquals(before,prefs.getAll());
    }
}
