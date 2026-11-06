package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.context.CompactionPlan;
import dev.pocketengine.features.memory.TaskMemoryHistory;
import org.json.JSONArray;

/**
 * Request-only materialization and retry comparison against the same native provenance.
 * Neither a permission cache nor the final budgeted request. Physical-call admission remains
 * with RequestProjection.prepared; this object never persists materialized source bytes.
 */
final class RequestHistoryProjection {
    private final AgentRunner.RequestProjection source;
    private final JSONArray provenance;
    private final JSONArray messages;

    private RequestHistoryProjection(AgentRunner.RequestProjection source, JSONArray provenance,
                                     JSONArray messages) {
        this.source = source;
        this.provenance = provenance;
        this.messages = messages;
    }

    static RequestHistoryProjection prepare(AgentRunner.RequestProjection source, JSONArray history,
                                            ModelConfig config, RunControl control) throws Exception {
        JSONArray materialized = history;
        if (source != null) materialized = source.project(history, config, control);
        else if (hasContentReferences(history))
            throw new IllegalStateException("附件或原文请求投影未安装，不能把引用冒充真实内容");
        return new RequestHistoryProjection(source, history, TaskMemoryHistory.wireMessages(materialized));
    }

    JSONArray provenance() { return provenance; }
    JSONArray messages() { return messages; }

    /** Re-materialize, never reuse an earlier authorization or a cached comparison result. */
    boolean current(ModelConfig config, RunControl control) throws Exception {
        if (source == null) return true;
        JSONArray current = TaskMemoryHistory.wireMessages(source.project(provenance, config, control));
        return CompactionPlan.fingerprint(messages).equals(CompactionPlan.fingerprint(current));
    }

    private static boolean hasContentReferences(JSONArray history) throws Exception {
        for (int i = 0; i < history.length(); i++)
            for (String key : new String[]{"ownerAttachmentRefs", "ownerSourceRefs", "ownerPreviewRefs"}) {
                JSONArray refs = history.getJSONObject(i).optJSONArray(key);
                if (refs != null && refs.length() > 0) return true;
            }
        return false;
    }
}
