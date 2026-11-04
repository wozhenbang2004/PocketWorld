package dev.pocketengine.features.assistant;

import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only current-conversation projection. The original native run remains the authority. */
public final class ConversationTurnProjection {
    private ConversationTurnProjection() {}

    public static JSONObject current(JSONObject snapshot, String conversation, JSONObject previous,
                                     Predicate<JSONObject> nativeAvailable) throws Exception {
        if (conversation == null || conversation.isBlank()) return null;
        JSONArray candidates = new JSONArray();
        boolean scopedSnapshot = snapshot.has("activeTurns") || snapshot.has("retainedTurns");
        if (scopedSnapshot) {
            for (String key : new String[]{"activeTurns", "retainedTurns"}) {
                JSONArray rows = snapshot.optJSONArray(key);
                for (int i = 0; rows != null && i < rows.length(); i++) candidates.put(rows.optJSONObject(i));
            }
        } else {
            JSONObject active = snapshot.optJSONObject("activeTurn");
            if (active != null) candidates.put(active);
            if (previous != null && (active == null || !sameRun(active, previous))) candidates.put(previous);
        }
        JSONObject selected = null;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject turn = candidates.optJSONObject(i);
            if (turn == null || !conversation.equals(turn.optString("conversationId")) ||
                    turn.optString("taskId").isBlank() || turn.optString("runId").isBlank()) continue;
            Object epoch = turn.opt("epoch");
            if (!(epoch instanceof Integer || epoch instanceof Long) || ((Number) epoch).longValue() < 1 ||
                    ((Number) epoch).longValue() > Integer.MAX_VALUE || !nativeAvailable.test(turn)) continue;
            if (selected != null && !sameRun(selected, turn)) return null;
            if (selected == null) selected = turn;
        }
        return selected == null ? null : new JSONObject(selected.toString());
    }

    private static boolean sameRun(JSONObject a, JSONObject b) {
        return a.optString("taskId").equals(b.optString("taskId")) && a.optString("runId").equals(b.optString("runId")) &&
                a.opt("epoch") != null && a.opt("epoch").equals(b.opt("epoch"));
    }
}
