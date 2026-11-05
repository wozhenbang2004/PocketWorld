package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskReceiptVisibilityTest {
    private fun record(extra: JSONObject, receipt: JSONObject): TaskExecutionPresentation.Record {
        val row = JSONObject().put("operationId", "op").put("tool", "project_execute")
            .put("state", "receipt").put("status", "available")
            .put("arguments", JSONObject().put("private", "hidden-argument")).put("receipt", receipt)
        for (key in extra.keySet()) row.put(key, extra.get(key))
        return TaskExecutionPresentation.records(JSONObject().put("entries", JSONArray().put(row)), "operations").single()
    }

    @Test fun unreadBodiesCannotReappearInNativePresentation() {
        for (guard in listOf(JSONObject().put("readLimited", true), JSONObject().put("bodyAvailable", false), JSONObject().put("status", "read_limited"))) {
            val shown = record(guard, JSONObject().put("ok", true).put("content", "hidden-receipt"))
            assertEquals("", shown.arguments)
            assertEquals("", shown.receipt)
            assertTrue(shown.limited)
            assertTrue(shown.outputNotes.isEmpty())
            assertNull(shown.projectExecutionId)
        }
    }

    @Test fun runningStateCannotHideCanonicalFailure() {
        val shown = record(JSONObject().put("state", "project_running"), JSONObject().put("ok", false).put("status", "failed"))
        assertEquals(TaskExecutionPresentation.Tone.ERROR, shown.tone)
    }

    @Test fun runningStateCannotHideOwnerIntervention() {
        val shown = record(JSONObject().put("state", "project_running"), JSONObject().put("ok", true).put("requiresPause", true))
        assertEquals(TaskExecutionPresentation.Tone.WAITING, shown.tone)
        assertTrue(shown.label.contains("需要你处理"))
    }

    @Test fun unreadEventBodiesCannotReappearInNativePresentation() {
        for (guard in listOf(JSONObject().put("readLimited", true), JSONObject().put("bodyAvailable", false), JSONObject().put("status", "read_limited"))) {
            val row = JSONObject().put("position", 4).put("status", "available")
                .put("event", JSONObject().put("type", "tool").put("message", "hidden-event"))
            for (key in guard.keySet()) row.put(key, guard.get(key))
            val shown = TaskExecutionPresentation.records(JSONObject().put("entries", JSONArray().put(row)), "events").single()
            assertEquals("", shown.receipt)
            assertTrue(shown.limited)
            assertTrue(shown.outputNotes.isEmpty())
            val event = JSONObject().put("type", "tool").put("status", "running").put("message", "hidden-event")
            for (key in guard.keySet()) event.put(key, guard.get(key))
            val current = TaskExecutionPresentation.current(JSONObject().put("status", "running").put("events", JSONArray().put(event)), 0)
            assertFalse(current.detail.contains("hidden-event"))
        }
    }
}
