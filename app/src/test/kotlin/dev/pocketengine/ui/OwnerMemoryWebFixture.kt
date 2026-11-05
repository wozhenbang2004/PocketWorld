package dev.pocketengine.ui

import dev.pocketengine.ui.web.PocketWebKnowledge
import dev.pocketengine.ui.web.PocketWebMemoryHistory
import org.json.JSONObject
import org.junit.Assert.*

/** Real Controller/native owner/Book/VWS fixture; only the browser transport is invoked directly. */
internal class OwnerMemoryWebFixture : AutoCloseable {
    val native = ProjectMemoryReviewUiNativeTest.Fixture()
    val history = PocketWebMemoryHistory(native.controller)
    val knowledge = PocketWebKnowledge(native.controller)
    init {
        PocketWorldController::class.java.getDeclaredField("voiceForeground").apply { isAccessible = true }.setBoolean(native.controller, true)
        native.enter()
    }
    class Reply {
        var calls = 0; var value: JSONObject? = null; var error: String? = null
        val callback: (JSONObject?, String?) -> Unit = { value, error -> calls++; this.value = value; this.error = error }
    }
    fun start(action: String, args: JSONObject = JSONObject()): Reply = Reply().also { reply ->
        try { if (action in PocketWebMemoryHistory.COMMANDS) history.dispatch(action, args, reply.callback) else knowledge.dispatch(action, args, reply.callback) }
        catch (failure: Exception) { reply.callback(null, failure.message) }
    }
    fun finish(reply: Reply): Reply {
        repeat(30) { if (reply.calls == 0) native.settle() }
        assertEquals("Every original Web command must settle once", 1, reply.calls); return reply
    }
    fun request(action: String, args: JSONObject = JSONObject()): JSONObject {
        val result = finish(start(action, args)); assertNull(result.error, result.error); return result.value ?: error("Missing original result")
    }
    fun rejected(action: String, args: JSONObject = JSONObject()) {
        val reply = finish(start(action, args)); assertNull(reply.value); assertFalse(reply.error.isNullOrBlank())
    }
    fun session() = request("memoryHistory.open", JSONObject().put("view", "history")).getString("sessionHandle")
    fun search(session: String, query: String) = request("memoryHistory.search", JSONObject().put("sessionHandle", session).put("query", query).put("cursor", ""))
    fun read(header: JSONObject) = request("memoryHistory.record", JSONObject().put("recordHandle", header.getString("recordHandle")))
    override fun close() { history.clear(); knowledge.clear(); native.close() }
}
