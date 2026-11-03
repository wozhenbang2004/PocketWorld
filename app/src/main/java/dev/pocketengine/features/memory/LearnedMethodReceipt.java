package dev.pocketengine.features.memory;

import dev.pocketengine.features.skills.SkillExecutionRecords;
import org.json.JSONObject;

/** Body-free audit projection of an original Skill backend attempt. A loaded Skill, a model
 * claim or exitCode alone never proves that a learned method accomplished the task. */
public final class LearnedMethodReceipt {
    private LearnedMethodReceipt(){}
    public static JSONObject project(JSONObject original,String record,long revision,String owner,String space)throws Exception{
        JSONObject review=original.optJSONObject("review"),pack=review==null?null:review.optJSONObject("package"),origin=pack==null?null:pack.optJSONObject("origin");
        if(origin==null||!"accepted_procedure".equals(origin.optString("kind")))return null;
        JSONObject method=origin.getJSONObject("ref");long actual=SourceRef.integer(method,"revision",1);
        if(!record.equals(method.getString("recordId"))||actual!=revision||!owner.equals(method.getString("ownerId"))||!space.equals(method.getString("spaceId")))return null;
        var reference=SkillExecutionRecords.reference(original.getJSONObject("reference"));
        if(!owner.equals(reference.ownerId)||!space.equals(reference.spaceId))throw new SecurityException("方法应用回执不能跨原受众");
        JSONObject receipt=original.optJSONObject("receipt");boolean claimed=original.has("claimedAt")&&SourceRef.integer(original,"claimedAt",1)>0;
        String state="not_applied",reason=original.optString("approvalState").equals("cancelled")?"cancelled_before_dispatch":"not_dispatched",outcome="not_available";
        if(claimed){state="dispatch_claimed";reason="backend_result_not_confirmed";}
        if(receipt!=null){
            if(!reference.taskId.equals(receipt.optString("taskId"))||!reference.runId.equals(receipt.optString("runId"))||reference.epoch!=receipt.optInt("epoch",-1)
                    ||!reference.operationId.equals(receipt.optString("operationId"))||!reference.planDigest.equals(receipt.optString("planDigest")))throw new SecurityException("方法应用后端回执与原计划不符");
            String status=receipt.optString("status");if(!java.util.Set.of("accepted","running","completed","failed","cancelled","timed_out","rejected","unknown").contains(status))throw new SecurityException("方法应用回执状态未知");
            outcome=status;boolean realBackend=receipt.opt("backendReceiptId") instanceof String&&!receipt.getString("backendReceiptId").isBlank();
            if(claimed&&realBackend){
                if(java.util.Set.of("completed","failed","timed_out").contains(status)){state="applied_backend_attempt";reason="original_backend_terminal_receipt";}
                else if(status.equals("running")){state="backend_running";reason="original_backend_running_not_completed";}
                else if(status.equals("accepted")){state="backend_accepted";reason="accepted_is_not_application_success";}
                else if(status.equals("cancelled")){state="backend_cancelled";reason="cancellation_does_not_prove_method_applied";}
            }
            if("unknown".equals(status)){state=claimed?"application_unknown":"not_applied";reason="unknown_is_not_replay_permission";}
        }
        JSONObject result=new JSONObject().put("recordId",record).put("recordRevision",revision).put("ownerId",owner).put("spaceId",space)
                .put("taskId",reference.taskId).put("runId",reference.runId).put("epoch",reference.epoch).put("operationId",reference.operationId).put("planDigest",reference.planDigest)
                .put("applicationState",state).put("reason",reason).put("backendOutcome",outcome).put("applicationScope","derived_skill_backend_attempt")
                .put("methodBusinessSuccessVerified",false).put("executionAuthorized",false).put("replayAllowed",false);
        if(claimed)result.put("claimedAt",original.getLong("claimedAt"));
        if(receipt!=null)for(String key:java.util.List.of("backendReceiptId","exitCode","outputSha256","outputBytes","outputTruncated"))if(receipt.has(key))result.put(key,receipt.get(key));
        result.put("effectEvidence",MethodEffectEvidence.captureSkillAttempt(original,result));
        if(original.optJSONObject("qualityFeedback")!=null){
            JSONObject originalQuality=original.getJSONObject("qualityFeedback"),quality=new JSONObject();
            for(String key:java.util.List.of("taskId","runId","operationId","skillId","revision","digest","executionGeneration","originRef","outcome","at","evidenceKind","originalReceiptFingerprint","automaticallyVerified","executionAuthorized","methodEffectEvidence"))if(originalQuality.has(key))quality.put(key,originalQuality.get(key));
            result.put("qualityFeedback",quality);
        }
        return result;
    }
}
