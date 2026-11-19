package dev.pocketengine.features.teams;

import org.json.JSONObject;

/** Only a private foreground file read uses this. The native reviewer's advice is not an owner decision. */
public final class TeamOwnerBlockedReview {
    private TeamOwnerBlockedReview(){}
    public static JSONObject read(JSONObject team,JSONObject child,JSONObject version,String run,String hash)throws Exception{
        if(team.has("teamOutputReview")||!TeamDeliveryWorkers.original(child,run,team.getString("id"),"reviewer",hash)||
                !hash.equals(version.opt("planHash"))||!"completed".equals(child.opt("status"))||!"completed".equals(version.opt("status")))return null;
        JSONObject ind=team.optJSONObject("teamIndependentReview"),found=TeamParentReviewFeedback.finding(run,hash,ind);
        if(found==null||!"completed".equals(found.opt("status"))||!child.optString("id").equals(found.opt("reviewerTaskId"))||
                !child.optString("runId").equals(found.opt("reviewerRunId"))||!Boolean.TRUE.equals(version.opt("completeOriginalFilesRead"))||
                !Boolean.TRUE.equals(found.opt("completeOriginalFilesRead"))||!found.getString("findings").equals(version.opt("findings"))||
                !found.getString("verdict").equals(version.opt("verdict"))||!found.getString("verdict").matches("changes_requested|inconclusive"))return null;
        JSONObject childProof=child.optJSONObject("evidence")==null?null:child.getJSONObject("evidence").optJSONObject("teamIndependentReview");
        if(childProof==null||!Boolean.TRUE.equals(childProof.opt("safeReceiptAndModelPresentationVerified"))||!Boolean.TRUE.equals(childProof.opt("verdictSubmitted")))return null;
        JSONObject origin=new JSONObject().put("teamRunId",run).put("hostTaskId",team.getString("id")).put("role","reviewer").put("taskId",child.getString("id"))
                .put("runId",child.getString("runId")).put("epoch",child.getInt("coordinatorEpoch")).put("planHash",hash).put("canOpen",true);
        return new JSONObject().put("state","independent_finding").put("verdict",found.getString("verdict")).put("findings",found.getString("findings"))
                .put("executionAuthorized",false).put("businessVerified",false).put("originalReviewer",origin);
    }
}
