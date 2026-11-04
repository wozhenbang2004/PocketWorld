package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeReminderSourceDraftTest {
    private fun source() = JSONObject().put("id", "original").put("revision", 3).put("manualOriginVersion", 1)
    private fun schedule() = JSONObject().put("type", "once").put("at", 200000L)

    @Test fun rescheduleDraftRetainsOnlyRestrictiveOriginalIdentity() {
        val original = source()
        val draft = reminderRuleDraft(null, "未来提醒", false, "", schedule(), "skip", "buffer_one", 3, original)
        assertEquals("original", draft.getJSONObject("rescheduleSource").getString("id"))
        assertEquals(3, draft.getJSONObject("rescheduleSource").getInt("revision"))
        assertFalse(draft.has("id")); assertFalse(draft.has("revision")); assertFalse(draft.has("nativeOrigin"))
    }

    @Test fun nativeProvenanceCannotBeCopiedThroughNativeDraft() {
        val original = source().put("nativeOrigin", JSONObject().put("taskId", "original-task"))
        assertThrows(IllegalArgumentException::class.java) { reminderRuleDraft(null, "旧来源", false, "", schedule(), "skip", "buffer_one", 3, original) }
        assertFalse(reminderRescheduleSourceCurrent(original, JSONArray().put(original)))
    }

    @Test fun lossyOldDisplayCannotProveThatCurrentSourceIsOrdinary() {
        val displayed = source()
        assertTrue(reminderRescheduleSourceCurrent(displayed, JSONArray().put(source())))
        assertFalse(reminderRescheduleSourceCurrent(displayed, JSONArray().put(source().put("nativeOrigin", JSONObject()))))
        assertFalse(reminderRescheduleSourceCurrent(displayed, JSONArray().put(source().put("sourceCheckPending", true))))
        assertFalse(reminderRescheduleSourceCurrent(displayed, JSONArray().put(source().put("revision", 4))))
        assertFalse(reminderRescheduleSourceCurrent(displayed, JSONArray()))
        val unknown = source(); unknown.remove("manualOriginVersion")
        assertFalse(reminderRescheduleSourceCurrent(unknown, JSONArray().put(unknown)))
    }
}
