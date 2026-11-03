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
        /** Native startup default, never MAIN or a later global lookup. Missing adapters stay explicit. */
        default ModelConfig globalDefault(RunControl observation) throws Exception { observation.check(); return null; }
        /** Existing source's public native facts, not content from a model or imported Profile. */
        JSONObject skill(String id, RunControl observation) throws Exception;
        /** Native project/task adapters only. Imported Profile JSON cannot supply this scope. */
        default Sources forProject(dev.pocketengine.features.local.project.ProjectOwnerWorkflows.ProfileProjectSelection selected,RunControl observation)throws Exception {
            observation.check();if(selected!=null)throw new SecurityException("native_profile_project_source_not_mounted");return this;
        }
        default Sources forTask(dev.pocketengine.store.TaskStore tasks,String id,RunControl observation)throws Exception {
            observation.check();JSONObject task=tasks.get(id,false);if(task!=null&&task.has("developmentContext"))throw new SecurityException("native_profile_task_scope_not_mounted");return this;
        }
        default JSONObject skillScope(RunControl observation)throws Exception {observation.check();return null;}
        /** Compatibility for old native snapshots: global sources only, never a new project selection. */
        default JSONObject legacyGlobalSkill(String id,RunControl observation)throws Exception {return skill(id,observation);}
        default JSONObject referencePackage(JSONObject exactReference,RunControl observation)throws Exception{throw new SecurityException("原技能文本投射读取未挂载，请保留编辑");}
        /** Old native snapshots retain their global text sources inside a project task. */
        default JSONObject legacyGlobalReferencePackage(JSONObject exactReference,RunControl observation)throws Exception{return referencePackage(exactReference,observation);}
        JSONObject plugin(String id, RunControl observation) throws Exception;
        JSONObject extension(String id, RunControl observation) throws Exception;
        JSONObject team(String id, RunControl observation) throws Exception;
        JSONObject target(JSONObject exactOriginalReference, RunControl observation) throws Exception;
        default void checkTarget(JSONObject exactOriginalReference, JSONObject capturedPublicBinding, RunControl observation) throws Exception { throw new SecurityException("target_current_guard_not_mounted"); }
        default JSONObject choices(RunControl observation) throws Exception { observation.check(); return new JSONObject().put("available", false).put("reasonCode", "native_choice_source_not_mounted"); }
        Set<String> hostCapabilities() throws Exception;
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
                public ModelConfig globalDefault(RunControl control) throws Exception { ModelConfig captured=models.get("_GLOBAL"); if(captured!=null)sources.checkModel(captured,"DEFAULT",control);return captured; }
                public JSONObject skill(String id, RunControl control) throws Exception { return sources.skill(id, control); }
                public JSONObject skillScope(RunControl control)throws Exception{return sources.skillScope(control);}
                public JSONObject legacyGlobalSkill(String id,RunControl control)throws Exception{return sources.legacyGlobalSkill(id,control);}
                public JSONObject plugin(String id, RunControl control) throws Exception { return sources.plugin(id, control); }
                public JSONObject extension(String id, RunControl control) throws Exception { return sources.extension(id, control); }
                public JSONObject team(String id, RunControl control) throws Exception { return sources.team(id, control); }
                public JSONObject target(JSONObject reference, RunControl control) throws Exception {
                    return frozenTarget(sources, view(), reference, control);
                }
                public void checkTarget(JSONObject reference, JSONObject captured, RunControl control) throws Exception { sources.checkTarget(reference, captured, control); }
                public Set<String> hostCapabilities() throws Exception { return sources.hostCapabilities(); }
            };
            Resolution current = resolve(definition, frozenModels, observation); current.requireReady();
            JSONObject expected = view(), actual = current.view(); JSONArray previousItems = expected.getJSONArray("items"), currentItems = actual.getJSONArray("items");
            // An explicitly omitted target remains omitted for this run; later installation cannot add it.
            for (int i = 0; i < previousItems.length(); i++) { JSONObject item = previousItems.getJSONObject(i); if (item.getString("kind").startsWith("target:") && item.getBoolean("optional") && !item.getBoolean("included")) currentItems.put(i, new JSONObject(item.toString())); }
            actual.remove("resolutionDigest"); if (!expected.getString("resolutionDigest").equals(WorkProfile.fingerprint(actual))) throw new SecurityException("work_profile_sources_changed");
            observation.check();
        }
    }
    private static JSONObject frozenTarget(Sources sources, JSONObject frozen, JSONObject reference, RunControl control) throws Exception {
        String key = WorkProfile.targetKey(reference); JSONArray items = frozen.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) { JSONObject item = items.getJSONObject(i);
            if (key.equals(item.getString("id")) && item.getString("kind").startsWith("target:")) {
                if (!item.getBoolean("included")) throw new Missing(item.getString("status"));
                JSONObject captured = new JSONObject(item.getJSONObject("binding").toString()); if (item.has("sourceEvidence")) captured.put("sourceEvidence", item.getJSONObject("sourceEvidence"));
                sources.checkTarget(new JSONObject(reference.toString()), new JSONObject(captured.toString()), control); return new JSONObject(captured.toString()).put("supported", true);
            }
        }
        throw new SecurityException("frozen_profile_target_missing");
    }
    /** Original SQL snapshots can rebuild a removal-only tool policy without recapturing models or probing CLIs.
     * Model request identity/authorization stays in the already captured config and original Gateway. */
    static void checkStoredAssetSourcesCurrent(JSONObject frozen, Sources sources, RunControl control) throws Exception {
        requireStoredSkillScope(frozen,sources,control);
        JSONObject definition = frozen.getJSONObject("definition"); WorkProfile assets = WorkProfile.fromJson(new JSONObject(definition.toString()).put("modelPolicies", new JSONObject()));
        Sources originalAssets = new Sources() {
            public ModelConfig model(ModelTargetRef ref, String purpose, RunControl observation) { throw new SecurityException("tool_policy_cannot_recapture_models"); }
            public void checkModel(ModelConfig captured, String purpose, RunControl observation) { throw new SecurityException("tool_policy_cannot_recapture_models"); }
            public JSONObject skill(String id, RunControl observation) throws Exception { return frozen.has("skillScope")?sources.skill(id, observation):sources.legacyGlobalSkill(id,observation); }
            public JSONObject skillScope(RunControl observation)throws Exception{return frozen.has("skillScope")?sources.skillScope(observation):null;}
            public JSONObject plugin(String id, RunControl observation) throws Exception { return sources.plugin(id, observation); }
            public JSONObject extension(String id, RunControl observation) throws Exception { return sources.extension(id, observation); }
            public JSONObject team(String id, RunControl observation) throws Exception { return sources.team(id, observation); }
            public JSONObject target(JSONObject ref, RunControl observation) throws Exception { return frozenTarget(sources, frozen, ref, observation); }
            public Set<String> hostCapabilities() throws Exception { return sources.hostCapabilities(); }
        };
        Resolution observed = resolve(assets, originalAssets, control, false); observed.requireReady(); JSONArray expected = new JSONArray(), actual = observed.view().getJSONArray("items"), original = frozen.getJSONArray("items");
        for (int i = 0; i < original.length(); i++) if (!"model".equals(original.getJSONObject(i).getString("kind"))) expected.put(original.getJSONObject(i));
        for (int i = 0; i < expected.length(); i++) { JSONObject item = expected.getJSONObject(i); if (item.getString("kind").startsWith("target:") && item.getBoolean("optional") && !item.getBoolean("included")) actual.put(i, new JSONObject(item.toString())); }
        if (!WorkProfile.fingerprint(expected).equals(WorkProfile.fingerprint(actual))) throw new SecurityException("original_work_profile_asset_sources_changed");requireStoredSkillScope(frozen,sources,control);control.check();
    }
    /** The stored JSON only describes a previous native choice. A current native adapter
     * must independently derive the same namespace before it can be used again. Old pins
     * without scope are read through legacyGlobalSkill and cannot gain project sources. */
    static void requireStoredSkillScope(JSONObject frozen,Sources sources,RunControl observation)throws Exception {
        JSONObject current=sources.skillScope(observation);
        if(frozen.has("skillScope")!=frozen.has("skillScopeFingerprint"))throw new SecurityException("original_work_profile_skill_scope_pin_incomplete");
        if(!frozen.has("skillScope"))return;
        String expected=dev.pocketengine.features.skills.SkillSourcePolicy.scopeFingerprint(frozen.getJSONObject("skillScope"));
        if(!expected.equals(frozen.getString("skillScopeFingerprint"))||current==null||!expected.equals(dev.pocketengine.features.skills.SkillSourcePolicy.scopeFingerprint(current)))throw new SecurityException("original_work_profile_skill_scope_changed");
    }
    private interface Read { JSONObject get() throws Exception; }
    private interface Check { JSONObject verify(JSONObject actual) throws Exception; }
    private WorkProfileResolver() { }
    public static Resolution resolve(WorkProfile profile, Sources sources, RunControl observation) throws Exception {return resolve(profile,sources,observation,true);}
    private static Resolution resolve(WorkProfile profile,Sources sources,RunControl observation,boolean captureDefault) throws Exception {
        java.util.Objects.requireNonNull(profile); java.util.Objects.requireNonNull(sources); observation.check();
        JSONObject definition = profile.toJson(), capturedModels = new JSONObject(),skillScope=sources.skillScope(observation); JSONArray rows = new JSONArray();
        Set<String> availableCapabilities=sources.hostCapabilities();
        Set<String> selectedPluginCapabilities = new java.util.HashSet<>();JSONArray pluginRefs=definition.getJSONArray("pluginRefs");for(int i=0;i<pluginRefs.length();i++){JSONObject plugin=pluginRefs.getJSONObject(i);JSONArray tools=plugin.getJSONArray("tools");for(int n=0;n<tools.length();n++)selectedPluginCapabilities.add("plugin:"+plugin.getString("id")+":"+tools.getString(n));}
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
        if(captureDefault){
        // A fully explicit MAIN/SUMMARY Profile does not depend on an unrelated unconfigured
        // global source. Member/required-team use still needs an exact native startup fallback.
        ModelConfig startupDefault=null;
        try {startupDefault=sources.globalDefault(observation);if(startupDefault!=null)sources.checkModel(startupDefault,"DEFAULT",observation);}
        catch(Exception unavailable){observation.check();startupDefault=null;}
        if (startupDefault != null) {
            JSONObject identity = modelIdentity(startupDefault);
            models.put("_GLOBAL", startupDefault); capturedModels.put("_GLOBAL", identity);
            rows.put(row("model", "_GLOBAL", false, "ready", identity));
        } else if (WorkProfile.keys(policies).stream().anyMatch(p -> p.equals("MEMBER") || p.startsWith("MEMBER.")) || definition.optJSONObject("teamTemplateRef")!=null&&!definition.getJSONObject("teamTemplateRef").optBoolean("optional")) {
            rows.put(row("model", "_GLOBAL", false, "native_startup_default_unavailable", null));
        }
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
                            for (int n = 0; n < required.length(); n++){String capability=required.getString(n);if(!availableCapabilities.contains(capability))throw new Missing("host_capability_missing");if(capability.startsWith("plugin:")&&!selectedPluginCapabilities.contains(capability))throw new Missing("required_plugin_not_selected");}
                            return pick(actual, "id", "revision", "digest", "executionGeneration", "expiresAt", "skillScopeFingerprint");
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
            JSONObject actual = null, item;
            try {
                observation.check(); actual = sources.target(new JSONObject(ref.toString()), observation); observation.check();
                if (actual == null) throw new Missing("source_missing");
                if (actual.opt("supported") != Boolean.TRUE) throw new Missing("target_not_supported");
                JSONObject binding = new JSONObject();
                for (String field : WorkProfile.keys(ref)) if (!field.equals("optional")) { exact(actual, ref, field); binding.put(field, ref.get(field)); }
                item = row("target:" + kind, id, ref.getBoolean("optional"), "ready", binding);
            } catch (Missing unavailable) { observation.check(); item = row("target:" + kind, id, ref.getBoolean("optional"), unavailable.code, null); }
            catch (Exception unavailable) { observation.check(); item = row("target:" + kind, id, ref.getBoolean("optional"), "source_unavailable", null); }
            if (actual != null && actual.optJSONObject("sourceEvidence") != null) item.put("sourceEvidence", targetEvidence(actual.getJSONObject("sourceEvidence")));
            rows.put(item);
        }
        boolean ready = true; int omitted = 0;
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.getJSONObject(i); if (!"ready".equals(row.getString("status"))) { if (row.getBoolean("optional")) omitted++; else ready = false; } }
        observation.check();
        JSONObject result = new JSONObject().put("format", "pocketworld.resolved-work-profile.v1").put("profileId", profile.id).put("profileRevision", profile.revision)
                .put("profileDigest", profile.digest()).put("items", rows).put("modelPolicies", capturedModels).put("resourcePreferences", definition.getJSONObject("resourcePreferences"))
                .put("ready", ready).put("sourceChecksOnly", true).put("configurationApplied", false).put("runtimeVerified", false).put("optionalOmissions", omitted).put("executionAuthorized", false).put("permissionsChanged", false).put("executed", false);
        if(skillScope!=null)result.put("skillScope",new JSONObject(skillScope.toString())).put("skillScopeFingerprint",dev.pocketengine.features.skills.SkillSourcePolicy.scopeFingerprint(skillScope));
        requireStoredSkillScope(result,sources,observation);
        return new Resolution(profile, sources, result, models);
    }
    private static JSONObject inspect(String kind, String id, JSONObject ref, RunControl observation, Read read, Check check) throws Exception {
        observation.check(); boolean optional = ref.getBoolean("optional");
        try { JSONObject actual = read.get(); observation.check(); if (actual == null) throw new Missing("source_missing"); return row(kind, id, optional, "ready", check.verify(actual)); }
        catch (Missing unavailable) { observation.check(); return row(kind, id, optional, unavailable.code, null); }
        catch (Exception unavailable) { observation.check(); return row(kind, id, optional, "source_unavailable", null); }
    }
    /** Stable public support facts only. Observation time and raw CLI/network output never enter run digests. */
    private static JSONObject targetEvidence(JSONObject source) throws Exception {
        JSONObject result = new JSONObject();
        for (String field : new String[]{"sourceState", "reasonCode", "workerVersion", "accountMode"}) if (source.has(field)) {
            Object value = source.get(field); if (value == JSONObject.NULL) result.put(field, JSONObject.NULL);
            else { if (!(value instanceof String text) || text.length() > 120 || !text.matches("[A-Za-z0-9_.:/ +()@-]*")) throw new SecurityException("invalid_public_target_fact"); result.put(field, text); }
        }
        for (String field : new String[]{"workerAvailable", "protocolSupported", "protocolVerified", "accountConfigured", "configReadVerified", "cliFlagsVerified", "hostKeyTrusted", "credentialsConfigured", "connectionVerified", "configurationApplied", "runtimeVerified", "executableIdentityVerified", "versionContentVerified", "executionChainVerified"}) if (source.has(field)) {
            Object value = source.get(field); if (!(value instanceof Boolean) && value != JSONObject.NULL) throw new SecurityException("invalid_public_target_fact"); result.put(field, value);
        }
        if (source.has("executableIdentity")) {
            if (source.isNull("executableIdentity")) result.put("executableIdentity", JSONObject.NULL); else result.put("executableIdentity", WorkProfile.digest(source, "executableIdentity"));
        }
        if (source.has("executableIdentityKind")) {
            Object kind = source.get("executableIdentityKind"); if (kind != JSONObject.NULL && (!(kind instanceof String text) || !Set.of("native_file_stat_v1", "unverified").contains(text))) throw new SecurityException("invalid_public_executable_identity_kind"); result.put("executableIdentityKind", kind);
        }
        return result;
    }
    static JSONObject modelIdentity(ModelConfig captured) throws Exception {
        JSONObject result = new JSONObject().put("model", captured.model).put("configurationDigest", ModelRoutePlan.configurationDigest(captured));
        if (captured.capabilityBinding != null) result.put("binding", captured.capabilityBinding.toJson());
        if (captured.usesAccount()) result.put("account", captured.accountModel.toJson());
        if (captured.routePlan != null) {
            JSONObject route = captured.routePlan.toJson(); route.remove("snapshotId"); result.put("route", route);
        }
        return result;
    }
    /** Reopening a native route uses the original SQL pin, not a newly selected default or JSON config. */
    static void requireStoredModelIdentity(JSONObject frozen,String purpose,ModelConfig captured)throws Exception {
        JSONObject expected=frozen.getJSONObject("modelPolicies").getJSONObject(purpose);
        if(!WorkProfile.fingerprint(expected).equals(WorkProfile.fingerprint(modelIdentity(captured))))throw new SecurityException("original_work_profile_model_source_changed:"+purpose);
    }
    /** Original run recovery only: recapture credential-bearing configs from their native stores,
     * compare all public pins, and keep explicitly omitted targets omitted without probing them. */
    static Resolution resolveStored(JSONObject frozen,Sources original,RunControl observation)throws Exception {
        requireStoredSkillScope(frozen,original,observation);
        Sources pinned=new Sources(){
            public ModelConfig model(ModelTargetRef ref,String purpose,RunControl control)throws Exception{ModelConfig captured=original.model(ref,purpose,control);requireStoredModelIdentity(frozen,purpose,captured);return captured;}
            public void checkModel(ModelConfig captured,String purpose,RunControl control)throws Exception{original.checkModel(captured,purpose,control);}
            public ModelConfig globalDefault(RunControl control)throws Exception {
                JSONObject pin=frozen.getJSONObject("modelPolicies").optJSONObject("_GLOBAL");if(pin==null)return null;
                JSONObject account=pin.optJSONObject("account"),binding=pin.optJSONObject("binding");
                String connection=binding!=null?binding.getString("connectionId"):account!=null?"account:"+account.getString("accountId"):"";
                if(connection.isEmpty())throw new SecurityException("original_profile_global_default_unavailable");
                ModelConfig captured=original.model(ModelTargetRef.exact(connection,pin.getString("model")),"DEFAULT",control);
                requireStoredModelIdentity(frozen,"_GLOBAL",captured);return captured;
            }
            public JSONObject skill(String id,RunControl control)throws Exception{return frozen.has("skillScope")?original.skill(id,control):original.legacyGlobalSkill(id,control);}
            public JSONObject skillScope(RunControl control)throws Exception{return frozen.has("skillScope")?original.skillScope(control):null;}
            public JSONObject legacyGlobalSkill(String id,RunControl control)throws Exception{return original.legacyGlobalSkill(id,control);}
            public JSONObject plugin(String id,RunControl control)throws Exception{return original.plugin(id,control);}
            public JSONObject extension(String id,RunControl control)throws Exception{return original.extension(id,control);}
            public JSONObject team(String id,RunControl control)throws Exception{return original.team(id,control);}
            public JSONObject target(JSONObject ref,RunControl control)throws Exception{return frozenTarget(original,frozen,ref,control);}
            public void checkTarget(JSONObject ref,JSONObject binding,RunControl control)throws Exception{original.checkTarget(ref,binding,control);}
            public Set<String> hostCapabilities()throws Exception{return original.hostCapabilities();}
        };
        Resolution restored=resolve(WorkProfile.fromJson(frozen.getJSONObject("definition")),pinned,observation);restored.requireReady();
        if(!frozen.getString("resolutionDigest").equals(restored.view().getString("resolutionDigest")))throw new SecurityException("original_work_profile_recovery_pins_changed");return restored;
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
