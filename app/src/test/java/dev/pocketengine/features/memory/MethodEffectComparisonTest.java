package dev.pocketengine.features.memory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MethodEffectComparisonTest {
    private static JSONObject reviewed(String outcome) throws Exception {
        JSONObject method = MethodEffectEvidenceTest.captured();
        method.put("appQualityFeedback", AppQualityReceipt.fromOriginalMethod(method, outcome, "Owner inspected the original result", 2000).snapshot()); return method;
    }
    @Test public void sameObservedConditionsGroupIndependentRunsWithSeparateFailureAttribution() throws Exception {
        JSONObject first = reviewed("verified"), second = reviewed("environment"), third = reviewed("skill_failed");
        JSONObject summary = MethodEffectComparison.summarize(new JSONArray().put(first).put(second).put(third), false);
        assertEquals(3, summary.getInt("eligibleApplications")); assertEquals(1, summary.getJSONArray("groups").length());
        JSONObject method = summary.getJSONArray("groups").getJSONObject(0).getJSONArray("methods").getJSONObject(0);
        assertEquals(3, method.getInt("attempts")); assertEquals(1, method.getInt("ownerVerifiedResults"));
        assertEquals(1, method.getInt("ownerAttributedEnvironmentFailures")); assertEquals(1, method.getInt("ownerAttributedMethodFailures"));
        assertEquals("bounded_page_only", summary.getString("coverage")); assertFalse(summary.getBoolean("causalEffectVerified"));
        assertFalse(summary.getBoolean("successRateEstimated")); assertFalse(summary.getBoolean("automaticPromotionAllowed"));
    }
    @Test public void changedInputAndEnvironmentStaySeparateAndNewMethodRevisionRemainsIdentifiable() throws Exception {
        JSONObject first = reviewed("verified"), changedInput = MethodEffectEvidenceTest.method(), changedEnvironment = MethodEffectEvidenceTest.method(), newRevision = MethodEffectEvidenceTest.method();
        changedInput.put("effectEvidence", MethodEffectEvidence.captureTaskDecision(changedInput, MethodEffectEvidenceTest.task(changedInput).put("goal", "different original input")));
        changedEnvironment.getJSONArray("stepRefs").getJSONObject(0).getJSONObject("workspaceSource").put("expectedVersion", 8);
        changedEnvironment.put("effectEvidence", MethodEffectEvidence.captureTaskDecision(changedEnvironment, MethodEffectEvidenceTest.task(changedEnvironment)));
        newRevision.put("revision", 2).put("recordRevision", 2).put("recordFingerprint", "d".repeat(64));
        newRevision.put("effectEvidence", MethodEffectEvidence.captureTaskDecision(newRevision, MethodEffectEvidenceTest.task(newRevision)));
        JSONObject summary = MethodEffectComparison.summarize(new JSONArray().put(first).put(changedInput).put(changedEnvironment).put(newRevision), true);
        assertEquals(3, summary.getJSONArray("groups").length());
        assertEquals(2, summary.getJSONArray("groups").getJSONObject(0).getJSONArray("methods").length());
        assertTrue(summary.getBoolean("completeHistory")); assertEquals(0, summary.getInt("confidenceDelta"));
    }
    @Test public void repeatedClicksSameRunAndConflictingCallbacksCannotIncreaseSampleCount() throws Exception {
        JSONObject first = reviewed("verified"), sameRun = MethodEffectEvidenceTest.method();
        sameRun.put("taskId", first.getString("taskId")).put("runId", first.getString("runId"));
        sameRun.put("effectEvidence", MethodEffectEvidence.captureTaskDecision(sameRun, MethodEffectEvidenceTest.task(sameRun)));
        JSONObject summary = MethodEffectComparison.summarize(new JSONArray().put(first).put(new JSONObject(first.toString())).put(sameRun), false);
        assertEquals(1, summary.getInt("eligibleApplications")); assertEquals(1, summary.getInt("duplicateOperations")); assertEquals(1, summary.getInt("duplicateRuns"));
        JSONObject conflict = new JSONObject(first.toString()).put("backendOutcome", "unknown");
        JSONObject disputed = MethodEffectComparison.summarize(new JSONArray().put(first).put(conflict), false);
        assertEquals(0, disputed.getInt("eligibleApplications")); assertEquals(1, disputed.getInt("conflictingOperations"));
    }
    @Test public void modelSelfReportedSuccessLegacyAndUnobservedEnvironmentDoNotBecomePositiveResults() throws Exception {
        JSONObject model = MethodEffectEvidenceTest.captured().put("modelClaimsSuccess", true).put("methodBusinessSuccessVerified", true);
        JSONObject legacy = MethodEffectEvidenceTest.method(), unknownEnvironment = MethodEffectEvidenceTest.method();
        unknownEnvironment.getJSONArray("stepRefs").getJSONObject(0).remove("workspaceSource");
        unknownEnvironment.put("effectEvidence", MethodEffectEvidence.captureTaskDecision(unknownEnvironment, MethodEffectEvidenceTest.task(unknownEnvironment)));
        JSONObject summary = MethodEffectComparison.summarize(new JSONArray().put(model).put(legacy).put(unknownEnvironment), false);
        assertEquals(1, summary.getInt("eligibleApplications")); assertEquals(2, summary.getInt("excludedIncompleteEvidence"));
        JSONObject method = summary.getJSONArray("groups").getJSONObject(0).getJSONArray("methods").getJSONObject(0);
        assertEquals(0, method.getInt("ownerVerifiedResults")); assertEquals(1, method.getInt("unreviewed"));
        assertFalse(summary.getBoolean("causalEffectVerified"));
    }
    @Test public void foreignReviewAndUnknownExecutionCannotBeCountedAsVerified() throws Exception {
        JSONObject foreign = reviewed("verified"); foreign.getJSONObject("appQualityFeedback").put("runId", "foreign-run");
        JSONObject unknown = reviewed("verified").put("applicationState", "application_unknown").put("backendOutcome", "unknown");
        JSONObject summary = MethodEffectComparison.summarize(new JSONArray().put(foreign).put(unknown), false);
        assertEquals(1, summary.getInt("excludedUnapplied"));
        JSONObject method = summary.getJSONArray("groups").getJSONObject(0).getJSONArray("methods").getJSONObject(0);
        assertEquals(0, method.getInt("ownerVerifiedResults")); assertEquals(1, method.getInt("invalidFeedback"));
    }
}
