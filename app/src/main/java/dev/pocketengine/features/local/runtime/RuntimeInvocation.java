package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import org.json.JSONObject;

/** Frozen reviewed source plus the retained original native authority; no JSON approval flags. */
public final class RuntimeInvocation {
    private final RuntimeExecutionRef reference;
    private final JSONObject source;
    private final RunControl original;
    private final ProjectRuntime.LiveGate gate;
    private final long pauseRevision;
    private final RuntimeWorkspace workspace;

    public RuntimeInvocation(JSONObject exact,JSONObject nativeBinding,JSONObject frozenSourceSnapshot,
            RunControl original,ProjectRuntime.LiveGate liveGate) throws Exception {
        this(exact,nativeBinding,frozenSourceSnapshot,original,liveGate,null);
    }
    public RuntimeInvocation(JSONObject exact,JSONObject nativeBinding,JSONObject frozenSourceSnapshot,
            RunControl original,ProjectRuntime.LiveGate liveGate,RuntimeWorkspace workspace) throws Exception {
        reference=RuntimeExecutionRef.fromHost(exact,nativeBinding);
        source=ProjectSources.copy(frozenSourceSnapshot);
        JSONObject actual=ProjectSources.describe(source);
        if(!reference.projectId().equals(actual.getString("projectId"))||!reference.checkoutId().equals(actual.getString("checkoutId"))
                ||!reference.sourceDigest().equals(actual.getString("sourceDigest")))
            throw new SecurityException("runtime_reviewed_source_mismatch");
        this.original=java.util.Objects.requireNonNull(original);gate=java.util.Objects.requireNonNull(liveGate);this.workspace=workspace;
        pauseRevision=original.pauseRevision();checkCurrent();
    }
    public RuntimeExecutionRef executionRef() { return reference; }
    public JSONObject exact() throws Exception { return reference.exact(); }
    public JSONObject binding() throws Exception { return reference.binding(); }
    public JSONObject sourceSnapshot() throws Exception { return ProjectSources.copy(source); }
    public RunControl originalControl() { return original; }
    public RuntimeWorkspace workspace() {
        if(workspace==null)throw new SecurityException("runtime_native_active_workspace_required");
        return workspace;
    }
    public void checkCurrent() throws Exception {
        reference.checkControl(original);
        if(original.isPaused()||original.pauseRevision()!=pauseRevision)throw new SecurityException("runtime_original_control_paused");
        gate.check();reference.checkControl(original);
        if(original.isPaused()||original.pauseRevision()!=pauseRevision)throw new SecurityException("runtime_original_control_paused");
    }
}
