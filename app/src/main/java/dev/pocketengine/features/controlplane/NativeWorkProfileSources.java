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
        /** Fast original identity/revocation guard only. Never repeats CLI probe or starts a process. */
        default void checkCurrent(JSONObject exactOriginalReference, JSONObject capturedPublicBinding, RunControl observation) throws Exception { throw new SecurityException("target_current_guard_not_mounted"); }
    }
    private final SettingsStore settings;
    private final FeatureHost features;
    private final NativePluginHost plugins;
    @FunctionalInterface public interface CapabilityFacts { Set<String> current() throws Exception; }
    private final CapabilityFacts capabilities;
    private final TargetFacts targets;
    @FunctionalInterface public interface AccountFacts { JSONObject snapshot() throws Exception; }
    private final AccountFacts accounts;
    public NativeWorkProfileSources(SettingsStore settings, FeatureHost features, NativePluginHost plugins, Set<String> capabilities, TargetFacts targets) { this(settings, features, plugins, capabilities, targets, null); }
    public NativeWorkProfileSources(SettingsStore settings, FeatureHost features, NativePluginHost plugins, Set<String> capabilities, TargetFacts targets, AccountFacts accounts) { this(settings,features,plugins,fixedCapabilities(capabilities),targets,accounts); }
    public NativeWorkProfileSources(SettingsStore settings,FeatureHost features,NativePluginHost plugins,CapabilityFacts capabilities,TargetFacts targets,AccountFacts accounts) {
        this.accounts = accounts; this.settings = java.util.Objects.requireNonNull(settings); this.features = java.util.Objects.requireNonNull(features);
        this.plugins = java.util.Objects.requireNonNull(plugins); this.capabilities = java.util.Objects.requireNonNull(capabilities); this.targets = targets;
    }
    private static CapabilityFacts fixedCapabilities(Set<String> capabilities) { Set<String> frozen=Set.copyOf(capabilities);return ()->frozen; }
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
        JSONObject value = targets.current(new JSONObject(exactOriginalReference.toString()), observation); observation.check();
        if (value != null && value.opt("supported") == Boolean.TRUE) targets.checkCurrent(new JSONObject(exactOriginalReference.toString()), new JSONObject(value.toString()), observation);
        observation.check(); return value;
    }
    @Override public void checkTarget(JSONObject reference, JSONObject captured, RunControl observation) throws Exception {
        observation.check(); if (targets == null) throw new SecurityException("target_current_guard_not_mounted");
        targets.checkCurrent(new JSONObject(reference.toString()), new JSONObject(captured.toString()), observation); observation.check();
    }
    @FunctionalInterface private interface ChoiceRead { JSONObject get() throws Exception; }
    private JSONObject choiceSection(ChoiceRead read, RunControl observation) throws Exception {
        observation.check(); try { JSONObject data = read.get(); observation.check(); return data.put("available", true).put("executionAuthorized", false).put("permissionsChanged", false); }
        catch (Exception unavailable) { observation.check(); return new JSONObject().put("available", false).put("reasonCode", "original_choice_source_unavailable").put("executionAuthorized", false); }
    }
    /** Live native metadata only. Failure is unavailable, never an invented empty registry or balance. */
    @Override public JSONObject choices(RunControl observation) throws Exception {
        JSONObject sections = new JSONObject();
        sections.put("models", choiceSection(() -> new JSONObject().put("api", settings.apiConnectionsSnapshot()).put("groups", settings.modelGroupsSnapshot()).put("originalStore", "SettingsStore"), observation));
        sections.put("skills", choiceSection(() -> {
            EngineModule module = features.module("skills"); if (!(module instanceof dev.pocketengine.features.skills.SkillsModule)) throw new IllegalStateException("original_skill_store_not_mounted");
            JSONArray rows = module.snapshot().getJSONArray("skills"), items = new JSONArray();
            for (int i = 0; i < rows.length(); i++) items.put(publicFields(rows.getJSONObject(i), "id", "name", "revision", "digest", "executionGeneration", "expiresAt", "active", "status", "contentVerified", "requiredCapabilities"));
            return new JSONObject().put("items", items).put("originalStore", "SkillBook");
        }, observation));
        sections.put("plugins", choiceSection(() -> {
            EngineModule module = features.module("plugins"); if (!(module instanceof dev.pocketengine.features.plugins.PluginsModule)) throw new IllegalStateException("original_plugin_store_not_mounted");
            JSONArray rows = module.snapshot().getJSONArray("plugins"), items = new JSONArray();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i), manifest = row.getJSONObject("manifest"), item = publicFields(row, "id", "installationId", "stateRevision", "enabled", "grantsDigest", "mcpStatus", "mcpAuthorizationState", "hasToken");
                item.put("name", manifest.getString("name")).put("version", manifest.getString("version")).put("manifestDigest", WorkProfile.fingerprint(manifest));
                JSONArray tools = manifest.getJSONArray("tools"), choices = new JSONArray(), grants = row.optJSONArray("grants");
                for (int n = 0; n < tools.length(); n++) {
                    JSONObject tool = tools.getJSONObject(n); boolean granted = false;
                    for (int g = 0; grants != null && g < grants.length(); g++) if (("tool:" + tool.getString("name")).equals(grants.getString(g))) granted = true;
                    choices.put(publicFields(tool, "name", "kind", "effect", "protocolVersion", "descriptorDigest", "serverDigest").put("granted", granted).put("schemaDigest", WorkProfile.fingerprint(tool.getJSONObject("schema"))));
                }
                items.put(item.put("tools", choices));
            }
            return new JSONObject().put("items", items).put("originalStore", "PluginsModule");
        }, observation));
        sections.put("extensions", choiceSection(() -> {
            JSONObject data = plugins.extensionCatalogMetadata(observation); JSONArray rows = data.getJSONArray("plugins"), items = new JSONArray();
            for (int i = 0; i < rows.length(); i++) items.put(publicFields(rows.getJSONObject(i), "id", "name", "version", "installationId", "approvalRevision", "sourceSha256", "enabled", "approved"));
            return new JSONObject().put("items", items).put("revision", data.getLong("revision")).put("originalStore", "ExecutablePluginCatalog");
        }, observation));
        if (accounts == null) sections.put("accounts", new JSONObject().put("available", false).put("reasonCode", "original_account_source_not_mounted"));
        else sections.put("accounts", choiceSection(() -> {
            JSONObject original=accounts.snapshot();JSONArray rows = original.getJSONArray("accounts"), items = new JSONArray();
            for (int i = 0; i < rows.length(); i++) items.put(publicFields(rows.getJSONObject(i), "id", "provider", "label", "enabled", "status", "selected", "version", "planUsageEnabled", "usageLimited"));
            JSONObject result=new JSONObject().put("items",items).put("originalStore","AccountGateway").put("modelBindingScope","original_selected_account_model_only").put("selectedModelUsable",original.optBoolean("selectedModelUsable"));JSONObject selected=original.optJSONObject("selectedModelRef");if(selected!=null)result.put("selectedModelRef",publicFields(selected,"accountId","accountVersion","modelId"));return result;
        }, observation));
        observation.check(); return new JSONObject().put("format", "pocketworld.work-profile-choices.v1").put("sections", sections).put("metadataOnly", true).put("executionAuthorized", false).put("permissionsChanged", false).put("sourceDefinitionsCopied", false).put("configurationApplied", false);
    }
    private static JSONObject publicFields(JSONObject row, String... fields) throws Exception {
        JSONObject result = new JSONObject(); for (String field : fields) if (row.has(field)) result.put(field, row.get(field)); return new JSONObject(result.toString());
    }
    @Override public Set<String> hostCapabilities() throws Exception { return Set.copyOf(capabilities.current()); }
    static JSONObject find(JSONArray rows, String id) throws Exception {
        for (int i = 0; i < rows.length(); i++) if (id.equals(rows.getJSONObject(i).optString("id"))) return new JSONObject(rows.getJSONObject(i).toString()); return null;
    }
}
