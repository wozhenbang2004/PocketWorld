package dev.pocketengine.ui.web

import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiTask
import org.json.JSONObject

/** A read window over the original task journal. Closing it never stops the task. */
internal class PocketWebTrace(
    private val visible: () -> Boolean,
    private val window: () -> Pair<Triple<Long, Int, Long>, Long>,
    private val findTask: (String) -> UiTask?,
    private val readNative: (String, String, String, Int, (JSONObject?, String?) -> Unit) -> (() -> Unit),
    private val readDetailNative: (String, String, String, (JSONObject?, String?) -> Unit) -> (() -> Unit),
    private val sourceRevision: () -> Long
) {
    constructor(controller: PocketWorldController) : this(controller::webPrivateOwnerVisible,
        controller::webWindowTicket, { id -> controller.state.tasks.firstOrNull { it.id == id } },
        controller::readTaskExecutionTrace, controller::readTaskExecutionTraceDetail, { controller.conversationSourceRevision })

    // Opening an event must not cancel the overview's older-page read. Both slots
    // remain bound to the original owner window and close together on navigation.
    private class ReadWindow {
        var generation = 0L
        var cancel: (() -> Unit)? = null
        var pending: ((JSONObject?, String?) -> Unit)? = null
        var taskId = ""
        var runId = ""

        fun clear() {
            generation++
            val stop = cancel
            val complete = pending
            cancel = null
            pending = null
            taskId = ""
            runId = ""
            stop?.invoke()
            complete?.invoke(null, "原轨迹读取窗口已关闭")
        }
    }
    private val pages = ReadWindow()
    private val details = ReadWindow()

    fun clear() {
        pages.clear()
        details.clear()
    }

    private fun requireTask(taskId: String) {
        check(visible()) { "请在原私人任务查看执行轨迹" }
        val task = findTask(taskId) ?: error("请先读取原任务")
        check(task.raw.optString("taskProjection") != "source_unavailable" && !task.raw.optBoolean("sourceCurrentReadRequired")) { "原任务来源暂不可核对，请重新读取任务" }
    }

    fun read(taskId: String, runId: String, cursor: String, limit: Int, done: (JSONObject?, String?) -> Unit) {
        require(limit in 1..200 && cursor.length <= 2048) { "轨迹读取范围无效" }
        require(runId.length <= 200 && '\u0000' !in runId && '\u0000' !in cursor) { "原执行身份无效" }
        requireTask(taskId)
        if (details.taskId.isNotEmpty() && (details.taskId != taskId || runId.isNotEmpty() && details.runId != runId)) details.clear()
        start(pages, taskId, runId, false, done, { callback -> readNative(taskId, runId, cursor, limit, callback) }) { result ->
            check(result.optString("format") == "pocketworld.execution-trace.v1") { "原轨迹回执格式不可核对" }
        }
    }

    fun detail(taskId: String, runId: String, epoch: Long, nodeId: String, done: (JSONObject?, String?) -> Unit) {
        require(runId.isNotBlank() && runId.length <= 200 && '\u0000' !in runId && epoch >= 0) { "原执行身份无效" }
        require(nodeId.isNotBlank() && nodeId.length <= 300 && '\u0000' !in nodeId) { "原轨迹事件身份无效" }
        requireTask(taskId)
        start(details, taskId, runId, true, done, { callback -> readDetailNative(taskId, runId, nodeId, callback) }) { result ->
            val observedEpoch = result.opt("epoch")
            check(result.optString("format") == "pocketworld.execution-trace-detail.v1" &&
                observedEpoch is Number && observedEpoch.toLong() == epoch && observedEpoch.toDouble() == epoch.toDouble() &&
                result.optJSONObject("node")?.optString("id") == nodeId) { "轨迹详情不属于原执行事件" }
        }
    }

    private fun start(slot: ReadWindow, taskId: String, runId: String, readsBody: Boolean,
                      done: (JSONObject?, String?) -> Unit,
                      launch: ((JSONObject?, String?) -> Unit) -> (() -> Unit),
                      validate: (JSONObject) -> Unit) {
        slot.clear()
        slot.taskId = taskId
        slot.runId = runId
        val request = slot.generation
        val owner = window()
        val originalSource = sourceRevision()
        slot.pending = done
        val stop = try { launch(callback@{ result, error ->
            if (request != slot.generation) return@callback
            slot.cancel = null
            val complete = slot.pending
            slot.pending = null
            if (!visible() || window() != owner || readsBody && sourceRevision() != originalSource) {
                complete?.invoke(null, "原轨迹窗口或来源已变化，请重新读取")
            } else if (result == null) {
                complete?.invoke(null, error ?: "原任务轨迹暂不可读")
            } else {
                runCatching {
                    requireTask(taskId)
                    check(result.optString("taskId") == taskId &&
                        (runId.isEmpty() || result.optString("runId") == runId) &&
                        result.opt("readOnly") == true && result.opt("executionAuthorized") == false) { "轨迹回执不属于原任务" }
                    validate(result)
                    PocketWebState.publicValue(result) as JSONObject
                }.fold({ complete?.invoke(it, null) }, { complete?.invoke(null, it.message ?: "原轨迹回执不可核对") })
            }
        }) } catch (failure: Exception) {
            val complete = slot.pending
            slot.pending = null
            complete?.invoke(null, failure.message ?: "原任务轨迹暂不可读")
            return
        }
        // The controller may synchronously reject an already closed read window.
        if (request == slot.generation && slot.pending != null) slot.cancel = stop else stop()
    }
}
