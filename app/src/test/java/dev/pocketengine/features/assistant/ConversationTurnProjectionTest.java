package dev.pocketengine.features.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConversationTurnProjectionTest {
    private static JSONObject turn(String conversation, String run) throws Exception {
        return new JSONObject().put("conversationId", conversation).put("taskId", "task-" + conversation)
                .put("runId", run).put("epoch", 1).put("resourceState", "running");
    }
    private static JSONObject snapshot(JSONObject... turns) throws Exception {
        return new JSONObject().put("activeTurns", new JSONArray(java.util.List.of(turns)))
                .put("retainedTurns", new JSONArray()).put("running", turns.length > 0);
    }
    @Test public void concurrentOtherConversationNeverBecomesTheCurrentStopTarget() throws Exception {
        JSONObject a = turn("a", "run-a"), b = turn("b", "run-b"), state = snapshot(a, b);
        assertEquals("run-b", ConversationTurnProjection.current(state, "b", a, row -> true).getString("runId"));
        assertNull(ConversationTurnProjection.current(state, "c", a, row -> true));
        assertNull(ConversationTurnProjection.current(state, "", a, row -> true));
    }
    @Test public void theNewSnapshotWinsOverAnOlderRememberedRun() throws Exception {
        JSONObject old = turn("a", "old"), current = turn("a", "new");
        assertEquals("new", ConversationTurnProjection.current(snapshot(current), "a", old, row -> true).getString("runId"));
        assertNull(ConversationTurnProjection.current(snapshot(), "a", old, row -> true));
        assertNull(ConversationTurnProjection.current(snapshot(current), "a", old, row -> false));
    }
    @Test public void retainedServicesStayAddressableWithoutOccupyingAWorker() throws Exception {
        JSONObject retained = turn("a", "same-original").put("resourceState", "retained").put("workerSlotHeld", false);
        JSONObject state = snapshot().put("retainedTurns", new JSONArray().put(retained));
        JSONObject selected = ConversationTurnProjection.current(state, "a", null, row -> true);
        assertEquals("same-original", selected.getString("runId")); assertFalse(selected.getBoolean("workerSlotHeld"));
        assertNull(ConversationTurnProjection.current(state, "b", retained, row -> true));
    }
    @Test public void missingAndAmbiguousIdentitiesNeverInventAControlTarget() throws Exception {
        assertNull(ConversationTurnProjection.current(snapshot(turn("a", "").put("epoch", 0)), "a", null, row -> true));
        assertNull(ConversationTurnProjection.current(snapshot(turn("a", "one"), turn("a", "two")), "a", null, row -> true));
        JSONObject legacy = new JSONObject().put("running", true).put("activeTurn", turn("other", "other-run"));
        assertNull(ConversationTurnProjection.current(legacy, "a", null, row -> true));
    }
}
