package dev.pocketengine.features.remote;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.features.controlplane.NativeWorkProfileSources;
import dev.pocketengine.features.controlplane.NativeWorkProfileTargetSources;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.controlplane.WorkProfileResolver;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.ssh.SshModule;
import dev.pocketengine.features.ssh.SshProfile;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual mounted original modules -> native adapter -> resolver -> frozen fast guard, no target execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkProfileHarnessCompositionNativeTest {
    private static final class Host implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final SettingsStore settings=new SettingsStore(app);
        final FeatureContext context=new FeatureContext(app,settings,new SecretVault(app),event->{});
        final TaskStore tasks;final FeatureHost features;final NativePluginHost plugins;final NativeWorkProfileSources sources;
        Host()throws Exception {
            app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);features=new FeatureHost(event->{},tasks,()->false);PluginsModule nativePlugins=new PluginsModule(context);features.register(nativePlugins);
            plugins=new NativePluginHost(app,nativePlugins,tasks,(binding,control)->{throw new AssertionError("No profile precheck may execute an extension");},task->{});
            sources=new NativeWorkProfileSources(settings,features,plugins,Set.of(),new NativeWorkProfileTargetSources(features));
        }
        WorkProfileResolver.Resolution resolve(JSONObject reference)throws Exception {
            WorkProfile profile=WorkProfile.fromJson(WorkProfile.definition("native-composition",1,"Exact original source only").put("targetBindings",new JSONArray().put(reference)));
            return WorkProfileResolver.resolve(profile,sources,new RunControl());
        }
        public void close()throws Exception {plugins.close();features.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
    @Test public void remoteStatEvidenceSurvivesRealResolutionAndFrozenFastCheckWithoutAnotherProbe()throws Exception {
        try(Host host=new Host();WorkProfileRemoteTargetFactsNativeTest.Fixture remote=new WorkProfileRemoteTargetFactsNativeTest.Fixture()){
            host.features.register(remote.remote);WorkProfileResolver.Resolution frozen=host.resolve(remote.ref("codex_app_server"));frozen.requireReady();JSONObject view=frozen.view(),item=view.getJSONArray("items").getJSONObject(0),evidence=item.getJSONObject("sourceEvidence");
            assertFalse(item.getJSONObject("binding").has("supported"));assertEquals("d".repeat(64),evidence.getString("executableIdentity"));assertTrue(evidence.getBoolean("executableIdentityVerified"));assertFalse(evidence.getBoolean("versionContentVerified"));assertFalse(evidence.getBoolean("executionChainVerified"));assertFalse(view.getBoolean("configurationApplied"));assertFalse(view.getBoolean("runtimeVerified"));assertFalse(view.getBoolean("executionAuthorized"));assertEquals(2,remote.gets.get());assertEquals(1,remote.probes.get());
            frozen.checkCurrent(new RunControl());assertEquals(3,remote.gets.get());assertEquals(1,remote.probes.get());assertEquals(0,remote.jobs.get());assertEquals(0,remote.remote.snapshot().getJSONArray("operations").length());
            remote.executableIdentity="e".repeat(64);assertThrows(SecurityException.class,()->frozen.checkCurrent(new RunControl()));assertEquals(4,remote.gets.get());assertEquals(1,remote.probes.get());assertEquals(0,remote.jobs.get());
        }
    }
    @Test public void originalSshReferenceSurvivesResolutionButCredentialWithdrawalInvalidatesFrozenSelection()throws Exception {
        try(Host host=new Host()){
            JSONObject row=new JSONObject().put("id","source-ssh").put("name","Owned fixture source").put("host","fixture.invalid").put("port",22).put("username","owner").put("workspace","/fixture/project").put("authType","key").put("pinnedKey",Base64.getEncoder().encodeToString(new byte[32])).put("configRevision","11111111-1111-1111-1111-111111111111").put("credentialRevision","22222222-2222-2222-2222-222222222222");SshProfile profile=new SshProfile(row);
            Files.write(host.app.getFilesDir().toPath().resolve("ssh-profiles.json"),new JSONArray().put(row).toString().getBytes(StandardCharsets.UTF_8));host.app.getSharedPreferences("feature_secrets",0).edit().putString(profile.credentialSlot(),"fixture-ciphertext-presence-only").commit();SshModule ssh=new SshModule(host.context);host.features.register(ssh);
            WorkProfileResolver.Resolution frozen=host.resolve(new JSONObject().put("kind","ssh").put("profileId",profile.id).put("bindingHash",profile.bindingHash()).put("optional",false));frozen.requireReady();JSONObject evidence=frozen.view().getJSONArray("items").getJSONObject(0).getJSONObject("sourceEvidence");assertTrue(evidence.getBoolean("hostKeyTrusted"));assertTrue(evidence.getBoolean("credentialsConfigured"));assertFalse(evidence.getBoolean("connectionVerified"));frozen.checkCurrent(new RunControl());
            host.app.getSharedPreferences("feature_secrets",0).edit().remove(profile.credentialSlot()).commit();assertThrows(SecurityException.class,()->frozen.checkCurrent(new RunControl()));assertEquals(0,ssh.snapshot().getJSONArray("operations").length());assertEquals(0,ssh.snapshot().getJSONArray("terminals").length());
        }
    }
}
