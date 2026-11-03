package dev.pocketengine.features.memory;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MethodEffectEvidenceTest {
    static JSONObject method() throws Exception {
        return new JSONObject().put("format", "pocketworld.learned-method-decision.v1")
                .put("ownerId", "owner").put("spaceId", "private").put("taskId", UUID.randomUUID().toString())
                .put("runId", UUID.randomUUID().toString()).put("epoch", 2).put("operationId", UUID.randomUUID().toString())
                .put("recordId", "method-record").put("revision", 1).put("recordRevision", 1).put("recordFingerprint", "a".repeat(64))
                .put("decision", "apply").put("recordedAt", 1234L).put("applicationState", "model_apply_with_native_steps")
                .put("backendOutcome", "native_step_receipts_confirmed").put("stepRefs", new JSONArray().put(step()));
    }
    static JSONObject step() throws Exception {
        return new JSONObject().put("operationId", UUID.randomUUID().toString()).put("tool", "workspace_write")
                .put("operationState", "receipt").put("resultStatus", "").put("receiptFingerprint", "b".repeat(64))
                .put("argumentsFingerprint", "c".repeat(64)).put("workspaceSource", new JSONObject()
                        .put("projectId", "private-project").put("path", "report.txt").put("expectedVersion", 1));
    }
    static JSONObject task(JSONObject method) throws Exception {
        return new JSONObject().put("id", method.getString("taskId")).put("runId", method.getString("runId"))
                .put("coordinatorEpoch", method.getInt("epoch")).put("actorId", "owner").put("spaceId", "private")
                .put("goal", "PRIVATE_GOAL_只对本次固定输入执行核对").put("executionMode", "capabilities");
    }
    static JSONObject captured() throws Exception {
        JSONObject method = method(); method.put("effectEvidence", MethodEffectEvidence.captureTaskDecision(method, task(method))); return method;
    }
    @Test public void frozenEvidenceContainsNoGoalOrInputBodyAndNeverMintsSuccessOrPermission() throws Exception {
        JSONObject method = captured(), evidence = method.getJSONObject("effectEvidence");
        assertFalse(evidence.toString().contains("PRIVATE_GOAL")); assertFalse(evidence.toString().contains("report.txt"));
        assertTrue(evidence.getBoolean("inputComparable")); assertTrue(evidence.getBoolean("environmentComparable"));
        assertFalse(evidence.getBoolean("causalEffectVerified")); assertFalse(evidence.getBoolean("businessSuccessVerified"));
        assertFalse(evidence.getBoolean("executionAuthorized")); assertFalse(evidence.getBoolean("replayAllowed"));
        assertEquals(0, evidence.getInt("confidenceDelta")); MethodEffectEvidence.requireBinding(method);
    }
    @Test public void taskRunCancellationAndScopeAreNativeBindingBoundaries() throws Exception {
        JSONObject method = method(), wrongRun = task(method).put("runId", UUID.randomUUID().toString());
        assertThrows(SecurityException.class, () -> MethodEffectEvidence.captureTaskDecision(method, wrongRun));
        JSONObject cancelled = task(method).put("cancelRequested", true);
        assertThrows(SecurityException.class, () -> MethodEffectEvidence.captureTaskDecision(method, cancelled));
        JSONObject wrongAudience = task(method).put("spaceId", "group");
        assertThrows(SecurityException.class, () -> MethodEffectEvidence.captureTaskDecision(method, wrongAudience));
    }
    @Test public void missingEnvironmentAndMissingArgumentEvidenceRemainExplicitlyNonComparable() throws Exception {
        JSONObject method = method(); method.getJSONArray("stepRefs").getJSONObject(0).remove("workspaceSource");
        JSONObject evidence = MethodEffectEvidence.captureTaskDecision(method, task(method));
        assertFalse(evidence.getBoolean("environmentComparable")); assertEquals("unobserved", evidence.getString("environmentCoverage"));
        method.getJSONArray("stepRefs").getJSONObject(0).remove("argumentsFingerprint");
        assertFalse(MethodEffectEvidence.captureTaskDecision(method, task(method)).getBoolean("inputComparable"));
        method.getJSONArray("stepRefs").put(step());
        assertFalse(MethodEffectEvidence.captureTaskDecision(method, task(method)).getBoolean("environmentComparable"));
    }
    @Test public void originalNativePhoneObservationPinsVersionAndRejectsOldRunEvidence() throws Exception {
        JSONObject method = method(), step = method.getJSONArray("stepRefs").getJSONObject(0); step.remove("workspaceSource");
        step.put("tool", "phone_tap").put("observationId", "observation-1");
        JSONObject app = new JSONObject().put("source", "android_package_manager").put("packageName", "example.app").put("versionCode", 7).put("lastUpdateTime", 100L);
        JSONObject observation = new JSONObject().put("app", app).put("runId", method.getString("runId")).put("epoch", 2).put("observationId", "observation-1");
        JSONObject task = task(method).put("nativeAppObservations", new JSONArray().put(observation));
        JSONObject first = MethodEffectEvidence.captureTaskDecision(method, task); assertTrue(first.getBoolean("environmentComparable"));
        app.put("versionCode", 8); JSONObject updated = MethodEffectEvidence.captureTaskDecision(method, task);
        assertNotEquals(first.getString("environmentFingerprint"), updated.getString("environmentFingerprint"));
        observation.put("runId", "old-run"); assertFalse(MethodEffectEvidence.captureTaskDecision(method, task).getBoolean("environmentComparable"));
    }
    @Test public void sourceRevisionReceiptMutationAndDuplicateStepsCannotReuseOldEvaluation() throws Exception {
        JSONObject revised = captured(); revised.put("recordRevision", 2);
        assertThrows(SecurityException.class, () -> MethodEffectEvidence.requireBinding(revised));
        JSONObject changed = captured(); changed.getJSONArray("stepRefs").getJSONObject(0).put("receiptFingerprint", "d".repeat(64));
        assertThrows(SecurityException.class, () -> MethodEffectEvidence.requireBinding(changed));
        JSONObject duplicate = method(); duplicate.getJSONArray("stepRefs").put(duplicate.getJSONArray("stepRefs").getJSONObject(0));
        assertThrows(SecurityException.class, () -> MethodEffectEvidence.captureTaskDecision(duplicate, task(duplicate)));
        JSONObject forged = captured(); forged.getJSONObject("effectEvidence").put("confidenceDelta", 1);
        assertThrows(SecurityException.class, () -> MethodEffectEvidence.requireBinding(forged));
    }
    @Test public void oldInputWithoutRevisionCanStillBeAuditedWithoutAssumingAnExactInput() throws Exception {
        JSONObject method = method(), task = task(method).put("ownerInputs", new JSONArray().put(new JSONObject()
                .put("materializedRunId", method.getString("runId")).put("queueState", "materialized").put("payloadSha256", "f".repeat(64))));
        JSONObject evidence = MethodEffectEvidence.captureTaskDecision(method, task);
        assertFalse(evidence.getBoolean("inputComparable")); assertEquals(1, evidence.getInt("inputCount"));
    }
    @Test public void ownerJudgementPinsEvidenceButIsNotCausalityOrModelConfidence() throws Exception {
        JSONObject method = captured(); method.put("modelClaimsSuccess", true);
        JSONObject quality = AppQualityReceipt.fromOriginalMethod(method, "verified", "主人核对了原成果版本", 2000).snapshot();
        assertEquals(method.getJSONObject("effectEvidence").getString("evidenceFingerprint"), quality.getJSONObject("methodEffectEvidence").getString("evidenceFingerprint"));
        assertTrue(quality.getBoolean("resultVerifiedByOwner")); assertFalse(quality.getBoolean("causalEffectVerified"));
        assertFalse(quality.getBoolean("automaticallyVerified")); assertFalse(quality.getBoolean("executionAuthorized")); assertEquals(0, quality.getInt("confidenceDelta"));
        method.remove("effectEvidence"); JSONObject legacy = AppQualityReceipt.fromOriginalMethod(method, "environment", "原环境不可比较", 2000).snapshot();
        assertEquals("legacy_original_steps_only", legacy.getJSONObject("methodEffectEvidence").getString("evaluationCoverage"));
    }
}
