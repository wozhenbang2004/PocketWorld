package dev.pocketengine.browser

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import dev.pocketengine.BrowserActivity
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.web.PocketWebEndpoint
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Owner-only endpoint for the same bundled React renderer. Website WebViews never receive it. */
internal class BrowserWorkspaceEndpoint(
    private val activity: BrowserActivity,
    private val owner: OwnerController,
    private val emit: (JSONObject) -> Unit
) : PocketWebEndpoint {
    override val surface = "browser_workspace"
    private val main = Handler(Looper.getMainLooper())
    private val windowId = UUID.randomUUID().toString()
    @Volatile private var generation = 0L
    @Volatile private var foreground = false
    @Volatile private var closed = false
    private var document: String? = null
    private var revision = 0L
    private var previousProjection = ""
    private val seen = linkedSetOf<String>()
    private var history: JSONArray? = null
    private var review: Review? = null
    private var lastSnapshot: BrowserTaskHost.OwnerSnapshot? = null
    private var lastConflict: JSONObject? = null
    private var currentFrame: Frame? = null
    // These are the actual displayed owner snapshots, including opaque native stop authority.
    // An old JS button can select its original frame, never borrow a newer task's ticket.
    private val frames = linkedMapOf<String, Frame>()

    private data class Frame(val id: String, val native: JSONObject, val stop: BrowserTaskHost.OwnerStopTicket?, val conflict: JSONObject?)
    private data class ScopeReview(
        val target: String, val taskId: String, val goal: String, val pin: JSONObject,
        val read: BrowserReadScope.Consent, val frames: BrowserFrameOrigins.Consent,
        val actions: BrowserActionScope.Consent, val execution: JSONObject?,
        val browserRevision: Long, val executionRevision: Long
    )
    private data class Review(val id: String, val kind: String, val frame: Frame, val display: JSONObject, val scope: ScopeReview? = null)

    override fun canInteract(): Boolean = !closed && foreground && owner.expanded &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus()

    override fun start() { owner.refresh(); refresh(true) }

    override fun captureDispatch(raw: String, stale: () -> Unit): () -> Unit {
        val original = generation
        return {
            if (canInteract() && original == generation) dispatch(raw)
            else { refresh(true); stale() }
        }
    }

    private fun dispatch(raw: String) {
        if (raw.length > 131072) return
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = envelope.opt("id") as? String ?: return
        val suppliedDocument = envelope.opt("documentId") as? String ?: return
        if (envelope.optInt("v") != 1 || id.isBlank() || id.length > 160 || suppliedDocument.isBlank() || suppliedDocument.length > 160) return
        try {
            check(canInteract()) { "浏览工作区已离开前台，请重新核对。" }
            if (envelope.optString("action") == "app.ready") {
                check(document == null || document == suppliedDocument) { "工作区文档已变化。" }
                document = suppliedDocument
                refresh(true)
                result(id, true, JSONObject().put("accepted", true))
                return
            }
            require(envelope.optString("action") == "browserWorkspace.action") { "此工作区操作未开放。" }
            check(document == suppliedDocument && id !in seen && seen.size < 1024) { "原请求已处理或文档已变化，请查询原结果。" }
            val args = envelope.getJSONObject("args")
            check(args.optString("windowId") == windowId && args.optLong("generation", -1) == generation) { "原浏览窗口已失效。" }
            seen += id
            val command = args.getString("command")
            check(!owner.busy || command in setOf("refresh", "backToPage", "dismissReview", "stop")) { "原请求正在处理，请等待回执。" }
            when (command) {
                "refresh" -> owner.refresh()
                "backToPage" -> { review = null; owner.backToPage() }
                "setView" -> {
                    val view = args.getString("view")
                    require(view in setOf("research", "sources", "pages"))
                    review = null; owner.workspaceView = view
                }
                "editGoal" -> { review = null; owner.editGoal(args.getString("text")) }
                "clearGoal" -> { review = null; owner.clearGoal() }
                "retryDraft" -> owner.retryDraft()
                "reviewDraftConflict" -> owner.reviewDraftConflict()
                // Dismiss only this comparison. The unsaved input, stored draft and conflict error remain.
                "dismissDraftConflict" -> owner.draftConflict = null
                "dismissReview" -> review = null
                "showPagePrompt" -> owner.showPagePrompt()
                else -> action(command, args, frames[args.optString("snapshotId")] ?: error("原显示已更新，请核对当前页面后再操作。"))
            }
            refresh(true)
            result(id, true, JSONObject().put("accepted", true).put("status", "requested").put("verified", false))
        } catch (failure: Exception) {
            owner.error = failure.message?.take(500) ?: "本次操作未确认，请核对原任务。"
            result(id, false, JSONObject().put("code", "not_sent").put("message", owner.error))
            refresh(true)
        }
    }

    private fun action(command: String, args: JSONObject, frame: Frame) {
        val state = frame.native
        val active = state.child("activeResearchTask")
        when (command) {
            "resolveDraftConflict" -> {
                val conflict = frame.conflict ?: error("原草稿核对已失效。")
                check(conflict === owner.draftConflict) { "原草稿核对已关闭或被更新。" }
                owner.resolveDraftConflict(JSONObject(conflict.toString()), args.getBoolean("keepInput"))
            }
            "readHistory" -> work({ history = owner.host.historyForOwner() })
            "selectHistory" -> {
                val id = args.getString("taskId")
                require(history?.rows()?.any { it.optString("taskId") == id } == true) { "请先选择实际研究记录。" }
                work({ owner.host.selectHistoryForOwner(id) }, { owner.workspaceView = "sources" })
            }
            "returnToTask" -> {
                val id = args.getString("taskId")
                require(id in setOf(active.optString("id"), state.child("researchTask").optString("id"), state.child("entryTask").optString("id")) && id.isNotBlank())
                owner.returnToTask(id)
            }
            "selectPage" -> {
                val page = page(frame, args.getString("tabId"))
                work({ if (page.optString("openedBy") == "task") owner.host.takeOverPage(page.getString("tabId")) else owner.host.selectOwnerPage(page.getString("tabId")) }, { owner.backToPage() })
            }
            "adoptPage" -> {
                val page = page(frame, args.getString("tabId"))
                require(page.optString("openedBy") == "owner")
                val pin = JSONObject().put("space", JSONObject(state.child("pages").child("taskSpace").toString())).put("page", page)
                work({ owner.host.adoptPage(pin) })
            }
            "closePage" -> {
                val page = page(frame, args.getString("tabId")); require(page.optString("openedBy") == "owner")
                work({ owner.host.closeOwnerPage(page.getString("tabId")) })
            }
            "returnForOwner" -> {
                val pages = state.child("pages"); val space = pages.child("taskSpace")
                val handoffs = space.array("handoffPages").rows().filter { !it.optBoolean("unavailable") }
                val selected = handoffs.firstOrNull { pin -> pages.array("tabs").rows().any { it.optBoolean("selected") && it.optString("tabId") == pin.optString("tabId") } }
                val pin = selected ?: space.optJSONObject("continuePin")?.takeIf { handoffs.isEmpty() } ?: error("请先选择要交还的原页面。")
                work({ owner.host.returnForOwner(JSONObject(pin.toString())) }, { owner.backToPage() })
            }
            "stop" -> owner.stop(frame.stop)
            "revokeReadScope" -> work({ owner.host.revokeReadScopeForOwner(active.getString("id"), state.getJSONObject("readScope").getString("scopeId")) })
            "revokeTaskScope" -> work({ owner.host.revokeTaskScopeForOwner(active.getString("id"), state.getJSONObject("taskActionScope").getLong("revision")) })
            "reopenPage" -> {
                val id = args.getString("logicalPageId")
                require(state.child("researchPages").array("pages").rows().any { it.optString("logicalPageId") == id })
                work({ owner.host.reopenResearchPageForOwner(state.getJSONObject("researchTask").getString("id"), id, state.getString("researchPagesFingerprint")) }, { owner.backToPage() })
            }
            "openSource", "openSourcePage" -> {
                val source = source(frame, args.getString("taskId"), args.getString("sourceId"))
                if (command == "openSource") owner.openSavedSource(source.getString("taskId"), source.getString("sourceId"))
                else work({ owner.host.openSourceForOwner(source.getString("taskId"), source.getString("sourceId")) }, { owner.backToPage() })
            }
            "openField" -> {
                val taskId = args.getString("taskId"); val field = args.getString("fieldRef")
                val source = source(frame, taskId, BrowserStructuredSource.sourceId(taskId, field))
                require((0 until source.array("fieldRefs").length()).any { source.array("fieldRefs").optString(it) == field })
                owner.openSavedField(taskId, field)
            }
            "openDownload" -> {
                val taskId = args.getString("taskId"); val downloadId = args.getString("downloadId")
                val download = state.array("downloads").rows().singleOrNull { it.optString("taskId") == taskId && it.optString("id") == downloadId } ?: error("原下载已不在此任务中。")
                require(state.child("researchTask").optString("id") == taskId && download.optString("status") == "downloaded")
                owner.openDownload(taskId, download)
            }
            "cancelUpload" -> {
                val attempt = upload(frame, args.getString("attemptId"))
                work({ owner.host.cancelUploadForOwner(attempt.getJSONObject("pin").getString("taskId"), attempt.getString("attemptId"), attempt.getString("fingerprint")) })
            }
            "createProfile" -> {
                val label = args.getString("label"); require(label.isNotBlank() && label.length <= 120)
                work({ owner.host.createProfileForOwner(label, state.getJSONObject("profiles").getLong("revision")) })
            }
            "reviewProfile" -> {
                val profile = state.child("profiles").array("profiles").rows().singleOrNull { it.optString("id") == args.getString("profileId") } ?: error("登录资料已变化。")
                val operation = args.getString("operation"); require(operation in setOf("switch", "delete"))
                val deleting = operation == "delete"
                openReview(if (deleting) "profileDelete" else "profileSwitch", frame,
                    if (deleting) "删除登录资料？" else "切换登录资料？",
                    if (deleting) "会停止本资料浏览、删除其网页登录数据、清除历史与书签和全部研究文字草稿。系统数据清理可能需要重启；已保存研究来源保留。" else "切换会结束当前自动研究并关闭当前资料的页面；登录状态和已保存来源保留。",
                    JSONObject().put("profile", JSONObject(profile.toString())).put("revision", state.getJSONObject("profiles").getLong("revision")))
            }
            "reviewRecovery" -> {
                require(state.optBoolean("researchRecoveryAvailable")) { "原研究当前不能恢复，请回原任务核对。" }
                openReview("recovery", frame, "重新继续原研究？", "保留同一任务与已保存依据，新建执行并重新读取页面。继续使用当前登录资料；旧授权和未知操作不会恢复或重发。请明确同意把重新读取的网页观察发送给当前模型。",
                    JSONObject().put("taskId", state.getJSONObject("researchTask").getString("id")).put("manifestFingerprint", state.getString("researchPagesFingerprint")))
            }
            "reviewScope" -> reviewScope(frame, args)
            "reviewApproval" -> {
                val request = active.array("approvals").rows().singleOrNull { it.optString("id") == args.getString("requestId") && it.optString("state") == "pending" && it.optLong("expiresAt") > System.currentTimeMillis() && it.optString("runId") == active.optString("runId") } ?: error("原浏览请求已不再等待。")
                openReview("approval", frame, toolLabel(request.optString("tool")), browserApprovalDetail(request, profileLabel(state)),
                    JSONObject().put("taskId", active.getString("id")).put("request", JSONObject(request.toString())))
            }
            "decideApproval" -> {
                val selected = takeReview(args, "approval")
                val request = selected.display.getJSONObject("request")
                val allow = args.getBoolean("allow")
                work({
                    if (allow && request.optString("tool") in setOf(BrowserVisualCapture.TOOL, "browser_task_native_input", "browser_task_site_mode", "browser_task_resize", "browser_task_upload_artifact")) main.post { if (!closed) owner.backToPage() }
                    checkNotNull(owner.host.decideIfWaiting(selected.display.getString("taskId"), request.getString("id"), allow)) { "原浏览请求已不再等待。" }
                })
            }
            "reviewUpload" -> {
                val original = upload(frame, args.getString("attemptId"))
                openReview("upload", frame, "核对这次原上传", "请填写原网页实际显示的结果。只记录本次核对；不重新上传，不证明已发送内容已撤回。", JSONObject().put("attempt", JSONObject(original.toString())))
            }
            "confirmUpload" -> {
                val selected = takeReview(args, "upload"); val original = selected.display.getJSONObject("attempt")
                val outcome = args.getString("outcome"); val note = args.getString("note")
                require(outcome in setOf("accepted", "rejected", "unknown") && note.isNotBlank() && note.length <= 2000)
                work({ owner.host.confirmUploadForOwner(original.getJSONObject("pin").getString("taskId"), original.getString("attemptId"), original.getString("fingerprint"), outcome, note) })
            }
            "prepareRecipe" -> {
                val source = source(frame, args.getString("taskId"), args.getString("sourceId"))
                val query = args.getString("query"); require(query.isNotBlank() && query.length <= 160)
                var actual: JSONObject? = null
                work({ actual = owner.host.prepareRecipeForOwner(source.getString("taskId"), source.getString("sourceId"), query) }, {
                    val prepared = checkNotNull(actual)
                    openReview("recipe", frame, "保存网站只读配方？", "按核对过的原来源整理字段；页面结构变化时回到普通读取。配方不包含账号授权，不会导航、点击或提交。",
                        JSONObject().put("taskId", source.getString("taskId")).put("sourceId", source.getString("sourceId")).put("query", query).put("recipe", prepared).put("fingerprint", TaskStore.capabilityFingerprint(prepared)))
                })
            }
            "confirmReview" -> confirmReview(args)
            else -> error("此工作区操作未开放。")
        }
    }

    private fun reviewScope(frame: Frame, args: JSONObject) {
        val state = frame.native
        val target = args.getString("target")
        require(target in setOf("start", "existing", "renew", "renewActions"))
        require(args.opt("consent") == true) { "请明确同意本轮网页观察与显示的范围。" }
        val options = args.optJSONObject("options") ?: JSONObject()
        val minutes = options.optInt("scopeMinutes", 0); require(minutes in setOf(0, 30, 120))
        val active = state.child("activeResearchTask")
        val taskId = when (target) {
            "start" -> ""
            "existing" -> args.getString("taskId").also { id -> require(state.array("currentAssistantTasks").rows().any { it.optString("taskId") == id }) { "此原任务已不在可接入列表。" } }
            else -> active.getString("id")
        }
        val goal = if (target == "start") owner.goal else if (target == "existing") state.array("currentAssistantTasks").rows().first { it.optString("taskId") == taskId }.optString("goal") else active.optString("goal")
        if (target in setOf("start", "existing")) require(owner.draftReady) { "原文字草稿尚未就绪。" }
        if (target == "start") require(goal.isNotBlank() && goal.length <= 4000 && state.optBoolean("open"))
        val pins = options.optJSONArray("selectedInteractionCandidates") ?: JSONArray()
        val candidates = state.child("pages").child("taskSpace").array("interactionCandidates").rows()
        require(pins.length() <= 32)
        val selected = JSONArray()
        pins.rows().forEach { pin ->
            val digest = TaskStore.capabilityFingerprint(pin)
            selected.put(JSONObject((candidates.singleOrNull { TaskStore.capabilityFingerprint(it) == digest } ?: error("所选控件不属于原显示的当前文档。" )).toString()))
        }
        val tools = state.child("model").optInt("maxSteps", 40).coerceIn(1, 400)
        val execution = if (target == "start") JSONObject().put("deadlineAt", if (minutes == 0) 0L else System.currentTimeMillis() + minutes * 60_000L)
            .put("toolLimit", tools).put("modelCallLimit", dev.pocketengine.store.TaskExecutionScope.defaultModelBudget(tools)) else null
        val pin = JSONObject((if (target in setOf("renew", "renewActions")) state.child("readScopeIdentity") else state).toString())
        var scope: ScopeReview? = null
        work({
            val read = if (target == "renewActions") BrowserReadScope.disabled() else BrowserReadScope.forOwner(options.optBoolean("autoRead"), options.optBoolean("saveReadSources"), options.optString("publicSeeds"))
            val frames = if (target in setOf("renew", "renewActions")) BrowserFrameOrigins.sameOriginOnly() else BrowserFrameOrigins.forOwner(options.optString("extraFrameOrigins"))
            val actions = if (target == "renew") BrowserActionScope.disabled() else BrowserActionScope.forOwner(options.optBoolean("autoActions"), options.optString("actionOrigins"), options.optString("actionBudget", "60").toIntOrNull() ?: 0,
                options.optBoolean("ordinaryInput"), options.optBoolean("interactionClicks"), options.optBoolean("interactionSelections"), options.optBoolean("interactionChecks"), options.optString("interactionTargets"), selected)
            if (target == "renew") require(read.enabled())
            if (target == "renewActions") require(actions.enabled())
            scope = ScopeReview(target, taskId, goal, pin, read, frames, actions, execution, state.child("taskActionScope").optLong("revision"), active.child("executionScope").optLong("revision"))
        }, {
            val actual = checkNotNull(scope)
            val data = JSONObject().put("target", target).put("taskId", taskId).put("goal", goal).put("profileLabel", profileLabel(state))
                .put("read", actual.read.review()).put("frames", actual.frames.summary()).put("actions", actual.actions.review()).put("executionChoice", execution)
            openReview("scope", frame, "明确授予这轮浏览范围？", browserScopeDetail(actual.read, actual.actions), data, actual)
        })
    }

    private fun confirmReview(args: JSONObject) {
        require(args.opt("acknowledge") == true) { "请明确确认当前显示的原审阅。" }
        val selected = takeReview(args)
        val display = selected.display
        when (selected.kind) {
            "scope" -> {
                val scope = checkNotNull(selected.scope)
                if (scope.target == "start") require(owner.goal == scope.goal && owner.draftReady) { "研究文字已改变，请重新核对。" }
                work({
                    when (scope.target) {
                        "start" -> owner.host.startForOwner(scope.goal, scope.pin, true, scope.read, scope.frames, scope.actions, scope.execution)
                        "existing" -> owner.host.allowExistingForOwner(scope.taskId, scope.pin, true, scope.read, scope.frames, scope.actions)
                        "renewActions" -> owner.host.grantTaskScopeForOwner(scope.taskId, scope.pin, scope.browserRevision, scope.executionRevision, scope.actions)
                        else -> owner.host.grantReadScopeForOwner(scope.taskId, scope.pin, scope.read)
                    }
                }, {
                    owner.consent = false; owner.resetReadConsent()
                    if (scope.target == "start") owner.consumedGoal()
                    if (scope.target in setOf("start", "existing")) owner.backToPage()
                }, saveText = true)
            }
            "profileSwitch" -> work({ owner.host.selectProfileForOwner(display.getJSONObject("profile").getString("id"), display.getLong("revision")) }, saveText = true)
            "profileDelete" -> work({
                val deleted = owner.host.deleteProfileForOwner(display.getJSONObject("profile").getString("id"), display.getLong("revision"))
                if (!deleted.optBoolean("researchDraftsCleared", true)) main.post { if (!closed) owner.error = deleted.optString("researchDraftCleanupMessage") }
            })
            "recovery" -> work({ owner.host.recoverResearchForOwner(display.getString("taskId"), display.getString("manifestFingerprint"), selected.frame.native, true) }, { owner.workspaceView = "research" })
            "recipe" -> {
                val enabled = args.opt("enabled") == true
                work({ owner.host.saveRecipeForOwner(display.getString("taskId"), display.getString("sourceId"), display.getString("query"), display.getString("fingerprint"), enabled) })
            }
            else -> error("请使用此审阅对应的明确决定。")
        }
    }

    private fun openReview(kind: String, frame: Frame, title: String, detail: String, data: JSONObject, scope: ScopeReview? = null) {
        val id = UUID.randomUUID().toString()
        val display = JSONObject(data.toString()).put("id", id).put("kind", kind).put("title", title).put("detail", detail).put("data", JSONObject(data.toString()))
        review = Review(id, kind, frame, display, scope)
    }

    private fun takeReview(args: JSONObject, kind: String? = null): Review {
        val original = review ?: error("原审阅已关闭，请重新核对。")
        require(original.id == args.getString("reviewId") && (kind == null || original.kind == kind)) { "原审阅已变化。" }
        review = null
        return original
    }

    private fun work(action: () -> Unit, done: () -> Unit = {}, saveText: Boolean = false) {
        val original = generation
        val run = {
            check(!closed && foreground && original == generation && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus()) { "原浏览窗口已离开前台，未派发这次操作。" }
            action()
        }
        val complete = { if (!closed && original == generation) done(); refresh(true) }
        if (saveText) owner.commandWithDraft(run, complete) else owner.command(run, complete)
    }

    private fun page(frame: Frame, id: String) = frame.native.child("pages").array("tabs").rows().singleOrNull { it.optString("tabId") == id } ?: error("原网页已关闭。")
    private fun source(frame: Frame, taskId: String, id: String) = frame.native.array("sources").rows().singleOrNull { it.optString("taskId") == taskId && it.optString("sourceId") == id } ?: error("原来源不在当前研究结果中。")
    private fun upload(frame: Frame, id: String) = frame.native.child("researchTask").array("browserUploadAttempts").rows().singleOrNull { it.optString("attemptId") == id } ?: error("原上传记录已变化。")
    private fun profileLabel(state: JSONObject) = state.child("profiles").array("profiles").rows().firstOrNull { it.optString("id") == state.optString("profileId") }?.optString("label").orEmpty()

    fun refresh(force: Boolean = false) {
        if (closed) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh(force) }; return }
        try {
            val actual = owner.displayedSnapshot()
            val conflict = owner.draftConflict
            if (actual != null && (actual !== lastSnapshot || conflict !== lastConflict || currentFrame == null)) {
                val native = JSONObject(actual.state.toString())
                val viewed = native.child("researchTask")
                val conclusion = viewed.child("evidence").optString("modelSummary").ifBlank { viewed.optString("summary") }
                if (viewed.optString("id").isNotBlank() && conclusion.isNotBlank()) {
                    runCatching { BrowserResearchResult.project(viewed.getString("id"), conclusion, native.array("sources")) }
                        .onSuccess { native.put("researchResult", it) }
                        .onFailure { native.put("researchResultError", "原结论的来源身份暂不可核对，请查看原任务。"); owner.error = native.getString("researchResultError") }
                }
                native.array("sources").rows().forEach { it.put("scopeNote", sourceScopeNote(it)) }
                val next = Frame(UUID.randomUUID().toString(), native, actual.stopTicket, conflict)
                currentFrame = next; frames[next.id] = next
                while (frames.size > 8) frames.remove(frames.keys.first())
                lastSnapshot = actual; lastConflict = conflict
            }
            val workspace = JSONObject().put("windowId", windowId).put("generation", generation).put("snapshotId", currentFrame?.id.orEmpty())
                .put("ready", canInteract() && document != null && currentFrame != null).put("view", owner.workspaceView)
                .put("native", currentFrame?.native ?: JSONObject()).put("busy", owner.busy).put("error", owner.error)
                .put("snapshotError", owner.snapshotError).put("history", history).put("review", review?.display)
                .put("draft", JSONObject().put("text", owner.goal).put("ready", owner.draftReady).put("saving", owner.draftSaving)
                    .put("savedAt", owner.draftSavedAt).put("error", owner.draftError).put("conflict", owner.draftConflict).put("contextTaskId", owner.contextTaskId))
            val projection = workspace.toString()
            if (!force && projection == previousProjection) return
            previousProjection = projection
            emit(JSONObject().put("v", 1).put("kind", "state").put("revision", ++revision)
                .put("snapshot", JSONObject().put("surface", surface).put("state", JSONObject()).put("composer", JSONObject.NULL)
                    .put("browserWorkspace", workspace).put("host", JSONObject().put("surface", surface))
                    .put("capabilities", JSONObject().put("commands", JSONArray(listOf("app.ready", "browserWorkspace.action"))))))
        } catch (_: Exception) {
            owner.error = "浏览工作区状态暂不可核对，请刷新原任务。"
            previousProjection = ""
            // Keep the failure visible in the same workspace without offering authority from a stale frame.
            currentFrame = null; frames.clear()
            emit(JSONObject().put("v", 1).put("kind", "state").put("revision", ++revision)
                .put("snapshot", JSONObject().put("surface", surface).put("state", JSONObject()).put("composer", JSONObject.NULL)
                    .put("browserWorkspace", JSONObject().put("windowId", windowId).put("generation", generation)
                        .put("snapshotId", "").put("ready", canInteract() && document != null).put("view", owner.workspaceView)
                        .put("native", JSONObject()).put("busy", false).put("error", owner.error).put("snapshotError", true)
                        .put("draft", JSONObject().put("text", owner.goal).put("ready", owner.draftReady).put("error", owner.draftError)))
                    .put("host", JSONObject().put("surface", surface))
                    .put("capabilities", JSONObject().put("commands", JSONArray(listOf("app.ready", "browserWorkspace.action"))))))
        }
    }

    private fun result(id: String, ok: Boolean, value: JSONObject) {
        if (!closed) emit(JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", ok).put(if (ok) "value" else "error", value))
    }

    fun dismissReviewForBack(): Boolean = (review != null).also { if (it) { review = null; refresh(true) } }
    override fun foreground(value: Boolean) { if (foreground != value) { foreground = value; if (!value) invalidatePresentation(false) } }
    override fun invalidatePresentation(documentReplaced: Boolean) {
        generation++; document = null; seen.clear(); frames.clear(); currentFrame = null; lastSnapshot = null
        review = null; history = null; previousProjection = ""; owner.consent = false; owner.resetReadConsent()
        refresh(true)
    }
    override fun restorePresentationState(saved: JSONObject?) = Unit
    override fun savePresentationState() = JSONObject()
    override fun close() { closed = true; foreground = false; generation++; document = null; review = null; frames.clear(); currentFrame = null; seen.clear(); main.removeCallbacksAndMessages(null) }
}
