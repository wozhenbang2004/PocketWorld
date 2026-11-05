package dev.pocketengine.ui

import org.json.JSONObject

internal fun mergeTeamSnapshot(previous: JSONObject?, incoming: JSONObject): JSONObject {
    if (previous != null) {
        val nextGeneration = incoming.optInt("generation")
        val previousGeneration = previous.optInt("generation")
        if (nextGeneration < previousGeneration) return previous
        if (nextGeneration == previousGeneration) {
            if (previous.has("revision") && incoming.has("revision")) {
                if (incoming.optLong("revision") < previous.optLong("revision")) return previous
            } else if (incoming.optLong("updatedAt", incoming.optLong("createdAt")) < previous.optLong("updatedAt", previous.optLong("createdAt"))) return previous
        }
    }
    return JSONObject(incoming.toString())
}

internal fun isTeamRunResult(command: String, value: JSONObject): Boolean = command in setOf("start", "get", "pause", "resume", "feedback", "cancel", "continueMessages", "reviseAssignment", "continueOutcome", "sendMessage", "configureMember") && value.optString("id").isNotBlank() && value.has("goal") && value.optJSONArray("members") != null
/** Receipts are native UI acknowledgements, not task state or model/executor result bodies. */

internal fun teamOwnerReceipts(state: PocketUiState, runId: String? = null, teamId: String? = null): Map<String, JSONObject> = state.teamOwnerReceipts.filterValues {
    (runId != null && it.optString("runId") == runId) || (teamId != null && it.optString("command") == "savePlan" && it.optString("teamId") == teamId)
}

internal fun teamRunStatus(status: String): String = when (status) { "completed" -> "本轮已有产出，待核对"; "partial" -> "部分分工未完成，待核对"; "awaiting_review" -> "真实文件成果等待审查"; else -> taskStatus(status) }

internal fun teamRunReferences(task: UiTask): List<String> {
    val result = linkedSetOf<String>()
    teamChildLocator(task)?.let { result += it.getString("teamRunId") }
    if (task.raw.optString("feature") == "teams" || task.raw.optString("kind") == "teams") result += task.raw.optString("externalId").ifBlank { task.id }
    task.raw.optJSONArray("relatedTasks").objects().filter { it.optString("kind", it.optString("feature")) == "teams" }.forEach { result += it.optString("runId", it.optString("taskId")) }
    return result.filter { it.isNotBlank() }
}

internal fun teamRunnerLabel(id: String): String = when (id) { "model" -> "模型服务"; "codex_app_server" -> "Codex app-server"; "claude" -> "Claude Code"; "pi" -> "Pi"; "phone_agent" -> "手机操作成员"; else -> id }

internal fun teamMemberStatus(id: String): String = when (id) { "model", "running" -> "工作中"; "queued" -> "等待本轮"; "paused" -> "已暂停"; "completed" -> "本轮完成"; "failed" -> "未完成"; "unsupported" -> "执行器未接入"; "blocked" -> "环境未就绪"; "blocked_unknown" -> "结果待核对"; "waiting_dependency" -> "等待前置分工"; "blocked_dependency" -> "前置未完成，已阻断"; "partial" -> "部分分工未完成"; "awaiting_approval" -> "等待本机授权"; else -> taskStatus(id) }
