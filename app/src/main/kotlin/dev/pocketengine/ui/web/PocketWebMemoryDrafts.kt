package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.memory.NativeConversationMemoryDraftHost
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** Only the native original-operation review holds adoption authority; Web receives selectors. */
internal class PocketWebMemoryDrafts(private val controller: PocketWorldController) {
    private data class Window(val id: String, val review: NativeConversationMemoryDraftHost.Review, val owner: Any, val control: RunControl)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var window: Window? = null
    @Volatile private var generation = 0L
    private var busy = false

    companion object { val COMMANDS = setOf("memoryDraft.open", "memoryDraft.source", "memoryDraft.confirm", "memoryDraft.cancel", "memoryDraft.query", "memoryDraft.close") }

    fun clear() {
        ++generation; busy = false
        val old = window; window = null; old?.control?.cancel()
        if (old != null) runCatching { controller.runPluginCatalogWork(Runnable { old.review.close() }) }
    }
    private fun fields(args: JSONObject, vararg allowed: String) { require(args.keys().asSequence().all { it in allowed }) { "原记忆审阅包含不适用字段" } }
    private fun text(args: JSONObject, key: String, max: Int = 128): String {
        val value = args.opt(key); require(value is String && value.isNotBlank() && value.length <= max && '\u0000' !in value) { "请从原提案重新打开" }; return value
    }
    private fun epoch(args: JSONObject): Int { val value = args.opt("epoch"); require(value is Int && value > 0) { "原提案执行代次无效" }; return value }
    private fun current(value: Window) = window === value && controller.webPrivateOwnerVisible() && controller.webWindowTicket() == value.owner && !value.control.isCancelled
    private fun selection(args: JSONObject) { fields(args, "taskId", "runId", "epoch", "operationId", "proposalDigest") }
    private fun presentation(row: JSONObject) = JSONObject().put("proposal", row.getJSONObject("proposal")).put("apply", row.getJSONObject("apply"))

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到原私人回答审阅记忆提案" }
        if (action == "memoryDraft.close") { fields(args, "handle"); if (window?.id == text(args, "handle")) clear(); done(JSONObject().put("closed", true), null); return }
        check(!busy) { "请等待原审阅回执" }
        if (action == "memoryDraft.open" || action == "memoryDraft.query") {
            selection(args)
            val task = text(args, "taskId"); val run = text(args, "runId"); val epoch = epoch(args); val operation = text(args, "operationId")
            val digest = text(args, "proposalDigest", 64); require(digest.matches(Regex("[a-f0-9]{64}")))
            clear(); val request = generation; val owner = controller.webWindowTicket(); busy = true
            controller.runPluginCatalogWork(Runnable {
                var acquired: NativeConversationMemoryDraftHost.Review? = null
                val answer = runCatching {
                    check(controller.webPrivateOwnerVisible() && controller.webWindowTicket() == owner && generation == request)
                    val host = controller.conversationMemoryDraftHost()
                    val row = host.queryForOwner(task, run, epoch, operation)
                    check(row.getJSONObject("proposal").getString("proposalDigest") == digest) { "原提案内容已变化" }
                    val result = presentation(row)
                    if (action == "memoryDraft.open" && row.getJSONObject("apply").getString("status") == "pending_owner") {
                        acquired = host.reviewForOwner(task, run, epoch, operation, digest)
                        result.put("review", acquired!!.view())
                    }
                    result
                }
                main.post {
                    if (request != generation || !controller.webPrivateOwnerVisible() || controller.webWindowTicket() != owner) { acquired?.close(); done(null, "原记忆审阅窗口已变化，未接入迟到内容"); return@post }
                    busy = false
                    answer.fold({ result ->
                        acquired?.let { native -> Window(UUID.randomUUID().toString(), native, owner, RunControl()).also { window = it; result.put("handle", it.id) } }
                        done(PocketWebState.publicValue(result) as JSONObject, null)
                    }, { acquired?.close(); done(null, it.message ?: "原记忆草稿暂不可读取") })
                }
            }); return
        }
        val original = window?.takeIf { it.id == text(args, "handle") } ?: error("原记忆审阅已关闭，请查询原提案")
        check(current(original)) { "原主人窗口已变化" }
        when (action) {
            "memoryDraft.source" -> { fields(args, "handle", "index", "offset"); require(args.opt("index") is Int && args.getInt("index") >= 0); require(args.opt("offset") is Int && args.getInt("offset") in 0..4000000) }
            "memoryDraft.confirm", "memoryDraft.cancel" -> fields(args, "handle")
            else -> error("不支持的原记忆审阅操作")
        }
        val input = JSONObject(args.toString()); busy = true
        controller.runPluginCatalogWork(Runnable {
            val answer = runCatching {
                check(current(original)) { "原审阅窗口已变化" }; original.control.check()
                when (action) {
                    "memoryDraft.source" -> {
                        val refs = original.review.view().getJSONArray("sourceRefs"); val index = input.getInt("index"); require(index < refs.length())
                        val selected = refs.getJSONObject(index); val actual = controller.readOriginalMemorySource(selected); original.control.check()
                        val text = actual.getString("text"); val offset = input.getInt("offset"); require(offset <= text.length && (offset == text.length || !Character.isLowSurrogate(text[offset])))
                        var end = minOf(offset + 8000, text.length); if (end < text.length && end > offset && Character.isHighSurrogate(text[end - 1])) end--
                        JSONObject().put("title", actual.optString("title", "原来源")).put("text", text.substring(offset, end)).put("offset", offset).put("nextOffset", end).put("totalChars", text.length).put("hasMore", end < text.length).put("sourceRef", selected).put("index", index)
                    }
                    "memoryDraft.confirm" -> original.review.confirm(original.control)
                    else -> original.review.cancel().let(::presentation)
                }
            }
            main.post {
                if (!current(original)) { done(null, "原窗口已关闭；请按原任务查询保存结果，不要重复提交"); return@post }
                busy = false
                if (action != "memoryDraft.source") { window = null; original.review.close() }
                answer.fold({ done(PocketWebState.publicValue(it) as JSONObject, null) }, { done(null, it.message ?: "原操作结果尚未核对，请查询原提案") })
            }
        })
    }
}
