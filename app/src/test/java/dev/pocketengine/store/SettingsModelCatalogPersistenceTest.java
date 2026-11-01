package dev.pocketengine.store;

import android.app.Application;
import android.content.SharedPreferences;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.models.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Android's memory-before-commit-result contract, over actual Robolectric SharedPreferences. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class SettingsModelCatalogPersistenceTest {
    private SharedPreferences real,prefs;private SettingsStore settings;private ModelBinding binding;private ModelConfig captured;
    private final AtomicBoolean failNext=new AtomicBoolean();
    @Before public void open()throws Exception{
        real=RuntimeEnvironment.getApplication().getSharedPreferences("prefs-confirmation-fixture",0);real.edit().clear().commit();
        prefs=(SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            try{
                Object result=method.invoke(real,args);
                if(!method.getName().equals("edit"))return result;
                SharedPreferences.Editor editor=(SharedPreferences.Editor)result;
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(owner,operation,values)->{
                    try{Object reply=operation.invoke(editor,values);if(operation.getName().equals("commit")&&failNext.getAndSet(false))return false;return reply==editor?owner:reply;}
                    catch(InvocationTargetException failure){throw failure.getCause();}
                });
            }catch(InvocationTargetException failure){throw failure.getCause();}
        });
        settings=new SettingsStore(prefs);settings.save(new JSONObject().put("baseUrl","https://fixture.example/v1").put("model","old-model"));
        settings.configureApiCatalog(null,ModelBinding.Format.OPENAI,URI.create("https://fixture.example/v1/models"));binding=settings.apiCatalogBinding();captured=settings.get();
        new ModelConnectionService(settings,Files.createTempDirectory("prefs-confirmation-").resolve("catalog.json"),Clock.systemUTC()).install();
    }
    private interface Checked {void run()throws Exception;}
    private void unavailable(Checked call)throws Exception{try{call.run();fail("Unconfirmed in-memory model settings were consumed");}catch(IllegalStateException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("不确定"));}}
    private void noConsumerCanUseUnconfirmedMemory()throws Exception{
        unavailable(()->settings.get());unavailable(()->settings.apiConfigForCatalog());unavailable(()->settings.apiCatalogBinding());unavailable(()->settings.selectedAccountModel());
        unavailable(()->new SettingsStore(prefs).get());unavailable(()->settings.save(new JSONObject().put("clearApiKey",true)));
        try{ModelGateway.complete(captured,new JSONArray().put(new JSONObject().put("role","user").put("content","must not send")),null,new RunControl());fail("Unconfirmed binding reached HTTP");}
        catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("读取")||expected.getMessage().contains("状态"));}
    }
    @Test public void failedNativeSelectionCannotBecomeTheNextTaskConfig()throws Exception{
        failNext.set(true);try{settings.selectCatalogModel(binding,"unconfirmed-model",512,ReasoningOptions.DEFAULT,0);fail();}catch(IllegalStateException expected){}
        assertEquals("unconfirmed-model",real.getString("model","")); // Proves mutation occurred before false, not a no-op fake.
        noConsumerCanUseUnconfirmedMemory();
    }
    @Test public void failedCatalogRevisionCannotBecomeTheNextCapabilityBinding()throws Exception{
        failNext.set(true);try{settings.configureApiCatalog(binding,ModelBinding.Format.OPENROUTER,binding.modelsEndpoint());fail();}catch(IllegalStateException expected){}
        assertEquals(binding.installationRevision()+1,new JSONObject(real.getString("apiCatalogBinding","")).getLong("installationRevision"));
        noConsumerCanUseUnconfirmedMemory();
    }
    @Test public void failedAccountSelectionCannotBecomeTheActiveRoute()throws Exception{
        failNext.set(true);try{settings.selectAccountModel(new JSONObject().put("provider","chatgpt_plan").put("accountId","fixture-account").put("accountVersion",1).put("modelId","fixture-model"));fail();}catch(IllegalStateException expected){}
        assertEquals("account",real.getString("modelRoute",""));noConsumerCanUseUnconfirmedMemory();
    }
    @Test public void failedBasicApiSaveCannotBypassTheNativeCatalogFence()throws Exception{
        failNext.set(true);try{settings.save(new JSONObject().put("model","unconfirmed-basic-model"));fail();}catch(IllegalStateException expected){}
        assertEquals("unconfirmed-basic-model",real.getString("model",""));noConsumerCanUseUnconfirmedMemory();
    }
}
