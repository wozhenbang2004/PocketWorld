package dev.pocketengine.ui.web

import dev.pocketengine.core.RuntimeDiagnostics
import dev.pocketengine.ui.MediaOwnerUi
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiTask
import dev.pocketengine.ui.isPhoneTask
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.pendingCapabilityApprovals
import dev.pocketengine.ui.unresolvedOperations
import org.json.JSONArray
import org.json.JSONObject

/** Task decisions consume the existing native review; the bridge owns the presenting window. */
internal class PocketWebTaskActions(
    private val controller: PocketWorldController,
    private val reviews: PocketWebTaskReviews,
    private val canUseTaskControls: (UiTask) -> Boolean,
    private val postToMain: (Runnable) -> Unit
) {
    /** Accepted is controller admission only. Plan and continuation still need their native receipt. */
    sealed class Decision {
        object Accepted : Decision()
        class Plan(val action: String, val task: UiTask, val input: JSONObject) : Decision()
        class Continuation(val task: UiTask, val input: JSONObject) : Decision()
    }

    fun decide(action: String, args: JSONObject, taskViewId: String?): Decision {
        when (action) {
            "tasks.plan.enter" -> {
                checkedPayload(args, setOf("reviewHandle"))
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                require(canUseTaskControls(task) && !specialOwnerTask(task)) { "原任务规划窗口已关闭" }
                return Decision.Plan("enterTaskPlanning", task, PocketWebApprovals.planningInput(task))
            }
            "tasks.resume" -> {
                checkedPayload(args, setOf("reviewHandle", "confirmed", "feedback"))
                require(requiredBoolean(args, "confirmed")) { "请先核对继续原任务的范围" }
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                require(taskViewId == task.id && canUseTaskControls(task) && task.raw.optString("executionRole") != "owner_work") { "请使用这件事的原执行入口继续" }
                val project = PocketWebProjectContinuation.view(task, task.id == controller.state.activeTaskId)
                if (project != null) {
                    require(!project.has("reason")) { project.optString("reason") }
                    return Decision.Continuation(task, PocketWebProjectContinuation.input(task, requiredString(args, "feedback")))
                }
                controller.resumeTask(task, if (isGroup(task)) "" else args.optString("feedback"))
            }
            "tasks.approve" -> {
                val approved = requiredBoolean(args, "approved")
                val approvalId = requiredString(args, "approvalId")
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks, approvalId.takeIf { approved })
                require(canUseTaskControls(task)) { "原审核窗口已关闭" }
                val original = pendingCapabilityApprovals(task).firstOrNull { it.optString("approvalId", it.optString("id")) == approvalId }
                    ?: error("这项授权不在原审阅中")
                PocketWebApprovals.requireDecision(original, args)
                if (PocketWebApprovals.isExecutionPlan(original)) {
                    checkedPayload(args, setOf("reviewHandle", "approvalId", "approved", "scopeReviewed", "planRevision", "planArtifactRef", "planHash"))
                    require(!specialOwnerTask(task)) { "请回原专用任务处理计划" }
                    return Decision.Plan("approveCapability", task, PocketWebApprovals.executionDecisionInput(task, original, approved))
                }
                controller.approveCapability(task, approvalId, approved)
            }
            "tasks.confirm", "tasks.resolve", "tasks.scope" -> {
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                require(canUseTaskControls(task)) { "原审核窗口已关闭" }
                when (action) {
                    "tasks.confirm" -> controller.confirmReviewedTask(task, requiredBoolean(args, "completed"), if (isGroup(task)) "" else args.optString("feedback"))
                    "tasks.resolve" -> {
                        val operation = requiredString(args, "operationId"); val outcome = requiredString(args, "outcome")
                        require(outcome in setOf("occurred", "not_occurred"))
                        require(unresolvedForReview(task).any { it.optString("operationId", it.optString("id")) == operation }) { "这项原操作不在审阅中" }
                        controller.resolveReviewedTask(task, operation, outcome)
                    }
                    else -> controller.configureExecutionScope(task, requiredInt(args, "minutes"), requiredInt(args, "tools"), requiredInt(args, "models"))
                }
            }
            else -> error("这项操作不属于原任务决定")
        }
        return Decision.Accepted
    }

    /** tasks.open only schedules a read. A review handle requires the actual complete native result. */
    fun read(taskId: String, closed: () -> Boolean, current: () -> Boolean, complete: (JSONObject?, String?) -> Unit) {
        controller.readTaskForReview(taskId) { task, error ->
            if (closed()) return@readTaskForReview
            if (!current()) {
                complete(null, "原任务窗口已关闭，请重新打开后核对")
            } else if (error != null || task == null) {
                complete(null, error ?: "任务结果尚未读取，请重试")
            } else {
                val captured = task.copy(raw = JSONObject(task.raw.toString()))
                fun finish(approvals: JSONArray) {
                    if (!current()) complete(null, "原任务窗口已关闭，请重新打开后核对")
                    else complete(review(captured, approvals, reviews, controller.state.activeTaskId, canUseTaskControls(captured)), null)
                }
                try {
                    if (pendingCapabilityApprovals(captured).any(PocketWebApprovals::needsOriginalPlan)) {
                        controller.runEcosystemControlWork(Runnable {
                            val result = runCatching { PocketWebApprovals.read(captured, controller) }
                                .onFailure { RuntimeDiagnostics.event("diagnostics", "failed", "TASK_REVIEW_PLAN_READ_FAILED", null, it) }
                            postToMain(Runnable {
                                if (!closed()) result.fold({ approvals ->
                                    try { finish(approvals) } catch (failure: Exception) {
                                        readFailed("TASK_REVIEW_PRESENTATION_FAILED", "任务结果暂时无法核对", failure, complete)
                                    }
                                }, { failure -> complete(null, failure.message ?: "原计划暂时无法读取") })
                            })
                        })
                    } else finish(PocketWebApprovals.read(captured, controller))
                } catch (failure: Exception) {
                    readFailed("TASK_REVIEW_READ_FAILED", "任务结果暂时无法核对", failure, complete)
                }
            }
        }
    }

    private fun readFailed(code: String, fallback: String, failure: Throwable, complete: (JSONObject?, String?) -> Unit) {
        RuntimeDiagnostics.event("diagnostics", "failed", code, null, failure)
        complete(null, failure.message ?: fallback)
    }

    companion object {
        val DECISIONS = setOf("tasks.plan.enter", "tasks.resume", "tasks.approve", "tasks.confirm", "tasks.resolve", "tasks.scope")

        /** Only original native task facts and original plan reads can add review actions. */
        fun review(task: UiTask, approvalViews: JSONArray, reviews: PocketWebTaskReviews, activeTaskId: String?, canUseTaskControls: Boolean): JSONObject {
            val allowed = linkedMapOf<String, String>()
            val special = specialOwnerTask(task)
            val unknown = unresolvedForReview(task)
            val approvals = pendingCapabilityApprovals(task)
            val projectContinuation = PocketWebProjectContinuation.view(task, task.id == activeTaskId)
            val question = PocketWebOwnerQuestions.view(task.raw)
            val screenshot = task.raw.optJSONObject("evidence")?.optJSONObject("screenshot")
            if (task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private" &&
                task.raw.optJSONObject("origin")?.optString("kind") !in setOf("group", "paired_device") &&
                screenshot?.optString("imageId")?.isNotBlank() == true && screenshot.optString("state") != "deleted") allowed["tasks.deleteScreenshot"] = "删除这张本机截图"
            if (canUseTaskControls && !special) {
                PocketWebOwnerQuestions.actions(question).forEach { allowed[it] = if (it == PocketWebOwnerQuestions.CHOOSE) "选择原问题答案" else "回答原问题" }
                if (task.status == "needs_confirmation" && task.id != activeTaskId) allowed["tasks.confirm"] = "核对结果"
                if (approvals.isNotEmpty()) allowed["tasks.approve"] = "审阅操作授权"
                if (unknown.any { it.optString("operationId", it.optString("id")).isNotBlank() }) allowed["tasks.resolve"] = "核对原操作"
                if (projectContinuation != null) {
                    if (!projectContinuation.has("reason")) allowed["tasks.resume"] = "在原范围内继续项目任务"
                } else if (task.raw.optString("executionRole") != "owner_work" && !PocketWebOwnerQuestions.blocksGenericResume(task.raw) && (isPhoneTask(task) || task.raw.optString("kind") == "assistant") && task.id != activeTaskId && task.status in setOf("paused", "awaiting_user", "needs_confirmation", "interrupted") && !task.raw.optBoolean("cancelRequested") && unknown.isEmpty() && approvals.isEmpty()) allowed["tasks.resume"] = "确认继续原任务"
                if (!isGroup(task) && task.raw.optJSONObject("executionScope") != null && task.status !in setOf("completed", "cancelled", "canceled", "failed", "interrupted", "reconciling")) allowed["tasks.scope"] = "调整原任务范围"
                if (PocketWebApprovals.ordinaryPlanningTask(task) && task.raw.getJSONObject("executionScope").optString("executionPhase", "executing") == "executing" &&
                    task.status in setOf("queued", "paused", "awaiting_user", "needs_confirmation") && unknown.isEmpty() && approvals.isEmpty()) {
                    allowed["tasks.plan.enter"] = "进入只读规划"
                }
            }
            val positiveApprovalIds = approvalViews.objects().filter { it.optJSONObject("webReview")?.optBoolean("canApprove") == true }
                .map { it.optString("approvalId", it.optString("id")) }.filter { it.isNotBlank() }.toSet()
            val handle = reviews.create(task, allowed.keys, positiveApprovalIds)
            return JSONObject().put("reviewHandle", handle).put("expiresInMs", 120_000)
                .put("task", PocketWebState.task(task, images = true))
                .put("ownerQuestion", question ?: JSONObject.NULL)
                .put("projectContinuation", projectContinuation ?: JSONObject.NULL)
                .put("actions", JSONArray().also { out -> allowed.forEach { (action, label) -> out.put(JSONObject().put("action", action).put("label", label)) } })
                .put("approvals", PocketWebState.publicValue(approvalViews))
                .put("unresolvedOperations", PocketWebState.publicValue(JSONArray(unknown)))
        }

        fun unresolvedForReview(task: UiTask): List<JSONObject> = unresolvedOperations(task).filterNot { it.optString("tool") in setOf("device_invoke_tool", "device_delegate_goal", "device_operation_get") }
        fun specialOwnerTask(task: UiTask) = setOf("ownerLocalTerminal", "ownerBackupUpload", "ownerStorageCleanup", "ownerArchivePhase", "ownerGit", "ownerGitImport", "ownerGitPublication", "ownerMcpDiscovery").any(task.raw::has) || MediaOwnerUi.isTask(task.raw) || task.raw.optJSONObject("sourceLearningJob") != null || task.raw.optJSONObject("nativeEventTypeFlags")?.optBoolean("memory_owner_consolidation") == true || task.raw.optJSONArray("events").objects().any { it.optString("type") == "memory_owner_consolidation" }

        private fun isGroup(task: UiTask) = task.raw.optJSONObject("origin")?.optString("kind") == "group"
        private fun checkedPayload(args: JSONObject, allowed: Set<String>): JSONObject {
            require(args.keys().asSequence().all { it in allowed }) { "操作包含未接入的参数" }
            return JSONObject(args.toString())
        }
        private fun requiredString(args: JSONObject, name: String): String {
            val value = args.opt(name); require(value is String && value.isNotBlank()) { "缺少有效参数 $name" }; return value
        }
        private fun requiredInt(args: JSONObject, name: String): Int {
            val value = args.opt(name); require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "缺少有效整数参数 $name" }; return value.toInt()
        }
        private fun requiredBoolean(args: JSONObject, name: String): Boolean {
            val value = args.opt(name); require(value is Boolean) { "缺少有效开关参数 $name" }; return value
        }
    }
}
