package dev.pocketengine.phone.observation;

import org.json.JSONObject;

/** Read-only presentation of one original run's observation; never grants capture or input authority. */
public final class PhoneObservationPreview {
    private PhoneObservationPreview() {}

    public static final class Identity {
        public final String taskId, runId;
        public final int epoch;
        public Identity(String taskId, String runId, int epoch) {
            if (taskId == null || taskId.isBlank() || runId == null || runId.isBlank() || epoch < 0)
                throw new IllegalArgumentException("original_phone_run_required");
            this.taskId = taskId; this.runId = runId; this.epoch = epoch;
        }
        public boolean matchesTask(JSONObject task) {
            return task != null && taskId.equals(task.opt("id")) && runId.equals(task.opt("runId"))
                    && exactEpoch(task.opt("coordinatorEpoch")) == epoch;
        }
        public boolean matchesObservation(JSONObject observation) {
            return observation != null && taskId.equals(observation.opt("taskId")) && runId.equals(observation.opt("runId"))
                    && exactEpoch(observation.opt("coordinatorEpoch")) == epoch;
        }
        public JSONObject request() throws Exception {
            return new JSONObject().put("taskId", taskId).put("expectedRunId", runId).put("expectedEpoch", epoch);
        }
    }

    /** Both absent preserves the existing owner inspector. A partial or coerced identity is rejected. */
    public static Identity expected(JSONObject input) {
        boolean run = input.has("expectedRunId"), epoch = input.has("expectedEpoch");
        if (!run && !epoch) return null;
        Object taskValue = input.opt("taskId"), runValue = input.opt("expectedRunId");
        int exact = exactEpoch(input.opt("expectedEpoch"));
        if (!run || !epoch || !(taskValue instanceof String) || !(runValue instanceof String) || exact < 0)
            throw new IllegalArgumentException("original_phone_preview_identity_invalid");
        return new Identity((String) taskValue, (String) runValue, exact);
    }

    public record State(boolean hasFrame, boolean fresh, long observedAtUptimeMs, String packageName, String reason) {}

    public static State present(Identity identity, JSONObject observation, long nowUptime) {
        if (observation == null) return absent("no_observation");
        if (identity == null || !identity.matchesObservation(observation)) return absent("original_run_changed");
        JSONObject handoff = observation.optJSONObject("ownerHandoff");
        if (handoff != null && ("main".equals(handoff.optString("state")) || "unknown".equals(handoff.optString("state"))))
            return absent("owner_handoff");
        if (!Boolean.TRUE.equals(observation.opt("ok")) || !"background".equals(observation.optString("executionMode"))
                || observation.optInt("displayId", -1) <= 0 || observation.optString("sessionId").isBlank()
                || observation.optString("observationId").isBlank()) return absent("no_observation");
        String pkg = observation.optString("packageName");
        if (pkg.isBlank() || observation.optBoolean("frameEmpty") || "display_has_no_frame".equals(observation.optString("screenState")))
            return absent("no_app_content");
        if (observation.optBoolean("imageUnavailable") || !ObservationReport.hasImage(observation))
            return absent("no_image");
        if (observation.optInt("imageWidth", 0) <= 0 || observation.optInt("imageHeight", 0) <= 0
                || observation.optInt("imageWidth") != observation.optInt("screenWidth")
                || observation.optInt("imageHeight") != observation.optInt("screenHeight")) return absent("invalid_image_metadata");
        long at = exactLong(observation.opt("observedAtUptimeMs"));
        boolean fresh = at >= 0 && nowUptime >= at && nowUptime - at <= 120_000;
        return new State(true, fresh, at, pkg, fresh ? "observed" : "stale_observation");
    }

    private static State absent(String reason) { return new State(false, false, -1, "", reason); }
    private static int exactEpoch(Object value) { long n = exactLong(value); return n >= 0 && n <= Integer.MAX_VALUE ? (int) n : -1; }
    private static long exactLong(Object value) { return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : -1; }
}
