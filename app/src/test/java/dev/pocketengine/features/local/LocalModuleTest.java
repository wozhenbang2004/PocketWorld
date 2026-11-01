package dev.pocketengine.features.local;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class LocalModuleTest {
    @Test public void realProjectCheckoutCanBeEditedAndImportedWithVersionProtection()throws Exception {
        Context context=RuntimeEnvironment.getApplication();FeatureContext feature=new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{});WorkspaceModule projects=new WorkspaceModule(feature);String project=projects.handle("create",new JSONObject().put("name","程序")).getString("id");
        projects.handle("write",new JSONObject().put("projectId",project).put("path","src/main.py").put("content","print(1)\n").put("expectedVersion",0));
        try(LocalModule local=new LocalModule(feature,projects)){
            String checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");JSONObject read=local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","src/main.py"));
            local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","src/main.py").put("content","print(2)\n").put("expectedHash",read.getString("sha256")));
            assertEquals("print(1)\n",projects.handle("read",new JSONObject().put("projectId",project).put("path","src/main.py")).getString("content"));
            JSONObject review=local.handle("reviewFile",new JSONObject().put("checkoutId",checkout).put("path","src/main.py"));JSONObject imported=local.handle("importToProject",review);assertEquals(2,imported.getInt("version"));assertEquals("print(2)\n",projects.handle("read",new JSONObject().put("projectId",project).put("path","src/main.py")).getString("content"));
            assertEquals(1,local.handle("export",new JSONObject().put("checkoutId",checkout)).getJSONArray("files").length());
            try{local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","src/main.py").put("content","overwrite").put("expectedHash",read.getString("sha256")));fail("Stale local edit accepted");}catch(IllegalStateException expected){}
        }
    }
}
