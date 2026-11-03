package dev.pocketengine.features.controlplane;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.remote.RemoteModule;
import dev.pocketengine.features.ssh.SshModule;
import org.json.JSONObject;

/** Routes exact references to the already mounted original modules; no target registry or executor. */
public final class NativeWorkProfileTargetSources implements NativeWorkProfileSources.TargetFacts {
    private final FeatureHost features;
    public NativeWorkProfileTargetSources(FeatureHost features) { this.features = java.util.Objects.requireNonNull(features); }
    @Override public JSONObject current(JSONObject reference, RunControl observation) throws Exception {
        observation.check();
        return switch (reference.getString("kind")) {
            case "remote_harness" -> features.module("remote") instanceof RemoteModule remote ? remote.workProfileTargetFacts(reference, observation) : unavailable("remote_source_not_mounted");
            case "ssh" -> features.module("ssh") instanceof SshModule ssh ? ssh.workProfileTargetFacts(reference, observation) : unavailable("ssh_source_not_mounted");
            case "local_runtime" -> unavailable("runtime_target_source_not_mounted");
            default -> throw new SecurityException("unknown_original_work_profile_target");
        };
    }
    @Override public void checkCurrent(JSONObject reference, JSONObject capturedPublicBinding, RunControl observation) throws Exception {
        observation.check();
        switch (reference.getString("kind")) {
            case "remote_harness" -> { if (!(features.module("remote") instanceof RemoteModule remote)) throw new SecurityException("remote_source_not_mounted"); remote.checkWorkProfileTargetCurrent(reference, capturedPublicBinding, observation); }
            case "ssh" -> { if (!(features.module("ssh") instanceof SshModule ssh)) throw new SecurityException("ssh_source_not_mounted"); ssh.checkWorkProfileTargetCurrent(reference, capturedPublicBinding, observation); }
            default -> throw new SecurityException("work_profile_target_current_guard_not_mounted");
        }
        observation.check();
    }
    private static JSONObject unavailable(String reason) throws Exception {
        return new JSONObject().put("supported", false).put("sourceEvidence", new JSONObject().put("sourceState", "not_mounted").put("reasonCode", reason)
                .put("connectionVerified", false).put("configurationApplied", false).put("runtimeVerified", false));
    }
}
