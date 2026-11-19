package dev.pocketengine.features.teams;

import org.json.JSONObject;
import org.json.JSONArray;

/** A local content-free identity of a reviewer's configured route. Not a provider-usage, grant or start receipt. */
public final class TeamReviewerRouteRecord {
    public static final String KEY="teamReviewerRouteCapture";
    private static final String FORMAT="pocketworld.team-independent-review-route.v1";
    private TeamReviewerRouteRecord(){}
    public static boolean firstLocal(JSONObject scope,JSONObject work){
        JSONObject selected=scope==null?null:scope.optJSONObject("projectSelection");
        if(selected==null||work==null||!"pocketworld.team-project-selection.v1".equals(selected.opt("format"))||!"owner_baseline".equals(selected.opt("role"))||
                !Boolean.FALSE.equals(selected.opt("credentialsDelegated"))||selected.optBoolean("remoteProjectIntegratorOnly")||!"active".equals(work.opt("state"))||
                selected.has("previousDelivery")||selected.has("originalPackageArtifactId")||selected.optBoolean("knownFailedCopy")||
                selected.optBoolean("ownerSelectedKnownFailedBaseline")||work.optInt("revision",-1)!=1||work.has("lastRevisionRequestId"))return false;
        JSONArray authors=scope.optJSONArray("assignments"),items=work.optJSONArray("items");
        if(authors==null||authors.length()<1||authors.length()>8||items==null||items.length()!=authors.length())return false;
        for(int i=0;i<authors.length();i++){JSONObject author=authors.optJSONObject(i),item=items.optJSONObject(i);
            if(author==null||author.has("remoteSelection")||author.optJSONObject("projectSelection")==null||item==null||item.has("remoteSelection")||
                    author.optString("workItemId").isEmpty()||!author.optString("workItemId").equals(item.opt("workItemId"))||!author.optString("memberId").equals(item.opt("memberId"))||
                    item.optInt("itemRevision",-1)!=1||item.optInt("ownerGeneration",-1)!=0||item.has("revisionRequestId")||
                    item.optJSONArray("assignmentHistory")==null||item.optJSONArray("assignmentHistory").length()!=0)return false;
        }
        return true;
    }
    static JSONObject from(JSONObject binding,String selected,String effective)throws Exception{
        if(!selected.matches("[a-f0-9]{64}")||!effective.matches("[a-f0-9]{64}")||!"reviewer".equals(binding.opt("memberId")))return null;
        JSONObject row=new JSONObject().put("format",FORMAT).put("selectedRoute",selected).put("runnerRoute",effective).put("executionAuthorized",false)
                .put("actualProviderForEachRequestVerified",false);
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","teamRunId","planHash","stepCeiling"})row.put(key,binding.get(key));
        return row;
    }
    public static boolean matches(JSONObject row,JSONObject child,JSONObject proof){
        if(row==null||proof==null||!FORMAT.equals(row.opt("format"))||!Boolean.FALSE.equals(row.opt("executionAuthorized"))||
                !Boolean.FALSE.equals(row.opt("actualProviderForEachRequestVerified"))||!row.optString("selectedRoute").matches("[a-f0-9]{64}")||
                !row.optString("runnerRoute").matches("[a-f0-9]{64}")||row.optInt("stepCeiling")<1||!"completed".equals(child.opt("status"))||
                !child.optString("runId").equals(row.opt("runId"))||child.optInt("coordinatorEpoch",-1)!=row.optInt("coordinatorEpoch")||
                !child.optString("id").equals(row.opt("taskId"))||!Boolean.TRUE.equals(proof.opt("verdictSubmitted"))||
                !Boolean.TRUE.equals(proof.opt("completeOriginalFilesRead"))||!Boolean.TRUE.equals(proof.opt("safeReceiptAndModelPresentationVerified")))return false;
        for(String key:new String[]{"taskId","runId","teamRunId","planHash"})if(row.optString(key).isEmpty()||!row.optString(key).equals(proof.opt(key)))return false;
        return true;
    }
}
