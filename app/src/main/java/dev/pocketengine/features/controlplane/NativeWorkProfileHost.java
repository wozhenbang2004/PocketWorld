package dev.pocketengine.features.controlplane;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.SettingsStore;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner management/preflight only. Import/save/select creates no run, approval, or tool effect. */
public final class NativeWorkProfileHost {
    private final SettingsStore settings;
    private final WorkProfileResolver.Sources sources;
    public NativeWorkProfileHost(SettingsStore settings, WorkProfileResolver.Sources sources) {
        this.settings = java.util.Objects.requireNonNull(settings); this.sources = java.util.Objects.requireNonNull(sources);
    }
    public Page newOwnerPage() { return new Page(); }
    private WorkProfile stored(String id, long revision) throws Exception {
        JSONArray rows = settings.workProfilesSnapshot().getJSONArray("profiles");
        for (int i = 0; i < rows.length(); i++) {
            WorkProfile row = WorkProfile.fromJson(rows.getJSONObject(i));
            if (id.equals(row.id)) { if (row.revision != revision) throw new SecurityException("work_profile_definition_changed"); return row; }
        }
        throw new SecurityException("work_profile_definition_missing");
    }
    public final class Page implements AutoCloseable {
        private boolean alive = true, foreground;
        private Review pending;
        private long generation;
        private Page() { }
        public synchronized void setForeground(boolean value) { if (alive && foreground != value) { foreground = value; generation++; pending = null; } }
        private void current(boolean editing) { if (!alive || editing && !foreground) throw new SecurityException("work_profile_owner_page_not_current"); }
        public synchronized JSONObject snapshot() throws Exception { current(false); return settings.workProfilesSnapshot().put("executionAuthorized", false).put("permissionsChanged", false); }
        public synchronized JSONObject save(String id, long revision, String name, JSONObject definition) throws Exception {
            current(true); generation++; pending = null; return settings.saveWorkProfile(id, revision, name, definition);
        }
        public synchronized JSONObject remove(String id, long revision) throws Exception {
            current(true); generation++; pending = null; return settings.removeWorkProfile(id, revision);
        }
        public Review review(String id, long revision, RunControl observation) throws Exception {
            final long request; synchronized (this) { current(true); pending = null; request = ++generation; }
            WorkProfile row = stored(id, revision); WorkProfileResolver.Resolution resolution = WorkProfileResolver.resolve(row, sources, observation);
            synchronized (this) { current(true); if(request != generation) throw new SecurityException("work_profile_review_superseded"); if (!row.digest().equals(stored(id, revision).digest())) throw new SecurityException("work_profile_definition_changed"); pending = new Review(this, generation, resolution, () -> { if (!row.digest().equals(stored(id, revision).digest())) throw new SecurityException("work_profile_definition_changed"); }); return pending; }
        }
        /** Explicit recovery of the original run's exact references, even if its reusable definition was removed.
         * Missing/changed sources remain visible blockers; this does not restart or replay the old run. */
        public Review reviewForTask(dev.pocketengine.store.TaskStore tasks, String taskId, String runId, int epoch, RunControl observation) throws Exception {
            final long request; synchronized (this) { current(true); pending = null; request = ++generation; }
            JSONObject original = tasks.workProfileRunSnapshot(taskId, runId, epoch);
            WorkProfile row = WorkProfile.fromJson(original.getJSONObject("definition"));
            WorkProfileResolver.Resolution resolution = WorkProfileResolver.resolve(row, sources, observation);
            String retained = WorkProfile.fingerprint(original);
            synchronized (this) {
                current(true); if (request != generation) throw new SecurityException("work_profile_review_superseded");
                pending = new Review(this, generation, resolution, () -> { if (!retained.equals(WorkProfile.fingerprint(tasks.workProfileRunSnapshot(taskId, runId, epoch)))) throw new SecurityException("original_work_profile_run_changed"); }); return pending;
            }
        }
        /** Opaque native result. The original TaskStore must separately bind it to the real task/run. */
        public Selection select(Review review, String digest, RunControl observation) throws Exception {
            synchronized (this) {
                current(true); if (review == null || review.page != this || review != pending || review.generation != generation || review.consumed || !review.digest.equals(digest)) throw new SecurityException("current_work_profile_review_required");
            }
            review.resolution.requireReady(); review.resolution.checkCurrent(observation);
            synchronized (this) {
                current(true); if (pending != review || review.generation != generation || review.consumed) throw new SecurityException("current_work_profile_review_required");
                review.currentDefinition.check();
                review.consumed = true; pending = null; return new Selection(review.resolution, observation);
            }
        }
        @Override public synchronized void close() { alive = false; foreground = false; generation++; pending = null; }
    }
    @FunctionalInterface private interface CurrentDefinition { void check() throws Exception; }
    public static final class Review {
        public final String digest;
        private final Page page;
        private final long generation;
        private final WorkProfileResolver.Resolution resolution;
        private final CurrentDefinition currentDefinition;
        private boolean consumed;
        private Review(Page page, long generation, WorkProfileResolver.Resolution resolution, CurrentDefinition currentDefinition) throws Exception { this.page = page; this.generation = generation; this.resolution = resolution; this.currentDefinition = currentDefinition; digest = resolution.view().getString("resolutionDigest"); }
        public JSONObject view() throws Exception { return resolution.view(); }
    }
    /** Transient exact model configs stay in memory; public snapshot contains no key, secret, or grant. */
    public static final class Selection {
        private final WorkProfileResolver.Resolution resolution;
        private final RunControl original;
        private String reservedTask, boundRun;
        private int boundEpoch;
        private Selection(WorkProfileResolver.Resolution resolution, RunControl original) { this.resolution = resolution; this.original = original; }
        public JSONObject snapshot() throws Exception { return resolution.view().put("definition", profile().toJson()); }
        public WorkProfile profile() { return resolution.profile(); }
        public Map<String, ModelConfig> models() { return resolution.models(); }
        public void checkCurrent(RunControl observation) throws Exception { if (observation != original) throw new SecurityException("work_profile_control_mismatch"); observation.check(); resolution.checkCurrent(observation); }
        /** Native TaskStore admission only: consumes this owner selection even if admission later fails. */
        public synchronized void reserveForTask(String taskId, RunControl control) throws Exception {
            if (control != original || reservedTask != null || control.hasHostRun() || control.isPaused()) throw new SecurityException("work_profile_selection_already_used_or_wrong_control");
            control.check(); if (taskId == null || !taskId.matches("[a-f0-9-]{36}")) throw new SecurityException("original_work_profile_task_required"); reservedTask = taskId;
        }
        /** Immutable data under TaskStore's monitor, with no source lookup or module lock. */
        public synchronized JSONObject metadataForReservedTask(String taskId) throws Exception {
            if (!taskId.equals(reservedTask) || boundRun != null) throw new SecurityException("work_profile_selection_not_reserved_for_task"); return snapshot();
        }
        public synchronized void bindNativeRun(String taskId, String runId, int epoch, RunControl control) throws Exception {
            if (control != original || !taskId.equals(reservedTask) || boundRun != null || !control.matchesHostRun(taskId, runId, epoch)) throw new SecurityException("work_profile_native_run_binding_required");
            boundRun = runId; boundEpoch = epoch;
        }
        public synchronized void requireBoundRun(String taskId, String runId, int epoch, RunControl control) throws Exception {
            if (control != original || !taskId.equals(reservedTask) || !runId.equals(boundRun) || epoch != boundEpoch || !control.matchesHostRun(taskId, runId, epoch)) throw new SecurityException("work_profile_not_original_bound_run");
        }
    }
}
