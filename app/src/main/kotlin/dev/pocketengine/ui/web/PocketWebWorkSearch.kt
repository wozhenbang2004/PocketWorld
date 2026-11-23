package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.assistant.AssistantModule
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.AttachmentResultRun
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiTaskResult
import dev.pocketengine.ui.objects
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

/** Owner read paths over existing stores; navigation and report reads grant no execution. */
internal class PocketWebWorkSearch(
    private val controller: PocketWorldController,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val rememberTasks: (JSONObject) -> Unit
) {
    companion object {
        const val SAVED_BUILD_WEB = "projects.savedBuildWeb.read"
        val COMMANDS = setOf("work.search", "work.search.read", "work.tasks.list", "work.capabilities", "webSearch.usage", "ownerWork.report.read", "tasks.projectPackages", TaskProjectPackageFiles.COMMAND, "tasks.resultFiles", "composer.object.add", "composer.object.remove", SAVED_BUILD_WEB)
    }
    private val main = Handler(Looper.getMainLooper())
    private val host get() = controller.nativeButlerHost()
    private val pending = mutableSetOf<RunControl>()

    data class CurrentConversation(val id: String, val epoch: Int, val current: () -> Boolean, val scanCurrent: () -> Boolean) {
        fun view() = JSONObject().put("conversationId", id).put("composerEpoch", epoch)
    }

    fun clear() { pending.toList().forEach { it.cancel() }; pending.clear() }

    fun dispatch(action: String, args: JSONObject, conversationResult: UiTaskResult? = null,
                 reportQuote: ((TaskStore.OwnerWorkReportQuote) -> Unit)? = null,
                 currentConversation: CurrentConversation? = null, done: (JSONObject?, String?) -> Unit) {
        try {
            check(controller.webPrivateOwnerVisible() && currentConversation?.current?.invoke() != false) { "请回到私人工作区查找自己的记录" }
            val window = captureWindow()
            val control = RunControl()
            if (currentConversation != null) control.setAuthorityCheck {
                check(currentConversation.scanCurrent()) { "原查找窗口已关闭" }
            }
            pending.add(control)
            var proof: TaskStore.OwnerWorkReportQuote? = null
            try {
                controller.runPluginCatalogWork(Runnable {
                    val result = runCatching {
                        control.check()
                        check(acceptsWindow(window) && controller.webPrivateOwnerVisible() &&
                            currentConversation?.current?.invoke() != false) { "原查找窗口已关闭" }
                        when (action) {
                            "composer.object.add" -> {
                                fields(args,setOf("key","epoch","revision","reference"))
                                JSONObject().put("reference",host.selectOwnerObjectReference(args.getJSONObject("reference"),control))
                            }
                            "composer.object.remove" -> {
                                fields(args,setOf("key","epoch","revision","index"))
                                JSONObject()
                            }
                            "ownerWork.report.read" -> {
                                fields(args, setOf("taskId", "runId", "epoch", "reportMessageId", "offset", "maxChars", "expectedReportRevision", "expectedReportFingerprint", "independentFollowUp"))
                                val selection=JSONObject(args.toString()).also { it.remove("independentFollowUp") }
                                if (reportQuote == null) host.tasks.ownerWorkReportForOwner(selection, control)
                                else host.tasks.ownerWorkReportForOwner(selection, control) { proof=it }
                            }
                            "tasks.projectPackages" -> {
                                fields(args, setOf("taskId", "cursor"))
                                val task = args.opt("taskId") as? String ?: error("原项目任务身份缺失")
                                val cursor = args.opt("cursor") as? String ?: error("原成果读取位置缺失")
                                host.tasks.projectPackagesForOwner(task, cursor, control)
                            }
                            SAVED_BUILD_WEB -> {
                                fields(args, setOf("projectId", "checkoutId", "cursor"))
                                require(args.keys().asSequence().toSet()==setOf("projectId", "checkoutId", "cursor"))
                                val project=args.opt("projectId") as? String ?: error("请选择原项目")
                                val checkout=args.opt("checkoutId") as? String ?: error("原副本范围无效")
                                val cursor=args.opt("cursor") as? String ?: error("原网页读取位置无效")
                                require(project.isNotBlank()&&project.length<=80&&checkout.length<=80&&cursor.length<=2048)
                                check(controller.state.projectId==project&&controller.state.filePath==null) { "当前不在该项目工作台" }
                                controller.projectDevelopmentForWeb().savedBuildWebForOwner(project,checkout,cursor,control)
                            }
                            TaskProjectPackageFiles.COMMAND -> TaskProjectPackageFiles.read(host, args, control)
                            "tasks.resultFiles" -> resultFiles(args, conversationResult, control)
                            "work.search" -> search(args, control, currentConversation)
                            "work.tasks.list" -> {
                                fields(args, setOf("query", "filter", "projectId", "cursor", "limit"))
                                val query = args.opt("query") as? String ?: error("请输入工作查找范围")
                                val filter = args.opt("filter") as? String ?: error("请选择工作状态")
                                val projectId = if (args.has("projectId")) args.opt("projectId") as? String ?: error("项目查找范围无效") else ""
                                require(query.length <= 500 && '\u0000' !in query && filter in setOf("all", "attention", "running", "ended") &&
                                    projectId.length <= 240 && '\u0000' !in projectId && projectId == projectId.trim()) { "工作查找范围无效" }
                                val page = host.tasks.ownerWorkSearchPage(query, args.optString("cursor"), integer(args, "limit", 30, 8, 40), filter, projectId)
                                JSONObject(page.toString()).put("format", "pocketworld.work-directory.v1")
                                    .put("taskPage", page).put("modelCalled", false)
                            }
                            // Internal tasks.open preflight: reread the selected original instead
                            // of treating the bounded navigation-ID cache as lasting authority.
                            "work.search.task" -> {
                                fields(args, setOf("taskId", "query", "expectedRunId", "expectedEpoch", "expectedProjectId", "expectedCheckoutId"))
                                val id = args.opt("taskId") as? String ?: error("原工作身份缺失")
                                val query = args.opt("query") as? String ?: error("原查找文字缺失")
                                require(id.isNotBlank() && id.length <= 240 && query.length <= 500 && '\u0000' !in query) { "原工作查找范围无效" }
                                val task = host.tasks.getForUi(id, false) ?: error("原工作已不存在，请返回核对")
                                require(task.optString("id") == id && task.optString("actorId") == "owner" &&
                                    task.optString("spaceId") == "private" && task.optString("kind") != "inference") { "原工作不属于当前私人工作区" }
                                // The original source-filtered placeholder retains task
                                // identity and stop controls. Opening it grants no input
                                // or continuation; a nonempty query still matches only
                                // the currently visible text below.
                                require(args.has("expectedRunId") == args.has("expectedEpoch")) { "原整理记录身份不完整" }
                                if (args.has("expectedRunId")) {
                                    val run = args.opt("expectedRunId") as? String ?: error("原整理记录身份缺失")
                                    require(run.isNotBlank() && run.length <= 80 && task.optString("runId") == run &&
                                        task.optInt("coordinatorEpoch") == integer(args, "expectedEpoch", 0, 1, Int.MAX_VALUE)) { "原整理回合已变化，请在原工作中重新核对" }
                                }
                                ProjectTaskReadScope.read(args)?.requireOriginal(id, task)
                                val normalized = normalize(query)
                                check(normalized.isEmpty() || normalize(task.optString("goal") + "\n" + task.optString("summary")).contains(normalized)) { "原工作已变化，请重新搜索" }
                                JSONObject().put("taskId", id).put("readOnly", true).put("executionAuthorized", false)
                                    .put("taskPage", JSONObject().put("auditBodyIncluded", false).put("tasks", JSONArray().put(JSONObject().put("id", id))))
                            }
                            "work.search.read" -> {
                                fields(args, setOf("ownerLocator", "offset", "maxChars") +
                                    (if (currentConversation != null) setOf("conversationScope") else emptySet()))
                                val assistant = host.features.module("assistant") as? AssistantModule ?: error("私人原文尚未准备")
                                val offset = integer(args, "offset", 0, 0, Int.MAX_VALUE)
                                val maximum = integer(args, "maxChars", 6000, 64, 12000)
                                require(offset >= 0 && maximum in 64..12000) { "原文读取范围无效" }
                                val locator = args.getJSONObject("ownerLocator")
                                if (currentConversation != null) require(locator.opt("kind") == "owner_native_original" &&
                                    locator.opt("conversationId") == currentConversation.id) { "所选原文不属于此对话" }
                                val scope = currentConversation?.view()
                                val read = JSONObject().put("ownerLocator", locator).put("offset", offset).put("maxChars", maximum)
                                    .putOpt("conversationScope", scope)
                                assistant.readOwnerSearchHit(read, control).putOpt("conversationScope", scope)
                            }
                            "work.capabilities" -> {
                                fields(args, setOf("purpose"))
                                host.ownerWorkCapabilitiesForOwner(args.optString("purpose", "all"))
                            }
                            "webSearch.usage" -> {
                                fields(args, setOf("fromInclusive", "toExclusive", "zoneId", "cursor"))
                                host.tasks.ownerWebUsageForOwner(args.getLong("fromInclusive"), args.getLong("toExclusive"),
                                    args.getString("zoneId"), args.optString("cursor"), control)
                            }
                            else -> error("查找操作不可用")
                        }.also { control.check() }
                    }
                    main.post {
                        pending.remove(control)
                        if (control.isCancelled || !acceptsWindow(window) || !controller.webPrivateOwnerVisible() ||
                            currentConversation?.current?.invoke() == false)
                            done(null, "原查找窗口已变化，请重新读取")
                        else result.fold({ value ->
                            try {
                                if(action=="composer.object.add"||action=="composer.object.remove") {
                                    val revision=args.opt("revision")
                                    require((revision is Int||revision is Long)&&(revision as Number).toLong()>0) { "原草稿版本无效" }
                                    val key=args.getString("key");val epoch=integer(args,"epoch",0,0,Int.MAX_VALUE)
                                    if(action=="composer.object.add")controller.addComposerObject(key,epoch,(revision as Number).toLong(),value.getJSONObject("reference"))
                                    else controller.removeComposerObject(key,epoch,(revision as Number).toLong(),integer(args,"index",-1,0,3))
                                    value.remove("reference");value.put(if(action=="composer.object.add")"added" else "removed",true)
                                }
                                value.optJSONObject("taskPage")?.let(rememberTasks)
                                value.remove("taskPage")
                                proof?.let { reportQuote?.invoke(it) }
                                done(PocketWebState.publicValue(value) as JSONObject, null)
                            } catch (failure: Exception) { done(null, failure.message ?: "原记录不可核对") }
                        }, { done(null, it.message ?: "记录暂时无法读取，请重试") })
                    }
                })
            } catch (failure: Exception) { pending.remove(control); control.cancel(); throw failure }
        } catch (failure: Exception) { done(null, failure.message ?: "查找暂不可用") }
    }

    private fun resultFiles(args: JSONObject, conversationResult: UiTaskResult?, control: RunControl): JSONObject {
        fields(args, if (conversationResult == null) setOf("taskId") else setOf("taskId", "conversationId", "resultMessageId", "resultReaderWindow", "resultSourceRevision"))
        val taskId = args.opt("taskId") as? String ?: error("原工作身份缺失")
        require(taskId.isNotBlank() && taskId.length <= 240 && taskId == taskId.trim() && '\u0000' !in taskId) { "原工作身份无效" }
        fun requireVisibleTask(): JSONObject {
            control.check()
            val task = host.tasks.getForUi(taskId, false) ?: error("原工作已不可读取")
            check(task.optString("id") == taskId && task.optString("actorId") == "owner" && task.optString("spaceId") == "private" &&
                task.optString("kind") != "inference" && task.optJSONObject("origin")?.optString("kind") != "group" &&
                task.optString("taskProjection") != "source_unavailable" && task.optBoolean("contextAvailable", true)) { "原工作来源已不可读取" }
            if (conversationResult != null) check(conversationResult.taskId == taskId && task.optString("runId") == conversationResult.runId &&
                task.optInt("coordinatorEpoch") == conversationResult.epoch && task.optString("status") == "completed") { "原任务轮次或状态已变化，没有用其他轮次的文件替代" }
            return task
        }
        val selectedTask = requireVisibleTask()
        val originalCompletedWork = conversationResult == null && selectedTask.opt("status") == "completed"
        val readSshTransfer = conversationResult == null && selectedTask.opt("feature") == "ssh" && selectedTask.opt("command") == "downloadFile"
        val snapshot = if (originalCompletedWork || readSshTransfer) (host.attachmentOutcomes ?: error("附件成果宿主尚未就绪")).workResultFilesSnapshot(taskId, control)
            else host.ownerOutcomeSnapshot(taskId, control)
        check(snapshot.getString("taskId") == taskId) { "原工作成果身份不符" }
        val runFiles = if (conversationResult != null)
            AttachmentResultRun(taskId, conversationResult.runId, conversationResult.epoch).files(snapshot)
        else null
        val artifacts = runFiles?.entries ?: snapshot.getJSONArray("artifacts").objects()
        val available = artifacts.filter { it.optString("sourceTaskId") == taskId && it.optString("availability") == "available" }
            .sortedBy { it.optString("runProvenance") != "current" }
        val limit = 8
        fun fileHeader(file: JSONObject) = JSONObject().put("id", file.getString("id")).put("sha256", file.getString("sha256"))
            .put("name", file.getString("name")).put("mimeType", file.getString("mimeType")).put("size", file.getLong("size"))
        val files = JSONArray()
        available.take(limit).forEach { file ->
            val row = fileHeader(file).put("runProvenance", file.optString("runProvenance", "unrecorded"))
            if (file.opt("sourceKind") == "device_received_file" && file.opt("sourceRunEvidence") == "original_device_file_receive")
                row.put("sourceRunEvidence", "original_device_file_receive")
            else if (file.opt("sourceKind") == "managed_build_output" && file.opt("sourceRunEvidence") == "original_node_compiler_output")
                row.put("sourceRunEvidence", "original_node_compiler_output")
            else if (conversationResult == null && !selectedTask.optBoolean("contextWithdrawn") && !selectedTask.optBoolean("sourceCurrentReadRequired")) {
                // Keep the existing per-file draft as the only source of text. The
                // original work lists a marker, never an unsubmitted draft body.
                val hasDraft = runCatching {
                    val draft = controller.readArtifactContinuationDraft(taskId, file.getString("id"), file.getString("sha256"))
                    draft.opt("saved") == true && draft.getString("text").isNotBlank()
                }.getOrDefault(false)
                if (hasDraft) row.put("hasSavedSupplement", true)
            }
            files.put(row)
        }
        val readLimited = runFiles?.readLimited ?: snapshot.getJSONObject("indexDiagnostics").getBoolean("incomplete")
        val page = JSONObject().put("taskId", taskId).put("files", files)
            .put("readLimited", readLimited || artifacts.size != available.size || available.size > limit)
        val current = requireVisibleTask()
        val sameCompletedRun = originalCompletedWork && current.opt("status") == "completed" && snapshot.opt("taskStatus") == "completed" &&
            selectedTask.opt("runId") == current.opt("runId") && snapshot.opt("taskRunId") == current.opt("runId") &&
            selectedTask.opt("coordinatorEpoch") == current.opt("coordinatorEpoch") && snapshot.opt("coordinatorEpoch") == current.opt("coordinatorEpoch")
        val run = selectedTask.opt("runId") as? String
        val epoch = selectedTask.opt("coordinatorEpoch")
        if (originalCompletedWork && !run.isNullOrBlank() && (epoch is Int || epoch is Long) && (epoch as Number).toLong() >= 0) {
            check(sameCompletedRun) { "工作已换轮，请重新读取原工作文件。" }
            if (!selectedTask.optBoolean("contextWithdrawn") && !selectedTask.optBoolean("sourceCurrentReadRequired") &&
                !current.optBoolean("contextWithdrawn") && !current.optBoolean("sourceCurrentReadRequired"))
                page.put("completedFileRun", JSONObject().put("runId", run).put("epoch", epoch))
        }
        if (sameCompletedRun && !current.optBoolean("contextWithdrawn") && !current.optBoolean("sourceCurrentReadRequired")) {
            for (index in 0 until files.length()) available[index].optJSONObject("pdfExcerptStart")?.let {
                files.getJSONObject(index).put("pdfExcerptStart", it)
            }
        }
        if (conversationResult == null) {
            // Only the original private work can offer inputs. A chat completion receipt
            // remains scoped to that run's outputs, even for a work with input but no output.
            val sources = snapshot.getJSONArray("sources").objects()
            val revision = snapshot.optLong("taskResultRevision")
            val received = if (readSshTransfer && revision > 0 && selectedTask.optLong("resultRevision") == revision &&
                current.optLong("resultRevision") == revision && current.opt("taskCoreReadLimited") == false &&
                current.opt("hasUnresolvedOperations") == false && !current.optBoolean("contextWithdrawn") &&
                !current.optBoolean("sourceCurrentReadRequired") && current.opt("runId") == taskId &&
                snapshot.opt("taskRunId") == taskId && current.optInt("coordinatorEpoch", -1) == 0 &&
                snapshot.optInt("coordinatorEpoch", -1) == 0) {
                sources.singleOrNull { it.opt("availability") == "available" && it.opt("sourceRunEvidence") == "original_ssh_file_download" }
            } else null
            if (received != null) {
                // Presentation only. The normal task file list and exact local reader are unchanged.
                val withReceived = JSONArray().put(fileHeader(received).put("runProvenance", "current")
                    .put("sourceRunEvidence", "original_ssh_file_download"))
                for (index in 0 until minOf(files.length(), limit - 1)) withReceived.put(files.getJSONObject(index))
                page.put("files", withReceived)
                if (files.length() == limit) page.put("readLimited", true)
            }
            val readable = sources.filter { it.optString("availability") == "available" }
            val inputsAvailable = readable.filter { it !== received }
            val sourceLimit = 4
            val inputs = JSONArray()
            inputsAvailable.take(sourceLimit).forEach { inputs.put(fileHeader(it)) }
            page.put("sourceFiles", inputs).put("moreSourceFiles", inputsAvailable.size > sourceLimit)
                .put("sourceUnavailable", readable.size < sources.size)
                .put("hasRequestAttachments", current.optJSONObject("ownerWork")?.optBoolean("hasInitialAttachments") == true)
        }
        return page
    }

    private fun search(args: JSONObject, control: RunControl, currentConversation: CurrentConversation?): JSONObject {
        fields(args, setOf("query", "cursor", "limit", "kind") +
            (if (currentConversation != null) setOf("conversationScope") else emptySet()))
        val query = args.opt("query") as? String ?: error("请输入查找文字")
        require(query.length <= 500 && '\u0000' !in query) { "查找文字请控制在 500 字以内" }
        val kind = if (args.has("kind")) args.opt("kind") as? String ?: error("查找对象无效") else "all"
        require(kind in setOf("all", "work", "conversation", "artifact", "project")) { "查找对象无效" }
        val normalized = normalize(query)
        val limit = integer(args, "limit", 24, 8, 40)
        require(limit in 8..40) { "查找每页数量无效" }
        if (currentConversation != null) {
            require(kind == "conversation" && normalized.isNotEmpty()) { "请输入要在此对话里找的文字" }
            val cursor = if (args.has("cursor")) args.opt("cursor") as? String ?: error("查找位置无效") else ""
            require(cursor.length <= 4000) { "此对话的查找位置已失效，请重新查找" }
            val assistant = host.features.module("assistant") as? AssistantModule ?: error("私人原文尚未准备")
            val options = JSONObject().put("query", query).put("cursor", cursor).put("limit", limit / 2).put("maxChars", 6000)
                .put("conversationScope", currentConversation.view())
            val page = assistant.searchOwnerOriginals(options, control)
            val items = JSONArray()
            page.getJSONArray("entries").objects().forEach { row ->
                val locator = row.getJSONObject("ownerLocator")
                check(row.opt("conversationId") == currentConversation.id && locator.opt("kind") == "owner_native_original" &&
                    locator.opt("conversationId") == currentConversation.id) { "原文范围已变化" }
                items.put(originalSearchItem(row))
            }
            val notices = JSONArray()
            if (page.getJSONArray("gaps").length() > 0) notices.put("此对话的部分本机原文无法核对，未用其他内容代替。")
            return JSONObject().put("items", items).put("nextCursor", page.getString("nextCursor")).put("notices", notices)
                .put("kind", "conversation").put("query", query).put("conversationScope", currentConversation.view())
                .put("executionAuthorized", false).put("modelCalled", false).put("coverage", "current_conversation_native_originals")
        }
        val count = if (kind == "all") limit / 4 else limit
        val signature = digest(normalized + "\n" + kind)
        val cursor = args.optString("cursor")
        require(cursor.length <= 16000) { "查找位置已失效，请重新查找" }
        val position = if (cursor.isBlank()) JSONObject().put("v", 2).put("query", signature) else decode(cursor).also {
            require(it.optInt("v") == 2 && it.optString("query") == signature) { "查询或对象已变化，请重新查找" }
        }
        val sources = when (kind) {
            "work" -> setOf("tasks")
            "conversation" -> setOf("titles", "originals")
            "artifact" -> setOf("files")
            "project" -> setOf("projects")
            else -> setOf("tasks", "titles", "originals", "files", "projects")
        }
        val items = JSONArray()
        val notices = JSONArray()
        var taskPage: JSONObject? = null
        fun source(key: String, read: () -> Unit) {
            if (key !in sources || position.optBoolean(key + "Done")) return
            control.check()
            try { read() } catch (failure: InterruptedException) { throw failure }
            catch (failure: Exception) {
                val label = mapOf("tasks" to "工作", "titles" to "对话目录", "originals" to "对话原文", "files" to "文件成果", "projects" to "项目")[key] ?: "记录"
                notices.put("$label 暂未读全：${failure.message ?: "请稍后重试"}")
            }
        }
        fun next(key: String, value: String) { position.put(key, value).put(key + "Done", value.isEmpty()) }
        source("tasks") {
            val page = host.tasks.ownerWorkSearchPage(query, position.optString("tasks"), count)
            taskPage = page
            page.getJSONArray("tasks").objects().forEach { task ->
                items.put(JSONObject().put("kind", "work").put("id", task.getString("id")).put("taskId", task.getString("id"))
                    .put("title", clip(task.optString("goal").ifBlank { "已保存的工作" }, 160))
                    .put("excerpt", clip(task.optString("summary"), 300)).put("status", task.optString("status"))
                    .put("updatedAt", task.optLong("updatedAt")).put("conversationId", task.optString("conversationId")))
            }
            next("tasks", page.optString("nextCursor"))
        }
        val assistant = host.features.module("assistant") as? AssistantModule
        source("titles") {
            check(assistant != null) { "私人对话尚未准备" }
            val quota = if (normalized.isEmpty()) count else (count + 1) / 2
            var page: JSONObject
            var matches: List<JSONObject>
            var reads = 0
            do {
                page = assistant.listOriginalConversations(position.optString("titles"), quota)
                matches = page.optJSONArray("conversations").objects().filter { normalized.isEmpty() || normalize(it.optString("title")).contains(normalized) }
                next("titles", page.optString("nextCursor")); reads++; control.check()
            } while (matches.isEmpty() && !position.optBoolean("titlesDone") && reads < 10)
            matches.forEach { row -> items.put(JSONObject().put("kind", "conversation").put("id", row.getString("conversationId"))
                .put("conversationId", row.getString("conversationId")).put("title", clip(row.optString("title").ifBlank { "私人对话" }, 160))
                .put("updatedAt", row.optLong("updatedAt"))) }
            if (page.optBoolean("retentionLimitApplied")) notices.put("旧版对话目前只能查找已保留的目录。")
        }
        if (normalized.isEmpty()) position.put("originalsDone", true)
        else source("originals") {
            check(assistant != null) { "私人原文尚未准备" }
            val page = assistant.searchOwnerOriginals(JSONObject().put("query", query).put("cursor", position.optString("originals"))
                .put("limit", count / 2).put("maxChars", 6000), control)
            page.optJSONArray("entries").objects().forEach { row -> items.put(originalSearchItem(row)) }
            next("originals", page.optString("nextCursor"))
            if ((page.optJSONArray("gaps")?.length() ?: 0) > 0) notices.put("部分旧原文已删除或无法核对；没有用缓存内容代替。")
        }
        source("files") {
            val snapshot = host.ownerOutcomeSnapshot(null, control)
            val rows = (snapshot.optJSONArray("artifacts").objects() + snapshot.optJSONArray("sources").objects()).distinctBy { it.optString("id") }
            val page = catalog(rows, position.optJSONObject("files"), normalized, count, "name")
            page.first.forEach { row -> items.put(JSONObject().put("kind", "artifact").put("id", row.getString("id")).put("artifactId", row.getString("id"))
                .put("sha256", row.getString("sha256")).put("title", clip(row.optString("name").ifBlank { "已保存的文件" }, 160))
                .put("excerpt", row.optString("mimeType")).put("updatedAt", timestamp(row)).put("taskId", row.optString("sourceTaskId"))) }
            position.put("files", page.second).put("filesDone", page.second == null)
        }
        source("projects") {
            val workspace = host.features.module("workspace") ?: error("项目库尚未准备")
            val page = catalog(workspace.snapshot().optJSONArray("projects").objects(), position.optJSONObject("projects"), normalized, count, "name")
            page.first.forEach { row -> items.put(JSONObject().put("kind", "project").put("id", row.getString("id")).put("projectId", row.getString("id"))
                .put("title", clip(row.optString("name").ifBlank { "已保存的项目" }, 160)).put("updatedAt", timestamp(row))) }
            position.put("projects", page.second).put("projectsDone", page.second == null)
        }
        val more = sources.any { !position.optBoolean(it + "Done") }
        return JSONObject().put("items", items).put("nextCursor", if (more) encode(position) else "").put("notices", notices)
            .put("kind", kind).put("query", query)
            .put("taskPage", taskPage).put("executionAuthorized", false).put("modelCalled", false)
            .put("coverage", "private_conversation_originals_task_summaries_file_metadata_and_project_names")
    }

    private fun originalSearchItem(row: JSONObject): JSONObject {
        val conversation = row.getString("conversationId")
        return JSONObject().put("kind", "conversation").put("id", conversation + ":" + row.getString("recordId"))
            .put("conversationId", conversation).put("title", "对话中的原文").put("excerpt", clip(row.optString("body"), 300))
            .put("updatedAt", row.optLong("at")).put("recordId", row.getString("recordId"))
            .put("ownerLocator", row.getJSONObject("ownerLocator")).put("readRange", row.getJSONObject("readRange"))
    }

    private fun catalog(rows: List<JSONObject>, cursor: JSONObject?, query: String, limit: Int, title: String): Pair<List<JSONObject>, JSONObject?> {
        val sorted = rows.filter { query.isEmpty() || normalize(it.optString(title)).contains(query) }
            .sortedWith(compareByDescending<JSONObject> { timestamp(it) }.thenByDescending { it.optString("id") })
            .filter { cursor == null || timestamp(it) < cursor.getLong("at") || timestamp(it) == cursor.getLong("at") && it.optString("id") < cursor.getString("id") }
        val page = sorted.take(limit)
        val next = if (sorted.size > limit) page.last().let { JSONObject().put("at", timestamp(it)).put("id", it.getString("id")) } else null
        return page to next
    }
    private fun integer(args: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        if (!args.has(key)) return default
        val value = args.opt(key)
        require((value is Int || value is Long) && (value as Number).toLong() in min.toLong()..max.toLong()) { "查找读取范围无效" }
        return (value as Number).toInt()
    }
    private fun timestamp(row: JSONObject) = row.optLong("updatedAt", row.optLong("createdAt"))
    private fun fields(args: JSONObject, allowed: Set<String>) = require(args.keys().asSequence().all { it in allowed }) { "查找参数包含不支持的字段" }
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
    private fun clip(value: String, limit: Int): String = if (value.length <= limit) value else value.take(if (value[limit - 1].isHighSurrogate()) limit - 1 else limit) + "…"
    private fun digest(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))
    private fun encode(value: JSONObject) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().toByteArray(StandardCharsets.UTF_8))
    private fun decode(value: String) = try { JSONObject(String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)) }
        catch (failure: Exception) { throw IllegalArgumentException("查找位置已失效，请重新查找", failure) }
}
