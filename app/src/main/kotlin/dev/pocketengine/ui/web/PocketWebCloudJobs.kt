package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.remote.RemoteModule
import dev.pocketengine.store.CloudWorkspaceJobs
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import org.json.JSONObject
import java.util.UUID

/** Reads the phone's original private tasks; holds one visible page, no old scopes, service requests or execution handles. */
internal class PocketWebCloudJobs(private val controller: PocketWorldController) {
    private val fields = setOf("profileId", "profileBinding", "environmentId", "worker", "workspaceAnchor")
    private data class Page(val target: JSONObject, val window: Pair<Triple<Long, Int, Long>, Long>,
                            var token: String = "", var position: String = "", var rows: Map<String, Pair<String, Int>> = emptyMap(), var pending: Boolean = false)
    @Volatile private var page: Page? = null
    private val main = Handler(Looper.getMainLooper())
    fun clear() { page = null }
    private fun selected(p: Page): Boolean = runCatching {
        (controller.webPluginFeatureHost().module("remote") as? RemoteModule)?.isCloudWorkspaceForOwner(p.target) == true
    }.getOrDefault(false)
    private fun visible(p: Page) = page === p && controller.webPrivateOwnerVisible() && controller.webWindowTicket() == p.window && selected(p)
    private fun target(args: JSONObject, vararg extra: String): JSONObject {
        require(args.keys().asSequence().toSet() == fields + extra) { "原云工作位置参数不符" }
        return JSONObject().also { t -> fields.forEach { t.put(it, args.get(it)) }; require(CloudWorkspaceJobs.valid(t)) { "无法核对原云工作位置" } }
    }
    fun read(args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val scope = target(args, "cursor"); val cursor = args.opt("cursor")
        require(cursor is String && cursor.length <= 80)
        val p = if (cursor.isEmpty()) Page(scope, controller.webWindowTicket()).also { page = it } else page ?: error("原云工作目录已关闭")
        check(visible(p) && CloudWorkspaceJobs.same(scope,p.target) && !p.pending && (cursor.isEmpty() || cursor == p.token)) { "工作位置或页面已变化，请重新读取" }
        val position = p.position; p.pending = true
        try { controller.runPluginCatalogWork(Runnable {
            val result = runCatching {
                check(page === p && selected(p)) { "原云位置已变化" }
                // Don't hold a module lock across the TaskStore lock. Recheck before presenting any private text.
                controller.nativeButlerHost().tasks.originalCloudWorkspacePage(p.target, position) { check(page === p) { "原云工作页面已关闭" } }
            }
            main.post {
                p.pending = false
                if (!visible(p)) { done(null,"原工作位置或页面已变化，未返回旧记录"); return@post }
                try {
                    val v = result.getOrThrow(); val items = v.getJSONArray("items"); val more = v.getBoolean("hasMore")
                    p.rows = items.objects().associate { it.getString("taskId") to (it.getString("runId") to it.getInt("epoch")) }
                    p.position = v.getString("position"); p.token = if (more) UUID.randomUUID().toString() else ""
                    done(JSONObject().put("scope",p.target).put("format","pocketworld.cloud-jobs-page.v1").put("items",items)
                        .put("hasMore",more).put("nextCursor",p.token).put("networkUsed",false).put("executionAuthorized",false),null)
                } catch (e: Exception) { done(null,e.message ?: "本机没有读完整这一页") }
            }
        }) } catch (e: Exception) { p.pending = false; throw e }
    }
    internal data class Open(val current: () -> Boolean, val verify: (JSONObject) -> Unit)
    fun opener(args: JSONObject): Open {
        val scope = target(args,"taskId","runId","epoch"); val id = args.getString("taskId"); val run = args.getString("runId"); val epoch = args.opt("epoch")
        require(id.length == 36 && run.length == 36 && epoch is Number && epoch.toInt() >= 0 && epoch.toDouble() == epoch.toInt().toDouble())
        val p = page ?: error("原云工作目录已关闭"); val seen = run to epoch.toInt()
        check(visible(p) && !p.pending && CloudWorkspaceJobs.same(scope,p.target) && p.rows[id] == seen) { "请重新读取原位置的工作" }
        return Open({ visible(p) }, { t ->
            check(visible(p) && p.rows[id] == seen && t.opt("id") == id && t.opt("runId") == run && t.optInt("coordinatorEpoch",-1) == seen.second
                && CloudWorkspaceJobs.matches(t,p.target)) { "原任务或工作位置变化，未转到另一项工作" }
        })
    }
}
