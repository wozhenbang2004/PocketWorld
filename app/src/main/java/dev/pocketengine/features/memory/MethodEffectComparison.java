package dev.pocketengine.features.memory;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded observational comparison over already admitted original receipts, with no writes. */
public final class MethodEffectComparison {
    private MethodEffectComparison() {}

    public static JSONObject summarize(JSONArray applications, boolean completeHistory) throws Exception {
        if (applications == null || applications.length() > 50) throw new IllegalArgumentException("方法对照每次最多50份原回执");
        Map<String, JSONObject> unique = new LinkedHashMap<>(); Set<String> conflicts = new HashSet<>(); int duplicates = 0;
        for (int i = 0; i < applications.length(); i++) {
            JSONObject row = applications.getJSONObject(i);
            String operation = row.getString("taskId") + ":" + row.getString("runId") + ":" + row.getString("operationId");
            if (conflicts.contains(operation)) { duplicates++; continue; }
            JSONObject old = unique.get(operation);
            if (old != null) {
                duplicates++;
                if (!MethodEffectEvidence.digest(old).equals(MethodEffectEvidence.digest(row))) { unique.remove(operation); conflicts.add(operation); }
            } else unique.put(operation, row);
        }
        Map<String, JSONObject> groups = new LinkedHashMap<>(), samples = new LinkedHashMap<>();
        Set<String> conflictingRuns = new HashSet<>(); int incomplete = 0, unapplied = 0, duplicateRuns = 0, invalid = 0, eligible = 0;
        for (JSONObject row : unique.values()) {
            String state = row.optString("applicationState");
            if (!state.equals("model_apply_with_native_steps") && !state.equals("applied_backend_attempt")) { unapplied++; continue; }
            JSONObject evidence = row.optJSONObject("effectEvidence");
            if (evidence == null) { incomplete++; continue; }
            try { MethodEffectEvidence.requireBinding(row); }
            catch (org.json.JSONException | SecurityException | IllegalArgumentException changed) { invalid++; continue; }
            if (!evidence.optBoolean("inputComparable") || !evidence.optBoolean("environmentComparable")) { incomplete++; continue; }
            String run = row.getString("taskId") + ":" + row.getString("runId") + ":" + methodKey(evidence);
            if (conflictingRuns.contains(run)) { duplicateRuns++; continue; }
            JSONObject previous = samples.get(run);
            if (previous == null) samples.put(run, row);
            else {
                duplicateRuns++;
                JSONObject previousEvidence = previous.getJSONObject("effectEvidence");
                String before = ownerOutcome(previous, previousEvidence), after = ownerOutcome(row, evidence);
                if (!conditions(previousEvidence).equals(conditions(evidence))
                        || !before.equals("unreviewed") && !after.equals("unreviewed") && !before.equals(after)) {
                    samples.remove(run); conflictingRuns.add(run);
                } else if (before.equals("unreviewed") && !after.equals("unreviewed")) samples.put(run, row);
            }
        }
        for (JSONObject row : samples.values()) {
            JSONObject evidence = row.getJSONObject("effectEvidence"); String method = methodKey(evidence), conditions = conditions(evidence);
            JSONObject group = groups.get(conditions);
            if (group == null) {
                group = new JSONObject().put("conditionsFingerprint", conditions).put("inputFingerprint", evidence.getString("inputFingerprint"))
                        .put("inputCoverage", evidence.getString("inputCoverage")).put("environmentFingerprint", evidence.getString("environmentFingerprint"))
                        .put("environmentCoverage", evidence.getString("environmentCoverage")).put("methods", new JSONArray());
                groups.put(conditions, group);
            }
            JSONArray methods = group.getJSONArray("methods"); JSONObject comparison = null;
            for (int i = 0; i < methods.length(); i++) if (method.equals(methods.getJSONObject(i).getString("methodKey"))) comparison = methods.getJSONObject(i);
            if (comparison == null) {
                comparison = new JSONObject().put("methodKey", method).put("recordId", evidence.getString("recordId"))
                        .put("recordRevision", evidence.getLong("recordRevision")).put("recordFingerprint", evidence.optString("recordFingerprint"))
                        .put("implementationFingerprint", evidence.optString("implementationFingerprint"))
                        .put("applications", new JSONArray()).put("attempts", 0).put("ownerVerifiedResults", 0)
                        .put("ownerAttributedMethodFailures", 0).put("ownerAttributedEnvironmentFailures", 0)
                        .put("cancelled", 0).put("modelErrors", 0).put("unreviewed", 0).put("invalidFeedback", 0);
                methods.put(comparison);
            }
            eligible++; increment(comparison, "attempts");
            comparison.getJSONArray("applications").put(new JSONObject().put("taskId", row.getString("taskId"))
                    .put("runId", row.getString("runId")).put("operationId", row.getString("operationId"))
                    .put("evidenceFingerprint", evidence.getString("evidenceFingerprint")));
            String outcome = ownerOutcome(row, evidence);
            if (outcome.equals("verified")) increment(comparison, "ownerVerifiedResults");
            else if (outcome.equals("skill_failed") || outcome.equals("verifier_rejected")) increment(comparison, "ownerAttributedMethodFailures");
            else if (outcome.equals("environment")) increment(comparison, "ownerAttributedEnvironmentFailures");
            else if (outcome.equals("cancelled")) increment(comparison, "cancelled");
            else if (outcome.equals("model_error")) increment(comparison, "modelErrors");
            else if (outcome.equals("invalid")) increment(comparison, "invalidFeedback");
            else increment(comparison, "unreviewed");
        }
        JSONArray result = new JSONArray();
        for (JSONObject group : groups.values()) result.put(group.put("evidenceRelation", "matched_observed_conditions")
                .put("causalEffectVerified", false).put("allExternalConditionsControlled", false));
        return new JSONObject().put("format", "pocketworld.method-effect-comparison.v1")
                .put("coverage", completeHistory ? "complete_available_original_history" : "bounded_page_only")
                .put("completeHistory", completeHistory).put("scannedApplications", applications.length()).put("eligibleApplications", eligible)
                .put("excludedIncompleteEvidence", incomplete).put("excludedUnapplied", unapplied).put("excludedInvalidEvidence", invalid)
                .put("duplicateOperations", duplicates).put("duplicateRuns", duplicateRuns).put("conflictingOperations", conflicts.size()).put("conflictingRuns", conflictingRuns.size())
                .put("groups", result).put("successRateEstimated", false).put("causalEffectVerified", false)
                .put("automaticPromotionAllowed", false).put("confidenceDelta", 0).put("executionAuthorized", false).put("replayAllowed", false);
    }

    private static String methodKey(JSONObject evidence) throws Exception {
        return evidence.getString("recordId") + ":" + evidence.getLong("recordRevision") + ":" + evidence.optString("recordFingerprint") + ":" + evidence.optString("implementationFingerprint");
    }
    private static String conditions(JSONObject evidence) throws Exception {
        return MethodEffectEvidence.digest(new JSONObject().put("inputFingerprint", evidence.getString("inputFingerprint"))
                .put("inputCoverage", evidence.getString("inputCoverage")).put("environmentFingerprint", evidence.getString("environmentFingerprint"))
                .put("environmentCoverage", evidence.getString("environmentCoverage")));
    }

    private static String ownerOutcome(JSONObject row, JSONObject evidence) throws Exception {
        JSONObject feedback = row.optJSONObject("appQualityFeedback");
        if (feedback == null) feedback = row.optJSONObject("qualityFeedback");
        if (feedback == null) return "unreviewed";
        if (!Boolean.FALSE.equals(feedback.opt("automaticallyVerified")) || !Boolean.FALSE.equals(feedback.opt("executionAuthorized"))
                || !Set.of("owner_reviewed_original_method_steps", "owner_reviewed_original_execution").contains(feedback.optString("evidenceKind"))) return "invalid";
        for (String key : new String[]{"taskId", "runId", "operationId"}) if (!row.getString(key).equals(feedback.optString(key))) return "invalid";
        JSONObject origin = feedback.optJSONObject("originRef");
        if (origin == null || !evidence.getString("recordId").equals(origin.optString("recordId"))
                || evidence.getLong("recordRevision") != origin.optLong("revision") || !evidence.getString("ownerId").equals(origin.optString("ownerId"))
                || !evidence.getString("spaceId").equals(origin.optString("spaceId"))) return "invalid";
        if ("original_skill_operation".equals(evidence.optString("source"))) {
            JSONObject implementation = evidence.getJSONObject("implementationRef");
            if (!"owner_reviewed_original_execution".equals(feedback.optString("evidenceKind"))
                    || !implementation.getString("id").equals(feedback.optString("skillId"))
                    || implementation.getLong("revision") != feedback.optLong("revision")
                    || !implementation.getString("digest").equals(feedback.optString("digest"))
                    || implementation.optLong("executionGeneration") != feedback.optLong("executionGeneration")) return "invalid";
        } else if (!"owner_reviewed_original_method_steps".equals(feedback.optString("evidenceKind"))) return "invalid";
        JSONObject link = feedback.optJSONObject("methodEffectEvidence");
        if (link != null && !evidence.getString("evidenceFingerprint").equals(link.optString("evidenceFingerprint"))) return "invalid";
        String outcome = feedback.optString("outcome");
        if (!Set.of("verified", "skill_failed", "verifier_rejected", "environment", "cancelled", "model_error").contains(outcome)) return "invalid";
        // A settled process is only an attempt. The owner may verify its result, not its causality.
        if (outcome.equals("verified") && row.optString("backendOutcome").matches("unknown|cancelled|failed|timed_out|rejected")) return "invalid";
        return outcome;
    }

    private static void increment(JSONObject value, String key) throws Exception { value.put(key, value.getInt(key) + 1); }
}
