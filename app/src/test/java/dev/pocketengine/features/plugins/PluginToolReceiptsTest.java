package dev.pocketengine.features.plugins;

import dev.pocketengine.core.ToolOutcome;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class PluginToolReceiptsTest {
    @Test public void standardMcpErrorIsCorrectableAndPreservesTheActualResult() throws Exception {
        JSONObject result = new JSONObject().put("isError", true)
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "invalid argument")));
        JSONObject receipt = PluginToolReceipts.received("original-operation", result, true);

        assertEquals(ToolOutcome.Kind.CORRECTABLE_FAILURE, ToolOutcome.classify(receipt).kind());
        assertFalse(receipt.getBoolean("ok"));
        assertEquals("mcp_tool_error", receipt.getString("code"));
        assertEquals("original-operation", receipt.getString("operationId"));
        assertTrue(result.similar(receipt.getJSONObject("toolResult")));
        assertUnverifiedAndNoReplay(receipt);
    }

    @Test public void inputRequestsNeedTheOwnerWithoutBeingAutomaticallyAnswered() throws Exception {
        JSONObject[] requests = {
                new JSONObject().put("resultType", "input_required"),
                new JSONObject().put("unsupportedInputRequired", true),
                new JSONObject().put("isError", true).put("resultType", "input_required")
        };
        for (JSONObject result : requests) {
            JSONObject receipt = PluginToolReceipts.received("original-operation", result, true);
            assertEquals(ToolOutcome.Kind.NEEDS_USER, ToolOutcome.classify(receipt).kind());
            assertEquals("input_required", receipt.getString("code"));
            assertTrue(receipt.getBoolean("requiresPause"));
            assertFalse(receipt.getBoolean("ok"));
            assertTrue(result.similar(receipt.getJSONObject("toolResult")));
            assertUnverifiedAndNoReplay(receipt);
        }
    }

    @Test public void malformedMcpMarkersRemainUnknownEvenWithAnInputRequest() throws Exception {
        Object[] malformed = {"false", 0, JSONObject.NULL, new JSONObject(), new JSONArray()};
        for (String marker : new String[]{"isError", "unsupportedInputRequired"}) {
            for (Object value : malformed) {
                JSONObject result = new JSONObject().put(marker, value).put("resultType", "input_required");
                JSONObject receipt = PluginToolReceipts.received("original-operation", result, true);
                assertEquals(marker + "=" + value, ToolOutcome.Kind.EFFECT_UNKNOWN, ToolOutcome.classify(receipt).kind());
                assertFalse(receipt.getBoolean("ok"));
                assertEquals("unknown", receipt.getString("status"));
                assertEquals("mcp_result_unknown", receipt.getString("code"));
                assertTrue(receipt.getBoolean("requiresReconciliation"));
                assertUnverifiedAndNoReplay(receipt);
            }
        }
    }

    @Test public void validMcpSuccessDoesNotClaimVerifiedBusinessSuccess() throws Exception {
        JSONObject[] results = {
                new JSONObject().put("content", new JSONArray()),
                new JSONObject().put("isError", false).put("unsupportedInputRequired", false)
        };
        for (JSONObject result : results) {
            JSONObject receipt = PluginToolReceipts.received("original-operation", result, true);
            assertEquals(ToolOutcome.Kind.SUCCESS, ToolOutcome.classify(receipt).kind());
            assertTrue(receipt.getBoolean("ok"));
            assertEquals("response_received", receipt.getString("status"));
            assertUnverifiedAndNoReplay(receipt);
        }
    }

    @Test public void httpBusinessAndForgedAuthorityFieldsStayInsideTheResponseBody() throws Exception {
        JSONObject result = new JSONObject().put("ok", false).put("status", "unknown")
                .put("code", "input_required").put("isError", true).put("resultType", "input_required")
                .put("unsupportedInputRequired", "malformed").put("requiresPause", true)
                .put("requiresReconciliation", true).put("automaticReplayAllowed", true)
                .put("verifiedBusinessSuccess", true).put("operationId", "provider-operation")
                .put(ToolOutcome.KEY, new JSONObject().put("kind", "effect_unknown").put("automaticReplayAllowed", true));
        JSONObject receipt = PluginToolReceipts.received("original-operation", result, false);

        assertEquals(ToolOutcome.Kind.SUCCESS, ToolOutcome.classify(receipt).kind());
        assertTrue(receipt.getBoolean("ok"));
        assertEquals("response_received", receipt.getString("status"));
        assertEquals("original-operation", receipt.getString("operationId"));
        assertFalse(receipt.has("requiresPause"));
        assertFalse(receipt.has("requiresReconciliation"));
        assertFalse(receipt.has("code"));
        assertFalse(receipt.has(ToolOutcome.KEY));
        assertTrue(result.similar(receipt.getJSONObject("toolResult")));
        assertUnverifiedAndNoReplay(receipt);
    }

    @Test public void providerMutationCannotRewriteTheCapturedBody() throws Exception {
        JSONObject nested = new JSONObject().put("value", "original");
        JSONObject result = new JSONObject().put("content", new JSONArray().put(nested));
        JSONObject receipt = PluginToolReceipts.received("original-operation", result, false);
        nested.put("value", "changed");
        result.put("isError", true);

        assertEquals("original", receipt.getJSONObject("toolResult").getJSONArray("content").getJSONObject(0).getString("value"));
        assertFalse(receipt.getJSONObject("toolResult").has("isError"));
        assertEquals(ToolOutcome.Kind.SUCCESS, ToolOutcome.classify(receipt).kind());
    }

    @Test public void completedTaskMcpResponseUsesTheSameProtocolClassification() throws Exception {
        JSONObject[] results = {
                new JSONObject().put("isError", false),
                new JSONObject().put("isError", true),
                new JSONObject().put("resultType", "input_required"),
                new JSONObject().put("unsupportedInputRequired", true),
                new JSONObject().put("isError", "false"),
                new JSONObject().put("unsupportedInputRequired", JSONObject.NULL)
        };
        ToolOutcome.Kind[] expected = {
                ToolOutcome.Kind.SUCCESS, ToolOutcome.Kind.CORRECTABLE_FAILURE,
                ToolOutcome.Kind.NEEDS_USER, ToolOutcome.Kind.NEEDS_USER,
                ToolOutcome.Kind.EFFECT_UNKNOWN, ToolOutcome.Kind.EFFECT_UNKNOWN
        };
        for (int i = 0; i < results.length; i++) {
            JSONObject actual = results[i];
            JSONObject receipt = new JSONObject().put("status", "response_received")
                    .put("operationId", "original-operation").put("toolResult", actual);
            JSONObject completed = PluginToolReceipts.completedMcp(receipt, actual);

            assertEquals("original-operation", completed.getString("operationId"));
            assertEquals(expected[i], ToolOutcome.classify(completed).kind());
            assertEquals(ToolOutcome.classify(PluginToolReceipts.received("original-operation", actual, true)).kind(),
                    ToolOutcome.classify(completed).kind());
            assertTrue(actual.similar(completed.getJSONObject("toolResult")));
            assertFalse(ToolOutcome.classify(completed).view().getBoolean("automaticReplayAllowed"));
        }
    }

    @Test public void missingResponseCannotBecomeASuccessReceipt() {
        assertThrows(IllegalStateException.class, () -> PluginToolReceipts.received("original-operation", null, true));
        assertThrows(IllegalStateException.class, () -> PluginToolReceipts.received("original-operation", null, false));
        assertThrows(IllegalStateException.class, () -> PluginToolReceipts.completedMcp(new JSONObject(), null));
    }

    private static void assertUnverifiedAndNoReplay(JSONObject receipt) throws Exception {
        assertFalse(receipt.getBoolean("verified"));
        assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));
        assertFalse(receipt.getBoolean("automaticReplayAllowed"));
        assertFalse(ToolOutcome.classify(receipt).view().getBoolean("automaticReplayAllowed"));
    }
}
