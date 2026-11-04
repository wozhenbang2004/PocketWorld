package dev.pocketengine.store;

import android.app.Application;
import android.content.SharedPreferences;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.controlplane.NativeWorkProfileHost;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.controlplane.WorkProfileResolver;
import dev.pocketengine.runtime.models.ModelGroup;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual SettingsStore transactions; no model inference, account login, or copied source grants. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class SettingsConfigurationBackupTest {
    private SharedPreferences prefs(){return RuntimeEnvironment.getApplication().getSharedPreferences("archive-"+UUID.randomUUID(),0);}
    private JSONObject add(SettingsStore store,String name)throws Exception{return store.saveApiConnection("",0,name,new JSONObject().put("baseUrl","https://example.test/v1").put("model","model-a").put("sendScreenshots",true));}
    private JSONObject archive()throws Exception {
        SettingsStore source=new SettingsStore(prefs());JSONObject api=add(source,"来自旧设备");
        ModelGroup group=new ModelGroup("draft-group",1,"旧模型组",Set.of(api.getString("id")),List.of(new ModelGroup.Candidate("primary",ModelTargetRef.exact(api.getString("id"),"model-a"),true,Set.of("MAIN"),Set.of("APP_OWNER"))),1,0);
        JSONObject savedGroup=source.saveModelGroup("",0,group.name,group.toJson());
        JSONObject profile=WorkProfile.definition("draft-work",1,"旧工作配置");profile.getJSONObject("modelPolicies").put("MAIN",ModelTargetRef.group(savedGroup.getString("groupId"),savedGroup.getLong("revision")).toJson());
        profile.getJSONArray("targetBindings").put(new JSONObject().put("kind","ssh").put("profileId","old-target").put("bindingHash","a".repeat(64)).put("optional",false));
        source.saveWorkProfile("",0,"旧工作配置",profile);return source.exportBackupForOwner(new RunControl());
    }
    @Test public void oneReservedTransactionAddsDisabledMappingsWithoutChangingCurrentChoiceOrBindings()throws Exception {
        JSONObject section=archive();SettingsStore target=new SettingsStore(prefs());JSONObject existing=add(target,"当前本机");target.selectApiConnection(existing.getString("id"),existing.getLong("revision"));ModelTargetRef binding=ModelTargetRef.exact(existing.getString("id"),"model-a");target.selectModelPurpose("MAIN",binding);
        String action=UUID.randomUUID().toString();var review=target.reviewBackupForOwner(section,action,new RunControl());assertFalse(target.modelSelection().status().pending());review.reserve();assertTrue(target.modelSelection().status().pending());assertTrue(review.restore().getBoolean("found"));assertFalse(target.modelSelection().status().pending());
        JSONObject snapshot=target.apiConnectionsSnapshot();assertEquals(existing.getString("id"),snapshot.getString("selectedConnectionId"));assertEquals(binding,target.modelPurposeTarget("MAIN"));assertEquals(2,snapshot.getJSONArray("connections").length());
        JSONObject imported=snapshot.getJSONArray("connections").getJSONObject(1);assertFalse(imported.getBoolean("enabled"));assertFalse(imported.getBoolean("hasApiKey"));assertFalse(imported.getJSONObject("configuration").getBoolean("sendScreenshots"));
        JSONObject group=target.modelGroupsSnapshot().getJSONArray("groups").getJSONObject(0);assertFalse(group.getJSONArray("candidates").getJSONObject(0).getBoolean("enabled"));assertEquals(imported.getString("id"),group.getJSONArray("candidates").getJSONObject(0).getJSONObject("target").getString("connectionId"));
        JSONObject profile=target.workProfilesSnapshot().getJSONArray("profiles").getJSONObject(0);assertTrue(profile.getBoolean("restoredPendingReview"));assertEquals(group.getString("groupId"),profile.getJSONObject("modelPolicies").getJSONObject("MAIN").getString("groupId"));assertEquals(0,profile.getJSONArray("targetBindings").length());assertEquals(2,profile.getJSONArray("restoredReferences").length());
        assertFalse(target.agentPreferences().getBoolean("enabled"));assertTrue(target.queryBackupForOwner(section,action,new RunControl()).getBoolean("found"));
    }
    @Test public void queuedMutationCancellationAndChangedStoreAreFencedBeforeAWrite()throws Exception {
        JSONObject section=archive();SharedPreferences original=prefs();SettingsStore target=new SettingsStore(original);var stale=target.reviewBackupForOwner(section,UUID.randomUUID().toString(),new RunControl());
        try(var other=target.modelSelection().beginMutation()){assertThrows(IllegalStateException.class,stale::reserve);}
        RunControl stopped=new RunControl();var cancelled=target.reviewBackupForOwner(section,UUID.randomUUID().toString(),stopped);cancelled.reserve();stopped.cancel();assertFalse(target.modelSelection().status().pending());assertThrows(IllegalStateException.class,cancelled::restore);assertEquals(0,target.apiConnectionsSnapshot().getJSONArray("connections").length());
        var changed=target.reviewBackupForOwner(section,UUID.randomUUID().toString(),new RunControl());changed.reserve();target.saveTaskAnnouncementsEnabled(true);assertThrows(SecurityException.class,changed::restore);assertTrue(target.taskAnnouncementsEnabled());assertEquals(0,target.workProfilesSnapshot().getJSONArray("profiles").length());assertFalse(target.modelSelection().status().pending());
    }
    @Test public void nonLoopbackHttpIsRetainedForExplicitRecreationAndNeverCountedAsAnImportedConnection()throws Exception {
        SettingsStore source=new SettingsStore(prefs());String endpoint="http://192.0.2.1:8080/v1";source.saveApiConnection("",0,"原局域网",new JSONObject().put("baseUrl",endpoint).put("model","model-a").put("cleartextConsent",ModelConfig.cleartextConsentFor("openai_chat",endpoint)));
        JSONObject section=source.exportBackupForOwner(new RunControl());assertFalse(section.toString().contains("cleartextConsent"));SettingsStore target=new SettingsStore(prefs());var review=target.reviewBackupForOwner(section,UUID.randomUUID().toString(),new RunControl());assertEquals(0,review.metadata().getJSONObject("counts").getInt("apiConnections"));assertEquals(endpoint,review.metadata().getJSONArray("requiresRecreation").getJSONObject(0).getJSONObject("configuration").getString("baseUrl"));
        review.reserve();JSONObject result=review.restore();assertEquals("completed_with_pending_recreation",result.getString("status"));assertFalse(result.getJSONArray("requiresRecreation").getJSONObject(0).getBoolean("restored"));assertEquals(0,target.apiConnectionsSnapshot().getJSONArray("connections").length());
    }
    @Test public void restoredProfileCanOnlyBeActivatedThroughTheCurrentNativeRebindReview()throws Exception {
        SettingsStore target=new SettingsStore(prefs());JSONObject local=add(target,"本机新选择"),section=archive();var restore=target.reviewBackupForOwner(section,UUID.randomUUID().toString(),new RunControl());restore.reserve();restore.restore();
        WorkProfileResolver.Sources sources=new WorkProfileResolver.Sources(){
            public ModelConfig model(ModelTargetRef ref,String purpose,RunControl original)throws Exception{return target.captureWorkProfileModel(ref,purpose,"APP_OWNER");}
            public void checkModel(ModelConfig value,String purpose,RunControl original)throws Exception{target.currentForCaptured(value);}
            public JSONObject skill(String id,RunControl original){throw new SecurityException("not-selected");}
            public JSONObject plugin(String id,RunControl original){throw new SecurityException("not-selected");}
            public JSONObject extension(String id,RunControl original){throw new SecurityException("not-selected");}
            public JSONObject team(String id,RunControl original){throw new SecurityException("not-selected");}
            public JSONObject target(JSONObject ref,RunControl original){throw new SecurityException("not-selected");}
            public Set<String> hostCapabilities(){return Set.of();}
        };
        NativeWorkProfileHost.Page page=new NativeWorkProfileHost(target,sources).newOwnerPage();page.setForeground(true);JSONObject pending=page.snapshot().getJSONArray("profiles").getJSONObject(0);String id=pending.getString("profileId");
        assertThrows(SecurityException.class,()->page.review(id,1,new RunControl()));JSONObject stripped=new JSONObject(pending.toString());stripped.remove("restoredPendingReview");stripped.remove("restoredReferences");JSONObject saved=page.save(id,1,pending.getString("name"),stripped);assertTrue(saved.getBoolean("restoredPendingReview"));assertEquals(2,saved.getJSONArray("restoredReferences").length());
        assertThrows(SecurityException.class,()->page.save(id,2,saved.getString("name"),new JSONObject(saved.toString()).put("restoredPendingReview",false)));
        saved.getJSONObject("modelPolicies").put("MAIN",ModelTargetRef.exact(local.getString("id"),"model-a").toJson());var oldReview=page.reviewRestored(id,2,saved.getString("name"),saved,new RunControl(),null);page.setForeground(false);assertThrows(SecurityException.class,()->page.rebindRestored(oldReview));page.setForeground(true);
        var reviewed=page.reviewRestored(id,2,saved.getString("name"),saved,new RunControl(),null);JSONObject rebound=page.rebindRestored(reviewed);assertFalse(rebound.getBoolean("restoredPendingReview"));assertEquals(3,rebound.getLong("revision"));assertEquals(2,rebound.getJSONArray("restoredReferences").length());assertEquals(0,rebound.getJSONArray("targetBindings").length());assertThrows(SecurityException.class,()->page.rebindRestored(reviewed));
    }
}
