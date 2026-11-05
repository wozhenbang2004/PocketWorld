package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.project.OwnerLocalTerminal
import dev.pocketengine.features.local.project.ProjectOwnerTerminal
import dev.pocketengine.features.local.project.ProjectTerminalScreen
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** Viewer state only. Processes, input sequencing, scope and receipts remain in the original host. */
internal class PocketWebLocal(private val controller: PocketWorldController, private val taskView: () -> String? = { null }) {
    private data class Review(val id: String, val original: OwnerLocalTerminal.Review, val owner: Any)
    private data class Viewer(val id: String, val task: String, val execution: String, val session: ProjectOwnerTerminal.Session, val control: RunControl, val owner: Any, val taskScoped: Boolean, var stopRequested: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private var review: Review? = null
    private var viewer: Viewer? = null
    @Volatile private var generation = 0L
    private var busy = false
    companion object { val COMMANDS = setOf("local.prepareCheckout", "local.describeCheckout", "local.terminal.review", "local.terminal.confirm", "local.terminal.query", "local.terminal.stop", "local.terminal.open", "local.terminal.read", "local.terminal.input", "local.terminal.inputReceipt", "local.terminal.close", "local.execution.open", "local.execution.readLogs", "local.execution.input", "local.execution.inputReceipt", "local.execution.stop", "local.execution.close") }

    fun clear() {
        generation++; busy = false
        val prior = review; review = null
        val old = viewer; viewer = null; old?.control?.cancel()
        runCatching { controller.runPluginCatalogWork(Runnable { prior?.original?.close(); old?.session?.close() }) }
    }
    private fun fields(args: JSONObject, vararg allowed: String) { require(args.keys().asSequence().all { it in allowed }) { "本机操作包含不适用的字段" } }
    private fun id(args: JSONObject, key: String): String { val value = args.opt(key); require(value is String && value.matches(Regex("[a-f0-9-]{36}"))) { "请重新选择原本机对象" }; return value }
    private fun executionId(args: JSONObject, key: String): String { val value = args.opt(key); require(value is String && value.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "请重新选择原项目执行记录" }; return value }
    private fun current(owner: Any, request: Long, originalTask: String? = null) = generation == request && controller.webPrivateOwnerVisible() && controller.webWindowTicket() == owner && (originalTask == null || taskView() == originalTask)

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS) { "不支持的原本机操作" }
        val taskScoped = action.startsWith("local.execution.")
        if (action in setOf("local.terminal.close", "local.execution.close")) {
            fields(args, "handle"); val handle = id(args, "handle")
            if (viewer?.let { it.id == handle && it.taskScoped == taskScoped } == true || !taskScoped && review?.id == handle) clear()
            done(JSONObject().put("viewerClosed", true).put("processStopRequested", false), null); return
        }
        check(controller.webPrivateOwnerVisible()) { "请在私人前台打开原本机工作区" }
        check(!busy) { "请等待本次原操作回执" }
        val owner = controller.webWindowTicket(); val request = generation
        if (action == "local.prepareCheckout") {
            fields(args, "projectId"); val project = id(args, "projectId")
            check(controller.state.projects.any { it.id == project }) { "原项目不在当前读取范围" }; busy = true
            controller.requestFeatureReceipt("local", "prepare", JSONObject().put("projectId", project)) { result, error ->
                if (current(owner, request)) { busy = false; done(result?.let { PocketWebState.publicValue(it) as JSONObject }, error) }
                else done(null, "原页面已关闭，请查询既有工作副本，不要重复准备")
            }; return
        }
        val originalReview = if (action == "local.terminal.confirm") {
            fields(args, "handle"); val selected = review?.takeIf { it.id == id(args, "handle") && it.owner == owner } ?: error("原终端审阅已过期，请查询原编号")
            review = null; selected
        } else null
        val originalViewer = if (action in setOf("local.terminal.read", "local.terminal.input", "local.terminal.inputReceipt", "local.execution.readLogs", "local.execution.input", "local.execution.inputReceipt", "local.execution.stop")) {
            val selected = viewer?.takeIf { it.id == id(args, "handle") && it.owner == owner && it.taskScoped == taskScoped && (!taskScoped || taskView() == it.task) } ?: error("原终端视图已关闭，请重新打开原进程")
            when (action) {
                "local.terminal.input", "local.execution.input" -> {
                    fields(args, "handle", "action", "text", "columns", "rows")
                    if (args.optString("action") == "resize") { require(args.opt("columns") is Int && args.opt("rows") is Int); if (taskScoped) ProjectTerminalScreen.requireProjectDimensions(args.getInt("columns"), args.getInt("rows")) else ProjectTerminalScreen.requireDimensions(args.getInt("columns"), args.getInt("rows")) }
                }
                else -> fields(args, "handle")
            }; selected
        } else null
        when (action) {
            "local.describeCheckout" -> { fields(args, "projectId", "checkoutId"); id(args, "projectId"); id(args, "checkoutId") }
            "local.terminal.review" -> fields(args, "projectId", "checkoutId", "expectedSourceDigest", "runtimeId", "cwd", "env", "timeoutMillis", "columns", "rows", "inputByteLimit")
            "local.terminal.query", "local.terminal.stop" -> { fields(args, "taskId", "runId", "epoch", "executionId"); val task = id(args, "taskId"); require(id(args, "runId") == task && id(args, "executionId") == task && args.opt("epoch") == 1) { "不是原手动终端身份" } }
            "local.terminal.open" -> { fields(args, "taskId", "executionId"); id(args, "taskId"); id(args, "executionId") }
            "local.execution.open" -> { fields(args, "taskId", "executionId", "observingOnly"); check(taskView() == executionId(args, "taskId")) { "请重新打开这份原项目任务" }; executionId(args, "executionId"); require(args.opt("observingOnly") is Boolean) { "请明确选择只读日志或原交互终端" } }
        }
        if (action == "local.execution.stop") { check(!originalViewer!!.stopRequested) { "已请求停止原进程，请读取原退出与资源回执，不要重复请求" }; originalViewer.stopRequested = true }
        val originalTask = if (taskScoped) originalViewer?.task ?: args.getString("taskId") else null
        val input = JSONObject(args.toString()); busy = true
        controller.runPluginCatalogWork(Runnable {
            var acquired: OwnerLocalTerminal.Review? = null
            var opened: ProjectOwnerTerminal.Session? = null
            val observation = RunControl()
            val answer = runCatching {
                check(current(owner, request, originalTask)) { "原本机窗口已变化" }
                when (action) {
                    "local.describeCheckout" -> {
                        val source = controller.describeLocalCheckout(input.getString("checkoutId")); check(source.getString("projectId") == input.getString("projectId")) { "工作副本不属于原项目" }
                        source.remove("files"); source
                    }
                    "local.terminal.review" -> controller.localTerminalHost().reviewLocalTerminalForOwner(input).also { acquired = it }.view()
                    "local.terminal.confirm" -> controller.localTerminalHost().submitLocalTerminalForOwner(originalReview!!.original.confirm())
                    "local.terminal.query", "local.terminal.stop" -> controller.localTerminalHost().localTerminalForOwner(input.getString("taskId"), action == "local.terminal.stop")
                    "local.terminal.open" -> {
                        opened = controller.localTerminalHost().openProjectTerminalForOwner(input.getString("taskId"), input.getString("executionId"), observation)
                        JSONObject().put("metadata", opened!!.metadata()).put("output", opened!!.readScreen())
                    }
                    "local.execution.open" -> {
                        opened = if (input.getBoolean("observingOnly")) controller.localTerminalHost().openProjectExecutionForOwner(input.getString("taskId"), input.getString("executionId"), observation)
                        else controller.localTerminalHost().openProjectTerminalForOwner(input.getString("taskId"), input.getString("executionId"), observation)
                        JSONObject().put("metadata", opened!!.metadata()).put("output", opened!!.readScreen())
                    }
                    "local.terminal.read" -> originalViewer!!.session.readScreen()
                    "local.execution.readLogs" -> JSONObject().put("metadata", originalViewer!!.session.metadata()).put("output", originalViewer.session.readScreen())
                    "local.terminal.inputReceipt", "local.execution.inputReceipt" -> originalViewer!!.session.originalInput()
                    "local.terminal.input", "local.execution.input" -> { input.remove("handle"); originalViewer!!.session.input(input) }
                    "local.execution.stop" -> originalViewer!!.session.stop()
                    else -> error("不支持的原本机操作")
                }
            }
            originalReview?.original?.close()
            main.post {
                if (!current(owner, request, originalTask)) { acquired?.close(); observation.cancel(); opened?.close(); done(null, "原页面已变化；只可查询原任务，不重复启动或输入"); return@post }
                busy = false
                answer.fold({ result ->
                    acquired?.let { native -> review?.original?.close(); Review(UUID.randomUUID().toString(), native, owner).also { review = it; result.put("handle", it.id) } }
                    opened?.let { native -> viewer?.control?.cancel(); viewer?.session?.close(); Viewer(UUID.randomUUID().toString(), input.getString("taskId"), input.getString("executionId"), native, observation, owner, taskScoped).also { viewer = it; result.put("handle", it.id) } }
                    if (taskScoped) (originalViewer ?: viewer)?.let { result.put("handle", it.id).put("taskId", it.task).put("executionId", it.execution).put("stopRequested", it.stopRequested) }
                    done(PocketWebState.publicValue(result) as JSONObject, null)
                }, { observation.cancel(); opened?.close(); done(null, it.message ?: "原本机回执暂不可核对") })
            }
        })
    }
}
