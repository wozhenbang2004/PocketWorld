package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.assistant.AssistantModule
import dev.pocketengine.ui.PocketWorldController
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
    companion object { val COMMANDS = setOf("work.search", "work.search.read", "work.tasks.list", "work.capabilities", "webSearch.usage", "ownerWork.report.read") }
    private val main = Handler(Looper.getMainLooper())
    private val host get() = controller.nativeButlerHost()
    private val pending = mutableSetOf<RunControl>()

    fun clear() { pending.toList().forEach { it.cancel() }; pending.clear() }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        try {
            check(controller.webPrivateOwnerVisible()) { "请回到私人工作区查找自己的记录" }
            val window = captureWindow()
            val control = RunControl()
            pending.add(control)
            try {
                controller.runPluginCatalogWork(Runnable {
                    val result = runCatching {
                        control.check()
                        check(acceptsWindow(window) && controller.webPrivateOwnerVisible()) { "原查找窗口已关闭" }
                        when (action) {
                            "ownerWork.report.read" -> {
                                fields(args, setOf("taskId", "runId", "epoch", "reportMessageId", "offset", "maxChars", "expectedReportRevision", "expectedReportFingerprint"))
                                host.tasks.ownerWorkReportForOwner(args, control)
                            }
                            "work.search" -> search(args, control)
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
                                fields(args, setOf("taskId", "query", "expectedRunId", "expectedEpoch"))
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
                                val normalized = normalize(query)
                                check(normalized.isEmpty() || normalize(task.optString("goal") + "\n" + task.optString("summary")).contains(normalized)) { "原工作已变化，请重新搜索" }
                                JSONObject().put("taskId", id).put("readOnly", true).put("executionAuthorized", false)
                                    .put("taskPage", JSONObject().put("auditBodyIncluded", false).put("tasks", JSONArray().put(JSONObject().put("id", id))))
                            }
                            "work.search.read" -> {
                                fields(args, setOf("ownerLocator", "offset", "maxChars"))
                                val assistant = host.features.module("assistant") as? AssistantModule ?: error("私人原文尚未准备")
                                val offset = integer(args, "offset", 0, 0, Int.MAX_VALUE)
                                val maximum = integer(args, "maxChars", 6000, 64, 12000)
                                require(offset >= 0 && maximum in 64..12000) { "原文读取范围无效" }
                                assistant.readOwnerSearchHit(JSONObject().put("ownerLocator", args.getJSONObject("ownerLocator"))
                                    .put("offset", offset).put("maxChars", maximum), control)
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
                        if (control.isCancelled || !acceptsWindow(window) || !controller.webPrivateOwnerVisible())
                            done(null, "原查找窗口已变化，请重新读取")
                        else result.fold({ value ->
                            try {
                                value.optJSONObject("taskPage")?.let(rememberTasks)
                                value.remove("taskPage")
                                done(PocketWebState.publicValue(value) as JSONObject, null)
                            } catch (failure: Exception) { done(null, failure.message ?: "原记录不可核对") }
                        }, { done(null, it.message ?: "记录暂时无法读取，请重试") })
                    }
                })
            } catch (failure: Exception) { pending.remove(control); control.cancel(); throw failure }
        } catch (failure: Exception) { done(null, failure.message ?: "查找暂不可用") }
    }

    private fun search(args: JSONObject, control: RunControl): JSONObject {
        fields(args, setOf("query", "cursor", "limit", "kind"))
        val query = args.opt("query") as? String ?: error("请输入查找文字")
        require(query.length <= 500 && '\u0000' !in query) { "查找文字请控制在 500 字以内" }
        val kind = if (args.has("kind")) args.opt("kind") as? String ?: error("查找对象无效") else "all"
        require(kind in setOf("all", "work", "conversation", "artifact", "project")) { "查找对象无效" }
        val normalized = normalize(query)
        val limit = integer(args, "limit", 24, 8, 40)
        require(limit in 8..40) { "查找每页数量无效" }
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
            page.optJSONArray("entries").objects().forEach { row ->
                val conversation = row.getString("conversationId")
                items.put(JSONObject().put("kind", "conversation").put("id", conversation + ":" + row.getString("recordId"))
                    .put("conversationId", conversation).put("title", "对话中的原文").put("excerpt", clip(row.optString("body"), 300))
                    .put("updatedAt", row.optLong("at")).put("recordId", row.getString("recordId"))
                    .put("ownerLocator", row.getJSONObject("ownerLocator")).put("readRange", row.getJSONObject("readRange")))
            }
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
