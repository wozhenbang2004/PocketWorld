package dev.pocketengine.features;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.features.proxy.ProxyModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
public final class ProxyReviewedOwnerTest {
    @Test public void staleModelReviewOrClosedWindowCannotStartRelayOrStoreItsToken()throws Exception {
        File files=Files.createTempDirectory("proxy-reviewed-owner-").toFile();
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
        };
        SettingsStore settings=new SettingsStore(context);SecretVault secrets=new SecretVault(context);
        try(ProxyModule proxy=new ProxyModule(new FeatureContext(context,settings,secrets,event->{}))) {
            JSONObject before=proxy.handleReviewedOwner("snapshot",new JSONObject(),-1,"",()->true);
            long original=before.getLong("selectionRevision");
            JSONObject input=new JSONObject().put("port",0).put("token","fixture-local-service-token-32-characters");
            assertThrows(SecurityException.class,()->proxy.handleReviewedOwner("start",input,original,"",()->false));
            assertThrows(IllegalStateException.class,()->proxy.handleReviewedOwner("start",input,original+1,"",()->true));
            assertThrows(IllegalStateException.class,()->proxy.handleReviewedOwner("stop",new JSONObject(),-1,"successor-not-reviewed",()->true));
            assertFalse(proxy.snapshot().getBoolean("running"));assertFalse(secrets.contains("proxy.bearer"));
            assertEquals(original,proxy.handleReviewedOwner("snapshot",new JSONObject(),-1,"",()->true).getLong("selectionRevision"));
        }
    }
}
