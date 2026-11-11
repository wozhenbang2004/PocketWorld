package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.local.project.ProjectFileDraft
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Native owner adapter for the existing private-file draft/cache and SHA-CAS
 * writer. Formal versions use a separate, single-use native import review;
 * neither path authorizes live source edits or code execution. */
internal class PocketWebCheckoutFiles(private val controller: PocketWorldController) {
    private class Page(val handle: String, val project: String, val checkout: String, val parentCurrent: () -> Boolean) {
        var opened: Opened? = null
        var adoption: Adoption? = null
    }
    private class Adoption(val handle: String, val file: Opened, val revision: Long, val review: JSONObject, val expires: Long)
    private class Opened(val handle: String, var draft: ProjectFileDraft) {
        var sequence = 0L
        var saveUnknown = false
    }
    private val main = Handler(Looper.getMainLooper())
    private val store = controller.projectWorkbench.fileDrafts
    @Volatile private var page: Page? = null
    private var busy = false
    companion object {
        val COMMANDS = setOf("projects.development.files.open", "projects.development.files.list", "projects.development.files.read",
            "projects.development.files.edit", "projects.development.files.save", "projects.development.files.resolve",
            "projects.development.files.cache", "projects.development.files.close", "projects.development.files.reviewImport",
            "projects.development.files.import", "projects.development.files.dismissImport")
    }
    fun clear() { val old = page; page = null; busy = false; old?.opened?.let { store.release(it.draft) } }
    private fun current(original: Page) = page === original && original.parentCurrent() && controller.webPrivateOwnerVisible() && controller.state.projectId == original.project
    private fun fields(args: JSONObject, vararg allowed: String) = require(args.keys().asSequence().all { it in allowed }) { "副本文件操作包含未声明字段" }
    private fun text(args: JSONObject, key: String, max: Int = 80, blank: Boolean = false): String {
        val value = args.opt(key); require(value is String && value.length <= max && '\u0000' !in value && (blank || value.isNotBlank())) { "请核对$key" }; return value
    }
    private fun number(args: JSONObject, key: String): Long {
        val value = args.opt(key); require(value is Int || value is Long) { "请核对$key" }; return (value as Number).toLong()
    }
    private fun selected(args: JSONObject): Page {
        val original = page; check(original != null && current(original) && original.handle == text(args, "filesHandle")) { "原私人副本文件窗口已关闭，请重新打开" }; return original
    }
    private fun opened(original: Page, args: JSONObject): Opened {
        val file = original.opened; check(file != null && file.handle == text(args, "fileHandle")) { "原文件已切换，请重新读取" }; return file
    }
    private fun requireDraft(file: Opened) {
        val value = file.draft
        check(store.open(value.projectId, value.checkoutId, value.path, value.creating) === value) { "另一编辑器已更新这份草稿，当前文字仍保留，请重新读取后比较" }
    }
    private fun info(original: Page, file: Opened, body: Boolean): JSONObject {
        val value = file.draft
        val result = JSONObject().put("filesHandle", original.handle).put("fileHandle", file.handle).put("projectId", original.project)
            .put("checkoutId", original.checkout).put("path", value.path).put("revision", value.revision).put("editSequence", file.sequence)
            .put("dirty", value.dirty()).put("conflict", value.conflict()).put("baseHash", value.baseHash).put("saveUnknown", file.saveUnknown)
            .put("draftDurable", store.durable(value)).put("draftError", store.persistenceError(value) ?: "")
            .put("projectVersionWritten", false).put("executionAuthorized", false)
        if (body) result.put("text", value.text).put("baseContent", value.baseContent).put("latest", value.latestReview() ?: JSONObject.NULL)
        return result
    }
    private fun work(original: Page, done: (JSONObject?, String?) -> Unit, block: () -> JSONObject) {
        check(!busy) { "请等待原文件操作回执" }; busy = true
        try { controller.runPluginCatalogWork(Runnable {
            val result = runCatching { check(current(original)) { "原副本窗口已关闭，未开始排队操作" }; block() }
            main.post {
                if (page === original) busy = false
                if (!current(original)) done(null, "原副本窗口已变化；已有草稿保留，保存结果请重新读取原文件核对")
                else result.fold({ value ->
                    if (value.has("importStatus")) runCatching { controller.requestFeature("workspace", "list", JSONObject().put("projectId", original.project)) }
                        .onFailure { value.put("projectListRefreshMessage", "正式文件列表尚未刷新；请重新选择原项目读取，不重复采纳。") }
                    done(PocketWebState.publicValue(value) as JSONObject, null)
                }, { done(null, it.message ?: "原文件操作未完成，草稿仍保留") })
            }
        }) } catch (failure: Exception) { busy = false; throw failure }
    }
    private fun listing(original: Page, after: String, expected: String): JSONObject {
        val source = controller.projectDevelopmentForWeb().filesForOwner(original.project, original.checkout)
        val digest = source.getString("sourceDigest")
        check(after.isBlank() || expected == digest) { "副本文件在翻页期间变化，请重新读取列表，不拼接不同版本" }
        val all = sortedMapOf<String, JSONObject>(); val files = source.getJSONArray("files")
        for (i in 0 until files.length()) { val row = files.getJSONObject(i); all[row.getString("path")] = JSONObject(row.toString()) }
        var cacheError = ""
        try { for (path in store.dirtyPaths(original.project, original.checkout)) all.getOrPut(path) { JSONObject().put("path", path).put("change", "draft_only") }.put("hasDraft", true) }
        catch (failure: Exception) { cacheError = failure.message ?: "部分保留草稿暂无法读取，未删除" }
        val selected = all.entries.filter { it.key > after }.take(41)
        return JSONObject().put("filesHandle", original.handle).put("projectId", original.project).put("checkoutId", original.checkout)
            .put("sourceDigest", digest).put("files", JSONArray(selected.take(40).map { it.value })).put("fileCount", source.getInt("fileCount"))
            .put("nextPath", if (selected.size > 40) selected[39].key else "").put("draftError", cacheError).put("readOnly", true)
    }
    fun dispatch(action: String, args: JSONObject, project: String, parentCurrent: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        if (action == "projects.development.files.close") {
            fields(args, "viewHandle", "filesHandle")
            if (page?.handle == text(args, "filesHandle")) clear()
            done(JSONObject().put("closed", true).put("taskStopped", false), null); return
        }
        if (action == "projects.development.files.open") {
            fields(args, "viewHandle", "checkoutId"); val checkout = text(args, "checkoutId")
            check(parentCurrent() && controller.webPrivateOwnerVisible()) { "原项目窗口已关闭" }
            clear(); val original = Page(UUID.randomUUID().toString(), project, checkout, parentCurrent); page = original
            work(original, done) { listing(original, "", "") }; return
        }
        val original = selected(args)
        if (action in setOf("projects.development.files.list", "projects.development.files.read", "projects.development.files.edit",
                "projects.development.files.resolve", "projects.development.files.save")) original.adoption = null
        when (action) {
            "projects.development.files.dismissImport" -> {
                fields(args, "viewHandle", "filesHandle", "importHandle")
                if (original.adoption?.handle == text(args, "importHandle")) original.adoption = null
                done(JSONObject().put("dismissed", true).put("projectVersionWritten", false), null)
            }
            "projects.development.files.reviewImport" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle"); val file = opened(original, args)
                val revision = synchronized(store) {
                    requireDraft(file); check(!file.draft.dirty() && !file.saveUnknown) { "先保存或处理这份草稿，再比较实际副本与正式版本" }; file.draft.revision
                }
                original.adoption = null
                work(original, done) {
                    val reviewed = controller.projectDevelopmentForWeb().reviewFileImportForOwner(original.project, original.checkout, file.draft.path)
                    synchronized(store) { requireDraft(file); check(file.draft.revision == revision && file.draft.baseHash == reviewed.getString("expectedHash")) { "副本或草稿在比较期间变化，请读取同一文件重新核对" } }
                    check(current(original) && original.opened === file) { "原文件采纳窗口已变化，没有准备替代文件审阅" }
                    val entry = Adoption(UUID.randomUUID().toString(), file, revision, JSONObject(reviewed.toString()), SystemClock.elapsedRealtime() + 120_000)
                    original.adoption = entry
                    JSONObject(reviewed.toString()).put("filesHandle", original.handle).put("fileHandle", file.handle).put("importHandle", entry.handle)
                        .put("expiresAt", System.currentTimeMillis() + 120_000).put("projectVersionWritten", false).put("executionAuthorized", false)
                }
            }
            "projects.development.files.import" -> {
                fields(args, "viewHandle", "filesHandle", "importHandle")
                val entry = original.adoption
                check(entry != null && entry.handle == text(args, "importHandle") && entry.expires > SystemClock.elapsedRealtime() && original.opened === entry.file) { "原采纳审阅已过期或换页，请重新比较" }
                check(entry.review.optBoolean("importable") && !entry.review.optBoolean("truncated")) { "这份差异当前不能采纳，原版本未改变" }
                original.adoption = null // Consume before dispatch, including an unknown acknowledgement.
                work(original, done) {
                    try {
                        val result = controller.projectDevelopmentForWeb().importFileForOwner(original.project, original.checkout, entry.file.draft.path, entry.review) {
                            current(original) && original.opened === entry.file && entry.expires > SystemClock.elapsedRealtime() && synchronized(store) { requireDraft(entry.file); entry.file.draft.revision == entry.revision && !entry.file.draft.dirty() }
                        }
                        info(original, entry.file, true).also { reply ->
                            reply.remove("projectVersionWritten")
                            if (result.opt("projectVersionCommitted") is Boolean) reply.put("projectVersionWritten", result.getBoolean("projectVersionCommitted"))
                        }.put("importStatus", "observed").put("importReceipt", result)
                    } catch (failure: Exception) {
                        info(original, entry.file, true).also { it.remove("projectVersionWritten") }.put("importStatus", "unknown").put("importError", failure.message)
                            .put("importMessage", "没有取得完整采纳回执。原审阅已消费；请重新比较这个文件与正式版本，不自动重发或改写其他文件。")
                    }
                }
            }
            "projects.development.files.list" -> {
                fields(args, "viewHandle", "filesHandle", "afterPath", "sourceDigest")
                val after = text(args, "afterPath", 240, true); val expected = text(args, "sourceDigest", 64, true)
                work(original, done) { listing(original, after, expected) }
            }
            "projects.development.files.read" -> {
                fields(args, "viewHandle", "filesHandle", "path"); val path = text(args, "path", 240)
                work(original, done) {
                    val actual = controller.projectDevelopmentForWeb().fileForOwner(original.project, original.checkout, path)
                    val value = synchronized(store) {
                        val prior = store.open(original.project, original.checkout, path, actual.optBoolean("deleted"))
                        prior.observe(actual).also { store.retain(it) }
                    }
                    if (!current(original)) { store.release(value); error("原文件读取窗口已变化，草稿已保留") }
                    original.opened?.let { store.release(it.draft) }
                    val file = Opened(UUID.randomUUID().toString(), value); original.opened = file
                    info(original, file, true)
                }
            }
            "projects.development.files.edit" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle", "editSequence", "text")
                check(!busy) { "原文件正在核对或保存，当前编辑未替换" }; val file = opened(original, args)
                val sequence = number(args, "editSequence")
                require(sequence > 0 && sequence <= 1_000_000) { "文件编辑序号无效" }
                val value = text(args, "text", 262144, true)
                synchronized(store) {
                    requireDraft(file)
                    if (sequence > file.sequence) { val next = file.draft.edit(value); store.retain(next); file.draft = next; file.sequence = sequence }
                }
                done(info(original, file, false), null)
            }
            "projects.development.files.cache" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle", "retry"); val file = opened(original, args)
                check(!busy) { "请等待原文件回执" }
                require(args.opt("retry") is Boolean)
                synchronized(store) { requireDraft(file); if (args.getBoolean("retry")) store.retry(file.draft) }
                done(info(original, file, false), null)
            }
            "projects.development.files.resolve" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle", "revision", "choice")
                check(!busy) { "请等待原文件回执" }; val file = opened(original, args)
                val choice = text(args, "choice"); require(choice in setOf("keep_mine", "use_latest"))
                synchronized(store) {
                    requireDraft(file); check(number(args, "revision") == file.draft.revision) { "草稿在比较后已变化，没有替换" }
                    val next = if (choice == "keep_mine") file.draft.keepMineAgainstLatest() else file.draft.useLatest()
                    store.retain(next); file.draft = next; file.saveUnknown = false
                }
                done(info(original, file, true), null)
            }
            "projects.development.files.save" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle", "revision", "editSequence"); val file = opened(original, args)
                val submitted = synchronized(store) {
                    requireDraft(file); check(!file.saveUnknown && number(args, "revision") == file.draft.revision && number(args, "editSequence") == file.sequence) { "编辑或保存状态已变化，请读取原文件核对" }
                    check(file.draft.dirty()) { "没有待保存的修改" }; file.draft.saveIntent()
                }
                work(original, done) {
                    try {
                        val result = controller.projectDevelopmentForWeb().saveFileForOwner(submitted) { current(original) && original.opened === file }
                        synchronized(store) {
                            val latest = store.open(original.project, original.checkout, submitted.path, false)
                            val observed = JSONObject(result.toString()).put("projectId", original.project).put("expectedHash", result.getString("sha256"))
                            val saved = latest.saved(submitted, result).observe(observed); store.retain(saved); file.draft = saved
                        }
                        info(original, file, true).put("saveStatus", "observed").put("message", "已按原SHA保存到这个私人副本并回读；没有导回正式项目，也没有运行代码")
                    } catch (failure: Exception) {
                        file.saveUnknown = true
                        info(original, file, true).put("saveStatus", "unknown").put("message", "原保存未取得完整回执，草稿仍保留。请读取同一文件核对，不自动再次保存。").put("saveError", failure.message)
                    }
                }
            }
        }
    }
}
