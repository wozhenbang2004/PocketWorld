package dev.pocketengine.ui

import android.os.Bundle
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.project.ProjectGitImport
import org.json.JSONObject
import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal data class ProjectGitImportState(
    val open: Boolean = false, val busy: Boolean = false, val review: JSONObject? = null,
    val sourceAction: String? = null, val fetch: JSONObject? = null, val publicationAction: String? = null,
    val publication: JSONObject? = null, val checkout: JSONObject? = null,
    val notice: String? = null, val error: String? = null
)

/** Native owner input only. Persisted IDs restore read positions, not the opaque
 * confirmation, token, grant, fetch, publication or a new execution attempt. */
internal class ProjectGitImportCoordinator(
    private val app: EngineApplication, private val io: ExecutorService, private val main: Handler,
    private val privateOwner: () -> Boolean, private val navigation: () -> String,
    private val openCheckout: (JSONObject) -> Unit
) {
    var state by mutableStateOf(ProjectGitImportState()); private set
    private var generation = 0L
    private var foreground = true
    private var closed = false
    private var observation: RunControl? = null
    private var future: Future<*>? = null
    private var fetchReview: ProjectGitImport.Review? = null
    private var fetchReviewControl: RunControl? = null
    private var publicationReview: LocalModule.GitPublicationReview? = null
    private var publicationControl: RunControl? = null
    private fun owner() { check(!closed && foreground && privateOwner()) { "请在本机私人项目页操作Git导入" } }
    private fun local() = app.features.module("local") as? LocalModule ?: error("原本机项目模块不可用")
    fun open() { owner(); state = state.copy(open = true) }
    private fun publicationStatus() = state.publication?.let { it.optString("originalTaskStatus", it.optString("status")) }.orEmpty()
    private fun pending() = state.sourceAction != null && state.fetch?.optString("status") !in setOf("completed", "failed") || state.publicationAction != null && publicationStatus() != "completed"
    fun review(url: String, branch: String, name: String, username: String, token: String) {
        owner(); if (state.busy || pending() || state.review != null) return
        clearReview()
        val native = RunControl(); fetchReviewControl = native
        val user = username.takeIf { it.isNotEmpty() }?.toCharArray(); val secret = token.takeIf { it.isNotEmpty() }?.toCharArray()
        val wipe = Runnable { user?.let { Arrays.fill(it, '\u0000') }; secret?.let { Arrays.fill(it, '\u0000') } }
        native.onCancel(wipe)
        work(onFailure = { native.cancel(); if (fetchReviewControl === native) fetchReviewControl = null }) { c ->
            c.check()
            val selected = try { local().gitImportsForOwner().review(url, branch, name, user, secret, native) } finally { wipe.run(); native.removeCancelAction(wipe) }
            val visible = selected.metadata()
            return@work { fetchReview = selected; state = state.copy(review = visible.put("reviewKind", "fetch"), error = null, notice = "请核对来源、分支、工具和认证范围；此时尚未下载或运行仓库代码。") }
        }
    }
    fun cancelReview() { clearReview(); state = state.copy(review = null) }
    fun confirm() {
        owner(); if (state.busy) return
        fetchReview?.let { review ->
            val confirmed = review.confirm(); fetchReview = null; fetchReviewControl = null
            state = state.copy(review = null, sourceAction = confirmed.actionId(), fetch = null, publicationAction = null, publication = null, checkout = null, notice = "已确认这一次读取。原编号已保留；确认结果前不会重新下载或重送认证。")
            work { _ -> val row = app.features.submitGitImportForOwner(confirmed); return@work { state = state.copy(fetch = row) } }
            return
        }
        publicationReview?.let { review ->
            val confirmed = review.confirm(); publicationReview = null; publicationControl = null
            state = state.copy(review = null, publicationAction = confirmed.actionId(), publication = null, checkout = null, notice = "已确认从固定Git版本创建新项目。原编号已保留；未确认的发布只读核对，不重复创建。")
            work { _ -> val row = app.features.submitGitPublicationForOwner(confirmed); return@work { state = state.copy(publication = row) } }
        }
    }
    fun querySource(id: String = state.sourceAction.orEmpty(), stop: Boolean = false) {
        owner(); if (state.busy || !id.matches(Regex("[a-f0-9-]{36}"))) return
        clearReview()
        work { _ -> val actual = app.features.gitImportForOwner(id, stop); val published = app.features.gitPublicationForSourceForOwner(id)
            return@work { state = state.copy(sourceAction = id, fetch = actual, review = null, publicationAction = if (published.optBoolean("found")) published.getString("taskId") else state.publicationAction, publication = if (published.optBoolean("found")) published else state.publication, notice = when { stop -> "已请求停止原读取与连接；取消不代表此前Git文件写入已回滚。原结果仍可核对。"; actual.optString("status") == "completed" && actual.optBoolean("sourceAvailable", true) == false -> "原读取曾已完成，但来源缓存已变化或不可用；不会伪造当前原件或重新下载。"; actual.optString("status") == "completed" -> "已读取原分支的完整固定版本。请另行审阅后创建新项目，尚未运行代码。"; actual.optString("status") == "failed" -> "原读取已明确结束，但没有成功生成可发布项目。临时文件保留供核对。"; else -> "正在核对同一原操作，不新建任务或重新传送认证。" }) }
        }
    }
    fun reviewPublication() {
        owner(); if (state.busy || state.review != null || state.publicationAction != null) return
        val id = state.sourceAction ?: return
        check(state.fetch?.optString("status") == "completed" && state.fetch?.optBoolean("sourceAvailable", true) != false) { "请先核对原Git读取完成结果" }
        val original = RunControl(); publicationControl = original
        work(onFailure = { original.cancel(); if (publicationControl === original) publicationControl = null }) { c ->
            val source = app.features.gitImportResultForOwner(id, c)
            val selected = local().reviewGitPublicationForOwner(source, original); val visible = selected.metadata()
            return@work { publicationReview = selected; state = state.copy(review = visible.put("reviewKind", "publish"), error = null, notice = "这次只创建新的项目工作副本，保留同版Git历史和执行位。不会恢复来源凭据、任务或执行授权。") }
        }
    }
    fun queryPublication(stop: Boolean = false) {
        owner(); if (state.busy) return
        val id = state.publicationAction ?: return
        work { _ -> val actual = app.features.gitPublicationForOwner(id, stop)
            return@work { state = state.copy(publication = actual, checkout = actual.takeIf { it.optBoolean("found") && it.optString("id").isNotEmpty() }, notice = if (actual.optBoolean("found")) "已找到原发布创建的新项目，没有重复创建。请明确打开后，再开始新的开发任务。" else if (stop) "已请求停止后续复制；已发布的项目不会回滚，请核对原结果。" else "原发布结果尚未完整确认；保留原编号，只查询，不重新复制。") }
        }
    }
    fun continueProject() {
        owner(); if (state.busy) return
        val pin = state.checkout ?: return; val action = state.publicationAction ?: return; val source = state.sourceAction ?: return; val intended = navigation()
        work { c -> c.check(); val actual = local().projectCheckoutForHost(pin.getString("id")); val origin = actual.getJSONObject("sourceImport")
            check(actual.getString("projectId") == pin.getString("projectId") && origin.getString("clientId") == action && origin.getJSONObject("gitOriginal").getString("actionId") == source) { "原Git项目版本无法核对，未切换其他项目" }
            return@work { if (navigation() == intended) { closePage(); openCheckout(actual) } else state = state.copy(notice = "新项目已保留；你刚切换了位置，因此没有替你跳回。可再次明确打开。") }
        }
    }
    fun forgetPosition() { owner(); if (state.busy || state.review != null) return; clearReview(); state = ProjectGitImportState(open = true, notice = "仅清除了页面定位，没有取消任务、删除缓存或撤销效果。原未决操作仍由原生账本拒绝新读取；旧动作不会重放。") }
    fun closePage() { cancelRead(); clearReview(); state = state.copy(open = false, busy = false, review = null) }
    fun unmount() { closePage() }
    fun foreground(visible: Boolean) { foreground = visible; if (!visible) { cancelRead(); clearReview(); state = state.copy(busy = false, review = null) } }
    fun detach() { closed = true; cancelRead(); clearReview() }
    fun save(bundle: Bundle) { state.sourceAction?.let { bundle.putString("projectGitImportSourceAction", it) }; state.publicationAction?.let { bundle.putString("projectGitImportPublicationAction", it) } }
    fun restore(bundle: Bundle?) { state = state.copy(sourceAction = bundle?.getString("projectGitImportSourceAction"), publicationAction = bundle?.getString("projectGitImportPublicationAction"), notice = if (bundle?.getString("projectGitImportSourceAction") != null) "只恢复了原读取与发布编号；没有恢复令牌、确认或执行权限。请先只读核对。" else null) }
    private fun clearReview() { fetchReview?.close(); fetchReview = null; fetchReviewControl?.cancel(); fetchReviewControl = null; publicationReview?.close(); publicationReview = null; publicationControl?.cancel(); publicationControl = null }
    private fun cancelRead() { generation++; observation?.cancel(); observation = null; future?.cancel(true); future = null }
    private fun work(onFailure: (() -> Unit)? = null, read: (RunControl) -> (() -> Unit)) {
        owner(); if (state.busy) return
        val epoch = ++generation; val c = RunControl(); observation = c; state = state.copy(busy = true, error = null)
        try { future = io.submit {
            try { val accepted = read(c); c.check(); main.post { if (!closed && epoch == generation && foreground && privateOwner()) { observation = null; future = null; state = state.copy(busy = false); accepted() } } }
            catch (failure: Exception) { main.post { if (!closed && epoch == generation) { onFailure?.invoke(); observation = null; future = null; state = state.copy(busy = false, error = failure.message?.take(400) ?: "原Git结果未确认，请核对原编号。") } } }
        } } catch (failure: java.util.concurrent.RejectedExecutionException) { onFailure?.invoke(); observation = null; future = null; state = state.copy(busy = false, error = "页面读取队列未接受这次观察，原操作编号保留。") }
    }
}
