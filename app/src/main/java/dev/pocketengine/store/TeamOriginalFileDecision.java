package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.teams.TeamDeliveryWorkers;
import dev.pocketengine.features.teams.TeamIntegrationFiles;
import dev.pocketengine.features.teams.TeamWorkFiles;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only locator for the one real, unanswered file decision in the parent's original text team. */
public final class TeamOriginalFileDecision {
    private TeamOriginalFileDecision() {}
    private static final String ID="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    static boolean waitingCandidate(JSONObject work,JSONObject team,long now)throws Exception {
        JSONObject scope=team.optJSONObject("teamWriteScope"),plan=team.optJSONObject("teamWorkPlan"),review=team.optJSONObject("teamOutputReview");
        if(scope==null||plan==null||review==null||scope.has("projectSelection")||review.has("registrationKind")||review.has("previousReviewId")||
                work.optBoolean("cancelRequested")||team.optBoolean("cancelRequested")||work.optString("status").matches("completed|cancelled|stopping|paused|pausing|interrupted|reconciling")||
                team.optString("status").matches("completed|cancelled|stopping|paused|pausing|interrupted|reconciling")||
                !"owner".equals(review.opt("actorId"))||!"private".equals(review.opt("spaceId"))||!"active".equals(plan.opt("state"))||
                plan.optLong("expiresAt",0)<=now||plan.has("lastRevisionRequestId")||!work.getString("id").equals(scope.opt("parentTaskId"))||
                !work.getString("runId").equals(team.opt("parentRunId"))||!work.getString("runId").equals(scope.opt("parentRunId")))return false;
        JSONArray authors=scope.optJSONArray("assignments");if(authors==null||authors.length()==0)return false;
        for(int i=0;i<authors.length();i++){JSONObject a=authors.getJSONObject(i);if(a.has("remoteSelection")||a.has("projectSelection"))return false;}
        return true;
    }
    private static JSONObject onlyPending(JSONObject task,long now)throws Exception {
        if(!"awaiting_user".equals(task.opt("status"))||task.optBoolean("cancelRequested"))return null;
        JSONArray approvals=task.optJSONArray("approvals");if(approvals==null||approvals.length()>100)return null;JSONObject found=null;
        for(int i=0;i<approvals.length();i++){
            JSONObject a=approvals.optJSONObject(i);if(a==null)return null;if(!"pending".equals(a.opt("state"))||!task.getString("runId").equals(a.opt("runId"))||a.optLong("expiresAt",0)<=now)continue;
            if(found!=null)return null;found=a;
        }
        return found!=null&&"team_integrate_file".equals(found.opt("tool"))?found:null;
    }
    static JSONObject mark(SQLiteDatabase db,JSONObject work,JSONObject team,JSONObject child,long now)throws Exception {
        String teamRun=team.getString("runId");JSONObject reviewed=team.getJSONObject("teamOutputReview");String hash=reviewed.optString("planHash");
        if(!TeamDeliveryWorkers.original(child,teamRun,team.getString("id"),"integrator",hash))return null;
        JSONObject a=onlyPending(child,now),original=child.getJSONObject("teamAssignment"),plan=reviewed.getJSONObject("plan");
        if(a==null||!a.optString("id").matches(ID)||!reviewed.optString("reviewId").matches(ID)||!reviewed.getString("reviewId").equals(original.opt("reviewId"))||
                !TaskStore.capabilityFingerprint(plan).equals(hash)||!TeamWorkFiles.canonical(plan).equals(TeamWorkFiles.canonical(original.getJSONObject("plan")))||plan.has("retained")||plan.has("recovery")||
                !teamRun.equals(plan.opt("teamRunId"))||!team.getJSONObject("teamWriteScope").getString("targetProjectId").equals(original.opt("targetProjectId")))return null;
        JSONObject args=a.optJSONObject("arguments");if(args==null||args.length()!=2||!TaskStore.capabilityFingerprint(args).equals(a.opt("argumentsHash")))return null;
        JSONObject selected=null;JSONArray files=plan.getJSONArray("files");
        for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if(!"text_file".equals(f.opt("artifactKind")))return null;
            if(f.getString("path").equals(args.opt("path"))&&f.getString("artifactId").equals(args.opt("artifactId"))){if(selected!=null)return null;selected=f;}}
        String task=child.getString("id"),run=child.getString("runId");int epoch=child.getInt("coordinatorEpoch");
        if(selected==null||!run.matches(ID)||epoch<1||!teamRun.equals(selected.opt("teamRunId"))||!selected.getString("artifactId").matches(ID))return null;
        JSONObject binding=new JSONObject(original.toString()).put("taskId",task).put("runId",run).put("coordinatorEpoch",epoch);
        JSONObject capability=TeamIntegrationFiles.fileCapability(binding,selected);if(!TeamWorkFiles.canonical(capability).equals(a.opt("capabilityKey")))return null;
        try(Cursor r=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=? AND status='awaiting_user'",new String[]{task,run,Integer.toString(epoch)})){if(!r.moveToFirst())return null;}
        JSONObject pool=work.optJSONObject("teamReservationPool");
        if(pool==null||!work.getString("id").equals(pool.opt("parentTaskId"))||!work.getString("runId").equals(pool.opt("parentRunId")))return null;
        // This reads the actual no-dispatch receipt and confirms this operation never took a parent tool reservation.
        JSONObject proof=TaskStoreTeamFileApproval.source(db,binding,a,run,epoch,pool);
        return new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("approvalId",a.getString("id"))
                .put("operationId",proof.getString("operationId")).put("artifactId",selected.getString("artifactId")).put("sourceUpdatedAt",child.getLong("updatedAt"))
                .put("planHash",hash).put("reviewId",reviewed.getString("reviewId")).put("capabilityHash",TaskStore.capabilityFingerprint(capability)).put("expiresAt",a.getLong("expiresAt"));
    }
    /** A bounded cache invalidator. It carries no path, file data, approval payload or authority. */
    static void projectChildHeader(JSONObject task,JSONObject header)throws Exception {
        JSONObject o=task.optJSONObject("origin");if(o==null||!"team_integration".equals(o.opt("kind"))||
                !"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))||
                !TeamDeliveryWorkers.taskId(o.optString("teamRunId"),"integrator","").equals(task.opt("id")))return;
        JSONObject a=onlyPending(task,System.currentTimeMillis());
        header.put("originalFileApprovalId",a==null?"":a.optString("id"));
    }
    static void retireCached(SQLiteDatabase db,JSONObject view,long now)throws Exception {
        JSONArray rows=view.optJSONArray("ownerWorkTeams");for(int i=0;rows!=null&&i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i),mark=row==null?null:row.optJSONObject("pendingFileOperation");
            if(mark==null)continue;
            boolean live=false;
            if(mark.optLong("expiresAt")>now)try(Cursor c=db.rawQuery("SELECT CASE WHEN length(CAST(ui_header AS BLOB))<=32768 THEN ui_header END,updated_at FROM tasks WHERE task_id=?",new String[]{mark.optString("taskId")})){
                if(c.moveToFirst()&&!c.isNull(0)){JSONObject h=new JSONObject(c.getString(0));
                    live="awaiting_user".equals(h.opt("status"))&&!h.optBoolean("cancelRequested")&&mark.optString("runId").equals(h.opt("runId"))&&
                            mark.optInt("epoch",-1)==h.optInt("coordinatorEpoch",-2)&&
                            (h.has("originalFileApprovalId")?mark.optString("approvalId").equals(h.opt("originalFileApprovalId")):
                                    c.getLong(1)==mark.optLong("sourceUpdatedAt",-1)); // Unchanged pre-upgrade header. Every new child write stamps the current decision.
                }
            }catch(org.json.JSONException malformed){live=false;}
            if(!live){row.remove("pendingFileOperation");if(row.optBoolean("attentionFromFileOnly"))row.put("needsAttention",false);row.remove("attentionFromFileOnly");}
        }
    }
    /** Verify the task materialized by the native reader before leaving the original conversation. */
    public static boolean stillPending(JSONObject task,JSONObject mark,long now)throws Exception {
        JSONObject a=onlyPending(task,now),read=task.optJSONObject("capabilityApprovalRead");
        if(a==null||read==null||!read.optBoolean("approvalComplete")||!mark.getString("approvalId").equals(read.opt("approvalId"))||
                read.getJSONArray("pendingApprovalIds").length()!=1||!mark.getString("approvalId").equals(read.getJSONArray("pendingApprovalIds").opt(0))||
                !"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))||!task.getString("id").equals(mark.opt("taskId"))||
                !task.getString("runId").equals(mark.opt("runId"))||task.getInt("coordinatorEpoch")!=mark.optInt("epoch",-1)||
                !a.getString("id").equals(mark.opt("approvalId"))||a.getLong("expiresAt")!=mark.optLong("expiresAt",-1))return false;
        JSONObject cap=a.optJSONObject("capability"),args=a.optJSONObject("arguments"),file=cap==null?null:cap.optJSONObject("file");
        return cap!=null&&TaskStore.capabilityFingerprint(cap).equals(mark.opt("capabilityHash"))&&"integrator".equals(cap.opt("memberId"))&&
                task.getString("id").equals(cap.opt("assignmentId"))&&mark.getString("reviewId").equals(cap.opt("reviewId"))&&mark.getString("planHash").equals(cap.opt("planHash"))&&
                file!=null&&args!=null&&args.length()==2&&args.getString("path").equals(file.opt("path"))&&args.getString("artifactId").equals(file.opt("artifactId"))&&args.getString("artifactId").equals(mark.opt("artifactId"));
    }
}
