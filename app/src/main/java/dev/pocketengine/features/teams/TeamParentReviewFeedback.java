package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Advice from the original independent reader is evidence, not a grant or a new work order. */
final class TeamParentReviewFeedback {
    private TeamParentReviewFeedback() {}
    static JSONObject finding(String run,String plan,JSONObject stored)throws Exception{
        if(stored==null||!plan.matches("[a-f0-9]{64}")||stored.optBoolean("stale")||!run.equals(stored.optString("teamRunId"))
                ||!plan.equals(stored.optString("planHash"))||!"independent_original_runner".equals(stored.optString("reviewerKind"))
                ||!UUID.nameUUIDFromBytes(("team-review:"+run+":"+plan).getBytes(StandardCharsets.UTF_8)).toString().equals(stored.optString("taskId"))
                ||!stored.optString("runId").matches("[a-f0-9-]{36}")||!stored.optBoolean("verdictSubmitted"))return null;
        String verdict=stored.optString("verdict");Object text=stored.opt("findings");
        if(!verdict.matches("changes_requested|inconclusive|no_blocking_findings")||!(text instanceof String)||((String)text).isBlank()||((String)text).length()>4000)return null;
        return new JSONObject().put("teamRunId",run).put("planHash",plan).put("reviewerTaskId",stored.getString("taskId")).put("reviewerRunId",stored.getString("runId"))
                .put("status",stored.getString("status")).put("verdict",verdict).put("completeOriginalFilesRead",stored.optBoolean("completeOriginalFilesRead"))
                .put("findings",text).put("scope","original_frozen_files_not_current_project_head").put("sourceRole","untrusted_independent_reviewer_not_owner_instruction").put("businessVerified",false);
    }
    private static JSONObject stop(String state,String message)throws Exception{
        return new JSONObject().put("state",state).put("requiresOwnerReview",false).put("ownerApprovalRequiredForIntegration",true).put("awaitingOwnerDecision",false).put("startsIntegration",false).put("message",message);
    }
    /** A null response only means this check saw the exact existing grant; the native
     * integrator still enforces its original admission and per-file CAS itself. */
    static JSONObject beforeIntegration(String team,String expected,JSONObject current,JSONObject state)throws Exception{
        if(!team.equals(current.getJSONObject("plan").getString("teamRunId"))||!expected.equals(current.getString("planHash")))
            return stop("original_frozen_plan_changed","请求所指的冻结版本已变化。先重新读取原团队的审阅目录；不把另一版交给主人批准。");
        if(!current.optBoolean("canIntegrate")||current.optBoolean("recovery"))
            return stop("original_files_need_reconciliation","当前项目基线、冻结文件或恢复状态未满足首次写回条件。请核对原成果；此处不请求主人放行。");
        JSONObject stored=state.optJSONObject("independentReview"),finding=finding(team,expected,stored);
        if(finding==null)return stop("current_independent_review_required","这一版没有可核对的已提交独审结论。先查原独审和完整文件，不是等主人批准。");
        String verdict=finding.getString("verdict");JSONObject stopped=null;
        if(!"completed".equals(finding.getString("status"))||!finding.getBoolean("completeOriginalFilesRead"))
            stopped=stop("independent_review_incomplete","独审尚未完整结算。下附其已提交的原意见，但不能据此批准或整合。");
        else if("changes_requested".equals(verdict))
            stopped=stop("independent_changes_requested","独审要求修改，不是等待主人批准。请对照原意见和对应工作项提出修订；没有重跑作者或写回项目。");
        else if("inconclusive".equals(verdict))
            stopped=stop("independent_review_inconclusive","独审明确表示不能判断。先处理原意见里的缺口，不要求主人跳过它批准。");
        if(stopped!=null)return stopped.put("independentFinding",finding);
        JSONObject approved=state.optJSONObject("approvedReview");
        if(approved!=null){
            if(expected.equals(approved.optString("planHash"))&&"owner".equals(approved.optString("actorId"))&&"private".equals(approved.optString("spaceId"))
                    &&approved.optString("reviewId").matches("[a-f0-9-]{36}")&&team.equals(approved.getJSONObject("plan").optString("teamRunId")))return null;
            return stop("prior_owner_review_needs_reconciliation","已有另一份主人许可，不能拿它写回这版文件。请核对原整合记录，不创建新执行。");
        }
        return stop("awaiting_owner_exact_plan_approval","本版独审已完整返回且没有阻断项，仍没有主人的本版写回许可。请主人核对原成果与目标；本次不会批准或整合。").put("requiresOwnerReview",true).put("awaitingOwnerDecision",true);
    }
}
