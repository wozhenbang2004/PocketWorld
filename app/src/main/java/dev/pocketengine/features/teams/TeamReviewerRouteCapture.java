package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import org.json.JSONArray;
import org.json.JSONObject;

/** Constructed on the independent Runner path. Uses that live, native model only; records no keys or URLs. */
public final class TeamReviewerRouteCapture {
    private final TaskStore issuer;
    private final RunControl original;
    private final String team,task,run,plan,selectedRoute,runnerRoute;
    private final int epoch,ceiling;
    private final ModelConfig config;
    private boolean supplied;
    private static JSONObject dispatch(ModelRoutePlan plan)throws Exception{
        JSONArray permitted=new JSONArray();for(var candidate:plan.candidates)permitted.put(plan.dispatches(candidate));
        return new JSONObject().put("mask",permitted).put("maxAttempts",plan.dispatchMaxAttempts());
    }
    static String fingerprint(ModelConfig config)throws Exception{
        // A native step can select only one original candidate; the persisted public group plan alone
        // does not describe that restriction. Include it for the main and every purpose route.
        JSONObject purposes=new JSONObject(),constraints=new JSONObject().put("configuredRoute",TeamFileRemainder.route(config)).put("purposes",purposes);
        if(config.routePlan!=null)constraints.put("main",dispatch(config.routePlan));
        for(var purpose:config.purposePlans.entrySet())purposes.put(purpose.getKey(),dispatch(purpose.getValue()));
        return TeamWorkFiles.hash(TeamWorkFiles.canonical(constraints));
    }
    private TeamReviewerRouteCapture(TaskStore host,RunControl control,JSONObject binding,ModelConfig selected)throws Exception{
        issuer=host;original=control;team=binding.getString("teamRunId");task=binding.getString("taskId");run=binding.getString("runId");
        epoch=binding.getInt("coordinatorEpoch");ceiling=binding.getInt("stepCeiling");plan=binding.getString("planHash");
        config=selected.withStepCeiling(ceiling);String before=null,after=null;
        // Older/unfingerprintable configurations still run the existing review. They never acquire historical origin later.
        try{before=fingerprint(selected);after=fingerprint(config);}catch(Exception unavailable){before=null;after=null;}
        selectedRoute=before;runnerRoute=after;
    }
    static TeamReviewerRouteCapture capture(TaskStore host,RunControl original,JSONObject binding,ModelConfig selected)throws Exception{
        return new TeamReviewerRouteCapture(host,original,binding,selected);
    }
    ModelConfig forRunner(){supplied=true;return config;}
    public JSONObject originalRecord(TaskStore host,RunControl owner,JSONObject binding,JSONObject proof)throws Exception{
        if(!supplied||issuer!=host||original!=owner||!original.matchesHostRun(task,run,epoch)||original.isCancelled()||selectedRoute==null||runnerRoute==null||
                !run.equals(binding.opt("runId"))||!team.equals(binding.opt("teamRunId"))||!task.equals(binding.opt("taskId"))||!plan.equals(binding.opt("planHash"))||
                binding.optInt("coordinatorEpoch",-1)!=epoch||binding.optInt("stepCeiling",-1)!=ceiling||!Boolean.TRUE.equals(proof.opt("safeReceiptAndModelPresentationVerified"))||
                !Boolean.TRUE.equals(proof.opt("completeOriginalFilesRead"))||!Boolean.TRUE.equals(proof.opt("verdictSubmitted")))return null;
        return TeamReviewerRouteRecord.from(binding,selectedRoute,runnerRoute);
    }
}
