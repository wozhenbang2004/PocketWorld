package dev.pocketengine.features.assistant;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.NativeReadContextRepair;
import dev.pocketengine.core.NativeToolObservation;
import dev.pocketengine.core.NativeToolPlan;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.OwnerWorkTask;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A smaller view of the original native report grant, not a new authority or
 * executor. Keeps every source, receipt, approval and lifecycle callback intact. */
public final class OwnerWorkReportPort implements CapabilityPort {
    private final CapabilityPort delegate;
    private final Set<String> allowed;
    public OwnerWorkReportPort(CapabilityPort delegate, JSONArray originalTools) throws Exception {
        this.delegate = Objects.requireNonNull(delegate);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < originalTools.length(); i++) {
            String name = originalTools.getString(i);
            if (OwnerWorkTask.RESULT_TOOLS.contains(name)) names.add(name);
        }
        allowed = Set.copyOf(names);
    }
    private void require(String name, JSONObject arguments) {
        if (!allowed.contains(name)) throw new SecurityException("结果处理只使用本次原范围内的核对与补充工具");
        // Creating a deliverable does not grant editing of an existing attachment.
        if ("attachments_create_document".equals(name) && arguments != null
                && (arguments.has("parentArtifactId") || arguments.has("editableSourceId")))
            throw new SecurityException("结果补充只能创建新交付文件；修改原文件须回原任务处理");
    }
    private JSONArray filter(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject definition = source.getJSONObject(i);
            if (allowed.contains(definition.getJSONObject("function").getString("name"))) result.put(new JSONObject(definition.toString()));
        }
        return result;
    }
    @Override public JSONArray tools() throws Exception { return filter(delegate.tools()); }
    @Override public boolean supports(String name) { return allowed.contains(name) && delegate.supports(name); }
    @Override public boolean sideEffecting(String name) { return delegate.sideEffecting(name); }
    @Override public ExecutionPlanning.Access planningAccess(String name, JSONObject arguments) throws Exception { require(name, arguments); return delegate.planningAccess(name, arguments); }
    @Override public void validate(String name, JSONObject arguments) throws Exception { require(name, arguments); delegate.validate(name, arguments); }
    @Override public JSONObject beforeTool(String name, JSONObject arguments, RunControl original) throws Exception { require(name, arguments); return delegate.beforeTool(name, arguments, original); }
    @Override public JSONObject execute(String name, JSONObject arguments, RunControl original) throws Exception { require(name, arguments); return delegate.execute(name, arguments, original); }
    @Override public void afterTool(String name, JSONObject arguments, JSONObject receipt, RunControl original) throws Exception { delegate.afterTool(name, arguments, receipt, original); }
    @Override public JSONObject contextSnapshot(String query) throws Exception { return delegate.contextSnapshot(query); }
    @Override public void beginRun(String goal, RunControl original) throws Exception { delegate.beginRun(goal, original); }
    @Override public void prepareTurnSurface(RunControl original) throws Exception { delegate.prepareTurnSurface(original); }
    @Override public void admitCapabilities(CapabilitySnapshot snapshot, RunControl original) throws Exception { delegate.admitCapabilities(snapshot, original); }
    @Override public JSONObject prepareTurn(String query, int round, int steps, RunControl original) throws Exception {
        JSONObject result = new JSONObject(delegate.prepareTurn(query, round, steps, original).toString());
        return result.put("tools", filter(result.getJSONArray("tools")));
    }
    @Override public boolean responseCurrent(RunControl original) throws Exception { return delegate.responseCurrent(original); }
    @Override public boolean stepReadSourceCurrent(String name, JSONObject receipt, RunControl original) throws Exception { return allowed.contains(name) && delegate.stepReadSourceCurrent(name, receipt, original); }
    @Override public void beforeModelRequest(String requestId, JSONArray messages, RunControl original) throws Exception { delegate.beforeModelRequest(requestId, messages, original); }
    @Override public JSONObject afterModelResponse(String requestId, String answerId, RunControl original) throws Exception { return delegate.afterModelResponse(requestId, answerId, original); }
    @Override public void modelRequestAbandoned(String requestId) throws Exception { delegate.modelRequestAbandoned(requestId); }
    @Override public NativeToolObservation.Requirement observationRequirement(String name, JSONObject arguments, RunControl original) throws Exception { require(name, arguments); return delegate.observationRequirement(name, arguments, original); }
    @Override public NativeToolObservation takeToolObservation(String name, String operation, RunControl original) throws Exception { require(name, null); return delegate.takeToolObservation(name, operation, original); }
    @Override public NativeToolPlan takeToolPlan(String name, String operation, RunControl original) throws Exception { require(name, null); return delegate.takeToolPlan(name, operation, original); }
    @Override public NativeReadContextRepair nativeReadContextRepair(JSONArray messages, RunControl original) throws Exception { return delegate.nativeReadContextRepair(messages, original); }
    @Override public void endRun(RunControl original) throws Exception { delegate.endRun(original); }
    @Override public void close() { delegate.close(); }
}
