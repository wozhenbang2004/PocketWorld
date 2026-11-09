package dev.pocketengine.features.proxy.accounts;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.models.ReasoningOptions;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Native conversation capture with actual localhost account/catalog fixtures; no live account claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeAccountPreferenceCaptureTest {
    private static final class Rig implements AutoCloseable {
        final AccountGatewayTest.Rig account=new AccountGatewayTest.Rig();
        final Application app=RuntimeEnvironment.getApplication();
        final FeatureHost host=new FeatureHost(event->{});
        final TaskStore tasks=new TaskStore(app);
        final SettingsStore settings=new SettingsStore(app);
        final FeatureContext context=new FeatureContext(app,settings,new SecretVault(app),event->{});
        final NativeOwnerWorkflows flows=new NativeOwnerWorkflows(host,context,tasks,new MemorySourceBridge(host,tasks));
        Rig()throws Exception{
            var gateway=NativeOwnerWorkflows.class.getDeclaredField("accounts");gateway.setAccessible(true);gateway.set(flows,account.gateway);
            JSONObject model=new JSONObject().put("slug","fixture-model").put("display_name","Fixture model").put("visibility","list")
                .put("context_window",12000).put("max_output_tokens",256).put("input_modalities",new JSONArray().put("text").put("image"))
                .put("supported_reasoning_levels",new JSONArray().put(new JSONObject().put("effort","high")));
            account.fake.modelsBody=new JSONObject().put("models",new JSONArray().put(model)).toString();
        }
        JSONObject review(String id)throws Exception{
            long version=account.book.row(id).getLong("version");
            try(GatewayCall call=new GatewayCall(5000)){
                return flows.account("models",new JSONObject().put("accountId",id).put("expectedVersion",version),call)
                    .getJSONArray("models").getJSONObject(0).getJSONObject("nativeRef");
            }
        }
        ModelConfig capture(JSONObject reference)throws Exception{
            try(GatewayCall call=new GatewayCall(5000)){return flows.captureModel(reference,call);}
        }
        ModelConfig saved(ModelConfig.AccountModelRef reference)throws Exception{
            try(GatewayCall call=new GatewayCall(5000)){return flows.captureSavedModel(reference,ReasoningOptions.DEFAULT,1024,512,call);}
        }
        Object selectedModel()throws Exception{
            var selected=NativeOwnerWorkflows.class.getDeclaredField("selectedModel");selected.setAccessible(true);return selected.get(flows);
        }
        public void close(){account.close();host.close();tasks.close();}
    }

    @Test public void nativeBrowserHandoffAllowsReturningSessionWithoutDisablingOriginalAccount()throws Exception{
        try(Rig rig=new Rig()){
            String id=rig.account.connect();
            JSONObject original=rig.account.book.row(id);
            JSONObject result;
            try(GatewayCall call=new GatewayCall(5000)){
                result=rig.flows.account("beginLogin",new JSONObject()
                    .put("accountId",id).put("expectedVersion",original.getLong("version")).put("label","Returning"),call);
            }
            String session=result.getString("sessionId");
            assertFalse(result.has("authorizationUrl"));
            String url=rig.flows.authorizationUrl(session);
            assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"));
            assertEquals("connected",rig.account.book.row(id).getString("status"));
            assertEquals(original.getString("slot"),rig.account.book.row(id).getString("slot"));
            rig.account.gateway.cancelLogin(session);
            assertThrows(GatewayFailure.class,()->rig.flows.authorizationUrl(session));
            assertEquals("connected",rig.account.book.row(id).getString("status"));
        }
    }

    @Test public void reviewedConversationCaptureLeavesGlobalApiAndBookSelectionUntouched()throws Exception{
        try(Rig rig=new Rig()){
            String original=rig.account.connect();rig.account.fake.subject="fixture-second";String other=rig.account.connect();
            rig.account.gateway.selectReviewed(original,rig.account.book.row(original).getLong("version"));
            var prefs=rig.app.getSharedPreferences("private_settings",0);
            assertTrue(prefs.edit().putString("encryptedKey","unreadable-unrelated-api-fixture").commit());
            var before=new HashMap<String,Object>(prefs.getAll());
            JSONObject reference=rig.review(other);
            ModelConfig captured=rig.capture(new JSONObject(reference.toString()).put("reasoningEffort","high").put("imageTokenReserve",1024).put("outputReserve",512));
            assertEquals(other,captured.accountModel.accountId);assertEquals("fixture-model",captured.model);
            assertEquals("high",captured.reasoning.level());assertEquals(512,captured.maxOutputTokens);assertTrue(captured.supportsImageInput);
            assertEquals("",captured.apiKey);assertEquals("",captured.baseUrl);
            assertEquals(original,rig.account.book.selected());assertNull(rig.settings.selectedAccountModel());assertNull(rig.selectedModel());
            assertEquals(before,prefs.getAll());assertEquals(0,rig.account.fake.responses.get());
            // An omitted reserve is a conversation default, not a read of the unrelated encrypted API key.
            assertEquals(4096,rig.capture(new JSONObject(reference.toString())).maxOutputTokens);
            assertThrows(ModelRequestPolicy.Rejected.class,()->rig.capture(new JSONObject(reference.toString()).put("outputReserve",32768)));
            assertThrows(GatewayFailure.class,()->rig.capture(new JSONObject(reference.toString()).put("accountVersion",reference.getLong("accountVersion")+1)));
            try(GatewayCall call=new GatewayCall(5000)){rig.account.gateway.logoutReviewed(other,reference.getLong("accountVersion"),call);}
            assertThrows(GatewayFailure.class,()->rig.capture(new JSONObject(reference.toString())));
            assertEquals(original,rig.account.book.selected());assertEquals(before,prefs.getAll());assertNull(rig.selectedModel());
        }
    }

    @Test public void savedCaptureFollowsOnlyTheOriginalGrantAndMetadataReadsDoNotRefreshOrSelect()throws Exception{
        try(Rig rig=new Rig()){
            String original=rig.account.connect();JSONObject global=rig.review(original);
            rig.settings.selectAccountModel(global,ReasoningOptions.DEFAULT,0,512);
            rig.account.fake.subject="fixture-second";String other=rig.account.connect();
            rig.account.gateway.selectReviewed(original,global.getLong("accountVersion"));
            ModelConfig captured=rig.capture(rig.review(other).put("outputReserve",512));
            JSONObject beforeGlobal=rig.settings.selectedAccountModel().toJson();
            var beforePrefs=new HashMap<String,Object>(rig.app.getSharedPreferences("private_settings",0).getAll());
            try(GatewayCall call=new GatewayCall(5000)){rig.account.gateway.refreshReviewed(other,captured.accountModel.accountVersion,call);}
            ModelConfig restored=rig.saved(captured.accountModel);
            assertEquals(other,restored.accountModel.accountId);assertEquals(captured.model,restored.model);
            assertTrue(restored.accountModel.accountVersion>captured.accountModel.accountVersion);
            assertEquals(512,restored.maxOutputTokens);assertEquals(1024,restored.imageTokenReserve);assertTrue(restored.supportsImageInput);
            int catalogReads=rig.account.fake.models.get(),refreshes=rig.account.fake.refreshes.get();
            assertEquals("fixture-model",rig.flows.modelFactsForPreference(restored.accountModel).modelId);
            assertEquals(catalogReads,rig.account.fake.models.get());assertEquals(refreshes,rig.account.fake.refreshes.get());
            assertEquals(original,rig.account.book.selected());assertEquals(beforeGlobal.toString(),rig.settings.selectedAccountModel().toJson().toString());
            assertEquals(beforePrefs,rig.app.getSharedPreferences("private_settings",0).getAll());assertNull(rig.selectedModel());
            assertEquals(0,rig.account.fake.responses.get());
            try(GatewayCall call=new GatewayCall(5000)){rig.account.gateway.logoutReviewed(other,restored.accountModel.accountVersion,call);}
            assertThrows(GatewayFailure.class,()->rig.saved(captured.accountModel));
            assertThrows(GatewayFailure.class,()->rig.flows.modelFactsForPreference(restored.accountModel));
            assertEquals(original,rig.account.book.selected());assertEquals(beforePrefs,rig.app.getSharedPreferences("private_settings",0).getAll());
        }
    }
}
