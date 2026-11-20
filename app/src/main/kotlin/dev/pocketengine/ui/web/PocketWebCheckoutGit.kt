package dev.pocketengine.ui.web

import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.project.ProjectGit
import dev.pocketengine.features.local.project.ProjectSources
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONArray
import org.json.JSONObject

/** Same ordinary private file window; only the existing fixed Git status and working-tree diff actions. */
internal class PocketWebCheckoutGit(private val project: String, private val checkout: String) {
    private data class Page(val operation: String, val data: JSONObject)
    private data class Original(val request: ProjectGit.OwnerRequest, val args: JSONObject)
    private var status: Page? = null
    private var diff: Page? = null
    private var pending: Original? = null

    companion object {
        private fun local(controller: PocketWorldController) =
            controller.nativeButlerHost().features.module("local") as? LocalModule ?: error("本机 Git 宿主尚不可用")
        fun available(controller: PocketWorldController, checkout: String) = local(controller).hasOrdinaryGitDirectoryForOwner(checkout)
    }

    fun read(controller: PocketWorldController, input: JSONObject, current: () -> Boolean): JSONObject {
        check(current()) { "原工作副本文件窗口已关闭" }
        controller.projectDevelopmentForWeb().requireOrdinaryFileOwner(project, checkout)
        val kind = input.getString("kind")
        val allowed = when (kind) {
            "status", "query" -> setOf("kind")
            "nextStatus" -> setOf("kind", "statusOperation")
            "diff" -> setOf("kind", "statusOperation", "path")
            "nextDiff" -> setOf("kind", "statusOperation", "diffOperation")
            else -> error("仅支持读取工作副本 Git 变更")
        }
        require(input.keys().asSequence().toSet() == allowed)
        val host = controller.nativeButlerHost().features
        val original: Original
        var result: JSONObject
        if (kind == "query") {
            original = pending ?: error("没有待核对的原 Git 读取")
            result = try { host.projectGitForOwner(original.request.requestId, false) }
            catch (_: Exception) { return view("原 Git 读取仍待核对，未重新执行。") }
        } else {
            check(pending == null) { "先核对同一笔 Git 读取，不重复执行" }
            check(available(controller, checkout)) { "这个副本没有可读取的本地 Git 目录" }
            val previous = if (kind == "status") null else statusPage(input.getString("statusOperation"))
            val args = when (kind) {
                "status" -> JSONObject().put("gitAction", "status")
                "nextStatus" -> {
                    check(previous!!.data.optBoolean("truncated")) { "本页没有后续 Git 结果" }
                    JSONObject().put("gitAction", "status").put("gitPin", previous.data.getJSONObject("pin"))
                        .put("gitOffset", previous.data.getInt("nextOffset"))
                }
                else -> {
                    val prior = if (kind == "nextDiff") diff?.takeIf { it.operation == input.getString("diffOperation") && it.data.optBoolean("truncated") }
                        ?: error("原差异已变化，不读取另一份") else null
                    val path = prior?.data?.getString("path") ?: input.getString("path")
                    val row = selected(previous!!, path)
                    check(canDiff(row)) { "只比较常规文件的工作树修改，不打开子模块或外部差异工具" }
                    JSONObject().put("gitAction", "diff").put("gitLayer", "index_worktree").put("path", path)
                        .put("gitPin", previous.data.getJSONObject("pin")).putOpt("gitOffset", prior?.data?.getInt("nextOffset"))
                }
            }
            val request = local(controller).reviewGitForOwner(checkout, args)
            val review = request.review()
            check(current() && request.projectId == project && request.checkoutId == checkout &&
                request.action == args.getString("gitAction") && review.opt("writesGit") == false && review.opt("network") == false) {
                "原工作副本或只读 Git 范围已变化"
            }
            check(review.optBoolean("available")) { "这台手机的本机 Git 工具尚未就绪；没有运行命令" }
            original = Original(request, args)
            pending = original
            if (kind == "status") status = null
            if (kind == "status" || kind == "diff") diff = null
            result = try { host.submitProjectGitForOwner(request.confirm()) }
            catch (_: Exception) { return view("读取回执待核对，未另外开始。") }
            // Only query the same issued native operation. A slower reader remains explicitly queryable.
            repeat(3) {
                if (result.optJSONObject("git")?.optString("code") == "git_original_process_pending" && current()) {
                    Thread.sleep(120)
                    check(current()) { "原工作副本文件窗口已关闭" }
                    result = try { host.projectGitForOwner(request.requestId, false) }
                    catch (_: Exception) { return view("原 Git 读取仍待核对，未重新执行。") }
                }
            }
        }
        check(current() && result.optString("projectId") == project && result.optString("checkoutId") == checkout &&
            result.optString("taskId") == original.request.requestId && result.optString("operationId") == original.request.requestId &&
            result.optString("planDigest") == original.request.planDigest && result.opt("replayAllowed") == false) { "未取得这个副本的原 Git 回执" }
        val observed = result.getJSONObject("git")
        val code = observed.optString("code")
        if (code == "git_original_process_pending" || observed.optBoolean("publicationUnknown")) return view("原 Git 读取尚未确认，可继续核对，不会重新执行。")
        pending = null
        if (observed.opt("ok") != true) {
            if (code in setOf("git_review_stale", "git_state_changed_during_read", "git_worktree_changed_during_read", "git_file_changed_during_read")) {
                status = null; diff = null
                return view("Git 或工作树已变化，请重新读取这份副本的状态。")
            }
            return view(when (code) {
                "git_diff_not_utf8" -> "这个差异不是可显示的 UTF-8 文字，原文件未改。"
                "git_repository_layout_unsupported", "git_metadata_link_unsupported", "git_config_execution_unsupported" ->
                    "这个仓库的 Git 布局或配置不支持本机差异读取。仍可浏览和编辑副本文件。"
                else -> "这次没有取得可读 Git 结果。文件仍可从原列表浏览或编辑。"
            })
        }
        val data = observed.getJSONObject("data")
        val a = original.args
        check(observed.optString("action") == original.request.action && data.opt("readOnly") == true &&
            data.optInt("offset", if (data.opt("repositoryPresent") == false) 0 else -1) == a.optInt("gitOffset") &&
            (!a.has("gitPin") || ProjectSources.canonical(data.getJSONObject("pin")) == ProjectSources.canonical(a.getJSONObject("gitPin")))) {
            "Git 回执不是本次原页"
        }
        val item = Page(original.request.requestId, data)
        if (original.request.action == "status") { status = item; diff = null }
        else {
            check(data.optString("path") == a.getString("path") && data.optString("layer") == "index_worktree" &&
                (a.optInt("gitOffset") == 0 || data.optString("diffSha256") == diff?.data?.optString("diffSha256"))) { "原差异已变化，未混合不同结果" }
            diff = item
        }
        return view()
    }

    fun shaForEditor(statusOperation: String, path: String): String {
        val row = selected(statusPage(statusOperation), path)
        check(canOpen(row)) { "原 Git 项不是现存的可编辑文件" }
        return row.getJSONObject("file").getString("sha256")
    }
    private fun statusPage(operation: String) = status?.takeIf { it.operation == operation && it.data.opt("repositoryPresent") == true }
        ?: error("原 Git 状态已变化，请重读已选副本")
    private fun rows(page: Page): List<JSONObject> {
        val rows = page.data.optJSONArray("entries") ?: return emptyList()
        return (0 until rows.length()).map { rows.getJSONObject(it) }.filter { row ->
            row.opt("sourceScope") == true && runCatching { ProjectGit.path(row.getString("path")); true }.getOrDefault(false)
        }
    }
    private fun selected(page: Page, path: String): JSONObject {
        val exact = ProjectGit.path(path)
        check(exact == path) { "Git 路径不是原文件的准确名称" }
        return rows(page).singleOrNull { it.optString("path") == exact } ?: error("无法核对所选原变更文件")
    }
    private fun regular(mode: String) = mode == "100644" || mode == "100755"
    private fun canDiff(row: JSONObject): Boolean {
        if (row.optString("worktreeStatus") == ".") return false
        val index = row.getJSONArray("indexEntries")
        val mode = row.getJSONObject("file").getString("mode")
        // The unchanged native diff never reaches Gitlinks/symlinks: both the observed index
        // entries and the actual worktree file (or its absence) are known here.
        return (regular(mode) || (mode == "absent" && index.length() > 0)) &&
            (0 until index.length()).all { regular(index.getJSONObject(it).optString("mode")) }
    }
    private fun canOpen(row: JSONObject): Boolean {
        val file = row.getJSONObject("file")
        return regular(file.getString("mode")) && file.optLong("bytes", -1) in 0L..262144L &&
            file.optString("sha256").matches(Regex("[a-f0-9]{64}"))
    }
    private fun view(message: String? = null): JSONObject {
        val out = JSONObject().put("readOnly", true).put("executionAuthorized", false).putOpt("message", message)
        pending?.let { out.put("pendingOperation", it.request.requestId) }
        status?.let { page ->
            val clean = rows(page).map { row -> JSONObject().put("path", row.getString("path"))
                .put("indexStatus", row.getString("indexStatus")).put("worktreeStatus", row.getString("worktreeStatus"))
                .put("conflict", row.getBoolean("conflict")).put("canDiff", canDiff(row)).put("canOpen", canOpen(row)) }
            out.put("status", JSONObject().put("operationId", page.operation).put("repositoryPresent", page.data.getBoolean("repositoryPresent"))
                .put("entries", JSONArray(clean)).put("more", page.data.optBoolean("truncated")))
        }
        diff?.let { page -> out.put("diff", JSONObject().put("operationId", page.operation).put("path", page.data.getString("path"))
            .put("text", page.data.getString("diff")).put("more", page.data.optBoolean("truncated"))) }
        return out
    }
}
