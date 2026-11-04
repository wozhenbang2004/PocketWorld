package dev.pocketengine.media;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original Android owner admission; the transport must never be created by review/prepare. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public final class RealtimeReviewedOwnerTest {
    private RealtimeSessionModule module;
    private ActivityController<Activity> owner;
    private MediaController media;
    private final Map<String,String> secrets=new HashMap<>();
    @Before public void setup()throws Exception {
        File files=Files.createTempDirectory("realtime-reviewed-owner-").toFile();
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
        };
        FeatureContext features=new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{});
        module=new RealtimeSessionModule(features,(android,events)->{throw new AssertionError("Review and prepare cannot create transport");},new RealtimeSessionModule.Credentials() {
            public boolean contains(String slot){return secrets.containsKey(slot);}
            public String get(String slot){return secrets.getOrDefault(slot,"");}
            public void put(String slot,String value){secrets.put(slot,value);}
            public void remove(String slot){secrets.remove(slot);}
        });
        owner=Robolectric.buildActivity(Activity.class).create().start().resume();
        media=new MediaController(owner.get(),event->{});module.setForeground(owner.get(),media);
    }
    @After public void close(){if(module!=null)module.close();if(media!=null)media.close();if(owner!=null)owner.pause().stop().destroy();}
    private JSONObject configuration(String host)throws Exception{return new JSONObject().put("serverUrl","wss://"+host).put("participantToken","fixture-short-lived-participant");}
    @Test public void staleConfigurationAndPrepareCannotReplaceTheReviewedOriginal()throws Exception {
        JSONObject first=module.handleReviewedOwner(owner.get(),"configure",configuration("first.example"),0,()->true);
        long revision=first.getJSONObject("configuration").getLong("revision");
        assertThrows(IllegalStateException.class,()->module.handleReviewedOwner(owner.get(),"configure",configuration("second.example"),0,()->true));
        assertEquals("wss://first.example",module.snapshot().getJSONObject("configuration").getString("serverUrl"));
        assertThrows(IllegalStateException.class,()->module.handleReviewedOwner(owner.get(),"prepareCall",new JSONObject(),0,()->true));
        assertTrue(module.snapshot().isNull("invitation"));
        JSONObject prepared=module.handleReviewedOwner(owner.get(),"prepareCall",new JSONObject(),revision,()->true);
        assertEquals("awaiting_owner",prepared.getString("invitationState"));
        assertFalse(prepared.getBoolean("audioLeaseHeld"));
        assertFalse(prepared.toString().contains("fixture-short-lived-participant"));
    }
    @Test public void invalidatedWebWindowCannotWriteCredentialsAndDoesNotPoisonNativeOwner()throws Exception {
        AtomicBoolean visible=new AtomicBoolean(false);
        assertThrows(SecurityException.class,()->module.handleReviewedOwner(owner.get(),"configure",configuration("first.example"),0,visible::get));
        assertTrue(secrets.isEmpty());
        JSONObject nativeResult=module.handleOwner(owner.get(),"configure",configuration("native.example"));
        assertEquals("wss://native.example",nativeResult.getJSONObject("configuration").getString("serverUrl"));
        visible.set(true);
        assertTrue(module.handleReviewedOwner(owner.get(),"snapshot",new JSONObject(),-1,visible::get).getBoolean("foregroundOwner"));
    }
}
