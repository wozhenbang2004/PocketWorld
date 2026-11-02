package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.controlplane.NativeWorkProfileHost;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.controlplane.WorkProfileResolver;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original runs/grant/operation SQLite, not a parallel profile task store or a Runner replacement. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkProfileNativeRunTest {
    private TaskStore tasks;
    private SettingsStore settings;
    private NativeWorkProfileHost host;
    private NativeWorkProfileHost.Page page;
    private JSONObject definition;
    private Sources sources;
    private final class Sources implements WorkProfileResolver.Sources {
        boolean active = true;
        public ModelConfig model(ModelTargetRef r, String p, RunControl c) { throw new AssertionError("No model selected"); }
        public void checkModel(ModelConfig m, String p, RunControl c) { throw new AssertionError(); }
        public JSONObject skill(String id, RunControl c) throws Exception {
            assertFalse("TaskStore cannot enter source/module locks while holding its monitor", Thread.holdsLock(tasks));
            return new JSONObject().put("id", "skill-a").put("revision", 1).put("digest", "a".repeat(64)).put("executionGeneration", 1).put("expiresAt", JSONObject.NULL).put("requiredCapabilities", new JSONArray()).put("contentVerified", true).put("active", active).put("status", active ? "active" : "inactive");
        }
        public JSONObject plugin(String id, RunControl c) { throw new AssertionError(); }
        public JSONObject extension(String id, RunControl c) { throw new AssertionError(); }
        public JSONObject team(String id, RunControl c) { throw new AssertionError(); }
        public JSONObject target(JSONObject ref, RunControl c) { throw new AssertionError(); }
        public Set<String> hostCapabilities() { return Set.of(); }
    }
    private SQLiteDatabase db() throws Exception { var field = TaskStore.class.getDeclaredField("db"); field.setAccessible(true); return (SQLiteDatabase)field.get(tasks); }
    @Before public void open() throws Exception {
        var app = RuntimeEnvironment.getApplication(); app.deleteDatabase("pocket-world.db"); tasks = new TaskStore(app);
        settings = new SettingsStore(app.getSharedPreferences("run-profile-" + java.util.UUID.randomUUID(), 0)); sources = new Sources(); host = new NativeWorkProfileHost(settings, sources); page = host.newOwnerPage(); page.setForeground(true);
        JSONObject draft = WorkProfile.definition("draft-profile", 1, "Coding"); draft.getJSONArray("skillRefs").put(new JSONObject().put("id", "skill-a").put("revision", 1).put("digest", "a".repeat(64)).put("optional", false));
        definition = page.save("", 0, "Coding", draft);
    }
    @After public void close() throws Exception { page.close(); db().close(); }
    private NativeWorkProfileHost.Selection selection(RunControl original) throws Exception { var review = page.review(definition.getString("profileId"), definition.getLong("revision"), new RunControl()); return page.select(review, review.digest, original); }
    private JSONObject task() throws Exception { return tasks.createCapabilityTask("Original profile task", ""); }
    private JSONObject start(JSONObject task, RunControl original, NativeWorkProfileHost.Selection selected) throws Exception { return tasks.startAttemptWithWorkProfile(task.getString("id"), "capabilities", 4, selected, original); }
    private JSONObject grant(String run) throws Exception { try (var row = db().rawQuery("SELECT grant_json FROM runs WHERE run_id=?", new String[]{run})) { assertTrue(row.moveToFirst()); return new JSONObject(row.getString(0)); } }
    @Test public void runFreezesExactReferencesWithoutChangingOriginalPermissionsOrSchema() throws Exception {
        int version = db().getVersion(); JSONObject created = task(); RunControl original = new RunControl(); var selected = selection(original); JSONObject run = start(created, original, selected);
        String id = run.getString("id"), runId = run.getString("runId"); int epoch = run.getInt("coordinatorEpoch"); JSONObject frozen = tasks.workProfileRunSnapshot(id, runId, epoch);
        assertEquals(definition.getString("profileId"), frozen.getString("profileId")); assertEquals(1, frozen.getInt("profileRevision")); assertFalse(frozen.getBoolean("executionAuthorized")); assertFalse(frozen.getBoolean("permissionsChanged"));
        JSONObject grant = grant(runId); assertEquals(4, grant.getInt("remainingOperations")); assertEquals("capabilities", grant.getString("mode")); assertFalse(grant.has("tools")); assertEquals(0, tasks.get(id, false).getJSONArray("operations").length()); assertEquals(version, db().getVersion());
        tasks.checkWorkProfileRun(id, runId, epoch, frozen.getString("resolutionDigest"), original); selected.requireBoundRun(id, runId, epoch, original);
        assertThrows(SecurityException.class, () -> selected.requireBoundRun(id, runId, epoch, new RunControl()));
        assertThrows(SecurityException.class, () -> tasks.checkWorkProfileRun(id, runId, epoch, "b".repeat(64), original));
        frozen.put("profileRevision", 999); assertEquals(1, tasks.workProfileRunSnapshot(id, runId, epoch).getInt("profileRevision"));
    }
    @Test public void profileMetadataSurvivesTheOriginalJournalCheckpointRebuild() throws Exception {
        RunControl original = new RunControl(); JSONObject run = start(task(), original, selection(original)); String id = run.getString("id"), runId = run.getString("runId"); int epoch = run.getInt("coordinatorEpoch");
        String before = WorkProfile.fingerprint(tasks.workProfileRunSnapshot(id, runId, epoch));
        tasks.journal(id, runId, epoch, original).checkpoint(new JSONArray().put(new JSONObject().put("role", "user").put("content", "original context")), 0, new JSONObject());
        assertEquals(before, WorkProfile.fingerprint(tasks.workProfileRunSnapshot(id, runId, epoch))); assertEquals(4, grant(runId).getInt("remainingOperations"));
    }
    @Test public void nativeSelectionIsSingleUseAndBoundToTheExactOriginalControl() throws Exception {
        RunControl original = new RunControl(); var selected = selection(original); JSONObject created = task();
        assertThrows(SecurityException.class, () -> start(created, new RunControl(), selected));
        JSONObject run = start(created, original, selected); assertThrows(SecurityException.class, () -> start(task(), original, selected));
        assertEquals(run.getString("runId"), tasks.get(created.getString("id"), false).getString("runId"));
    }
    @Test public void sourceRevocationAfterFreezeDoesNotChangeOrAuthorizeTheFrozenRun() throws Exception {
        RunControl original = new RunControl(); var selected = selection(original); JSONObject run = start(task(), original, selected); sources.active = false;
        assertThrows(SecurityException.class, () -> selected.checkCurrent(original));
        JSONObject frozen = tasks.workProfileRunSnapshot(run.getString("id"), run.getString("runId"), run.getInt("coordinatorEpoch")); assertEquals(1, frozen.getInt("profileRevision")); assertFalse(frozen.getBoolean("executionAuthorized"));
    }
    @Test public void removedGlobalDefinitionRequiresExplicitOriginalTaskRecoveryNotDefaultFallback() throws Exception {
        RunControl original = new RunControl(); JSONObject run = start(task(), original, selection(original)); String id = run.getString("id"), old = run.getString("runId"); int epoch = run.getInt("coordinatorEpoch");
        tasks.endAttempt(id, old, "paused", "Owner paused", new JSONObject()); settings.removeWorkProfile(definition.getString("profileId"), 1);
        SecurityException blocked = assertThrows(SecurityException.class, () -> tasks.startAttempt(id, "capabilities", 4)); assertTrue(blocked.getMessage().contains("工作配置页"));
        var review = page.reviewForTask(tasks, id, old, epoch, new RunControl()); RunControl next = new RunControl(); var recovered = page.select(review, review.digest, next); JSONObject resumed = start(run, next, recovered);
        assertEquals(epoch + 1, resumed.getInt("coordinatorEpoch")); assertNotEquals(old, resumed.getString("runId")); assertEquals(1, tasks.workProfileRunSnapshot(id, resumed.getString("runId"), epoch + 1).getInt("profileRevision")); assertEquals(0, settings.workProfilesSnapshot().getJSONArray("profiles").length());
    }
    @Test public void pendingOriginalEffectCannotBeReplayedBySelectingAProfileAgain() throws Exception {
        RunControl original = new RunControl(); JSONObject run = start(task(), original, selection(original)); String id = run.getString("id"), old = run.getString("runId"); int epoch = run.getInt("coordinatorEpoch");
        tasks.authorizeTools(id, old, new JSONArray().put("fixture_write")); String operation = tasks.journal(id, old, epoch, original).beginOperation("fixture_write", new JSONObject().put("value", "original")); tasks.endAttempt(id, old, "paused", "Pending original effect", new JSONObject());
        var review = page.reviewForTask(tasks, id, old, epoch, new RunControl()); RunControl next = new RunControl(); var recovered = page.select(review, review.digest, next); assertThrows(Exception.class, () -> start(run, next, recovered));
        assertEquals(old, tasks.get(id, false).getString("runId")); assertTrue(tasks.get(id, false).getJSONArray("operations").toString().contains(operation)); assertTrue(next.isCancelled());
    }
    @Test public void sqlFailureLeavesNoRunAndDoesNotRecycleTheConsumedSelection() throws Exception {
        RunControl original = new RunControl(); var selected = selection(original); JSONObject created = task();
        db().execSQL("CREATE TRIGGER reject_profile_run BEFORE INSERT ON runs BEGIN SELECT RAISE(ABORT,'fixture storage failure'); END"); assertThrows(Exception.class, () -> start(created, original, selected));
        assertTrue(original.isCancelled()); try (var count = db().rawQuery("SELECT COUNT(*) FROM runs WHERE task_id=?", new String[]{created.getString("id")})) { assertTrue(count.moveToFirst()); assertEquals(0, count.getInt(0)); }
        db().execSQL("DROP TRIGGER reject_profile_run"); assertThrows(SecurityException.class, () -> start(created, original, selected));
    }
}
