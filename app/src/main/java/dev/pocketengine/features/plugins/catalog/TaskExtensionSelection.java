package dev.pocketengine.features.plugins.catalog;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One original task's exact selection snapshot, never a permission registry or runtime. */
public final class TaskExtensionSelection implements ExecutableExtensionSource.SelectionPolicy {
    @FunctionalInterface public interface Current {
        void check(ExtensionRuntime.Binding binding, long selectionRevision, RunControl control) throws Exception;
    }
    private final String taskId, runId, selectedJson;
    private final int epoch;
    private final long revision;
    private final boolean inherited;
    private final Current current;
    public TaskExtensionSelection(JSONObject nativeTarget, Current current) throws Exception {
        this.current = java.util.Objects.requireNonNull(current); taskId = nativeTarget.getString("taskId"); runId = nativeTarget.getString("runId"); epoch = nativeTarget.getInt("epoch");
        JSONObject selection = nativeTarget.getJSONObject("selection"); revision = selection.getLong("revision"); String mode = selection.getString("mode");
        if (revision < 0 || !mode.matches("inherit_enabled|explicit")) throw new SecurityException("JS任务选用记录无效");
        inherited = mode.equals("inherit_enabled"); JSONArray selected = selection.getJSONArray("programs");
        if (selected.length() > ExtensionRuntime.MAX_EXTENSIONS || inherited && selected.length() != 0) throw new SecurityException("JS任务选用数量无效");
        Set<String> ids = new HashSet<>(); for (int i = 0; i < selected.length(); i++) {
            JSONObject row = selected.getJSONObject(i);
            if (!ids.add(row.getString("id")) || row.getString("installationId").codePoints().allMatch(Character::isWhitespace) || row.getString("version").codePoints().allMatch(Character::isWhitespace) || row.getString("version").length() > 32 || row.getLong("approvalRevision") < 1 || !row.getString("sourceSha256").matches("[a-f0-9]{64}")) throw new SecurityException("JS任务选用缺少准确安装身份");
        }
        selectedJson = selected.toString();
    }
    @Override public List<ExecutablePluginCatalog.ApprovedSource> select(ExtensionRuntime.Binding binding, List<ExecutablePluginCatalog.ApprovedSource> available, RunControl control) throws Exception {
        checkCurrent(binding, control); if (inherited) return available;
        List<ExecutablePluginCatalog.ApprovedSource> result = new ArrayList<>(); JSONArray selected = new JSONArray(selectedJson);
        for (int i = 0; i < selected.length(); i++) {
            JSONObject ref = selected.getJSONObject(i); ExecutablePluginCatalog.ApprovedSource found = null;
            for (ExecutablePluginCatalog.ApprovedSource source : available) if (matches(ref, source)) found = source;
            if (found == null) throw new SecurityException("本任务选用的 " + ref.getString("id") + " 已撤销、停用或改版；请重新审阅本任务选用，未静默替换或跳过");
            result.add(found);
        }
        checkCurrent(binding, control); return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(result));
    }
    @Override public void checkCurrent(ExtensionRuntime.Binding binding, RunControl control) throws Exception {
        control.check(); if (!taskId.equals(binding.taskId) || !runId.equals(binding.runId) || epoch != binding.epoch) throw new SecurityException("选用不属于原任务执行代次");
        current.check(binding, revision, control); control.check();
    }
    public static boolean matches(JSONObject ref, ExecutablePluginCatalog.ApprovedSource source) {
        return source.id.equals(ref.optString("id")) && source.installationId.equals(ref.optString("installationId")) && source.version.equals(ref.optString("version")) && source.approvalRevision == ref.optLong("approvalRevision", -1) && source.sourceSha256.equals(ref.optString("sourceSha256"));
    }
    public static JSONObject reference(ExecutablePluginCatalog.ApprovedSource source) throws Exception {
        return new JSONObject().put("id", source.id).put("name", source.name).put("version", source.version).put("installationId", source.installationId).put("approvalRevision", source.approvalRevision).put("sourceSha256", source.sourceSha256);
    }
}
