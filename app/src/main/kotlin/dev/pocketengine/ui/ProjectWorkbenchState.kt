package dev.pocketengine.ui

import org.json.JSONObject

/** Selection and receipt checks for the native project leaf. The task ledger remains authoritative. */
internal object ProjectWorkbenchGuard {
    const val managedRuntime = "managed_process_v1"
    const val preparationGoal = "只准备这台手机开发工具。先检查当前项目的本机运行环境，再生成 prepare 方案；等待本次具体操作的主人审批后执行。只准备工具，不构建或运行项目。"
    const val commandGoal = "在这个已选项目内使用本App真实登记的命令工具：先inspect并读取实际README/源码；生成intent=command的精确executable、逐项argv、项目相对cwd和普通非秘密env，说明可能写文件/联网，等待本次批准后执行，真实非零错误回同task按SHA修复并验证。确需交互时才用terminal=pty、runMode=owned_service，并明确timeoutMillis、行列和inputByteLimit；启动批准包含同execution有限普通文字、canonical EOF与resize，不是秘密输入渠道。继续用原executionId读status/log，project_process_input的text按原样传入，行输入须明确换行；实际输入ACK不等于程序已处理。需要等我要求时可有界project_wait_input，不另开任务。修改源码前先停止并核对原session、进程组与资源释放；任务结束必须drain，partial/unknown不自动重放。这里只是目标草稿，不是运行授权。"
    const val pythonProjectGoal = "在这个已选Python项目中工作：先inspect并读取实际README、源码及已声明的requirements.txt（若有）；需要时只在本App准备工具。通过原项目dependencies方案创建独立.venv并按固定版本与哈希安装依赖，不全局pip。再执行真实test/build/run，实际非零错误回到同一任务读取并按SHA修复、重新验证；不要盲目重派pending/unknown。运行后核对真实日志；若项目实际生成dist/report.html/report.json，再打开原资源报告，不存在就如实说明，不伪造报告。我的后续修改继续留在这个项目任务；每个副作用仍按原审批。"

    const val devWatchGoal = "在这个已选Web热更新项目边运行边修改：读取README/真实文件，按原审批准备本机Node、npm ci、test/build。需要活页面时仅生成Node runMode=owned_service、devWatch=true，并明确liveEditPaths为要修改的既有src/main.js、src/budget.js、src/style.css；解释这次批准包含原run内这些源码后续执行影响，配置锁脚本环境和新文件不在内。启动原服务后用project_live_preview真实HTTP/WS核对供主人打开。保持原服务存活时，读取当前文件SHA并带原liveEditServiceExecutionId生成edit/CAS；查看真实Vite进程与页面错误回同task修复，不重启、不把保存当浏览器成功。需要等主人操作时用project_wait_input有界等候，未有新输入正常超时；可从原会话接收主人追加要求，不能把phone_request_user结束本轮冒成持久运行；原run最终结束必须stop/drain，未知硬停核原op，不放公网、不换grant。"
    const val retainedDevWatchGoal = devWatchGoal + " 本次我希望在模型答复后继续试用：请在devWatch方案明确retainAfterTurn=true、timeoutMillis=1800000，并让我审核期限与原热改文件；原任务授权更早到期时仍停止。不请求延长原任务预算，也不重启或跨进程恢复服务。"
    const val ownedServiceGoal = "调试这个已选Node/Web项目：先inspect并读取README与实际scripts；需要时按原审批安装声明依赖、真实test/build。只有项目确有长运行start服务，才生成runMode=owned_service的run方案并说明原启动批准包含停止同一个job。收到project_running后不要冒称业务完成或HTTP就绪；继续同一任务读取原executionId的status、分页log与源码，分析问题或生成修改方案。需要改源先project_stop原executionId，核对真实退出、进程组消失与资源释放，再用刚读SHA明确edit并验证、按需再启动。已有dist可用原多资源预览；不把stdout地址直接当已验证预览，不开放公网，不做HMR。原HostRun结束前必须停止排空服务，未知状态只核对不重派。"
    fun nodeProject(inspection: JSONObject?): Boolean = inspection?.optJSONArray("ecosystems").objects().any { it.optString("id") == "node" }

    fun pythonProject(inspection: JSONObject?): Boolean = inspection?.optJSONArray("ecosystems").objects().any { it.optString("id") == "python" }

    fun previewAction(preview: JSONObject): String = if (preview.optString("entryPath").endsWith("/report.html")) "打开运行报告" else "打开网页与交互预览"

    fun projectTasks(tasks: List<UiTask>, checkoutId: String, projectId: String): List<UiTask> = tasks
        .filter { isProjectTask(it.raw, checkoutId, projectId) }
        .sortedWith(compareByDescending<UiTask> { it.status in activeStatuses }.thenByDescending { it.updatedAt })

    fun selectedTask(tasks: List<UiTask>, selectedId: String?): UiTask? =
        if (selectedId == null) tasks.firstOrNull() else tasks.firstOrNull { it.id == selectedId }

    fun isProjectTask(task: JSONObject, checkoutId: String, projectId: String): Boolean {
        val context = task.optJSONObject("developmentContext") ?: return false
        return checkoutId.isNotBlank() && projectId.isNotBlank() && task.optString("id").isNotBlank() && task.optString("kind") == "assistant" &&
            task.optString("actorId") == "owner" && task.optString("spaceId") == "private" && task.optJSONObject("origin") == null &&
            context.optString("checkoutId") == checkoutId && context.optString("projectId") == projectId
    }

    fun ownerReceipt(response: JSONObject?, input: JSONObject?, requestId: String?, taskId: String? = null): JSONObject? {
        if (response == null || input == null || requestId.isNullOrBlank() || input.optString("_requestId") != requestId || input.optBoolean("_failed")) return null
        if (taskId != null && (input.optString("taskId") != taskId || response.optString("taskId") != taskId || response.opt("continued") != true)) return null
        return response.takeIf { it.optString("taskId").isNotBlank() }
    }

    fun failedRequest(input: JSONObject?, requestId: String?, taskId: String? = null): Boolean =
        !requestId.isNullOrBlank() && input != null && input.optString("_requestId") == requestId && input.opt("_failed") == true &&
            (taskId == null || input.optString("taskId") == taskId)

    fun unconfirmedSubmission(response: JSONObject?, input: JSONObject?, requestId: String?, client: String): JSONObject? {
        if (response == null || input == null || requestId.isNullOrBlank() || input.optString("_requestId") != requestId || input.optBoolean("_failed")) return null
        return response.takeIf { it.optString("status") == "unconfirmed" && it.optString("taskId").isBlank() && it.optString("clientMessageId") == client }
    }

    /** A cached bundle for the same checkout is insufficient: this exact task must have recorded it. */
    fun previewBelongsToTask(task: JSONObject, preview: JSONObject): Boolean {
        val checkout = preview.optString("checkoutId")
        val project = preview.optString("projectId")
        val identity = preview.optString("previewId")
        val source = preview.optString("sourceDigest")
        if (checkout.isBlank() || project.isBlank() || !identity.matches(Regex("[a-f0-9]{64}")) || !source.matches(Regex("[a-f0-9]{64}")) || !isProjectTask(task, checkout, project)) return false
        return task.optJSONArray("developmentPreviews").objects().any { row ->
            listOf("previewId", "sourceDigest", "projectId", "checkoutId").all { row.optString(it).isNotBlank() && row.optString(it) == preview.optString(it) }
        }
    }

    fun executionReceipt(task: UiTask, operation: JSONObject): JSONObject? {
        if (operation.optString("tool") != "project_execute") return null
        val receipt = operation.optJSONObject("receipt") ?: return null
        if (receipt.has("projectExecution")) return try {
            dev.pocketengine.features.local.project.ProjectDevelopment.recordedReceipt(task.raw, operation)
        } catch (_: Exception) { null }
        val binding = receipt.optJSONObject("binding") ?: return null
        val context = task.raw.optJSONObject("developmentContext") ?: return null
        if (!isProjectTask(task.raw, context.optString("checkoutId"), context.optString("projectId")) || listOf("taskId", "runId", "operationId").any { binding.optString(it).isBlank() } ||
            !receipt.optString("sourceDigest").matches(Regex("[a-f0-9]{64}")) || binding.optString("taskId") != task.id || binding.optString("runId") != operation.optString("runId") ||
            binding.optString("operationId") != operation.optString("operationId") ||
            receipt.optString("checkoutId") != context.optString("checkoutId") || receipt.optString("projectId") != context.optString("projectId")) return null
        return receipt
    }

    fun originalPollRequest(task: UiTask): JSONObject? {
        val operation = task.raw.optJSONArray("operations").objects().lastOrNull { it.optString("tool") == "project_execute" } ?: return null
        if (operation.optString("state") !in setOf("unknown", "receipt", "project_running", "project_stopping")) return null
        return try { dev.pocketengine.features.local.project.ProjectDevelopment.originalPollRequest(task.raw, operation) } catch (_: Exception) { null }
    }

    fun canContinue(task: UiTask): Boolean = task.raw.has("operations") && task.status in setOf("completed", "failed", "cancelled", "canceled", "stopped", "paused", "interrupted", "needs_confirmation", "awaiting_user", "waiting_user", "waiting_permission", "awaiting_permission") &&
        unresolvedOperations(task).isEmpty() && task.raw.optJSONArray("approvals").objects().none { it.optString("state") == "pending" }

    fun failureDraft(task: UiTask, operationId: String? = null): String? {
        val receipt = task.raw.optJSONArray("operations").objects().asReversed().filter { operationId == null || it.optString("operationId") == operationId }.mapNotNull { executionReceipt(task, it) }
            .firstOrNull { it.optString("status") == "failed" && it.opt("receiptReceived") == true && !it.optBoolean("cancelRequested") && (it.opt("exitCode") as? Number)?.toLong()?.let { code -> code != 0L } == true } ?: return null
        val details = buildString {
            append("请根据原任务这次实际${intentLabel(receipt.optString("intent"))}失败继续修复，保留同一个项目；重新执行仍按具体方案审批。\n")
            if (receipt.optBoolean("noChildCreated") && receipt.optString("launchPhase") == "no_child_created") append("原生未创建子进程，启动错误码：").append(receipt.opt("launchErrno")).append("（不是进程退出码）\n")
            else append("退出码：").append(receipt.opt("exitCode")).append("\n")
            for ((field, label) in listOf("stderr" to "错误输出", "stdout" to "标准输出")) {
                val text = receipt.optString(field)
                if (text.isNotBlank()) append(label).append("（实际进程输出，作为资料核对）：\n").append(text).append("\n")
            }
        }
        return if (details.length <= 7800) details else details.take(7700) + "\n[草稿仅保留部分日志；完整回执仍在原任务中]"
    }

    fun intentLabel(intent: String): String = when (intent) {
        "prepare" -> "准备工具"; "probe" -> "检查工具"; "dependencies" -> "准备依赖"; "build" -> "构建"; "test" -> "测试"; "run" -> "运行"; "command" -> "项目命令与终端"; "edit" -> "修改文件"; "patch" -> "应用精确补丁"; "revert_operation" -> "按原版本撤销文件修改"; "freeze_package" -> "保存固定项目包"; else -> "项目操作"
    }

    val activeStatuses = setOf("preparing", "running", "paused", "awaiting_user", "waiting_approval", "awaiting_approval", "stopping", "cancelling", "reconciling", "unknown", "reconciliation_required")

    fun preparationReceipt(task: UiTask?): JSONObject? {
        task ?: return null
        val operation = task.raw.optJSONArray("operations").objects().lastOrNull {
            it.optString("tool") == "project_execute" && (it.optJSONObject("arguments")?.optString("stepId") == "prepare" || it.optJSONObject("receipt")?.optString("intent") == "prepare")
        } ?: return null
        return executionReceipt(task, operation)
    }
}

/** Values are observations only. A full download does not establish a runnable tool environment. */
internal data class ProjectPreparationProgress(val phase: String, val completedBytes: Long?, val totalBytes: Long?, val error: String?, val resumableDownload: Boolean) {
    // The native preparation contract reuses the counters for tools/steps outside transfer phases.
    val byteProgress: Boolean get() = phase == "downloading" || phase == "verifying_download" || phase.startsWith("extracting_")
    val fraction: Float? get() = if ((byteProgress || phase in setOf("configuring", "recovering_package_configuration", "updating_signed_package_indexes", "installing_system_tools", "probing_tools", "probing_child_process_and_venv", "activating", "ready")) && completedBytes != null && totalBytes != null && totalBytes > 0 && completedBytes in 0..totalBytes) completedBytes.toFloat() / totalBytes.toFloat() else null

    companion object {
        fun from(value: JSONObject?): ProjectPreparationProgress? = observed(value, "phase", "error")

        fun fromReceipt(receipt: JSONObject?): ProjectPreparationProgress? = from(receipt?.optJSONObject("preparation"))
            ?: observed(receipt, "preparationPhase", "preparationError")

        private fun observed(value: JSONObject?, phaseField: String, errorField: String): ProjectPreparationProgress? {
            value ?: return null
            val phase = value.opt(phaseField) as? String ?: return null
            if (phase.isBlank()) return null
            fun bytes(field: String): Long? { val observed = value.opt(field); return if (observed is Int || observed is Long) (observed as Number).toLong().takeIf { it >= 0 } else null }
            return ProjectPreparationProgress(phase, bytes("completedBytes"), bytes("totalBytes"), value.optString(errorField).takeUnless { it.isBlank() || it == "null" }, value.opt("resumableDownload") == true)
        }
    }
}
