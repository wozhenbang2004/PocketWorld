package dev.pocketengine.ui

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.project.ProjectGit
import java.util.concurrent.ExecutorService
import org.json.JSONArray
import org.json.JSONObject

internal data class ProjectGitState(
    val projectId: String? = null, val checkoutId: String? = null, val busy: Boolean = false,
    val snapshot: JSONObject? = null, val diff: JSONObject? = null, val history: JSONObject? = null, val observation: JSONObject? = null,
    val review: JSONObject? = null, val originalId: String? = null, val selected: Set<String> = emptySet(),
    val reviewedStagedDiffs: Set<String> = emptySet(), val notice: String? = null, val error: String? = null,
    val visible: Boolean = false
) {
    val processPending: Boolean get() = observation?.optJSONObject("git")?.optString("code") == "git_original_process_pending"
    val uncertain: Boolean get() = originalId != null && observation == null || observation?.optJSONObject("git")?.optBoolean("publicationUnknown") == true
}

/** Native owner selection over the original task/approval/job. No model proxy, persisted grant or second ledger. */
internal class ProjectGitCoordinator(
    private val app: EngineApplication, private val io: ExecutorService, private val main: Handler,
    private val privateOwner: () -> Boolean, private val selectOriginalCheckout: (JSONObject) -> Unit = {}
) {
    var state by mutableStateOf(ProjectGitState()); private set
    private var generation = 0L
    private var mounted = false
    private var foreground = true
    private var closed = false
    private var pendingReview: ProjectGit.OwnerRequest? = null
    private var diffReadThrough = 0
    private var diffIdentity: String? = null
    private fun local() = app.features.module("local") as? LocalModule ?: error("项目工具尚未就绪")
    private fun owner() { check(!closed && mounted && foreground && privateOwner()) { "请回到当前私人项目的 Git 页面操作" } }
    fun activate(project: String, checkout: String) {
        check(!closed && privateOwner()) { "请回到私人项目" }
        mounted = true
        if (state.projectId != project || state.checkoutId != checkout) {
            generation++; pendingReview = null; diffIdentity = null; diffReadThrough = 0
            state = ProjectGitState(projectId = project, checkoutId = checkout, visible = foreground)
        } else state = state.copy(visible = foreground)
    }
    fun deactivate() { mounted = false; generation++; pendingReview = null; state = state.copy(busy = false, review = null, visible = false) }
    fun foreground(visible: Boolean) { foreground = visible; if (!visible) { generation++; pendingReview = null }; state = state.copy(busy = if (visible) state.busy else false, review = null, visible = visible && mounted) }
    fun close() { closed = true; deactivate() }
    fun select(path: String, selected: Boolean) {
        owner(); if (state.busy || state.review != null) return
        ProjectGit.path(path)
        val next = state.selected.toMutableSet(); if (selected) { check(next.size < ProjectGit.MAX_SELECTED || path in next) { "每次最多选择40个文件" }; next.add(path) } else next.remove(path)
        state = state.copy(selected = next, error = null)
    }
    fun can(action: String): Boolean = !state.busy && state.review == null && !state.processPending && !state.uncertain && runCatching {
        val snapshot = state.snapshot ?: error("先读取Git")
        ProjectGit.selectionRequest(snapshot, action, JSONArray(state.selected.toList()))
        action != "commit" || state.selected.all { it in state.reviewedStagedDiffs }
    }.getOrDefault(false)
    fun readStatus(nextPage: Boolean = false) {
        val prior = state.snapshot
        val args = JSONObject().put("gitAction", "status")
        if (nextPage) {
            if (prior == null || !prior.optBoolean("truncated")) return
            args.put("gitPin", prior.getJSONObject("pin")).put("gitOffset", prior.getInt("nextOffset"))
        }
        prepare(args, executeRead = true)
    }
    fun history(nextPage: Boolean = false) {
        val snapshot = state.snapshot ?: return
        val args = JSONObject().put("gitAction", "history").put("gitPin", snapshot.getJSONObject("pin"))
        if (nextPage) { val prior = state.history ?: return; if (!prior.optBoolean("truncated")) return; args.put("gitOffset", prior.getInt("nextOffset")) }
        prepare(args, executeRead = true)
    }
    fun diff(path: String, layer: String, nextPage: Boolean = false) {
        val snapshot = state.snapshot ?: return
        val args = JSONObject().put("gitAction", "diff").put("gitPin", snapshot.getJSONObject("pin")).put("path", ProjectGit.path(path)).put("gitLayer", layer)
        if (nextPage) {
            val prior = state.diff ?: return
            check(prior.getString("path") == path && prior.getString("layer") == layer) { "请重新打开所选差异" }
            args.put("gitOffset", prior.getInt("nextOffset"))
        }
        prepare(args, executeRead = true)
    }
    fun review(action: String, message: String = "", author: String = "", email: String = "") {
        val snapshot = state.snapshot ?: return
        val args = if (action == "init") JSONObject().put("gitAction", action).put("gitWorktreeSha256", snapshot.getString("worktreeSha256"))
        else ProjectGit.selectionRequest(snapshot, action, JSONArray(state.selected.toList()))
        if (action == "commit") {
            check(state.selected.all { it in state.reviewedStagedDiffs }) { "请先逐个读取所选文件的完整暂存差异，再审阅提交" }
            args.put("commitMessage", message).put("authorName", author).put("authorEmail", email)
        }
        prepare(args, executeRead = false)
    }
    fun reviewIsolation(baseCommit: String, branch: String) {
        val snapshot = state.snapshot ?: return
        val args = JSONObject().put("gitAction", "isolate").put("gitPin", snapshot.getJSONObject("pin"))
            .put("baseCommit", dev.pocketengine.features.local.project.ProjectGitIsolation.commit(baseCommit.trim()))
            .put("branch", dev.pocketengine.features.local.project.ProjectGitIsolation.branch(branch.trim()))
        prepare(args, executeRead = false)
    }
    fun selectRelated(target: String, selected: (String) -> Unit = {}) {
        owner(); val current = state.checkoutId ?: return
        work({ local().relatedGitCheckoutForOwner(current, target) }) { actual ->
            check(actual.getString("projectId") == state.projectId) { "副本选择的原项目已变化" }
            selectOriginalCheckout(actual); selected(actual.getString("id"))
        }
    }
    private fun prepare(args: JSONObject, executeRead: Boolean) {
        owner(); if (state.busy || state.processPending || state.review != null) return
        if (!executeRead && state.uncertain) { state = state.copy(error = "上一项变更结果仍待核对，不能另起Git变更。请读取原操作。"); return }
        val checkout = state.checkoutId ?: return
        work({ local().reviewGitForOwner(checkout, JSONObject(args.toString())) }) { request ->
            if (executeRead) submit(request) else { pendingReview = request; state = state.copy(review = request.review(), error = null) }
        }
    }
    fun dismissReview() { if (!state.busy) { pendingReview = null; state = state.copy(review = null) } }
    fun confirm() { owner(); if (state.busy) return; val request = pendingReview ?: return; submit(request) }
    private fun submit(request: ProjectGit.OwnerRequest) {
        owner(); check(request.checkoutId == state.checkoutId && request.projectId == state.projectId) { "Git审阅入口已变化" }
        val selected = request.confirm(); pendingReview = null
        state = state.copy(originalId = selected.requestId(), review = null, observation = null, error = null,
            notice = if (ProjectGit.writes(request.action)) "正在执行这次已确认的Git动作。未确认结果前不会自动重试。" else "正在读取本次Git结果。")
        work({ app.features.submitProjectGitForOwner(selected) }, ::apply)
    }
    fun query(id: String? = state.originalId, cancel: Boolean = false) {
        owner(); if (state.busy) return
        val original = id ?: return
        state = state.copy(originalId = original, review = null); pendingReview = null
        work({ app.features.projectGitForOwner(original, cancel) }, ::apply)
    }
    private fun apply(result: JSONObject) {
        check(result.getString("checkoutId") == state.checkoutId && result.getString("projectId") == state.projectId) { "原Git结果属于另一项目，没有切换当前选择" }
        val git = result.getJSONObject("git"); val data = git.optJSONObject("data"); val action = git.optString("action")
        var next = state.copy(observation = result, originalId = result.getString("operationId"), error = null,
            notice = when { git.optBoolean("publicationUnknown") -> "原操作的变更结果仍待核对；不会重复执行。"
                git.optString("code") == "git_original_process_pending" -> "原Git进程仍在执行，可以查看同一任务或明确停止。"
                !git.optBoolean("ok") -> gitMessage(git.optString("code"))
                else -> if (action == "isolate") "私有隔离副本已完成。原仓与未提交修改保留，选择后继续开发；不会自动合并。" else "已取得这次Git的真实结果。" })
        if (git.optBoolean("ok") && data != null) {
            if (action == "status") {
                val same = state.snapshot?.optJSONObject("pin")?.optString("snapshotDigest") == data.optJSONObject("pin")?.optString("snapshotDigest")
                next = next.copy(snapshot = data, diff = if (same) state.diff else null, history = if (same) state.history else null, selected = emptySet(), reviewedStagedDiffs = if (same) state.reviewedStagedDiffs else emptySet())
            } else if (action == "history") next = next.copy(history = data)
            else if (action == "diff") {
                val identity = data.getString("diffSha256") + ":" + data.getString("path") + ":" + data.getString("layer") + ":" + data.getJSONObject("pin").getString("snapshotDigest")
                if (data.getInt("offset") == 0) { diffIdentity = identity; diffReadThrough = data.getInt("nextOffset") }
                else if (identity == diffIdentity && data.getInt("offset") == diffReadThrough) diffReadThrough = data.getInt("nextOffset")
                val originalPin = state.snapshot?.optJSONObject("pin")?.optString("snapshotDigest")
                val read = data.getString("layer") == "head_index" && data.getJSONObject("pin").getString("snapshotDigest") == originalPin && identity == diffIdentity && diffReadThrough == data.getInt("totalBytes")
                next = next.copy(diff = data, reviewedStagedDiffs = if (read) state.reviewedStagedDiffs + data.getString("path") else state.reviewedStagedDiffs)
            } else if (ProjectGit.writes(action) && action != "isolate") next = next.copy(snapshot = null, diff = null, history = null, selected = emptySet(), reviewedStagedDiffs = emptySet(), notice = "Git动作已有确定结果。请重新读取状态；原工作树内容和未选文件保留。")
        }
        state = next
    }
    private fun <T> work(read: () -> T, accept: (T) -> Unit) {
        owner(); if (state.busy) return
        val token = generation; val checkout = state.checkoutId; val project = state.projectId
        state = state.copy(busy = true, error = null)
        try { io.execute {
            val result = runCatching(read)
            main.post {
                if (closed || token != generation || !mounted || !foreground || !privateOwner() || state.checkoutId != checkout || state.projectId != project) return@post
                state = state.copy(busy = false)
                result.fold({ value -> runCatching { accept(value) }.onFailure { failure -> state = state.copy(error = failure.message?.take(500) ?: "Git结果未完整确认；请读取原任务") } },
                    { failure -> state = state.copy(error = failure.message?.take(500) ?: "Git请求未完整返回；保留原编号供核对") })
            }
        } } catch (failure: Exception) { state = state.copy(busy = false, error = failure.message?.take(500)) }
    }
    private fun gitMessage(code: String): String = when (code) {
        "git_isolation_incomplete" -> "隔离创建未完整完成。原副本仍在，私有失败现场保留；只核对原任务，不自动重放或删除。"
        "git_isolation_branch_exists", "git_isolation_target_exists" -> "分支或目标已存在，未覆盖。请核对原操作和现有副本。"
        "git_review_stale", "git_state_changed_during_read", "git_worktree_changed_during_read" -> "Git或文件已变化；原内容没有被覆盖，请重新读取状态后再选择。"
        "git_diff_not_utf8" -> "原Git差异包含非UTF-8字节，这个文字视图不替换或修改二进制/非UTF-8内容。"
        "git_index_locked" -> "仓库已有其它索引锁，未删除该锁。请核对原Git操作。"
        "git_config_execution_unsupported", "git_config_syntax_unsupported", "git_repository_layout_unsupported" -> "这份仓库的布局或配置不支持安全的受控Git入口，未自动转换或换路径。"
        "git_conflict_requires_explicit_resolution", "git_unresolved_conflicts" -> "请先在文件编辑器核对并保存冲突解决稿，再明确标为解决。"
        "project_git_preflight_rejected" -> "审阅后执行条件已变化，本次未进入运行时；请重新核对。"
        else -> "这次Git没有取得成功结果。可查看同一任务日志；不要自动重试未知操作。"
    }
}
