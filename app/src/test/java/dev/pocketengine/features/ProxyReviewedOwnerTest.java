package dev.pocketengine.features;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ClipboardManager;
import dev.pocketengine.features.proxy.ProxyModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.nio.file.Files;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.Provider;
import java.security.Security;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
public final class ProxyReviewedOwnerTest {
    private final Provider keys=new Provider("ProxyReviewedOwner-"+UUID.randomUUID(),1.0,"Explicit test-only AndroidKeyStore") {{
        put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());
    }};
    @Before public void installKeys(){assertTrue(Security.insertProviderAt(keys,1)>0);}
    @After public void removeKeys(){Security.removeProvider(keys.getName());}

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

    @Test public void generatedTokenStaysNativeAndCopyRequiresTheCurrentOwnerAndRevision()throws Exception {
        Context context=RuntimeEnvironment.getApplication();SettingsStore settings=new SettingsStore(context);SecretVault secrets=new SecretVault(context);
        ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
        try(ProxyModule proxy=new ProxyModule(new FeatureContext(context,settings,secrets,event->{}))){
            JSONObject before=proxy.handleReviewedOwner("snapshot",new JSONObject(),-1,"",()->true);
            JSONObject first=new JSONObject().put("tokenRevision",before.getString("tokenRevision"));
            assertThrows(SecurityException.class,()->proxy.handleReviewedOwner("generateToken",first,-1,"",()->false));
            assertFalse(secrets.contains("proxy.bearer"));
            JSONObject generated=proxy.handleReviewedOwner("generateToken",first,-1,"",()->true);
            String token=secrets.get("proxy.bearer");assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
            assertFalse(generated.toString().contains(token));assertTrue(generated.getBoolean("hasToken"));assertFalse(generated.getBoolean("running"));
            assertNotEquals(before.getString("tokenRevision"),generated.getString("tokenRevision"));
            assertThrows(IllegalStateException.class,()->proxy.handleReviewedOwner("generateToken",first,-1,"",()->true));
            JSONObject current=new JSONObject().put("tokenRevision",generated.getString("tokenRevision"));
            assertThrows(SecurityException.class,()->proxy.handleReviewedOwner("copyToken",current,-1,"",()->false));
            JSONObject copied=proxy.handleReviewedOwner("copyToken",current,-1,"",()->true);
            assertTrue(copied.getBoolean("tokenCopied"));assertFalse(copied.toString().contains(token));
            assertEquals(token,clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            assertTrue(clipboard.getPrimaryClipDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
            JSONObject rotated=proxy.handleReviewedOwner("generateToken",current,-1,"",()->true);
            assertNotEquals(token,secrets.get("proxy.bearer"));assertFalse(rotated.getBoolean("running"));
            assertThrows(IllegalStateException.class,()->proxy.handleReviewedOwner("copyToken",current,-1,"",()->true));
        }
    }

    @Test public void savedTokenStartsOnlyExplicitlyAndPublishedClientConfigurationMatchesTheActualServer()throws Exception {
        Context context=RuntimeEnvironment.getApplication();SettingsStore settings=new SettingsStore(context);SecretVault secrets=new SecretVault(context);
        settings.addAndSelectApiConnection("API fixture",new JSONObject().put("provider","anthropic")
            .put("baseUrl","https://api.fixture.invalid").put("model","fixture-model").put("apiKey","fixture-private-upstream-key"));
        try(ProxyModule proxy=new ProxyModule(new FeatureContext(context,settings,secrets,event->{}))){
            JSONObject before=proxy.handleReviewedOwner("snapshot",new JSONObject(),-1,"",()->true);
            JSONObject generated=proxy.handleReviewedOwner("generateToken",new JSONObject().put("tokenRevision",before.getString("tokenRevision")),-1,"",()->true);
            assertFalse(generated.getBoolean("running"));assertEquals("",generated.getString("rootEndpoint"));
            long selection=generated.getLong("selectionRevision");
            JSONObject input=new JSONObject().put("port",0).put("tokenRevision",generated.getString("tokenRevision"));
            JSONObject badPort=new JSONObject(input.toString()).put("port",1023);
            assertThrows(IllegalArgumentException.class,()->proxy.handleReviewedOwner("start",badPort,selection,"",()->true));
            assertFalse(proxy.snapshot().getBoolean("running"));
            JSONObject started=proxy.handleReviewedOwner("start",input,selection,"",()->true);
            String root=started.getString("rootEndpoint"),token=secrets.get("proxy.bearer"),session=started.getString("sessionId");
            assertTrue(started.getBoolean("running"));assertEquals("127.0.0.1",started.getString("listenHost"));assertFalse(started.getBoolean("supportsLan"));
            assertEquals(root,started.getString("endpoint"));assertEquals(root+"/v1/models",started.getString("modelsEndpoint"));
            assertFalse(started.toString().contains(token));assertFalse(started.toString().contains("fixture-private-upstream-key"));
            JSONObject messages=started.getJSONArray("clientConfigurations").getJSONObject(0),chat=started.getJSONArray("clientConfigurations").getJSONObject(1);
            assertEquals("anthropic",messages.getString("protocol"));assertEquals(root,messages.getString("baseUrl"));
            assertEquals("x-api-key",messages.getString("authHeader"));assertTrue(messages.getBoolean("native"));
            assertEquals(root+"/v1/messages",messages.getJSONArray("requestUrls").getString(0));
            assertEquals("openai_chat",chat.getString("protocol"));assertEquals(root+"/v1",chat.getString("baseUrl"));assertFalse(chat.getBoolean("native"));
            HttpURLConnection connection=(HttpURLConnection)new URL(started.getString("modelsEndpoint")).openConnection();
            connection.setConnectTimeout(2000);connection.setReadTimeout(2000);connection.setRequestProperty("Authorization","Bearer "+token);
            try{assertEquals(200,connection.getResponseCode());try(var response=connection.getInputStream()){assertTrue(new String(response.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("fixture-model"));}}finally{connection.disconnect();}
            JSONObject current=new JSONObject().put("tokenRevision",started.getString("tokenRevision"));
            assertThrows(IllegalStateException.class,()->proxy.handleReviewedOwner("generateToken",current,-1,"",()->true));
            assertThrows(IllegalStateException.class,()->proxy.handleReviewedOwner("copyToken",current,-1,"not-current-session",()->true));
            assertFalse(proxy.handleReviewedOwner("stop",new JSONObject(),-1,session,()->true).getBoolean("running"));
        }
    }
}
