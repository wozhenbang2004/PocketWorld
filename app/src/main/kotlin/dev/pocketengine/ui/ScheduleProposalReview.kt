package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.R
import dev.pocketengine.features.automation.AutomationBook
import dev.pocketengine.features.automation.AutomationSchedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONObject

internal data class ScheduleProposalDisplay(
    val original: JSONObject, val schedule: JSONObject, val displayZone: String,
    val nextLocalTimes: List<String>, val anchorLocalTime: String?, val weekDays: List<String>
)

/** Pure original-Book integrity + original calendar arithmetic; no parsing of natural language. */
internal fun scheduleProposalDisplay(proposal: JSONObject, displayZone: ZoneId, now: Long, locale: Locale = Locale.getDefault()): ScheduleProposalDisplay {
    require(now >= 0)
    val checked = AutomationBook.reviewedProposalForDisplay(proposal)
    val schedule = AutomationSchedule.normalize(checked.getJSONObject("draft").getJSONObject("schedule"))
    fun time(at: Long) = DateTimeFormatter.ofPattern(if (at % 1000 == 0L) "uuuu-MM-dd HH:mm:ss XXX" else "uuuu-MM-dd HH:mm:ss.SSS XXX", locale)
        .withZone(displayZone).format(Instant.ofEpochMilli(at))
    val preview = AutomationSchedule.preview(schedule, now, 3).getJSONArray("occurrences")
    val times = (0 until preview.length()).map { time(preview.getJSONObject(it).getLong("at")) }
    val days = schedule.optJSONArray("daysOfWeek")
    return ScheduleProposalDisplay(checked, schedule, displayZone.id, times,
        when (schedule.getString("type")) { "once" -> time(schedule.getLong("at")); "interval" -> time(schedule.getLong("anchorAt")); else -> null },
        (0 until (days?.length() ?: 0)).map { DayOfWeek.of(days!!.getInt(it)).getDisplayName(TextStyle.FULL, locale) })
}

/** Existing native owner plan review. Sends exact id/digest, never a replacement source identity. */
@Composable
internal fun ScheduleProposalReview(proposal: JSONObject, busy: Boolean, send: (String, JSONObject) -> Unit) {
    val encoded = proposal.toString()
    val zone = ZoneId.systemDefault()
    val minute = System.currentTimeMillis() / 60_000
    val display = remember(encoded, zone.id, minute) { runCatching { scheduleProposalDisplay(JSONObject(encoded), zone, System.currentTimeMillis()) }.getOrNull() }
    var consent by remember(encoded) { mutableStateOf(false) }
    var invalidated by remember(encoded) { mutableStateOf(false) }
    if (display == null) {
        Text(stringResource(R.string.schedule_proposal_invalid), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 20.sp)
        return
    }
    val original = display.original
    val draft = original.getJSONObject("draft")
    val schedule = display.schedule
    val condition = draft.optJSONObject("semanticCondition")
    val pending = original.optString("status") == "pending"
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(draft.getString("title"), style = MaterialTheme.typography.titleSmall)
        if (draft.optString("goal").isNotBlank()) Text(draft.getString("goal"), fontSize = 13.sp, lineHeight = 22.sp)
        when (schedule.getString("type")) {
            "once" -> Text(stringResource(R.string.schedule_proposal_once, display.anchorLocalTime.orEmpty()), fontSize = 13.sp)
            "interval" -> {
                Text(stringResource(R.string.schedule_proposal_interval, scheduleIntervalLabel(schedule.getLong("intervalMillis"))), fontSize = 13.sp)
                Text(stringResource(R.string.schedule_proposal_anchor, display.anchorLocalTime.orEmpty()), fontSize = 12.sp, color = SecondaryInk)
            }
            "daily" -> Text(stringResource(R.string.schedule_proposal_daily, schedule.getString("time"), schedule.getString("zone")), fontSize = 13.sp)
            "weekly" -> Text(stringResource(R.string.schedule_proposal_weekly, display.weekDays.joinToString(", "), schedule.getString("time"), schedule.getString("zone")), fontSize = 13.sp)
            "monthly" -> {
                Text(stringResource(R.string.schedule_proposal_monthly, schedule.getInt("dayOfMonth"), schedule.getString("time"), schedule.getString("zone")), fontSize = 13.sp)
                Text(stringResource(if (schedule.getString("shortMonthPolicy") == "last_day") R.string.schedule_proposal_short_last else R.string.schedule_proposal_short_skip), color = SecondaryInk, fontSize = 12.sp)
            }
        }
        Text(stringResource(R.string.schedule_proposal_display_zone, display.displayZone), fontSize = 12.sp, color = SecondaryInk)
        if (display.nextLocalTimes.isNotEmpty()) {
            Text(stringResource(R.string.schedule_proposal_next), fontSize = 12.sp)
            display.nextLocalTimes.forEach { Text(it, fontSize = 12.sp, color = SecondaryInk) }
        } else Text(stringResource(R.string.schedule_proposal_expired), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        if (schedule.has("zone")) Text(stringResource(
            R.string.schedule_proposal_dst,
            stringResource(if (schedule.optString("dstGap", "shift_forward") == "skip") R.string.schedule_proposal_dst_gap_skip else R.string.schedule_proposal_dst_gap_shift),
            stringResource(if (schedule.optString("dstOverlap", "earlier") == "later") R.string.schedule_proposal_dst_later else R.string.schedule_proposal_dst_earlier)
        ), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        Text(stringResource(if (draft.getString("kind") == "phone_goal") R.string.schedule_proposal_phone_note else R.string.schedule_proposal_reminder_note), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        Text(stringResource(R.string.schedule_proposal_timing), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        condition?.let { SemanticConditionUseConsent(it, consent, busy) { selected -> consent = selected } }
        val origin = original.getJSONObject("origin")
        Text(stringResource(R.string.schedule_proposal_source_task, origin.getString("taskId")), fontSize = 11.sp, color = SecondaryInk)
        Text(stringResource(R.string.schedule_proposal_source_run, origin.getString("runId"), origin.getInt("epoch")), fontSize = 11.sp, color = SecondaryInk)
        Text(stringResource(R.string.schedule_proposal_digest, original.getString("digest").take(12)), fontSize = 11.sp, color = SecondaryInk)
        if (invalidated) Text(stringResource(R.string.schedule_proposal_invalid), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 20.sp)
        Button(onClick = {
            val fresh = runCatching { scheduleProposalDisplay(JSONObject(encoded), ZoneId.systemDefault(), System.currentTimeMillis()) }.getOrNull()
            if (fresh == null || fresh.nextLocalTimes.isEmpty() || fresh.displayZone != display.displayZone) invalidated = true
            else {
                invalidated = false
                send("approveSchedule", JSONObject().put("id", fresh.original.getString("id")).put("digest", fresh.original.getString("digest")).put("modelUseConsent", consent))
            }
        }, enabled = pending && !busy && display.nextLocalTimes.isNotEmpty() && (condition == null || consent)) {
            Text(stringResource(if (pending) R.string.schedule_proposal_approve else R.string.schedule_proposal_approved))
        }
    }
}

@Composable
private fun scheduleIntervalLabel(millis: Long): String {
    val days = millis / 86_400_000; val hours = millis / 3_600_000 % 24
    val minutes = millis / 60_000 % 60; val seconds = millis / 1000 % 60; val remainder = millis % 1000
    return listOfNotNull(
        if (days > 0) stringResource(R.string.schedule_proposal_duration_days, days) else null,
        if (hours > 0) stringResource(R.string.schedule_proposal_duration_hours, hours) else null,
        if (minutes > 0) stringResource(R.string.schedule_proposal_duration_minutes, minutes) else null,
        if (seconds > 0) stringResource(R.string.schedule_proposal_duration_seconds, seconds) else null,
        if (remainder > 0) stringResource(R.string.schedule_proposal_duration_millis, remainder) else null
    ).joinToString(" ")
}
