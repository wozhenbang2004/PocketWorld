package dev.pocketengine.features.controlplane;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-through composition of original native stores. It never installs, grants, executes, or retries. */
public final class WorkProfileResolver {
    public interface Sources {
        ModelConfig model(ModelTargetRef reference, String purpose, RunControl observation) throws Exception;
        void checkModel(ModelConfig captured, String purpose, RunControl observation) throws Exception;
        /** Existing source's public native facts, not content from a model or imported Profile. */
        JSONObject skill(String id, RunControl observation) throws Exception;
        JSONObject plugin(String id, RunControl observation) throws Exception;
        JSONObject extension(String id, RunControl observation) throws Exception;
        JSONObject team(String id, RunControl observation) throws Exception;
        JSONObject target(JSONObject exactOriginalReference, RunControl observation) throws Exception;
        Set<String> hostCapabilities();
    }
    public static final class Resolution {
        private final WorkProfile definition;
        private final Sources sources;
        private final String json;
        private final Map<String, ModelConfig> models;
        private Resolution(WorkProfile definition, Sources sources, JSONObject data, Map<String, ModelConfig> models) throws Exception {
            this.definition = definition; this.sources = sources; this.models = Map.copyOf(models);
            data.put("resolutionDigest", WorkProfile.fingerprint(data)); json = data.toString();
        }
        public JSONObject view() throws Exception { return new JSONObject(json); }
        public WorkProfile profile() { return definition; }
        public Map<String, ModelConfig> models() { return models; }
        public void requireReady() throws Exception { if (!view().getBoolean("ready")) throw new SecurityException("work_profile_requires_review"); }
        /** Revocation and source drift tighten the frozen run; they never replace it with a new selection. */
        public void checkCurrent(RunControl observation) throws Exception {
            observation.check(); requireReady();
            Sources frozenModels = new Sources() {
                public ModelConfig model(ModelTargetRef reference, String purpose, RunControl control) throws Exception { ModelConfig captured = models.get(purpose); if(captured == null) throw new SecurityException("frozen_profile_model_missing"); sources.checkModel(captured, purpose, control); return captured; }
                public void checkModel(ModelConfig captured, String purpose, RunControl control) throws Exception { sources.checkModel(captured, purpose, control); }
                public JSONObject skill(String id, RunControl control) throws Exception { return sources.skill(id, control); }
                public JSONObject plugin(String id, RunControl control) throws Exception { return sources.plugin(id, control); }
                public JSONObject extension(String id, RunControl control) throws Exception { return sources.extension(id, control); }
                public JSONObject team(String id, RunControl control) throws Exception { return sources.team(id, control); }
                public JSONObject target(JSONObject reference, RunControl control) throws Exception { return sources.target(reference, control); }
                public Set<String> hostCapabilities() { return sources.hostCapabilities(); }
            };
            Resolution current = resolve(definition, frozenModels, observation); current.requireReady();
            if (!view().getString("resolutionDigest").equals(current.view().getString("resolutionDigest"))) throw new SecurityException("work_profile_sources_changed");
            observation.check();
        }
    }
    private interface Read { JSONObject get() throws Exception; }
    private interface Check { JSONObject verify(JSONObject actual) throws Exception; }
    private WorkProfileResolver() { }
    public static Resolution resolve(WorkProfile profile, Sources sources, RunControl observation) throws Exception {
        java.util.Objects.requireNonNull(profile); java.util.Objects.requireNonNull(sources); observation.check();
        JSONObject definition = profile.toJson(), capturedModels = new JSONObject(); JSONArray rows = new JSONArray();
        Map<String, ModelConfig> models = new LinkedHashMap<>();
        JSONObject policies = definition.getJSONObject("modelPolicies");
        for (String purpose : WorkProfile.keys(policies)) {
            JSONObject requested = policies.getJSONObject(purpose); observation.check();
            try {
                ModelConfig captured = sources.model(ModelTargetRef.fromJson(requested), purpose, observation); observation.check();
                if (captured == null) throw new IllegalStateException("model_unavailable");
                JSONObject identity = modelIdentity(captured); models.put(purpose, captured); capturedModels.put(purpose, identity);
                rows.put(row("model", purpose, false, "ready", identity));
            } catch (Exception unavailable) { observation.check(); rows.put(row("model", purpose, false, "unavailable", null)); }
        }
        for (String section : new String[]{"instructionRefs", "skillRefs"}) {
            JSONArray selected = definition.getJSONArray(section);
            for (int i = 0; i < selected.length(); i++) {
                JSONObject ref = selected.getJSONObject(i); String id = ref.getString("id");
                rows.put(inspect(section.equals("instructionRefs") ? "instruction" : "skill", id, ref, observation,
                        () -> sources.skill(id, observation), actual -> {
                            exact(actual, ref, "id", "revision", "digest");
                            if (!actual.optBoolean("contentVerified") || !actual.optBoolean("active") || !"active".equals(actual.optString("status"))) throw new Missing("skill_not_active");
                            JSONArray required = actual.getJSONArray("requiredCapabilities");
                            for (int n = 0; n < required.length(); n++) if (!sources.hostCapabilities().contains(required.getString(n))) throw new Missing("host_capability_missing");
                            return pick(actual, "id", "revision", "digest", "executionGeneration", "expiresAt");
                        }));
            }
        }
        JSONArray plugins = definition.getJSONArray("pluginRefs");
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject ref = plugins.getJSONObject(i); String id = ref.getString("id");
            rows.put(inspect("plugin", id, ref, observation, () -> sources.plugin(id, observation), actual -> {
                exact(actual, ref, "id", "installationId", "stateRevision");
                if (!WorkProfile.fingerprint(actual.getJSONObject("manifest")).equals(ref.getString("manifestDigest"))) throw new Missing("revision_changed");
                if (!actual.optBoolean("enabled")) throw new Missing("plugin_disabled");
                JSONArray requested = ref.getJSONArray("tools"), granted = actual.optJSONArray("grants"), tools = actual.getJSONObject("manifest").getJSONArray("tools");
                for (int n = 0; n < requested.length(); n++) {
                    String name = requested.getString(n); JSONObject found = null;
                    for (int t = 0; t < tools.length(); t++) if (name.equals(tools.getJSONObject(t).getString("name"))) found = tools.getJSONObject(t);
                    if (found == null) throw new Missing("tool_missing");
                    if (!contains(granted, "tool:" + name)) throw new Missing("tool_grant_required");
                    if ("mcp".equals(found.optString("kind")) && Set.of("needs_authorization", "needs_discovery").contains(actual.optString("mcpStatus"))) throw new Missing("mcp_revalidation_required");
                }
                return pick(actual, "id", "installationId", "stateRevision", "grantsDigest").put("manifestDigest", ref.getString("manifestDigest")).put("tools", new JSONArray(requested.toString()));
            }));
        }
        JSONArray extensions = definition.getJSONArray("extensionRefs");
        for (int i = 0; i < extensions.length(); i++) {
            JSONObject ref = extensions.getJSONObject(i); String id = ref.getString("id");
            rows.put(inspect("extension", id, ref, observation, () -> sources.extension(id, observation), actual -> {
                exact(actual, ref, "id", "installationId", "version", "approvalRevision", "sourceSha256");
                if (!actual.optBoolean("enabled") || !actual.optBoolean("approved")) throw new Missing("extension_not_approved");
                return pick(actual, "id", "installationId", "version", "approvalRevision", "sourceSha256");
            }));
        }
        if (!definition.isNull("teamTemplateRef")) {
            JSONObject ref = definition.getJSONObject("teamTemplateRef"); String id = ref.getString("id");
            rows.put(inspect("team", id, ref, observation, () -> sources.team(id, observation), actual -> {
                if (!id.equals(actual.getString("id")) || !ref.getString("digest").equals(WorkProfile.fingerprint(actual))) throw new Missing("revision_changed");
                return new JSONObject().put("id", id).put("digest", ref.getString("digest"));
            }));
        }
        JSONArray targets = definition.getJSONArray("targetBindings");
        for (int i = 0; i < targets.length(); i++) {
            JSONObject ref = targets.getJSONObject(i); String id = WorkProfile.targetKey(ref), kind = ref.getString("kind");
            rows.put(inspect("target:" + kind, id, ref, observation, () -> sources.target(new JSONObject(ref.toString()), observation), actual -> {
                if (!actual.optBoolean("supported")) throw new Missing("target_not_supported");
                JSONObject binding = new JSONObject();
                for (String field : WorkProfile.keys(ref)) if (!field.equals("optional")) { exact(actual, ref, field); binding.put(field, ref.get(field)); }
                return binding;
            }));
        }
        boolean ready = true; int omitted = 0;
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.getJSONObject(i); if (!"ready".equals(row.getString("status"))) { if (row.getBoolean("optional")) omitted++; else ready = false; } }
        observation.check();
        JSONObject result = new JSONObject().put("format", "pocketworld.resolved-work-profile.v1").put("profileId", profile.id).put("profileRevision", profile.revision)
                .put("profileDigest", profile.digest()).put("items", rows).put("modelPolicies", capturedModels).put("resourcePreferences", definition.getJSONObject("resourcePreferences"))
                .put("ready", ready).put("sourceChecksOnly", true).put("configurationApplied", false).put("runtimeVerified", false).put("optionalOmissions", omitted).put("executionAuthorized", false).put("permissionsChanged", false).put("executed", false);
        return new Resolution(profile, sources, result, models);
    }
    private static JSONObject inspect(String kind, String id, JSONObject ref, RunControl observation, Read read, Check check) throws Exception {
        observation.check(); boolean optional = ref.getBoolean("optional");
        try { JSONObject actual = read.get(); observation.check(); if (actual == null) throw new Missing("source_missing"); return row(kind, id, optional, "ready", check.verify(actual)); }
        catch (Missing unavailable) { observation.check(); return row(kind, id, optional, unavailable.code, null); }
        catch (Exception unavailable) { observation.check(); return row(kind, id, optional, "source_unavailable", null); }
    }
    private static JSONObject modelIdentity(ModelConfig captured) throws Exception {
        JSONObject result = new JSONObject().put("model", captured.model).put("configurationDigest", ModelRoutePlan.configurationDigest(captured));
        if (captured.capabilityBinding != null) result.put("binding", captured.capabilityBinding.toJson());
        if (captured.usesAccount()) result.put("account", captured.accountModel.toJson());
        if (captured.routePlan != null) {
            JSONObject route = captured.routePlan.toJson(); route.remove("snapshotId"); result.put("route", route);
        }
        return result;
    }
    private static JSONObject row(String kind, String id, boolean optional, String status, JSONObject binding) throws Exception {
        return new JSONObject().put("kind", kind).put("id", id).put("optional", optional).put("status", status).put("included", "ready".equals(status)).put("binding", binding == null ? JSONObject.NULL : binding);
    }
    private static void exact(JSONObject actual, JSONObject expected, String... fields) throws Exception {
        for (String field : fields) if (!actual.has(field) || !String.valueOf(actual.get(field)).equals(String.valueOf(expected.get(field)))) throw new Missing("revision_changed");
    }
    private static JSONObject pick(JSONObject actual, String... fields) throws Exception {
        JSONObject result = new JSONObject(); for (String field : fields) if (actual.has(field)) result.put(field, actual.get(field)); return result;
    }
    private static boolean contains(JSONArray rows, String value) { for (int i = 0; rows != null && i < rows.length(); i++) if (value.equals(rows.optString(i))) return true; return false; }
    private static final class Missing extends Exception { final String code; Missing(String code) { this.code = code; } }
}
