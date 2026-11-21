package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.local.project.OwnerDraftFirstBuild
import dev.pocketengine.features.local.project.ProjectOwnerWorkflows
import dev.pocketengine.features.local.project.ProjectSources
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
    private data class ReturnToDraft(val handle:String,val project:String,val checkout:String,val draftDigest:String,val until:Long)
    private class Page(val handle: String, val project: String, val window: PocketWebWindowTicket, val generation: Long, val requested:ReturnToDraft?=null) {
        // Set only when this specific page actually reads the native draft. Guarded by the shared draft monitor.
        var shownSeal:String?=null
    }
    private data class Review(val handle: String, val page: Page, val input: String, val picked:ProjectConventionDraft.Bound, val modelRevision: Long, val expires: Long, val heldBuildDraftDigest:String?=null)
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
        private val deliveryFields = arrayOf("constraints", "deliverable", "completionCriteria")
        val COMMANDS = setOf("projects.development.read", "projects.development.draft", "projects.development.inspect",
            "projects.development.taskPackageSource", "projects.development.resolveTaskPackageSource",
            "projects.development.hostSource", "projects.development.resolveHostSource", "projects.development.compareHostSource", "projects.development.readHostDiff",
            "projects.development.prepare", "projects.development.queryCopy", "projects.development.reviewDraftBuild", "projects.development.review",
            "projects.development.start", "projects.development.query", "projects.development.dismiss", "projects.development.close") + PocketWebCheckoutFiles.COMMANDS
    }
    // Only a transient comparison guard for the already-authorized ordinary project reader. No Book, body or grant.
    @Volatile private var stagedReturn: ReturnToDraft? = null
    fun invalidateDraftReturn() { synchronized(drafts){ stagedReturn=null } }
    private fun requireReturn(project:String,handle:String):ReturnToDraft = synchronized(drafts){
        val held=stagedReturn
        check(held!=null && held.project==project && held.handle==handle && SystemClock.elapsedRealtime()<held.until &&
            !pending(project) && restoreFailure==null && dev.pocketengine.features.local.project.ProjectSources.digest(draft(project))==held.draftDigest) {
            "刚才那份原草稿已变化、过期或不再待交办。未打开其他副本或草稿。"
        }
        held
    }
    fun clear() { generation++; page = null; review = null; busy = false; files.clear() }
    private fun public(value: JSONObject) = PocketWebState.publicValue(value) as JSONObject
    private fun fields(value: JSONObject, vararg allowed: String) = require(value.keys().asSequence().all { it in allowed }) { "项目操作包含未声明字段" }
    private fun text(value: JSONObject, key: String, maximum: Int = 80, blank: Boolean = false): String {
        val found = value.opt(key)
        require(found is String && found.length <= maximum && '\u0000' !in found && (blank || found.isNotBlank())) { "请核对$key" }
        return found
    }
    private fun copyDeliveryFields(from: JSONObject, into: JSONObject): JSONObject {
        deliveryFields.forEach { key -> into.put(key, if (from.has(key)) text(from, key, 4000, true) else "") }
        return into
    }
    private fun sameDraft(left: JSONObject, right: JSONObject): Boolean =
        left.optString("goal") == right.optString("goal") && left.optString("checkoutId") == right.optString("checkoutId") &&
            deliveryFields.all { left.optString(it) == right.optString(it) }
    private fun untouchedBuildDraft(d:JSONObject,checkout:String)=ProjectConventionDraft.present(d)==null &&
        (d.optString("checkoutId").isEmpty() || d.optString("checkoutId")==checkout) && d.optString("goal").isEmpty() && deliveryFields.all { d.optString(it).isEmpty() }
    private fun buildDraftDigest(d:JSONObject) = ProjectSources.digest(copyDeliveryFields(d,JSONObject()
        .put("projectId",d.getString("projectId")).put("checkoutId",d.optString("checkoutId")).put("goal",d.optString("goal"))
        .put("conventionSeal",ProjectConventionDraft.bind(d).seal)))
    private fun matchesInput(d:JSONObject,input:JSONObject,selected:Review):Boolean =
        if(input.has(OwnerDraftFirstBuild.FIELD)) selected.heldBuildDraftDigest==buildDraftDigest(d)
        else selected.heldBuildDraftDigest==null && sameDraft(d,input) && selected.picked.matches(d)
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
            copyDeliveryFields(JSONObject(), JSONObject().put("projectId", project).put("goal", "").put("checkoutId", ""))
        })
    }
    private fun updateDraft(project: String, durable: Boolean = false, change: (JSONObject) -> Unit) = synchronized(drafts) {
        check(restoreFailure == null) { restoreFailure ?: "本机项目输入引用不可用" }
        if (!drafts.containsKey(project) && drafts.size >= 40) {
            val disposable = drafts.entries.firstOrNull { (_, value) ->
                value.optString("goal").isBlank() && deliveryFields.all { value.optString(it).isBlank() } && value.optJSONObject("submission")?.optString("status") !in setOf("pending", "unknown", "unconfirmed") &&
                    (!value.has("copyRequestId") || value.optString("copyStatus") == "ready")
            }?.key
            check(disposable != null) { "有 40 个项目保留输入或未决准备，请先核对已有请求；没有丢弃草稿或未决记录" }
            drafts.remove(disposable) // Only presentation; original tasks and files stay in their stores.
        }
        val value = draft(project); change(value); drafts[project] = copy(value)
        val editor = presentation.edit().putString("drafts", savePresentation().toString())
        if (durable) check(editor.commit()) { "原请求引用没有确认保存，没有开始执行；请核对本机存储空间" } else editor.apply()
    }
    private fun shownDraft(original:Page):JSONObject = synchronized(drafts){
        val value=draft(original.project)
        original.requested?.let { guard ->
            check(requireReturn(original.project,guard.handle)===guard && guard.checkout==value.optString("checkoutId")) { "原项目草稿定位已失效" }
            stagedReturn=null // Successful native snapshot consumes the hint. No second, less restricted read on failure.
        }
        original.shownSeal=ProjectConventionDraft.bind(value).seal;ProjectConventionDraft.ownerView(value)
    }
    private fun editShownDraft(original:Page,durable:Boolean=false,change:(JSONObject)->Unit):ProjectConventionDraft.Bound = synchronized(drafts){
        updateDraft(original.project,durable){value->
            check(!pending(original.project) && ProjectConventionDraft.bind(value).seal==original.shownSeal) { "原草稿或手写约定已在另一窗口变化；请重读原项目，未用旧页面覆盖。" }
            change(value)
        }
        ProjectConventionDraft.bind(draft(original.project)).also { original.shownSeal=it.seal }
    }
    /** Called only with the exact row freshly resolved by the owner memory screen. No Book reader
     * is installed here. Return only the destination and version; the current draft is private. */
    fun conventionDraft(origin: JSONObject, row: JSONObject, expectedStamp: String?): JSONObject = synchronized(drafts) {
        check(restoreFailure == null && !busy && review == null) { "项目草稿正在使用或需要恢复，请先回原项目核对" }
        val mark = ProjectConventionDraft.source(origin, row)
        val project = mark.getString("projectId"); val checkout = mark.getString("checkoutId")
        val before = draft(project)
        check(!pending(project) && before.optString("checkoutId").let { it.isEmpty() || it == checkout }) {
            "原草稿已选另一副本或启动结果待核对，没有覆盖。请到原项目处理。"
        }
        val transfer=ProjectConventionDraft.transfer(before,mark)
        val stamp = dev.pocketengine.features.local.project.ProjectSources.digest(JSONObject().put("origin", origin)
            .put("selected", mark).put("draft", before))
        val reply = JSONObject().put("projectId",project).put("checkoutId",checkout).put("name",origin.getString("name"))
            .put("revision",mark.getLong("revision")).put("taskStarted",false).put("modelCalled",false).put("transfer",transfer.kind)
        transfer.fromRevision?.let { reply.put("fromRevision",it).put("oldChars",transfer.oldChars) }
        if (expectedStamp == null) return@synchronized reply.put("stamp",stamp).put("written",false)
        check(transfer.kind!="same_text" && stamp == expectedStamp) { "原草稿、已选约定或项目副本已变化，未放入本次限制" }
        updateDraft(project, true) { it.put("checkoutId",checkout).put("constraints",row.getString("text")).put(ProjectConventionDraft.KEY,mark) }
        val saved = draft(project)
        check(ProjectConventionDraft.present(saved)?.toString() == mark.toString() && saved.getString("constraints") == row.getString("text")) {
            "草稿写入结果待核对，请在原项目核对，不要重复放入"
        }
        val target=ReturnToDraft(UUID.randomUUID().toString(),project,checkout,
            dev.pocketengine.features.local.project.ProjectSources.digest(saved),SystemClock.elapsedRealtime()+120_000L)
        stagedReturn=target
        reply.put("written",true).put("draftReturnHandle",target.handle).put("draftReturnExpiresAt",System.currentTimeMillis()+120_000L)
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
    private fun source(original: Page, checkout: String) = controller.projectDevelopmentForWeb().sourceForOwner(original.project, checkout, true)
    private fun model() = controller.nativeModelSelection().readStable { controller.projectDevelopmentModelForWeb() }
    private fun metadata(original: Page): JSONObject {
        val actual = controller.projectDevelopmentForWeb().inspectForOwner(original.project)
        if (actual.getJSONArray("checkouts").length() == 0) {
            // No guessed absence from the catalog snapshot; only the phone import's original durable locator can prove this.
            val recovery = runCatching { controller.projectPackages.pendingWebSourceForProject(original.project) }.getOrNull()
            actual.putOpt("phoneSourceRecovery", recovery)
        }
        val selectedModel = model()
        return actual.put("viewHandle", original.handle).put("model", selectedModel.value()).put("modelRevision", selectedModel.revision())
            .put("draft", shownDraft(original)).put("commandsAuthorized", false)
            .also { out -> original.requested?.let { out.put("openedDraftReturn",it.handle) } }
            .put("draftRecoveryRequired", restoreFailure != null).put("draftRecoveryMessage", restoreFailure ?: "")
    }
    private fun recordSubmission(original: JSONObject, result: JSONObject?, error: String?, viewer:Page): JSONObject {
        val observed = copy(original).also { it.remove("draftSourceNeedsReview") }
        val independentBuild=original.opt("independentNodeBuild")==true
        val same = result != null && result.optString("clientMessageId") == original.getString("clientMessageId")
        val accepted = same && !result!!.optString("taskId").isBlank() && result.optString("status") in setOf("accepted", "queued", "consumed")
        val rejected = same && result!!.optBoolean("notAdmitted") && result.optString("taskId").isBlank()
        observed.put("status", if (accepted) "accepted" else if (rejected) "rejected" else "unknown")
            .put("message", if (accepted && independentBuild) "构建交办已保存，项目草稿原样保留。运行命令仍需单独确认。"
                else if (accepted) "项目任务已保存。运行命令仍按原任务的实际方案审批。"
                else if (rejected) "本次没有启动项目任务，请核对后重新审阅。"
                else "项目输入回执待核对，请查询原输入，不要重新提交同一目标。")
        if (same) for (key in listOf("taskId", "conversationId", "runId", "epoch", "messageId", "notAdmitted")) if (result!!.has(key)) observed.put(key, result.get(key))
        if (error != null) observed.put("error", error)
        updateDraft(original.getString("projectId")) { retained ->
            val sameSubmission=retained.optJSONObject("submission")?.optString("clientMessageId")==original.getString("clientMessageId")
            val seal=ProjectConventionDraft.bind(retained).seal
            // Legacy queries never mint an owner selection. Keep any current picked copy whose original cannot be proved.
            val sameSource=original.optString(ProjectConventionDraft.SUBMISSION_SEAL,"no_owner_source")==seal
            if(accepted && !independentBuild && (!sameSubmission || !sameSource))observed.put("draftSourceNeedsReview",true)
                .put("message","已核对原任务受理。当前草稿的约定与原提交不同或无法核对，未将它清除；请回原项目查看。")
            if (sameSubmission) {
                retained.put("submission", observed)
                if (accepted && !independentBuild && sameSource && sameDraft(retained, original)) {
                    retained.remove(ProjectConventionDraft.KEY); retained.put("goal", ""); deliveryFields.forEach { retained.put(it, "") }
                    if(page===viewer && viewer.shownSeal==seal)viewer.shownSeal=ProjectConventionDraft.bind(retained).seal
                }
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
            fields(args, "projectId", "draftReturnHandle"); val project = text(args, "projectId")
            check(ownerCurrent() && controller.webPrivateOwnerVisible() && controller.state.projectId == project &&
                controller.state.projects.any { it.id == project }) { "请打开当前私人项目后再读取" }
            val target=if(args.has("draftReturnHandle"))requireReturn(project,text(args,"draftReturnHandle")) else null
            clear(); val original = Page(UUID.randomUUID().toString(), project, captureWindow(), generation,target); page = original
            working(original, done) { metadata(original) }; return
        }
        val original = selected(args)
        if (action in PocketWebCheckoutFiles.COMMANDS) {
            check(review == null && !busy) { "请先完成或关闭原项目审阅" }
            files.dispatch(action, args, original.project, { current(original) }, done); return
        }
        if (action in setOf("projects.development.draft", "projects.development.prepare", "projects.development.reviewDraftBuild", "projects.development.review", "projects.development.start"))
            check(restoreFailure == null) { restoreFailure ?: "本机项目输入引用不可用" }
        when (action) {
            "projects.development.dismiss" -> {
                fields(args, "viewHandle", "reviewHandle")
                if (review?.handle == text(args, "reviewHandle")) review = null
                done(JSONObject().put("dismissed", true).put("taskStopped", false), null)
            }
            "projects.development.draft" -> {
                fields(args, "viewHandle", "goal", "checkoutId", "dropSelectedSource", "verifySavedForTools", *deliveryFields)
                require(!args.has("dropSelectedSource") || args.opt("dropSelectedSource")==true)
                require(!args.has("verifySavedForTools") || args.opt("verifySavedForTools")==true && !args.has("dropSelectedSource"))
                val drop=args.opt("dropSelectedSource")==true
                val goal = text(args, "goal", 12000, true); val checkout = text(args, "checkoutId", 80, true)
                val retainedFields = copyDeliveryFields(args, JSONObject())
                check(!busy && !pending(original.project)) { "原项目输入仍待核对，草稿没有替换" }
                if (args.opt("verifySavedForTools") == true) {
                    check(review == null) { "请先返回原项目草稿" }
                    val shown = retainedFields.put("goal", goal).put("checkoutId", checkout)
                    working(original, done) {
                        synchronized(drafts) {
                            val saved = draft(original.project)
                            check(!pending(original.project) && saved.optString("projectId") == original.project &&
                                sameDraft(saved, shown) && ProjectConventionDraft.bind(saved).seal == original.shownSeal) {
                                "本页目标、要求或副本与已保存的原稿还没核对一致。文字仍在这里，没有离开、覆盖或提交。"
                            }
                            // Finish persisting the exact existing native draft. The verification path
                            // must never write caller text back or replay a previously submitted action.
                            check(presentation.edit().putString("drafts", savePresentation().toString()).commit()) {
                                "尚未确认草稿已写入手机存储。请保留本页文字后再核对；没有打开工具准备。"
                            }
                            JSONObject().put("projectId", original.project).put("checkoutId", checkout)
                                .put("draftVerifiedForTools", true).put("taskStarted", false)
                        }
                    }
                    return
                }
                editShownDraft(original,drop) { stored ->
                    val selected = ProjectConventionDraft.present(stored)
                    check(selected == null || selected.getString("checkoutId") == checkout ||
                        retainedFields.getString("constraints") != stored.getString("constraints")) { "更换副本前请先移除这个副本的约定" }
                    if(drop) {
                        check(selected!=null && selected.getString("checkoutId")==checkout) { "所选来源已经变化，未覆盖原草稿。" }
                        // Explicit conflict resolution replaces only the source field, not other pending owner edits.
                        stored.put("constraints",retainedFields.getString("constraints")).remove(ProjectConventionDraft.KEY)
                    } else copyDeliveryFields(retainedFields, stored.put("goal", goal).put("checkoutId", checkout))
                    if (ProjectConventionDraft.present(stored) == null) stored.remove(ProjectConventionDraft.KEY)
                }; review = null
                done(JSONObject().put("saved", true).put("projectId", original.project).put("removedSelectedSource",drop), null)
            }
            "projects.development.readHostDiff" -> {
                fields(args, "viewHandle", "checkoutId", "evidenceDigest", "path", "originalSha256", "phoneSha256")
                check(review == null) { "请先关闭原项目审阅" }
                val checkout = text(args, "checkoutId"); val path = text(args, "path", 240)
                fun hash(key: String) = text(args, key, 64).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                val evidence = hash("evidenceDigest"); val host = hash("originalSha256"); val phone = hash("phoneSha256")
                working(original, done) { controller.projectDevelopmentForWeb().readChangedHostTextForOwner(original.project, checkout, evidence, path, host, phone) { current(original) } }
            }
            "projects.development.compareHostSource" -> {
                fields(args, "viewHandle", "checkoutId", "evidenceDigest")
                check(review == null) { "请先关闭原项目审阅" }
                val checkout = text(args, "checkoutId"); val evidence = text(args, "evidenceDigest", 64)
                require(evidence.matches(Regex("[a-f0-9]{64}")))
                working(original, done) { controller.projectDevelopmentForWeb().compareHostSourceForOwner(original.project, checkout, evidence) { current(original) } }
            }
            "projects.development.taskPackageSource","projects.development.resolveTaskPackageSource" -> {
                val opening=action=="projects.development.resolveTaskPackageSource"
                if(opening)fields(args,"viewHandle","checkoutId","evidenceDigest") else fields(args,"viewHandle","checkoutId")
                check(review==null){ "请先关闭当前项目审阅" }
                val checkout=text(args,"checkoutId")
                val expected=if(opening)text(args,"evidenceDigest",64).also{require(it.matches(Regex("[a-f0-9]{64}")))} else null
                working(original,done){controller.projectDevelopmentForWeb().taskSourceOriginForOwner(original.project,checkout,expected)}
            }
            "projects.development.hostSource", "projects.development.resolveHostSource" -> {
                val opening = action == "projects.development.resolveHostSource"
                if (opening) fields(args, "viewHandle", "checkoutId", "evidenceDigest") else fields(args, "viewHandle", "checkoutId")
                check(review == null) { "请先关闭原项目审阅" }
                val checkout = text(args, "checkoutId")
                val expected = if (opening) text(args, "evidenceDigest", 64).also { require(it.matches(Regex("[a-f0-9]{64}"))) } else null
                working(original, done) { controller.projectDevelopmentForWeb().hostSourceOriginForOwner(original.project, checkout, expected) }
            }
            "projects.development.inspect" -> {
                fields(args, "viewHandle", "checkoutId", "savedEdit"); val checkout = text(args, "checkoutId")
                val receipt = if (args.has("savedEdit")) {
                    val saved = args.getJSONObject("savedEdit"); fields(saved, "filesHandle", "id")
                    files.savedEditForBuild(original.project, checkout, text(saved, "filesHandle"), text(saved, "id"))
                } else null
                working(original, done) {
                    if (receipt == null) source(original, checkout)
                    else controller.projectDevelopmentForWeb().sourceAfterSaveForOwner(original.project, checkout, receipt.path, receipt.savedHash)
                }
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
            "projects.development.reviewDraftBuild" -> {
                val previewDigest=if(args.has("previewOnly")){
                    fields(args,"viewHandle","checkoutId","retainedDraft","previewOnly","expectedSourceDigest")
                    require(args.opt("previewOnly")==true) { "只读构建方案须明确选择" }
                    check(review==null) { "请先关闭原构建审阅" }
                    text(args,"expectedSourceDigest",64).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                }else{
                    fields(args,"viewHandle","checkoutId","retainedDraft")
                    null
                }
                val checkout=text(args,"checkoutId")
                val shown=args.getJSONObject("retainedDraft")
                fields(shown,"checkoutId","goal",*deliveryFields)
                val expected=copyDeliveryFields(shown,JSONObject().put("checkoutId",text(shown,"checkoutId",80,true))
                    .put("goal",text(shown,"goal",12000,true)))
                fun heldDraft()=synchronized(drafts){
                    val d=draft(original.project)
                    check(!pending(original.project) && original.shownSeal==ProjectConventionDraft.bind(d).seal &&
                        sameDraft(d,expected) && (d.optString("checkoutId")==checkout || untouchedBuildDraft(d,checkout))) {
                        "项目草稿仍待保存或属于另一副本。已保留输入，没有替换或提交。"
                    }
                    d
                }
                val retained=heldDraft();val heldDigest=buildDraftDigest(retained)
                if(previewDigest==null)review=null
                working(original,done){
                    val host=controller.projectDevelopmentForWeb()
                    val preparation=host.reviewNodeBuildForOwner(original.project,checkout);val pin=preparation.getJSONObject("pin")
                    val actual=source(original,checkout);val selectedModel=if(previewDigest==null)model() else null
                    val edited=OwnerDraftFirstBuild.edited(pin);val existing=OwnerDraftFirstBuild.existingCheckout(pin)
                    check(existing || untouchedBuildDraft(retained,checkout)) { "请保留或清空当前未交办的目标；未提交改稿构建。" }
                    val sourceDigest=actual.getString("sourceDigest")
                    val sameSource=pin.getString("sourceDigest")==sourceDigest && edited==actual.optBoolean("editedDraftCopy") &&
                        existing==actual.optBoolean("existingNodeBuild")
                    if(previewDigest==null)check(sameSource && selectedModel?.value()?.optBoolean("configured")==true) {
                        "所选版本或默认模型未就绪，未开始。"
                    }else {
                        check(existing && !edited) { "只读构建方案目前仅适用于普通私人副本。" }
                        check(sameSource && previewDigest==sourceDigest && actual.opt("projectId")==original.project &&
                            actual.opt("checkoutId")==checkout) { "原副本源码版本已变化，请重新读取。" }
                    }
                    check((!edited || !actual.has("editedDraftBuild") && !actual.optBoolean("editedDraftBuildUnavailable")) &&
                        (!existing || !actual.has("existingNodeBuildTask") && !actual.optBoolean("existingNodeBuildUnavailable"))) {
                        "这版已有构建或原记录待核对；请回项目打开这一版的原任务，不重复交办。"
                    }
                    if(previewDigest!=null){
                        check(current(original) && review==null && heldDigest==buildDraftDigest(heldDraft())) {
                            "原项目、草稿或审阅状态已变化，没有查看其他版本。"
                        }
                        // No model snapshot, runnable review, or submission identity.
                        return@working JSONObject().put("format","pocketworld.node-build-plan-preview.v1")
                            .put("projectId",original.project).put("checkoutId",checkout).put("sourceDigest",sourceDigest)
                            .put("runtimeId","managed_process_v1").put("manager",pin.getString("manager"))
                            .put("scripts",preparation.getJSONObject("scripts")).put("editedCopy",edited).put("existingCopy",existing)
                            .put("preservedProjectDraft",existing && !untouchedBuildDraft(retained,checkout))
                            .put("readOnly",true).put("commandsAuthorized",false).put("executionAuthorized",false).also { out ->
                                preparation.optJSONObject("dependencies")?.let { lock ->
                                    val summary=JSONObject()
                                    for(key in listOf("manager","managerVersion","registry","lockPath","lockSha256","packages"))summary.put(key,lock.get(key))
                                    out.put("lockedDependencies",summary).put("dependencyState",preparation.getString("dependencyState"))
                                }
                            }
                    }
                    val reviewedModel=checkNotNull(selectedModel)
                    val goal=OwnerDraftFirstBuild.goal(pin);val contract=ProjectOwnerWorkflows.deliveryContractForOwner(JSONObject().put("goal",goal))
                    val input=JSONObject().put("projectId",original.project).put("checkoutId",checkout).put("runtime","managed_process_v1")
                        .put("expectedSourceDigest",pin.getString("sourceDigest")).put("clientMessageId",OwnerDraftFirstBuild.reviewedClient(pin))
                        .put("goal",goal).put(OwnerDraftFirstBuild.FIELD,pin)
                    copyDeliveryFields(contract,input)
                    check(current(original) && heldDigest==buildDraftDigest(heldDraft())) { "原项目或草稿已变，没有准备新工作。" }
                    val selected=Review(UUID.randomUUID().toString(),original,input.toString(),ProjectConventionDraft.bind(retained),
                        reviewedModel.revision(),SystemClock.elapsedRealtime()+120_000,heldDigest)
                    review=selected
                    JSONObject().put("reviewHandle",selected.handle).put("requestId",input.getString("clientMessageId")).put("projectId",original.project)
                        .put("checkoutId",checkout).put("goal",goal).put("deliveryContract",contract).put("source",actual).put("model",reviewedModel.value())
                        .put("runtimeId","managed_process_v1").put("runtime","这台手机").put("expiresAt",System.currentTimeMillis()+120_000)
                        .put("commandsAuthorized",false).put("projectMemoryAuthorized",false).put("draftBuild",true).put("editedCopy",edited).put("existingCopy",existing)
                        .put("preservedProjectDraft",existing && !untouchedBuildDraft(retained,checkout))
                        .put("manager",pin.getString("manager")).put("scripts",preparation.getJSONObject("scripts"))
                        .putOpt("lockedDependencies",preparation.optJSONObject("dependencies")).putOpt("dependencyState",preparation.opt("dependencyState"))
                        .putOpt("previousVerification",preparation.optJSONObject("previousVerification"))
                }
            }
            "projects.development.review" -> {
                fields(args, "viewHandle", "checkoutId", "goal", *deliveryFields)
                check(!pending(original.project)) { "请先查询原项目输入，不要重新开始" }
                val checkout = text(args, "checkoutId")
                val contract = ProjectOwnerWorkflows.deliveryContractForOwner(args)
                val goal = contract.getString("goal")
                val reviewedSource=editShownDraft(original) { stored ->
                    val prior=stored.optJSONObject("submission")
                    check(prior?.optBoolean("draftSourceNeedsReview")!=true || !sameDraft(prior,copyDeliveryFields(contract,JSONObject().put("goal",goal).put("checkoutId",checkout)))) { "上次任务已受理。这份同文草稿的来源未能核对，先修改下一次的目标或移除约定。" }
                    val picked = ProjectConventionDraft.present(stored)
                    check(picked==null || picked.getString("checkoutId")==checkout || contract.optString("constraints")!=stored.getString("constraints")) { "原副本约定不能自动带到另一个工作副本" }
                    copyDeliveryFields(contract, stored.put("goal",goal).put("checkoutId",checkout))
                    if (ProjectConventionDraft.present(stored)==null) stored.remove(ProjectConventionDraft.KEY)
                }; review = null
                working(original, done) {
                    val actual = source(original, checkout); val selectedModel = model()
                    check(selectedModel.value().optBoolean("configured")) { "默认模型尚未就绪，请先连接模型；项目与草稿保留" }
                    val input = JSONObject().put("projectId", original.project).put("checkoutId", checkout)
                        .put("expectedSourceDigest", actual.getString("sourceDigest")).put("runtime", "managed_process_v1")
                        .put("clientMessageId", UUID.randomUUID().toString()).put("goal", goal)
                    copyDeliveryFields(contract, input)
                    val selected = Review(UUID.randomUUID().toString(), original, input.toString(), reviewedSource, selectedModel.revision(), SystemClock.elapsedRealtime() + 120_000)
                    check(current(original) && reviewedSource.matches(draft(original.project))) { "原项目窗口或约定来源已变化，没有准备启动" }
                    review = selected
                    JSONObject().put("reviewHandle", selected.handle).put("requestId", input.getString("clientMessageId")).put("projectId", original.project)
                        .put("checkoutId", checkout).put("goal", goal).put("deliveryContract", contract).put("source", actual).put("model", selectedModel.value())
                        .put("runtime", "这台手机").put("runtimeId", input.getString("runtime")).put("expiresAt", System.currentTimeMillis() + 120_000)
                        .put("commandsAuthorized", false).put("projectMemoryAuthorized", false)
                        .also { value->selected.picked.copyMarker()?.let { value.put("ownerPickedConventionRevision",it.getLong("revision")) } }
                }
            }
            "projects.development.start" -> {
                fields(args, "viewHandle", "reviewHandle")
                check(!busy && !pending(original.project)) { "原项目操作仍待核对" }
                val selected = review
                check(selected != null && selected.handle == text(args, "reviewHandle") && selected.page === original) { "这份项目审阅已关闭或使用" }
                val input = JSONObject(selected.input)
                val independentBuild=input.has(OwnerDraftFirstBuild.FIELD)
                fun startCurrent(): Boolean {
                    val status = controller.nativeModelSelection().status()
                    return current(original) && SystemClock.elapsedRealtime() < selected.expires && !status.pending() && status.revision() == selected.modelRevision &&
                        matchesInput(draft(original.project),input,selected)
                }
                val marker = JSONObject().put("projectId", original.project).put("checkoutId", input.getString("checkoutId"))
                    .put("clientMessageId", input.getString("clientMessageId")).put("goal", input.getString("goal")).put("status", "pending")
                if(!independentBuild)selected.picked.submissionSeal?.let { marker.put(ProjectConventionDraft.SUBMISSION_SEAL,it) }
                copyDeliveryFields(input, marker)
                input.optJSONObject(OwnerDraftFirstBuild.FIELD)?.let { scoped ->
                    marker.put("independentNodeBuild",true)
                    if(OwnerDraftFirstBuild.edited(scoped)||OwnerDraftFirstBuild.existingCheckout(scoped))
                        marker.put("versionedBuildSourceDigest",input.getString("expectedSourceDigest"))
                }
                val ready=startCurrent()
                if(!ready && independentBuild){
                    review=null
                    done(public(marker.put("status","rejected").put("notAdmitted",true)
                        .put("message","项目草稿、默认模型或审阅期限已变化，没有提交构建。请重新核对当前版本。")),null)
                    return
                }
                check(ready) { "项目、默认模型或审阅期限已变化，请重新核对" }
                review = null; busy = true
                try {
                    // Persist the original query identity before any action can
                    // reach Assistant; a killed Activity cannot turn unknown
                    // admission into permission to replay the goal.
                    updateDraft(original.project, true) {
                        check(!pending(original.project) && matchesInput(it,input,selected)) { "原交办或约定来源已变化，没有提交。" }
                        it.put("submission", marker)
                    }
                    // The independent build only receives its generated narrow goal. A held project
                    // draft/selected convention is a comparison fence, never an attachment or grant.
                    controller.startProjectInputFromDraftForWeb(input, if(independentBuild)null else selected.picked.copyMarker(), ::startCurrent) { result, error ->
                        if (page === original) busy = false
                        val receipt = recordSubmission(marker, result, error, original)
                        done(public(receipt), null)
                    }
                } catch (failure: Exception) {
                    busy = false
                    marker.put("status", "rejected").put("notAdmitted", true).put("message", "本次尚未进入启动队列，请重新核对。").put("error", failure.message)
                    updateDraft(original.project) {
                        if(it.optJSONObject("submission")?.optString("clientMessageId")==marker.getString("clientMessageId"))it.put("submission",marker)
                    }
                    done(public(marker), null)
                }
            }
            "projects.development.query" -> {
                fields(args, "viewHandle")
                val marker = draft(original.project).optJSONObject("submission")?.let(::copy) ?: error("没有可查询的原项目输入")
                working(original, done) {
                    val result = runCatching { controller.projectDevelopmentForWeb().submissionForOwner(original.project, marker.getString("checkoutId"), marker.getString("clientMessageId")) }
                    recordSubmission(marker, result.getOrNull(), result.exceptionOrNull()?.message, original)
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
            if (!project.matches(Regex("[A-Za-z0-9_-]{1,80}"))) continue
            // Durable presentation wins over an older Activity Bundle. Neither
            // source can restore an opaque review or execution authority.
            if (drafts.containsKey(project)) continue
            val retained = copyDeliveryFields(value, JSONObject().put("projectId", project)
                .put("goal", if (value.has("goal")) text(value, "goal", 12000, true) else "").put("checkoutId", value.optString("checkoutId")))
            ProjectConventionDraft.present(value)?.let { retained.put(ProjectConventionDraft.KEY,it) }
            value.optString("copyRequestId").takeIf { it.matches(Regex("[a-f0-9-]{36}")) }?.let { retained.put("copyRequestId", it).put("copyStatus", if (value.optString("copyStatus") == "ready") "ready" else "unknown") }
            value.optJSONObject("submission")?.takeIf { it.optString("projectId") == project && it.optString("clientMessageId").matches(Regex("[a-f0-9-]{36}")) }?.let {
                val marker = copyDeliveryFields(it, copy(it)); if (marker.optString("status") == "pending") marker.put("status", "unknown")
                retained.put("submission", marker)
            }
            drafts[project] = retained
        }
        // Presentation data cannot restore page handles, start reviews or grants.
        }
    }
}
