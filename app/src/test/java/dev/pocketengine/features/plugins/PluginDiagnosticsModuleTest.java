package dev.pocketengine.features.plugins;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public class PluginDiagnosticsModuleTest {
    private JSONObject manifest(String kind,String endpoint)throws Exception {
        JSONObject tool=new JSONObject().put("name","submit").put("kind",kind).put("schema",new JSONObject("{\"type\":\"object\",\"required\":[\"count\"],\"properties\":{\"count\":{\"type\":\"integer\",\"minimum\":1}},\"additionalProperties\":false}"));
        if(endpoint!=null)tool.put("endpoint",endpoint);
        return new JSONObject().put("id","diagnostics").put("name","参数诊断").put("version","1").put("tools",new JSONArray().put(tool));
    }
    private JSONObject request(JSONObject installed,Object args)throws Exception {
        return new JSONObject().put("id","diagnostics").put("tool","submit").put("expectedInstallationId",installed.getString("installationId")).put("arguments",args);
    }
    private void enable(PluginsModule module)throws Exception {
        module.handle("enable",new JSONObject().put("id","diagnostics").put("enabled",true));
        module.handle("grant",new JSONObject().put("id","diagnostics").put("grants",new JSONArray().put("tool:submit")));
    }
    @Test public void diagnosticsAreReadOnlyWithoutGrantOrNetworkAndDoNotAuthorizeInvoke()throws Exception {
        Context context=RuntimeEnvironment.getApplication();AtomicInteger events=new AtomicInteger();
        PluginsModule module=new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->events.incrementAndGet()));
        try(ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(100);
            JSONObject installed=module.handle("install",new JSONObject().put("manifest",manifest("http","http://127.0.0.1:"+server.getLocalPort()+"/tool")));
            File file=new File(context.getFilesDir(),"plugins/manifests.json");byte[] before=Files.readAllBytes(file.toPath());
            java.util.Map<String,?> secretsBefore=context.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).getAll();
            JSONObject good=request(installed,new JSONObject().put("count",1));
            JSONObject report=module.handle("validateArguments",good);assertTrue(report.getBoolean("valid"));assertTrue(report.getBoolean("validationOnly"));
            assertEquals(installed.getString("installationId"),report.getString("currentInstallationId"));assertEquals("1",report.getString("currentVersion"));
            assertFalse(report.has("operationId"));assertFalse(report.has("toolResult"));assertFalse(report.has("value"));
            JSONObject invalid=module.handle("validateArguments",request(installed,new JSONObject().put("count","secret-value")));
            assertFalse(invalid.getBoolean("valid"));assertFalse(invalid.toString().contains("secret-value"));
            assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals(secretsBefore,context.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).getAll());
            assertEquals(0,module.snapshot().getJSONArray("active").length());assertEquals(0,module.toolRegistry().length());assertEquals(0,events.get());
            try{module.handle("invoke",good);fail("valid arguments do not grant execution");}catch(IllegalStateException disabled){}
            module.handle("enable",new JSONObject().put("id","diagnostics").put("enabled",true));
            assertTrue(module.handle("validateArguments",good).getBoolean("valid"));
            try{module.handle("invoke",good);fail("diagnostics do not create a grant");}catch(SecurityException ungranted){}
            try(java.net.Socket unexpected=server.accept()){fail("validation must never send HTTP");}catch(SocketTimeoutException noRequest){}
        }finally{module.close();}
    }
    @Test public void diagnosisRequiresAnInstalledToolAndExactCurrentInstallation()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
        try{
            JSONObject installed=module.handle("install",new JSONObject().put("manifest",manifest("echo",null)));
            JSONObject original=request(installed,new JSONObject().put("count",1));
            JSONObject missing=new JSONObject(original.toString());missing.remove("expectedInstallationId");
            try{module.handle("validateArguments",missing);fail();}catch(IllegalArgumentException required){}
            for(String field:new String[]{"id","tool"}){
                JSONObject wrong=new JSONObject(original.toString()).put(field,"missing");
                try{module.handle("validateArguments",wrong);fail();}catch(IllegalArgumentException absent){}
            }
            JSONObject updated=module.handle("install",new JSONObject().put("manifest",manifest("echo",null)));
            try{module.handle("validateArguments",original);fail("same version reinstall invalidates an old form schema");}catch(IllegalStateException stale){}
            JSONObject fresh=request(updated,new JSONObject().put("count",1));assertTrue(module.handle("validateArguments",fresh).getBoolean("valid"));
            JSONObject restored=module.handle("rollback",new JSONObject().put("id","diagnostics").put("expectedInstallationId",updated.getString("installationId")).put("expectedStateRevision",updated.getLong("stateRevision")).put("targetInstallationId",installed.getString("installationId")));
            try{module.handle("validateArguments",original);fail();}catch(IllegalStateException stale){}
            try{module.handle("validateArguments",fresh);fail();}catch(IllegalStateException stale){}
            assertTrue(module.handle("validateArguments",request(restored,new JSONObject().put("count",1))).getBoolean("valid"));
            module.handle("remove",new JSONObject().put("id","diagnostics"));
            try{module.handle("validateArguments",original);fail();}catch(IllegalArgumentException removed){}
        }finally{module.close();}
    }
    @Test public void runtimeAndDiagnosticShareValidationIncludingWrongRootAndExactNumbers()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
        try{
            JSONObject declaration=manifest("echo",null);declaration.getJSONArray("tools").getJSONObject(0).getJSONObject("schema").getJSONObject("properties").getJSONObject("count").put("enum",new JSONArray().put(9007199254740993L));
            JSONObject installed=module.handle("install",new JSONObject().put("manifest",declaration));enable(module);
            for(Object args:new Object[]{new JSONObject(),new JSONObject().put("count",9007199254740992L),new JSONObject().put("count",new BigDecimal("9007199254740993.1")),JSONObject.NULL,new JSONArray(),"not-json-object"}){
                JSONObject input=request(installed,args);assertFalse(module.handle("validateArguments",input).getBoolean("valid"));
                try{module.handle("invoke",input);fail("diagnostic failure must also block actual invocation");}catch(IllegalArgumentException invalid){}
            }
            BigDecimal precise=new BigDecimal("9007199254740993.0");JSONObject input=request(installed,new JSONObject().put("count",precise));
            assertTrue(module.handle("validateArguments",input).getBoolean("valid"));
            JSONObject receipt=module.handle("invoke",input);assertEquals("completed",receipt.getString("status"));
            assertEquals(0,new BigDecimal(receipt.getJSONObject("value").get("count").toString()).compareTo(precise));
            assertTrue(module.snapshot().getJSONArray("commands").toString().contains("validateArguments"));
        }finally{module.close();}
    }
    @Test public void omittedArgumentsStillMeanEmptyObjectButExplicitNonObjectsAreNotSilentlyDiscarded()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
        try{
            JSONObject declaration=manifest("echo",null);declaration.getJSONArray("tools").getJSONObject(0).put("schema",new JSONObject().put("type","object"));
            JSONObject installed=module.handle("install",new JSONObject().put("manifest",declaration));enable(module);
            JSONObject input=request(installed,new JSONObject());input.remove("arguments");
            assertTrue(module.handle("validateArguments",input).getBoolean("valid"));assertEquals(0,module.handle("invoke",input).getJSONObject("value").length());
            input.put("arguments",JSONObject.NULL);assertFalse(module.handle("validateArguments",input).getBoolean("valid"));
            try{module.handle("invoke",input);fail();}catch(IllegalArgumentException invalid){}
        }finally{module.close();}
    }
}
