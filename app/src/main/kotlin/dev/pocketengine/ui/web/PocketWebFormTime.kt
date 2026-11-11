package dev.pocketengine.ui.web

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import org.json.JSONObject

/** Parse presentation calendar fields once, in the requested zone, before the original review. */
internal object PocketWebFormTime {
    fun instant(value: JSONObject, now: Long, maxDays: Long = 366): Long {
        fun text(key: String, max: Int): String { val raw = value.opt(key); require(raw is String && raw.length in 1..max) { "请核对日期、时间与时区" }; return raw }
        val date = text("date", 10); val time = text("time", 5); val zone = try { ZoneId.of(text("zone", 80)) } catch (_: java.time.DateTimeException) { error("时区无效，请使用例如 Asia/Shanghai 的地区时区") }
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && time.matches(Regex("[0-2]\\d:[0-5]\\d"))) { "请使用完整日期与 HH:mm 时间" }
        val local = try { LocalDateTime.of(LocalDate.parse(date, DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)), LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT))) } catch (_: java.time.DateTimeException) { error("日期或时间无效，请核对年月日与小时分钟") }
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "该时区的这个当地时间不存在，请选择夏令时调整后的时间" }
        val later = value.opt("laterOverlap"); require(later is Boolean) { "请核对重复时间选项" }
        val at = local.toInstant(if (later) offsets.last() else offsets.first()).toEpochMilli()
        require(at > now && at <= Math.addExact(now, Math.multiplyExact(maxDays, 86_400_000L))) { "请选择未来一年内的日期与时间" }
        return at
    }
}
