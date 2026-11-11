package dev.pocketengine.ui.web

import dev.pocketengine.features.automation.AutomationSchedule
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** One-use presentation reviews only. The original AutomationBook remains the writer/authority. */
internal class PocketWebReminders(private val now: () -> Long = System::currentTimeMillis) {
    private data class Review(val command: String, val input: JSONObject, val sourceId: String?, val sourceRevision: Int?, val expires: Long)
    private val reviews = linkedMapOf<String, Review>()

    fun clear() = reviews.clear()

    fun reviewSchedule(args: JSONObject, rules: JSONArray): JSONObject {
        only(args, setOf("intent", "id", "revision", "draft"))
        val intent = string(args, "intent", 16)
        require(intent in setOf("create", "edit", "reschedule")) { "请选择新建、编辑或重新安排" }
        val source = if (intent == "create") {
            require(!args.has("id") && !args.has("revision")) { "新建不能携带其他提醒身份" }; null
        } else currentRule(args, rules, requireSource = true)
        require(intent != "reschedule" || !source!!.has("nativeOrigin")) { "原对话来源提醒暂不支持复制，请在原对话重新明确提出安排" }
        val manualVersion = source?.opt("manualOriginVersion")
        require(intent != "reschedule" || manualVersion is Number && manualVersion.toDouble() == 1.0) { "原提醒没有可核对的手建来源，请从空白明确新建" }
        val draft = args.getJSONObject("draft")
        only(draft, setOf("title", "phone", "goal", "type", "minutes", "date", "time", "zone", "weekdays", "monthDay", "lastDay", "skipGap", "laterOverlap", "cron", "preserveSchedule", "misfire", "overlap", "maxCatchUp"))
        val title = string(draft, "title", 200).trim()
        require(title.isNotEmpty()) { "请填写提醒内容" }
        val phone = boolean(draft, "phone")
        val goal = string(draft, "goal", 4000, blank = true).trim()
        require(!phone || goal.isNotEmpty()) { "请填写手机任务目标" }
        val preserve = boolean(draft, "preserveSchedule")
        require(!preserve || intent == "edit") { "只有编辑可以保留原时间安排" }
        val schedule = if (preserve) AutomationSchedule.normalize(source!!.getJSONObject("schedule")) else schedule(draft)
        val preview = AutomationSchedule.preview(schedule, now(), 3)
        require(preview.getJSONArray("occurrences").length() > 0) { "当前没有下一次安排，请核对时间" }
        if (schedule.getString("type") == "once" && draft.has("date")) {
            val zone = ZoneId.of(string(draft, "zone", 80).trim())
            val occurrence = preview.getJSONArray("occurrences").getJSONObject(0)
            val local = Instant.ofEpochMilli(occurrence.getLong("at")).atZone(zone)
            val repeated = zone.rules.getValidOffsets(local.toLocalDateTime()).size > 1
            occurrence.put("requestedLocal", local.toLocalDateTime().toString()).put("local", local.toLocalDateTime().toString())
                .put("zone", zone.id).put("offset", local.offset.id)
                .put("dstResolution", if (repeated) if (local.offset == zone.rules.getValidOffsets(local.toLocalDateTime()).last()) "overlap_later" else "overlap_earlier" else "absolute")
        }
        val repeating = schedule.getString("type") != "once"
        val misfire = string(draft, "misfire", 16)
        val overlap = string(draft, "overlap", 16)
        require(misfire in setOf("skip", "coalesce", "catch_up") && overlap in setOf("buffer_one", "skip", "allow")) { "提醒处理策略无效" }
        val catchUp = integer(draft, "maxCatchUp", 1, 20)
        // Copy policy fields owned by the original rule, including its budgets/conditions.
        val policy = JSONObject(if (intent == "edit") source?.optJSONObject("policy")?.toString() ?: "{}" else "{}")
            .put("misfire", if (repeating) misfire else "skip").put("overlap", if (repeating) overlap else "buffer_one").put("maxCatchUp", catchUp)
        val rule = JSONObject().put("title", title).put("kind", if (phone) "phone_goal" else "reminder")
            .put("goal", goal).put("schedule", schedule).put("policy", policy)
        if (intent == "edit") rule.put("id", source!!.getString("id")).put("revision", source.getInt("revision"))
        else rule.put("enabled", true)
        if (intent == "reschedule") rule.put("rescheduleSource", JSONObject().put("id", source!!.getString("id")).put("revision", source.getInt("revision")))
        return retain("save", JSONObject().put("rule", rule), source).put("rule", rule).put("preview", preview)
            .put("intent", intent).put("executionAuthorized", false)
    }

    fun reviewRule(args: JSONObject, rules: JSONArray): JSONObject {
        only(args, setOf("id", "revision", "command", "enabled"))
        val command = string(args, "command", 16)
        require(command in setOf("setEnabled", "delete")) { "不支持此提醒操作" }
        val source = currentRule(args, rules, requireSource = false)
        val input = JSONObject().put("id", source.getString("id")).put("revision", source.getInt("revision"))
        if (command == "setEnabled") {
            val enabled = boolean(args, "enabled")
            require(!source.optBoolean("sourceInvalidated")) { "原提醒来源已失效，请刷新核对" }
            require(enabled != source.optBoolean("enabled")) { "提醒启停状态已变化，请刷新核对" }
            if (enabled) {
                require(!source.optBoolean("sourceCheckPending")) { "原提醒来源仍待核对" }
                val schedule = AutomationSchedule.normalize(source.getJSONObject("schedule"))
                require(!exhaustedOnce(source, now())) { "这次安排已到期或已有发生记录，请重新安排" }
                require(AutomationSchedule.nextAt(schedule, now()) > 0) { "没有下一次安排，请重新安排" }
            }
            input.put("enabled", enabled)
        } else require(!args.has("enabled")) { "删除操作不能携带启停修改" }
        return retain(command, input, source).put("rule", JSONObject(source.toString()))
            .put("enabled", input.opt("enabled") ?: JSONObject.NULL).put("executionAuthorized", false)
    }

    /** Consume before dispatch: an unknown backend outcome never permits replay of the same review. */
    fun consume(handle: String, rules: JSONArray): JSONObject {
        val review = reviews.remove(handle) ?: error("原提醒审阅已关闭或使用，请重新核对")
        check(now() < review.expires) { "提醒审阅已过期，请重新核对" }
        if (review.sourceId != null) {
            val current = currentRule(JSONObject().put("id", review.sourceId).put("revision", review.sourceRevision), rules, review.command == "save")
            if (review.command == "setEnabled") {
                require(!current.optBoolean("sourceInvalidated")) { "原提醒来源已失效，请刷新核对" }
                if (review.input.getBoolean("enabled")) {
                    require(!current.optBoolean("sourceCheckPending") && !exhaustedOnce(current, now())) { "原安排或来源已变化，请重新核对" }
                    require(AutomationSchedule.nextAt(current.getJSONObject("schedule"), now()) > 0) { "没有下一次安排，请重新安排" }
                }
            }
        }
        if (review.command == "save") {
            val schedule = review.input.getJSONObject("rule").getJSONObject("schedule")
            require(AutomationSchedule.nextAt(schedule, now()) > 0) { "预览时间已过，请重新核对" }
        }
        return JSONObject().put("command", review.command).put("input", JSONObject(review.input.toString()))
    }

    private fun retain(command: String, input: JSONObject, source: JSONObject?): JSONObject {
        reviews.entries.removeAll { it.value.expires <= now() }
        while (reviews.size >= 12) reviews.remove(reviews.keys.first())
        val handle = UUID.randomUUID().toString()
        reviews[handle] = Review(command, JSONObject(input.toString()), source?.getString("id"), source?.getInt("revision"), now() + 120_000L)
        return JSONObject().put("reviewHandle", handle).put("command", command)
    }

    private fun currentRule(args: JSONObject, rules: JSONArray, requireSource: Boolean): JSONObject {
        val id = string(args, "id", 200)
        val revision = integer(args, "revision", 1, Int.MAX_VALUE)
        val source = (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
        require(source != null && source.optInt("revision", -1) == revision) { "原提醒已变化，请刷新后重新核对" }
        if (requireSource) require(!source.optBoolean("sourceInvalidated") && !source.optBoolean("sourceCheckPending")) { "原提醒来源已变化或仍待核对" }
        return JSONObject(source.toString())
    }

    private fun schedule(draft: JSONObject): JSONObject {
        val type = string(draft, "type", 16)
        val calendar = type in setOf("daily", "weekly", "monthly", "cron")
        val raw = if (calendar) {
            val zone = string(draft, "zone", 80).trim()
            JSONObject().put("type", type).put("zone", zone)
                .put("dstGap", if (boolean(draft, "skipGap")) "skip" else "shift_forward")
                .put("dstOverlap", if (boolean(draft, "laterOverlap")) "later" else "earlier")
                .put(if (type == "cron") "expression" else "time", string(draft, if (type == "cron") "cron" else "time", if (type == "cron") AutomationSchedule.MAX_CRON_LENGTH else 5))
        } else if (type == "once" && draft.has("date")) {
            JSONObject().put("type", "once").put("at", PocketWebFormTime.instant(draft, now()))
        } else {
            require(type == "once" || type == "interval") { "请选择支持的时间安排" }
            val minutes = string(draft, "minutes", 6).toLongOrNull() ?: 0
            require(minutes in 1..525600) { "分钟数应为 1 至 525600" }
            val delay = Math.multiplyExact(minutes, 60000L); val first = Math.addExact(now(), delay)
            if (type == "once") JSONObject().put("type", type).put("at", first)
            else JSONObject().put("type", type).put("anchorAt", first).put("intervalMillis", delay)
        }
        if (type == "weekly") raw.put("daysOfWeek", draft.getJSONArray("weekdays"))
        if (type == "monthly") raw.put("dayOfMonth", integer(draft, "monthDay", 1, 31)).put("shortMonthPolicy", if (boolean(draft, "lastDay")) "last_day" else "skip")
        return AutomationSchedule.normalize(raw.put("scheduleVersion", AutomationSchedule.SCHEDULE_VERSION))
    }

    companion object {
        fun exhaustedOnce(rule: JSONObject, at: Long): Boolean {
            val schedule = rule.optJSONObject("schedule") ?: return false
            return schedule.optString("type") == "once" && !rule.optBoolean("enabled") &&
                (schedule.optLong("at") <= at || rule.optLong("onceClaimedAt", -1) >= schedule.optLong("at") || !rule.has("onceClaimedAt") && rule.optLong("claimedCount") > 0)
        }
        private fun only(value: JSONObject, keys: Set<String>) { require(value.keys().asSequence().all { it in keys }) { "提醒参数含未开放字段" } }
        private fun string(value: JSONObject, key: String, max: Int, blank: Boolean = false): String {
            val result = value.opt(key); require(result is String && result.length <= max && (blank || result.isNotBlank())) { "提醒字段 $key 无效" }; return result
        }
        private fun boolean(value: JSONObject, key: String): Boolean { val result = value.opt(key); require(result is Boolean) { "提醒字段 $key 应为开关" }; return result }
        private fun integer(value: JSONObject, key: String, min: Int, max: Int): Int {
            val result = value.opt(key); require(result is Number && result.toDouble().isFinite() && result.toDouble() == result.toLong().toDouble() && result.toLong() in min.toLong()..max.toLong()) { "提醒字段 $key 应为有效整数" }; return result.toInt()
        }
    }
}
