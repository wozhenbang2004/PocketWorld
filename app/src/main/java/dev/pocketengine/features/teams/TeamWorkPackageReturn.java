package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

/** A body-free pointer to a native-signed, immutable team package on its original owner's work row.
 * Not a task result, a release of resources, or a grant. The reader rechecks the original operation. */
public final class TeamWorkPackageReturn {
    private TeamWorkPackageReturn(){}
    public static boolean possible(JSONObject team){
        JSONObject scope=team.optJSONObject("teamWriteScope");
        return scope!=null&&scope.optJSONObject("projectSelection")!=null&&team.optJSONObject("teamOutputReview")!=null&&!team.optBoolean("cancelRequested");
    }
    public static JSONObject mark(JSONObject team,JSONObject integrator)throws Exception {
        if(!possible(team)||integrator==null||!"completed".equals(integrator.opt("status"))||integrator.optBoolean("cancelRequested"))return null;
        String run=team.optString("runId"),host=team.optString("id");
        JSONObject decision=team.getJSONObject("teamOutputReview"),review=team.optJSONObject("teamIndependentReview"),
                delivery=integrator.optJSONObject("teamProjectDelivery"),identity=integrator.optJSONObject("teamAssignment");
        if(run.isEmpty()||!run.equals(team.opt("externalId"))||delivery==null||identity==null||review==null||review.optBoolean("stale")||
                !"completed".equals(review.opt("status"))||!"no_blocking_findings".equals(review.opt("verdict"))||
                !"pocketworld.team-project-delivery.v1".equals(delivery.opt("format"))||!Boolean.TRUE.equals(delivery.opt("complete"))||
                !Boolean.TRUE.equals(delivery.opt("fixedBaselineOnly"))||!Boolean.FALSE.equals(delivery.opt("parentActiveCheckoutOverwritten"))||
                !Boolean.FALSE.equals(delivery.opt("currentMainProjectVerified"))||!Boolean.FALSE.equals(delivery.opt("businessVerified"))||
                !run.equals(delivery.opt("teamRunId"))||!run.equals(review.opt("teamRunId")))return null;
        String hash=delivery.optString("planHash"),reviewId=delivery.optString("reviewId"),worker=integrator.optString("id");
        if(!TeamDeliveryWorkers.original(integrator,run,host,"integrator",hash)||reviewId.isEmpty()||!reviewId.equals(identity.opt("reviewId"))||
                !reviewId.equals(decision.opt("reviewId"))||!hash.equals(decision.opt("planHash"))||!hash.equals(review.opt("planHash")))return null;
        JSONObject pack=delivery.optJSONObject("packageArtifact");
        if(pack==null||!worker.equals(pack.opt("taskId"))||!"source_project".equals(pack.opt("mode"))||!Boolean.TRUE.equals(pack.opt("immutable"))||
                !Boolean.TRUE.equals(pack.opt("persisted"))||!Boolean.FALSE.equals(pack.opt("authorityAdded")))return null;
        String id=pack.optString("artifactId"),operation=pack.optString("operationId"),sha=pack.optString("archiveSha256"),
                source=pack.optString("sourceDigest"),manifest=pack.optString("manifestIdentity");
        long bytes=pack.optLong("archiveBytes",-1);int count=pack.optInt("fileCount",-1);
        if(!id.matches("[a-f0-9-]{36}")||!id.equals(pack.opt("id"))||!operation.matches("[A-Za-z0-9_-]{1,80}")||
                !sha.matches("[a-f0-9]{64}")||!sha.equals(pack.opt("sha256"))||!source.matches("[a-f0-9]{64}")||
                !source.equals(delivery.opt("sourceDigest"))||!manifest.matches("[a-f0-9]{64}")||bytes<=0||bytes>20L*1024*1024||
                bytes!=pack.optLong("sizeBytes",-1)||count<0||count>2000)return null;
        return new JSONObject().put("taskId",worker).put("operationId",operation).put("artifactId",id).put("archiveSha256",sha)
                .put("sourceDigest",source).put("manifestIdentity",manifest).put("archiveBytes",bytes).put("fileCount",count);
    }
    /** Only an administrative author-phase awaiting_review, never a real member question, proposal or problem. */
    public static boolean solelyAuthorReview(JSONObject team,JSONObject progress) throws Exception {
        for(String key:new String[]{"approvals","teamQuestions"}){
            JSONArray rows=team.optJSONArray(key);for(int i=0;rows!=null&&i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null||"pending".equals(row.opt("state")))return false;
            }
        }
        return "awaiting_review".equals(team.opt("executionStatus"))&&"needs_confirmation".equals(team.opt("status"))&&progress!=null&&
                Boolean.FALSE.equals(progress.opt("needsAttention"))&&progress.optInt("attentionCount",-1)==0&&
                progress.optInt("pendingDecisionCount",-1)==0&&progress.optInt("pendingQuestionCount",-1)==0&&progress.optInt("pendingParentRevisionCount",-1)==0;
    }
}
