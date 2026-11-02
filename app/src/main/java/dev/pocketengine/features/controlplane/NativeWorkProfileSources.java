package dev.pocketengine.features.controlplane;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.store.SettingsStore;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Application-owned read-through adapter. Original stores and execution gates remain authoritative. */
public final class NativeWorkProfileSources implements WorkProfileResolver.Sources {
    @FunctionalInterface public interface TargetFacts {
        JSONObject current(JSONObject exactOriginalReference, RunControl observation) throws Exception;
    }
    private final SettingsStore settings;
    private final FeatureHost features;
    private final NativePluginHost plugins;
    private final Set<String> capabilities;
    private final TargetFacts targets;
    public NativeWorkProfileSources(SettingsStore settings, FeatureHost features, NativePluginHost plugins, Set<String> capabilities, TargetFacts targets) {
        this.settings = java.util.Objects.requireNonNull(settings); this.features = java.util.Objects.requireNonNull(features);
        this.plugins = java.util.Objects.requireNonNull(plugins); this.capabilities = Set.copyOf(capabilities); this.targets = targets;
    }
    @Override public ModelConfig model(ModelTargetRef reference, String purpose, RunControl observation) throws Exception {
        observation.check(); ModelConfig value = settings.captureWorkProfileModel(reference, purpose, "RELAY".equals(purpose) ? "EXTERNAL_RELAY" : "APP_OWNER"); checkModel(value, purpose, observation); observation.check(); return value;
    }
    @Override public void checkModel(ModelConfig captured, String purpose, RunControl observation) throws Exception {
        observation.check();
        if (captured.routePlan == null) { requireCandidate(captured, observation); return; }
        boolean available = false;
        for (var candidate : captured.routePlan.candidates) {
            if (candidate.config() == null || !candidate.exclusion().isEmpty()) continue;
            try { settings.requireModelGroupAdmission(captured.routePlan, candidate.candidateId(), candidate.config()); requireCandidate(candidate.config(), observation); available = true; }
            catch (Exception revoked) { observation.check(); }
        }
        if (!available) throw new SecurityException("original_profile_model_candidates_unavailable");
        observation.check();
    }
    private void requireCandidate(ModelConfig captured, RunControl observation) throws Exception {
        observation.check();
        if (captured.usesAccount()) {
            if (!dev.pocketengine.runtime.ModelGateway.accountSelectionContinues(observation, captured.accountModel, captured.accountModel)) throw new SecurityException("original_profile_account_unavailable");
        } else {
            if (!settings.matchesApiCatalogRoute(captured)) throw new SecurityException("original_profile_api_binding_revoked");
            dev.pocketengine.runtime.models.ModelRequestPolicy.resolve(captured);
        }
        observation.check();
    }
    @Override public JSONObject skill(String id, RunControl observation) throws Exception {
        observation.check(); EngineModule module = features.module("skills");
        if (!(module instanceof dev.pocketengine.features.skills.SkillsModule)) throw new IllegalStateException("original_skill_store_not_mounted");
        JSONObject value = module.handle("get", new JSONObject().put("id", id).put("scope", new JSONObject().put("ownerId", "owner").put("spaceId", "private"))).getJSONObject("skill"); observation.check(); return value;
    }
    @Override public JSONObject plugin(String id, RunControl observation) throws Exception {
        observation.check(); EngineModule module = features.module("plugins");
        if (!(module instanceof dev.pocketengine.features.plugins.PluginsModule)) throw new IllegalStateException("original_plugin_store_not_mounted");
        JSONObject value = find(module.snapshot().getJSONArray("plugins"), id); observation.check(); return value;
    }
    @Override public JSONObject extension(String id, RunControl observation) throws Exception {
        return find(plugins.approvedExtensionReferences(observation), id);
    }
    @Override public JSONObject team(String id, RunControl observation) throws Exception {
        observation.check(); JSONObject value = features.teamDefinition(id); observation.check(); return value;
    }
    @Override public JSONObject target(JSONObject exactOriginalReference, RunControl observation) throws Exception {
        observation.check(); if (targets == null) return new JSONObject().put("supported", false);
        JSONObject value = targets.current(new JSONObject(exactOriginalReference.toString()), observation); observation.check(); return value;
    }
    @Override public Set<String> hostCapabilities() { return capabilities; }
    static JSONObject find(JSONArray rows, String id) throws Exception {
        for (int i = 0; i < rows.length(); i++) if (id.equals(rows.getJSONObject(i).optString("id"))) return new JSONObject(rows.getJSONObject(i).toString()); return null;
    }
}
