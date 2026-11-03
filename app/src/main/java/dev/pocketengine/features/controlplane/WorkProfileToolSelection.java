package dev.pocketengine.features.controlplane;

import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** A removal-only projection into original Skills/Plugins/Extension hosts. It never grants a tool. */
public final class WorkProfileToolSelection {
    private final Map<String, String> skills = new LinkedHashMap<>(), instructions = new LinkedHashMap<>(), plugins = new LinkedHashMap<>();
    private final String extensionRefs;
    private final CapabilitySnapshot.CurrentCheck current, bindingCurrent, sourcesCurrent;
    @FunctionalInterface public interface PreparedCheck { void check(String tool,String operation,JSONObject args,RunControl original)throws Exception; }
    private final PreparedCheck preparedCurrent;
    public WorkProfileToolSelection(NativeWorkProfileHost.Selection selection, String task, String run, int epoch, RunControl original, CapabilitySnapshot.CurrentCheck currentRun) throws Exception {
        this(selection.snapshot(), () -> { selection.requireBoundRun(task, run, epoch, original); currentRun.check(); selection.checkCurrent(original); }, () -> { selection.requireBoundRun(task, run, epoch, original); currentRun.check(); });
        selection.requireBoundRun(task, run, epoch, original);
    }
    public WorkProfileToolSelection(NativeWorkProfileHost.Selection selection,String task,String run,int epoch,RunControl original,CapabilitySnapshot.CurrentCheck currentRun,CapabilitySnapshot.CurrentCheck binding,PreparedCheck prepared)throws Exception {
        this(selection.snapshot(),()->{selection.requireBoundRun(task,run,epoch,original);currentRun.check();selection.checkCurrent(original);},
            ()->{selection.requireBoundRun(task,run,epoch,original);binding.check();},
            ()->{selection.requireBoundRun(task,run,epoch,original);selection.checkCurrent(original);},
            (tool,operation,args,supplied)->{if(supplied!=original)throw new SecurityException("original_profile_control_required");prepared.check(tool,operation,args,supplied);});
        selection.requireBoundRun(task,run,epoch,original);
    }
    /** Package-private data tests; production callers need the typed, bound native selection above. */
    WorkProfileToolSelection(JSONObject snapshot, CapabilitySnapshot.CurrentCheck current) throws Exception { this(snapshot, current, current); }
    WorkProfileToolSelection(JSONObject snapshot, CapabilitySnapshot.CurrentCheck current, CapabilitySnapshot.CurrentCheck bindingCurrent) throws Exception {
        this(snapshot,current,bindingCurrent,current,null);
    }
    WorkProfileToolSelection(JSONObject snapshot,CapabilitySnapshot.CurrentCheck current,CapabilitySnapshot.CurrentCheck bindingCurrent,CapabilitySnapshot.CurrentCheck sourcesCurrent,PreparedCheck prepared)throws Exception {
        if (!snapshot.getBoolean("ready")) throw new SecurityException("profile_selection_not_ready"); this.current = java.util.Objects.requireNonNull(current); this.bindingCurrent = java.util.Objects.requireNonNull(bindingCurrent); this.sourcesCurrent=java.util.Objects.requireNonNull(sourcesCurrent);this.preparedCurrent=prepared;
        JSONArray selected = snapshot.getJSONArray("items"), extensions = new JSONArray();
        for (int i = 0; i < selected.length(); i++) {
            JSONObject item = selected.getJSONObject(i); if (!item.getBoolean("included")) continue;
            String kind = item.getString("kind"); JSONObject binding = item.getJSONObject("binding");
            if (kind.equals("skill") || kind.equals("instruction")) {
                String id = binding.getString("id");
                for (Map<String,String> references : java.util.List.of(skills,instructions)) {
                    String previous = references.get(id);
                    if (previous != null && !matches(new JSONObject(previous),binding)) throw new SecurityException("conflicting_profile_skill_references");
                }
                (kind.equals("skill") ? skills : instructions).putIfAbsent(id,binding.toString());
            } else if (kind.equals("plugin")) plugins.put(binding.getString("id"), binding.toString());
            else if (kind.equals("extension")) extensions.put(new JSONObject(binding.toString()));
        }
        extensionRefs = extensions.toString();
    }
    public void checkCurrent() throws Exception { current.check(); }
    /** The exact original journal op already exists; no read-side no-pending redispatch fence. */
    public void checkPrepared(String tool,JSONObject dispatched,RunControl original)throws Exception {
        String operation=dispatched==null?"":dispatched.optString("_hostOperationId");
        if(operation.isEmpty()||preparedCurrent==null){checkCurrent();return;}
        JSONObject args=new JSONObject(dispatched.toString());args.remove("_hostOperationId");
        preparedCurrent.check(tool,operation,args,original);sourcesCurrent.check();preparedCurrent.check(tool,operation,args,original);
    }
    /** An opaque same-call recheck, not permission or a reusable operation/start token. */
    public final class PreparedTool {
        private final String tool,dispatched;private final RunControl original;
        private PreparedTool(String tool,JSONObject dispatched,RunControl original)throws Exception {this.tool=tool;this.dispatched=dispatched==null?"{}":dispatched.toString();this.original=original;}
        public void checkCurrent()throws Exception {checkPrepared(tool,new JSONObject(dispatched),original);}
        private void require(WorkProfileToolSelection expected)throws Exception {if(expected!=WorkProfileToolSelection.this)throw new SecurityException("original_profile_tool_check_required");checkCurrent();}
    }
    public PreparedTool forTool(String tool,JSONObject dispatched,RunControl original)throws Exception {
        PreparedTool checked=new PreparedTool(tool,dispatched,original);checked.checkCurrent();return checked;
    }
    /** Short original SQL/control identity only; safe for existing retained capability checks. */
    public void checkBindingCurrent() throws Exception { bindingCurrent.check(); }
    public boolean hasSkills() { return !skills.isEmpty(); }
    public boolean hasInstructions() { return !instructions.isEmpty(); }
    /** Read-only exact refs, not executable Skill selection or a file-read authority. */
    public JSONArray instructionReferences() throws Exception {
        checkCurrent(); JSONArray refs=new JSONArray();for(String reference:instructions.values())refs.put(new JSONObject(reference));return refs;
    }
    public JSONArray extensionReferences() throws Exception { return new JSONArray(extensionRefs); }
    public boolean includesSkill(JSONObject metadata) throws Exception {
        String selected = skills.get(metadata.optString("id")); if (selected == null) return false;
        return matches(new JSONObject(selected),metadata);
    }
    /** Original reference invalidation includes task guidance, without exposing it as a Skill tool. */
    public boolean includesReference(JSONObject metadata) throws Exception {
        String instruction=instructions.get(metadata.optString("id"));
        return includesSkill(metadata)||instruction!=null&&matches(new JSONObject(instruction),metadata);
    }
    private static boolean matches(JSONObject ref,JSONObject actual)throws Exception {
        return ref.getInt("revision")==actual.optInt("revision",-1)&&ref.getString("digest").equals(actual.optString("digest"));
    }
    public void requireSkillRequest(JSONObject args) throws Exception {
        checkCurrent();requireSkillReference(args);
    }
    public void requireSkillRequest(JSONObject args,PreparedTool checked)throws Exception {
        if(checked==null)throw new SecurityException("original_profile_tool_check_required");checked.require(this);requireSkillReference(args);
    }
    private void requireSkillReference(JSONObject args)throws Exception {
        String selected = skills.get(args.getString("id"));
        if (selected == null) throw new SecurityException("skill_not_selected_in_original_work_profile"); JSONObject ref = new JSONObject(selected);
        if (!ref.getString("digest").equals(args.getString("digest")) || args.has("revision") && ref.getInt("revision") != args.getInt("revision")) throw new SecurityException("skill_revision_not_selected_in_original_work_profile");
    }
    /** Exact native plugin registry entries only. Matching names alone cannot repoint a selected tool. */
    public boolean includesPluginTool(JSONObject entry) throws Exception {
        String selected = plugins.get(entry.optString("pluginId")); if (selected == null) return false;
        JSONObject ref = new JSONObject(selected), execution = entry.optJSONObject("executionBinding");
        if (execution == null || !ref.getString("installationId").equals(entry.optString("installationId")) || ref.getLong("stateRevision") != execution.optLong("stateRevision", -1) || !ref.getString("grantsDigest").equals(execution.optString("grantsDigest"))) return false;
        JSONArray tools = ref.getJSONArray("tools"); for (int i = 0; i < tools.length(); i++) if (tools.getString(i).equals(entry.optString("tool"))) return true; return false;
    }
    /** Preserve original discovery/error/budget provenance; only remove nonselected metadata. */
    public JSONObject filterSkillDiscovery(JSONObject original) throws Exception {
        checkCurrent();return filterDiscoveryData(original);
    }
    private JSONObject filterDiscoveryData(JSONObject original)throws Exception {
        JSONObject result = new JSONObject(original.toString()); JSONArray rows = original.getJSONArray("skills"), retained = new JSONArray(); int excluded = 0;
        for (int i = 0; i < rows.length(); i++) if (includesSkill(rows.getJSONObject(i))) retained.put(new JSONObject(rows.getJSONObject(i).toString())); else excluded++;
        result.put("skills", retained).put("workProfileSelection", true).put("profileExcluded", excluded); return result;
    }
    /** Owner-selected exact references are eligible even when unrelated global matches filled the lexical limit. */
    public JSONObject filterSkillDiscovery(JSONObject original, JSONArray currentCatalogue, java.util.Set<String> hostCapabilities) throws Exception {
        checkCurrent();return filterDiscoveryData(original,currentCatalogue,hostCapabilities);
    }
    public JSONObject filterSkillDiscovery(JSONObject original,JSONArray currentCatalogue,java.util.Set<String> hostCapabilities,PreparedTool checked)throws Exception {
        if(checked==null)throw new SecurityException("original_profile_tool_check_required");checked.require(this);return filterDiscoveryData(original,currentCatalogue,hostCapabilities);
    }
    private JSONObject filterDiscoveryData(JSONObject original,JSONArray currentCatalogue,java.util.Set<String> hostCapabilities)throws Exception {
        JSONObject result = filterDiscoveryData(original); JSONArray retained = result.getJSONArray("skills"); java.util.Set<String> included = new java.util.HashSet<>();
        for (int i = 0; i < retained.length(); i++) included.add(retained.getJSONObject(i).getString("id")); int added = 0, omitted = 0;
        for (int i = 0; i < currentCatalogue.length(); i++) {
            JSONObject row = currentCatalogue.getJSONObject(i); if (!includesSkill(row) || included.contains(row.getString("id")) || !row.optBoolean("active") || !row.optBoolean("contentVerified")) continue;
            boolean capable = true; JSONArray required = row.optJSONArray("requiredCapabilities"); for (int n = 0; required != null && n < required.length(); n++) if (!hostCapabilities.contains(required.getString(n))) capable = false;
            if (!capable) continue;
            JSONObject metadata = new JSONObject().put("id", row.getString("id")).put("revision", row.getInt("revision")).put("digest", row.getString("digest"))
                    .put("name", row.getString("name")).put("description", row.optString("description")).put("entryPath", "SKILL.md")
                    .put("matchReasons", new JSONArray().put("native_work_profile_selection")).put("selectionSource", "native_work_profile");
            for(String identity:new String[]{"executionGeneration","skillScopeFingerprint"})if(row.has(identity))metadata.put(identity,row.get(identity));
            if (retained.length() >= 8 || result.toString().length() + metadata.toString().length() + 120 > 6000) { omitted++; continue; }
            retained.put(metadata); included.add(row.getString("id")); added++;
        }
        return result.put("profileSelectedAdded", added).put("profileSelectedOmitted", omitted);
    }

}
