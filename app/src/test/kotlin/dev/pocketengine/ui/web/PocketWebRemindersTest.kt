package dev.pocketengine.ui.web

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import dev.pocketengine.features.automation.AutomationBook

class PocketWebRemindersTest {
    @JvmField @Rule val temp = TemporaryFolder()
    private var now = Instant.parse("2026-11-03T10:00:00Z").toEpochMilli()
    private fun draft(type: String = "once") = JSONObject().put("title", "原提醒").put("phone", false).put("goal", "")
        .put("type", type).put("minutes", "30").put("time", "09:00").put("zone", "Asia/Shanghai")
        .put("weekdays", JSONArray().put(1).put(3).put(5)).put("monthDay", 31).put("lastDay", true)
        .put("skipGap", false).put("laterOverlap", false).put("cron", "0 9 * * 1-5")
        .put("preserveSchedule", false).put("misfire", "coalesce").put("overlap", "buffer_one").put("maxCatchUp", 3)
    private fun args(draft: JSONObject = draft()) = JSONObject().put("intent", "create").put("draft", draft)
    private fun rule() = JSONObject().put("id", "original-rule").put("revision", 4).put("title", "原提醒").put("kind", "reminder").put("manualOriginVersion", 1)
        .put("enabled", false).put("schedule", JSONObject().put("type", "interval").put("anchorAt", now + 60000).put("intervalMillis", 1800000))
        .put("policy", JSONObject().put("maxOccurrences", 9).put("requiresCharging", true))
    private fun target(rule: JSONObject, command: String) = JSONObject().put("id", rule.getString("id")).put("revision", rule.getInt("revision")).put("command", command)

    @Test fun allSixCalendarsUseOriginalNativePreview() {
        val reviews = PocketWebReminders { now }
        for (type in listOf("once", "interval", "daily", "weekly", "monthly", "cron")) {
            val preview = reviews.reviewSchedule(args(draft(type)), JSONArray())
            assertEquals(type, preview.getJSONObject("rule").getJSONObject("schedule").getString("type"))
            assertTrue(preview.getJSONObject("preview").getJSONArray("occurrences").length() > 0)
            assertFalse(preview.getBoolean("executionAuthorized"))
            assertEquals("save", reviews.consume(preview.getString("reviewHandle"), JSONArray()).getString("command"))
        }
    }

    @Test fun editPreservesOriginalAnchorBudgetsAndPauseState() {
        val original = rule(); val rules = JSONArray().put(original); val reviews = PocketWebReminders { now }
        val input = args(draft("interval").put("title", "仅改内容").put("preserveSchedule", true))
            .put("intent", "edit").put("id", original.getString("id")).put("revision", 4)
        val preview = reviews.reviewSchedule(input, rules)
        val saved = reviews.consume(preview.getString("reviewHandle"), rules).getJSONObject("input").getJSONObject("rule")
        assertEquals(original.getJSONObject("schedule").getLong("anchorAt"), saved.getJSONObject("schedule").getLong("anchorAt"))
        assertEquals(9, saved.getJSONObject("policy").getInt("maxOccurrences")); assertTrue(saved.getJSONObject("policy").getBoolean("requiresCharging"))
        assertFalse(saved.has("enabled")); assertEquals(4, saved.getInt("revision")); assertEquals("仅改内容", saved.getString("title"))
    }

    @Test fun rescheduleCreatesNewFutureRuleWithoutOriginalOccurrenceOrBudget() {
        val original = rule().put("schedule", JSONObject().put("type", "once").put("at", now - 10)).put("claimedCount", 1)
        val reviews = PocketWebReminders { now }; val rules = JSONArray().put(original)
        val input = args().put("intent", "reschedule").put("id", original.getString("id")).put("revision", 4)
        val preview = reviews.reviewSchedule(input, rules)
        val saved = reviews.consume(preview.getString("reviewHandle"), rules).getJSONObject("input").getJSONObject("rule")
        assertFalse(saved.has("id")); assertFalse(saved.has("revision")); assertTrue(saved.getBoolean("enabled"))
        assertEquals("original-rule", saved.getJSONObject("rescheduleSource").getString("id")); assertEquals(4, saved.getJSONObject("rescheduleSource").getInt("revision"))
        assertTrue(saved.getJSONObject("schedule").getLong("at") > now); assertFalse(saved.getJSONObject("policy").has("maxOccurrences"))
        assertEquals(1, original.getInt("claimedCount"))
    }

    @Test fun revisionChangeConsumesAndRejectsOriginalReview() {
        val original = rule(); val rules = JSONArray().put(original); val reviews = PocketWebReminders { now }
        val review = reviews.reviewRule(target(original, "delete"), rules)
        original.put("revision", 5)
        assertThrows(IllegalArgumentException::class.java) { reviews.consume(review.getString("reviewHandle"), rules) }
        assertThrows(IllegalStateException::class.java) { reviews.consume(review.getString("reviewHandle"), rules) }
    }

    @Test fun oneUseExpiryAndWindowClearNeverReplayAReview() {
        val reviews = PocketWebReminders { now }
        val review = reviews.reviewSchedule(args(), JSONArray()); val handle = review.getString("reviewHandle")
        reviews.consume(handle, JSONArray()); assertThrows(IllegalStateException::class.java) { reviews.consume(handle, JSONArray()) }
        val expired = reviews.reviewSchedule(args(), JSONArray()).getString("reviewHandle"); now += 120001
        assertThrows(IllegalStateException::class.java) { reviews.consume(expired, JSONArray()) }
        val closed = reviews.reviewSchedule(args(), JSONArray()).getString("reviewHandle"); reviews.clear()
        assertThrows(IllegalStateException::class.java) { reviews.consume(closed, JSONArray()) }
    }

    @Test fun enablingCannotReviveClaimedOnceOrNewlyUncertainSource() {
        val original = rule().put("schedule", JSONObject().put("type", "once").put("at", now + 90000)).put("claimedCount", 1)
        val rules = JSONArray().put(original); val reviews = PocketWebReminders { now }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewRule(target(original, "setEnabled").put("enabled", true), rules) }
        original.remove("claimedCount")
        val handle = reviews.reviewRule(target(original, "setEnabled").put("enabled", true), rules).getString("reviewHandle")
        original.put("sourceCheckPending", true)
        assertThrows(IllegalArgumentException::class.java) { reviews.consume(handle, rules) }
    }

    @Test fun editingSourceMustRemainCurrentUntilConfirm() {
        val original = rule(); val rules = JSONArray().put(original); val reviews = PocketWebReminders { now }
        val review = reviews.reviewSchedule(args().put("intent", "edit").put("id", original.getString("id")).put("revision", 4), rules)
        original.put("sourceInvalidated", true)
        assertThrows(IllegalArgumentException::class.java) { reviews.consume(review.getString("reviewHandle"), rules) }
    }

    @Test fun elapsedPreviewCannotSaveAnExpiredOnceTime() {
        val reviews = PocketWebReminders { now }
        val review = reviews.reviewSchedule(args(draft().put("minutes", "1")), JSONArray()); now += 61000
        assertThrows(IllegalArgumentException::class.java) { reviews.consume(review.getString("reviewHandle"), JSONArray()) }
    }

    @Test fun schemaRejectsInjectedFieldsAndInvalidCalendarInput() {
        val reviews = PocketWebReminders { now }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewSchedule(args().put("grant", true), JSONArray()) }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewSchedule(args(draft().put("enabled", true)), JSONArray()) }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewSchedule(args(draft("daily").put("zone", "not-a-zone")), JSONArray()) }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewSchedule(args(draft().put("minutes", "0")), JSONArray()) }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewSchedule(args(draft("weekly").put("weekdays", JSONArray())), JSONArray()) }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewSchedule(args(draft().put("phone", "true")), JSONArray()) }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewSchedule(args(draft().put("phone", true).put("goal", "")), JSONArray()) }
    }

    @Test fun deleteAndPauseKeepExactNativeCommandAndRevision() {
        val original = rule().put("enabled", true); val rules = JSONArray().put(original); val reviews = PocketWebReminders { now }
        for (command in listOf("delete", "setEnabled")) {
            val input = target(original, command).also { if (command == "setEnabled") it.put("enabled", false) }
            val review = reviews.reviewRule(input, rules); val result = reviews.consume(review.getString("reviewHandle"), rules)
            assertEquals(command, result.getString("command")); assertEquals(4, result.getJSONObject("input").getInt("revision"))
            assertEquals("original-rule", result.getJSONObject("input").getString("id"))
        }
    }

    @Test fun nativePreviewReceivesDstAndShortMonthChoices() {
        now = Instant.parse("2026-03-07T10:00:00Z").toEpochMilli()
        val reviews = PocketWebReminders { now }
        val shifted = reviews.reviewSchedule(args(draft("daily").put("zone", "America/New_York").put("time", "02:30")), JSONArray())
        assertEquals("gap_shifted_forward", shifted.getJSONObject("preview").getJSONArray("occurrences").getJSONObject(0).getString("dstResolution"))
        val skipped = reviews.reviewSchedule(args(draft("daily").put("zone", "America/New_York").put("time", "02:30").put("skipGap", true)), JSONArray())
        assertTrue(skipped.getJSONObject("preview").getJSONArray("occurrences").getJSONObject(0).getString("local").startsWith("2026-03-09"))
        now = Instant.parse("2026-01-31T10:00:00Z").toEpochMilli()
        val monthly = reviews.reviewSchedule(args(draft("monthly")), JSONArray())
        assertTrue(monthly.getJSONObject("preview").getJSONArray("occurrences").getJSONObject(0).getString("local").startsWith("2026-02-28"))
    }

    @Test fun sourceBearingRescheduleIsRejectedAtReview() {
        val original = rule().put("nativeOrigin", JSONObject().put("taskId", "original"))
        val reviews = PocketWebReminders { now }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewSchedule(args().put("intent", "reschedule").put("id", original.getString("id")).put("revision", 4), JSONArray().put(original)) }
    }

    @Test fun unknownManualProvenanceCannotReachPreview() {
        val original = rule(); original.remove("manualOriginVersion")
        val reviews = PocketWebReminders { now }
        assertThrows(IllegalArgumentException::class.java) { reviews.reviewSchedule(args().put("intent", "reschedule").put("id", original.getString("id")).put("revision", 4), JSONArray().put(original)) }
    }

    @Test fun consumedRescheduleCannotWashProvenanceMissingFromItsCachedDisplay() {
        val book = AutomationBook(java.io.File(temp.root, "native-source.json"))
        val origin = JSONObject().put("ownerId", "owner").put("spaceId", "private").put("taskId", java.util.UUID.randomUUID().toString())
            .put("conversationId", "original-conversation").put("runId", java.util.UUID.randomUUID().toString()).put("epoch", 1)
        val proposal = book.proposeSchedule(origin, java.util.UUID.randomUUID().toString(), JSONObject().put("title", "原来源目标").put("kind", "reminder")
            .put("schedule", JSONObject().put("type", "once").put("at", now + 60000)), now)
        val original = book.approveScheduleProposal(proposal.getString("id"), proposal.getString("digest"), now).getJSONObject("rule")
        // A stale/lossy display is not evidence that the durable source has no provenance.
        val cached = JSONObject(original.toString()); cached.remove("nativeOrigin"); cached.put("manualOriginVersion", 1)
        val reviews = PocketWebReminders { now }
        val review = reviews.reviewSchedule(args().put("intent", "reschedule").put("id", cached.getString("id")).put("revision", cached.getInt("revision")), JSONArray().put(cached))
        val frozen = reviews.consume(review.getString("reviewHandle"), JSONArray().put(cached)).getJSONObject("input").getJSONObject("rule")
        assertThrows(SecurityException::class.java) { book.save(frozen, now) }
        assertEquals(1, book.rules().length()); assertTrue(book.rules().getJSONObject(0).has("nativeOrigin"))
    }
}
