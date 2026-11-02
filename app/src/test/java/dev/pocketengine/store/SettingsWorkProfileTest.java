package dev.pocketengine.store;

import android.app.Application;
import android.content.SharedPreferences;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.controlplane.NativeWorkProfileHost;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.controlplane.WorkProfileResolver;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original SharedPreferences/CAS and opaque native owner review; no model, task, or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
public final class SettingsWorkProfileTest {
    private SharedPreferences prefs;
    private SettingsStore settings;
    @Before public void open() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("work-profile-" + java.util.UUID.randomUUID(), 0);
        settings = new SettingsStore(prefs);
    }
    private JSONObject draft() throws Exception { return WorkProfile.definition("draft-profile", 1, "Project work"); }
    @Test public void originalSettingsPersistReferencesWithoutSelectingExecutingOrCreatingGrants() throws Exception {
        JSONObject saved = settings.saveWorkProfile("", 0, "Project work", draft()); String id = saved.getString("profileId");
        assertEquals(1, saved.getLong("revision")); assertTrue(id.startsWith("work-"));
        assertEquals(1, new SettingsStore(prefs).workProfilesSnapshot().getJSONArray("profiles").length());
        assertFalse(prefs.contains("modelRoute")); assertFalse(prefs.contains("apiConnections")); assertEquals(Set.of("workProfiles"), prefs.getAll().keySet());
        JSONObject removed = settings.removeWorkProfile(id, 1); assertTrue(removed.getBoolean("removed")); assertFalse(removed.getBoolean("runsChanged")); assertFalse(removed.getBoolean("permissionsChanged"));
        assertEquals(2, settings.workProfilesSnapshot().getLong("revision")); assertEquals(0, new SettingsStore(prefs).workProfilesSnapshot().getJSONArray("profiles").length());
    }
    @Test public void staleEditsAndDeletesCannotOverwriteOrResurrectAnotherDefinition() throws Exception {
        JSONObject saved = settings.saveWorkProfile("", 0, "First", draft()); String id = saved.getString("profileId");
        settings.saveWorkProfile(id, 1, "Second", saved);
        assertThrows(SecurityException.class, () -> settings.saveWorkProfile(id, 1, "Old", saved));
        assertThrows(SecurityException.class, () -> settings.removeWorkProfile(id, 1));
        assertEquals("Second", settings.workProfilesSnapshot().getJSONArray("profiles").getJSONObject(0).getString("name"));
        settings.removeWorkProfile(id, 2); assertThrows(SecurityException.class, () -> settings.saveWorkProfile(id, 2, "Resurrected", saved));
    }
    @Test public void importedAuthorityFieldsOrNonIntegerPinsAreNotStored() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> settings.saveWorkProfile("", 0, "Forbidden", draft().put("grants", new JSONObject().put("phone.local", true))));
        JSONObject bad = draft(); bad.getJSONArray("skillRefs").put(new JSONObject().put("id", "skill-a").put("revision", 2.0).put("digest", "a".repeat(64)).put("optional", false));
        assertThrows(IllegalArgumentException.class, () -> settings.saveWorkProfile("", 0, "Bad ref", bad));
        assertEquals(0, settings.workProfilesSnapshot().getJSONArray("profiles").length()); assertFalse(prefs.contains("workProfiles"));
    }
    @Test public void failedCommitCannotBecomeConfirmedViaTheSameOrAnotherStoreObject() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        SharedPreferences uncertain = (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
            try {
                Object value = method.invoke(prefs, args); if (!method.getName().equals("edit")) return value;
                SharedPreferences.Editor editor = (SharedPreferences.Editor)value;
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (owner, operation, input) -> {
                    try { Object result = operation.invoke(editor, input); if (operation.getName().equals("commit") && fail.getAndSet(false)) return false; return result == editor ? owner : result; }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
            } catch (InvocationTargetException error) { throw error.getCause(); }
        });
        SettingsStore store = new SettingsStore(uncertain);
        assertThrows(SettingsStore.PersistenceUncertain.class, () -> store.saveWorkProfile("", 0, "Unknown", draft()));
        assertTrue("The Android memory-before-failed-result case must actually mutate memory", prefs.contains("workProfiles"));
        assertThrows(SettingsStore.PersistenceUncertain.class, store::workProfilesSnapshot);
        assertThrows(SettingsStore.PersistenceUncertain.class, () -> new SettingsStore(uncertain).workProfilesSnapshot());
    }
    private static final class Sources implements WorkProfileResolver.Sources {
        public ModelConfig model(ModelTargetRef r, String p, RunControl c) { throw new AssertionError("No model policy was selected"); }
        public void checkModel(ModelConfig captured, String purpose, RunControl c) { throw new AssertionError(); }
        public JSONObject skill(String id, RunControl c) { throw new AssertionError(); }
        public JSONObject plugin(String id, RunControl c) { throw new AssertionError(); }
        public JSONObject extension(String id, RunControl c) { throw new AssertionError(); }
        public JSONObject team(String id, RunControl c) { throw new AssertionError(); }
        public JSONObject target(JSONObject reference, RunControl c) { throw new AssertionError(); }
        public Set<String> hostCapabilities() { return Set.of(); }
    }
    @Test public void ownerReviewIsPageBoundSingleUseAndNeverAnExecutionGrant() throws Exception {
        NativeWorkProfileHost host = new NativeWorkProfileHost(settings, new Sources()); var page = host.newOwnerPage();
        assertThrows(SecurityException.class, () -> page.save("", 0, "Project", draft())); page.setForeground(true);
        JSONObject saved = page.save("", 0, "Project", draft()); String id = saved.getString("profileId");
        var review = page.review(id, 1, new RunControl()); assertTrue(review.view().getBoolean("ready")); assertFalse(review.view().getBoolean("executionAuthorized"));
        var another = host.newOwnerPage(); another.setForeground(true); assertThrows(SecurityException.class, () -> another.select(review, review.digest, new RunControl()));
        RunControl selectedControl = new RunControl(); var selected = page.select(review, review.digest, selectedControl); selected.checkCurrent(selectedControl);
        assertThrows(SecurityException.class, () -> page.select(review, review.digest, new RunControl()));
        assertEquals(Set.of("workProfiles"), prefs.getAll().keySet()); page.close(); another.close();
        assertFalse(selected.snapshot().getBoolean("executed")); assertFalse(selected.snapshot().getBoolean("permissionsChanged"));
    }
    @Test public void changedOrClosedOwnerReviewCannotFreezeTheOldDefinition() throws Exception {
        NativeWorkProfileHost host = new NativeWorkProfileHost(settings, new Sources()); var page = host.newOwnerPage(); page.setForeground(true);
        JSONObject saved = page.save("", 0, "Project", draft()); String id = saved.getString("profileId"); var review = page.review(id, 1, new RunControl());
        settings.saveWorkProfile(id, 1, "Changed", saved); assertThrows(SecurityException.class, () -> page.select(review, review.digest, new RunControl()));
        var next = page.review(id, 2, new RunControl()); page.close(); assertThrows(SecurityException.class, () -> page.select(next, next.digest, new RunControl()));
    }
    @Test public void originalSkillPluginAndCatalogSourcesAreComposedWithoutDuplicatingTheirAuthority() throws Exception {
        var app = RuntimeEnvironment.getApplication(); app.deleteDatabase("pocket-world.db"); TaskStore tasks = new TaskStore(app);
        var context = new dev.pocketengine.features.FeatureContext(app, settings, new SecretVault(app), event -> {});
        var skills = new dev.pocketengine.features.skills.SkillsModule(context);
        var plugins = new dev.pocketengine.features.plugins.PluginsModule(context);
        var features = new dev.pocketengine.features.FeatureHost(event -> {}, tasks, () -> false); features.register(skills); features.register(plugins);
        var nativePlugins = new dev.pocketengine.features.NativePluginHost(app, plugins, tasks, (binding, control) -> { throw new AssertionError("Preflight must not bind an executor"); }, id -> {});
        try {
            JSONObject skill = skills.handle("import", new JSONObject().put("source", "fixture:original-source").put("manifest", "---\nname: profile-source\ndescription: Original source is verified before use\n---\nRead the original current observation.")).getJSONObject("skill");
            JSONObject skillRef = new JSONObject().put("id", skill.getString("id")).put("revision", skill.getInt("revision")).put("digest", skill.getString("digest")).put("optional", false);
            JSONObject manifest = new JSONObject().put("id", "profile-echo").put("name", "Profile echo").put("version", "1").put("tools", new org.json.JSONArray().put(new JSONObject().put("name", "read").put("kind", "echo")));
            JSONObject plugin = plugins.handle("install", new JSONObject().put("manifest", manifest));
            String installation = plugin.getString("installationId");
            plugin = plugins.setEnabledReviewed("profile-echo", installation, true);
            JSONObject pluginRef = new JSONObject().put("id", "profile-echo").put("installationId", installation).put("stateRevision", plugin.getLong("stateRevision")).put("manifestDigest", WorkProfile.fingerprint(plugin.getJSONObject("manifest"))).put("tools", new org.json.JSONArray().put("read")).put("optional", false);
            JSONObject definition = draft().put("skillRefs", new org.json.JSONArray().put(skillRef)).put("pluginRefs", new org.json.JSONArray().put(pluginRef));
            var sources = new dev.pocketengine.features.controlplane.NativeWorkProfileSources(settings, features, nativePlugins, Set.of(), null);
            var initial = WorkProfileResolver.resolve(WorkProfile.fromJson(definition), sources, new RunControl()); assertFalse(initial.view().getBoolean("ready"));
            assertEquals(0, plugins.toolRegistry().length()); assertFalse(skills.handle("get", new JSONObject().put("id", skill.getString("id"))).getJSONObject("skill").getBoolean("active"));
            skills.handle("setEnabled", new JSONObject().put("id", skill.getString("id")).put("enabled", true).put("confirmed", true).put("acceptedDigest", skill.getString("digest")));
            plugin = plugins.setToolGrantReviewed("profile-echo", installation, plugin.getString("grantsDigest"), "read", true); pluginRef.put("stateRevision", plugin.getLong("stateRevision"));
            definition.put("pluginRefs", new org.json.JSONArray().put(pluginRef));
            var resolved = WorkProfileResolver.resolve(WorkProfile.fromJson(definition), sources, new RunControl()); resolved.requireReady(); resolved.checkCurrent(new RunControl());
            assertEquals(1, plugins.toolRegistry().length()); assertEquals(0, tasks.list().length());
            assertEquals("not_prepared", nativePlugins.runtimeSnapshot().getString("status"));
            assertEquals(0, nativePlugins.approvedExtensionReferences(new RunControl()).length());
            plugins.setToolGrantReviewed("profile-echo", installation, plugin.getString("grantsDigest"), "read", false);
            assertThrows(SecurityException.class, () -> resolved.checkCurrent(new RunControl()));
            assertEquals(0, tasks.list().length()); assertFalse(resolved.view().getBoolean("runtimeVerified"));
        } finally { nativePlugins.close(); features.close(); var field = TaskStore.class.getDeclaredField("db"); field.setAccessible(true); ((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close(); }
    }

}
