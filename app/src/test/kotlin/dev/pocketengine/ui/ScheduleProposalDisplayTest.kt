package dev.pocketengine.ui

import dev.pocketengine.features.automation.AutomationBook
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual original Book digest and calendar, no UI/model/phone or background execution claim. */
class ScheduleProposalDisplayTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
    private fun proposal(schedule: JSONObject): JSONObject {
        val book = AutomationBook(File(temp.root, UUID.randomUUID().toString() + ".json"))
        val origin = JSONObject().put("ownerId", "owner").put("spaceId", "private").put("taskId", UUID.randomUUID().toString())
            .put("conversationId", "original-private").put("runId", UUID.randomUUID().toString()).put("epoch", 2)
        return book.proposeSchedule(origin, UUID.randomUUID().toString(), JSONObject().put("title", "Original draft").put("kind", "phone_goal")
            .put("goal", "Original owner-admitted goal").put("schedule", schedule), now)
    }
    @Test fun absoluteTimeShowsExactDeviceLocalDateOffsetAndFixedSourceIdentity() {
        val at = Instant.parse("2030-01-01T00:00:00.123Z").toEpochMilli()
        val row = proposal(JSONObject().put("type", "once").put("at", at))
        val display = scheduleProposalDisplay(row, ZoneId.of("Asia/Shanghai"), now, Locale.ENGLISH)
        assertEquals("Asia/Shanghai", display.displayZone)
        assertEquals(listOf("2030-01-01 08:00:00.123 +08:00"), display.nextLocalTimes)
        assertEquals(row.getJSONObject("origin").getString("runId"), display.original.getJSONObject("origin").getString("runId"))
        assertEquals(2, display.original.getJSONObject("origin").getInt("epoch"))
        assertEquals(row.getString("digest"), display.original.getString("digest"))
    }
    @Test fun weeklyZoneAndDstChoicesRemainOriginalWhilePreviewUsesDeviceZone() {
        val row = proposal(JSONObject().put("type", "weekly").put("time", "09:00").put("zone", "America/New_York")
            .put("daysOfWeek", JSONArray().put(5).put(1)).put("dstGap", "skip").put("dstOverlap", "later"))
        val display = scheduleProposalDisplay(row, ZoneId.of("UTC"), now, Locale.ENGLISH)
        assertEquals(listOf("Monday", "Friday"), display.weekDays)
        assertEquals("America/New_York", display.schedule.getString("zone"))
        assertEquals("skip", display.schedule.getString("dstGap"))
        assertEquals("later", display.schedule.getString("dstOverlap"))
        assertTrue(display.nextLocalTimes.first().contains("14:00:00 Z"))
    }
    @Test fun alteredDraftSourceRunAndLegacyMissingRunCannotProduceAnApprovableDisplay() {
        val row = proposal(JSONObject().put("type", "once").put("at", now + 60_000))
        val changed = JSONObject(row.toString()).apply { getJSONObject("draft").put("goal", "different unreviewed goal") }
        assertThrows(SecurityException::class.java) { scheduleProposalDisplay(changed, ZoneId.of("UTC"), now) }
        val differentRun = JSONObject(row.toString()).apply { getJSONObject("origin").put("runId", UUID.randomUUID().toString()) }
        assertThrows(SecurityException::class.java) { scheduleProposalDisplay(differentRun, ZoneId.of("UTC"), now) }
        val legacy = JSONObject(row.toString()).apply { getJSONObject("origin").remove("runId") }
        assertThrows(SecurityException::class.java) { scheduleProposalDisplay(legacy, ZoneId.of("UTC"), now) }
    }
    @Test fun elapsedOnceAndFixedIntervalAreDisplayedWithoutChangingTheSchedule() {
        val once = proposal(JSONObject().put("type", "once").put("at", now + 1000))
        assertTrue(scheduleProposalDisplay(once, ZoneId.of("UTC"), now + 2000).nextLocalTimes.isEmpty())
        val interval = proposal(JSONObject().put("type", "interval").put("anchorAt", now).put("intervalMillis", 96_001))
        val display = scheduleProposalDisplay(interval, ZoneId.of("UTC"), now)
        assertEquals(96_001L, display.schedule.getLong("intervalMillis"))
        assertEquals(3, display.nextLocalTimes.size)
        assertTrue(display.nextLocalTimes.first().contains("00:01:36.001"))
    }
}
