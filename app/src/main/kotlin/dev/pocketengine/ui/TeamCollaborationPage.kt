package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

internal data class TeamCollaborationStamp(val runId: String, val generation: Int, val revision: Long)

internal fun teamCollaborationStamp(run: JSONObject) = TeamCollaborationStamp(run.getString("id"), run.getInt("generation"), run.getLong("revision"))

internal fun reviewedTeamCollaborationAction(run: JSONObject, stamp: TeamCollaborationStamp, command: String, memberId: String?, text: String = ""): JSONObject {
    require(teamCollaborationStamp(run) == stamp) { "团队状态已改变，请刷新后操作" }
    require(command in setOf("pause", "resume", "feedback", "continueMessages")) { "操作未开放" }
    require(run.optString("status") != "cancelled") { "团队任务已停止" }
    val input = JSONObject().put("runId", stamp.runId).put("expectedGeneration", stamp.generation).put("expectedRevision", stamp.revision)
    if (memberId != null) {
        val member = teamRows(run.optJSONArray("members")).find { it.optString("id") == memberId } ?: error("成员不属于当前团队任务")
        input.put("memberId", memberId).put("expectedMemberGeneration", member.getInt("generation"))
    }
    if (command == "continueMessages") require(memberId != null) { "请选择有未读消息的成员" }
    if (command == "feedback") { require(text.isNotBlank() && text.length <= 4000); input.put("text", text.trim()) }
    return input
}

internal fun teamCollaborationResultMatches(result: JSONObject?, requestId: String, command: String, runId: String): Boolean =
    result != null && result.optString("requestId") == requestId && result.optString("command") == command && result.optString("runId") == runId && result.opt("ok") is Boolean

internal data class TeamAssignmentDraft(val memberId: String, val assignment: String, val acceptance: String, val dependsOn: Set<String>)

internal fun reviewedTeamPlanPayload(current: JSONObject, reviewedDefinition: String, drafts: List<TeamAssignmentDraft>): JSONObject {
    require(current.toString() == reviewedDefinition) { "团队配置已改变，请重新载入" }
    val ids = teamRows(current.optJSONArray("members")).map { it.getString("id") }.toSet()
    require(drafts.size == ids.size && drafts.map { it.memberId }.toSet() == ids)
    val assignments = JSONArray()
    drafts.forEach { draft ->
        require(draft.assignment.isNotBlank() && draft.assignment.length <= 2000 && draft.acceptance.length <= 1200)
        require(draft.memberId !in draft.dependsOn && ids.containsAll(draft.dependsOn))
        assignments.put(JSONObject().put("memberId", draft.memberId).put("assignment", draft.assignment.trim()).put("acceptance", draft.acceptance.trim()).put("dependsOn", JSONArray(draft.dependsOn.toList())))
    }
    return JSONObject().put("teamId", current.getString("id")).put("expectedDefinition", reviewedDefinition).put("assignments", assignments)
}

private fun teamRows(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

internal fun teamConversationRows(run: JSONObject): List<JSONObject> {
    val rows = teamRows(run.optJSONArray("transcript")).map { JSONObject(it.toString()) }.toMutableList()
    val assignments = if (run.optJSONObject("workPlan") != null) {
        teamRows(run.getJSONObject("workPlan").optJSONArray("items")).mapNotNull { item ->
            item.optJSONObject("assignmentTask")?.let { Triple(item.getString("memberId"), it, item.getString("workItemId")) }
        }
    } else teamRows(run.optJSONArray("members")).mapNotNull { member ->
        member.optJSONObject("assignmentTask")?.let { Triple(member.getString("id"), it, "") }
    }
    assignments.forEach { (memberId, assignment, workItemId) ->
        teamRows(assignment.optJSONArray("questions")).forEach { question ->
            rows += JSONObject(question.toString()).put("messageId", "question:" + question.getString("questionId"))
                .put("kind", "assignment_question").put("memberId", memberId)
                .put("assignmentId", assignment.getString("assignmentId")).put("content", question.optString("question"))
                .put("at", question.optLong("askedAt")).put("role", "assistant")
                .also { if (workItemId.isNotEmpty()) it.put("workItemId", workItemId) }
        }
    }
    return rows.distinctBy { it.getString("messageId") }.sortedWith(compareBy<JSONObject> { it.optLong("at") }.thenBy { it.getString("messageId") })
}
