package dev.pocketengine.ui

import dev.pocketengine.features.automation.AutomationSchedule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReminderScheduleDraftTest {
    private fun draft(type: String, now: Long = 1700000000000L, minutes: String = "30", days: String = "1,3,5", monthDay: String = "31", lastDay: Boolean = false) = reminderScheduleDraft(type, minutes, "09:00", "Asia/Shanghai", days, monthDay, lastDay, false, false, now)
    @Test fun intervalPreviewAndActualBookInitialSelectionShareAnExplicitFutureAnchor() {
        val now = 1700000000000L
        val schedule = draft("interval", now)
        assertEquals(now + 1800000L, schedule.getLong("anchorAt"))
        assertEquals(1800000L, schedule.getLong("intervalMillis"))
        val preview = AutomationSchedule.preview(schedule, now, 3).getJSONArray("occurrences")
        assertEquals(schedule.getLong("anchorAt"), preview.getJSONObject(0).getLong("at"))
        assertEquals(schedule.getLong("anchorAt"), AutomationSchedule.nextAt(schedule, now))
        assertEquals(now + 5400000L, preview.getJSONObject(2).getLong("at"))
        assertFalse(schedule.has("zone"))
    }
    @Test fun allFiveEditorTypesUseTheOriginalClosedNormalizer() {
        val once = draft("once"); assertEquals(1700001800000L, once.getLong("at"))
        val daily = draft("daily"); assertEquals("Asia/Shanghai", daily.getString("zone")); assertEquals("shift_forward", daily.getString("dstGap"))
        val weekly = draft("weekly"); assertEquals("[1,3,5]", weekly.getJSONArray("daysOfWeek").toString())
        assertEquals("skip", draft("monthly").getString("shortMonthPolicy"))
        assertEquals("last_day", draft("monthly", lastDay = true).getString("shortMonthPolicy"))
        assertEquals("interval", draft("interval").getString("type"))
    }
    @Test fun invalidMinutesDaysAndUnknownModesNeverBecomeASaveableDraft() {
        for (minutes in listOf("0", "-1", "", "NaN", "1.5", "525601")) assertThrows(Exception::class.java) { draft("interval", minutes = minutes) }
        assertThrows(Exception::class.java) { draft("weekly", days = "") }
        assertThrows(Exception::class.java) { draft("weekly", days = "1,8") }
        assertThrows(Exception::class.java) { draft("monthly", monthDay = "0") }
        assertThrows(Exception::class.java) { draft("semantic_condition") }
    }
    @Test fun overflowAndUnsupportedEpochCannotWrapAnInstantOrCreateAnImmediateInterval() {
        assertThrows(Exception::class.java) { draft("interval", Long.MAX_VALUE) }
        val maximum = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli()
        assertThrows(Exception::class.java) { draft("once", maximum) }
        val small = draft("interval", 1000L, "1")
        assertEquals(61000L, small.getLong("anchorAt"))
        assertEquals(61000L, AutomationSchedule.nextAt(small, 1000L))
    }
    @Test fun cronEditorUsesTheSameVersionedCalendarAndRejectsAmbiguousDates() {
        val now = Instant.parse("2027-01-04T00:00:00Z").toEpochMilli()
        val schedule = reminderScheduleDraft("cron", "30", "09:00", "Asia/Shanghai", "", "1", false, true, true, now, "0 9 * * 1-5")
        assertEquals("cron", schedule.getString("type"))
        assertEquals(1, schedule.getInt("scheduleVersion"))
        assertEquals("skip", schedule.getString("dstGap"))
        assertEquals("later", schedule.getString("dstOverlap"))
        assertEquals(Instant.parse("2027-01-04T01:00:00Z").toEpochMilli(), AutomationSchedule.preview(schedule, now, 3).getJSONArray("occurrences").getJSONObject(0).getLong("at"))
        assertThrows(IllegalArgumentException::class.java) { reminderScheduleDraft("cron", "30", "09:00", "UTC", "", "1", false, false, false, now, "0 9 1 * 1") }
    }
    @Test fun editingKeepsTheExactAnchorNativeRevisionAndAllExistingPolicyLimits() {
        val source = JSONObject().put("type", "interval").put("anchorAt", 1700000123456L).put("intervalMillis", 90001L)
        val preserved = reminderPreservedSchedule(source)
        assertEquals(source.getLong("anchorAt"), preserved.getLong("anchorAt"))
        assertEquals(source.getLong("intervalMillis"), preserved.getLong("intervalMillis"))
        assertEquals(1, preserved.getInt("scheduleVersion"))
        assertFalse(source.has("scheduleVersion"))
        val old = JSONObject().put("id", "original-rule").put("revision", 7).put("enabled", false).put("claimedCount", 4)
            .put("policy", JSONObject().put("maxOccurrences", 5).put("expiresAt", 1700009999999L).put("requiresNetwork", true).put("quietHours", JSONObject().put("start", "22:00").put("end", "07:00")))
        val rule = reminderRuleDraft(old, "更新文字", false, "", preserved, "coalesce", "buffer_one", 2)
        assertEquals("original-rule", rule.getString("id")); assertEquals(7, rule.getInt("revision"))
        assertFalse(rule.has("enabled")); assertFalse(rule.has("claimedCount")); assertFalse(rule.has("executionAuthorized"))
        assertEquals(5, rule.getJSONObject("policy").getInt("maxOccurrences"))
        assertEquals(old.getJSONObject("policy").getLong("expiresAt"), rule.getJSONObject("policy").getLong("expiresAt"))
        assertTrue(rule.getJSONObject("policy").getBoolean("requiresNetwork"))
        assertEquals("22:00", rule.getJSONObject("policy").getJSONObject("quietHours").getString("start"))
        assertFalse(old.getJSONObject("policy").has("misfire"))
        assertThrows(IllegalArgumentException::class.java) { reminderPreservedSchedule(JSONObject(source.toString()).put("scheduleVersion", 2)) }
    }
    @Test fun reschedulingRequiresSameLiveRuleAndNoRevocation() {
        val source = JSONObject().put("id", "original").put("revision", 4).put("manualOriginVersion", 1)
        fun rules(value: JSONObject) = org.json.JSONArray().put(value)
        assertTrue(reminderRescheduleSourceCurrent(source, rules(JSONObject(source.toString()))))
        assertFalse(reminderRescheduleSourceCurrent(source, rules(JSONObject(source.toString()).put("revision", 5))))
        assertFalse(reminderRescheduleSourceCurrent(source, rules(JSONObject(source.toString()).put("sourceInvalidated", true))))
        assertFalse(reminderRescheduleSourceCurrent(source, rules(JSONObject(source.toString()).put("sourceCheckPending", true))))
        assertFalse(reminderRescheduleSourceCurrent(source, org.json.JSONArray()))
        val unknown = JSONObject(source.toString()); unknown.remove("manualOriginVersion")
        assertFalse(reminderRescheduleSourceCurrent(unknown, rules(unknown)))
        assertFalse(reminderRescheduleSourceCurrent(source, rules(JSONObject(source.toString()).put("nativeOrigin", JSONObject()))))
        assertTrue(reminderRescheduleSourceCurrent(null, null))
    }
}
