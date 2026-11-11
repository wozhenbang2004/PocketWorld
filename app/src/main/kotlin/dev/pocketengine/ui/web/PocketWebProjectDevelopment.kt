package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Presentation around the original project checkout and Assistant admission.
 * No execution grant, alternate runner, task ledger or restored review lives here. */
internal class PocketWebProjectDevelopment(
    private val controller: PocketWorldController,
    private val ownerCurrent: () -> Boolean,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean
) {
    private data class Page(val handle: String, val project: String, val window: PocketWebWindowTicket, val generation: Long)
    private data class Review(val handle: String, val page: Page, val input: String, val modelRevision: Long, val expires: Long)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    @Volatile private var page: Page? = null
    @Volatile private var review: Review? = null
    private var busy = false
    private val files = PocketWebCheckoutFiles(controller)
    // Identifiers and unsent text only. Original checkout metadata / Assistant
    // submissions remain authoritative, including after Activity recreation.
    private val drafts get() = ownerDrafts
    private val presentation = controller.projectDevelopmentPresentationForWeb()
    private var restoreFailure: String? = null
    init {
        try { presentation.getString("drafts", null)?.let { restorePresentation(JSONArray(it)) } }
        catch (failure: Exception) { restoreFailure = "本机项目输入引用未能读取，已保留原记录。请先在任务列表核对已有任务；不会自动重新提交。" }
    }
    companion object {
        // Multiple Activities must not overwrite each other's pending original
        // input references with an older presentation snapshot.
        private val ownerDrafts = linkedMapOf<String, JSONObject>()
        val COMMANDS = setOf("projects.development.read", "projects.development.draft", "projects.development.inspect",
            "projects.development.prepare", "projects.development.queryCopy", "projects.development.review",
            "projects.development.start", "projects.development.query", "projects.development.dismiss", "projects.development.close") + PocketWebCheckoutFiles.COMMANDS
    }
    fun clear() { generation++; page = null; review = null; busy = false; files.clear() }
    private fun public(value: JSONObject) = PocketWebState.publicValue(value) as JSONObject
    private fun fields(value: JSONObject, vararg allowed: String) = require(value.keys().asSequence().all { it in allowed }) { "项目操作包含未声明字段" }
    private fun text(value: JSONObject, key: String, maximum: Int = 80, blank: Boolean = false): String {
        val found = value.opt(key)
        require(found is String && found.length <= maximum && '\u0000' !in found && (blank || found.isNotBlank())) { "请核对$key" }
        return found
    }
    private fun current(original: Page): Boolean = page === original && generation == original.generation &&
        ownerCurrent() && controller.webPrivateOwnerVisible() && acceptsWindow(original.window) &&
        controller.state.projectId == original.project && controller.state.projects.any { it.id == original.project }
    private fun selected(args: JSONObject): Page {
        val original = page
        check(original != null && original.handle == text(args, "viewHandle") && current(original)) { "原项目页面已变化，请重新读取" }
        return original
    }
    private fun draft(project: String): JSONObject = synchronized(drafts) {
        copy(drafts[project] ?: run {
            JSONObject().put("projectId", project).put("goal", "").put("checkoutId", "")
        })
    }
    private fun updateDraft(project: String, durable: Boolean = false, change: (JSONObject) -> Unit) = synchronized(drafts) {
        check(restoreFailure == null) { restoreFailure ?: "本机项目输入引用不可用" }
        if (!drafts.containsKey(project) && drafts.size >= 40) {
            val disposable = drafts.entries.firstOrNull { (_, value) ->
                value.optString("goal").isBlank() && value.optJSONObject("submission")?.optString("status") !in setOf("pending", "unknown", "unconfirmed") &&
                    (!value.has("copyRequestId") || value.optString("copyStatus") == "ready")
            }?.key
            check(disposable != null) { "有 40 个项目保留输入或未决准备，请先核对已有请求；没有丢弃草稿或未决记录" }
            drafts.remove(disposable) // Only presentation; original tasks and files stay in their stores.
        }
        val value = draft(project); change(value); drafts[project] = copy(value)
        val editor = presentation.edit().putString("drafts", savePresentation().toString())
        if (durable) check(editor.commit()) { "原请求引用没有确认保存，没有开始执行；请核对本机存储空间" } else editor.apply()
    }
    private fun pending(project: String): Boolean = draft(project).optJSONObject("submission")?.optString("status") in setOf("pending", "unknown", "unconfirmed")
    private fun copy(value: JSONObject) = JSONObject(value.toString())
    private fun working(original: Page, done: (JSONObject?, String?) -> Unit, effect: Boolean = false, block: () -> JSONObject) {
        check(!busy) { "请等待当前项目操作回执" }; busy = true
        try {
            controller.runPluginCatalogWork(Runnable {
                val result = runCatching {
                    check(current(original)) { "原项目页面已关闭，没有开始排队操作" }
                    block()
                }
                main.post {
                    if (page === original) busy = false
                    if (current(original)) result.fold({ done(public(it), null) }, { done(null, it.message ?: "项目操作未完成，请核对原记录") })
                    else done(null, if (effect) "原页面已变化，请回到这个项目查询原记录；不要重复提交" else "原页面已变化，请重新读取")
                }
            })
        } catch (failure: Exception) { busy = false; throw failure }
    }
    private fun source(original: Page, checkout: String) = controller.projectDevelopmentForWeb().sourceForOwner(original.project, checkout)
    private fun model() = controller.nativeModelSelection().readStable { controller.projectDevelopmentModelForWeb() }
    private fun metadata(original: Page): JSONObject {
        val actual = controller.projectDevelopmentForWeb().inspectForOwner(original.project)
        val selectedModel = model()
        return actual.put("viewHandle", original.handle).put("model", selectedModel.value()).put("modelRevision", selectedModel.revision())
            .put("draft", copy(draft(original.project))).put("commandsAuthorized", false)
            .put("draftRecoveryRequired", restoreFailure != null).put("draftRecoveryMessage", restoreFailure ?: "")
    }
    private fun recordSubmission(original: JSONObject, result: JSONObject?, error: String?): JSONObject {
        val observed = copy(original)
        val same = result != null && result.optString("clientMessageId") == original.getString("clientMessageId")
        val accepted = same && !result!!.optString("taskId").isBlank() && result.optString("status") in setOf("accepted", "queued", "consumed")
        val rejected = same && result!!.optBoolean("notAdmitted") && result.optString("taskId").isBlank()
        observed.put("status", if (accepted) "accepted" else if (rejected) "rejected" else "unknown")
            .put("message", if (accepted) "项目任务已保存。运行命令仍按原任务的实际方案审批。" else if (rejected) "本次没有启动项目任务，请核对设置后重新审阅。" else "项目输入回执待核对，请查询原输入，不要重新提交同一目标。")
        if (same) for (key in listOf("taskId", "conversationId", "runId", "epoch", "messageId", "notAdmitted")) if (result!!.has(key)) observed.put(key, result.get(key))
        if (error != null) observed.put("error", error)
        updateDraft(original.getString("projectId")) { retained ->
            if (retained.optJSONObject("submission")?.optString("clientMessageId") == original.getString("clientMessageId")) {
                retained.put("submission", observed)
                if (accepted && retained.optString("goal") == original.optString("goal")) retained.put("goal", "")
            }
        }
        return observed
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        if (action == "projects.development.close") {
            fields(args, "viewHandle")
            if (page?.handle == args.optString("viewHandle")) clear()
            done(JSONObject().put("viewClosed", true).put("taskStopped", false), null); return
        }
        if (action == "projects.development.read") {
            fields(args, "projectId"); val project = text(args, "projectId")
            check(ownerCurrent() && controller.webPrivateOwnerVisible() && controller.state.projectId == project &&
                controller.state.projects.any { it.id == project }) { "请打开当前私人项目后再读取" }
            clear(); val original = Page(UUID.randomUUID().toString(), project, captureWindow(), generation); page = original
            working(original, done) { metadata(original) }; return
        }
        val original = selected(args)
        if (action in PocketWebCheckoutFiles.COMMANDS) {
            check(review == null && !busy) { "请先完成或关闭原项目审阅" }
            files.dispatch(action, args, original.project, { current(original) }, done); return
        }
        if (action in setOf("projects.development.draft", "projects.development.prepare", "projects.development.review", "projects.development.start"))
            check(restoreFailure == null) { restoreFailure ?: "本机项目输入引用不可用" }
        when (action) {
            "projects.development.dismiss" -> {
                fields(args, "viewHandle", "reviewHandle")
                if (review?.handle == text(args, "reviewHandle")) review = null
                done(JSONObject().put("dismissed", true).put("taskStopped", false), null)
            }
            "projects.development.draft" -> {
                fields(args, "viewHandle", "goal", "checkoutId")
                val goal = text(args, "goal", 12000, true); val checkout = text(args, "checkoutId", 80, true)
                check(!busy && !pending(original.project)) { "原项目输入仍待核对，草稿没有替换" }
                updateDraft(original.project) { it.put("goal", goal).put("checkoutId", checkout) }; review = null
                done(JSONObject().put("saved", true).put("projectId", original.project), null)
            }
            "projects.development.inspect" -> {
                fields(args, "viewHandle", "checkoutId"); val checkout = text(args, "checkoutId")
                working(original, done) { source(original, checkout) }
            }
            "projects.development.prepare", "projects.development.queryCopy" -> {
                fields(args, "viewHandle")
                val retained = draft(original.project)
                val request = retained.optString("copyRequestId").ifBlank {
                    check(action == "projects.development.prepare") { "没有可查询的原副本准备" }
                    UUID.randomUUID().toString().also { request -> updateDraft(original.project, true) { it.put("copyRequestId", request).put("copyStatus", "pending") } }
                }
                working(original, done, action == "projects.development.prepare") {
                    val host = controller.projectDevelopmentForWeb()
                    if (action == "projects.development.prepare") host.prepareCheckoutForOwner(original.project, request)
                    val observed = host.queryCheckoutForOwner(original.project, request)
                    updateDraft(original.project) { if (it.optString("copyRequestId") == request) it.put("copyStatus", if (observed.optBoolean("found")) "ready" else "unknown") }
                    observed
                }
            }
            "projects.development.review" -> {
                fields(args, "viewHandle", "checkoutId", "goal")
                check(!pending(original.project)) { "请先查询原项目输入，不要重新开始" }
                val checkout = text(args, "checkoutId"); val goal = text(args, "goal", 12000).trim()
                updateDraft(original.project) { it.put("goal", goal).put("checkoutId", checkout) }; review = null
                working(original, done) {
                    val actual = source(original, checkout); val selectedModel = model()
                    check(selectedModel.value().optBoolean("configured")) { "默认模型尚未就绪，请先连接模型；项目与草稿保留" }
                    val input = JSONObject().put("projectId", original.project).put("checkoutId", checkout)
                        .put("expectedSourceDigest", actual.getString("sourceDigest")).put("runtime", "managed_process_v1")
                        .put("clientMessageId", UUID.randomUUID().toString()).put("goal", goal)
                    val selected = Review(UUID.randomUUID().toString(), original, input.toString(), selectedModel.revision(), SystemClock.elapsedRealtime() + 120_000)
                    check(current(original)) { "原项目窗口已变化，没有准备启动" }
                    review = selected
                    JSONObject().put("reviewHandle", selected.handle).put("requestId", input.getString("clientMessageId")).put("projectId", original.project)
                        .put("checkoutId", checkout).put("goal", goal).put("source", actual).put("model", selectedModel.value())
                        .put("runtime", "这台手机").put("expiresAt", System.currentTimeMillis() + 120_000)
                        .put("commandsAuthorized", false).put("projectMemoryAuthorized", false)
                }
            }
            "projects.development.start" -> {
                fields(args, "viewHandle", "reviewHandle")
                check(!busy && !pending(original.project)) { "原项目操作仍待核对" }
                val selected = review
                check(selected != null && selected.handle == text(args, "reviewHandle") && selected.page === original) { "这份项目审阅已关闭或使用" }
                val input = JSONObject(selected.input)
                fun startCurrent(): Boolean {
                    val status = controller.nativeModelSelection().status()
                    return current(original) && SystemClock.elapsedRealtime() < selected.expires && !status.pending() && status.revision() == selected.modelRevision &&
                        draft(original.project).optString("goal") == input.getString("goal") && draft(original.project).optString("checkoutId") == input.getString("checkoutId")
                }
                check(startCurrent()) { "项目、默认模型或审阅期限已变化，请重新核对" }
                review = null; busy = true
                val marker = JSONObject().put("projectId", original.project).put("checkoutId", input.getString("checkoutId"))
                    .put("clientMessageId", input.getString("clientMessageId")).put("goal", input.getString("goal")).put("status", "pending")
                try {
                    // Persist the original query identity before any action can
                    // reach Assistant; a killed Activity cannot turn unknown
                    // admission into permission to replay the goal.
                    updateDraft(original.project, true) { it.put("submission", marker) }
                    controller.startProjectDevelopmentForWeb(input, ::startCurrent) { result, error ->
                        if (page === original) busy = false
                        val receipt = recordSubmission(marker, result, error)
                        done(public(receipt), null)
                    }
                } catch (failure: Exception) {
                    busy = false
                    marker.put("status", "rejected").put("notAdmitted", true).put("message", "本次尚未进入启动队列，请重新核对。").put("error", failure.message)
                    updateDraft(original.project) { it.put("submission", marker) }
                    done(public(marker), null)
                }
            }
            "projects.development.query" -> {
                fields(args, "viewHandle")
                val marker = draft(original.project).optJSONObject("submission")?.let(::copy) ?: error("没有可查询的原项目输入")
                working(original, done) {
                    val result = runCatching { controller.projectDevelopmentForWeb().submissionForOwner(original.project, marker.getString("checkoutId"), marker.getString("clientMessageId")) }
                    recordSubmission(marker, result.getOrNull(), result.exceptionOrNull()?.message)
                }
            }
        }
    }
    fun savePresentation(): JSONArray = synchronized(drafts) { JSONArray().also { saved -> drafts.values.forEach { saved.put(copy(it)) } } }
    fun restorePresentation(saved: JSONArray?) {
        synchronized(drafts) {
        if (saved == null) return
        for (i in 0 until minOf(40, saved.length())) {
            val value = saved.optJSONObject(i) ?: continue
            val project = value.optString("projectId")
            if (!project.matches(Regex("[A-Za-z0-9_-]{1,80}")) || value.optString("goal").length > 12000) continue
            // Durable presentation wins over an older Activity Bundle. Neither
            // source can restore an opaque review or execution authority.
            if (drafts.containsKey(project)) continue
            val retained = JSONObject().put("projectId", project).put("goal", value.optString("goal")).put("checkoutId", value.optString("checkoutId"))
            value.optString("copyRequestId").takeIf { it.matches(Regex("[a-f0-9-]{36}")) }?.let { retained.put("copyRequestId", it).put("copyStatus", if (value.optString("copyStatus") == "ready") "ready" else "unknown") }
            value.optJSONObject("submission")?.takeIf { it.optString("projectId") == project && it.optString("clientMessageId").matches(Regex("[a-f0-9-]{36}")) }?.let {
                val marker = copy(it); if (marker.optString("status") == "pending") marker.put("status", "unknown")
                retained.put("submission", marker)
            }
            drafts[project] = retained
        }
        // Presentation data cannot restore page handles, start reviews or grants.
        }
    }
}
