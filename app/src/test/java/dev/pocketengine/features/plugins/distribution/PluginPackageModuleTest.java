package dev.pocketengine.features.plugins.distribution;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
public class PluginPackageModuleTest {
    @Test public void actualModuleReinstallClearsOldGrantsAndInstallationIdentity()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
        try{JSONObject manifest=new JSONObject().put("id","packageFixture").put("name","声明包").put("version","1").put("tools",new JSONArray().put(new JSONObject().put("name","show").put("kind","echo")));
            JSONObject old=module.handle("install",new JSONObject().put("manifest",manifest));dev.pocketengine.features.plugins.PluginOwnerTestReview.enableCurrent(module,"packageFixture",true);module.handle("grant",new JSONObject().put("id","packageFixture").put("grants",new JSONArray().put("tool:show")));assertEquals(1,module.toolRegistry().length());
            PluginPackages packages=new PluginPackages(module);ByteArrayOutputStream out=new ByteArrayOutputStream();packages.exportPackage("packageFixture",old.getString("installationId"),new JSONObject().put("label","本机导出"),out,new RunControl());
            PluginPackages.Inspection review=packages.inspect(new ByteArrayInputStream(out.toByteArray()),new RunControl());JSONObject result=packages.importReviewed(new ByteArrayInputStream(out.toByteArray()),review,review.reviewDigest,new RunControl());
            assertEquals("installed_disabled",result.getString("status"));assertEquals(0,module.toolRegistry().length());assertNotEquals(old.getString("installationId"),result.getJSONObject("plugin").getString("installationId"));assertEquals(1,module.handle("history",new JSONObject().put("id","packageFixture")).getJSONArray("versions").length());
        }finally{module.close();}
    }
}
