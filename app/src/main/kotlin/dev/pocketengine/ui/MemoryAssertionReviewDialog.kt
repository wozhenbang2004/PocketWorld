package dev.pocketengine.ui

import dev.pocketengine.features.memory.SourceRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

internal const val MAX_ASSERTION_DISPLAY_MILLIS = 8_640_000_000_000_000L

internal fun assertionReviewDate(value: String): Any {
    val text = value.trim()
    if (text.isEmpty()) return JSONObject.NULL
    val millis = try {
        val instant = if (text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant() else Instant.parse(text)
        instant.toEpochMilli()
    } catch (failure: Exception) { throw IllegalArgumentException("请填写有效日期（如 2026-11-03）或保留完整时间；未知可留空。", failure) }
    require(millis in 0L..MAX_ASSERTION_DISPLAY_MILLIS) { "新填的日期必须能在手机上核对，且不早于 1970-01-01 00:00 UTC。" }
    return millis
}

/** Native donor dependencies may add source refs, but may never discard the original evidence. */

internal fun assertionReviewKeepsSources(before: JSONObject, after: JSONObject): Boolean = runCatching {
    val original = before.getJSONArray("sourceRefs")
    val returned = after.getJSONArray("sourceRefs")
    val refs = (0 until returned.length()).map { SourceRef.fromJson(returned.getJSONObject(it)) }.toSet()
    (0 until original.length()).all { refs.contains(SourceRef.fromJson(original.getJSONObject(it))) }
}.getOrDefault(false)
