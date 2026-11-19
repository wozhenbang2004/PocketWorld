package dev.pocketengine.ui.web

import dev.pocketengine.features.teams.TaskStoreTeamWriteHost
import dev.pocketengine.features.teams.TeamWorkItems
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.objects
import org.json.JSONArray
import org.json.JSONObject

/** Native review of a named existing teammate and a single stopped original assignment. Never accepts Web scopes. */
internal class PocketWebTeamFailedHandoff private constructor(private val page: TaskStoreTeamWriteHost.WorkPlanPage,
    private val sealed:TaskStore.TeamWorkReview, val preview:JSONObject) : AutoCloseable {
    fun confirm():JSONObject {
        val result=page.approveSoleLocalHandoff(sealed)
        val item=TeamWorkItems.find(result.getJSONObject("workPlan").getJSONArray("items"),preview.getString("workItemId"))
        val previous=item.getJSONObject("handoff")
        check(result.getString("id")==preview.getString("runId") && item.getString("memberId")==preview.getString("nextMemberId") &&
            item.getString("assignmentId")!=preview.getString("assignmentId") && previous.getString("sourceAssignmentId")==preview.getString("assignmentId") &&
            previous.getString("sourceRunId")==preview.getString("oldRunId") && previous.getString("sourceMemberId")==preview.getString("oldMemberId") &&
            item.getInt("ownerGeneration")==preview.getInt("ownerGeneration")+1 && item.getInt("itemRevision")==preview.getInt("itemRevision")+1) { "原交接的排队结果待核对，不重复提交" }
        return result
    }
    override fun close()=page.close()
    companion object {
        fun prepare(run:JSONObject,id:String,next:String,page:TaskStoreTeamWriteHost.WorkPlanPage):PocketWebTeamFailedHandoff {
            val plan=run.getJSONObject("workPlan");val original=TeamWorkItems.find(plan.getJSONArray("items"),id);val child=original.getJSONObject("assignmentTask")
            val members=run.getJSONArray("members").objects();val old=members.single{it.opt("id")==original.getString("memberId")};val recipient=members.single{it.opt("id")==next}
            check(old!==recipient && child.opt("status")=="failed" && child.opt("taskId")==original.opt("assignmentId") && child.opt("memberId")==old.opt("id")) {"原失败分工或所选成员已变化"}
            val sealed=page.reviewSoleLocalHandoff(id,next,original.getInt("itemRevision"),original.getInt("ownerGeneration"));val facts=sealed.view()
            val current=facts.getJSONObject("workItem")
            check(facts.getString("kind")=="failed_transfer" && facts.getBoolean("carryLocalRemainder") && !facts.getBoolean("budgetChanged") &&
                !facts.getBoolean("parentBudgetIncreaseRequired") && !facts.has("projectContext") && facts.getString("teamRunId")==run.getString("id") &&
                facts.getString("planId")==plan.getString("planId") && TeamWorkItems.sameIdentity(current,original) &&
                facts.getString("oldTaskId")==child.getString("taskId") && facts.getString("oldRunId")==child.getString("runId") && facts.getInt("oldEpoch")>0 &&
                facts.getString("newMemberId")==next && facts.getString("oldStatus")=="failed") {"原范围或预算已变化，未交接"}
            val nextSnapshot=facts.getJSONObject("newMemberSnapshot")
            check(nextSnapshot.getString("id")==next && nextSnapshot.optInt("identityRevision",1)==recipient.optInt("identityRevision",1) &&
                nextSnapshot.getInt("generation")==recipient.getInt("generation")) {"接手成员的原身份已改变"}
            val files=facts.getJSONArray("files").objects().map{it.getString("path")}
            val preserved=facts.getJSONArray("preservedDraftFiles").objects()
            check(files.isNotEmpty() && files.distinct().size==files.size && (!facts.has("draft") && preserved.isEmpty() ||
                facts.has("draft") && files.toSet()==preserved.map{it.getString("path")}.toSet())) {"旧草稿不能逐件核对"}
            val budget=facts.getJSONObject("parentBudget")
            val shown=JSONObject().put("runId",run.getString("id")).put("workItemId",id).put("assignmentId",child.getString("taskId"))
                .put("oldRunId",child.getString("runId")).put("oldMemberId",old.getString("id")).put("nextMemberId",next)
                .put("oldMemberName",old.optString("name").ifBlank{old.getString("id")}).put("nextMemberName",nextSnapshot.optString("name").ifBlank{next})
                .put("title",original.getString("title")).put("itemRevision",original.getInt("itemRevision")).put("ownerGeneration",original.getInt("ownerGeneration"))
                .put("files",JSONArray(files)).put("preserved",JSONArray(preserved.map{JSONObject().put("path",it.getString("path")).put("exists",it.getBoolean("exists")).put("version",it.getInt("version"))}))
                .put("effectCount",facts.getInt("confirmedEffectCount")).put("carried",facts.getInt("proposedDraftOperations"))
                .put("remainingModels",budget.getInt("remainingModelRequests")).put("remainingTools",budget.getInt("remainingToolOperations")).put("canDelete",facts.getBoolean("carryAllowDelete"))
            return PocketWebTeamFailedHandoff(page,sealed,shown)
        }
    }
}
