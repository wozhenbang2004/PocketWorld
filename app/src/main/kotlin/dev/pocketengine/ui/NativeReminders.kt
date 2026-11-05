package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

internal fun reminderScheduleDraft(type: String, minutes: String, time: String, zone: String, weekdays: String, monthDay: String, lastDay: Boolean, skipGap: Boolean, laterOverlap: Boolean, now: Long, cron: String = ""): JSONObject {
    require(type in setOf("once", "interval", "daily", "weekly", "monthly", "cron")) { "请选择支持的时间安排" }
    val calendar = type in setOf("daily", "weekly", "monthly", "cron")
    val raw = if (calendar) JSONObject().put("type", type).put(if (type == "cron") "expression" else "time", if (type == "cron") cron else time).put("zone", zone.trim()).put("dstGap", if (skipGap) "skip" else "shift_forward").put("dstOverlap", if (laterOverlap) "later" else "earlier") else {
        val count = minutes.toLongOrNull() ?: 0L
        require(count in 1L..525600L) { "分钟数应为1至525600" }
        val delay = Math.multiplyExact(count, 60000L)
        // First occurrence is explicit and future, even if save reaches Book in the same millisecond.
        val first = Math.addExact(now, delay)
        if (type == "interval") JSONObject().put("type", "interval").put("anchorAt", first).put("intervalMillis", delay)
        else JSONObject().put("type", "once").put("at", first)
    }
    if (type == "weekly") raw.put("daysOfWeek", JSONArray(weekdays.split(',').filter { it.isNotBlank() }.map { it.toInt() }))
    if (type == "monthly") raw.put("dayOfMonth", monthDay.toIntOrNull() ?: 0).put("shortMonthPolicy", if (lastDay) "last_day" else "skip")
    return dev.pocketengine.features.automation.AutomationSchedule.normalize(raw.put("scheduleVersion", dev.pocketengine.features.automation.AutomationSchedule.SCHEDULE_VERSION))
}

/** Preserve original policy fields and pause state; this draft cannot create execution authority. */

internal fun reminderRuleDraft(original: JSONObject?, title: String, phone: Boolean, goal: String, schedule: JSONObject, misfire: String, overlap: String, maxCatchUp: Int, rescheduleSource: JSONObject? = null): JSONObject {
    require(rescheduleSource == null || original == null && !rescheduleSource.has("nativeOrigin") && rescheduleSource.opt("manualOriginVersion") == 1) { "原提醒来源尚未确认，请从空白明确新建或在原对话重新提出安排" }
    val policy = JSONObject(original?.optJSONObject("policy")?.toString() ?: "{}")
    policy.put("misfire", misfire).put("overlap", overlap).put("maxCatchUp", maxCatchUp)
    return JSONObject().put("title", title.trim()).put("kind", if (phone) "phone_goal" else "reminder").put("goal", goal.trim()).put("schedule", schedule).put("policy", policy).apply {
        if (original == null) put("enabled", true) else put("id", original.getString("id")).put("revision", original.getInt("revision"))
        if (rescheduleSource != null) put("rescheduleSource", JSONObject().put("id", rescheduleSource.getString("id")).put("revision", rescheduleSource.getInt("revision")))
    }
}

internal fun reminderPreservedSchedule(original: JSONObject): JSONObject = dev.pocketengine.features.automation.AutomationSchedule.normalize(original).put("scheduleVersion", dev.pocketengine.features.automation.AutomationSchedule.SCHEDULE_VERSION)

/** UI currentness only; the original Book remains the save authority. */

internal fun reminderRescheduleSourceCurrent(source: JSONObject?, rules: JSONArray?): Boolean {
    if (source == null) return true
    if (source.has("nativeOrigin") || source.opt("manualOriginVersion") != 1) return false
    if (rules == null) return false
    for (i in 0 until rules.length()) {
        val current = rules.optJSONObject(i) ?: continue
        if (current.optString("id") == source.optString("id") && current.optInt("revision", -1) == source.optInt("revision", -2) &&
            !current.has("nativeOrigin") && current.opt("manualOriginVersion") == 1 && !current.optBoolean("sourceInvalidated") && !current.optBoolean("sourceCheckPending")) return true
    }
    return false
}
