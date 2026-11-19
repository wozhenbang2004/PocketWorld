package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.ssh.SshModule
import dev.pocketengine.store.SshWorkHistory
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import org.json.JSONObject
import java.util.UUID

/** A page of local original task references, not a connection, command or new task owner. */
internal class PocketWebSshHistory(private val controller: PocketWorldController) {
    private data class Page(val profile: String, val binding: String, val window: Pair<Triple<Long, Int, Long>, Long>,
                            var token: String = "", var position: String = "", var rows: Map<String, Pair<String, Int>> = emptyMap(), var pending: Boolean = false)
    @Volatile private var page: Page? = null
    private val main = Handler(Looper.getMainLooper())
    fun clear() { page = null }

    private fun selected(p: Page) = (controller.webPluginFeatureHost().module("ssh") as? SshModule)?.isOriginalOwnerBinding(p.profile, p.binding) == true
    private fun visible(p: Page) = page === p && controller.webPrivateOwnerVisible() && controller.webWindowTicket() == p.window && selected(p)
    private fun string(args: JSONObject, key: String, max: Int, blank: Boolean = false): String {
        val v = args.opt(key)
        require(v is String && v.length <= max && '\u0000' !in v && (blank || v.isNotBlank())) { "原主机工作读取参数无效" }
        return v
    }
    fun read(args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(args.keys().asSequence().toSet() == setOf("profileId", "bindingHash", "cursor")) { "原主机工作范围无效" }
        val profile = string(args, "profileId", 80); val binding = string(args, "bindingHash", 64); val cursor = string(args, "cursor", 80, true)
        require(profile.matches(Regex("[A-Za-z0-9_-]{1,80}")) && binding.matches(Regex("[a-f0-9]{64}")))
        val p = if (cursor.isEmpty()) Page(profile, binding, controller.webWindowTicket()).also { page = it }
                else page ?: error("原主机工作目录已关闭")
        check(visible(p) && p.profile == profile && p.binding == binding && !p.pending && (cursor.isEmpty() || cursor == p.token)) { "主机配置、页面或读取位置已变化，请重新打开当前主机" }
        val position = p.position
        p.pending = true
        try { controller.runPluginCatalogWork(Runnable {
            val result = runCatching {
                // Don't overlap the SSH module lock with TaskStore's DB lock. Check the saved binding at both ends.
                check(page === p && selected(p)) { "原主机配置已变化" }
                controller.nativeButlerHost().tasks.originalSshWorkPage(p.profile, p.binding, position) {
                    check(page === p) { "原主机工作目录已关闭" }
                }
            }
            main.post {
                p.pending = false
                if (!visible(p)) { done(null, "原主机或页面已变化，没有返回旧连接记录"); return@post }
                try {
                    val actual = result.getOrThrow()
                    val items = actual.getJSONArray("items"); val more = actual.getBoolean("hasMore")
                    p.rows = items.objects().associate { it.getString("taskId") to (it.getString("runId") to it.getInt("epoch")) }
                    p.position = actual.getString("position"); p.token = if (more) UUID.randomUUID().toString() else ""
                    done(JSONObject().put("format","pocketworld.ssh-work-page.v1").put("profileId", profile).put("bindingHash", binding)
                        .put("items", items).put("hasMore", more).put("nextCursor", p.token).put("readOnly", true).put("executionAuthorized", false).put("networkUsed", false), null)
                } catch (e: Exception) { done(null, e.message ?: "这页本机主机工作未读完整") }
            }
        }) } catch (e: Exception) { p.pending = false; throw e }
    }
    internal data class Open(val current: () -> Boolean, val verify: (JSONObject) -> Unit)
    fun opener(args: JSONObject): Open {
        require(args.keys().asSequence().toSet() == setOf("profileId", "bindingHash", "taskId", "runId", "epoch"))
        val p = page ?: error("原主机工作目录已关闭")
        val id = string(args,"taskId",160); val run = string(args,"runId",160); val epoch = args.opt("epoch")
        require(epoch is Number && epoch.toInt() >= 0 && epoch.toDouble() == epoch.toInt().toDouble())
        val seen = run to epoch.toInt()
        check(visible(p) && !p.pending && p.profile == string(args,"profileId",80) && p.binding == string(args,"bindingHash",64) && p.rows[id] == seen) { "请重新读取这个主机的原工作，不会打开其他记录" }
        return Open({ visible(p) }, { source ->
            check(visible(p) && p.rows[id] == seen && source.opt("id") == id && source.opt("runId") == run && source.optInt("coordinatorEpoch",-1) == seen.second &&
                SshWorkHistory.matches(source, p.profile, p.binding)) { "原任务执行或主机绑定已变化，没有切换到其他工作" }
        })
    }
}
