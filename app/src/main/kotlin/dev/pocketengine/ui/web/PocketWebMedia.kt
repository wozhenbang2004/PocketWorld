package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.MediaOwnerUi
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.taskResultAttachments
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** MediaWorkflowHost owns durable submissions and recovery. These handles retain only owner readings. */
internal class PocketWebMedia(private val controller: PocketWorldController) {
    private data class Review(val command: String, val input: String, val expires: Long)
    private data class Reading(val task: String, val expires: Long)
    private var generation = 0L
    private var snapshot = JSONObject()
    private val reviews = linkedMapOf<String, Review>()
    private val readings = linkedMapOf<String, Reading>()
    private val preparedTasks = linkedSetOf<String>()
    companion object {
        val COMMANDS = setOf("media.snapshot", "media.reviewConfiguration", "media.prepare", "media.readTask", "media.confirm",
            "media.query", "media.fetchResults", "media.reviewDownload", "media.reviewCancel", "media.stopWaiting", "media.openResult", "media.closeReading")
    }
    fun clear() { generation++; snapshot = JSONObject(); reviews.clear(); readings.clear(); preparedTasks.clear() }
    private fun fields(args: JSONObject, vararg names: String) { require(args.keys().asSequence().all { it in names }) { "媒体操作包含不适用的字段" } }
    private fun text(args: JSONObject, key: String, max: Int = 128, optional: Boolean = false): String {
        val value = args.opt(key)
        if (optional && value == null) return ""
        require(value is String && value.length <= max && '\u0000' !in value && (optional || value.isNotBlank())) { "请核对$key" }
        return value
    }
    private fun public(value: JSONObject) = PocketWebState.publicValue(value) as JSONObject
    private fun review(command: String, args: JSONObject, details: JSONObject = JSONObject()): JSONObject {
        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        check(reviews.size < 8) { "请先关闭已有媒体审阅" }
        val handle = UUID.randomUUID().toString()
        reviews[handle] = Review(command, args.toString(), SystemClock.elapsedRealtime() + 120_000)
        val visible = JSONObject(args.toString()).also { it.remove("apiKey"); it.remove("expectedConfigurationRevision") }
        return public(details).put("reviewHandle", handle).put("command", command).put("input", public(visible))
    }
    private fun read(handle: String): JSONObject {
        val value = readings[handle]
        check(value != null && value.expires > SystemClock.elapsedRealtime()) { "原媒体读取已关闭或过期，请重新读取" }
        return JSONObject(value.task)
    }
    private fun view(task: JSONObject): JSONObject {
        readings.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        while (readings.size >= 16) readings.remove(readings.keys.first())
        val handle = UUID.randomUUID().toString()
        readings[handle] = Reading(task.toString(), SystemClock.elapsedRealtime() + 120_000)
        val operation = MediaOwnerUi.operation(task)
        return JSONObject().put("viewHandle", handle).put("taskId", task.getString("id")).put("task", public(task))
            .put("operation", operation?.let(::public) ?: JSONObject.NULL)
            .put("receipt", operation?.optJSONObject("receipt")?.let(::public) ?: task.optJSONObject("evidence")?.let(::public) ?: JSONObject())
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到前台私人媒体页面" }
        val originalGeneration = generation; val window = controller.webWindowTicket()
        fun current() = generation == originalGeneration && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()
        val deliver: (JSONObject?, String?) -> Unit = { value, error -> if (current()) done(value, error) }
        fun feature(command: String, payload: JSONObject, receive: (JSONObject) -> JSONObject = { JSONObject().put("confirmed", true).put("command", command).put("result", public(it)) }) {
            controller.requestFeatureReceipt("media", command, payload) { result, error ->
                if (current()) {
                    if (error != null || result == null) deliver(null, error ?: "原媒体操作没有返回回执")
                    else try { deliver(receive(result), null) } catch (failure: Exception) { deliver(null, failure.message ?: "原媒体回执待核对") }
                }
            }
        }
        when (action) {
            "media.snapshot" -> {
                fields(args)
                controller.readWebOwnerFeatureSnapshot("media") { value, error ->
                    if (current()) {
                        if (error == null && value != null) snapshot = JSONObject(value.toString())
                        deliver(value?.let(::public), error)
                    }
                }
            }
            "media.closeReading" -> { fields(args, "viewHandle", "reviewHandle"); readings.remove(args.optString("viewHandle")); reviews.remove(args.optString("reviewHandle")); deliver(JSONObject().put("closed", true), null) }
            "media.reviewConfiguration" -> {
                fields(args, "provider", "baseUrl", "apiKey", "imageModel", "ttsModel", "voice", "legacyImageBase64", "allowResultUrls", "comfyJobsApi", "workflow", "promptNodeId", "promptInput", "videoWorkflow", "videoPromptNodeId", "videoPromptInput")
                check(snapshot.optString("configurationRevision").isNotBlank()) { "请先读取媒体服务配置" }
                val provider = text(args, "provider", 32); require(provider in setOf("openai_compatible", "comfyui"))
                text(args, "baseUrl", 2048); text(args, "apiKey", 8192, true)
                listOf("imageModel", "ttsModel", "voice", "promptNodeId", "promptInput", "videoPromptNodeId", "videoPromptInput").forEach { text(args, it, 120, true) }
                listOf("legacyImageBase64", "allowResultUrls", "comfyJobsApi").forEach { require(args.opt(it) is Boolean) }
                listOf("workflow", "videoWorkflow").forEach { if (args.has(it)) { require(args.opt(it) is JSONObject); require(args.getJSONObject(it).toString().length <= 512 * 1024) } }
                val frozen = JSONObject(args.toString()).put("expectedConfigurationRevision", snapshot.getString("configurationRevision"))
                deliver(review("configure", frozen, JSONObject().put("replacesSavedTemplates", snapshot.optJSONObject("configuration")?.optBoolean("hasWorkflow") == true || snapshot.optJSONObject("configuration")?.optBoolean("hasVideoWorkflow") == true)), null)
            }
            "media.prepare" -> {
                fields(args, "kind", "text", "workflow", "observeUntilComplete", "imageId", "maskId")
                require(snapshot.optBoolean("configured")) { "请先保存并读取真实媒体服务配置" }
                val command = when (text(args, "kind", 24)) { "image" -> "generateImage"; "speech" -> "synthesizeSpeech"; "video" -> "generateVideo"; "edit" -> "editImage"; "workflow" -> "submitWorkflow"; else -> error("请选择已接入的媒体类型") }
                val payload = JSONObject().put("configurationRevision", snapshot.getString("configurationRevision"))
                require(args.opt("observeUntilComplete") is Boolean)
                payload.put("observeUntilComplete", args.getBoolean("observeUntilComplete"))
                if (command == "submitWorkflow") { require(args.opt("workflow") is JSONObject); require(args.getJSONObject("workflow").toString().length <= 512 * 1024); payload.put("workflow", args.getJSONObject("workflow")) }
                else payload.put(if (command == "synthesizeSpeech") "text" else "prompt", text(args, "text", snapshot.getJSONObject("inputLimits").getInt(if (command == "synthesizeSpeech") "speechTextChars" else "imagePromptChars")).trim())
                if (command == "editImage") {
                    fun image(key: String, prefix: String) {
                        val id = text(args, key)
                        val file = controller.state.feature("attachments").optJSONArray("attachments").objects().singleOrNull { it.optString("id") == id
                            && it.optString("mimeType") in (if (prefix == "mask") setOf("image/png") else setOf("image/png", "image/jpeg", "image/webp")) && it.optLong("size") in 1..4L * 1024 * 1024 }
                            ?: error("请选择本机已导入的原图版本")
                        payload.put(prefix + "Id", file.getString("id")).put(prefix + "Sha256", file.getString("sha256"))
                    }
                    image("imageId", "image"); if (args.optString("maskId").isNotBlank()) image("maskId", "mask")
                }
                feature("prepareSubmission", JSONObject().put("submissionCommand", command).put("arguments", payload)) { result ->
                    check(result.opt("dispatched") == false && result.optString("status") == "awaiting_owner_submission" && result.optString("submissionCommand") == command
                        && result.optString("configurationRevision") == payload.getString("configurationRevision") && result.optString("requestHash") == TaskStore.capabilityFingerprint(payload)) { "媒体准备回执与本次输入不一致" }
                    val task = result.getString("taskId"); require(task.matches(Regex("[a-f0-9-]{36}"))); preparedTasks.add(task)
                    review(command, JSONObject(payload.toString()).put("taskId", task), JSONObject().put("prepared", public(result)))
                }
            }
            "media.readTask" -> {
                fields(args, "taskId"); val id = text(args, "taskId")
                require(id in preparedTasks || controller.state.tasks.any { it.id == id && MediaOwnerUi.isTask(it.raw) }) { "请先读取这项原媒体任务" }
                controller.readTaskForOpen(id, ::current) { task, error ->
                    if (current()) {
                        if (error != null || task == null) deliver(null, error ?: "原媒体任务暂不可读取")
                        else try {
                            require(MediaOwnerUi.isTask(task.raw)) { "原任务不是媒体工作" }
                            val reading = view(task.raw)
                            if (MediaOwnerUi.operation(task.raw) != null) deliver(reading, null)
                            else feature("getPreparedSubmission", JSONObject().put("taskId", id)) { original ->
                                check(original.optString("taskId") == id)
                                if (original.optString("status") == "awaiting_owner_submission" && original.opt("dispatched") == false && original.opt("knownOperation") == false) {
                                    val input = JSONObject(original.getJSONObject("arguments").toString())
                                    check(TaskStore.capabilityFingerprint(input) == original.getString("requestHash"))
                                    reading.put("submissionReview", review(original.getString("submissionCommand"), input.put("taskId", id), JSONObject().put("prepared", public(original))))
                                }
                                reading
                            }
                        } catch (failure: Exception) { deliver(null, failure.message ?: "原媒体任务身份不可核对") }
                    }
                }
            }
            "media.confirm" -> {
                fields(args, "reviewHandle")
                val original = reviews.remove(text(args, "reviewHandle")) ?: error("原媒体审阅已使用或关闭，请先核对原任务")
                check(original.expires > SystemClock.elapsedRealtime()) { "原媒体审阅已过期，请重新核对" }
                feature(original.command, JSONObject(original.input)) { result ->
                    if (original.command == "configure") snapshot = JSONObject(result.toString())
                    JSONObject().put("confirmed", true).put("command", original.command).put("result", public(result))
                }
            }
            "media.query", "media.fetchResults", "media.reviewDownload", "media.reviewCancel", "media.stopWaiting", "media.openResult" -> {
                fields(args, "viewHandle", "fileId", "sha256")
                val task = read(text(args, "viewHandle")); val operation = MediaOwnerUi.operation(task) ?: error("原媒体操作尚未提交，请先读取准备回执")
                val binding = MediaOwnerUi.binding(task, operation)
                when (action) {
                    "media.query" -> feature("reconcileOperation", binding)
                    "media.fetchResults" -> feature("fetchResults", binding)
                    "media.stopWaiting" -> feature("cancel", JSONObject().put("operationId", binding.getString("operationId")))
                    "media.reviewCancel" -> deliver(review("cancelJob", binding), null)
                    "media.reviewDownload" -> feature("reviewResultDownload", binding) { value ->
                        val native = value.getJSONObject("resultDownloadReview")
                        for (key in listOf("operationId", "taskId", "runId", "configurationRevision")) check(native.optString(key) == binding.getString(key))
                        check(native.optInt("epoch") == binding.getInt("epoch") && native.optString("method") == "GET" && native.optInt("maxAttempts") == 1 && native.opt("newGeneration") == false)
                        review("confirmResultDownload", JSONObject(binding.toString()).put("downloadReview", native), JSONObject().put("downloadReview", public(native)))
                    }
                    else -> {
                        val files = operation.optJSONObject("receipt")?.optJSONArray("attachments").objects()
                        val file = files.singleOrNull { it.optString("id") == text(args, "fileId") && it.optString("sha256") == text(args, "sha256") } ?: error("原产物版本已变化，请重新读取")
                        val currentTask = controller.state.tasks.singleOrNull { it.id == task.getString("id") } ?: error("请重新读取原媒体任务")
                        val original = taskResultAttachments(currentTask).singleOrNull { it.id == file.getString("id") && it.sha256 == file.getString("sha256") }
                            ?: error("原产物与任务来源尚不可核对")
                        controller.openTaskResultAttachment(currentTask.id, original)
                        deliver(JSONObject().put("accepted", true).put("fileId", original.id), null)
                    }
                }
            }
            else -> error("媒体操作尚未接入")
        }
    }
}
