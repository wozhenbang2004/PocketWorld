package dev.pocketengine.features.memory;

import static dev.pocketengine.features.memory.MemoryRecallAgentJourneyNativeTest.*;
import static org.junit.Assert.*;

import android.app.Application;
import dev.pocketengine.core.RunOutcome;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** A frozen development corpus through the original native Agent entry, separate from the
 * 24-task Book quality evaluation and its held-out labels. All facts and model responses are
 * synthetic; original SQLite sources, query execution, result events and metering are real.
 * The four paired baselines are legacy_default_no_query_suggestions, not all prior capability:
 * the pre-existing optional suggestion plan is separately measured by MemoryRecallQualityTest. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryRecallAgentCorpusNativeTest {
    private static final String MANIFEST = "memory/recall-agent-corpus-v1.json";
    private static final String MANIFEST_SHA = "a49c6e298f33d26c0569836489bd8661d02d7161044129dab0bec2df03d8d868";
    private static final String SOURCE = "memory/recall-quality-v1.json";
    private static final String SOURCE_SHA = "cf64afb57c785d463c427bd34249d99c92042a7f98a761464503721a09c52656";

    @Test public void frozenManifestUsesTwelveDevelopmentCasesAndFourPairedComparisons() throws Exception {
        JSONObject manifest = manifest();
        Map<String, JSONObject> development = developmentScenarios();
        JSONArray cases = manifest.getJSONArray("cases");
        assertEquals(12, manifest.getInt("scenarioCount")); assertEquals(12, cases.length());
        assertFalse(manifest.getBoolean("holdoutUsed"));
        Set<String> ids = new HashSet<>(); int compared = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject scenario = cases.getJSONObject(i);
            assertTrue(ids.add(scenario.getString("id")));
            assertTrue(development.containsKey(scenario.getString("developmentScenario")));
            if (scenario.getBoolean("compareBaseline")) compared++;
        }
        assertEquals(4, compared); assertEquals(4, manifest.getInt("baselineComparisons"));
    }

    @Test public void twelveNativeAgentCasesRetainScopeTimeSourceAndCostBoundaries() throws Exception {
        JSONObject manifest = manifest();
        Map<String, JSONObject> development = developmentScenarios();
        JSONArray cases = manifest.getJSONArray("cases"), details = new JSONArray();
        List<String> failures = new ArrayList<>();
        int completed = 0, executions = 0, compared = 0, baselineHits = 0, enrichedHits = 0;
        double baselineRr = 0, enrichedRr = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject scenario = cases.getJSONObject(i), source = development.get(scenario.getString("developmentScenario"));
            JSONObject detail = new JSONObject().put("id", scenario.getString("id")).put("category", scenario.getString("category"));
            try {
                Result baseline = null;
                if (scenario.getBoolean("compareBaseline")) { baseline = execute(scenario, source, false); executions++; }
                Result enriched = execute(scenario, source, true); executions++;
                detail.put("enriched", enriched.metrics(source));
                List<String> caseFailures = acceptance(scenario, source, enriched);
                if (baseline != null) {
                    compared++; baselineHits += baseline.hit(source) ? 1 : 0; enrichedHits += enriched.hit(source) ? 1 : 0;
                    baselineRr += baseline.reciprocalRank(source); enrichedRr += enriched.reciprocalRank(source);
                    detail.put("baseline", baseline.metrics(source));
                    if (baseline.hit(source) && !enriched.hit(source)) caseFailures.add("paired relevant recall regressed");
                }
                if (caseFailures.isEmpty()) { completed++; detail.put("passed", true); }
                else { detail.put("passed", false).put("failures", new JSONArray(caseFailures)); failures.add(scenario.getString("id") + ": " + caseFailures); }
            } catch (Exception | AssertionError failure) {
                detail.put("passed", false).put("failureType", failure.getClass().getSimpleName())
                        .put("failureMessage", String.valueOf(failure.getMessage()));
                for (StackTraceElement frame : failure.getStackTrace()) if (frame.getClassName().equals(getClass().getName())) {
                    detail.put("failureLocation", frame.toString()); break;
                }
                failures.add(scenario.getString("id") + ": " + failure.getClass().getSimpleName() + " " + failure.getMessage());
            }
            details.put(detail);
        }
        JSONObject report = new JSONObject().put("datasetId", "memory-recall-agent-corpus-v1")
                .put("manifestSha256", MANIFEST_SHA).put("fixedScenarioCount", 12).put("passedScenarios", completed)
                .put("fixedPairedComparisons", 4).put("completedPairedComparisons", compared)
                .put("expectedNativeExecutions", 16).put("completedNativeExecutions", executions)
                .put("completedNativeExecutionsMeaning", "fixture_and_assertions_completed_not_physical_http_count")
                .put("pairedBaseline", "legacy_default_no_query_suggestions")
                .put("pairedBaselineRepresentsAllPriorCapabilities", false)
                .put("pairedBaselineHits", baselineHits).put("pairedEnrichedHits", enrichedHits)
                .put("pairedBaselineMrr", baselineRr / 4).put("pairedEnrichedMrr", enrichedRr / 4)
                .put("syntheticModelResponses", true).put("liveModelQualityMeasured", false).put("holdoutUsed", false)
                .put("testMethodCountIsNotScenarioCount", true).put("details", details);
        System.out.println("MEMORY_AGENT_CORPUS " + report);
        assertTrue("native Agent corpus failures " + failures, failures.isEmpty());
        assertEquals(12, completed); assertEquals(16, executions); assertEquals(4, compared);
        assertEquals(4, enrichedHits); assertTrue("paired recall did not improve", enrichedHits > baselineHits);
    }

    private static Result execute(JSONObject scenario, JSONObject source, boolean enriched) throws Exception {
        String mode = scenario.getString("mode");
        try (Fixture fixture = new Fixture(source.getString("query"))) {
            Map<String, Original> originals = new LinkedHashMap<>();
            Map<String, JSONObject> currentRecords = new LinkedHashMap<>();
            JSONArray inputs = source.getJSONArray("records");
            for (int i = 0; i < inputs.length(); i++) {
                JSONObject spec = inputs.getJSONObject(i); Original original = fixture.rememberSpec(spec);
                fixture.assertOriginal(original); originals.put(spec.getString("key"), original);
                currentRecords.put(spec.getString("key"), original.record);
            }
            assertTrue("each corpus journey retains multiple original sessions",
                    originals.values().stream().map(original -> original.conversation).distinct().count() >= 2);
            applyMutations(fixture, source, originals, currentRecords);
            byte[] memoryBefore = Files.readAllBytes(fixture.memoryFile.toPath());
            JSONObject arguments = new JSONObject().put("query", source.getString("query")).put("limit", source.getInt("limit"));
            if (enriched) arguments.put("queries", new JSONArray(source.getJSONArray("suggestions").toString()))
                    .put("constraints", new JSONObject(source.getJSONObject("constraints").toString()));
            if (mode.equals("reject_scope")) arguments.put("scope", new JSONObject().put("ownerId", "foreign-owner").put("spaceId", "foreign-group"));
            if (mode.equals("reject_cost")) arguments.put("queries", new JSONArray()
                    .put("historical reference").put("earlier account").put("previous record").put("past notes"));
            try (Gateway gateway = new Gateway(fixture, (request, body) -> {
                if (request == 1) return mode.equals("duplicate_cancel")
                        ? calls(call("corpus-read", arguments), call("corpus-duplicate", arguments))
                        : calls(call("corpus-read", arguments));
                if (mode.equals("duplicate_cancel")) {
                    fixture.tasks.requestCancel(fixture.id, fixture.run); fixture.control.cancel();
                    return calls(call("late-cancelled-read", arguments));
                }
                return answer("The original source checks are recorded. No external action or business result is claimed.");
            })) {
                RunOutcome outcome = fixture.run(gateway);
                // Preserve the actual native return and the independently observed wire
                // projection before assertions. A failed fixture is not zero execution.
                JSONArray nativeReceipts = new JSONArray(), wireReceipts = new JSONArray();
                for (JSONObject event : fixture.events) if ("tool".equals(event.optString("type")) && event.has("receipt"))
                    nativeReceipts.put(new JSONObject().put("tool", event.optString("tool"))
                            .put("status", event.optString("status")).put("receipt", event.getJSONObject("receipt")));
                for (int requestIndex = 1; requestIndex < gateway.requests.size(); requestIndex++) {
                    JSONArray messages = gateway.requests.get(requestIndex).getJSONArray("messages");
                    for (int messageIndex = 0; messageIndex < messages.length(); messageIndex++) {
                        JSONObject message = messages.getJSONObject(messageIndex);
                        if ("tool".equals(message.optString("role"))) wireReceipts.put(new JSONObject()
                                .put("physicalRequest", requestIndex + 1).put("toolCallId", message.optString("tool_call_id"))
                                .put("content", message.opt("content")));
                    }
                }
                System.out.println("MEMORY_AGENT_CASE_OBSERVATION " + new JSONObject().put("id", scenario.getString("id"))
                        .put("category", scenario.getString("category")).put("developmentScenario", scenario.getString("developmentScenario"))
                        .put("arm", enriched ? "enriched" : "legacy_default_no_query_suggestions")
                        .put("nativeOutcome", outcome.status).put("physicalHttpPosts", gateway.requests.size())
                        .put("originalMeter", fixture.tasks.inferenceUsage(fixture.id)).put("nativeToolReceipts", nativeReceipts)
                        .put("wireToolResults", wireReceipts).put("syntheticDevelopmentFactsOnly", true));
                gateway.assertHealthy();
                assertEquals(mode.equals("duplicate_cancel") ? "cancelled" : "needs_confirmation", outcome.status);
                assertEquals(2, gateway.requests.size());
                List<JSONObject> returned = toolResults(gateway.requests.get(1));
                assertEquals(mode.equals("duplicate_cancel") ? 2 : 1, returned.size());
                JSONObject result = returned.get(0), task = fixture.tasks.get(fixture.id, false);
                boolean rejected = mode.startsWith("reject_");
                if (rejected) { assertEquals("invalid_arguments", result.getString("code")); assertFalse(result.getBoolean("ok")); }
                else if (enriched) {
                    assertTrue(result.getBoolean("ok")); assertEquals("prepared", result.getString("phase"));
                    assertFalse(result.getBoolean("modelUseVerified")); assertFalse(result.getBoolean("executionAuthorized"));
                    assertEquals(0, result.getJSONObject("queryPlan").getInt("additionalModelRequests"));
                    assertEquals(0, result.getJSONObject("queryPlan").getInt("additionalNetworkRequests"));
                    assertTrue(result.getString("context").length() <= 12000);
                }
                JSONArray records = result.optJSONArray("records");
                List<String> keys = recordKeys(records == null ? new JSONArray() : records, currentRecords);
                assertTrue(keys.size() <= source.getInt("limit"));
                if (!rejected) for (int i = 0; i < records.length(); i++) {
                    JSONObject row = records.getJSONObject(i), current = currentRecords.get(keys.get(i));
                    assertEquals(current.getLong("revision"), row.getLong("revision"));
                    assertEquals("owner", row.getString("ownerId")); assertEquals("private", row.getString("spaceId"));
                    Original original = originals.get(keys.get(i));
                    assertFalse(fixture.originals.resolveMetadata(original.ref.sourceId).state.deleted);
                    assertEquals(original.ref, fixture.originals.resolveMetadata(original.ref.sourceId).state.ref);
                }
                if (mode.equals("duplicate_cancel")) {
                    assertEquals(keys, recordKeys(returned.get(1).getJSONArray("records"), currentRecords));
                    assertEquals(result.getString("contextDigest"), returned.get(1).getString("contextDigest"));
                    assertTrue(task.getBoolean("cancelRequested"));
                }
                int expectedPrepared = rejected || !enriched ? 0 : mode.equals("duplicate_cancel") ? 2 : 1;
                assertEquals(expectedPrepared, events(task, "memory_tool_reference_prepared").size());
                assertEquals(0, operations(task, "memory_search").size());
                int attempts = fixture.tasks.inferenceUsage(fixture.id).getInt("attempts");
                assertEquals(2, attempts);
                if (!mode.equals("duplicate_cancel")) assertEquals(2, fixture.tasks.inferenceUsage(fixture.id).getInt("completed"));
                for (JSONObject row : operations(task, TaskMemoryJournal.TOOL)) if (row.has("receipt")) {
                    assertFalse(row.getJSONObject("receipt").getBoolean("executionAuthorized"));
                    assertFalse(row.getJSONObject("receipt").getBoolean("modelUseVerified"));
                }
                assertArrayEquals("read-only Agent recall changed the authoritative Book", memoryBefore, Files.readAllBytes(fixture.memoryFile.toPath()));
                return new Result(keys, result.toString(), outcome.status, attempts, expectedPrepared);
            }
        }
    }

    private static void applyMutations(Fixture fixture, JSONObject scenario, Map<String, Original> originals,
            Map<String, JSONObject> records) throws Exception {
        JSONArray mutations = scenario.getJSONArray("mutations");
        for (int i = 0; i < mutations.length(); i++) {
            JSONObject mutation = mutations.getJSONObject(i); String key = mutation.getString("key");
            Original original = originals.get(key); JSONObject record = records.get(key);
            MemoryBook.BeforeForget retire = removed -> {
                fixture.tasks.invalidateMemoryContexts(MemoryBook.Scope.from(scope()), removed);
                fixture.host.clearWorkingMemory(scope());
            };
            if (mutation.getString("type").equals("correct")) records.put(key, fixture.memory.correctReviewed(scope(),
                    record.getString("id"), record.getLong("revision"), mutation.getString("text"), retire).getJSONObject("record"));
            else if (mutation.getString("type").equals("source_delete")) {
                fixture.originals.transaction(() -> { fixture.originals.deleteConversation(original.conversation, "user_conversation_delete"); return null; });
                SourceRef.State actual = fixture.originals.resolveMetadata(original.ref.sourceId).state;
                assertTrue(actual.deleted); fixture.memory.sourceDeleted(scope(), actual.ref, retire);
                assertNull(fixture.originals.read(() -> fixture.originals.message(original.conversation, original.ref.sourceId)));
            } else throw new AssertionError("frozen native corpus has an unsupported mutation");
        }
    }

    private static List<String> acceptance(JSONObject scenario, JSONObject source, Result result) throws Exception {
        List<String> failures = new ArrayList<>(); String mode = scenario.getString("mode");
        if (mode.startsWith("reject_")) return failures; // Native refusal already checked before returning.
        Set<String> expected = strings(source.getJSONArray("expectedRelevant"));
        if (!result.keys.containsAll(expected)) failures.add("relevant original record missing");
        for (String forbidden : strings(source.getJSONArray("forbiddenRecords")))
            if (result.keys.contains(forbidden)) failures.add("forbidden record returned");
        JSONArray forbiddenText = source.getJSONArray("forbiddenText");
        for (int i = 0; i < forbiddenText.length(); i++)
            if (result.content.contains(forbiddenText.getString(i))) failures.add("withdrawn or superseded text returned");
        if (mode.equals("no_answer") && !strings(source.getJSONArray("allowedSupport")).containsAll(result.keys))
            failures.add("unsupported answer candidate returned");
        return failures;
    }

    private static List<String> recordKeys(JSONArray rows, Map<String, JSONObject> records) throws Exception {
        List<String> result = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            String id = rows.getJSONObject(i).getString("id"), key = null;
            for (Map.Entry<String, JSONObject> record : records.entrySet()) if (id.equals(record.getValue().getString("id"))) { key = record.getKey(); break; }
            assertNotNull("unregistered original record", key); assertTrue(unique.add(key)); result.add(key);
        }
        return result;
    }

    private static final class Result {
        final List<String> keys; final String content, outcome; final int modelAttempts, prepared;
        Result(List<String> keys, String content, String outcome, int modelAttempts, int prepared) {
            this.keys = keys; this.content = content; this.outcome = outcome; this.modelAttempts = modelAttempts; this.prepared = prepared;
        }
        boolean hit(JSONObject scenario) throws Exception { return reciprocalRank(scenario) > 0; }
        double reciprocalRank(JSONObject scenario) throws Exception {
            Set<String> expected = strings(scenario.getJSONArray("expectedRelevant"));
            for (int i = 0; i < keys.size(); i++) if (expected.contains(keys.get(i))) return 1d / (i + 1);
            return 0;
        }
        JSONObject metrics(JSONObject scenario) throws Exception {
            int hits = 0; Set<String> expected = strings(scenario.getJSONArray("expectedRelevant"));
            for (String key : keys) if (expected.contains(key)) hits++;
            return new JSONObject().put("retrieved", keys.size()).put("relevantExpected", expected.size()).put("relevantRetrieved", hits)
                    .put("reciprocalRank", reciprocalRank(scenario)).put("mainAttempts", modelAttempts).put("nativePreparedSelections", prepared)
                    .put("outcome", outcome).put("modelUseVerified", false).put("causalEffectVerified", false);
        }
    }

    private static JSONObject manifest() throws Exception {
        byte[] bytes = resource(MANIFEST); assertEquals(MANIFEST_SHA, sha256(bytes));
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    private static Map<String, JSONObject> developmentScenarios() throws Exception {
        byte[] bytes = resource(SOURCE); assertEquals(SOURCE_SHA, sha256(bytes));
        JSONArray values = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getJSONArray("scenarios");
        Map<String, JSONObject> development = new LinkedHashMap<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            if ("development".equals(value.getString("split"))) development.put(value.getString("id"), value);
        }
        assertEquals(16, development.size()); return development;
    }
    private static byte[] resource(String name) throws Exception {
        try (InputStream input = MemoryRecallAgentCorpusNativeTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input != null) return input.readAllBytes();
        }
        return Files.readAllBytes(Path.of(System.getProperty("pocket.gateway.source", "."), "app/src/test/resources", name));
    }
    private static Set<String> strings(JSONArray array) throws Exception {
        Set<String> values = new HashSet<>(); for (int i = 0; i < array.length(); i++) values.add(array.getString(i)); return values;
    }
    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format("%02x", b & 255)); return value.toString();
    }
}
