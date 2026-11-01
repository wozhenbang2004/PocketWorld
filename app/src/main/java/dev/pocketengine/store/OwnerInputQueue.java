package dev.pocketengine.store;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** State of the existing ownerInputs rows, not another inbox or an execution authority. */
final class OwnerInputQueue {
    private OwnerInputQueue() {}

    static void accepted(JSONObject row, boolean taskGoal) throws Exception {
        row.put("queueFormat", 1).put("queueRevision", 1L)
                .put("queueState", taskGoal ? "admitted" : "pending")
                .put("queueRole", taskGoal ? "task_goal" : "followup")
                .put("acceptedPayloadSha256", row.getString("payloadSha256"));
    }

    static boolean tracked(JSONObject row) {
        return Integer.valueOf(1).equals(row.opt("queueFormat"));
    }

    static String state(JSONObject row) {
        return tracked(row) ? row.optString("queueState", "unconfirmed") : "legacy_unconfirmed";
    }

    static boolean withdrawn(JSONObject row) {
        return "withdrawn".equals(state(row));
    }

    static boolean mutable(JSONObject row) {
        return tracked(row) && "followup".equals(row.optString("queueRole"))
                && "pending".equals(state(row)) && integer(row.opt("ownerMessageRevision")) > 0
                && row.opt("ownerRequestFingerprint") instanceof String && row.optString("ownerRequestFingerprint").matches("[a-f0-9]{64}");
    }

    static long integer(Object value) {
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : -1;
    }

    static long revision(JSONObject row) {
        return integer(row.opt("queueRevision"));
    }

    static void requirePending(JSONObject row, long queueRevision, long messageRevision) {
        if (!mutable(row)) throw new IllegalStateException("这条输入已被接手或状态无法确认，不能编辑或撤回");
        if (queueRevision < 1 || messageRevision < 1 || revision(row) != queueRevision
                || integer(row.opt("ownerMessageRevision")) != messageRevision)
            throw new IllegalStateException("输入已变化，请刷新后重新确认");
    }

    static void changed(JSONObject row, String state) throws Exception {
        long before = revision(row);
        if (before < 1) throw new IllegalStateException("输入没有可核对的队列版本");
        row.put("queueRevision", Math.addExact(before, 1L)).put("queueState", state);
    }

    /** Claim is durable before returning any body. It does not mean a model received the input. */
    static boolean claim(JSONObject row, String run, int epoch, long now) throws Exception {
        if (!tracked(row) || withdrawn(row) || "materialized".equals(state(row))) return false;
        if ("pending".equals(state(row)) || "admitted".equals(state(row))) changed(row, "claimed");
        else if (!"claimed".equals(state(row))) throw new IllegalStateException("输入接手状态无效");
        boolean changedBinding = !run.equals(row.optString("claimRunId")) || epoch != row.optInt("claimEpoch", -1);
        if (changedBinding) row.put("claimRunId", run).put("claimEpoch", epoch).put("claimedAt", now);
        return changedBinding;
    }

    /** Only an actual checkpoint carrying the native message identity settles materialization. */
    static boolean checkpoint(JSONArray rows, JSONArray messages, String run, int epoch) throws Exception {
        Map<String, JSONObject> included = new HashMap<>();
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String id = message.optString("ownerMessageId");
            if (!id.isEmpty() && included.put(id, message) != null)
                throw new SecurityException("检查点重复了同一主人输入");
        }
        boolean changed = false;
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i), message = included.get(row.getString("messageId"));
            if (message == null || !tracked(row)) continue;
            if (withdrawn(row)) throw new SecurityException("已撤回输入不能重新进入检查点");
            // Initial goals are already admitted in the task identity. Followups must have been claimed.
            if (!"materialized".equals(state(row)) && !"claimed".equals(state(row))
                    && !"admitted".equals(state(row)))
                throw new SecurityException("检查点不能跳过主人输入的原子接手");
            long actualRevision = integer(row.opt("ownerMessageRevision"));
            if (actualRevision > 0 && integer(message.opt("ownerMessageRevision")) != actualRevision)
                throw new SecurityException("检查点不是当前接手的主人消息版本");
            if (!"task_goal".equals(row.optString("queueRole"))
                    && !row.optString("mode").equals(message.optString("ownerInputMode")))
                throw new SecurityException("检查点的输入方式与接手记录不同");
            if (!"materialized".equals(state(row)) || !run.equals(row.optString("materializedRunId"))
                    || epoch != row.optInt("materializedEpoch", -1)) {
                changed(row, "materialized");
                // Latest actual checkpoint membership, not a new delivery/consumption claim.
                // The original accepted identity and each older run's raw checkpoint remain intact.
                row.put("materializedRunId", run).put("materializedEpoch", epoch);
                changed = true;
            }
        }
        return changed;
    }

    static JSONObject view(JSONObject row, boolean inCheckpoint, boolean detail) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : new String[]{"messageId", "clientMessageId", "mode", "acceptedTaskId", "acceptedRunId", "acceptedEpoch",
                "acceptedAt", "ownerMessageRevision", "queueRevision", "queueRole", "claimedAt",
                "materializedRunId", "materializedEpoch", "withdrawnAt", "editedAt"})
            if (row.has(key)) result.put(key, row.get(key));
        String state = inCheckpoint && !withdrawn(row) ? "materialized" : state(row);
        result.put("queueState", state).put("editable", !inCheckpoint && mutable(row))
                .put("executionAuthorized", false).put("queueId", row.getString("messageId"));
        String content = row.optString("content");
        int end = detail ? content.length() : Math.min(240, content.length());
        if (end > 0 && end < content.length() && Character.isHighSurrogate(content.charAt(end - 1)) && Character.isLowSurrogate(content.charAt(end))) end--;
        result.put("content", content.substring(0, end)).put("contentChars", content.length()).put("contentTruncated", end < content.length()).put("attachmentCount", row.optJSONArray("attachments") == null ? 0 : row.getJSONArray("attachments").length())
                .put("sourceReferenceCount", row.optJSONArray("ownerSourceRefs") == null ? 0 : row.getJSONArray("ownerSourceRefs").length());
        if (detail) {
            result.put("attachments", row.optJSONArray("attachments") == null ? new JSONArray() : new JSONArray(row.getJSONArray("attachments").toString()));
            if (row.has("ownerSourceRefs")) result.put("ownerSourceRefs", new JSONArray(row.getJSONArray("ownerSourceRefs").toString()));
        }
        return result;
    }
}
