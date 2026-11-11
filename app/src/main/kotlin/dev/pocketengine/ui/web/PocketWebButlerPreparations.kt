package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.EngineApplication
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import java.util.UUID

/** Presentation of saved proposals. Execution remains in the original phone/project admissions. */
internal class PocketWebButlerPreparations(
    private val controller: PocketWorldController,
    private val ownerCurrent: () -> Boolean,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean
) {
    private data class Page(val handle: String, val source: String, val proposal: String, val digest: String,
        val window: PocketWebWindowTicket, val generation: Long)
    private data class PhoneReview(val handle: String, val page: Page, val native: EngineApplication.ConversationPhoneStartReview, val expires: Long)
    private data class ProjectReview(val handle: String, val page: Page, val native: TaskStore.OwnerWorkPreparation,
        val input: String, val modelRevision: Long, val expires: Long)
    private val main = Handler(Looper.getMainLooper())
    private val host get() = controller.nativeButlerHost()
    private val presentation = controller.butlerPreparationPresentationForWeb()
    @Volatile private var page: Page? = null
    @Volatile private var phone: PhoneReview? = null
    @Volatile private var project: ProjectReview? = null
    @Volatile private var generation = 0L
    private var busy = false
    private var workSequence = 0L

    companion object {
        private val persistence = Any()
        private val unsavedDrafts = mutableSetOf<String>()
        val COMMANDS = setOf("butler.capabilities", "butler.preparations.list", "butler.preparation.read",
            "butler.preparation.phone.review", "butler.preparation.phone.start", "butler.preparation.project.choices",
            "butler.preparation.project.prepare", "butler.preparation.project.queryCopy", "butler.preparation.project.review",
            "butler.preparation.project.start", "butler.preparation.draft", "butler.preparation.query", "butler.preparation.dismiss", "butler.preparation.close")
    }
    fun clear() { generation++; workSequence++; page = null; phone = null; project = null; busy = false }
    private fun current(original: Page) = page === original && generation == original.generation && ownerCurrent() &&
        controller.webPrivateOwnerVisible() && acceptsWindow(original.window)
    private fun fields(args: JSONObject, vararg allowed: String) = require(args.keys().asSequence().all { it in allowed }) { "准备操作包含未声明字段" }
    private fun text(args: JSONObject, key: String, maximum: Int = 240, blank: Boolean = false): String {
        val value = args.opt(key)
        require(value is String && value.length <= maximum && '\u0000' !in value && (blank || value.isNotBlank())) { "请核对$key" }
        return value
    }
    private fun selected(args: JSONObject): Page {
        val original = page
        check(original != null && original.handle == text(args, "viewHandle") && current(original)) { "原准备页面已变化，请重新打开这份提案" }
        return original
    }
    private fun markerKey(original: Page) = "start:${original.source}:${original.proposal}"
    private fun draftKey(original: Page) = "draft:${original.source}:${original.proposal}"
    private fun marker(original: Page): JSONObject? = synchronized(persistence) { presentation.getString(markerKey(original), null)?.let(::JSONObject) }
    private fun draft(original: Page): JSONObject = synchronized(persistence) { presentation.getString(draftKey(original), null)?.let(::JSONObject) ?: JSONObject() }
    private fun draftView(original: Page): JSONObject = synchronized(persistence) { draft(original).also { value ->
        value.put("saved", draftKey(original) !in unsavedDrafts)
        val projectId = value.optString("projectId")
        if (projectId.isNotBlank()) presentation.getString("copy:${original.source}:${original.proposal}:$projectId", null)?.let {
            value.put("copyRequestId", it).put("copyState", "query_required")
        }
    } }
    private fun remember(original: Page, vararg values: Pair<String, String>) = synchronized(persistence) {
        val saved = draft(original); values.forEach { (key, value) -> saved.put(key, value) }
        val key = draftKey(original)
        val committed = presentation.edit().putString(key, saved.toString()).commit()
        if (committed) unsavedDrafts.remove(key) else unsavedDrafts.add(key)
        check(committed) { "这份准备选择未能保存，请核对本机存储" }
    }
    private fun saveMarker(original: Page, value: JSONObject) = synchronized(persistence) {
        check(presentation.edit().putString(markerKey(original), value.toString()).commit()) { "原请求编号未能保存，没有进入新的启动；请核对本机存储" }
    }
    private fun requireNoUnresolvedStart(original: Page) {
        check(marker(original)?.optString("status") !in setOf("pending", "unknown", "accepted")) { "这份提案已有原启动记录，请查询或打开原任务；不会重新派发" }
    }
    private fun public(value: JSONObject) = PocketWebState.publicValue(value) as JSONObject
    private fun work(original: Page?, done: (JSONObject?, String?) -> Unit, effect: Boolean = false, block: () -> JSONObject) {
        check(!busy) { "请等待原准备操作的回执" }; busy = true
        val sequence = ++workSequence
        val window = original?.window ?: captureWindow()
        try {
            controller.runPluginCatalogWork(Runnable {
                val result = runCatching {
                    check(ownerCurrent() && controller.webPrivateOwnerVisible() && acceptsWindow(window) && (original == null || current(original))) { "原准备窗口已关闭，没有执行新的操作" }
                    block()
                }
                main.post {
                    if (sequence == workSequence) busy = false
                    if (!effect && (!acceptsWindow(window) || original != null && !current(original))) done(null, "原准备窗口已变化，请重新读取")
                    else result.fold({ done(public(it), null) }, { done(null, it.message ?: "原准备状态尚不可核对") })
                }
            })
        } catch (failure: Exception) { if (sequence == workSequence) busy = false; throw failure }
    }
    private fun typed(original: Page): TaskStore.OwnerWorkPreparation {
        check(current(original)); requireNoUnresolvedStart(original)
        return host.tasks.reviewOwnerWorkPreparation(original.source, original.proposal, original.digest)
    }
    private fun record(original: Page, input: JSONObject, result: JSONObject?, error: String?): JSONObject {
        val receipt = JSONObject(input.toString())
        val isPhone = input.getString("kind") == "phone"
        val same = result != null && if (isPhone) result.optString("requestId") == input.getString("requestId") &&
            result.optString("conversationId") == input.getString("conversationId") else result.optString("clientMessageId") == input.getString("clientMessageId")
        val accepted = same && !result!!.optString("taskId").isBlank() && (if (isPhone) result.optBoolean("accepted") else result.optString("status") in setOf("accepted", "queued", "consumed"))
        val rejected = same && result!!.optString("taskId").isBlank() && (result.optBoolean("notAdmitted") || isPhone && result.optString("status") == "rejected")
        receipt.put("status", if (accepted) "accepted" else if (rejected) "rejected" else "unknown").put("queryOnly", !rejected)
        if (same) for (key in listOf("taskId", "runId", "epoch", "conversationId", "admitted", "notAdmitted")) if (result!!.has(key)) receipt.put(key, result.get(key))
        receipt.put("message", if (accepted) "原任务已保存，请按真实执行状态查看进度。" else if (rejected) "本次尚未进入任务启动，可以核对条件后重新审阅。" else "原启动回执待核对，请查询原请求；不会再次派发。")
        if (error != null) receipt.put("error", error)
        saveMarker(original, receipt); return receipt
    }
    private fun completeStart(original: Page, input: JSONObject, result: JSONObject?, error: String?, done: (JSONObject?, String?) -> Unit) {
        // These callbacks run on Android's main thread. The pre-dispatch pending
        // identity remains queryable even if the completion cannot be persisted.
        runCatching { public(record(original, input, result, error)) }.fold(
            { done(it, null) },
            { done(null, "原启动回执的保存尚未确认，请查询原请求；不会再次派发。") }
        )
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        check(ownerCurrent() && controller.webPrivateOwnerVisible()) { "请在私人管家入口处理这份工作" }
        if (action == "butler.capabilities") {
            fields(args, "purpose"); work(null, done) { host.ownerWorkCapabilitiesForOwner(args.optString("purpose", "all")) }; return
        }
        if (action == "butler.preparations.list") {
            fields(args, "conversationId", "cursor", "limit")
            val conversation = if (args.has("conversationId")) text(args, "conversationId", blank = true) else ""
            val cursor = if (args.has("cursor")) text(args, "cursor", 4096, true) else ""
            val limit = if (args.has("limit")) args.getInt("limit") else 12; require(limit in 1..20)
            work(null, done) { host.tasks.ownerWorkPreparations(conversation, cursor, limit) }; return
        }
        if (action == "butler.preparation.read") {
            fields(args, "sourceTaskId", "proposalId"); check(!busy)
            val source = text(args, "sourceTaskId"); val proposal = text(args, "proposalId")
            clear(); val window = captureWindow(); val originalGeneration = generation
            work(null, { value, error ->
                if (value != null && generation == originalGeneration && acceptsWindow(window)) {
                    val original = Page(UUID.randomUUID().toString(), source, proposal, value.getString("reviewDigest"), window, generation)
                    page = original; value.put("viewHandle", original.handle).put("preparationDraft", draftView(original)); marker(original)?.let { value.put("originalStart", it) }; done(value, null)
                } else done(null, error ?: "原提案窗口已变化")
            }) { host.tasks.ownerWorkPreparation(source, proposal) }; return
        }
        val original = selected(args)
        when (action) {
            "butler.preparation.draft" -> {
                fields(args, "viewHandle", "mode", "projectId", "checkoutId")
                work(original, done) {
                    val proposed = typed(original).view()
                    if (proposed.getString("kind") == "phone") {
                        require(!args.has("projectId") && !args.has("checkoutId")); val mode = text(args, "mode")
                        require(mode in setOf("main_screen", "background")); remember(original, "mode" to mode)
                    } else {
                        require(!args.has("mode")); val projectId = text(args, "projectId", blank = true)
                        val checkout = if (args.has("checkoutId")) text(args, "checkoutId", blank = true) else ""
                        require(projectId.isNotBlank() || checkout.isBlank())
                        if (projectId.isNotBlank()) {
                            check(controller.state.projects.any { it.id == projectId }) { "请选择当前私人项目" }
                            controller.projectDevelopmentForWeb().inspectForOwner(projectId)
                            if (checkout.isNotBlank()) controller.projectDevelopmentForWeb().sourceForOwner(projectId, checkout)
                        }
                        remember(original, "projectId" to projectId, "checkoutId" to checkout)
                    }
                    JSONObject().put("saved", true).put("preparationDraft", draftView(original))
                }
            }
            "butler.preparation.close" -> { fields(args, "viewHandle"); clear(); done(JSONObject().put("closed", true).put("taskStopped", false), null) }
            "butler.preparation.dismiss" -> {
                fields(args, "viewHandle"); requireNoUnresolvedStart(original)
                work(original, done, true) { host.tasks.dismissOwnerWorkPreparation(original.source, original.proposal, original.digest) }
            }
            "butler.preparation.phone.review" -> {
                fields(args, "viewHandle", "mode"); val mode = text(args, "mode"); require(mode in setOf("main_screen", "background"))
                remember(original, "mode" to mode)
                work(original, done) {
                    val preparation = typed(original)
                    val native = host.prepareOwnerWorkPhone(preparation, mode, JSONObject().put("scopeMinutes", controller.newTaskScopeMinutes)
                        .put("toolLimit", controller.newTaskToolBudget).put("modelCallLimit", controller.newTaskModelBudget))
                    check(current(original)); val retained = PhoneReview(UUID.randomUUID().toString(), original, native, SystemClock.elapsedRealtime() + 120_000)
                    phone = retained; project = null; native.view().put("reviewHandle", retained.handle).put("contract", preparation.view().getJSONObject("contract"))
                }
            }
            "butler.preparation.phone.start" -> {
                fields(args, "viewHandle", "reviewHandle"); check(!busy); requireNoUnresolvedStart(original)
                val reviewed = phone; check(reviewed != null && reviewed.page === original && reviewed.handle == text(args, "reviewHandle") && SystemClock.elapsedRealtime() < reviewed.expires) { "这份手机审阅已关闭或过期" }
                val input = reviewed.native.view().put("kind", "phone").put("status", "pending"); saveMarker(original, input); phone = null
                val accepted = controller.startConversationPhone(reviewed.native, { current(original) && SystemClock.elapsedRealtime() < reviewed.expires }) { result, error ->
                    completeStart(original, input, result, error, done)
                }
                if (!accepted) completeStart(original, input, JSONObject().put("requestId", input.getString("requestId")).put("conversationId", input.getString("conversationId")).put("status", "rejected"), "尚未进入原手机启动", done)
            }
            "butler.preparation.project.choices" -> {
                fields(args, "viewHandle", "projectId"); val projectId = text(args, "projectId")
                remember(original, "projectId" to projectId)
                work(original, done) { typed(original).checkCurrent(); controller.projectDevelopmentForWeb().inspectForOwner(projectId) }
            }
            "butler.preparation.project.prepare", "butler.preparation.project.queryCopy" -> {
                fields(args, "viewHandle", "projectId"); val projectId = text(args, "projectId")
                val request = UUID.nameUUIDFromBytes((original.source + ":" + original.proposal + ":" + projectId).toByteArray(Charsets.UTF_8)).toString()
                val key = "copy:${original.source}:${original.proposal}:$projectId"
                work(original, done, action.endsWith("prepare")) {
                    typed(original).checkCurrent(); val native = controller.projectDevelopmentForWeb()
                    if (action.endsWith("prepare")) {
                        synchronized(persistence) {
                            check(!presentation.contains(key)) { "原副本准备已登记，请查询原请求" }
                            check(presentation.edit().putString(key, request).commit()) { "原副本请求编号未能保存，尚未准备" }
                        }
                        native.prepareCheckoutForOwner(projectId, request)
                    }
                    native.queryCheckoutForOwner(projectId, request).put("registered", synchronized(persistence) { presentation.contains(key) })
                }
            }
            "butler.preparation.project.review" -> {
                fields(args, "viewHandle", "projectId", "checkoutId")
                val projectId = text(args, "projectId"); val checkout = text(args, "checkoutId")
                remember(original, "projectId" to projectId, "checkoutId" to checkout)
                work(original, done) {
                    val preparation = typed(original); val proposed = preparation.view(); check(proposed.getString("kind") == "project")
                    val source = controller.projectDevelopmentForWeb().sourceForOwner(projectId, checkout)
                    val model = controller.nativeModelSelection().readStable { controller.projectDevelopmentModelForWeb() }
                    check(model.value().optBoolean("configured")) { "模型尚未就绪，提案和项目选择仍可保留" }
                    val input = JSONObject().put("projectId", projectId).put("checkoutId", checkout).put("expectedSourceDigest", source.getString("sourceDigest"))
                        .put("runtime", "managed_process_v1").put("clientMessageId", proposed.getString("clientMessageId")).put("goal", proposed.getJSONObject("contract").getString("goal"))
                    check(current(original)); val retained = ProjectReview(UUID.randomUUID().toString(), original, preparation, input.toString(), model.revision(), SystemClock.elapsedRealtime() + 120_000)
                    project = retained; phone = null
                    JSONObject().put("reviewHandle", retained.handle).put("projectId", projectId).put("checkoutId", checkout).put("source", source).put("model", model.value())
                        .put("contract", proposed.getJSONObject("contract")).put("goal", input.getString("goal")).put("runtime", "这台手机").put("commandsAuthorized", false).put("projectMemoryAuthorized", false)
                }
            }
            "butler.preparation.project.start" -> {
                fields(args, "viewHandle", "reviewHandle"); check(!busy); requireNoUnresolvedStart(original)
                val reviewed = project; check(reviewed != null && reviewed.page === original && reviewed.handle == text(args, "reviewHandle")) { "这份项目审阅已关闭或使用" }
                fun startCurrent(): Boolean { val state = controller.nativeModelSelection().status(); return current(original) && SystemClock.elapsedRealtime() < reviewed.expires && !state.pending() && state.revision() == reviewed.modelRevision }
                check(startCurrent()) { "原项目或模型审阅已变化" }; val input = JSONObject(reviewed.input)
                val marker = JSONObject(input.toString()).put("kind", "project").put("status", "pending"); saveMarker(original, marker); project = null
                controller.startPreparedProjectForWeb(reviewed.native, input, ::startCurrent) { result, error -> completeStart(original, marker, result, error, done) }
            }
            "butler.preparation.query" -> {
                fields(args, "viewHandle")
                val originalStart = marker(original)
                work(original, done) {
                    if (originalStart == null) host.tasks.ownerWorkPreparation(original.source, original.proposal)
                    else {
                        val result = if (originalStart.getString("kind") == "phone") host.queryConversationPhoneStart(originalStart.getString("requestId"), originalStart.getString("conversationId"))
                            else controller.projectDevelopmentForWeb().submissionForOwner(originalStart.getString("projectId"), originalStart.getString("checkoutId"), originalStart.getString("clientMessageId"))
                        record(original, originalStart, result, null)
                    }
                }
            }
            else -> error("未知准备操作")
        }
    }
}
