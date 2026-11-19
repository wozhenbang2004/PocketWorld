package dev.pocketengine.features.teams;

import dev.pocketengine.features.local.project.ProjectSources;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** A first local project edition only. Never convert post-handoff, remote, revised or byte-only work into text approval. */
public final class TeamFirstProjectReview {
    private TeamFirstProjectReview(){}
    private static final int MAX_FIRST_EDITION=8;
    public static final String REGISTRATION="first_local_project_text_v1";
    public static boolean registrationOnly(JSONObject approval){return approval!=null&&REGISTRATION.equals(approval.opt("registrationKind"));}
    private static void need(boolean ok){if(!ok)throw new SecurityException("不能证明这是首版、全本机、完整独审的原项目文本，保留待审");}
    private static boolean same(Object a,Object b)throws Exception{return a!=null&&b!=null&&ProjectSources.canonical(a).equals(ProjectSources.canonical(b));}
    public static JSONObject begin(JSONObject team,JSONObject parent,JSONObject dev,JSONObject verdict,String hash)throws Exception{
        need(!team.has("teamOutputReview")&&!team.optBoolean("cancelRequested")&&"active".equals(team.getJSONObject("teamWorkPlan").opt("state"))&&
                team.getJSONObject("teamWorkPlan").optLong("expiresAt")>System.currentTimeMillis());
        return original(team,parent,dev,verdict,hash);
    }
    /** Read the retained owner's very first registration, not an alternative way to seek or refresh approval. */
    public static JSONObject registered(JSONObject team,JSONObject parent,JSONObject dev,JSONObject verdict,String hash)throws Exception{
        JSONObject saved=team.getJSONObject("teamOutputReview"),plan=saved.getJSONObject("plan");
        need(registrationOnly(saved)&&Boolean.TRUE.equals(saved.opt("separateIntegratorStartRequired"))&&"owner".equals(saved.opt("actorId"))&&
                "private".equals(saved.opt("spaceId"))&&saved.optString("reviewId").matches("[a-f0-9-]{36}")&&saved.optLong("approvedAt")>0&&
                !saved.has("previousReviewId")&&!saved.has("previousPlanHash")&&!plan.has("recovery")&&hash.equals(saved.opt("planHash"))&&
                hash.equals(ProjectSources.digest(plan))&&same(plan.opt("teamRunId"),parent.opt("teamRunId"))&&same(plan.opt("targetProjectId"),parent.opt("targetProjectId")));
        return original(team,parent,dev,verdict,hash).put("state","registered_project_first")
                .put("registeredReviewId",saved.getString("reviewId")).put("registeredAt",saved.getLong("approvedAt"));
    }
    private static JSONObject original(JSONObject team,JSONObject parent,JSONObject dev,JSONObject verdict,String hash)throws Exception{
        JSONObject work=team.getJSONObject("teamWorkPlan"),scope=team.getJSONObject("teamWriteScope"),base=scope.getJSONObject("projectSelection"),ind=team.getJSONObject("teamIndependentReview");
        need(!team.has("teamOutputReviewHistory")&&work.optInt("revision",-1)==1&&!work.has("lastRevisionRequestId")&&hash.matches("[a-f0-9]{64}"));
        JSONObject copy=new JSONObject(base.toString());copy.remove("selectionHash");
        need(ProjectSources.digest(copy).equals(base.opt("selectionHash"))&&"pocketworld.team-project-selection.v1".equals(base.opt("format"))&&
                "owner_baseline".equals(base.opt("role"))&&!base.has("previousDelivery")&&!base.has("originalPackageArtifactId")&&!base.optBoolean("knownFailedCopy")&&
                !base.optBoolean("ownerSelectedKnownFailedBaseline")&&!base.optBoolean("remoteProjectIntegratorOnly")&&Boolean.FALSE.equals(base.opt("credentialsDelegated")));
        for(String key:new String[]{"teamRunId","parentTaskId","parentRunId","parentEpoch"})need(same(parent.opt(key),work.opt(key))&&same(parent.opt(key),base.opt(key)));
        need(same(parent.opt("targetProjectId"),scope.opt("targetProjectId"))&&same(parent.opt("targetProjectId"),work.opt("targetProjectId"))&&same(parent.opt("targetProjectId"),base.opt("parentProjectId"))&&
                same(dev.opt("checkoutId"),base.opt("parentCheckoutId"))&&same(dev.opt("projectId"),base.opt("parentProjectId"))&&same(dev.opt("runtime"),base.opt("runtimeId"))&&
                work.getJSONArray("items").length()>0&&work.getJSONArray("items").length()<=MAX_FIRST_EDITION&&work.getJSONArray("items").length()==scope.getJSONArray("assignments").length());
        need(base.optString("parentSourceDigest").matches("[a-f0-9]{64}")&&base.optString("sourceDigest").matches("[a-f0-9]{64}")&&base.optString("snapshotIdentity").matches("[a-f0-9]{64}")&&
                same(base.opt("inventoryHash"),work.opt("inventoryHash"))&&"independent_original_runner".equals(ind.opt("reviewerKind"))&&"completed".equals(ind.opt("status"))&&
                !ind.optBoolean("stale")&&Boolean.TRUE.equals(ind.opt("verdictSubmitted"))&&Boolean.TRUE.equals(ind.opt("completeOriginalFilesRead"))&&"no_blocking_findings".equals(ind.opt("verdict"))&&
                hash.equals(ind.opt("planHash"))&&same(parent.opt("teamRunId"),ind.opt("teamRunId"))&&"completed".equals(verdict.opt("status"))&&"no_blocking_findings".equals(verdict.opt("verdict"))&&
                Boolean.TRUE.equals(verdict.opt("completeOriginalFilesRead")));
        return new JSONObject().put("state","eligible_project_first").put("teamRunId",parent.get("teamRunId")).put("parentTaskId",parent.get("parentTaskId")).put("parentRunId",parent.get("parentRunId"))
                .put("parentEpoch",parent.get("parentEpoch")).put("targetProjectId",parent.get("targetProjectId")).put("checkoutId",dev.get("checkoutId")).put("planHash",hash)
                .put("reviewerTaskId",ind.get("taskId")).put("reviewerRunId",ind.get("runId")).put("workPlanId",work.get("planId")).put("selectionHash",base.get("selectionHash"))
                .put("parentSourceDigest",base.get("parentSourceDigest")).put("sourceDigest",base.get("sourceDigest")).put("snapshotIdentity",base.get("snapshotIdentity"))
                .put("reviewRouteCaptured",Boolean.TRUE.equals(verdict.opt("reviewRouteCaptured")));
    }
    public static Map<String,JSONObject> originals(JSONObject independent)throws Exception{
        JSONArray files=independent.getJSONArray("files");need(files.length()>=1&&files.length()<=MAX_FIRST_EDITION);Map<String,JSONObject> pending=new HashMap<>();
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);
            int units=file.getInt("totalUnits");need(units>=0&&file.getInt("safeReceiptPrefix")>=units&&file.getInt("modelPresentedPrefix")>=units&&Boolean.TRUE.equals(file.opt("complete"))&&
                    "UTF-16".equals(file.opt("coverageUnit"))&&"text_file".equals(file.opt("artifactKind"))&&pending.put(file.getString("artifactId"),file)==null);}
        return pending;
    }
    public static void author(JSONObject team,JSONObject item,JSONObject task,JSONObject selected,Map<String,JSONObject> pending)throws Exception{
        JSONObject scope=team.getJSONObject("teamWriteScope"),identity=task.getJSONObject("teamAssignment"),origin=task.getJSONObject("origin"),ctx=task.getJSONObject("teamProject");
        need("completed".equals(task.opt("status"))&&"completed".equals(item.opt("status"))&&!task.optBoolean("cancelRequested")&&!task.has("teamRemoteAssignmentScope")&&
                !item.has("remoteSelection")&&!identity.has("remoteSelection")&&!item.has("revisionRequestId")&&!identity.has("revisionRequestId")&&!identity.has("seedArtifacts")&&
                item.getInt("itemRevision")==1&&item.getInt("ownerGeneration")==0&&item.getJSONArray("assignmentHistory").length()==0&&identity.optInt("assignmentRevision",1)==1&&
                TeamWorkItemAddress.of(item).equals(TeamWorkItemAddress.of(identity))&&same(task.opt("parentTaskId"),team.opt("id"))&&same(task.opt("id"),origin.opt("assignmentId"))&&same(task.opt("id"),item.opt("assignmentId"))&&
                "team_assignment".equals(origin.opt("kind"))&&same(origin.opt("teamRunId"),team.opt("runId"))&&same(identity.opt("teamRunId"),team.opt("runId"))&&
                same(item.opt("memberId"),origin.opt("memberId"))&&same(item.opt("files"),identity.opt("files"))&&same(scope.opt("targetProjectId"),identity.opt("targetProjectId")));
        JSONArray members=team.getJSONObject("teamWorkPlan").getJSONObject("memberSelection").getJSONArray("members");int originalMembers=0;
        for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);if(item.getString("memberId").equals(member.opt("id"))){need("phone".equals(member.opt("device"))&&"model".equals(member.optString("runner","model"))&&!member.optBoolean("exited"));originalMembers++;}}
        need(originalMembers==1);JSONArray participants=scope.getJSONArray("assignments");int count=0;
        for(int i=0;i<participants.length();i++){JSONObject member=participants.getJSONObject(i);if(!item.getString("workItemId").equals(member.opt("workItemId")))continue;
            need(!member.has("remoteSelection")&&same(item.opt("memberId"),member.opt("memberId"))&&same(item.opt("files"),member.opt("files"))&&same(selected,member.opt("projectSelection")));count++;}
        need(count==1&&same(selected,identity.opt("projectSelection"))&&same(selected,ctx.opt("selection")));
        JSONArray refs=item.getJSONArray("artifacts");need(refs.length()>0&&refs.length()<=MAX_FIRST_EDITION);
        for(int i=0;i<refs.length();i++){
            JSONObject ref=refs.getJSONObject(i),seen=pending.remove(ref.getString("artifactId")),evidence=ref.getJSONObject("projectEvidence");
            need(seen!=null&&"text_file".equals(ref.opt("artifactKind"))&&!ref.has("remoteContribution")&&same(ref.opt("assignmentId"),task.opt("id"))&&same(ref.opt("runId"),task.opt("runId"))&&
                    same(selected.opt("selectionHash"),evidence.opt("selectionHash"))&&TeamWorkDependencyIndex.checks(ref).optBoolean("checksAvailable"));
            for(String key:new String[]{"sha256","runId","teamRunId","assignmentId","memberId","path","draftRevisionId","targetProjectId"})need(same(ref.opt(key),seen.opt(key)));
        }
    }
}
