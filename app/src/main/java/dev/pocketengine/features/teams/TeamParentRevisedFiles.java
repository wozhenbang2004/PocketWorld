package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Project the owner's actual draft-revision decision. This class cannot issue
 * a grant, replace an approval or start/resume the previous integrator. */
final class TeamParentRevisedFiles {
    private TeamParentRevisedFiles() {}
    record Round(String operation,String request,String previousReview,String previousPlan,String previousIntegrationRun,int affectedItems) {}
    private static Round round(JSONObject parent,JSONObject run,JSONObject sql)throws Exception {
        return TeamParentRevisionOffer.local(run)?ownerRound(parent,run,sql):null;
    }
    // Foreground historical reading remains valid after work stops; it confers no action.
    static Round ownerRound(JSONObject parent,JSONObject run,JSONObject sql)throws Exception {
        if(sql==null||!TeamParentRevisionOffer.localFiles(run))return null;
        JSONObject saved=run.getJSONObject("workPlan");String id=run.getString("id"),request=sql.optString("lastRevisionRequestId");
        if(!request.matches("[a-f0-9-]{36}")||!sql.optString("lastRevisionReviewDigest").matches("[a-f0-9]{64}")||
                !request.equals(saved.optString("lastRevisionRequestId"))||!sql.optString("planId").equals(saved.optString("planId"))||sql.optInt("revision")!=saved.optInt("revision"))return null;
        JSONArray proposals=run.optJSONArray("parentRequests");JSONObject proposal=null;
        for(int i=0;proposals!=null&&i<proposals.length();i++){JSONObject p=proposals.optJSONObject(i);
            if(p!=null&&"applied_to_work_revision".equals(p.optString("state"))&&request.equals(p.optString("revisionRequestId"))){
                if(proposal!=null)return null;proposal=p;}}
        if(proposal==null||!"revision_proposal".equals(proposal.optString("kind"))||!Boolean.FALSE.equals(proposal.opt("executionAuthorized"))||!parent.getString("parentTaskId").equals(proposal.opt("parentTaskId")))return null;
        JSONObject basis=proposal.optJSONObject("integratedBasis");if(basis==null||!integrator(id).equals(basis.opt("taskId"))||!id.equals(basis.opt("teamRunId"))||!sql.getString("planId").equals(basis.opt("planId"))||sql.getInt("revision")!=basis.optInt("planRevision",-2)+1)return null;
        for(String k:new String[]{"parentTaskId","parentRunId","parentEpoch","targetProjectId"})if(!sql.get(k).equals(parent.opt(k))||!sql.get(k).equals(basis.opt(k)))return null;
        String hash=basis.getString("planHash"),review=basis.getString("reviewId");if(!hash.matches("[a-f0-9]{64}")||!review.matches("[a-f0-9-]{36}"))return null;
        // Each affected work item has its own receipt inside the owner approval SQL transaction.
        boolean target=false;int affected=0;JSONArray items=sql.getJSONArray("items");
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);JSONArray history=item.optJSONArray("revisionHistory");boolean seen=false;
            for(int j=0;history!=null&&j<history.length();j++){JSONObject retired=history.getJSONObject(j);if(!request.equals(retired.optString("revisionRequestId")))continue;
                JSONObject previous=retired.optJSONObject("workItem");if(seen||previous==null||!hash.equals(retired.opt("planHash"))||!review.equals(retired.opt("integrationReviewId")))return null;
                for(String k:new String[]{"workItemId","assignmentId","memberId","ownerGeneration"})if(!item.get(k).equals(previous.opt(k)))return null;
                if(previous.optInt("itemRevision",-2)+1!=item.getInt("itemRevision"))return null;
                if(item.getString("workItemId").equals(proposal.getString("workItemId"))){
                    for(String k:new String[]{"workItemId","assignmentId","memberId","ownerGeneration","itemRevision"})if(!previous.get(k).equals(proposal.opt(k)))return null;
                    target=true;
                }
                seen=true;affected++;
            }
        }
        return target&&affected>0?new Round(proposal.getString("operationId"),request,review,hash,basis.optString("runId"),affected):null;
    }
    private static boolean frozenMatches(JSONObject sql,JSONObject plan)throws Exception{
        java.util.Map<String,JSONObject> refs=new java.util.HashMap<>();JSONArray items=sql.getJSONArray("items"),files=plan.getJSONArray("files");
        if(items.length()<1||items.length()>8||files.length()<1||files.length()>8)return false;
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);JSONArray artifacts=item.optJSONArray("artifacts");
            if(!"completed".equals(item.optString("status"))||artifacts==null||artifacts.length()<1)return false;
            for(int n=0;n<artifacts.length();n++){JSONObject f=artifacts.getJSONObject(n);
                if(!item.get("assignmentId").equals(f.opt("assignmentId"))||!item.get("memberId").equals(f.opt("memberId"))||refs.put(f.getString("artifactId"),f)!=null)return false;
            }}
        for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i),original=refs.remove(f.getString("artifactId"));if(original==null)return false;
            for(String k:new String[]{"teamRunId","targetProjectId","assignmentId","memberId","draftRevisionId","runId","path","sha256"})if(!original.has(k)||!original.get(k).equals(f.opt(k)))return false;}
        return refs.isEmpty();
    }
    private static JSONObject outcome(String team,Round r,String state,String message)throws Exception{
        return new JSONObject().put("teamRunId",team).put("proposalOperationId",r.operation).put("revisionRequestId",r.request).put("affectedWorkItems",r.affectedItems)
                .put("previousPlanHash",r.previousPlan).put("draftRevisionOwnerConfirmed",true).put("reviewState",state).put("state",state)
                .put("ownerApprovalRequiredForIntegration",true).put("requiresOwnerReview",false).put("awaitingOwnerDecision",false).put("queryOnly",true)
                .put("startsIntegration",false).put("businessVerified",false).put("message",message);
    }
    private static String integrator(String team){return UUID.nameUUIDFromBytes(("team-integration:"+team).getBytes(StandardCharsets.UTF_8)).toString();}
    /** The owner-approved draft revision transaction itself checked the previous completed integration.
     * This is a historical receipt, deliberately not a read of old file bodies or a claim about their current heads. */
    static JSONObject previous(JSONObject parent,JSONObject run,JSONObject sql,String expected)throws Exception {
        Round r=round(parent,run,sql);if(r==null||!expected.equals(r.previousPlan))return null;
        return outcome(run.getString("id"),r,"prior_write_before_draft_revision","这是修订前已写回的那一版；主人后来只批准了新草稿范围。这次没有读取旧文件正文，也没有证明新稿已写回。")
                .put("planHash",r.previousPlan).put("reviewId",r.previousReview).put("historicalWriteAtDraftApproval",true)
                .put("currentHeadsChecked",false).put("approvalTransfersToRevisedFiles",false);
    }
    static boolean hasIntegrationRevision(JSONObject run,JSONObject sql) {
        return sql!=null&&sql.has("lastRevisionRequestId")&&TeamParentRevisionOffer.localFiles(run);
    }
    /** Model-facing read only. A V1 head may still be correct, but cannot settle
     * an owner-confirmed V2 draft. Keep its real receipt and cursors as history. */
    static JSONObject integrationForReport(JSONObject parent,JSONObject run,JSONObject sql,JSONObject decisions,JSONObject actual)throws Exception {
        String team=run.getString("id"),hash=actual.optString("planHash");
        JSONObject observation=new JSONObject().put("teamRunId",team).put("status","revised_integration_needs_verification")
                .put("currentRevisionWritebackVerified",false).put("executionAuthorized",false).put("businessVerified",false)
                .put("message","原分工已有修订，但尚不能核对这版的写回。请读原审阅，不把上版写回当成本版交付。");
        Round r=ownerRound(parent,run,sql);
        if(r==null)return observation;
        JSONObject registered=registered(parent,run,sql,hash,decisions,actual);
        if(registered!=null){
            boolean written="completed".equals(actual.opt("status"))&&Boolean.TRUE.equals(actual.opt("currentFilesComplete"))
                    &&Boolean.FALSE.equals(actual.opt("activeAttempt"))&&Boolean.FALSE.equals(actual.opt("hasUnresolved"));
            return new JSONObject(actual.toString()).put("revisionDecision",registered).put("currentRevisionWritebackVerified",written)
                    .put("workPlanRevision",sql.getInt("revision")).put("executionAuthorized",false);
        }
        observation.put("workPlanRevision",sql.getInt("revision")).put("draftRevisionOwnerConfirmed",true)
                .put("priorWriteAtDraftApproval",true).put("previousPlanHash",r.previousPlan()).put("previousReviewId",r.previousReview());
        if(!team.equals(actual.opt("teamRunId"))||!integrator(team).equals(actual.opt("taskId"))
                ||!r.previousPlan().equals(hash)||!r.previousReview().equals(actual.opt("reviewId"))
                ||!r.previousIntegrationRun().matches("[a-f0-9-]{36}")||!r.previousIntegrationRun().equals(actual.opt("runId")))return observation;
        JSONObject history=new JSONObject(actual.toString()).put("scope","previous_written_plan_not_revised_draft")
                .put("historical",true).put("currentRevisionWritebackVerified",false);
        if(history.has("currentFilesComplete"))history.put("previousFilesStillMatch",history.remove("currentFilesComplete"));
        if(history.has("currentVerifiedFiles"))history.put("previousFilesStillMatchingCount",history.remove("currentVerifiedFiles"));
        return observation.put("status","previous_writeback_only").put("previousIntegration",history)
                .put("message","下面只核对修订前的已写回版本。主人确认新草稿不等于本版已写回；请在原审阅核对作者交付、独审或本版批准。");
    }
    /** Only a private V2 grant on the same unique integrator, never the V1 receipt or an author status. */
    static JSONObject registered(JSONObject parent,JSONObject run,JSONObject sql,String expected,JSONObject approvals,JSONObject actual)throws Exception{
        Round r=round(parent,run,sql);if(r==null)return null;String id=run.getString("id");JSONObject grant=approvals.optJSONObject("approvedReview");
        if(grant==null||expected.equals(r.previousPlan)||!expected.matches("[a-f0-9]{64}")||!expected.equals(grant.opt("planHash"))||!r.previousPlan.equals(grant.opt("previousPlanHash"))||!r.previousReview.equals(grant.opt("previousReviewId"))
                ||!"owner".equals(grant.opt("actorId"))||!"private".equals(grant.opt("spaceId"))||!id.equals(actual.opt("teamRunId"))||!integrator(id).equals(actual.opt("taskId"))
                ||!expected.equals(actual.opt("planHash"))||!grant.getString("reviewId").equals(actual.opt("reviewId")))return null;
        JSONObject plan=grant.getJSONObject("plan"),old=plan.optJSONObject("recovery"),finding=TeamParentReviewFeedback.finding(id,expected,approvals.optJSONObject("independentReview"));
        if(!id.equals(plan.opt("teamRunId"))||!frozenMatches(sql,plan)||old==null||!integrator(id).equals(old.opt("taskId"))||!r.previousPlan.equals(old.opt("planHash"))||!r.previousReview.equals(old.opt("reviewId"))
                ||finding==null||!"completed".equals(finding.opt("status"))||!finding.optBoolean("completeOriginalFilesRead")||!"no_blocking_findings".equals(finding.opt("verdict")))return null;
        JSONObject result=outcome(id,r,"revised_owner_approval_registered","主人已单独批准这版文件，原唯一整合者的当前状态另列。许可不是新文件已写回；本次不启动或重试。")
                .put("planHash",expected).put("reviewId",grant.getString("reviewId")).put("ownerApprovalForThisVersion",true).put("integrationTaskId",actual.getString("taskId"))
                .put("registeredIntegratorStatus",actual.getString("status"));
        for(String flag:new String[]{"activeAttempt","hasUnresolved","currentFilesComplete"})if(actual.opt(flag) instanceof Boolean)result.put(flag,actual.get(flag));return result;
    }
    /** The current read was prepared from real frozen originals, real heads and the previous integrator receipts. */
    static JSONObject awaiting(JSONObject parent,JSONObject run,JSONObject sql,String expected,JSONObject current,JSONObject approvals)throws Exception{
        Round r=round(parent,run,sql);if(r==null||!Boolean.TRUE.equals(current.opt("recovery"))||!expected.equals(current.opt("planHash"))||expected.equals(r.previousPlan))return null;
        String id=run.getString("id");JSONObject plan=current.getJSONObject("plan"),old=plan.optJSONObject("recovery"),grant=approvals.optJSONObject("approvedReview");
        if(!id.equals(plan.opt("teamRunId"))||old==null||!integrator(id).equals(old.opt("taskId"))||!r.previousReview.equals(old.opt("reviewId"))||!r.previousPlan.equals(old.opt("planHash"))||
                grant==null||!r.previousReview.equals(grant.opt("reviewId"))||!r.previousPlan.equals(grant.opt("planHash"))||!"owner".equals(grant.opt("actorId"))||!"private".equals(grant.opt("spaceId")))return null;
        JSONObject out=outcome(id,r,"revised_files_need_reconciliation","原作者草稿修订已由主人确认。当前原件、作者结算或旧整合回执仍需核对，不能用上版许可批准或写回。")
                .put("planHash",expected).put("ownerApprovalForThisVersion",false).put("registeredIntegratorPlanHash",r.previousPlan);
        if(!frozenMatches(sql,plan)||!Boolean.TRUE.equals(current.opt("canIntegrate"))||!Boolean.FALSE.equals(current.opt("integrationActive"))||!Boolean.FALSE.equals(current.opt("integrationUnresolved"))||
                !"completed".equals(current.opt("integrationStatus"))||current.getJSONArray("incompleteAssignments").length()>0)return out;
        JSONArray diffs=current.getJSONArray("diffs"),files=plan.getJSONArray("files");int retained=0,changed=0;
        if(diffs.length()==0||diffs.length()>8||files.length()!=diffs.length())return out;
        for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i),d=diffs.getJSONObject(i);
            if(!f.getString("artifactId").equals(d.opt("artifactId"))||!(d.opt("diff") instanceof String)||d.optBoolean("truncated")||"file_tombstone".equals(f.optString("artifactKind")))return out;
            if("retain_applied".equals(d.opt("disposition")))retained++;
            else if("apply_reviewed".equals(d.opt("disposition"))&&Boolean.TRUE.equals(d.opt("matchesOwnerBase")))changed++;
            else return out;
        }
        if(changed==0)return out;
        out.put("retainedOriginalFiles",retained).put("filesWithNewDrafts",changed);
        JSONObject finding=TeamParentReviewFeedback.finding(id,expected,approvals.optJSONObject("independentReview"));String state,message;
        if(finding==null){state="revised_files_need_independent_review";message="主人批准的是修订草稿，原作者已经交回这版文件。可按这个版本请求独审，上版写回许可不适用。";}
        else if(!"completed".equals(finding.opt("status"))||!finding.optBoolean("completeOriginalFilesRead")){state="revised_review_incomplete";message="本版独审尚未完整结算，不能要求主人跳过独审。";}
        else if(!"no_blocking_findings".equals(finding.opt("verdict"))){state="changes_requested".equals(finding.opt("verdict"))?"revised_changes_requested":"revised_review_inconclusive";message="本版独审没有放行；请读这次原意见，不要请求主人批准或使用上版许可。";}
        else{state="revised_files_require_separate_owner_approval";message="本版独审已完成且未报阻断，但只有上版写回许可。本入口不能批准、启动或重放；不能向主人报告原项目已更新。";}
        if(finding!=null)out.put("independentFinding",finding);
        return out.put("state",state).put("reviewState",state).put("message",message);
    }
}
