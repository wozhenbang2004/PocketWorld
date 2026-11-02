package dev.pocketengine.runtime.eval;

import dev.pocketengine.core.RunOutcome;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Evaluation partitions and evidence scoring; never changes production task completion. */
public final class TaskEvaluation {
    public enum Partition { TUNE,HOLDOUT }
    public enum Expected { VERIFIED_SUCCESS,HONEST_FAILURE,TAKEOVER }
    public record Case(String id,String family,Partition partition,String marker,Expected expected,Set<String> requires,List<String> requiredFacts) {
        public Case {if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}")||family==null||family.isEmpty()||marker==null||!marker.matches("PW-EVAL-[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("marked_evaluation_case_required");java.util.Objects.requireNonNull(partition);java.util.Objects.requireNonNull(expected);requires=Set.copyOf(requires);requiredFacts=List.copyOf(requiredFacts);}
    }
    public record CheckedEffects(boolean observed,boolean achieved,boolean claimedSuccess,List<String> retainedEvidence,String reason){public CheckedEffects{retainedEvidence=List.copyOf(retainedEvidence);}}
    public interface Probe { CheckedEffects check(JSONObject originalTask)throws Exception; }
    private TaskEvaluation() { }
    public static void validatePartitions(List<Case> cases){Map<String,Partition> families=new java.util.HashMap<>();Set<String> ids=new java.util.HashSet<>();for(Case c:cases){if(!ids.add(c.id))throw new IllegalArgumentException("duplicate_evaluation_case");Partition previous=families.putIfAbsent(c.family,c.partition);if(previous!=null&&previous!=c.partition)throw new IllegalArgumentException("paired_cases_must_share_partition");}}
    public static List<Case> select(List<Case> cases,Partition partition){validatePartitions(cases);return cases.stream().filter(c->c.partition==partition).collect(java.util.stream.Collectors.toList());}
    public static JSONObject score(Case c,RunOutcome outcome,CheckedEffects effects)throws Exception {
        if(outcome==null||effects==null)return report(c,"harness_error",false,"missing_observation",outcome);
        String status=outcome.status;
        if(status.matches("cancelled|stopped|paused"))return report(c,"cancelled",false,"owner_stopped",outcome);
        if(effects.reason.equals("environment_unavailable"))return report(c,"skipped",false,"environment_unavailable",outcome);
        if(effects.reason.equals("model_error"))return report(c,"model_error",false,"model_request_failed",outcome);
        if(outcome.evidence.optBoolean("requiresReconciliation"))return report(c,"unknown_effect",false,"original_effect_requires_reconciliation",outcome);
        if(status.equals("awaiting_user")){boolean takeover=effects.reason.startsWith("takeover_");return report(c,takeover?"takeover":"blocked",takeover&&c.expected==Expected.TAKEOVER,"requires_owner",outcome);}
        if(!effects.observed)return report(c,"unverified",false,"independent_effect_not_observed",outcome);
        boolean grounded=true;for(String fact:c.requiredFacts){if(fact.isEmpty()||!outcome.summary.contains(fact)||effects.retainedEvidence.stream().noneMatch(source->source.contains(fact)))grounded=false;}
        boolean success=effects.achieved&&grounded&&status.matches("completed|needs_confirmation");
        if(effects.claimedSuccess&&!success)return report(c,"false_success",false,grounded?"effect_probe_rejected":"answer_not_grounded_in_retained_evidence",outcome).put("grounded",grounded);
        if(success)return report(c,"verified_success",c.expected==Expected.VERIFIED_SUCCESS,"independent_effect_and_literal_source_checked",outcome).put("grounded",grounded);
        boolean honest=status.equals("failed")&&!effects.claimedSuccess&&!effects.achieved&&effects.reason.equals("honest_failure");
        return report(c,honest?"honest_failure":"failed",honest&&c.expected==Expected.HONEST_FAILURE,"effect_or_expected_outcome_not_met",outcome).put("grounded",grounded);
    }
    static JSONObject report(Case c,String classification,boolean passed,String reason,RunOutcome outcome)throws Exception {return new JSONObject().put("format","pocketworld.task-evaluation.v1").put("caseId",c.id).put("family",c.family).put("partition",c.partition.name().toLowerCase(java.util.Locale.ROOT)).put("classification",classification).put("passed",passed).put("reason",reason).put("productionStatus",outcome==null?JSONObject.NULL:outcome.status).put("holdoutTuningPolicy","promote_to_tune_if_used_for_calibration_not_automatically_enforced").put("groundingMeaning","literal_retained_evidence_check_not_semantic_truth_proof").put("taskCompletionChangedByScore",false);}
}
