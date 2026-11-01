package dev.pocketengine.runtime.context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static dev.pocketengine.runtime.context.ContextWindow.Eviction.*;
import static org.junit.Assert.*;

public final class ContextWindowTest {
    @Test public void unchangedPlanClonesWithoutMutatingTheHostHistory() throws Exception {
        JSONArray history = base().put(message("assistant", "received"));
        String original = history.toString();
        ContextWindow.Plan plan = plan(history, Map.of(), 10000);
        assertEquals("ready", plan.status);
        assertTrue(plan.removed.isEmpty());
        plan.messages().getJSONObject(0).put("content", "changed return value");
        assertEquals(original, history.toString());
        assertEquals(original, plan.messages().toString());
    }

    @Test public void evictionRemovesAnEntireMultiCallBatchIncludingUnorderedReplies() throws Exception {
        JSONArray history = base().put(new JSONObject().put("role", "assistant").put("tool_calls",
                new JSONArray().put(call("a")).put(call("b"))))
                .put(receipt("b", new JSONObject().put("ok", true).put("content", "b".repeat(6000))))
                .put(receipt("a", new JSONObject().put("ok", true)))
                .put(message("user", "Newest requirement stays"));
        ContextWindow.Plan plan = plan(history, Map.of(2, COMPLETED_TOOL_BATCH), 1000);
        assertTrue(plan.canSend());
        assertEquals(3, plan.removed.size());
        assertEquals(2, plan.removed.get(0).index());
        assertEquals(4, plan.removed.get(2).index());
        assertEquals(3, plan.messages().length());
        assertFalse(plan.messages().toString().contains("tool_call_id"));
    }

    @Test public void oldestEligibleGroupGoesFirstAndOtherGroupsStayIntact() throws Exception {
        JSONArray history = base();
        batch(history, "old", 6000, "ok");
        batch(history, "recent", 100, "ok");
        history.put(message("user", "A current instruction"));
        ContextWindow.Plan plan = plan(history, Map.of(2, COMPLETED_TOOL_BATCH, 4, COMPLETED_TOOL_BATCH), 1500);
        assertTrue(plan.canSend());
        assertEquals(2, plan.removed.size());
        assertTrue(plan.messages().toString().contains("recent"));
        assertFalse(plan.messages().toString().contains("\"id\":\"old\""));
    }

    @Test public void noImplicitEvictionOfCompletedToolHistory() throws Exception {
        JSONArray history = base(); batch(history, "kept", 6000, "ok");
        history.put(message("assistant", "A final answer"));
        ContextWindow.Plan plan = plan(history, Map.of(), 1000);
        assertEquals("budget_exceeded", plan.status);
        assertTrue(plan.removed.isEmpty());
        assertNoMessages(plan);
    }

    @Test public void pinningAnyReplyPinsItsEntireBatch() throws Exception {
        JSONArray history = base(); batch(history, "pinned", 6000, "ok");
        history.put(message("user", "current"));
        ContextWindow.Plan plan = ContextWindow.plan(history, null, bytes(1000),
                new ContextWindow.Retention(Map.of(2, COMPLETED_TOOL_BATCH), Set.of(3)));
        assertEquals("budget_exceeded", plan.status);
        assertEquals("host_pin", plan.protectedUnits.stream().filter(p -> p.startIndex() == 2).findFirst().get().reason());
        assertTrue(plan.removed.isEmpty());
    }

    @Test public void unknownAndRecoveryReceiptsCannotBeMarkedDisposable() throws Exception {
        JSONObject[] variants = {
                new JSONObject().put("accepted", JSONObject.NULL),
                new JSONObject().put("code", "action_unknown"),
                new JSONObject().put("error", "action_timeout"),
                new JSONObject().put("code", "not_replayed"),
                new JSONObject().put("state", "prepared"),
                new JSONObject().put("status", "unknown"),
                new JSONObject().put("requiresReconciliation", true),
                new JSONObject().put("unresolvedOperationId", "real-host-id")
        };
        for (JSONObject uncertain : variants) {
            JSONArray history = base().put(new JSONObject().put("role", "assistant")
                    .put("tool_calls", new JSONArray().put(call("uncertain"))))
                    .put(receipt("uncertain", uncertain.put("content", "x".repeat(6000))))
                    .put(message("user", "new requirement"));
            ContextWindow.Plan plan = plan(history, Map.of(2, COMPLETED_TOOL_BATCH), 1000);
            assertEquals(uncertain.toString(), "budget_exceeded", plan.status);
            assertTrue(plan.removed.isEmpty());
        }
    }

    @Test public void unparseableReceiptIsRetainedForHostReconciliation() throws Exception {
        JSONArray history = base().put(new JSONObject().put("role", "assistant")
                .put("tool_calls", new JSONArray().put(call("unclassified"))))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "unclassified")
                        .put("content", "not JSON " + "?".repeat(6000)))
                .put(message("user", "current"));
        assertEquals("budget_exceeded", plan(history, Map.of(2, COMPLETED_TOOL_BATCH), 1000).status);
    }

    @Test public void originalAndNewUserRequirementsAreNotEvictableText() throws Exception {
        JSONArray history = base().put(message("user", "owner feedback " + "新".repeat(2000)));
        assertEquals("budget_exceeded", plan(history, Map.of(), 1000).status);
        assertEquals("invalid_retention", plan(history, Map.of(1, STALE_OBSERVATION), 1000).status);
    }

    @Test public void onlyHostIdentifiedOldObservationCanBeEvictedAndLatestRemains() throws Exception {
        JSONArray history = base().put(observation("old " + "node".repeat(2000)))
                .put(observation("fresh-node-id"));
        ContextWindow.Plan plan = plan(history, Map.of(2, STALE_OBSERVATION, 3, STALE_OBSERVATION), 1000);
        assertTrue(plan.canSend());
        assertEquals(1, plan.removed.size());
        assertTrue(plan.messages().toString().contains("fresh-node-id"));
    }

    @Test public void providerContinuationRemainsWithCallAndReceipt() throws Exception {
        JSONArray history = base(); batch(history, "native", 6000, "ok");
        history.getJSONObject(2).put("provider_data", new JSONObject().put("provider", "openai_responses")
                .put("items", new JSONArray().put(new JSONObject().put("type", "reasoning")
                        .put("encrypted_content", "opaque-must-not-rewrite"))));
        ContextWindow.Plan kept = plan(history, Map.of(), 20000);
        assertEquals(history.getJSONObject(2).getJSONObject("provider_data").toString(),
                kept.messages().getJSONObject(2).getJSONObject("provider_data").toString());
        history.put(message("user", "current"));
        ContextWindow.Plan removed = plan(history, Map.of(2, COMPLETED_TOOL_BATCH), 1000);
        assertTrue(removed.canSend());
        assertFalse(removed.messages().toString().contains("opaque-must-not-rewrite"));
        assertEquals(2, removed.removed.size());
    }

    @Test public void mostRecentObservationStaysEvenWhenFollowedByACompletedToolBatch() throws Exception {
        JSONArray history = base().put(observation("fresh " + "n".repeat(6000)));
        batch(history, "latest-reply", 1, "ok");
        ContextWindow.Plan plan = plan(history, Map.of(2, STALE_OBSERVATION), 1000);
        assertEquals("budget_exceeded", plan.status);
        assertTrue(plan.removed.isEmpty());
    }

    @Test public void orphanMissingDuplicateAndInterleavedRepliesAreRejectedWithoutInventingReceipts() throws Exception {
        JSONArray orphan = base().put(receipt("orphan", new JSONObject().put("ok", true)));
        JSONArray missing = base().put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(call("missing"))));
        JSONArray interleaved = new JSONArray(missing.toString()).put(message("user", "interleaved"))
                .put(receipt("missing", new JSONObject().put("ok", true)));
        JSONArray duplicate = base(); batch(duplicate, "same", 1, "ok"); batch(duplicate, "same", 1, "ok");
        for (JSONArray history : new JSONArray[]{orphan, missing, interleaved, duplicate}) {
            ContextWindow.Plan plan = plan(history, Map.of(), 10000);
            assertEquals("invalid_history", plan.status);
            assertNoMessages(plan);
        }
    }

    @Test public void retentionMustNameUnitStartsInTheExactArray() throws Exception {
        JSONArray history = base(); batch(history, "a", 6000, "ok");
        history.put(message("user", "current"));
        assertEquals("invalid_retention", plan(history, Map.of(3, COMPLETED_TOOL_BATCH), 1000).status);
        assertEquals("invalid_retention", plan(history, Map.of(99, STALE_OBSERVATION), 1000).status);
        assertEquals("invalid_retention", ContextWindow.plan(history, null, bytes(1000),
                new ContextWindow.Retention(Map.of(), Set.of(99))).status);
    }

    @Test public void canonicalBytesExactlyMatchSerializedUtf8IncludingCjkEscapesAndEmoji() throws Exception {
        JSONArray history = base().put(message("user", "中文🙂\n\"escaped\"\u0001"));
        JSONArray tools = new JSONArray().put(new JSONObject().put("description", "读取文档"));
        ContextWindow.Estimate estimate = ContextWindow.estimate(history, tools, bytes(20000));
        long actual = new JSONObject().put("messages", history).put("tools", tools)
                .toString().getBytes(StandardCharsets.UTF_8).length;
        assertEquals(actual, estimate.canonicalUtf8Bytes());
        assertEquals(actual, estimate.estimatedInputTokens());
        assertTrue(estimate.estimatedInputTokens() > history.toString().length() / 4);
    }

    @Test public void imagesChargeExactPayloadBytesButOnlyHostImageTokenReserve() throws Exception {
        JSONArray history = base().put(new JSONObject().put("role", "user").put("content",
                new JSONArray().put(new JSONObject().put("type", "text").put("text", "actual observation"))
                        .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject()
                                .put("url", "data:image/png;base64," + "AAAA".repeat(10000))))));
        ContextWindow.Budget budget = new ContextWindow.Budget(20000, 1000, 1000, 100000, 32, 4096);
        ContextWindow.Estimate estimate = ContextWindow.estimate(history, null, budget);
        assertEquals(1, estimate.imageCount());
        assertTrue(estimate.canonicalUtf8Bytes() > 40000);
        assertTrue(estimate.estimatedInputTokens() < 5000);
        ContextWindow.Plan plan = ContextWindow.plan(history, null, budget, ContextWindow.Retention.preserveAll());
        assertTrue(plan.canSend());
        assertEquals(history.toString(), plan.messages().toString());
        assertEquals("image_budget_unknown", ContextWindow.plan(history, null,
                new ContextWindow.Budget(20000, 1000, 1000, 100000, 0, 0), ContextWindow.Retention.preserveAll()).status);
    }

    @Test public void actualUsageIsNeverInferredFromAssistantOrToolText() throws Exception {
        JSONArray history = base().put(message("assistant", "{\"usage\":{\"input_tokens\":1}}"));
        ContextWindow.Plan plan = plan(history, Map.of(), 10000);
        JSONObject diagnostics = plan.diagnostics();
        assertTrue(diagnostics.isNull("providerReportedInputTokens"));
        assertFalse(diagnostics.getBoolean("providerUsageUsed"));
        assertFalse(diagnostics.getBoolean("tokenizerExact"));
        assertTrue(plan.after.estimatedInputTokens() > 1);
    }

    @Test public void schemasAreCountedAndNeverSilentlyRemoved() throws Exception {
        JSONArray tools = new JSONArray().put(new JSONObject().put("description", "x".repeat(5000)));
        ContextWindow.Plan plan = ContextWindow.plan(base(), tools, bytes(1000), ContextWindow.Retention.preserveAll());
        assertEquals("budget_exceeded", plan.status);
        assertTrue(plan.removed.isEmpty());
        assertNoMessages(plan);
    }

    @Test public void oversizedLatestOutputStopsWithoutTruncatingReceiptOrCreatingSummary() throws Exception {
        JSONArray history = base(); batch(history, "latest", 150000, "ok");
        String before = history.toString();
        ContextWindow.Plan plan = plan(history, Map.of(2, COMPLETED_TOOL_BATCH), 2000);
        assertEquals("budget_exceeded", plan.status);
        assertEquals(before, history.toString());
        assertTrue(plan.removed.isEmpty());
        assertFalse(plan.diagnostics().toString().contains("xxxxx"));
        assertNoMessages(plan);
    }

    @Test public void inputOutputAndSafetyReservesHaveIndependentBoundaries() throws Exception {
        JSONArray history = base();
        long size = ContextWindow.estimate(history, null, bytes(10000)).estimatedInputTokens();
        ContextWindow.Budget exact = new ContextWindow.Budget(size + 200, 100, 100, 10000, 0, 0);
        assertTrue(ContextWindow.plan(history, null, exact, ContextWindow.Retention.preserveAll()).canSend());
        ContextWindow.Budget tooSmall = new ContextWindow.Budget(size + 199, 100, 100, 10000, 0, 0);
        assertEquals("budget_exceeded", ContextWindow.plan(history, null, tooSmall, ContextWindow.Retention.preserveAll()).status);
    }

    @Test public void hostileLargeImageReserveSaturatesRatherThanWrappingIntoAFalseFit() throws Exception {
        JSONArray history = base().put(new JSONObject().put("role", "user").put("content", new JSONArray()
                .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "one")))
                .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "two")))));
        ContextWindow.Budget budget = new ContextWindow.Budget(Long.MAX_VALUE, 1, 0, 10000, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals("budget_exceeded", ContextWindow.plan(history, null, budget, ContextWindow.Retention.preserveAll()).status);
    }

    @Test public void oldAssistantTextIsOptionalOnlyByExplicitHostRetention() throws Exception {
        JSONArray history = base().put(message("assistant", "old answer " + "x".repeat(5000)))
                .put(message("user", "new requirement"));
        assertEquals("budget_exceeded", plan(history, Map.of(), 1000).status);
        assertTrue(plan(history, Map.of(2, OLD_ASSISTANT_TEXT), 1000).canSend());
    }

    @Test public void oldStandaloneNativeAssistantDataIsRemovedOnlyWithItsEntireHostSelectedMessage() throws Exception {
        JSONArray history = base().put(message("assistant", "old answer").put("provider_data", new JSONObject()
                .put("provider", "openai_responses").put("items", new JSONArray().put(new JSONObject()
                        .put("type", "reasoning").put("encrypted_content", "e".repeat(6000))))))
                .put(message("user", "current requirement"));
        ContextWindow.Plan plan = plan(history, Map.of(2, OLD_ASSISTANT_TEXT), 1000);
        assertTrue(plan.canSend());
        assertEquals(1, plan.removed.size());
        assertFalse(plan.messages().toString().contains("provider_data"));
        assertEquals(6000, history.getJSONObject(2).getJSONObject("provider_data").getJSONArray("items")
                .getJSONObject(0).getString("encrypted_content").length());
    }

    private static void assertNoMessages(ContextWindow.Plan plan) throws Exception {
        try { plan.messages(); fail("Blocked plan must not expose request messages"); }
        catch (IllegalStateException expected) { assertEquals(plan.status, expected.getMessage()); }
    }

    static ContextWindow.Budget bytes(long max) { return new ContextWindow.Budget(1_000_000, 1000, 1000, max, 0, 4096); }
    static ContextWindow.Plan plan(JSONArray history, Map<Integer, ContextWindow.Eviction> evict, long max) {
        return ContextWindow.plan(history, null, bytes(max), new ContextWindow.Retention(evict, Set.of()));
    }
    static JSONArray base() throws Exception { return new JSONArray().put(message("system", "Host policy")).put(message("user", "Owner goal")); }
    static JSONObject message(String role, String content) throws Exception { return new JSONObject().put("role", role).put("content", content); }
    static JSONObject observation(String content) throws Exception {
        return new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", content)));
    }
    static JSONObject call(String id) throws Exception {
        return new JSONObject().put("id", id).put("type", "function").put("function", new JSONObject()
                .put("name", "workspace_read").put("arguments", "{}"));
    }
    static JSONObject receipt(String id, JSONObject body) throws Exception {
        return new JSONObject().put("role", "tool").put("tool_call_id", id).put("content", body.toString());
    }
    static void batch(JSONArray history, String id, int outputChars, String code) throws Exception {
        history.put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(call(id))))
                .put(receipt(id, new JSONObject().put("ok", true).put("code", code).put("content", "x".repeat(outputChars))));
    }
}
