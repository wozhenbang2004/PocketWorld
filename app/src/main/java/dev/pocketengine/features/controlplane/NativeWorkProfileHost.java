package dev.pocketengine.features.controlplane;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.SettingsStore;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner management/preflight and original-run route sources. Import/save/select creates no run, approval, or tool effect. */
public final class NativeWorkProfileHost {
    private final SettingsStore settings;
    private final WorkProfileResolver.Sources sources;
    public NativeWorkProfileHost(SettingsStore settings, WorkProfileResolver.Sources sources) {
        this.settings = java.util.Objects.requireNonNull(settings); this.sources = java.util.Objects.requireNonNull(sources);
    }
    public Page newOwnerPage() { return new Page(); }
    public void requireOwnedSelection(Selection selected, RunControl original) throws Exception {
        if (selected == null || selected.issuer != this) throw new SecurityException("original_native_work_profile_owner_required"); selected.checkCurrent(original);
    }
    public void claimOwnerExecutionStart(Selection selected,RunControl original)throws Exception {
        if(selected==null||selected.issuer!=this)throw new SecurityException("original_native_work_profile_owner_required");selected.claimOwnerStart(original);selected.checkCurrent(original);
    }
    /** Reuses the original run's frozen metadata. This only removes tools; it never starts/resumes a run. */
    public WorkProfileToolSelection toolSelectionForRun(dev.pocketengine.store.TaskStore tasks, String id, String run, int epoch, RunControl original) throws Exception {
        JSONObject frozen = tasks.workProfileRunSnapshot(id, run, epoch); String digest = frozen.getString("resolutionDigest");
        dev.pocketengine.core.CapabilitySnapshot.CurrentCheck binding = () -> tasks.checkWorkProfileRun(id, run, epoch, digest, original);
        WorkProfileToolSelection policy = new WorkProfileToolSelection(frozen, () -> { binding.check(); WorkProfileResolver.checkStoredAssetSourcesCurrent(frozen, sources, original); }, binding);
        policy.checkCurrent(); return policy;
    }
    /** Active original run only. This chooses a route, never a gateway/target approval or new run. */
    public RelaySource relayForRun(dev.pocketengine.store.TaskStore tasks,String task,String run,int epoch,RunControl original)throws Exception {
        if(original==null||!original.hasHostRun()||!original.matchesHostRun(task,run,epoch))throw new SecurityException("original_work_profile_relay_control_required");
        original.check();JSONObject frozen=tasks.workProfileRunSnapshot(task,run,epoch);String digest=frozen.getString("resolutionDigest");tasks.checkWorkProfileRun(task,run,epoch,digest,original);
        JSONObject target=frozen.getJSONObject("definition").getJSONObject("modelPolicies").optJSONObject("RELAY");if(target==null)return null;
        ModelConfig captured=sources.model(dev.pocketengine.runtime.models.ModelTargetRef.fromJson(target),"RELAY",original);
        WorkProfileResolver.requireStoredModelIdentity(frozen,"RELAY",captured);
        if(captured.usesAccount())throw new SecurityException("CHATGPT_PLAN_APP_ONLY");
        RelaySource selected=new RelaySource(this,tasks,task,run,epoch,original,frozen,captured);selected.checkCurrent();return selected;
    }
    /** Opaque source from this host + verified original TaskStore snapshot. No constructor or JSON
     * factory is exposed; the original gateway owner still approves the exact child/target scope. */
    public static final class RelaySource implements dev.pocketengine.features.proxy.LoopbackRelay.ConfigSource {
        private final NativeWorkProfileHost issuer;private final dev.pocketengine.store.TaskStore tasks;private final RunControl original;
        private final String task,run,frozen,digest;private final int epoch;private final long revision,pause;private final ModelConfig captured;
        private RelaySource(NativeWorkProfileHost issuer,dev.pocketengine.store.TaskStore tasks,String task,String run,int epoch,RunControl original,JSONObject frozen,ModelConfig captured)throws Exception {
            this.issuer=issuer;this.tasks=tasks;this.task=task;this.run=run;this.epoch=epoch;this.original=original;this.frozen=frozen.toString();this.captured=captured;
            digest=frozen.getString("resolutionDigest");revision=frozen.getLong("profileRevision");pause=original.pauseRevision();
        }
        private void bindingCurrent()throws Exception {
            original.check();if(original.isPaused()||original.pauseRevision()!=pause||!original.matchesHostRun(task,run,epoch))throw new SecurityException("original_work_profile_relay_run_changed");
            tasks.checkWorkProfileRun(task,run,epoch,digest,original);
        }
        public void requireOriginal(dev.pocketengine.store.TaskStore actual,RunControl control)throws Exception {
            if(actual!=tasks||control!=original)throw new SecurityException("original_work_profile_relay_source_mismatch");bindingCurrent();
        }
        public void checkCurrent()throws Exception {
            bindingCurrent();issuer.sources.checkModel(captured,"RELAY",original);
            WorkProfileResolver.checkStoredAssetSourcesCurrent(new JSONObject(frozen),issuer.sources,original);bindingCurrent();
        }
        @Override public ModelConfig get()throws Exception{checkCurrent();return captured;}
        @Override public long selectionRevision()throws Exception{bindingCurrent();return revision;}
        public JSONObject view()throws Exception {
            bindingCurrent();JSONObject saved=new JSONObject(frozen);
            return new JSONObject().put("sourceKind","frozen_original_work_profile_RELAY").put("profileId",saved.getString("profileId")).put("profileRevision",revision)
                .put("resolutionDigest",digest).put("model",saved.getJSONObject("modelPolicies").getJSONObject("RELAY"))
                .put("purpose","RELAY").put("audience","EXTERNAL_RELAY").put("configurationApplied",false).put("runtimeVerified",false);
        }
    }
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
        /** Read-through owner choices, never a second asset/credential store or an implicit approval. */
        public JSONObject choices(RunControl observation) throws Exception {
            final long observed; synchronized(this) { current(false); observed = generation; }
            JSONObject data = sources.choices(observation); synchronized(this) { current(false); if(observed != generation) throw new SecurityException("work_profile_owner_page_changed"); return data; }
        }
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
            JSONObject original = runId.isEmpty()?tasks.pendingWorkProfileTaskSnapshot(taskId):tasks.workProfileRunSnapshot(taskId, runId, epoch);
            WorkProfile row = WorkProfile.fromJson(original.getJSONObject("definition"));
            WorkProfileResolver.Resolution resolution = WorkProfileResolver.resolve(row, sources, observation);
            String retained = WorkProfile.fingerprint(original);
            synchronized (this) {
                current(true); if (request != generation) throw new SecurityException("work_profile_review_superseded");
                pending = new Review(this, generation, resolution, () -> { if (!retained.equals(WorkProfile.fingerprint(runId.isEmpty()?tasks.pendingWorkProfileTaskSnapshot(taskId):tasks.workProfileRunSnapshot(taskId, runId, epoch)))) throw new SecurityException("original_work_profile_run_changed"); }, new TaskReview(taskId,runId,epoch,retained)); return pending;
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
                review.consumed = true; pending = null; return new Selection(NativeWorkProfileHost.this, review.resolution, observation, review.taskReview);
            }
        }
        @Override public synchronized void close() { alive = false; foreground = false; generation++; pending = null; }
    }
    private record TaskReview(String taskId,String runId,int epoch,String snapshotDigest) { }
    @FunctionalInterface private interface CurrentDefinition { void check() throws Exception; }
    public static final class Review {
        public final String digest;
        private final Page page;
        private final long generation;
        private final WorkProfileResolver.Resolution resolution;
        private final CurrentDefinition currentDefinition;
        private final TaskReview taskReview;
        private boolean consumed;
        private Review(Page page, long generation, WorkProfileResolver.Resolution resolution, CurrentDefinition currentDefinition) throws Exception { this(page,generation,resolution,currentDefinition,null); }
        private Review(Page page,long generation,WorkProfileResolver.Resolution resolution,CurrentDefinition currentDefinition,TaskReview taskReview)throws Exception { this.page=page;this.generation=generation;this.resolution=resolution;this.currentDefinition=currentDefinition;this.taskReview=taskReview;digest=resolution.view().getString("resolutionDigest"); }
        public JSONObject view() throws Exception { return resolution.view(); }
    }
    /** Transient exact model configs stay in memory; public snapshot contains no key, secret, or grant. */
    public static final class Selection {
        private final NativeWorkProfileHost issuer;
        private final WorkProfileResolver.Resolution resolution;
        private final RunControl original;
        private final long admissionPauseRevision;
        private String reservedTask, boundRun, intentTask;
        private final TaskReview taskReview;
        private boolean ownerStartClaimed;
        private int boundEpoch;
        private Selection(NativeWorkProfileHost issuer, WorkProfileResolver.Resolution resolution, RunControl original,TaskReview taskReview) { this.issuer = issuer; this.resolution = resolution; this.original = original;this.taskReview=taskReview;admissionPauseRevision = original.pauseRevision(); }
        public JSONObject snapshot() throws Exception { return resolution.view().put("definition", profile().toJson()); }
        public WorkProfile profile() { return resolution.profile(); }
        public Map<String, ModelConfig> models() { return resolution.models(); }
        public void checkCurrent(RunControl observation) throws Exception { if (observation != original) throw new SecurityException("work_profile_control_mismatch"); observation.check(); resolution.checkCurrent(observation); }
        private synchronized void claimOwnerStart(RunControl control)throws Exception {
            if(control!=original||ownerStartClaimed||control.hasHostRun()||control.isPaused()||control.pauseRevision()!=admissionPauseRevision)throw new SecurityException("work_profile_owner_start_already_used_or_changed");control.check();ownerStartClaimed=true;
        }
        /** Non-authority pending-goal metadata; no run/operation is created by reading this. */
        public synchronized JSONObject metadataForNewOwnerIntent(String taskId,RunControl control)throws Exception {
            if(control!=original||intentTask!=null||taskReview!=null||reservedTask!=null||boundRun!=null||control.hasHostRun()||control.isPaused()||control.pauseRevision()!=admissionPauseRevision)throw new SecurityException("work_profile_pending_goal_control_changed");control.check();if(taskId==null||!taskId.matches("[a-f0-9-]{36}"))throw new SecurityException("original_work_profile_task_required");intentTask=taskId;return snapshot();
        }
        /** Pure metadata check inside original SQL admission; existing Profile tasks require that exact task review. */
        public synchronized void requireTaskAdmission(String taskId,String runId,int epoch,String retainedDigest)throws Exception {
            if(intentTask!=null&&!intentTask.equals(taskId))throw new SecurityException("work_profile_pending_goal_mismatch");
            if(taskReview!=null){if(!taskReview.taskId.equals(taskId)||!taskReview.runId.equals(runId)||taskReview.epoch!=epoch||!taskReview.snapshotDigest.equals(retainedDigest))throw new SecurityException("work_profile_original_task_review_changed");}
            else if(retainedDigest!=null&&!(taskId.equals(intentTask)&&runId.isEmpty()))throw new SecurityException("work_profile_original_task_review_required");
        }
        /** Native TaskStore admission only: consumes this owner selection even if admission later fails. */
        public synchronized void reserveForTask(String taskId, RunControl control) throws Exception {
            if (control != original || reservedTask != null || control.hasHostRun() || control.isPaused() || control.pauseRevision() != admissionPauseRevision) throw new SecurityException("work_profile_selection_already_used_or_wrong_control");
            control.check(); if (taskId == null || !taskId.matches("[a-f0-9-]{36}")) throw new SecurityException("original_work_profile_task_required"); reservedTask = taskId;
        }
        /** Immutable data under TaskStore's monitor, with no source lookup or module lock. */
        public synchronized JSONObject metadataForReservedTask(String taskId) throws Exception {
            requireAdmissionCurrent(taskId, original); return snapshot();
        }
        public synchronized void requireAdmissionCurrent(String taskId, RunControl control) throws Exception {
            if (control != original || !taskId.equals(reservedTask) || boundRun != null || control.hasHostRun() || control.isPaused() || control.pauseRevision() != admissionPauseRevision) throw new SecurityException("work_profile_selection_admission_changed"); control.check();
        }
        public synchronized void bindNativeRun(String taskId, String runId, int epoch, RunControl control) throws Exception {
            control.check(); if (control != original || !taskId.equals(reservedTask) || boundRun != null || control.isPaused() || control.pauseRevision() != admissionPauseRevision || !control.matchesHostRun(taskId, runId, epoch)) throw new SecurityException("work_profile_native_run_binding_required");
            boundRun = runId; boundEpoch = epoch;
        }
        public synchronized void requireBoundRun(String taskId, String runId, int epoch, RunControl control) throws Exception {
            if (control != original || !taskId.equals(reservedTask) || !runId.equals(boundRun) || epoch != boundEpoch || !control.matchesHostRun(taskId, runId, epoch)) throw new SecurityException("work_profile_not_original_bound_run");
        }
    }
}
