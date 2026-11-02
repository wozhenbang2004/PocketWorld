package dev.pocketengine.features.proxy.accounts;

import android.app.Notification;
import android.content.Intent;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Local fictitious auth only. No browser login, user grant or inference. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class,shadows=ChatGptSignInServiceTest.StartupShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ChatGptSignInServiceTest {
    /** Keep the real final Application type without starting unrelated application resources. */
    @Implements(value=EngineApplication.class,isInAndroidSdk=false)
    public static final class StartupShadow {
        @Implementation public void onCreate(){}
    }
    private FeatureHost fixtureHost;
    private NativeOwnerWorkflows workflows()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        fixtureHost=new FeatureHost(event->{});
        TaskStore tasks=new TaskStore(app);
        FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
        return new NativeOwnerWorkflows(fixtureHost,context,tasks,new MemorySourceBridge(fixtureHost,tasks));
    }
    @After public void closeFixture(){if(fixtureHost!=null)fixtureHost.close();}
    private EngineApplication install(AccountGatewayTest.Rig rig)throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();app.nativeOwnerWorkflows=workflows();
        Field accounts=NativeOwnerWorkflows.class.getDeclaredField("accounts");accounts.setAccessible(true);accounts.set(app.nativeOwnerWorkflows,rig.gateway);return app;
    }
    private void call(ChatGptSignInService service,String name,Class<?>[] types,Object... values)throws Exception {
        Method method=ChatGptSignInService.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(service,values);
    }
    @Test public void coldMetadataPortsReturnUnknownWithoutInitializingStorageOrTheVault()throws Exception {
        NativeOwnerWorkflows workflows=workflows();workflows.installAccountInference();
        try{Field installed=dev.pocketengine.runtime.ModelGateway.class.getDeclaredField("accountInference");installed.setAccessible(true);
            var port=(dev.pocketengine.runtime.ModelGateway.AccountInferencePort)installed.get(null);
            var ref=dev.pocketengine.core.ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId","cold-fixture").put("accountVersion",1).put("modelId","fixture-model"));
            assertFalse(port.imageInputSupported(ref));assertFalse(port.planUsageLimited(ref));
            Field account=NativeOwnerWorkflows.class.getDeclaredField("accounts");account.setAccessible(true);assertNull(account.get(workflows));
        }finally{dev.pocketengine.runtime.ModelGateway.installAccountInferencePort(null);}
    }
    @Test public void observerDestructionDoesNotCancelApplicationOwnedLoginAndItsReplacementCanObserve()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            install(rig);JSONObject login=rig.begin("");Intent intent=new Intent().putExtra("sessionId",login.getString("sessionId"));
            var first=Robolectric.buildService(ChatGptSignInService.class).create();ChatGptSignInService service=first.get();
            assertEquals(ChatGptSignInService.START_NOT_STICKY,service.onStartCommand(intent,0,1));
            Notification notification=Shadows.shadowOf(service).getLastForegroundNotification();assertNotNull(notification);assertTrue((notification.flags&Notification.FLAG_ONGOING_EVENT)!=0);
            assertEquals("取消登录",notification.actions[0].title.toString());assertFalse(notification.extras.toString().contains("code_verifier"));
            first.destroy();assertEquals("pending",rig.gateway.snapshot().getJSONObject("login").getString("status"));
            var replacement=Robolectric.buildService(ChatGptSignInService.class).create();replacement.get().onStartCommand(intent,0,2);
            AccountGatewayTest.callback(login,AccountGatewayTest.parameters(login).get("state"),"oaiapp_fixture");
            call(replacement.get(),"observe",new Class<?>[0]);assertTrue(Shadows.shadowOf(replacement.get()).isForegroundStopped());assertTrue(Shadows.shadowOf(replacement.get()).isStoppedBySelf());
            assertEquals("complete",rig.gateway.snapshot().getJSONObject("login").getString("status"));assertEquals(1,rig.fake.exchanges.get());replacement.destroy();
        }
    }
    @Test public void notificationCancellationTargetsOnlyItsOriginalAttempt()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            install(rig);JSONObject old=rig.begin("");rig.gateway.cancelLogin(old.getString("sessionId"));JSONObject current=rig.begin("");
            var controller=Robolectric.buildService(ChatGptSignInService.class).create();ChatGptSignInService service=controller.get();
            service.onStartCommand(new Intent().putExtra("sessionId",current.getString("sessionId")),0,1);
            service.onStartCommand(new Intent("dev.pocketengine.CANCEL_CHATGPT_SIGN_IN").putExtra("sessionId",old.getString("sessionId")),0,2);
            call(service,"cancelExact",new Class<?>[]{String.class},old.getString("sessionId"));assertEquals("pending",rig.gateway.snapshot().getJSONObject("login").getString("status"));
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
            call(service,"cancelExact",new Class<?>[]{String.class},current.getString("sessionId"));assertEquals("cancelled",rig.gateway.snapshot().getJSONObject("login").getString("status"));
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf());assertEquals(0,rig.fake.exchanges.get());controller.destroy();
        }
    }
    @Test public void originalTtlStopsTheNotificationWithoutAStickyRestart()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            install(rig);JSONObject login=rig.gateway.beginLogin("TTL fixture","",100);
            var controller=Robolectric.buildService(ChatGptSignInService.class).create();ChatGptSignInService service=controller.get();
            service.onStartCommand(new Intent().putExtra("sessionId",login.getString("sessionId")),0,1);
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(!"expired".equals(rig.gateway.snapshot().getJSONObject("login").optString("status"))&&System.nanoTime()<until)Thread.sleep(20);
            call(service,"observe",new Class<?>[0]);assertEquals("expired",rig.gateway.snapshot().getJSONObject("login").getString("status"));assertTrue(Shadows.shadowOf(service).isStoppedBySelf());assertTrue(rig.vault.values.isEmpty());controller.destroy();
        }
    }
}
