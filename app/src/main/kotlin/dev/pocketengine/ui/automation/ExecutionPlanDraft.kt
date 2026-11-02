package dev.pocketengine.ui

import dev.pocketengine.R

import dev.pocketengine.features.automation.PlanArtifactVerifier
import dev.pocketengine.features.automation.PlanBusinessWait
import dev.pocketengine.features.automation.PlanRemoteWait
import org.json.JSONArray
import org.json.JSONObject

internal data class ExecutionDependencyDraft(val step: Int, val outcome: String = "completed")

internal data class ExecutionStepDraft(
    val goal: String = "", val executor: String = "phone", val projectId: String = "",
    val path: String = "", val heading: String = "", val minimumBytes: String = "16",
    val dependencies: List<ExecutionDependencyDraft> = emptyList(),
    val remoteWait: JSONObject? = null, val businessWait: JSONObject? = null
)

/** Exact earlier-step references only. An unsupported or missing edge is never silently dropped. */
internal fun executionStepDraft(plan: JSONObject, step: JSONObject): ExecutionStepDraft {
    val rows = plan.getJSONArray("steps")
    val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == step.getString("id") }
        ?: throw IllegalArgumentException("此步骤已不在当前目标中，请刷新后编辑")
    val dependencies = step.optJSONArray("dependencies") ?: throw IllegalArgumentException("此步骤的前置条件尚不可核实，请刷新后编辑")
    val refs = (0 until dependencies.length()).map { at ->
        val ref = dependencies.getJSONObject(at)
        val earlier = (0 until index).firstOrNull { rows.getJSONObject(it).getString("id") == ref.getString("stepId") }
            ?: throw IllegalArgumentException("某项前置不属于当前目标的更早步骤，未删除这项条件")
        ExecutionDependencyDraft(earlier, ref.getString("outcome"))
    }
    val verification = step.optJSONObject("verification")
    val draft = ExecutionStepDraft(step.optString("goal"), step.optString("executor", "phone"), verification?.optString("projectId").orEmpty(),
        verification?.optString("path").orEmpty(), verification?.optString("requiredHeading").orEmpty(), (verification?.optLong("minBytes", 16) ?: 16).toString(), refs,
        step.optJSONObject("remoteWait")?.let { PlanRemoteWait.standard(it) }, step.optJSONObject("businessWait")?.let { PlanBusinessWait.standard(it) })
    executionStepDefinition(draft, index, step.getLong("dueAt"))
    return draft
}

private fun executionStepDefinition(draft: ExecutionStepDraft, index: Int, due: Long): JSONObject {
    require(draft.goal.trim().isNotEmpty() && draft.goal.trim().length <= 4000) { "请补全第 ${index + 1} 步目标" }
    require(draft.executor in setOf("phone", "workspace")) { "步骤执行位置无效" }
    require(draft.dependencies.size <= 50 && draft.dependencies.map { it.step }.distinct().size == draft.dependencies.size) { "同一步骤不能有重复的前置条件" }
    val dependencies = JSONArray()
    draft.dependencies.sortedBy { it.step }.forEach { ref ->
        require(ref.step in 0 until index) { "前置只能来自同一目标中的更早步骤" }
        require(ref.outcome in setOf("completed", "failed", "cancelled", "settled")) { "请选择可核实的前置结果" }
        dependencies.put(JSONObject().put("step", ref.step).put("outcome", ref.outcome))
    }
    val row = JSONObject().put("title", draft.goal.trim().lineSequence().first().take(200)).put("goal", draft.goal.trim()).put("dueAt", due)
        .put("executor", draft.executor).put("dependsOn", dependencies)
    if (draft.executor == "workspace") {
        val minimum = draft.minimumBytes.toLongOrNull(); require(minimum != null && minimum in 1..1_000_000) { "文件最小大小应为 1–1000000 字节" }
        row.put("verification", PlanArtifactVerifier.standard(JSONObject().put("kind", "workspace_artifact").put("projectId", draft.projectId)
            .put("path", draft.path.trim()).put("requiredHeading", draft.heading.trim()).put("minBytes", minimum)))
    } else row.put("verification", JSONObject().put("kind", "owner_review"))
    draft.remoteWait?.let { row.put("remoteWait", PlanRemoteWait.standard(it)) }
    draft.businessWait?.let { row.put("businessWait", PlanBusinessWait.standard(it)) }
    return row
}

/** Changing a goal must not reset its time, expiry, dependency set or executed history. */
internal fun executionStepPatch(plan: JSONObject, original: JSONObject, draft: ExecutionStepDraft, minutes: String, timingEdited: Boolean, now: Long): JSONObject {
    val rows = plan.getJSONArray("steps")
    val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == original.getString("id") }
        ?: throw IllegalArgumentException("此步骤已变化，请刷新后编辑")
    val previous = executionStepDefinition(executionStepDraft(plan, original), index, original.getLong("dueAt"))
    val delay = minutes.toLongOrNull(); require(delay != null && delay in 0..525600) { "开始时间应为 0–525600 分钟之后" }
    val next = executionStepDefinition(draft, index, if (timingEdited) Math.addExact(now, delay * 60_000) else original.getLong("dueAt"))
    val patch = JSONObject()
    for (key in listOf("title", "goal", "executor", "verification", "dependsOn", "dueAt", "remoteWait", "businessWait"))
        if (next.opt(key)?.toString() != previous.opt(key)?.toString()) patch.put(key, next.opt(key) ?: JSONObject.NULL)
    return patch
}

/** A native editor drafts a definition; saving it cannot approve, start, or prove a result. */
internal fun executionPlanDefinition(goal: String, steps: List<ExecutionStepDraft>, minutes: String, now: Long): JSONObject {
    require(goal.trim().isNotEmpty() && goal.trim().length <= 4000) { "请填写 1–4000 字的完整目标" }
    require(steps.size in 1..50) { "目标需要 1–50 个步骤" }
    val delay = minutes.toLongOrNull(); require(delay != null && delay in 0..525600) { "开始时间应为 0–525600 分钟之后" }
    val due = Math.addExact(now, delay * 60_000); val rows = JSONArray()
    steps.forEachIndexed { index, draft ->
        rows.put(executionStepDefinition(draft, index, due))
    }
    return JSONObject().put("kind", "explicit_user").put("title", goal.trim().lineSequence().first().take(200)).put("goal", goal.trim()).put("steps", rows)
}

internal fun executionPlanStateLabel(value: String): String = when (value) {
    "completed" -> taskFlowText(R.string.task_flow_helper_ea71b3101c7c, "目标已按标准完成"); "finished_with_failures" -> taskFlowText(R.string.task_flow_helper_96dd93b9b380, "本轮结束，有步骤失败")
    "paused" -> taskFlowText(R.string.task_flow_helper_0297c9ec1ac7, "计划已暂停"); "cancelled" -> taskFlowText(R.string.task_flow_helper_393d64e96681, "计划已取消"); "review_required" -> taskFlowText(R.string.task_flow_helper_8de360349744, "修改后待审阅")
    "waiting_for_owner_start" -> taskFlowText(R.string.task_flow_helper_111cab760871, "等你开始"); "waiting_for_original_task" -> taskFlowText(R.string.task_flow_helper_a6847acc84d9, "原任务正在处理或等待核对")
    "scheduled" -> taskFlowText(R.string.task_flow_helper_d811bcc6a56f, "等待计划时间"); "ready_for_host_check" -> taskFlowText(R.string.task_flow_helper_47f1a559e503, "等待运行条件核对"); "blocked" -> taskFlowText(R.string.task_flow_helper_a4f47f5921cb, "需要处理阻塞")
    else -> taskFlowText(R.string.task_flow_helper_2d37dc8480bc, "尚未授权执行")
}
