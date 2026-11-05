package dev.pocketengine.ui

import dev.pocketengine.core.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject

/** Display-only projection of original TaskStore records. Never settles or replays work. */
internal object TaskExecutionPresentation {
    enum class Tone { RUNNING, WAITING, UNCERTAIN, ERROR, RETURNED, STOPPED, NEUTRAL }
    enum class Target { NONE, PROCESS, APPROVAL, RECONCILIATION, RESULT, FEEDBACK }
    data class Current(val title: String, val detail: String, val tone: Tone, val target: Target = Target.NONE, val action: String = "")
    data class Record(val key: String, val title: String, val label: String, val tone: Tone, val at: Long, val runId: String, val arguments: String, val receipt: String, val limited: Boolean, val bodyBytes: Long, val projectExecutionId: String? = null, val outputNotes: List<String> = emptyList(), val jobTaskId: String? = null, val jobOperationId: String? = null, val jobEpoch: Int? = null, val relatedTaskId: String? = null)

    /** Browser activity reads a full chronological journal; other paged views keep their bounds. */
    data class RecentOperations(val records: List<Record>, val omittedEarlierCount: Int) {
        val expandLabel: String get() = if (omittedEarlierCount > 0) "最近 ${records.size} 条" else "全部 ${records.size} 条"
        fun summary(expanded: Boolean): String? {
            if (omittedEarlierCount > 0) return if (expanded)
                "仅显示最近 ${records.size} 条原调用；另有 $omittedEarlierCount 条较早调用未在此处展示。"
            else "显示最近 ${minOf(records.size, 3)} 条原调用；展开可查看最近 ${records.size} 条。另有 $omittedEarlierCount 条较早调用未在此处展示。"
            return if (!expanded && records.size > 3) "显示最近 3 条原调用；展开可回查更早记录。" else null
        }
    }

    /** Select the tail before projection, without changing the journal or other shared views. */
    fun recentOperations(operations: JSONArray?): RecentOperations {
        val count = operations?.length() ?: 0
        val start = maxOf(0, count - 40)
        val records = (start until count).mapNotNull { operations?.optJSONObject(it) }.map(::operation)
        return RecentOperations(records, start)
    }

    /** A read locator only. The native factory rechecks the original SQL plan, receipt and owner. */
    fun projectExecutionObservationId(row: JSONObject): String? = row.optString("operationId").takeIf {
        row.optString("tool") == "project_execute" && it.matches(Regex("[A-Za-z0-9_-]{1,80}"))
    }

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
        val development = task.optJSONObject("developmentSession")
        if (status == "running" && development?.optString("phase") == "owned_running" && development.optString("runId") == task.optString("runId") && development.optInt("epoch") == task.optInt("coordinatorEpoch"))
            return Current("开发服务保留中", "模型本轮已结束；可继续试用、编辑或补充要求。原授权期限、停止与撤销仍有效，不会自动重启服务。", Tone.WAITING, Target.PROCESS, "查看服务与期限")
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
        if (section == "jobs") job(row)
        else if (section == "events") row.optJSONObject("event")?.let { event ->
            val tool = event.optString("tool")
            val projectLog = tool in setOf("project_execute", "project_status", "project_stop")
            val notes = if (event.optString("type") == "tool" && (projectLog || tool == "ssh_exec")) outputNotes(event.optJSONObject("receipt"), projectLog) else emptyList()
            Record("event:" + row.optLong("position"), eventTitle(event.optString("type")), event.optString("status").let(::eventLabel), eventTone(event.optString("status")), event.optLong("at", event.optLong("timestamp")), event.optString("runId"), "", event.toString(), false, row.optLong("bodyBytes"), outputNotes = notes)
        } ?: if (row.optString("status") == "read_limited") Record("event:" + row.optLong("position"), eventTitle(row.optString("type")), "原正文未在本页展开", Tone.NEUTRAL, 0, "", "", "", true, row.optLong("bodyBytes")) else null
        else operation(row)
    }

    private fun job(row: JSONObject): Record? {
        fun identity(key: String) = (row.opt(key) as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,120}")) }
        if (row.opt("observingOnly") != true || row.opt("executionAuthorized") != false || row.optString("statusSource") != "original_operation_journal") return null
        val task = identity("taskId") ?: return null
        val operation = identity("operationId") ?: return null
        val run = identity("runId") ?: return null
        val epoch = (row.opt("epoch") as? Int)?.takeIf { it >= 0 }
        val kind = row.optString("entryKind")
        val facts = row.optJSONObject("reportedFacts") ?: JSONObject()
        val state = row.optString("state")
        val originalStatus = facts.optString("originalReceiptStatus")
        val limited = row.optString("status") in setOf("read_limited", "source_unreadable")
        val unknown = limited || state.contains("unknown") || originalStatus == "unknown"
        val label = when {
            unknown -> "原结果仍待核对 · 未发起重试"
            kind == "approval_intent" -> "审批申请记录 · 请到原任务核对是否仍待处理"
            kind in setOf("delegation_intent", "team_start_intent") -> "委派申请记录 · 不代表子执行已经开始"
            facts.opt("resourcesReleased") == true -> "原回执已确认资源释放"
            originalStatus in setOf("failed", "rejected") -> "原回执：执行未完成"
            originalStatus in setOf("cancelled", "canceled", "stopped") -> "原回执：已停止 · 资源释放须另行核对"
            originalStatus == "completed" -> "原回执：已返回结果 · 目标仍需核对"
            state == "project_stopping" -> "原记录：正在收尾 · 释放尚未确认"
            state in setOf("project_running", "remote_job_running") || originalStatus in setOf("running", "active") -> "原记录：运行中 · 当前状态未查询"
            else -> "已登记原调用 · 当前状态请回原任务核对"
        }
        val title = when (row.optString("domain")) { "project" -> if (kind == "runtime_preparation_record") "手机环境准备" else "本机项目执行"; "remote" -> "远程执行"; "media" -> "媒体制作"; "child_work" -> "团队子工作"; else -> "原作业" }
        val tone = if (unknown) Tone.UNCERTAIN else if (kind == "approval_intent") Tone.WAITING else Tone.NEUTRAL
        val log = operation.takeIf { !limited && epoch != null && row.opt("projectLogCandidate") == true }
        val notes = listOf("显示原调用记录，未自动查询外部服务；查看记录不会恢复旧授权。") +
            if ((facts.opt("reportedPendingApprovalCount") as? Int)?.let { it > 0 } == true) listOf("原回执有待审批项，请到原任务核对当前请求。") else emptyList()
        return Record("job:" + row.optString("jobKey"), title, label, tone, row.optLong("createdAt"), run, "", facts.toString(), limited, row.optLong("bodyBytes"), log, notes, task, operation, epoch, identity("relatedTaskId"))
    }

    private fun operation(row: JSONObject): Record {
        val receipt = row.optJSONObject("receipt")
        val state = row.optString("state")
        val outcome = ToolOutcome.operationOutcome(row)
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
            outcome?.requiresUser() == true -> { tone = Tone.WAITING; label = "需要你处理 · 已发生的操作仍保留" }
            outcome?.correctable() == true -> { tone = Tone.ERROR; label = "请求可调整 · 不会自动重放原操作" }
            receipt?.optBoolean("terminal", true) == false -> { tone = Tone.WAITING; label = "已返回部分结果" }
            receipt != null -> { tone = Tone.RETURNED; label = "已返回 · 目标仍需核对，结果可展开查看" }
            else -> { tone = Tone.NEUTRAL; label = "原状态：" + excerpt(state.ifBlank { "未报告" }, 80) }
        }
        return Record("operation:" + row.optString("operationId", row.optString("position")), excerpt(row.optString("tool").ifBlank { "原工具调用" }, 120), label, tone, row.optLong("createdAt"), row.optString("runId"), row.optJSONObject("arguments")?.toString().orEmpty(), receipt?.toString().orEmpty(), row.optString("status") == "read_limited", row.optLong("bodyBytes"), projectExecutionObservationId(row), outputNotes(receipt, row.optString("tool") in setOf("project_execute", "project_status", "project_stop")))
    }

    /** Read-only output facts, independent of process success or permission to read a source. */
    fun outputNotes(receipt: JSONObject?, projectLog: Boolean = false): List<String> {
        if (receipt == null) return emptyList()
        val notes = mutableListOf<String>()
        val supplied = receipt.optJSONObject("outputCapture") ?: receipt.optJSONObject("receipt")?.optJSONObject("outputCapture")
        val capture = supplied?.let { runCatching { dev.pocketengine.features.memory.ToolOutputCapture.checkedMetadata(it) }.getOrNull() }
        if (capture != null) {
            val refs = objects(capture.optJSONArray("outputRefs"))
            val bytes = refs.sumOf { it.getJSONObject("retainedRange").getLong("utf8Bytes") }
            notes += when {
                refs.isEmpty() -> "本回执没有可核对的输出留存引用，不能据此保证原件完整。"
                refs.all { it.opt("retainedNativeDataComplete") == true } -> "本次允许留存的返回数据已完整保存，共 $bytes 字节；读取仍需核对原任务与来源。"
                else -> "本次允许留存的返回数据仅部分保存，共 $bytes 字节；摘要不能代替未留存内容。"
            }
            if (refs.any { it.opt("upstreamTruncated") == true }) notes += "上游返回内容已截断，缺失部分不在留存件中。"
            else if (refs.any { it.isNull("upstreamTruncated") }) notes += "上游内容是否完整尚未确认。"
        } else if (supplied != null) notes += "输出留存信息无法核对，未据此宣称原件完整。"
        val omissions = receipt.optJSONObject("displayOmissions")
        var counted = false
        var byteBudgetConfirmed = true
        for ((field, label) in listOf("stdout" to "标准输出", "stderr" to "错误输出", "content" to "文本", "observation.stdout" to "观察中的标准输出", "observation.stderr" to "观察中的错误输出")) {
            val fact = omissions?.optJSONObject(field) ?: continue
            fun count(key: String): Long? = (fact.opt(key) as? Number)?.takeIf { it is Int || it is Long }?.toLong()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            val source = count("sourceUtf8Bytes") ?: continue
            val head = count("headUtf8Bytes") ?: continue
            val tail = count("tailUtf8Bytes") ?: continue
            val omitted = count("omittedUtf8Bytes") ?: continue
            if (fact.optString("sourceByteScope") != "returned_text_utf8" || fact.opt("nativeOutputChanged") != false || source != head + tail + omitted || omitted == 0L) continue
            notes += "$label：返回文本的展示中省略 $omitted 字节，保留开头 $head 字节和末尾 $tail 字节。"
            counted = true
            byteBudgetConfirmed = byteBudgetConfirmed && fact.optString("budgetBasis") == "utf8_json_bytes" && fact.opt("tokenEstimateUsed") == false
        }
        if (receipt.opt("displayTruncated") == true && !counted) notes += "当前展示有省略，准确遗漏量未记录。"
        if (counted && byteBudgetConfirmed) notes += "摘录使用保守的字节预算；没有按模型 token 数估算。"
        if (projectLog) {
            val log = receipt.optJSONObject("executionReceipt") ?: receipt.optJSONObject("observation") ?: receipt
            if (log.opt("logIncompleteTail") == true) notes += "日志末条未写完整，仅可读取此前完整记录；这不能证明已达到存储上限。"
            if (log.opt("outputTruncated") == true) notes += "原日志内容不完整，未留存部分不能由摘要恢复。"
            if (log.opt("summaryOutputTruncated") == true) notes += "原回执仅含日志摘要，可展开这次执行的日志，分段读取已留存记录。"
        }
        return notes
    }

    private fun uncertain(operation: JSONObject): Boolean {
        return ToolOutcome.operationOutcome(operation)?.requiresReconciliation() == true
    }
    private fun eventTitle(type: String): String = when (type) { "model" -> "模型决定下一步"; "tool" -> "工具调用"; "observation" -> "观察现场"; "verification", "user_verification" -> "结果核对"; "run" -> "执行状态"; "result" -> "任务结果"; "owner_input" -> "收到主人补充"; "capability_changed" -> "能力状态更新"; "context_budget" -> "上下文整理"; "inference_receipt" -> "模型调用用量"; else -> excerpt(type.ifBlank { "原步骤记录" }, 120) }
    private fun eventLabel(status: String): String = when (status) { "running" -> "执行记录"; "completed" -> "已有该步骤记录"; "failed" -> "该步骤返回错误"; "unknown" -> "结果未核实"; "awaiting_user" -> "需要你处理"; "paused" -> "已暂停"; "cancelled", "canceled", "stopped" -> "已停止"; else -> excerpt(status.ifBlank { "原记录" }, 80) }
    private fun eventTone(status: String): Tone = when (status) { "running" -> Tone.RUNNING; "failed" -> Tone.ERROR; "unknown" -> Tone.UNCERTAIN; "awaiting_user", "paused" -> Tone.WAITING; "completed" -> Tone.RETURNED; "cancelled", "canceled", "stopped" -> Tone.STOPPED; else -> Tone.NEUTRAL }
    private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until minOf(array.length(), 40)).mapNotNull { array.optJSONObject(it) }
    fun excerpt(value: String, maxPoints: Int): String = if (value.codePointCount(0, value.length) <= maxPoints) value else value.substring(0, value.offsetByCodePoints(0, maxPoints)) + "\n…此处仅展示部分，原记录未改写。"
}
