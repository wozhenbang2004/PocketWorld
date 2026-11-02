package dev.pocketengine.ui

import org.json.JSONObject

/** Navigation projection only: no run, member generation, approval or execution authority. */
internal fun isTeamChild(task: UiTask): Boolean = task.raw.optJSONObject("origin")?.optString("kind") in setOf("team_assignment", "team_integration", "team_review", "team_research")

internal fun teamChildLocator(task: UiTask): JSONObject? {
    if (!isTeamChild(task) || task.raw.optString("actorId") != "owner" || task.raw.optString("spaceId") != "private") return null
    val origin = task.raw.optJSONObject("origin") ?: return null
    val research = origin.optString("kind") == "team_research"
    val identity = task.raw.optJSONObject(if (research) "teamResearch" else "teamAssignment") ?: return null
    val childKey = if (research) "researchTaskId" else "assignmentId"
    val team = origin.optString("teamRunId")
    val parent = task.raw.optString("parentTaskId")
    if (team.isBlank() || parent.isBlank() || parent == task.id || team != identity.optString("teamRunId") || task.id != origin.optString(childKey) || task.id != identity.optString(childKey)) return null
    val member = identity.optString("memberId")
    if (member.isBlank() || origin.has("memberId") && member != origin.optString("memberId")) return null
    val locator = JSONObject().put("teamRunId", team).put("parentTaskId", parent).put("assignmentId", task.id).put("memberId", member).put("childKind", origin.optString("kind"))
    identity.optString("workItemId").takeIf { it.isNotBlank() }?.let { locator.put("workItemId", it) }
    return locator
}
