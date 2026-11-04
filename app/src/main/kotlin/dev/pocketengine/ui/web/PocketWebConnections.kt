package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.remote.RemoteTaskRequest
import dev.pocketengine.features.ssh.SshTerminalScreen
import dev.pocketengine.features.ssh.SshOwnerWorkflows
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.SshFileReceiptGuard
import dev.pocketengine.ui.objects
import java.util.UUID
import org.json.JSONObject

/** Presentation only: the existing FeatureHost still owns every original task and receipt. */
internal class PocketWebConnections(private val controller: PocketWorldController) {
    private data class Review(val domain: String, val command: String, val input: String, val expires: Long, val window: Pair<Triple<Long, Int, Long>, Long>)
    private val reviews = linkedMapOf<String, Review>()
    private val environments = mutableMapOf<String, JSONObject>()
    private val probes = mutableMapOf<String, Pair<String, JSONObject>>()
    private val screens = mutableMapOf<String, SshTerminalScreen>()
    private var doctor: SshOwnerWorkflows.DoctorPage? = null
    @Volatile private var generation = 0L
    private val main = Handler(Looper.getMainLooper())

    companion object {
        val COMMANDS = setOf(
            "remote.saveProfile", "remote.removeProfile", "remote.probe", "remote.environments", "remote.probeWorker",
            "remote.reconnect", "remote.reconcileOperation", "remote.getJob", "remote.readSessionRecovery", "remote.cancelJob",
            "remote.reviewTask", "remote.confirmTask", "ssh.saveProfile", "ssh.removeProfile", "ssh.probeHostKey", "ssh.approveHostKey",
            "ssh.forgetHostKey", "ssh.listFiles", "ssh.readFile", "ssh.inspectFile", "ssh.getOperation", "ssh.reviewOperation", "ssh.confirmOperation",
            "ssh.readPty", "ssh.writePty", "ssh.resizePty", "ssh.closePty", "ssh.startTask", "ssh.doctor", "ssh.closeDoctor"
        )
    }
    fun clear() { generation++; reviews.clear(); environments.clear(); probes.clear(); screens.clear(); doctor?.close(); doctor = null }
    private fun fields(args: JSONObject, vararg allowed: String) { require(args.keys().asSequence().all { it in allowed }) { "原连接操作包含不适用的字段" } }
    private fun string(args: JSONObject, key: String, max: Int = 128, empty: Boolean = false): String {
        val value = args.opt(key); require(value is String && value.length <= max && '\u0000' !in value && (empty || value.isNotBlank())) { "请重新核对$key" }; return value
    }
    private fun profile(domain: String, id: String, binding: String): JSONObject {
        require(binding.matches(Regex("[a-f0-9]{64}"))) { "请重新读取原连接版本" }
        val current = controller.state.feature(domain).optJSONArray("profiles").objects().singleOrNull { it.optString("id") == id }
        check(current != null && current.optString("bindingHash") == binding) { "原连接已修改或移除，请重新打开" }
        return current
    }
    private fun currentProfile(domain: String, args: JSONObject, bindingKey: String = "_profileBinding"): JSONObject = profile(domain, string(args, "profileId"), string(args, bindingKey, 64))
    private fun review(domain: String, command: String, input: JSONObject): JSONObject {
        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        check(reviews.size < 16) { "请先关闭已有操作审阅" }
        val handle = UUID.randomUUID().toString()
        reviews[handle] = Review(domain, command, input.toString(), SystemClock.elapsedRealtime() + 120_000, controller.webWindowTicket())
        return JSONObject(input.toString()).put("reviewHandle", handle).put("operation", command)
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请在原私人工作台操作" }
        val domain = action.substringBefore('.'); var command = action.substringAfter('.')
        var input = JSONObject(args.toString())
        val originalGeneration = generation; val window = controller.webWindowTicket()
        val deliver: (JSONObject?, String?) -> Unit = { result, error ->
            if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) done(result, error)
            else done(null, "原连接页面已关闭；请查询原任务或当前连接，不自动重做这项操作")
        }
        when (command) {
            "saveProfile" -> {
                if (domain == "remote") fields(args, "id", "expectedBindingHash", "name", "baseUrl", "token", "clearToken")
                else fields(args, "id", "expectedBindingHash", "name", "host", "port", "username", "workspace", "authType", "password", "privateKey", "passphrase")
                val expected = string(args, "expectedBindingHash", 64, true)
                if (args.has("id")) profile(domain, string(args, "id"), expected) else require(expected.isEmpty()) { "新连接不能借用旧版本" }
            }
            "removeProfile" -> { fields(args, "profileId", "expectedBindingHash"); currentProfile(domain, args, "expectedBindingHash") }
            "probe", "environments", "reconnect", "probeHostKey" -> { fields(args, "profileId", "_profileBinding"); currentProfile(domain, args) }
            "probeWorker" -> { fields(args, "profileId", "_profileBinding", "environmentId", "environmentBinding", "worker", "model", "effort"); currentProfile(domain, args) }
            "approveHostKey" -> { fields(args, "profileId", "expectedBindingHash", "probeId", "fingerprint"); currentProfile(domain, args, "expectedBindingHash") }
            "forgetHostKey" -> { fields(args, "profileId", "expectedBindingHash", "fingerprint"); currentProfile(domain, args, "expectedBindingHash") }
            "listFiles", "readFile", "inspectFile" -> { fields(args, "profileId", "_profileBinding", "path"); currentProfile(domain, args); string(args, "path", 1024, command == "listFiles") }
            "getOperation" -> { fields(args, "operationId"); string(args, "operationId") }
            "reconcileOperation" -> { fields(args, "profileId", "operationId"); string(args, "profileId"); string(args, "operationId") }
            "getJob", "readSessionRecovery", "cancelJob" -> {
                fields(args, "profileId", "jobId", "jobOperationId", "serviceBase", "_profileBinding")
                val actual = controller.state.feature("remote").optJSONArray("jobs").objects().singleOrNull {
                    it.optString("profileId") == args.optString("profileId") && it.optString("id") == args.optString("jobId")
                } ?: error("请重新读取这项电脑原任务")
                require(actual.optString("operationId") == string(args, "jobOperationId") && actual.optString("serviceBase") == string(args, "serviceBase", 2048) && actual.optString("profileBinding") == string(args, "_profileBinding", 64)) { "未沿用旧任务控制另一执行" }
            }
            "reviewTask" -> {
                val checked = RemoteTaskRequest.firstForPrivateConnection(args)
                currentProfile(domain, checked)
                val observed = environments[checked.getString("profileId")] ?: error("请先读取这台电脑的实际环境")
                check(observed.optString("profileBinding") == checked.getString("_profileBinding")) { "环境读取属于旧连接" }
                val environment = RemoteTaskRequest.environment(observed, checked.getString("environmentId"), checked.getString("worker"))
                check(environment.optString("bindingHash") == checked.getString("environmentBinding")) { "原工作环境已变化" }
                if (checked.getString("worker") != "command") {
                    val (target, observedProbe) = probes[checked.getString("profileId")] ?: error("请先核对执行器与模型")
                    val proof = JSONObject(target)
                    for (key in listOf("profileId", "_profileBinding", "environmentId", "environmentBinding", "worker", "model", "effort")) check(proof.optString(key) == checked.optString(key)) { "执行器核对已变化，请按当前选项重新核对" }
                    check(observedProbe.optBoolean("available")) { "尚未核实此执行器可用" }
                    if (checked.getString("worker") == "gemini_acp") {
                        check(environment.opt("writeAllowed") == true) { "此 Gemini 后端只接受原环境已明确允许写入的私人工作；不支持只读分析" }
                        val observation = observedProbe.optJSONObject("backendObservation") ?: error("未取得原 Gemini 后端核对，请重新探测")
                        check(observedProbe.optString("capabilityRevision") == checked.getString("capabilityRevision")) { "原后端核对版本已变化，请重新审阅" }
                        check(observation.optString("format") == "pocketworld.remote-backend-observation.v1" && observation.optString("backendId") == checked.getString("backendId") && observation.optString("backendVersion") == "0.66.0" && observation.optInt("protocolVersion") == 1 && observation.optString("modeId") == checked.getString("modeId")) { "原后端或模式尚未核对一致" }
                        check(observation.optString("environmentId") == checked.getString("environmentId") && observation.optString("environmentBinding") == checked.getString("environmentBinding") && observation.optString("requestedModel") == checked.optString("model") && observation.optLong("expiresAt") > System.currentTimeMillis()) { "原后端来源已变化或探测过期，请重新核对" }
                    }
                    if (checked.has("modelOptionsDigest")) check(observedProbe.optJSONObject("modelOptions")?.optString("digest") == checked.getString("modelOptionsDigest")) { "模型目录已变化" }
                }
                check(controller.state.feature("remote").optJSONArray("operations").objects().none { it.optString("profileId") == checked.getString("profileId") && it.optString("status") in setOf("unknown", "submitting") }) { "请先核对这台电脑尚未确认的原提交" }
                checked.put("operationId", UUID.randomUUID().toString())
                done(review(domain, "submitJob", checked), null); return
            }
            "reviewOperation" -> {
                fields(args, "command", "arguments")
                val selected = string(args, "command")
                require(selected in setOf("exec", "openPty", "writeFile")) { "此操作没有原审阅入口" }
                val exact = args.getJSONObject("arguments")
                when (selected) {
                    "exec" -> { fields(exact, "profileId", "_profileBinding", "command", "timeoutSeconds"); string(exact, "command", 12000) }
                    "openPty" -> { fields(exact, "profileId", "_profileBinding", "cols", "rows", "timeoutSeconds", "allowInteractiveShell"); require(exact.opt("allowInteractiveShell") == true) { "请明确核对交互式Shell权限" } }
                    "writeFile" -> { fields(exact, "profileId", "_profileBinding", "path", "content", "expectedVersion", "acceptCooperativeWrite"); string(exact, "path", 1024); string(exact, "expectedVersion", 80); string(exact, "content", 262144, true); require(exact.opt("acceptCooperativeWrite") == true) { "请核对合作式版本保护的边界" } }
                }
                currentProfile(domain, exact)
                check(controller.state.tasks.none { it.raw.optString("feature") == "ssh" && it.raw.optJSONObject("externalRefs")?.optString("profileId") == exact.optString("profileId") && it.status in setOf("unknown", "reconciling") }) { "请先核对原SSH操作，未重新提交" }
                done(review(domain, selected, exact), null); return
            }
            "confirmTask", "confirmOperation" -> {
                fields(args, "reviewHandle")
                val captured = reviews.remove(string(args, "reviewHandle")) ?: error("原操作审阅已使用或关闭")
                check(captured.domain == domain && captured.expires > SystemClock.elapsedRealtime() && captured.window == window) { "原操作审阅已过期" }
                input = JSONObject(captured.input); command = captured.command; currentProfile(domain, input)
            }
            "readPty", "writePty", "resizePty", "closePty" -> {
                fields(args, "terminalId", "hostTaskId", "runId", "cursor", "dataBase64", "cols", "rows")
                val terminalId = string(args, "terminalId"); val task = string(args, "hostTaskId"); val run = string(args, "runId")
                check(controller.state.feature("ssh").optJSONArray("terminals").objects().any { it.optString("terminalId") == terminalId && it.optString("hostTaskId", it.optString("taskId")) == task && it.optString("runId") == run }) { "原终端已结束或属于另一执行，请核对原任务" }
                if (command == "readPty") { check(screens.containsKey(terminalId) || screens.size < 8); input.put("cursor", screens.getOrPut(terminalId) { SshTerminalScreen() }.cursor()) }
                if (command == "writePty") string(args, "dataBase64", 100000)
            }
            "startTask" -> {
                fields(args, "profileId", "bindingHash", "clientMessageId", "goal"); currentProfile(domain, args, "bindingHash")
                string(args, "goal", 12000); require(string(args, "clientMessageId").matches(Regex("[a-fA-F0-9-]{36}")))
                controller.requestOwnerActionReceipt("startSshTask", input, deliver); return
            }
            "doctor" -> {
                fields(args, "profileId", "_profileBinding"); currentProfile(domain, args)
                doctor?.close(); val page = controller.newPrivateSshDoctorPage(); doctor = page
                page.start(string(args, "profileId"), string(args, "_profileBinding", 64)) { actual -> main.post { deliver(actual, null) } }; return
            }
            "closeDoctor" -> { fields(args); doctor?.close(); doctor = null; done(JSONObject().put("closed", true), null); return }
            else -> error("原连接操作未接入")
        }
        val capturedCommand = command; val capturedInput = JSONObject(input.toString())
        controller.requestWebFeatureReceipt(domain, command, input, {
            originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()
        }) { actual, error ->
            if (error != null || actual == null) deliver(null, error ?: "原操作未返回结果")
            else try {
                if (generation == originalGeneration && window == controller.webWindowTicket()) {
                    if (action == "remote.environments") environments[input.getString("profileId")] = JSONObject(actual.toString())
                    if (action == "remote.probeWorker") probes[input.getString("profileId")] = Pair(input.toString(), JSONObject(actual.toString()))
                    if (domain == "ssh" && capturedCommand in setOf("readFile", "writeFile") && actual.optString("status") == "completed") {
                        check(actual.optString("profileId") == capturedInput.getString("profileId") && actual.optString("path") == capturedInput.getString("path")) { "文件回执不属于这次原读取或保存" }
                        if (capturedCommand == "writeFile") actual.put("contentVerifiedForReview", SshFileReceiptGuard.saved(actual, capturedInput.getString("content")))
                    }
                    if (domain == "ssh" && capturedCommand == "readPty" && actual.has("dataBase64")) {
                        val screen = screens[capturedInput.getString("terminalId")] ?: error("原终端读取窗口已关闭")
                        screen.append(actual.getString("dataBase64"), capturedInput.getLong("cursor"), actual.getLong("cursor"), actual.optBoolean("truncated"))
                        actual.put("frame", screen.view(80, 24)); actual.remove("dataBase64")
                    }
                }
                deliver(PocketWebState.publicValue(actual) as JSONObject, null)
            } catch (failure: Exception) { deliver(null, failure.message ?: "原操作回执无法核对") }
        }
    }
}
