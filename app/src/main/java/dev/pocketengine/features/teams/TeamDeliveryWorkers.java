package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** The reviewer and unique integrator are native tasks, not roster members or summary text. */
public final class TeamDeliveryWorkers {
    private TeamDeliveryWorkers(){}
    public static String taskId(String team,String role,String hash){
        return UUID.nameUUIDFromBytes((role.equals("reviewer")?"team-review:"+team+":"+hash:"team-integration:"+team).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static boolean original(JSONObject task,String team,String host,String role,String hash){
        if(task==null||!role.matches("reviewer|integrator")||!hash.matches("[a-f0-9]{64}"))return false;
        JSONObject identity=task.optJSONObject("teamAssignment"),o=task.optJSONObject("origin");
        String id=taskId(team,role,hash);
        return identity!=null&&o!=null&&"owner".equals(task.opt("actorId"))&&"private".equals(task.opt("spaceId"))&&id.equals(task.opt("id"))&&host.equals(task.opt("parentTaskId"))&&
                (role.equals("reviewer")?"team_review":"team_integration").equals(o.opt("kind"))&&team.equals(o.opt("teamRunId"))&&id.equals(o.opt("assignmentId"))&&
                team.equals(identity.opt("teamRunId"))&&role.equals(identity.opt("memberId"))&&id.equals(identity.opt("assignmentId"))&&hash.equals(identity.opt("planHash"));
    }
    public static JSONObject unavailable(String team)throws Exception{
        return new JSONObject().put("teamRunId",team).put("workers",new JSONArray()).put("unboundRoles",new JSONArray()).put("complete",false)
                .put("activeAttempts",0).put("unresolvedCount",0).put("nativeActive",false).put("stage","needs_check").put("resourcesReleased",false).put("readOnly",true);
    }
    /** Two independent facts: SQL may retain an unresolved write after the runner has gone;
     * a runner may still be unwinding after SQL has already saved its terminal status. */
    static JSONObject observed(JSONObject source,boolean liveReview,boolean liveIntegration,java.util.function.Predicate<JSONObject> ownsLive)throws Exception{
        JSONArray all=source.getJSONArray("workers"),shown=new JSONArray(),unbound=new JSONArray();int active=0,unknown=0;
        boolean reviewBound=false,integrationBound=false,missingSettlement=false;
        for(int i=0;i<all.length();i++){
            JSONObject row=all.getJSONObject(i);boolean journal=row.getBoolean("activeAttempt"),unresolved=row.getBoolean("hasUnresolvedEffects");
            if(journal)active++;if(unresolved)unknown++;
            boolean reviewer="reviewer".equals(row.getString("role"));
            boolean live=(reviewer?liveReview:liveIntegration)&&ownsLive.test(row);
            if(live){if(reviewer)reviewBound=true;else integrationBound=true;}
            String status=row.getString("taskStatus");
            boolean missing=!journal&&!live&&status.matches("running|preparing|stopping|reconciling");missingSettlement|=missing;
            if(!journal&&!unresolved&&!live&&!missing&&!row.optBoolean("sameFrozenOnly")&&!(row.getBoolean("selectedVersion")&&status.matches("failed|interrupted|reconciling|paused|awaiting_user|needs_confirmation|cancelled|stopping")))continue;
            String state=unresolved?"needs_reconciliation":journal?(row.optBoolean("cancelRequested")||status.equals("stopping")?"stopping":"running")
                    :live?"releasing":missing?"needs_status_check":status;
            shown.put(row.put("nativeActive",live).put("state",state));
        }
        if(liveReview&&!reviewBound)unbound.put("reviewer");if(liveIntegration&&!integrationBound)unbound.put("integrator");
        boolean complete=source.getBoolean("complete")&&!missingSettlement;
        String stage=unknown>0?"needs_reconciliation":!complete?"needs_check":active>0||liveReview||liveIntegration?"in_progress":shown.length()>0?"stopped_or_waiting":"no_pending_original";
        return source.put("complete",complete).put("stage",stage).put("workers",shown).put("unboundRoles",unbound).put("activeAttempts",active).put("unresolvedCount",unknown).put("nativeActive",liveReview||liveIntegration)
                .put("resourcesReleased",complete&&!liveReview&&!liveIntegration&&active==0&&unknown==0).put("readOnly",true);
    }
    public static boolean sameAttempt(JSONObject row,JSONObject task){
        if(!row.optBoolean("canOpen")||!original(task,row.optString("teamRunId"),row.optString("hostTaskId"),row.optString("role"),row.optString("planHash")))return false;
        JSONObject identity=task.optJSONObject("teamAssignment");return row.optString("taskId").equals(task.opt("id"))&&row.optString("runId").equals(task.opt("runId"))&&
                row.optInt("epoch",-1)==task.optInt("coordinatorEpoch",-2)&&row.optString("reviewId").equals(identity.optString("reviewId"));
    }
}
