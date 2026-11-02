package dev.pocketengine.features.controlplane;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WorkProfileTest {
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    private JSONObject profile() throws Exception { return WorkProfile.definition("work-project", 1, "Project coding"); }
    private JSONObject skillRef(boolean optional) throws Exception { return new JSONObject().put("id", "skill-a").put("revision", 2).put("digest", A).put("optional", optional); }
    private JSONObject skill() throws Exception { return new JSONObject().put("id", "skill-a").put("revision", 2).put("digest", A).put("executionGeneration", 4L).put("expiresAt", JSONObject.NULL).put("active", true).put("status", "active").put("contentVerified", true).put("requiredCapabilities", new JSONArray()); }
    private static final class Sources implements WorkProfileResolver.Sources {
        final Map<String, JSONObject> skills = new HashMap<>(), plugins = new HashMap<>(), extensions = new HashMap<>();
        Set<String> capabilities = Set.of(); boolean allowFrozen, revoked; int modelCaptures; ModelConfig config = new ModelConfig("https://fixture.invalid/v1", "fixture-secret-never-serialize", "model", false, 4, 30);
        public ModelConfig model(ModelTargetRef ref, String purpose, RunControl c) { modelCaptures++; return config; }
        public void checkModel(ModelConfig captured, String purpose, RunControl c) { if (revoked || !allowFrozen && captured != config) throw new SecurityException("fixture_revoked"); }
        public JSONObject skill(String id, RunControl c) { return skills.get(id); }
        public JSONObject plugin(String id, RunControl c) { return plugins.get(id); }
        public JSONObject extension(String id, RunControl c) { return extensions.get(id); }
        public JSONObject team(String id, RunControl c) { return null; }
        public JSONObject target(JSONObject reference, RunControl c) throws Exception { return new JSONObject().put("supported", false); }
        public Set<String> hostCapabilities() { return capabilities; }
    }
    @Test public void unknownSecretsGrantsAndExecutableFieldsAreRejected() throws Exception {
        for (String key : new String[]{"token", "apiKey", "grants", "hooks", "commands", "script", "accountCredentials"}) {
            JSONObject value = profile().put(key, "not-a-reference"); assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(value));
        }
        JSONObject ref = skillRef(false).put("grant", "phone.local"); assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(profile().put("skillRefs", new JSONArray().put(ref))));
    }
    @Test public void revisionsAndOptionalFlagsRequireTheirExactTypes() throws Exception {
        for (Object revision : new Object[]{0, -1, 1.0, "1"}) assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(profile().put("revision", revision)));
        JSONObject missing = skillRef(false); missing.remove("optional"); assertThrows(Exception.class, () -> WorkProfile.fromJson(profile().put("skillRefs", new JSONArray().put(missing))));
        assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(profile().put("skillRefs", new JSONArray().put(skillRef(false).put("optional", "false")))));
    }
    @Test public void duplicateReferencesAndResourcePrivilegeEscalationAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(profile().put("skillRefs", new JSONArray().put(skillRef(false)).put(skillRef(true)))));
        assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(profile().put("resourcePreferences", new JSONObject().put("maxConcurrentModelCalls", 3))));
        assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(profile().put("resourcePreferences", new JSONObject().put("approveAll", true))));
    }
    @Test public void definitionsAreDefensiveAndDigestsIgnoreObjectKeyOrder() throws Exception {
        JSONObject input = profile(); WorkProfile frozen = WorkProfile.fromJson(input); String digest = frozen.digest(); input.put("name", "changed"); frozen.toJson().put("name", "also changed");
        assertEquals("Project coding", frozen.name); assertEquals(digest, frozen.digest());
        assertEquals(WorkProfile.fingerprint(new JSONObject().put("b", 2).put("a", 1)), WorkProfile.fingerprint(new JSONObject().put("a", 1).put("b", 2)));
    }
    @Test public void requiredMissingAssetBlocksAndOnlyExplicitOptionalCanOmit() throws Exception {
        Sources sources = new Sources();
        var blocked = WorkProfileResolver.resolve(WorkProfile.fromJson(profile().put("skillRefs", new JSONArray().put(skillRef(false)))), sources, new RunControl());
        assertFalse(blocked.view().getBoolean("ready")); assertThrows(SecurityException.class, blocked::requireReady);
        var optional = WorkProfileResolver.resolve(WorkProfile.fromJson(profile().put("skillRefs", new JSONArray().put(skillRef(true)))), sources, new RunControl());
        assertTrue(optional.view().getBoolean("ready")); assertEquals(1, optional.view().getInt("optionalOmissions")); assertFalse(optional.view().getBoolean("executionAuthorized")); assertFalse(optional.view().getBoolean("configurationApplied"));
    }
    @Test public void exactSkillRevisionGenerationAndCapabilityAreRechecked() throws Exception {
        Sources sources = new Sources(); sources.skills.put("skill-a", skill()); WorkProfile p = WorkProfile.fromJson(profile().put("instructionRefs", new JSONArray().put(skillRef(false))));
        var resolution = WorkProfileResolver.resolve(p, sources, new RunControl()); resolution.requireReady(); resolution.checkCurrent(new RunControl());
        sources.skills.get("skill-a").put("executionGeneration", 5); assertThrows(SecurityException.class, () -> resolution.checkCurrent(new RunControl()));
        sources.skills.get("skill-a").put("revision", 3); assertFalse(WorkProfileResolver.resolve(p, sources, new RunControl()).view().getBoolean("ready"));
        sources.skills.put("skill-a", skill().put("requiredCapabilities", new JSONArray().put("phone.local"))); assertFalse(WorkProfileResolver.resolve(p, sources, new RunControl()).view().getBoolean("ready"));
        sources.capabilities = Set.of("phone.local"); assertTrue(WorkProfileResolver.resolve(p, sources, new RunControl()).view().getBoolean("ready"));
    }
    @Test public void inactiveOrInvalidSkillsNeverBecomeAuthorizedThroughAProfile() throws Exception {
        Sources sources = new Sources(); WorkProfile p = WorkProfile.fromJson(profile().put("skillRefs", new JSONArray().put(skillRef(false))));
        for (String status : new String[]{"invalid", "inactive", "missingCapabilities"}) { sources.skills.put("skill-a", skill().put("status", status)); assertFalse(WorkProfileResolver.resolve(p, sources, new RunControl()).view().getBoolean("ready")); }
        sources.skills.put("skill-a", skill().put("contentVerified", false)); assertFalse(WorkProfileResolver.resolve(p, sources, new RunControl()).view().getBoolean("ready"));
    }
    @Test public void pluginSelectionNeverCreatesGrantsAndDetectsEndpointOrInstallDrift() throws Exception {
        Sources sources = new Sources(); JSONObject manifest = new JSONObject().put("tools", new JSONArray().put(new JSONObject().put("name", "read").put("kind", "mcp").put("endpoint", "https://tools.invalid/mcp")));
        JSONObject actual = new JSONObject().put("id", "plugin-a").put("installationId", "install-a").put("stateRevision", 2).put("enabled", true).put("manifest", manifest).put("grants", new JSONArray()).put("grantsDigest", A).put("mcpStatus", "will_revalidate"); sources.plugins.put("plugin-a", actual);
        JSONObject ref = new JSONObject().put("id", "plugin-a").put("installationId", "install-a").put("stateRevision", 2).put("manifestDigest", WorkProfile.fingerprint(manifest)).put("tools", new JSONArray().put("read")).put("optional", false);
        WorkProfile p = WorkProfile.fromJson(profile().put("pluginRefs", new JSONArray().put(ref))); assertFalse(WorkProfileResolver.resolve(p, sources, new RunControl()).view().getBoolean("ready")); assertEquals(0, actual.getJSONArray("grants").length());
        actual.put("grants", new JSONArray().put("tool:read")); var resolution = WorkProfileResolver.resolve(p, sources, new RunControl()); resolution.requireReady(); assertFalse(resolution.view().toString().contains("tool:read"));
        actual.put("installationId", "install-b"); assertThrows(SecurityException.class, () -> resolution.checkCurrent(new RunControl())); actual.put("installationId", "install-a"); manifest.getJSONArray("tools").getJSONObject(0).put("endpoint", "https://other.invalid/mcp"); assertFalse(WorkProfileResolver.resolve(p, sources, new RunControl()).view().getBoolean("ready"));
    }
    @Test public void mcpReauthorizationAndUnknownTargetSupportRemainExplicitBlockers() throws Exception {
        Sources sources = new Sources(); JSONObject target = new JSONObject().put("kind", "remote_harness").put("profileId", "remote-a").put("profileBinding", A).put("environmentId", "env-a").put("environmentBinding", B).put("worker", "codex_app_server").put("optional", false);
        var resolution = WorkProfileResolver.resolve(WorkProfile.fromJson(profile().put("targetBindings", new JSONArray().put(target))), sources, new RunControl()); assertFalse(resolution.view().getBoolean("ready")); assertEquals("target_not_supported", resolution.view().getJSONArray("items").getJSONObject(0).getString("status"));
    }
    @Test public void extensionUsesExactOriginalInstallationAndApprovalWithoutExecutingIt() throws Exception {
        Sources sources = new Sources(); JSONObject ref = new JSONObject().put("id", "extension-a").put("installationId", "js-install-a").put("version", "1").put("approvalRevision", 4).put("sourceSha256", A).put("optional", false);
        sources.extensions.put("extension-a", new JSONObject(ref.toString()).put("enabled", true).put("approved", true)); WorkProfile p = WorkProfile.fromJson(profile().put("extensionRefs", new JSONArray().put(ref))); var resolution = WorkProfileResolver.resolve(p, sources, new RunControl()); resolution.requireReady();
        sources.extensions.get("extension-a").put("sourceSha256", B); assertThrows(SecurityException.class, () -> resolution.checkCurrent(new RunControl())); assertFalse(resolution.view().getBoolean("executed"));
    }
    @Test public void modelBindingsAreFrozenWithoutSerializingCredentials() throws Exception {
        Sources sources = new Sources(); JSONObject value = profile(); value.getJSONObject("modelPolicies").put("MAIN", ModelTargetRef.exact("connection-a", "model").toJson());
        var resolution = WorkProfileResolver.resolve(WorkProfile.fromJson(value), sources, new RunControl()); resolution.requireReady(); assertSame(sources.config, resolution.models().get("MAIN")); assertFalse(resolution.view().toString().contains("fixture-secret-never-serialize"));
        sources.config = sources.config.withModel("changed-model"); assertThrows(SecurityException.class, () -> resolution.checkCurrent(new RunControl()));
    }
    @Test public void cancellationIsNeverFlattenedIntoOptionalOrUnavailableSuccess() throws Exception {
        Sources sources = new Sources(); RunControl c = new RunControl(); c.cancel(); assertThrows(Exception.class, () -> WorkProfileResolver.resolve(WorkProfile.fromJson(profile().put("skillRefs", new JSONArray().put(skillRef(true)))), sources, c));
    }
    @Test public void modelDefaultAndGroupEditsCannotRebindAnExistingProfileSelection() throws Exception {
        Sources sources = new Sources(); sources.allowFrozen = true; JSONObject value = profile(); value.getJSONObject("modelPolicies").put("MAIN", ModelTargetRef.group("coding", 1).toJson());
        var resolution = WorkProfileResolver.resolve(WorkProfile.fromJson(value), sources, new RunControl()); ModelConfig captured = resolution.models().get("MAIN");
        sources.config = captured.withModel("new-default-must-not-be-captured"); resolution.checkCurrent(new RunControl());
        assertSame(captured, resolution.models().get("MAIN")); assertEquals(1, sources.modelCaptures);
        sources.revoked = true; assertThrows(SecurityException.class, () -> resolution.checkCurrent(new RunControl())); assertEquals(1, sources.modelCaptures);
    }

    @Test public void remoteTargetsRequireTheOriginalProfileEnvironmentAndWorkerTuple() throws Exception {
        JSONObject incomplete = new JSONObject().put("kind", "remote_harness").put("id", "remote-a").put("bindingDigest", A).put("optional", false);
        assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(profile().put("targetBindings", new JSONArray().put(incomplete))));
        JSONObject exact = new JSONObject().put("kind", "remote_harness").put("profileId", "remote-a").put("profileBinding", A).put("environmentId", "env-a").put("environmentBinding", B).put("worker", "codex_app_server").put("optional", false);
        WorkProfile.fromJson(profile().put("targetBindings", new JSONArray().put(exact)));
        for (String missing : new String[]{"profileBinding", "environmentId", "environmentBinding", "worker"}) { JSONObject invalid = new JSONObject(exact.toString()); invalid.remove(missing); assertThrows(Exception.class, () -> WorkProfile.fromJson(profile().put("targetBindings", new JSONArray().put(invalid)))); }
        JSONObject otherVersion = new JSONObject(exact.toString()).put("environmentBinding", A);
        assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(profile().put("targetBindings", new JSONArray().put(exact).put(otherVersion))));
    }
    @Test public void sshAndRuntimeReferencesUseOriginalBindingsNotInventedTargetIds() throws Exception {
        JSONObject ssh = new JSONObject().put("kind", "ssh").put("profileId", "ssh-a").put("bindingHash", A).put("optional", false);
        JSONObject runtime = new JSONObject().put("kind", "local_runtime").put("packageId", "ubuntu-arm64").put("identity", B).put("optional", false);
        WorkProfile value = WorkProfile.fromJson(profile().put("targetBindings", new JSONArray().put(ssh).put(runtime)));
        assertEquals("ssh:ssh-a", WorkProfile.targetKey(value.toJson().getJSONArray("targetBindings").getJSONObject(0)));
        assertThrows(IllegalArgumentException.class, () -> WorkProfile.fromJson(profile().put("targetBindings", new JSONArray().put(new JSONObject(ssh.toString()).put("host", "unreviewed.invalid")))));
    }

}
