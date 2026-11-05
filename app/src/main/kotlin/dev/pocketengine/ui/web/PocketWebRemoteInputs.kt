package dev.pocketengine.ui.web

import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Exact native private attachment workflow, shared by create and original-session continuation. */
internal class PocketWebRemoteInputs(
    private val controller: PocketWorldController,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val taskView: () -> String?
) {
    private class Page(val window: String, val task: String, val originalView: String?, val owner: PocketWebWindowTicket) {
        var snapshot: JSONObject? = null
        var viewHandle = UUID.randomUUID().toString()
        val pending = mutableSetOf<String>()
    }
    private var page: Page? = null
    companion object {
        private val fields = mapOf(
            "select" to setOf("files"), "setContinuation" to setOf("prompt", "timeoutSeconds"),
            "review" to emptySet(), "confirm" to setOf("digest", "billingAcknowledged"),
            "inspect" to emptySet(), "query" to emptySet(), "cancel" to emptySet(), "openTask" to emptySet()
        )
        val COMMANDS = fields.keys.map { "remoteInputs.$it" }.toSet() + setOf("remoteInputs.open", "remoteInputs.close")
        private val nativeCommands = mapOf("select" to "selectInputs", "setContinuation" to "setContinuationInput", "review" to "reviewInputs", "confirm" to "confirmInputs", "inspect" to "inspectInputs", "query" to "queryInputs", "cancel" to "cancelInputs")
        private fun keys(args: JSONObject, names: Set<String>) { require(args.keys().asSequence().toSet() == names) { "原附件流程参数不完整或包含额外目标" } }
        private fun text(args: JSONObject, name: String, max: Int = 160): String = (args.opt(name) as? String)?.takeIf { it.isNotBlank() && it.length <= max && '\u0000' !in it } ?: error("原$name 无效")
    }
    fun clear() { val original = page; page = null; original?.let { controller.leaveRemoteTask(it.window) } }
    private fun current(original: Page) = page === original && acceptsWindow(original.owner) && controller.webPrivateOwnerVisible() && taskView() == original.originalView
    private fun view(original: Page, actual: JSONObject): JSONObject {
        original.snapshot = JSONObject(actual.toString()); original.viewHandle = UUID.randomUUID().toString()
        return (PocketWebState.publicValue(actual) as JSONObject).put("pageHandle", original.window).put("viewHandle", original.viewHandle)
            .put("busy", actual.optBoolean("busy") || "confirmInputs" in original.pending)
    }
    private fun execute(original: Page, command: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(current(original) && command !in original.pending) { "原附件页面已变化或相同操作仍在处理" }
        require(original.pending.isEmpty() || command in setOf("inspectInputs", "cancelInputs")) { "原附件写入仍在处理，只能查看进度或停止" }
        original.pending.add(command)
        try { controller.requestOwnerDomainReceipt("remoteTask", command, input.put("windowId", original.window).put("taskId", original.task)) { result, error ->
            original.pending.remove(command)
            if (!current(original)) {
                controller.leaveRemoteTask(original.window); done(null, "原附件页面已关闭或换代，请查询原任务，不重传")
            } else {
                val snapshot = result?.optJSONObject("snapshot")
                if (snapshot == null || error != null) done(null, error ?: "原附件流程没有返回可核对状态")
                else if (snapshot.optString("windowId") != original.window || snapshot.optString("taskId") != original.task) done(null, "原附件回执身份不符")
                else done(view(original, snapshot), null)
            }
        } } catch (failure: Exception) { original.pending.remove(command); throw failure }
    }
    private fun open(task: String, command: String, request: JSONObject?, done: (JSONObject?, String?) -> Unit) {
        require(controller.webPrivateOwnerVisible()); clear()
        val original = Page(UUID.randomUUID().toString(), task, taskView(), captureWindow()); page = original
        val input = JSONObject(); request?.let { input.put("request", JSONObject(it.toString())) }
        execute(original, command, input, done)
    }
    /** Called only after PocketWebConnections consumes its actual native create review. */
    fun openPrepared(task: String, request: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(taskView() == null) { "请回到原电脑新建页面选择附件" }
        open(task, "openInputs", request, done)
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action == "remoteInputs.close") {
            keys(args, setOf("pageHandle")); if (page?.window == text(args, "pageHandle")) clear()
            done(JSONObject().put("closed", true), null); return
        }
        require(controller.webPrivateOwnerVisible()) { "请回到原私人附件页面" }
        if (action == "remoteInputs.open") {
            keys(args, setOf("taskId", "continuation")); val task = text(args, "taskId")
            require(taskView() == task && args.opt("continuation") is Boolean) { "请先打开原电脑任务" }
            open(task, if (args.getBoolean("continuation")) "openContinuationInputs" else "openInputs", null, done); return
        }
        val operation = action.removePrefix("remoteInputs."); val expected = fields[operation] ?: error("未知原附件操作")
        keys(args, expected + setOf("pageHandle", "viewHandle"))
        val original = page ?: error("原附件页面已关闭")
        require(current(original) && text(args, "pageHandle") == original.window && text(args, "viewHandle") == original.viewHandle) { "原附件范围已变化，请重新读取" }
        val snapshot = original.snapshot ?: error("请先读取原附件范围")
        if (operation == "openTask") {
            require(snapshot.optBoolean("registered")) { "原任务尚未登记" }
            controller.focusTask(original.task); done(JSONObject().put("taskId", original.task).put("navigationRequested", true), null); clear(); return
        }
        val input = JSONObject(); expected.forEach { input.put(it, args.get(it)) }
        if (operation == "select") {
            require(!snapshot.optBoolean("registered") && args.opt("files") is JSONArray)
            val files = args.getJSONArray("files").objects(); require(files.size == args.getJSONArray("files").length() && files.size <= 8 && files.map { text(it, "id") }.toSet().size == files.size)
            val available = snapshot.optJSONArray("availableFiles").objects()
            files.forEach { file -> keys(file, setOf("id", "sha256")); require(available.any { it.optString("id") == text(file, "id") && it.optString("sha256") == text(file, "sha256") }) { "所选原件已变化，请重新核对" } }
        }
        if (operation == "setContinuation") {
            text(args, "prompt", 8000); val seconds = args.opt("timeoutSeconds")
            require((seconds is Int || seconds is Long) && (seconds as Number).toLong() in 10..3600)
        }
        if (operation == "confirm") require(args.opt("billingAcknowledged") == true && text(args, "digest") == snapshot.optJSONObject("review")?.optString("digest")) { "请重新核对准确外传与计费范围" }
        execute(original, nativeCommands.getValue(operation), input, done)
    }
}
