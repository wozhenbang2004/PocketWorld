package dev.pocketengine.features.memory;

import static org.junit.Assert.*;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A fixed synthetic retrieval corpus, separate from the native Agent journey.
 * Query suggestions are authored paraphrases, NOT live-model results or independent evidence.
 * Three arms retain the original default query, the pre-existing two-argument query-suggestion
 * plan, and the new constrained plan. Every arm receives the same record/character budget,
 * existing alias projection and live source checks. The old suggestion plan has no new filters;
 * gains over the default query alone must not be attributed entirely to this implementation.
 */
public final class MemoryRecallQualityTest {
    private static final String RESOURCE = "memory/recall-quality-v1.json";
    private static final String FROZEN_SHA256 = "cf64afb57c785d463c427bd34249d99c92042a7f98a761464503721a09c52656";
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static byte[] corpusBytes() throws Exception {
        try (InputStream input = MemoryRecallQualityTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input != null) return input.readAllBytes();
        }
        return Files.readAllBytes(Path.of(System.getProperty("pocket.gateway.source", "."),
                "app/src/test/resources", RESOURCE));
    }

    private static JSONObject corpus() throws Exception {
        return new JSONObject(new String(corpusBytes(), StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format("%02x", b & 255));
        return value.toString();
    }

    @Test public void corpusHasFrozenSplitAndNoAnswerLabelsInQueries() throws Exception {
        assertEquals(FROZEN_SHA256, sha256(corpusBytes()));
        JSONObject corpus = corpus();
        assertEquals(1, corpus.getInt("schemaVersion"));
        JSONArray scenarios = corpus.getJSONArray("scenarios");
        assertEquals(24, scenarios.length());
        Set<String> ids = new HashSet<>();
        int development = 0, holdout = 0, zeroOverlap = 0;
        for (int i = 0; i < scenarios.length(); i++) {
            JSONObject scenario = scenarios.getJSONObject(i);
            assertTrue(ids.add(scenario.getString("id")));
            if ("development".equals(scenario.getString("split"))) development++;
            else { assertEquals("holdout", scenario.getString("split")); holdout++; }
            assertEquals("synthetic_query_paraphrase_not_model_output", scenario.getString("suggestionProvenance"));
            if (scenario.getBoolean("zeroOriginalLexicalOverlap")) zeroOverlap++;
            // The test inputs never hand a saved UUID or an expected answer key to the planner.
            Set<String> keys = strings(scenario.getJSONArray("expectedRelevant"));
            JSONArray suggestions = scenario.getJSONArray("suggestions");
            for (int j = 0; j < suggestions.length(); j++) {
                assertFalse(keys.contains(suggestions.getString(j)));
                assertFalse(suggestions.getString(j).matches(".*[a-f0-9]{8}-[a-f0-9-]{27}.*"));
            }
        }
        assertEquals(16, development);
        assertEquals(8, holdout);
        assertEquals(4, zeroOverlap);
    }

    @Test public void developmentSetComparesOriginalAndEnrichedAtSameBudget() throws Exception {
        evaluate("development", 16);
    }

    @Test public void heldOutSetComparesOriginalAndEnrichedAtSameBudget() throws Exception {
        evaluate("holdout", 8);
    }

    private void evaluate(String split, int expectedCount) throws Exception {
        Metrics baseline = new Metrics(), existingQueryPlanBaseline = new Metrics(), enriched = new Metrics();
        JSONArray scenarios = corpus().getJSONArray("scenarios"), details = new JSONArray();
        List<String> failures = new ArrayList<>();
        int cases = 0, zeroOverlapGains = 0, beyondExistingQueryPlanGains = 0;
        int defaultSnapshots = 0, existingSnapshots = 0, enrichedSnapshots = 0, existingPlanAttempts = 0;
        for (int i = 0; i < scenarios.length(); i++) {
            JSONObject scenario = scenarios.getJSONObject(i);
            if (!split.equals(scenario.getString("split"))) continue;
            cases++;
            Fixture fixture = new Fixture(temp.newFile(scenario.getString("id") + ".json"), scenario);
            JSONObject before = fixture.book.contextSnapshotQueriesScoped(scenario.getJSONObject("scope"),
                    MemoryQueryPlan.lexical(scenario.getString("query"), "not_requested"),
                    scenario.getInt("limit"), scenario.getInt("maxChars"), null);
            defaultSnapshots++;
            checkBudgetAndScope(scenario, before, fixture);
            if ("reject".equals(scenario.getString("expectation"))) {
                boolean oldPlanRejected = false;
                existingPlanAttempts++;
                try {
                    MemoryQueryPlan.agentSuggested(scenario.getString("query"), scenario.getJSONArray("suggestions"));
                } catch (IllegalArgumentException expected) {
                    existingQueryPlanBaseline.rejectedRequests++; oldPlanRejected = true;
                }
                try {
                    plan(scenario);
                    failures.add(scenario.getString("id") + ": invalid request budget was accepted");
                } catch (IllegalArgumentException expected) {
                    enriched.rejectedRequests++;
                }
                baseline.requestBoundaryCases++;
                existingQueryPlanBaseline.requestBoundaryCases++;
                enriched.requestBoundaryCases++;
                details.put(new JSONObject().put("id", scenario.getString("id"))
                        .put("requestBudgetRejected", enriched.rejectedRequests > 0)
                        .put("existingQueryPlanBaseline", new JSONObject().put("requestBoundaryCase", true)
                                .put("requestBudgetRejected", oldPlanRejected).put("retrievalExecuted", false)));
                fixture.book.closeRetrieval();
                continue;
            }
            existingPlanAttempts++;
            JSONObject existing = fixture.book.contextSnapshotQueriesScoped(scenario.getJSONObject("scope"),
                    MemoryQueryPlan.agentSuggested(scenario.getString("query"), scenario.getJSONArray("suggestions")),
                    scenario.getInt("limit"), scenario.getInt("maxChars"), null);
            existingSnapshots++;
            checkBudgetAndScope(scenario, existing, fixture);
            if (scenario.getBoolean("zeroOriginalLexicalOverlap")) {
                for (String key : strings(scenario.getJSONArray("expectedRelevant"))) {
                    String raw = fixture.specs.get(key).getJSONObject("source").getString("originalText");
                    assertTrue("zero-overlap fixture accidentally shares original lexical terms: " + scenario.getString("id"),
                            LexicalMemoryRetrievalBackend.score(new MemoryRetrievalBackend.Document(
                                    "owner", "private", key, 1, raw, 0, 1),
                                    LexicalMemoryRetrievalBackend.normalize(scenario.getString("query"))) < 0);
                }
                assertEquals("zero-overlap baseline is not independent", 0,
                        intersection(fixture.keys(before.getJSONArray("records")), strings(scenario.getJSONArray("expectedRelevant"))));
            }
            byte[] prior = Files.readAllBytes(fixture.file.toPath());
            JSONObject after = fixture.book.contextSnapshotQueriesScoped(scenario.getJSONObject("scope"),
                    plan(scenario), scenario.getInt("limit"), scenario.getInt("maxChars"), null);
            JSONObject repeated = fixture.book.contextSnapshotQueriesScoped(scenario.getJSONObject("scope"),
                    plan(scenario), scenario.getInt("limit"), scenario.getInt("maxChars"), null);
            enrichedSnapshots += 2;
            assertEquals("repeat query changed selection", fixture.keys(after.getJSONArray("records")),
                    fixture.keys(repeated.getJSONArray("records")));
            assertArrayEquals("retrieval mutated authoritative memory", prior, Files.readAllBytes(fixture.file.toPath()));
            checkBudgetAndScope(scenario, after, fixture);
            assertEquals(0, after.getJSONObject("queryPlan").getInt("additionalModelRequests"));
            assertEquals(0, after.getJSONObject("queryPlan").getInt("additionalNetworkRequests"));
            assertFalse(after.getBoolean("executionAuthorized"));
            assertFalse(after.getJSONObject("queryPlan").getBoolean("modelUseVerified"));
            List<String> beforeKeys = fixture.keys(before.getJSONArray("records"));
            List<String> existingKeys = fixture.keys(existing.getJSONArray("records"));
            List<String> afterKeys = fixture.keys(after.getJSONArray("records"));
            JSONObject beforeMetric = baseline.add(scenario, beforeKeys);
            JSONObject existingMetric = existingQueryPlanBaseline.add(scenario, existingKeys);
            JSONObject afterMetric = enriched.add(scenario, afterKeys);
            if (afterMetric.getInt("relevantRetrieved") > existingMetric.getInt("relevantRetrieved")) beyondExistingQueryPlanGains++;
            if (scenario.getBoolean("zeroOriginalLexicalOverlap") && afterMetric.getInt("relevantRetrieved") > 0) zeroOverlapGains++;
            Set<String> relevant = strings(scenario.getJSONArray("expectedRelevant"));
            if (!afterKeys.containsAll(relevant)) failures.add(scenario.getString("id") + ": missed relevant records");
            Set<String> forbidden = strings(scenario.getJSONArray("forbiddenRecords"));
            for (String key : afterKeys) if (forbidden.contains(key)) failures.add(scenario.getString("id") + ": forbidden record returned");
            JSONArray forbiddenText = scenario.getJSONArray("forbiddenText");
            for (int j = 0; j < forbiddenText.length(); j++)
                if (after.getString("context").contains(forbiddenText.getString(j)))
                    failures.add(scenario.getString("id") + ": superseded text returned");
            if ("no_answer".equals(scenario.getString("expectation"))) {
                Set<String> allowed = strings(scenario.getJSONArray("allowedSupport"));
                if (!allowed.containsAll(afterKeys)) failures.add(scenario.getString("id") + ": unsupported answer candidate returned");
            }
            details.put(new JSONObject().put("id", scenario.getString("id"))
                    .put("baseline", beforeMetric).put("existingQueryPlanBaseline", existingMetric).put("enriched", afterMetric));
            fixture.book.closeRetrieval();
        }
        assertEquals(expectedCount, cases);
        JSONObject report = new JSONObject().put("datasetId", "memory-recall-quality-v1")
                .put("datasetSha256", FROZEN_SHA256).put("split", split).put("fixedScenarioCount", expectedCount)
                .put("baseline", baseline.json()).put("existingQueryPlanBaseline", existingQueryPlanBaseline.json()).put("enriched", enriched.json())
                .put("baselineDefinition", "legacy_default_no_query_suggestions")
                .put("existingQueryPlanBaselineDefinition", "preexisting_two_argument_plan_same_suggestions_no_new_constraints")
                .put("existingQueryPlanBaselineHasNewConstraintSupport", false)
                .put("enrichedDefinition", "three_argument_plan_same_suggestions_with_declared_constraints")
                .put("querySnapshotExecutions", new JSONObject().put("legacyDefault", defaultSnapshots)
                        .put("existingQueryPlanBaseline", existingSnapshots).put("enrichedIncludingRepeatCheck", enrichedSnapshots))
                .put("existingQueryPlanAttemptsIncludingRequestBoundary", existingPlanAttempts)
                .put("beyondExistingQueryPlanRelevantGainCases", beyondExistingQueryPlanGains)
                .put("zeroOriginalOverlapGainsComparedTo", "legacy_default_no_query_suggestions")
                .put("zeroOriginalOverlapGains", zeroOverlapGains).put("details", details)
                .put("liveModelQualityMeasured", false).put("syntheticRewriteFixture", true)
                .put("extraModelRequests", 0).put("testMethodsAreNotRetrievalQuality", true);
        System.out.println("MEMORY_RECALL_QUALITY " + report);
        assertTrue("retrieval acceptance failures " + failures, failures.isEmpty());
        assertTrue("answerable hit count regressed", enriched.answerableHits >= baseline.answerableHits);
        assertTrue("nonlexical fixtures gained no recall", zeroOverlapGains > 0);
    }

    private static MemoryQueryPlan plan(JSONObject scenario) throws Exception {
        return MemoryQueryPlan.agentSuggested(scenario.getString("query"), scenario.getJSONArray("suggestions"),
                scenario.getJSONObject("constraints"));
    }

    private static void checkBudgetAndScope(JSONObject scenario, JSONObject result, Fixture fixture) throws Exception {
        JSONArray rows = result.getJSONArray("records");
        assertTrue(rows.length() <= scenario.getInt("limit"));
        assertTrue(result.getString("context").length() <= scenario.getInt("maxChars"));
        assertEquals(result.getString("context").length(), result.getInt("charsUsed"));
        JSONObject scope = scenario.getJSONObject("scope");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            assertTrue(seen.add(row.getString("id")));
            assertEquals(scope.getString("ownerId"), row.getString("ownerId"));
            assertEquals(scope.getString("spaceId"), row.getString("spaceId"));
            JSONObject current = fixture.book.get(scope, row.getString("id"));
            assertEquals(current.getLong("revision"), row.getLong("revision"));
            assertEquals(current.getString("text"), row.getString("text"));
        }
    }

    private static Set<String> strings(JSONArray values) throws Exception {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        return result;
    }

    private static int intersection(List<String> actual, Set<String> expected) {
        int matches = 0;
        for (String key : actual) if (expected.contains(key)) matches++;
        return matches;
    }

    private static final class Metrics {
        int answerableTasks, answerableHits, relevantExpected, relevantRetrieved;
        int noAnswerTasks, noAnswerCorrect, forbiddenReturns, rejectedRequests, requestBoundaryCases;
        double reciprocalRank;
        JSONObject add(JSONObject scenario, List<String> keys) throws Exception {
            Set<String> expected = strings(scenario.getJSONArray("expectedRelevant"));
            int found = intersection(keys, expected), forbidden = intersection(keys, strings(scenario.getJSONArray("forbiddenRecords")));
            double rr = 0;
            if (!expected.isEmpty()) {
                answerableTasks++; if (found > 0) answerableHits++;
                relevantExpected += expected.size(); relevantRetrieved += found;
                for (int rank = 0; rank < keys.size(); rank++) if (expected.contains(keys.get(rank))) { rr = 1d / (rank + 1); break; }
                reciprocalRank += rr;
            }
            boolean noAnswer = "no_answer".equals(scenario.getString("expectation"));
            boolean safeNoAnswer = strings(scenario.getJSONArray("allowedSupport")).containsAll(keys);
            if (noAnswer) { noAnswerTasks++; if (safeNoAnswer) noAnswerCorrect++; }
            forbiddenReturns += forbidden;
            return new JSONObject().put("retrieved", keys.size()).put("relevantRetrieved", found)
                    .put("relevantExpected", expected.size()).put("reciprocalRank", rr)
                    .put("forbiddenReturned", forbidden).put("noAnswerRequired", noAnswer)
                    .put("onlyAllowedSupport", safeNoAnswer);
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("answerableTasks", answerableTasks).put("answerableHits", answerableHits)
                    .put("relevantExpected", relevantExpected).put("relevantRetrieved", relevantRetrieved)
                    .put("recallAtLimit", relevantExpected == 0 ? 0 : (double) relevantRetrieved / relevantExpected)
                    .put("meanReciprocalRank", answerableTasks == 0 ? 0 : reciprocalRank / answerableTasks)
                    .put("noAnswerTasks", noAnswerTasks).put("noAnswerCorrect", noAnswerCorrect)
                    .put("forbiddenReturned", forbiddenReturns).put("requestBoundaryCases", requestBoundaryCases)
                    .put("rejectedRequests", rejectedRequests);
        }
    }

    private static final class Fixture {
        final File file;
        final Map<String, JSONObject> specs = new LinkedHashMap<>();
        final Map<String, JSONObject> saved = new LinkedHashMap<>();
        final Map<String, SourceRef.State> sources = new HashMap<>();
        MemoryBook book;
        Fixture(File file, JSONObject scenario) throws Exception {
            this.file = file;
            // TemporaryFolder has created an empty file; a fresh Book expects an absent file.
            Files.delete(file.toPath());
            SourceRef.Resolver resolver = (scope, kind, id) ->
                    sources.get(scope.ownerId + "\0" + scope.spaceId + "\0" + kind + "\0" + id);
            book = new MemoryBook(file, resolver);
            JSONArray rows = scenario.getJSONArray("records");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject spec = rows.getJSONObject(i), source = spec.getJSONObject("source");
                specs.put(spec.getString("key"), spec);
                SourceRef ref = SourceRef.fromJson(source);
                sources.put(sourceKey(ref), new SourceRef.State(ref, false));
                assertEquals(source.getString("sha256"),
                        sha256(source.getString("originalText").getBytes(StandardCharsets.UTF_8)));
                JSONObject value = new JSONObject().put("text", spec.getString("text")).put("layer", spec.getString("layer"))
                        .put("sourceRefs", new JSONArray().put(ref.toJson()));
                if (spec.has("assertion")) {
                    JSONObject assertion = new JSONObject(spec.getJSONObject("assertion").toString());
                    assertion.put("evidenceSpans", new JSONArray().put(new JSONObject().put("sourceRef", ref.toJson())
                            .put("offset", 0).put("chars", source.getString("originalText").length())
                            .put("sha256", source.getString("sha256"))));
                    value.put("assertion", assertion);
                }
                saved.put(spec.getString("key"), book.importFromSources(scope(ref), value, spec.getLong("updatedAt")));
            }
            JSONArray mutations = scenario.getJSONArray("mutations");
            for (int i = 0; i < mutations.length(); i++) {
                JSONObject mutation = mutations.getJSONObject(i);
                String key = mutation.getString("key");
                SourceRef ref = SourceRef.fromJson(specs.get(key).getJSONObject("source"));
                JSONObject row = saved.get(key), scope = scope(ref);
                switch (mutation.getString("type")) {
                    case "source_revision":
                        SourceRef changed = new SourceRef(ref.kind, ref.sourceId, mutation.getLong("revision"), ref.ownerId, ref.spaceId);
                        sources.put(sourceKey(ref), new SourceRef.State(changed, false)); book.sourceUpdated(scope, changed); break;
                    case "source_delete":
                        sources.put(sourceKey(ref), new SourceRef.State(ref, true)); book.sourceDeleted(scope, ref); break;
                    case "forget":
                        book.forgetReviewed(scope, row.getString("id"), row.getLong("revision"), ignored -> {}); break;
                    case "correct":
                        saved.put(key, book.correctReviewed(scope, row.getString("id"), row.getLong("revision"),
                                mutation.getString("text"), ignored -> {}, mutation.getLong("at")).getJSONObject("record")); break;
                    default: throw new AssertionError("unknown mutation");
                }
            }
            book.closeRetrieval();
            book = new MemoryBook(file, resolver); // Every scenario exercises persisted cross-session lookup.
        }
        List<String> keys(JSONArray rows) throws Exception {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                String id = rows.getJSONObject(i).getString("id"), key = null;
                for (Map.Entry<String, JSONObject> entry : saved.entrySet())
                    if (id.equals(entry.getValue().getString("id"))) { key = entry.getKey(); break; }
                if (key == null) throw new AssertionError("unexpected record identity returned");
                keys.add(key);
            }
            return keys;
        }
        private static JSONObject scope(SourceRef ref) throws Exception {
            return new JSONObject().put("ownerId", ref.ownerId).put("spaceId", ref.spaceId);
        }
        private static String sourceKey(SourceRef ref) {
            return ref.ownerId + "\0" + ref.spaceId + "\0" + ref.kind + "\0" + ref.sourceId;
        }
    }
}

