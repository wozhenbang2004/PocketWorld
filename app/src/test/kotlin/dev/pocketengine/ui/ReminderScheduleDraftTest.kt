package dev.pocketengine.ui

import dev.pocketengine.features.automation.AutomationSchedule
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
}
