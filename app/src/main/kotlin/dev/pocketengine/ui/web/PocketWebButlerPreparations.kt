package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.workspace.WorkspaceModule
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONArray
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
    private data class PhoneReview(val handle: String, val page: Page, val native: EngineApplication.ConversationPhoneStartReview, val expires: Long, val conditions: JSONObject)
    private data class ProjectReview(val handle: String, val page: Page, val native: TaskStore.OwnerWorkPreparation,
        val input: String, val modelRevision: Long, val expires: Long)
    private val preflight = PrivatePhoneStartConditions(controller)
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
            "butler.preparation.phone.review", "butler.preparation.phone.settings", "butler.preparation.phone.start", "butler.preparation.project.choices",
            "butler.preparation.project.prepare", "butler.preparation.project.queryCopy", "butler.preparation.project.review", "butler.preparation.project.savedRevisions",
            "butler.preparation.project.start", "butler.preparation.project.followup", "butler.preparation.draft", "butler.preparation.query", "butler.preparation.dismiss", "butler.preparation.close")
    }
    // No draft bodies, file metadata list, output bytes or fresh commands leave this lane.
    private fun published(row:JSONObject):JSONArray {
        val target=row.optJSONObject("target") ?: return JSONArray()
        val draft=target.optString("originalProjectDraftId")
        if(!draft.matches(Regex("[a-f0-9]{64}")))return JSONArray()
        val workspace=host.features.module("workspace") as? WorkspaceModule ?: return JSONArray()
        return workspace.retainedDraftCopyReferencesForHost(target.getString("taskId"),draft,true)
    }
    private fun visibleRequests(conversation:String):Map<String,Long> {
        val visible=mutableMapOf<String,Long>()
        if(conversation.isEmpty()||controller.state.conversationId!=conversation)return visible
        for(message in controller.state.messages){
            val id=message.id ?: continue
            if(message.role!="user")continue
            val locator=message.ownerOriginalLocator
            if(visible.containsKey(id)||locator?.optString("kind")!="owner_native_original"||
                locator.optString("conversationId")!=conversation||locator.optString("recordId")!=id||
                message.sourceRevision<=0||locator.optLong("recordVersion",-1)!=message.sourceRevision)visible[id]=-1
            else visible[id]=message.sourceRevision
        }
        return visible
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
        val accepted = same && !result!!.optString("taskId").isBlank() && (if (isPhone) result.optBoolean("accepted") && result.optString("taskId") == input.getString("requestId") else result.optString("status") in setOf("accepted", "queued", "consumed"))
        if (isPhone && input.optString("status") == "accepted" && input.optString("taskId") == input.optString("requestId")) {
            val pinned = input.optString("admissionState") in setOf("admitted", "service_denied", "component_unconfirmed")
            if (!accepted || input.optInt("epoch", -1) == 1 &&
                (result.optInt("epoch", -1) != 1 || result.optString("runId") != input.optString("runId") ||
                    pinned && result.optString("admissionState") != input.optString("admissionState"))) {
                // A missing read or a contradictory stamp must not replace an already observed, immutable first admission.
                return receipt.put("latestReadUnconfirmed", true)
            }
        }
        receipt.remove("latestReadUnconfirmed")
        val rejected = same && result!!.optString("taskId").isBlank() && (result.optBoolean("notAdmitted") || isPhone && result.optString("status") == "rejected")
        receipt.put("status", if (accepted) "accepted" else if (rejected) "rejected" else "unknown").put("queryOnly", !rejected)
        if (same) for (key in listOf("taskId", "runId", "epoch", "conversationId", "admitted", "notAdmitted")) if (result!!.has(key)) receipt.put(key, result.get(key))
        receipt.put("message", if (accepted) "原任务已保存，请按真实执行状态查看进度。" else if (rejected) "本次尚未进入任务启动，可以核对条件后重新审阅。" else "原启动回执待核对，请查询原请求；不会再次派发。")
        if (isPhone) {
            // The source is the canonical, first-run SQLite receipt, not the current task header or a model claim.
            listOf("taskId","runId","epoch","admitted","notAdmitted","admissionState","taskStatus","error").forEach { receipt.remove(it) }
            if (accepted) {
                receipt.put("taskId", result.getString("taskId"))
                val firstRun=result.optString("runId").isNotBlank() && result.optInt("epoch",-1)==1
                if(firstRun)listOf("runId","epoch","admitted").forEach { key -> if(result.has(key))receipt.put(key,result.get(key)) }
                val phase=result.optString("admissionState")
                if((firstRun && phase in setOf("admitted","service_denied","component_unconfirmed","run_recorded")) ||
                    (!firstRun && phase in setOf("no_run","unconfirmed")))receipt.put("admissionState",phase)
                if(firstRun && result.optString("status") in setOf("preparing","running","completed","cancelled","stopped","failed","paused","awaiting_user","needs_confirmation","interrupted","reconciling"))
                    receipt.put("taskStatus",result.getString("status"))
            }
        }
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
    fun dispatch(action: String, args: JSONObject, plainChat: Boolean = false, done: (JSONObject?, String?) -> Unit) {
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
            // IDs come from the current, native private chat, never a model or JS list.
            val visible = if(plainChat)visibleRequests(conversation) else emptyMap()
            work(null, done) {
                val page=host.tasks.ownerWorkPreparations(conversation,cursor,limit,visible)
                val entries=page.getJSONArray("entries")
                for(i in 0 until entries.length()){
                    val row=entries.getJSONObject(i);val source=row.optJSONObject("visibleOwnerRequest") ?: continue
                    try {
                        val candidates=published(row)
                        if(candidates.length()==0)continue
                        val current=host.tasks.originalChatProjectWork(row.getString("sourceTaskId"),row.getString("proposalId"),
                            conversation,source.getString("messageId"),source.getLong("revision"),candidates)
                        row.putOpt("chatOriginalBuild",current.optJSONObject("chatOriginalBuild"))
                    }catch(failure:Exception){if(Thread.currentThread().isInterrupted)throw failure}
                }
                page
            }; return
        }
        if(action=="butler.preparation.project.followup"){
            fields(args,"sourceTaskId","proposalId","conversationId","messageId","revision")
            val conversation=text(args,"conversationId");val message=text(args,"messageId")
            val revision=args.opt("revision")
            require(plainChat&&(revision is Int||revision is Long)){"请从原私人消息打开这份工作"}
            val version=(revision as Number).toLong()
            check(version>0&&visibleRequests(conversation)[message]==version){"请回到这条原话重新打开原工作"}
            val task=text(args,"sourceTaskId");val proposal=text(args,"proposalId")
            work(null,{ result,error->
                if(visibleRequests(conversation)[message]!=version)done(null,"已离开原话，没有打开其他工作")
                else done(result,error)
            }){
                val exact=host.tasks.originalChatProjectWork(task,proposal,conversation,message,version,JSONArray())
                val candidates=published(exact)
                host.tasks.originalChatProjectWork(task,proposal,conversation,message,version,candidates)
            }; return
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
                            controller.projectDevelopmentForWeb().inspectForOwner(projectId, false)
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
                    val started = SystemClock.elapsedRealtime()
                    val native = host.prepareOwnerWorkPhone(preparation, mode, JSONObject().put("scopeMinutes", controller.newTaskScopeMinutes)
                        .put("toolLimit", controller.newTaskToolBudget).put("modelCallLimit", controller.newTaskModelBudget))
                    val conditions = preflight.read(mode)
                    val expires = started + 120_000
                    check(current(original) && SystemClock.elapsedRealtime() < expires) { "原手机提案或本次核对已变化，请重新核对" }
                    val retained = PhoneReview(UUID.randomUUID().toString(), original, native, expires, conditions)
                    phone = retained; project = null
                    native.view().put("reviewHandle", retained.handle).put("contract", preparation.view().getJSONObject("contract"))
                        .put("conditions",JSONObject(conditions.toString())).put("expiresInMs",(expires-SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                }
            }
            "butler.preparation.phone.settings" -> {
                fields(args,"viewHandle","reviewHandle","target")
                val reviewed=phone
                check(!busy && current(original) && reviewed!=null && reviewed.page===original && reviewed.handle==text(args,"reviewHandle") && SystemClock.elapsedRealtime()<reviewed.expires) { "请重新核对这份原手机提案" }
                val opened=preflight.openSetting(reviewed.conditions,text(args,"target"));phone=null;done(opened,null)
            }
            "butler.preparation.phone.start" -> {
                fields(args, "viewHandle", "reviewHandle"); check(!busy); requireNoUnresolvedStart(original)
                val reviewed = phone; check(reviewed != null && reviewed.page === original && reviewed.handle == text(args, "reviewHandle") && SystemClock.elapsedRealtime() < reviewed.expires) { "这份手机审阅已关闭或过期" }
                // Screen work remains deferred until actually needed; Android still checks notification and slot again at real admission.
                check(PrivatePhoneStartConditions.canSubmit(reviewed.conditions)) { "上次核对未确认通知和执行位置；没有提交目标，请先重新核对" }
                val input = reviewed.native.view().put("kind", "phone").put("status", "pending"); saveMarker(original, input); phone = null
                val accepted = controller.startConversationPhone(reviewed.native, { current(original) && SystemClock.elapsedRealtime() < reviewed.expires }, reviewed.conditions.getJSONObject("capacity")) { result, error ->
                    completeStart(original, input, result, error, done)
                }
                if (!accepted) completeStart(original, input, JSONObject().put("requestId", input.getString("requestId")).put("conversationId", input.getString("conversationId")).put("status", "rejected"), "尚未进入原手机启动", done)
            }
            "butler.preparation.project.choices" -> {
                fields(args, "viewHandle", "projectId"); val projectId = text(args, "projectId")
                remember(original, "projectId" to projectId)
                work(original, done) { typed(original).checkCurrent(); controller.projectDevelopmentForWeb().inspectForOwner(projectId, false) }
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
            "butler.preparation.project.savedRevisions" -> {
                fields(args,"viewHandle","cursor"); project=null
                val cursor=if(args.has("cursor"))text(args,"cursor",4096,true) else ""
                work(original,done){host.tasks.priorOwnerProjectRevisions(typed(original),cursor)}
            }
            "butler.preparation.project.review" -> {
                project=null
                fields(args, "viewHandle", "projectId", "checkoutId", "draftOnly", "sourceKind", "ownerRevision")
                if(args.has("ownerRevision") || args.has("sourceKind")) require(args.opt("sourceKind")=="owner_saved_revision" && args.optJSONObject("ownerRevision")!=null) { "请明确选中一个原保存版本；不会退回工程副本" }
                val pinned = if(args.has("ownerRevision")) dev.pocketengine.features.local.project.OwnerRevisionSource.pin(args.getJSONObject("ownerRevision")) else null
                val draftOnly = args.opt("draftOnly") as? Boolean ?: error("请明确选择本次项目交付方式")
                if(pinned!=null)require(draftOnly && !args.has("projectId") && !args.has("checkoutId")) { "只能选择一份已存稿，不能同时读取工程" }
                else remember(original, "projectId" to text(args,"projectId"), "checkoutId" to text(args,"checkoutId"))
                work(original, done) {
                    val preparation = typed(original); val proposed = preparation.view(); check(proposed.getString("kind") == "project")
                    val source = if(pinned!=null)host.tasks.reviewOwnerProjectRevision(preparation,pinned)
                        else controller.projectDevelopmentForWeb().sourceForOwner(text(args,"projectId"),text(args,"checkoutId"))
                    val projectId=source.getString("projectId");val checkout=source.getString("checkoutId")
                    val conversation=proposed.getString("sourceConversationId")
                    val reportUnconfigured=plainChat && controller.state.conversationId==conversation && draftOnly && pinned==null &&
                        proposed.opt("canPrepare")==true
                    val selection=controller.nativeModelSelection()
                    val model=selection.readStable { controller.projectDevelopmentModelForWeb(pinned!=null,reportUnconfigured) }
                    if(reportUnconfigured && model.value().opt("defaultUnconfigured")==true){
                        preparation.checkCurrent();requireNoUnresolvedStart(original)
                        val revision=selection.status()
                        check(current(original) && controller.state.conversationId==conversation &&
                            !revision.pending() && revision.revision()==model.revision()) { "原项目或模型审阅已变化" }
                        // A prerequisite is a read result. It carries no review handle or model/source contents.
                        return@work JSONObject().put("status","blocked").put("prerequisite","default_model_unconfigured")
                            .put("kind","project").put("draftOnly",true).put("executionAuthorized",false)
                            .put("sourceTaskId",original.source).put("proposalId",original.proposal).put("sourceConversationId",conversation)
                            .put("sourceRunId",proposed.getString("sourceRunId")).put("sourceEpoch",proposed.getInt("sourceEpoch"))
                            .put("reviewDigest",original.digest).put("projectId",projectId).put("checkoutId",checkout)
                            .put("modelSelectionRevision",model.revision())
                    }
                    check(model.value().optBoolean("configured")) { "模型尚未就绪，提案和项目选择仍可保留" }
                    val input = JSONObject().put("projectId", projectId).put("checkoutId", checkout).put("expectedSourceDigest", source.getString("sourceDigest"))
                        .put("runtime", "managed_process_v1").put("clientMessageId", proposed.getString("clientMessageId")).put("goal", proposed.getJSONObject("contract").getString("goal"))
                    if (draftOnly) input.put("draftOnly", true)
                    val display=JSONObject(model.value().toString())
                    if(pinned!=null){input.put("ownerRevision",pinned).put("revisionRecipient",display.getString("reviewPin"));display.remove("reviewPin")}
                    check(current(original)); val retained = ProjectReview(UUID.randomUUID().toString(), original, preparation, input.toString(), model.revision(), SystemClock.elapsedRealtime() + 120_000)
                    project = retained; phone = null
                    JSONObject().put("reviewHandle", retained.handle).put("projectId", projectId).put("checkoutId", checkout).put("source", source).put("model", display)
                        .put("sourceTaskId",original.source).put("proposalId",original.proposal).put("sourceConversationId",proposed.getString("sourceConversationId"))
                        .put("contract", proposed.getJSONObject("contract")).put("goal", input.getString("goal")).put("runtime", "这台手机").put("draftOnly", draftOnly).put("commandsAuthorized", false).put("projectMemoryAuthorized", false)
                }
            }
            "butler.preparation.project.start" -> {
                fields(args, "viewHandle", "reviewHandle"); check(!busy); requireNoUnresolvedStart(original)
                val reviewed = project; check(reviewed != null && reviewed.page === original && reviewed.handle == text(args, "reviewHandle")) { "这份项目审阅已关闭或使用" }
                fun startCurrent(): Boolean { val state = controller.nativeModelSelection().status(); return current(original) && SystemClock.elapsedRealtime() < reviewed.expires && !state.pending() && state.revision() == reviewed.modelRevision }
                check(startCurrent()) { "原项目或模型审阅已变化" }; val input = JSONObject(reviewed.input)
                val marker = JSONObject(input.toString()).put("kind", "project").put("status", "pending"); marker.remove("revisionRecipient"); saveMarker(original, marker); project = null
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
                        if(originalStart.has("ownerRevision") && result.optString("taskId").isNotBlank())
                            host.tasks.requireOwnerRevisionSubmission(result.getString("taskId"),original.source,original.proposal,originalStart.getString("clientMessageId"),originalStart.getString("expectedSourceDigest"),originalStart.getJSONObject("ownerRevision"))
                        record(original, originalStart, result, null)
                    }
                }
            }
            else -> error("未知准备操作")
        }
    }
}
