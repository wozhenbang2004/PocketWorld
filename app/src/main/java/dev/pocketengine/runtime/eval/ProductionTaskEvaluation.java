package dev.pocketengine.runtime.eval;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import org.json.JSONObject;
import static dev.pocketengine.runtime.eval.TaskEvaluation.*;
/** Test/owner adapter, executing an already prepared original task through its production guards. */
public final class ProductionTaskEvaluation {
    private ProductionTaskEvaluation() { }
    public static JSONObject runPrepared(Case c,Set<String> available,TaskStore tasks,String taskId,ModelConfig config,RunControl original,AgentRunner runner,Probe probe)throws Exception {
        RunControl.HostRunIdentity identity=original.hostRunIdentity();if(identity==null||!taskId.equals(identity.taskId()))throw new SecurityException("evaluation_requires_original_prepared_owner_run");JSONObject task=tasks.get(taskId,false);if(task==null||!task.optString("goal").contains(c.marker()))throw new SecurityException("evaluation_target_marker_required");
        if(!available.containsAll(c.requires())){tasks.endAttempt(taskId,identity.runId(),"awaiting_user","评测前置环境不可用；没有派发模型或工具",new JSONObject().put("evaluationSkipped",true));return TaskEvaluation.report(c,"skipped",false,"environment_unavailable",null);}
        final RunOutcome outcome;
        try{outcome=runner.run(taskId,task.getString("goal"),config,original,event->tasks.event(taskId,identity.runId(),identity.epoch(),event),tasks.journal(taskId,identity.runId(),identity.epoch(),original));tasks.endAttempt(taskId,identity.runId(),outcome.status,outcome.summary,outcome.evidence);}
        catch(Exception harness){original.cancel();return TaskEvaluation.report(c,"harness_error",false,"harness_execution_or_persistence_unconfirmed",null);}
        CheckedEffects effects;try{effects=probe.check(tasks.get(taskId,false));}catch(Exception failure){return TaskEvaluation.report(c,"harness_error",false,"independent_probe_unavailable",outcome);}
        return score(c,outcome,effects).put("taskId",taskId).put("runId",identity.runId()).put("epoch",identity.epoch()).put("usage",tasks.inferenceUsage(taskId));
    }
}
