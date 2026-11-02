package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

/** Display-only projection of an original bounded TaskStore window. Never settles or replays work. */
internal object TaskExecutionPresentation {
    enum class Tone { RUNNING, WAITING, UNCERTAIN, ERROR, RETURNED, STOPPED, NEUTRAL }
    enum class Target { NONE, PROCESS, APPROVAL, RECONCILIATION, RESULT, FEEDBACK }
    data class Current(val title: String, val detail: String, val tone: Tone, val target: Target = Target.NONE, val action: String = "")
    data class Record(val key: String, val title: String, val label: String, val tone: Tone, val at: Long, val runId: String, val arguments: String, val receipt: String, val limited: Boolean, val bodyBytes: Long)

    fun current(task: JSONObject, now: Long): Current {
        val status = task.optString("status")
        val evidence = task.optJSONObject("evidence")
        val operations = objects(task.optJSONArray("operations"))
        val uncertain = operations.any(::uncertain)
        // A native false is meaningful: owned running/stopping rows are unresolved, not unknown.
        val unconfirmed = if (task.opt("historyHasUnconfirmedOperations") is Boolean) task.getBoolean("historyHasUnconfirmedOperations")
            else task.optBoolean("hasUnresolvedOperations")
        val active = status in setOf("preparing", "running", "verifying", "pausing", "stopping", "cancelling")
        val knownInFlight = operations.any { it.optString("runId") == task.optString("runId") && it.optString("state") == "prepared" }
        if (uncertain || status in setOf("reconciling", "reconciliation_required", "unknown") || evidence?.optBoolean("requiresReconciliation") == true)
            return Current("有操作结果待核对", "操作可能已经发生，请先核对结果，避免重复提交。", Tone.UNCERTAIN, Target.RECONCILIATION, "核对操作结果")
        if (evidence?.optBoolean("projectServiceDrainPending") == true)
            return Current("服务收尾仍待确认", "停止请求不等于进程结束或资源已释放。先查看原服务回执，不要重复启动。", Tone.WAITING, Target.PROCESS, "查看收尾记录")
        val approvals = objects(task.optJSONArray("approvals")).filter { it.optString("state", it.optString("status")) == "pending" && it.optLong("expiresAt", Long.MAX_VALUE) > now }
        if (approvals.isNotEmpty() || status in setOf("waiting_approval", "awaiting_approval", "waiting_permission", "awaiting_permission"))
            return Current("需要你的确认", "请核对操作对象与影响范围，再决定是否允许。", Tone.WAITING, Target.APPROVAL, "查看并确认")
        if (unconfirmed && !(active && knownInFlight))
            return Current("有操作结果待核对", "部分操作的结果尚未确认，核对前不会自动重试。", Tone.UNCERTAIN, Target.RECONCILIATION, "核对操作结果")
        if (task.optBoolean("cancelRequested") && active || status in setOf("stopping", "cancelling"))
            return Current("正在停止，等待确认", "已请求停止后续派发。最后记录仍可查看，尚不能据此确认外部进程已经结束。", Tone.WAITING, Target.PROCESS, "查看最后记录")
        if (status in setOf("awaiting_user", "waiting_user", "awaiting_answer", "waiting_answer"))
            return Current("需要你的下一步", excerpt(task.optString("summary"), 360).ifBlank { "查看原问题或补充要求，再由原任务决定是否继续。" }, Tone.WAITING, Target.FEEDBACK, "查看问题与补充")
        if (status == "needs_confirmation")
            return Current("已有结果，等待你确认", "先查看实际成果和未完成项；工具返回或文件已生成，不等于目标已验收。", Tone.WAITING, Target.RESULT, "核对已有成果")
        if (status == "failed")
            return Current("这次执行未完成", excerpt(task.optString("summary"), 360).ifBlank { "已完成的步骤与成果仍保留。先查看原失败记录，再从原任务补充或继续。" }, Tone.ERROR, Target.PROCESS, "查看失败记录")
        if (status in setOf("paused", "interrupted"))
            return Current(if (status == "paused") "已暂停" else "执行已中断", "保留原目标与已有结果。继续前需重新核对当前状态，历史工具调用不会自动重放。", Tone.WAITING, Target.FEEDBACK, "核对后继续原任务")
        if (status in setOf("cancelled", "canceled", "stopped"))
            return Current("任务已停止", "已有结果和原调用记录仍可查看。此状态本身不证明远端服务已终止。", Tone.STOPPED, Target.PROCESS, "查看停止前记录")
        if (status == "completed")
            return Current("任务已完成", "成果与执行记录已保留，可随时回看。", Tone.RETURNED, Target.RESULT, "查看成果")
        if (status == "queued") return Current("等待执行", "任务已加入队列，开始后会显示执行动态。", Tone.WAITING)
        val latest = objects(task.optJSONArray("events")).lastOrNull { it.optString("type") in setOf("run", "model", "tool", "observation", "verification", "owner_input", "capability_changed", "context_budget") }
        if (active) {
            val description = latest?.takeIf { it.optString("status") == "running" }?.optString("message")?.let { excerpt(it, 240) }
            val title = when {
                status == "pausing" -> "正在暂停，等待确认"
                status == "preparing" -> "准备执行"
                status == "verifying" -> "正在核对实际结果"
                latest?.optString("type") == "model" -> "正在决定下一步"
                latest?.optString("type") == "tool" && latest.optString("status") == "running" -> "等待工具返回"
                else -> "正在执行"
            }
            return Current(title, description?.takeIf { it.isNotBlank() } ?: "正在处理你的目标，最新操作会显示在下方。", Tone.RUNNING)
        }
        return Current("执行状态待核对", "暂时无法确认当前状态，请查看执行记录。", Tone.NEUTRAL, Target.PROCESS, "查看原记录")
    }

    fun records(page: JSONObject, section: String): List<Record> = objects(page.optJSONArray("entries")).mapNotNull { row ->
        if (section == "events") row.optJSONObject("event")?.let { event ->
            Record("event:" + row.optLong("position"), eventTitle(event.optString("type")), event.optString("status").let(::eventLabel), eventTone(event.optString("status")), event.optLong("at", event.optLong("timestamp")), event.optString("runId"), "", event.toString(), false, row.optLong("bodyBytes"))
        } ?: if (row.optString("status") == "read_limited") Record("event:" + row.optLong("position"), eventTitle(row.optString("type")), "原正文未在本页展开", Tone.NEUTRAL, 0, "", "", "", true, row.optLong("bodyBytes")) else null
        else operation(row)
    }

    private fun operation(row: JSONObject): Record {
        val receipt = row.optJSONObject("receipt")
        val state = row.optString("state")
        val tone: Tone
        val label: String
        when {
            uncertain(row) -> { tone = Tone.UNCERTAIN; label = "结果待核对" }
            state == "project_running" -> { tone = Tone.RUNNING; label = "服务已有启动回执 · 尚无终态回执" }
            state == "project_stopping" -> { tone = Tone.WAITING; label = "服务正在收尾 · 尚无资源释放确认" }
            receipt?.optString("code") == "approval_required" -> { tone = Tone.WAITING; label = "等待本次操作授权" }
            state == "resolved" && receipt?.optString("source") == "owner_reconciliation" -> { tone = Tone.NEUTRAL; label = when (receipt.optString("outcome")) { "occurred" -> "主人已核对发生 · 非自动验证"; "not_occurred" -> "主人已核对未发生 · 不会自动重试"; else -> "主人已记录核对结论" } }
            state == "prepared" -> { tone = Tone.WAITING; label = "已登记，等待结果" }
            receipt?.optString("status") in setOf("cancelled", "canceled", "stopped") -> { tone = Tone.STOPPED; label = "调用已停止 · 详见原回执" }
            receipt != null && (receipt.has("ok") && !receipt.optBoolean("ok") || receipt.optString("status") == "failed" || receipt.opt("exitCode") is Number && receipt.optInt("exitCode") != 0) -> { tone = Tone.ERROR; label = "调用出错 · 请核对操作结果" }
            receipt?.optBoolean("terminal", true) == false -> { tone = Tone.WAITING; label = "已返回部分结果" }
            receipt != null -> { tone = Tone.RETURNED; label = "已返回 · 结果可展开查看" }
            else -> { tone = Tone.NEUTRAL; label = "原状态：" + excerpt(state.ifBlank { "未报告" }, 80) }
        }
        return Record("operation:" + row.optString("operationId", row.optString("position")), excerpt(row.optString("tool").ifBlank { "原工具调用" }, 120), label, tone, row.optLong("createdAt"), row.optString("runId"), row.optJSONObject("arguments")?.toString().orEmpty(), receipt?.toString().orEmpty(), row.optString("status") == "read_limited", row.optLong("bodyBytes"))
    }

    private fun uncertain(operation: JSONObject): Boolean {
        val receipt = operation.optJSONObject("receipt")
        return operation.optString("state") in setOf("unknown", "uncertain", "reconciliation_required", "pending_reconciliation", "remote_job_unknown", "remote_approval_unknown", "remote_question_unknown") || operation.optBoolean("requiresReconciliation") || receipt != null && (receipt.has("accepted") && receipt.isNull("accepted") || receipt.optString("status") == "unknown" || receipt.optString("code").contains("unknown") || receipt.optBoolean("requiresReconciliation"))
    }
    private fun eventTitle(type: String): String = when (type) { "model" -> "模型决定下一步"; "tool" -> "工具调用"; "observation" -> "观察现场"; "verification", "user_verification" -> "结果核对"; "run" -> "执行状态"; "result" -> "任务结果"; "owner_input" -> "收到主人补充"; "capability_changed" -> "能力状态更新"; "context_budget" -> "上下文整理"; "inference_receipt" -> "模型调用用量"; else -> excerpt(type.ifBlank { "原步骤记录" }, 120) }
    private fun eventLabel(status: String): String = when (status) { "running" -> "执行记录"; "completed" -> "已有该步骤记录"; "failed" -> "该步骤返回错误"; "unknown" -> "结果未核实"; "awaiting_user" -> "需要你处理"; "paused" -> "已暂停"; "cancelled", "canceled", "stopped" -> "已停止"; else -> excerpt(status.ifBlank { "原记录" }, 80) }
    private fun eventTone(status: String): Tone = when (status) { "running" -> Tone.RUNNING; "failed" -> Tone.ERROR; "unknown" -> Tone.UNCERTAIN; "awaiting_user", "paused" -> Tone.WAITING; "completed" -> Tone.RETURNED; "cancelled", "canceled", "stopped" -> Tone.STOPPED; else -> Tone.NEUTRAL }
    private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until minOf(array.length(), 40)).mapNotNull { array.optJSONObject(it) }
    fun excerpt(value: String, maxPoints: Int): String = if (value.codePointCount(0, value.length) <= maxPoints) value else value.substring(0, value.offsetByCodePoints(0, maxPoints)) + "\n…此处仅展示部分，原记录未改写。"
}
