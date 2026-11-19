package dev.pocketengine.ui.web

import dev.pocketengine.ui.ProjectGitImportCoordinator
import org.json.JSONObject
import java.net.URI

/** UI access to the original owner Git operation. This route accepts public URLs
 * only and never receives credentials, runs code, or makes a network request on open. */
internal class PocketWebProjectGitImport(
    private val original: ProjectGitImportCoordinator,
    private val current: () -> Boolean
) {
    companion object {
        val COMMANDS = setOf("projects.gitImport.open", "projects.gitImport.close",
            "projects.gitImport.review", "projects.gitImport.confirm", "projects.gitImport.dismissReview",
            "projects.gitImport.querySource", "projects.gitImport.stopSource", "projects.gitImport.reviewCreate",
            "projects.gitImport.queryCreate", "projects.gitImport.openProject", "projects.gitImport.clearPosition")
    }

    fun snapshot(): JSONObject? {
        val state = original.state
        if (!current()) return null
        val review = state.review
        val result = state.fetch?.optJSONObject("result")
        return JSONObject().put("open", state.open).put("busy", state.busy)
            .put("notice", state.notice).put("error", state.error)
            .put("sourceStatus", state.fetch?.optString("status")).put("sourceAvailable", state.fetch?.optBoolean("sourceAvailable", true))
            .put("sourcePending", state.sourceAction != null)
            .put("creationPending", state.publicationAction != null).put("projectReady", state.checkout != null)
            .put("creationFailed", state.publication?.let { it.optString("originalTaskStatus", it.optString("status")) } == "failed")
            .put("reviewKind", review?.optString("reviewKind")).put("reviewId", review?.optString("actionId"))
            .put("expiresAt", review?.optLong("expiresAt")).put("reviewHost", review?.optJSONObject("target")?.optString("host"))
            .put("reviewUrl", review?.optJSONObject("target")?.optString("url")).put("draftBranch", review?.optString("branch"))
            .put("projectName", review?.optString("projectName")?.takeIf { it.isNotBlank() } ?: result?.optString("projectName"))
            .put("branch", result?.optString("branch")).put("commit", result?.optString("commitId"))
            .put("sourceBytes", result?.optLong("sourceBytes"))
    }

    fun close() {
        if (original.state.open) original.closePage()
    }

    fun dispatch(action: String, args: JSONObject): JSONObject {
        val fields = when (action) {
            "projects.gitImport.review" -> setOf("url", "branch", "name")
            "projects.gitImport.confirm" -> setOf("reviewId")
            else -> emptySet()
        }
        require(args.keys().asSequence().toSet() == fields) { "项目导入参数不完整" }
        if (action == "projects.gitImport.close") {
            close()
            return JSONObject().put("closed", true)
        }
        check(current()) { "请回到本机私人项目目录" }
        if (action == "projects.gitImport.open") {
            original.open()
            return JSONObject().put("opened", true)
        }
        check(original.state.open) { "原项目导入页已关闭" }
        when (action) {
            "projects.gitImport.review" -> {
                val url = text(args, "url", 2048)
                val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("请输入公开 HTTPS 仓库地址") }
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null) { "请输入不含凭据、查询参数或片段的公开 HTTPS 仓库地址" }
                original.review(url, text(args, "branch", 120), text(args, "name", 100), "", "")
            }
            "projects.gitImport.confirm" -> {
                val id = text(args, "reviewId", 64)
                val review = original.state.review ?: error("原审阅已关闭，请重新核对")
                check(review.optString("actionId") == id) { "已不是这次原审阅，请重新核对" }
                original.confirm()
            }
            "projects.gitImport.dismissReview" -> original.cancelReview()
            "projects.gitImport.querySource" -> original.querySource()
            "projects.gitImport.stopSource" -> original.querySource(stop = true)
            "projects.gitImport.reviewCreate" -> original.reviewPublication()
            "projects.gitImport.queryCreate" -> original.queryPublication()
            "projects.gitImport.openProject" -> original.continueProject()
            "projects.gitImport.clearPosition" -> {
                val state = original.state
                val createFailed = state.publication?.let { it.optString("originalTaskStatus", it.optString("status")) } == "failed"
                val sourceEndedWithoutUsableResult = state.publicationAction == null && (state.fetch?.optString("status") == "failed" ||
                    state.fetch?.optString("status") == "completed" && state.fetch?.optBoolean("sourceAvailable", true) == false)
                check(state.checkout != null || createFailed || sourceEndedWithoutUsableResult) { "原导入结果还不能丢弃，请先核对" }
                original.forgetPosition()
            }
            else -> error("未知的 Git 导入操作")
        }
        return JSONObject().put("requested", true)
    }

    private fun text(args: JSONObject, key: String, max: Int): String {
        val value = (args.opt(key) as? String)?.trim().orEmpty()
        require(value.isNotEmpty() && value.length <= max && value.none { it.isISOControl() }) { "请补全 Git 导入信息" }
        return value
    }
}
