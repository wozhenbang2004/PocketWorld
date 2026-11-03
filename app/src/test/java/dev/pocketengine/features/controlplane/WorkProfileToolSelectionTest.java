package dev.pocketengine.features.controlplane;

import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WorkProfileToolSelectionTest {
    private JSONObject skill(String id, int revision, String digest) throws Exception { return new JSONObject().put("id", id).put("revision", revision).put("digest", digest); }
    private JSONObject item(String kind, JSONObject binding, boolean included) throws Exception { return new JSONObject().put("kind", kind).put("included", included).put("binding", binding); }
    private JSONObject snapshot(JSONArray items) throws Exception { return new JSONObject().put("ready", true).put("items", items); }
    @Test public void skillDiscoveryOnlyRemovesAndRetainsOriginalBudgetAndValidationProvenance() throws Exception {
        JSONObject selected = skill("selected", 2, "a".repeat(64)); var policy = new WorkProfileToolSelection(snapshot(new JSONArray().put(item("skill", selected, true))), () -> {});
        JSONObject original = new JSONObject().put("skills", new JSONArray().put(selected).put(skill("other", 1, "b".repeat(64)))).put("omitted", new JSONObject().put("budget", 2)).put("metadataOnly", true).put("grantsAdded", new JSONArray());
        JSONObject filtered = policy.filterSkillDiscovery(original); assertEquals(1, filtered.getJSONArray("skills").length()); assertEquals(1, filtered.getInt("profileExcluded")); assertEquals(2, original.getJSONArray("skills").length()); assertEquals(2, filtered.getJSONObject("omitted").getInt("budget")); assertEquals(0, filtered.getJSONArray("grantsAdded").length());
    }
    @Test public void hiddenSkillNamesOrSameDigestDifferentRevisionCannotBypassSelection() throws Exception {
        var policy = new WorkProfileToolSelection(snapshot(new JSONArray().put(item("skill", skill("selected", 2, "a".repeat(64)), true))), () -> {});
        policy.requireSkillRequest(new JSONObject().put("id", "selected").put("digest", "a".repeat(64))); assertFalse(policy.includesSkill(skill("selected", 3, "a".repeat(64))));
        assertThrows(SecurityException.class, () -> policy.requireSkillRequest(new JSONObject().put("id", "other").put("digest", "a".repeat(64))));
        assertThrows(SecurityException.class, () -> policy.requireSkillRequest(new JSONObject().put("id", "selected").put("digest", "a".repeat(64)).put("revision", 3)));
    }
    @Test public void pluginToolRequiresOriginalInstallStateGrantDigestAndExplicitToolSubset() throws Exception {
        JSONObject binding = new JSONObject().put("id", "plugin-a").put("installationId", "install-a").put("stateRevision", 3).put("grantsDigest", "a".repeat(64)).put("tools", new JSONArray().put("read"));
        var policy = new WorkProfileToolSelection(snapshot(new JSONArray().put(item("plugin", binding, true))), () -> {});
        JSONObject entry = new JSONObject().put("pluginId", "plugin-a").put("installationId", "install-a").put("tool", "read").put("executionBinding", new JSONObject().put("stateRevision", 3).put("grantsDigest", "a".repeat(64)));
        assertTrue(policy.includesPluginTool(entry)); assertFalse(policy.includesPluginTool(new JSONObject(entry.toString()).put("tool", "write"))); assertFalse(policy.includesPluginTool(new JSONObject(entry.toString()).put("installationId", "other-install")));
        entry.getJSONObject("executionBinding").put("stateRevision", 4); assertFalse(policy.includesPluginTool(entry));
    }
    @Test public void omittedExtensionsCannotAppearAndReturnedSelectionIsDefensive() throws Exception {
        JSONObject a = new JSONObject().put("id", "js-a"), b = new JSONObject().put("id", "js-b"); var policy = new WorkProfileToolSelection(snapshot(new JSONArray().put(item("extension", a, true)).put(item("extension", b, false))), () -> {});
        JSONArray returned = policy.extensionReferences(); assertEquals(1, returned.length()); returned.getJSONObject(0).put("id", "changed"); assertEquals("js-a", policy.extensionReferences().getJSONObject(0).getString("id"));
    }
    @Test public void currentNativeRevocationAlwaysStopsDiscoveryAndHiddenReads() throws Exception {
        AtomicBoolean revoked = new AtomicBoolean(); var policy = new WorkProfileToolSelection(snapshot(new JSONArray().put(item("skill", skill("selected", 1, "a".repeat(64)), true))), () -> { if (revoked.get()) throw new SecurityException("revoked"); });
        revoked.set(true); assertThrows(SecurityException.class, () -> policy.filterSkillDiscovery(new JSONObject().put("skills", new JSONArray()))); assertThrows(SecurityException.class, () -> policy.requireSkillRequest(new JSONObject().put("id", "selected").put("digest", "a".repeat(64))));
    }
    @Test public void explicitSelectedSkillIsNotLostBehindUnselectedLexicalMatches() throws Exception {
        JSONObject selected = skill("selected", 2, "a".repeat(64)); var policy = new WorkProfileToolSelection(snapshot(new JSONArray().put(item("skill", selected, true))), () -> {});
        JSONArray globalMatches = new JSONArray(); for(int i=0;i<8;i++)globalMatches.put(skill("other-"+i,1,"b".repeat(64)));
        JSONObject discovery = new JSONObject().put("skills",globalMatches).put("status","ok").put("metadataOnly",true).put("grantsAdded",new JSONArray());
        JSONObject actual = new JSONObject(selected.toString()).put("name","Chosen workflow").put("description","Owner explicitly selected this reference").put("active",true).put("contentVerified",true).put("requiredCapabilities",new JSONArray());
        JSONObject filtered = policy.filterSkillDiscovery(discovery,new JSONArray().put(actual),java.util.Set.of()); assertEquals(1,filtered.getJSONArray("skills").length()); assertEquals(1,filtered.getInt("profileSelectedAdded")); assertEquals("native_work_profile",filtered.getJSONArray("skills").getJSONObject(0).getString("selectionSource")); assertEquals(0,filtered.getJSONArray("grantsAdded").length()); assertEquals(8,globalMatches.length());
    }

    @Test public void taskInstructionsDoNotSelectExecutableSkillsAndReturnedRefsCannotRepointTheBoundSelection()throws Exception {
        JSONObject ref=skill("guidance",2,"a".repeat(64));AtomicBoolean revoked=new AtomicBoolean();
        var policy=new WorkProfileToolSelection(snapshot(new JSONArray().put(item("instruction",ref,true)).put(item("instruction",skill("omitted",1,"b".repeat(64)),false))),()->{if(revoked.get())throw new SecurityException("Original source revoked");});
        assertTrue(policy.hasInstructions());assertFalse(policy.hasSkills());assertTrue(policy.includesReference(ref));assertFalse(policy.includesSkill(ref));
        assertThrows(SecurityException.class,()->policy.requireSkillRequest(new JSONObject(ref.toString())));
        assertFalse(policy.includesReference(skill("guidance",3,"a".repeat(64))));
        assertEquals(0,policy.filterSkillDiscovery(new JSONObject().put("skills",new JSONArray().put(ref))).getJSONArray("skills").length());
        JSONArray returned=policy.instructionReferences();assertEquals(1,returned.length());returned.getJSONObject(0).put("id","changed");
        assertEquals("guidance",policy.instructionReferences().getJSONObject(0).getString("id"));revoked.set(true);
        assertThrows(SecurityException.class,policy::instructionReferences);
    }
    @Test public void instructionAndExecutableSkillCannotCarryConflictingVersionsOfOneOriginalAsset()throws Exception {
        JSONArray refs=new JSONArray().put(item("instruction",skill("same",1,"a".repeat(64)),true)).put(item("skill",skill("same",2,"a".repeat(64)),true));
        assertThrows(SecurityException.class,()->new WorkProfileToolSelection(snapshot(refs),()->{}));
    }

}
