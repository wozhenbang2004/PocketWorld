package dev.pocketengine.runtime.eval;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import static dev.pocketengine.runtime.eval.TaskEvaluation.*;
/** Test/owner adapter, executing an already prepared original task through its production guards. */
public final class ProductionTaskEvaluation {
    private ProductionTaskEvaluation() { }
    public static JSONObject runPrepared(Case c,Set<String> available,TaskStore tasks,String taskId,ModelConfig config,RunControl original,AgentRunner runner,Probe probe)throws Exception {
        return runPrepared(List.of(c),c.partition(),c.id(),available,tasks,taskId,config,original,runner,probe);
    }
    /** Select and validate the whole suite before touching the already prepared native run. */
    public static JSONObject runPrepared(List<Case> cases,Partition partition,String caseId,Set<String> available,TaskStore tasks,String taskId,ModelConfig config,RunControl original,AgentRunner runner,Probe probe)throws Exception {
        Case c=TaskEvaluation.select(List.copyOf(cases),java.util.Objects.requireNonNull(partition,"evaluation_partition_required")).stream().filter(candidate->candidate.id().equals(caseId)).findFirst().orElseThrow(()->new IllegalArgumentException("evaluation_case_not_in_selected_partition"));
        RunControl.HostRunIdentity identity=original.hostRunIdentity();if(identity==null||!taskId.equals(identity.taskId()))throw new SecurityException("evaluation_requires_original_prepared_owner_run");JSONObject task=tasks.get(taskId,false);if(task==null||!task.optString("goal").contains(c.marker()))throw new SecurityException("evaluation_target_marker_required");
        if(!available.containsAll(c.requires())){tasks.endAttempt(taskId,identity.runId(),"awaiting_user","评测前置环境不可用；没有派发模型或工具",new JSONObject().put("evaluationSkipped",true));return TaskEvaluation.report(c,"skipped",false,"environment_unavailable",null);}
        final RunOutcome outcome;
        try{outcome=runner.run(taskId,task.getString("goal"),config,original,event->{try{tasks.event(taskId,identity.runId(),identity.epoch(),event);}catch(Exception persistence){throw new IllegalStateException("evaluation_original_event_persistence_unconfirmed",persistence);}},tasks.journal(taskId,identity.runId(),identity.epoch(),original));tasks.endAttempt(taskId,identity.runId(),outcome.status,outcome.summary,outcome.evidence);}
        catch(Exception harness){original.cancel();return TaskEvaluation.report(c,"harness_error",false,"harness_execution_or_persistence_unconfirmed",null);}
        CheckedEffects effects;RunOutcome settled;
        try{JSONObject saved=tasks.get(taskId,false);if(saved==null||!identity.runId().equals(saved.optString("runId"))||identity.epoch()!=saved.optInt("coordinatorEpoch"))throw new SecurityException("evaluation_original_run_changed");settled=new RunOutcome(saved.getString("status"),outcome.summary,outcome.evidence);effects=probe.check(saved);}
        catch(Exception failure){return TaskEvaluation.report(c,"harness_error",false,"independent_probe_or_original_run_unavailable",outcome);}
        return score(c,settled,effects).put("runnerStatus",outcome.status).put("taskId",taskId).put("runId",identity.runId()).put("epoch",identity.epoch()).put("usage",tasks.inferenceUsage(taskId));
    }
}
