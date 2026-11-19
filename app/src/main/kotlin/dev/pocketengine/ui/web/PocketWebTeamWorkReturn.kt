package dev.pocketengine.ui.web

import dev.pocketengine.features.teams.TaskStoreTeamWriteHost
import dev.pocketengine.features.teams.TeamWorkItems
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.objects
import org.json.JSONArray
import org.json.JSONObject

/** The view chooses an item key, never a grant, digest, file set or command. */
internal class PocketWebTeamWorkReturn private constructor(
    val page: TaskStoreTeamWriteHost.WorkPlanPage, private val review: TaskStore.TeamWorkReview,
    val source: Source, val preview: JSONObject
) : AutoCloseable {
    data class Source(val team: String, val plan: String, val work: JSONObject, val childRun: String, val stoppedStatus: String,
        val teamGeneration: Int, val memberGeneration: Int, val identityRevision: Int, val memberName: String)

    fun approve(): JSONObject {
        val result = page.approveShownLocalContinuation(review, review.view().getString("digest"), source.teamGeneration, source.memberGeneration, source.identityRevision)
        val member = result.optJSONArray("members").objects().singleOrNull { it.optString("id") == source.work.optString("memberId") }
        check(result.optString("id") == source.team && member != null && member.optInt("generation", -1) == source.memberGeneration + 1
            && member.optJSONObject("ownerContinuationWorkItem")?.let { TeamWorkItems.sameIdentity(it, source.work) } == true) { "原分工是否已排队待核对，请只刷新原件，不重复提交" }
        return result
    }
    override fun close() = page.close()

    companion object {
        fun source(run: JSONObject, id: String): Source {
            require(run.optString("status") !in setOf("completed", "stopping", "cancelled")) { "这次团队工作已结束" }
            val plan = run.getJSONObject("workPlan")
            val item = plan.optJSONArray("items").objects().single { it.optString("workItemId") == id }
            val member = run.optJSONArray("members").objects().single { it.optString("id") == item.getString("memberId") }
            val child = item.getJSONObject("assignmentTask")
            require(!member.optBoolean("exited") && member.optString("runner", "model") == "model" && member.opt("device") == "phone"
                && !member.has("remoteProduction") && !item.has("remoteSelection") && !item.has("projectSelection")
                && !run.getJSONObject("productionScope").has("projectSelection")
                && child.optString("status") in setOf("paused","failed","interrupted") && child.optString("runId").isNotBlank()
                && child.optString("assignmentId") == item.getString("assignmentId") && child.optString("taskId") == item.getString("assignmentId")
                && child.optString("memberId") == member.getString("id")) { "请打开原任务核对停点；此入口只继续已明确停下的本机选定文件分工" }
            return Source(run.getString("id"), plan.getString("planId"), TeamWorkItems.exactIdentity(item), child.getString("runId"), child.getString("status"),
                run.getInt("generation"), member.getInt("generation"), member.optInt("identityRevision",1), member.optString("name").ifBlank { member.getString("id") })
        }

        fun prepare(source: Source, page: TaskStoreTeamWriteHost.WorkPlanPage): PocketWebTeamWorkReturn {
            val item = source.work
            val exact = page.reviewLocalContinuation(item.getString("workItemId"), item.getInt("itemRevision"), item.getInt("ownerGeneration"))
            val facts = exact.view()
            check(facts.optString("kind") == "continue" && facts.opt("budgetChanged") == false && facts.opt("localDraftOnly") == true && facts.optString("stoppedStatus") == source.stoppedStatus && facts.optString("planId") == source.plan
                && facts.optString("runId") == source.childRun && TeamWorkItems.sameIdentity(facts.getJSONObject("workItem"), item)) { "原分工在读取后已改变，请刷新，没有开始工作" }
            val files = facts.getJSONArray("files").objects().map { it.getString("path") }
            check(files.isNotEmpty() && files.size == facts.getJSONArray("files").length()) { "原文件范围不完整" }
            val budget = facts.getJSONObject("parentBudget")
            val shown = JSONObject().put("runId",source.team).put("workItemId",item.getString("workItemId")).put("assignmentId",item.getString("assignmentId"))
                .put("title",facts.getString("title")).put("memberName",source.memberName).put("files", JSONArray(files))
                .put("remainingModelRequests",budget.getInt("remainingModelRequests")).put("remainingToolOperations",budget.getInt("remainingToolOperations"))
                .put("draftOperationsRemaining",facts.getInt("draftOperationsRemaining"))
            return PocketWebTeamWorkReturn(page,exact,source,shown)
        }
    }
}
