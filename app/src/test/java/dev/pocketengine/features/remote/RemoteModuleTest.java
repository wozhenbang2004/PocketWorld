package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
public final class RemoteModuleTest {
    @Test public void staleOwnerEditorCannotOverwriteOrRemoveReplacementConnection()throws Exception {
        Context context=RuntimeEnvironment.getApplication();RemoteModule module=restore(context,"unknown");
        try{
            String original=module.snapshot().getJSONArray("profiles").getJSONObject(0).getString("bindingHash");
            JSONObject saved=module.handle("saveProfile",new JSONObject().put("id","computer").put("expectedBindingHash",original).put("name","changed in another page").put("baseUrl","http://127.0.0.1:9999"));
            assertNotEquals(original,saved.getString("bindingHash"));
            for(String command:new String[]{"saveProfile","removeProfile"}){
                JSONObject stale=command.equals("saveProfile")?new JSONObject().put("id","computer").put("name","stale").put("baseUrl","http://127.0.0.1:8766"):new JSONObject().put("profileId","computer");
                try{module.handle(command,stale.put("expectedBindingHash",original));fail("Stale editor changed the replacement");}catch(SecurityException expected){}
            }
            JSONObject current=module.snapshot().getJSONArray("profiles").getJSONObject(0);
            assertEquals(saved.getString("bindingHash"),current.getString("bindingHash"));assertEquals("changed in another page",current.getString("name"));
        }finally{module.close();}
    }
    private RemoteModule restore(Context context,String status)throws Exception {
        Files.write(new File(context.getFilesDir(),"remote-profiles.json").toPath(),new JSONArray().put(new JSONObject().put("id","computer").put("name","电脑").put("baseUrl","http://127.0.0.1:8766")).toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(context.getFilesDir(),"remote-operations.json").toPath(),new JSONArray().put(new JSONObject().put("operationId","previous-operation").put("profileId","computer").put("serviceBase","http://127.0.0.1:8766").put("status",status).put("createdAt",1)).toString().getBytes(StandardCharsets.UTF_8));
        return new RemoteModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
    }
    @Test public void restartingAnUnacknowledgedSubmissionPreservesUnknownIntent()throws Exception {
        Context context=RuntimeEnvironment.getApplication();RemoteModule module=restore(context,"submitting");
        try{
            JSONObject operation=module.snapshot().getJSONArray("operations").getJSONObject(0);
            assertEquals("previous-operation",operation.getString("operationId"));
            assertEquals("unknown",operation.getString("status"));
            assertEquals(0,module.snapshot().getJSONArray("jobs").length());
            assertTrue(module.snapshot().getBoolean("operationReconciliation"));
        }finally{module.close();}
    }
    @Test public void changingTheComputerAddressCannotReconcileOrResubmitAnOldOperationThere()throws Exception {
        Context context=RuntimeEnvironment.getApplication();RemoteModule module=restore(context,"unknown");
        try{
            module.handle("saveProfile",new JSONObject().put("id","computer").put("name","新地址").put("baseUrl","http://127.0.0.1:9999"));
            for(String command:new String[]{"reconcileOperation","submitJob"}){
                JSONObject request=new JSONObject().put("profileId","computer").put("operationId","previous-operation").put("environmentId","project").put("argv",new JSONArray().put("git").put("status"));
                try{module.handle(command,request);fail("Old operation was transferred to the new computer");}
                catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("原操作不一致"));}
            }
            JSONObject operation=module.snapshot().getJSONArray("operations").getJSONObject(0);
            assertEquals("http://127.0.0.1:8766",operation.getString("serviceBase"));
            assertEquals("unknown",operation.getString("status"));
        }finally{module.close();}
    }
}
