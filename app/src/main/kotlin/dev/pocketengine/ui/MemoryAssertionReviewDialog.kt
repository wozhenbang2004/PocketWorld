package dev.pocketengine.ui

import dev.pocketengine.features.memory.SourceRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

internal fun assertionReviewDate(value: String): Any {
    val text = value.trim()
    if (text.isEmpty()) return JSONObject.NULL
    return try {
        val instant = if (text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant() else Instant.parse(text)
        instant.toEpochMilli().also { require(it >= 0) { "时间需为 1970 年或之后；未知可留空。" } }
    } catch (failure: Exception) { throw IllegalArgumentException("请填写有效日期（如 2026-11-03）或保留完整时间；未知可留空。", failure) }
}

/** Native donor dependencies may add source refs, but may never discard the original evidence. */

internal fun assertionReviewKeepsSources(before: JSONObject, after: JSONObject): Boolean = runCatching {
    val original = before.getJSONArray("sourceRefs")
    val returned = after.getJSONArray("sourceRefs")
    val refs = (0 until returned.length()).map { SourceRef.fromJson(returned.getJSONObject(it)) }.toSet()
    (0 until original.length()).all { refs.contains(SourceRef.fromJson(original.getJSONObject(it))) }
}.getOrDefault(false)
