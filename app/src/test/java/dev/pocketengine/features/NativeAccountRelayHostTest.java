package dev.pocketengine.features;
import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.proxy.ProxyModule;
import dev.pocketengine.features.proxy.accounts.*;
import dev.pocketengine.store.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class NativeAccountRelayHostTest {
    private static JSONObject ref()throws Exception{return new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","missing-fixture-account").put("accountVersion",1L).put("modelId","fixture-model");}
    @Test public void installedNativeAccountRouteIsAvailableButNeverAutoStartsOrClaimsAuthorization()throws Exception{
        Application app=RuntimeEnvironment.getApplication();FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
        try(FeatureHost host=new FeatureHost(event->{})){
            TaskStore tasks=new TaskStore(app);NativeOwnerWorkflows flows=new NativeOwnerWorkflows(host,context,tasks,new MemorySourceBridge(host,tasks));context.settings.selectAccountModel(ref());
            try(ProxyModule proxy=new ProxyModule(context,flows.accountRelayRoute())){
                JSONObject snapshot=proxy.snapshot();assertFalse(snapshot.getBoolean("accountRouteAvailable"));assertFalse(snapshot.getBoolean("externalRelayAllowed"));assertFalse(snapshot.getBoolean("running"));assertFalse(snapshot.getBoolean("phoneExecution"));assertEquals("unavailable",snapshot.getString("protocol"));assertEquals(0,snapshot.getJSONArray("endpoints").length());
                assertEquals("CHATGPT_PLAN_APP_ONLY",assertThrows(dev.pocketengine.runtime.models.InferenceAudiencePolicy.Rejected.class,()->proxy.handle("start",new JSONObject())).code);assertFalse(proxy.snapshot().getBoolean("running"));assertFalse(context.secrets.contains("proxy.bearer"));
            }
        }
    }
    @Test public void missingAccountFailsBeforeAnyModelCallAndLeavesNativeSelectionUntouched()throws Exception{
        Application app=RuntimeEnvironment.getApplication();FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
        try(FeatureHost host=new FeatureHost(event->{});GatewayCall call=new GatewayCall(1000)){
            TaskStore tasks=new TaskStore(app);NativeOwnerWorkflows flows=new NativeOwnerWorkflows(host,context,tasks,new MemorySourceBridge(host,tasks));context.settings.selectAccountModel(ref());
            String before=context.settings.selectedAccountModel().toJson().toString();assertThrows(GatewayFailure.class,()->flows.accountRelayRoute().models(ModelConfig.AccountModelRef.fromJson(ref()),call));
            assertEquals(before,context.settings.selectedAccountModel().toJson().toString());assertEquals(0,flows.accountSnapshot().getJSONArray("accounts").length());
        }
    }
}
