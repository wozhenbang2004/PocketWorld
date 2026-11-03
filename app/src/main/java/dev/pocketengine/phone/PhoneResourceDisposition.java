package dev.pocketengine.phone;

import org.json.JSONArray;
import org.json.JSONObject;

/** Evidence for the existing original-run cleanup policy, not a lease, scheduler or permission. */
public final class PhoneResourceDisposition {
    private PhoneResourceDisposition() {}

    public static JSONObject held(String task, String run, int epoch, String reason, Long deadline, JSONObject audio) throws Exception {
        if (!java.util.Set.of("awaiting_owner", "manual_takeover", "reconciliation").contains(reason)) throw new IllegalArgumentException("unknown_phone_hold_reason");
        if (deadline != null && deadline < 1) throw new IllegalArgumentException("invalid_original_deadline");
        return identity(task, run, epoch).put("state", "held_for_owner").put("reason", reason)
                .put("deadlineAt", deadline == null || deadline == Long.MAX_VALUE ? JSONObject.NULL : deadline)
                .put("deadlinePolicy", deadline == null ? "unverified" : deadline == Long.MAX_VALUE ? "until_original_task_stops" : "original_task_deadline")
                .put("manualRelease", "stop_original_run").put("audioRecovery", audioState(audio));
    }

    public static JSONObject release(String task, String run, int epoch, JSONObject receipt) throws Exception {
        boolean confirmed = receipt != null && Boolean.TRUE.equals(receipt.opt("releaseConfirmed"));
        return identity(task, run, epoch).put("state", confirmed ? "release_confirmed" : "release_unknown")
                .put("reason", confirmed ? "native_release_receipt" : "native_release_unconfirmed")
                .put("releaseConfirmed", confirmed).put("audioRecovery", audioState(receipt == null ? null : receipt.optJSONObject("audioRestore")));
    }

    public static JSONObject pending(String task, String run, int epoch) throws Exception {
        return identity(task, run, epoch).put("state", "release_pending").put("reason", "original_cleanup_queued").put("releaseConfirmed", false);
    }

    private static JSONObject identity(String task, String run, int epoch) throws Exception {
        if (task == null || task.isBlank() || run == null || run.isBlank() || epoch < 1) throw new IllegalArgumentException("original_phone_resource_identity_required");
        return new JSONObject().put("schemaVersion", 1).put("taskId", task).put("runId", run).put("coordinatorEpoch", epoch)
                .put("policy", "release_owned_display_on_exit").put("scope", "original_owned_display_and_activity")
                .put("observedAt", System.currentTimeMillis()).put("leaseExpiryIsReleaseProof", false).put("thirdPartyProcessesForceStopped", false);
    }

    /** A late cleanup receipt belongs to its captured run, even when the task now has a newer run. */
    public static boolean matchesTask(JSONObject task, JSONObject resources) {
        if (task == null || resources == null) return false;
        Object taskEpoch = task.opt("coordinatorEpoch"), receiptEpoch = resources.opt("coordinatorEpoch");
        return task.opt("id") instanceof String && task.opt("runId") instanceof String
                && task.opt("id").equals(resources.opt("taskId")) && task.opt("runId").equals(resources.opt("runId"))
                && exactEpoch(taskEpoch) > 0 && exactEpoch(taskEpoch) <= Integer.MAX_VALUE && exactEpoch(taskEpoch) == exactEpoch(receiptEpoch);
    }

    public static JSONObject forTask(JSONObject task) throws Exception {
        JSONObject evidence = task == null ? null : task.optJSONObject("evidence");
        JSONObject found = evidence == null ? null : evidence.optJSONObject("phoneResources");
        if (!matchesTask(task, found)) found = null;
        JSONArray events = task == null ? null : task.optJSONArray("events");
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null || !"phone_resource_release".equals(event.optString("type"))) continue;
            JSONObject actual = event.optJSONObject("phoneResources");
            if (matchesTask(task, actual)) found = actual;
        }
        return found == null ? null : new JSONObject(found.toString());
    }

    private static long exactEpoch(Object value) { return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : -1; }
    private static String audioState(JSONObject audio) {
        if (audio == null) return "unverified";
        if ("audio_not_configured".equals(audio.optString("status"))) return "not_configured";
        Object pending = audio.opt("pending");
        if (Boolean.TRUE.equals(pending) || "audio_recovery_pending".equals(audio.optString("status"))) return "pending";
        if (pending instanceof Integer || pending instanceof Long) {
            long count = ((Number) pending).longValue();
            if (count > 0) return "pending";
            if (count == 0) return "restored_or_preserved";
        }
        return "unverified";
    }
}
