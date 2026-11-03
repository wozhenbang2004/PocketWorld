package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.memory.checkedFinalAnswerSourceReceipt
import dev.pocketengine.ui.memory.finalAnswerSourceSelection
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Bounded presentation of the existing original-answer source reader. */
internal class PocketWebSources(private val controller: PocketWorldController) {
    private val main = Handler(Looper.getMainLooper())
    private data class Window(val id: String, val answer: JSONObject, val current: () -> JSONObject?, val owner: Any)
    private var window: Window? = null
    private var read: RunControl? = null
    private val pins = linkedMapOf<String, JSONObject>()
    private var generation = 0L
    fun clear() { ++generation; read?.cancel(); read = null; window = null; pins.clear() }
    fun close(id: String) { if (window?.id == id) clear() }

    fun open(messageId: String?, taskId: String?, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请在原私人回答中查看来源" }
        require((messageId != null) != (taskId != null)) { "请选择一条原回答或原任务" }
        val current: () -> JSONObject? = if (messageId != null) {
            { controller.state.messages.firstOrNull { it.id == messageId }?.let(::finalAnswerSourceSelection) }
        } else { { controller.state.tasks.firstOrNull { it.id == taskId }?.let(::finalAnswerSourceSelection) } }
        val answer = current() ?: error("这条原回答没有可核对的来源身份")
        clear(); window = Window(UUID.randomUUID().toString(), JSONObject(answer.toString()), current, controller.webWindowTicket())
        read(window!!.id, null, 0, 0, done)
    }

    fun read(id: String, source: String?, cursor: Int, offset: Int, done: (JSONObject?, String?) -> Unit) {
        val original = window?.takeIf { it.id == id } ?: error("原来源窗口已关闭")
        require(cursor in 0..1_000_000 && offset in 0..4_000_000) { "来源读取范围无效" }
        check(current(original)) { "原回答或主人窗口已变化" }
        val pin = source?.let { pins[it]?.let { selected -> JSONObject(selected.toString()) } ?: error("请从原来源列表选择保留的修订") }
        read?.cancel(); val control = RunControl(); read = control; val observed = ++generation
        val request = JSONObject().put("answer", JSONObject(original.answer.toString())).put("readId", UUID.randomUUID().toString())
            .put("cursor", cursor).put("offset", offset).put("maxChars", 8000).also { if (pin != null) it.put("source", pin) }
        try { controller.runPluginCatalogWork(Runnable {
            val result = runCatching { control.check(); checkedFinalAnswerSourceReceipt(request, controller.readFinalAnswerSources(request, control)).also { control.check() } }
            main.post {
                if (generation != observed || window !== original || control.isCancelled || !current(original)) { done(null, "原来源窗口已变化，迟到内容没有接到当前回答"); return@post }
                read = null
                result.fold({ value ->
                    val view = JSONObject(value.toString()).put("windowId", original.id)
                    val entries = view.optJSONArray("entries") ?: JSONArray()
                    for (index in 0 until entries.length()) {
                        val row = entries.getJSONObject(index); val selected = row.optJSONObject("pin") ?: continue
                        val handle = UUID.randomUUID().toString(); pins[handle] = JSONObject(selected.toString()); row.put("sourceHandle", handle)
                    }
                    while (pins.size > 80) pins.remove(pins.keys.first())
                    done(PocketWebState.publicValue(view, images = true) as JSONObject, null)
                }, { done(null, it.message ?: "原保留来源暂不可读") })
            }
        }) } catch (failure: Exception) { control.cancel(); if (read === control) read = null; done(null, failure.message ?: "原来源读取未接受") }
    }

    private fun current(original: Window): Boolean = controller.webPrivateOwnerVisible() && controller.webWindowTicket() == original.owner &&
        original.current()?.toString() == original.answer.toString()
}
