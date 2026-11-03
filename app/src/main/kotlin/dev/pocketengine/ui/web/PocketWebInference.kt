package dev.pocketengine.ui.web

import dev.pocketengine.runtime.usage.TaskInferencePresentation
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONArray
import org.json.JSONObject

/** Labels and totals come from the original native receipt projection; Web never re-sums usage. */
internal class PocketWebInference(private val controller: PocketWorldController) {
    private var cancel: (() -> Unit)? = null
    private var generation = 0L
    fun clear() { generation++; cancel?.invoke(); cancel = null }
    fun read(taskId: String, cursor: Int, done: (JSONObject?, String?) -> Unit) {
        require(cursor in 0..100000) { "调用记录窗口无效" }
        check(controller.webPrivateOwnerVisible()) { "请在原私人任务查看用量" }
        val task = controller.state.tasks.firstOrNull { it.id == taskId } ?: error("请先读取原任务")
        clear(); val request = generation; val owner = controller.webWindowTicket()
        cancel = controller.readTaskInferenceUsage(task.raw) { result, error ->
            if (generation != request || !controller.webPrivateOwnerVisible() || controller.webWindowTicket() != owner) { done(null, "原任务用量窗口已变化，请重新读取"); return@readTaskInferenceUsage }
            cancel = null
            if (result == null) done(null, error ?: "原任务累计回执暂不可读")
            else done(snapshot(taskId, result, cursor), null)
        }
    }
    private fun counters(values: List<TaskInferencePresentation.Counter>) = JSONArray().also { out -> values.forEach { out.put(JSONObject().put("name", it.name()).put("text", it.text())) } }
    private fun snapshot(task: String, value: TaskInferencePresentation.Snapshot, cursor: Int): JSONObject {
        val calls = value.calls(); val end = minOf(cursor + 20, calls.size)
        return JSONObject().put("taskId", task).put("headline", value.headline()).put("coverage", value.coverage())
            .put("money", JSONArray(value.money())).put("warnings", JSONArray(value.warnings())).put("hasAccount", value.hasAccount())
            .put("context", JSONObject().put("title", value.context().title()).put("lines", JSONArray(value.context().lines())).put("at", value.context().at()).put("attention", value.context().attention()))
            .put("groups", JSONArray().also { out -> value.groups().forEach { out.put(JSONObject().put("title", it.title()).put("route", it.route()).put("semantics", it.semantics()).put("counters", counters(it.counters()))) } })
            .put("calls", JSONArray().also { out -> calls.drop(cursor).take(20).forEach { out.put(JSONObject().put("id", it.id()).put("purpose", it.purpose()).put("status", it.status()).put("route", it.route()).put("binding", it.binding()).put("at", it.at()).put("apiKey", it.apiKey()).put("counters", counters(it.counters())).put("pricing", JSONArray(it.pricing())).put("failure", JSONArray(it.failure()))) } })
            .put("cursor", cursor).put("nextCursor", if (end < calls.size) end else -1).put("retainedCallCount", calls.size).put("readOnly", true).put("executionAuthorized", false)
    }
}
