package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** Project-scoped presentation of the existing profile admission. No profile editing or second runner. */
internal class PocketWebProjectWork(
    private val controller: PocketWorldController,
    private val document: () -> String?,
    private val ownerVisible: () -> Boolean,
) {
    private data class Window(val id: String, val project: String, val document: String, val owner: Any)
    private data class Operation(val project: String, val command: String, var value: JSONObject)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var window: Window? = null
    private var busy = false
    private val windowProjects = linkedMapOf<String, String>()
    private val operations = linkedMapOf<String, Operation>()
    private val reviews = linkedMapOf<String, String>()
    private val profiles = PocketWebProfiles(controller, ::ownerCurrent, projectScope = { input ->
        val original = window ?: error("请重新打开项目工作")
        check(ownerCurrent() && input.getString("projectId") == original.project) { "原项目选择已变化" }
        controller.reviewProjectWorkProfile(original.project, input.getString("checkoutId"), input.getString("runtimeId"))
    }, startCompleted = { id, value ->
        synchronized(lock) { operations[id]?.takeIf { it.command == "start" }?.let { retainStart(it, value) } }
    })

    companion object {
        val COMMANDS = setOf("projectWork.open", "projectWork.snapshot", "projectWork.prepareCheckout", "projectWork.preflight",
            "projectWork.reviewStart", "projectWork.confirm", "projectWork.result", "projectWork.stop", "projectWork.close")
    }

    fun clear() {
        synchronized(lock) {
            window = null; busy = false; reviews.clear()
            operations.values.filter { it.command == "prepareCheckout" && it.value.optString("status") == "queued" }
                .forEach { it.value.put("status", "not_entered") }
        }
        profiles.clear()
    }

    private fun current(original: Window): Boolean = window === original && document() == original.document && ownerVisible() &&
        controller.webPrivateOwnerVisible() && controller.webPhoneViewerWindowTicket() == original.owner &&
        controller.state.projectId == original.project && controller.state.filePath == null
    private fun ownerCurrent(): Boolean = window?.let(::current) == true
    private fun fields(args: JSONObject, vararg allowed: String) = require(args.keys().asSequence().all { it in allowed }) { "项目工作请求包含不适用的字段" }
    private fun identity(args: JSONObject, key: String): String {
        val value = args.opt(key)
        val pattern = if (key == "profileId") "[A-Za-z0-9_.:@/-]{1,100}" else "[A-Za-z0-9_-]{1,120}"
        require(value is String && value.matches(Regex(pattern))) { "请重新选择原项目工作对象" }
        return value
    }
    private fun original(args: JSONObject): Window {
        val selected = window ?: error("项目工作窗口已关闭")
        check(identity(args, "windowId") == selected.id && current(selected)) { "原项目或窗口已变化，请重新打开；草稿仍保留" }
        return selected
    }
    private fun response(original: Window, value: JSONObject): JSONObject = JSONObject(value.toString()).put("projectId", original.project).put("windowId", original.id)
    /** Caller holds the wrapper lock. Late queue/query replies cannot erase a delivered task or final receipt. */
    private fun retainStart(operation: Operation, incoming: JSONObject): JSONObject {
        val previous = operation.value; val before = previous.optString("status"); val after = incoming.optString("status")
        check(previous.getString("operationId") == incoming.getString("operationId")) { "原启动回执身份已变化" }
        val settled = setOf("completed", "not_entered")
        val finalBefore = previous.optBoolean("final") || before in settled
        val finalAfter = incoming.optBoolean("final") || after in settled
        val lostQuery = after == "unknown" && !incoming.has("taskId") && !incoming.has("error") && !finalAfter
        val keep = finalBefore && !finalAfter || before == "entered" && after == "queued" || previous.optBoolean("taskCreated") && lostQuery
        val merged = JSONObject(previous.toString())
        if (!keep) incoming.keys().asSequence().forEach { merged.put(it, incoming.get(it)) }
        val task = previous.optString("taskId")
        if (task.isNotBlank()) {
            check(!incoming.has("taskId") || incoming.optString("taskId") == task) { "原启动不能替换任务身份" }
            merged.put("taskId", task)
        }
        merged.put("taskCreated", previous.optBoolean("taskCreated") || incoming.optBoolean("taskCreated"))
            .put("admitted", previous.optBoolean("admitted") || incoming.optBoolean("admitted"))
            .put("command", "start").put("replayAllowed", false)
        if (!keep && after == "completed" && !incoming.has("error")) merged.remove("error")
        operation.value = merged
        return JSONObject(merged.toString())
    }
    private fun requireResolvedStart(project: String) = synchronized(lock) {
        check(operations.values.none { it.project == project && it.command == "start" &&
            it.value.optString("status") in setOf("queued", "entered", "unknown") && !it.value.optBoolean("taskCreated") }) {
            "请先查询原启动回执，不要重新提交同一目标"
        }
    }
    private fun remember(id: String, value: Operation) = synchronized(lock) {
        check(!operations.containsKey(id)) { "原操作已登记，请先核对结果" }
        while (operations.size >= 24) {
            val settled = operations.entries.firstOrNull { it.value.value.optString("status") in setOf("completed", "not_entered") ||
                it.value.command == "start" && it.value.value.optBoolean("taskCreated") }
                ?: error("请先核对已有项目操作的回执")
            operations.remove(settled.key)
        }
        operations[id] = value
    }

    private fun snapshot(original: Window, done: (JSONObject?, String?) -> Unit) {
        check(!busy) { "请等待这次项目读取完成" }; busy = true
        try { profiles.dispatch("profiles.snapshot", JSONObject()) { saved, error ->
            if (!current(original)) return@dispatch
            if (error != null || saved == null) { busy = false; done(null, error ?: "工作配置清单尚未返回"); return@dispatch }
            try { controller.runWorkProfileWork(Runnable {
                val result = runCatching {
                    check(current(original)) { "原项目窗口已关闭" }
                    controller.projectWorkSources(original.project).put("profiles", saved.getJSONArray("profiles"))
                        .put("executionAuthorized", false).put("readOnly", true)
                }
                main.post {
                    if (current(original)) { busy = false; result.fold({ done(response(original, it), null) }, { done(null, it.message ?: "项目来源尚未返回") }) }
                }
            }) } catch (failure: Exception) { busy = false; done(null, failure.message ?: "项目读取尚未进入") }
        } } catch (failure: Exception) { busy = false; throw failure }
    }

    private fun result(original: Window, id: String, done: (JSONObject?, String?) -> Unit) {
        val operation = synchronized(lock) { operations[id] }
        if (operation == null) {
            done(response(original, JSONObject().put("operationId", id).put("status", "unknown").put("readOnly", true).put("replayAllowed", false)), null)
            return
        }
        check(operation.project == original.project) { "此回执不属于当前项目" }
        val recorded = synchronized(lock) { JSONObject(operation.value.toString()) }
        if (operation.command == "prepareCheckout" || recorded.optString("status") == "not_entered") {
            done(response(original, recorded).put("readOnly", true).put("replayAllowed", false), null)
            return
        }
        profiles.dispatch("profiles.result", JSONObject().put("operationId", id)) { value, error ->
            val retained = value?.let { synchronized(lock) { retainStart(operation, it) } }
            done(retained?.let { response(original, it).put("readOnly", true) }, error)
        }
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        if (action == "projectWork.close") {
            fields(args, "windowId"); val id = identity(args, "windowId")
            val project = synchronized(lock) { windowProjects[id] }
            if (window?.id == id) clear()
            done(JSONObject().put("projectId", project ?: JSONObject.NULL).put("windowId", id).put("windowClosed", true).put("alreadyClosed", project == null), null); return
        }
        if (action == "projectWork.open") {
            fields(args, "projectId"); val project = identity(args, "projectId")
            check(document() != null && ownerVisible() && controller.webPrivateOwnerVisible() && controller.state.projectId == project &&
                controller.state.filePath == null && controller.state.projects.any { it.id == project }) { "请先打开这份原私人项目" }
            clear()
            val selected = Window(UUID.randomUUID().toString(), project, document()!!, controller.webPhoneViewerWindowTicket())
            synchronized(lock) {
                window = selected; while (windowProjects.size >= 16) windowProjects.remove(windowProjects.keys.first())
                windowProjects[selected.id] = project
            }
            snapshot(selected, done); return
        }
        val selected = original(args)
        when (action) {
            "projectWork.snapshot" -> { fields(args, "windowId"); snapshot(selected, done) }
            "projectWork.prepareCheckout" -> {
                fields(args, "windowId", "operationId"); val id = identity(args, "operationId")
                require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "请保留本次副本准备的准确编号" }
                synchronized(lock) { operations[id] }?.let {
                    check(it.project == selected.project && it.command == "prepareCheckout") { "原操作编号不能替换项目或用途" }
                    result(selected, id, done); return
                }
                check(!busy) { "请等待原项目操作回执" }
                check(synchronized(lock) { operations.values.none { it.project == selected.project && it.command == "prepareCheckout" && it.value.optString("status") in setOf("queued", "entered", "unknown") } }) { "先核对原副本准备结果，不要重复创建" }
                val operation = Operation(selected.project, "prepareCheckout", JSONObject().put("operationId", id).put("command", "prepareCheckout").put("status", "queued"))
                remember(id, operation); busy = true
                try { controller.runWorkProfileWork(Runnable {
                    val answer = runCatching {
                        controller.prepareProjectWorkCheckout(selected.project) {
                            synchronized(lock) {
                                if (!current(selected) || operation.value.optString("status") != "queued") false
                                else { operation.value.put("status", "entered"); true }
                            }
                        }
                    }
                    val value = synchronized(lock) {
                        answer.fold({ checkout -> operation.value.put("status", "completed").put("receipt", JSONObject().put("checkout", checkout)) },
                            { failure -> operation.value.put("status", if (operation.value.optString("status") in setOf("queued", "not_entered")) "not_entered" else "unknown")
                                .put("error", failure.message ?: "副本准备结果待核对") })
                        JSONObject(operation.value.toString())
                    }
                    main.post { if (current(selected)) busy = false; done(response(selected, value), null) }
                }) } catch (failure: Exception) {
                    synchronized(lock) { operation.value.put("status", "not_entered").put("error", failure.message) }; busy = false
                    done(response(selected, operation.value), null)
                }
            }
            "projectWork.preflight" -> {
                fields(args, "windowId", "profileId", "checkoutId", "runtimeId")
                val profileId = identity(args, "profileId"); val checkout = identity(args, "checkoutId"); val runtime = identity(args, "runtimeId")
                require(runtime in setOf("managed_process_v1", "termux")) { "请选择已有的本机运行位置" }
                requireResolvedStart(selected.project)
                check(!busy) { "请等待原项目读取完成" }; busy = true; reviews.clear()
                fun fail(message: String?) { busy = false; done(null, message ?: "原项目预检尚未返回") }
                try { profiles.dispatch("profiles.read", JSONObject().put("id", profileId)) { read, readError ->
                    if (!current(selected)) return@dispatch
                    if (readError != null || read == null) { fail(readError); return@dispatch }
                    try { profiles.dispatch("profiles.choices", JSONObject().put("projectId", selected.project).put("checkoutId", checkout).put("runtimeId", runtime)) choices@{ choices, choicesError ->
                        if (!current(selected)) return@choices
                        if (choicesError != null || choices == null) { fail(choicesError); return@choices }
                        try { profiles.dispatch("profiles.preflight", JSONObject().put("viewHandle", read.getString("viewHandle")).put("choicesHandle", choices.getString("choicesHandle"))) inspected@{ inspected, inspectError ->
                            if (!current(selected)) return@inspected
                            busy = false
                            done(inspected?.let { response(selected, it).put("profile", read.getJSONObject("profile")) }, inspectError)
                        } } catch (failure: Exception) { fail(failure.message) }
                    } } catch (failure: Exception) { fail(failure.message) }
                } } catch (failure: Exception) { busy = false; throw failure }
            }
            "projectWork.reviewStart" -> {
                fields(args, "windowId", "preflightHandle", "goal"); check(!busy) { "请等待原项目预检" }
                requireResolvedStart(selected.project); busy = true
                try { profiles.dispatch("profiles.reviewStart", JSONObject().put("preflightHandle", identity(args, "preflightHandle")).put("goal", args.get("goal"))) { value, error ->
                    if (!current(selected)) return@dispatch
                    busy = false
                    if (value != null) reviews[value.getString("reviewHandle")] = selected.id
                    done(value?.let { response(selected, it) }, error)
                } } catch (failure: Exception) { busy = false; throw failure }
            }
            "projectWork.confirm" -> {
                fields(args, "windowId", "reviewHandle", "consent"); require(args.opt("consent") == true) { "请明确核对这次目标、工作配置和项目副本" }
                val id = identity(args, "reviewHandle")
                synchronized(lock) { operations[id] }?.let {
                    check(it.project == selected.project && it.command == "start") { "原启动编号不能替换项目或用途" }
                    result(selected, id, done); return
                }
                requireResolvedStart(selected.project)
                check(!busy && reviews.remove(id) == selected.id) { "原项目启动审阅已关闭，请重新核对" }
                val operation = Operation(selected.project, "start", JSONObject().put("operationId", id).put("command", "start").put("status", "queued"))
                remember(id, operation)
                try { profiles.dispatch("profiles.confirm", JSONObject().put("reviewHandle", id).put("consent", true)) { value, error ->
                    val retained = synchronized(lock) { retainStart(operation, value ?: JSONObject().put("operationId", id).put("status", "unknown").put("error", error)) }
                    done(response(selected, retained), null)
                } } catch (failure: Exception) {
                    val notEntered = !profiles.startWasRegistered(id)
                    val retained = synchronized(lock) {
                        retainStart(operation, JSONObject().put("operationId", id).put("status", if (notEntered) "not_entered" else "unknown")
                            .put("final", notEntered).put("error", failure.message))
                    }
                    done(response(selected, retained), null)
                }
            }
            "projectWork.result" -> { fields(args, "windowId", "operationId"); result(selected, identity(args, "operationId"), done) }
            "projectWork.stop" -> {
                fields(args, "windowId", "operationId"); val id = identity(args, "operationId")
                check(synchronized(lock) { operations[id]?.let { it.project == selected.project && it.command == "start" } } == true) { "请选择这次原项目启动" }
                profiles.dispatch("profiles.stop", JSONObject().put("operationId", id)) { value, error -> done(value?.let { response(selected, it) }, error) }
            }
        }
    }
}
