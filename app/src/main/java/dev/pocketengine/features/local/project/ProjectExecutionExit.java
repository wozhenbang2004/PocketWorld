package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.runtime.NativeProcessSupervisor;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import org.json.JSONObject;

/** Scalar evidence from the original managed job, not text inferred from a
 * terminal screen or permission to retry. Does not duplicate its output. */
public final class ProjectExecutionExit {
    private ProjectExecutionExit(){}
    private static Integer number(JSONObject value,String key,int min,int max){
        Object n=value.opt(key);if(!(n instanceof Integer||n instanceof Long))return null;
        long v=((Number)n).longValue();return v>=min&&v<=max?(int)v:null;
    }
    public static JSONObject from(JSONObject exact,JSONObject binding,JSONObject job)throws Exception {
        if(!"project.runtime.run".equals(exact.opt("command"))||!"managed_process_v1".equals(exact.opt("runtimeId")))return null;
        RuntimeExecutionRef ref=RuntimeExecutionRef.fromHost(exact,binding);ref.requireReceipt(job);
        if(!Boolean.TRUE.equals(job.opt("receiptReceived"))||!ref.sourceDigest().equals(job.opt("sourceDigest")))return null;
        String status=job.optString("status"),contract=job.optString("nativeResultContract");
        if(!NativeProcessSupervisor.knownOrdinaryExitContract(contract)&&!NativeProcessSupervisor.knownSessionExitContract(contract))return null;
        String kind;Integer value;
        if(NativeProcessSupervisor.validatesNoChildReceipt(job)){kind="no_child";value=number(job,"launchErrno",1,Integer.MAX_VALUE);}
        else if(Boolean.TRUE.equals(job.opt("processLaunched"))&&Boolean.TRUE.equals(job.opt("processExited"))){
            Integer exit=number(job,"exitCode",-1,255),signal=number(job,"terminationSignal",0,127);
            if(exit==null||signal==null||!Integer.valueOf(0).equals(number(job,"launchErrno",0,0)))return null;
            if(signal==0&&exit>=0){kind="exit_code";value=exit;}
            else if(signal>0&&exit==-1){kind="signal";value=signal;}
            else return null;
        }else return null;
        if(value==null)return null;
        JSONObject out=new JSONObject().put("format","pocketworld.owner-managed-exit.v1").put("taskId",binding.getString("taskId"))
            .put("runId",binding.getString("runId")).put("epoch",binding.getInt("coordinatorEpoch")).put("executionId",ref.operationId())
            .put("projectId",ref.projectId()).put("checkoutId",ref.checkoutId()).put("planDigest",exact.getString("planDigest"))
            .put("sourceDigest",ref.sourceDigest()).put("stepId",exact.getString("stepId")).put("kind",kind).put("value",value).put("nativeStatus",status);
        for(String key:new String[]{"timedOut","cancelRequested","resourcesReleased","processGroupAbsent"})if(job.opt(key) instanceof Boolean)out.put(key,job.getBoolean(key));
        // A cancellation, unknown resource state, or launch/signal observation alone is not a failed build to hand back.
        boolean knownFailure="failed".equals(status)&&"exit_code".equals(kind)&&value>0
            &&"pre_exec_manifest_verified".equals(job.opt("sourceCheckStatus"))&&Boolean.FALSE.equals(job.opt("cancelRequested"))&&Boolean.FALSE.equals(job.opt("timedOut"))
            &&Boolean.TRUE.equals(job.opt("processGroupAbsent"))&&Boolean.TRUE.equals(job.opt("resourcesReleased"));
        out.put("canPrepareBuildFeedback","build".equals(exact.opt("stepId"))&&knownFailure);
        if(knownFailure&&OwnerDraftNodeDependencies.STEP.equals(exact.opt("stepId"))&&OwnerDraftNodeDependencies.exact(exact)){
            String sha=exact.getJSONObject("payload").getJSONObject(OwnerDraftNodeDependencies.FIELD).getString("lockSha256");
            if(sha.matches("[a-f0-9]{64}"))out.put("canPrepareDependencyFeedback",true).put("dependencyLockSha256",sha);
        }
        return out;
    }
}
