package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun NativeReminders(state: PocketUiState, controller: PocketWorldController) {
    var create by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val rules = state.feature("automation").optJSONArray("rules").objects()
    CenterCaption("提醒按手机系统调度，可能延后。手机目标会通知你查看并开始，不会擅自操作屏幕。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { create = true }) { Text("新建提醒") }
        TextButton(onClick = { controller.requestFeature("automation", "list"); controller.requestFeature("automation", "receipts") }, enabled = "automation.receipts" !in state.moduleBusy && "automation.list" !in state.moduleBusy) { Text("刷新记录") }
    }
    TextButton(onClick = { controller.openConnection("commitments") }) { Text("查看承诺与持续跟进", fontSize = 12.sp) }
    state.moduleErrors.filterKeys { it in setOf("automation.list", "automation.receipts", "automation.setEnabled", "automation.delete") }.values.distinct().forEach { Text(it, color = Danger, fontSize = 12.sp) }
    rules.forEach { rule ->
        val editableCalendar = runCatching { dev.pocketengine.features.automation.AutomationSchedule.normalize(rule.getJSONObject("schedule")) }.isSuccess
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.optString("title"), fontSize = 14.sp)
                CenterCaption(if (rule.optLong("nextAt", -1) > 0) "计划时间：" + centerDate(rule.optLong("scheduledAt", rule.optLong("nextAt"))) else "已暂停或安排已结束")
                if (rule.optLong("nextAt", -1) > 0 && rule.optLong("nextAt") != rule.optLong("scheduledAt")) CenterCaption("下次检查：" + centerDate(rule.optLong("nextAt")))
                val schedule = rule.optJSONObject("schedule")
                if (schedule?.optString("type") == "cron") CenterCaption("cron · ${schedule.optString("expression")} · ${schedule.optString("zone")}")
                CenterCaption("规则版本 ${rule.optInt("revision")}")
                if (!editableCalendar) CenterCaption("此日历格式或版本暂不支持。暂停保留原记录；删除会移除此提醒。")
                Row {
                    TextButton(onClick = { editing = JSONObject(rule.toString()) }, enabled = editableCalendar && !rule.optBoolean("sourceInvalidated") && !rule.optBoolean("sourceCheckPending")) { Text("编辑", fontSize = 12.sp) }
                    TextButton(onClick = { deleting = JSONObject(rule.toString()) }) { Text("删除", fontSize = 12.sp, color = Danger) }
                }
            }
            Switch(checked = rule.optBoolean("enabled"), onCheckedChange = { controller.requestFeature("automation", "setEnabled", JSONObject().put("id", rule.optString("id")).put("revision", rule.optInt("revision")).put("enabled", it)) }, enabled = "automation.setEnabled" !in state.moduleBusy && !rule.optBoolean("sourceInvalidated") && (rule.optBoolean("enabled") || editableCalendar))
        }
        HorizontalDivider(color = Line)
    }
    val receipts = state.moduleResults["automation.receipts"]?.optJSONArray("receipts").objects().takeLast(10).reversed()
    if (receipts.isNotEmpty()) CenterHeading("最近投递")
    receipts.forEach { receipt ->
        Text(receipt.optString("title"), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        CenterCaption(receipt.optString("message"))
        val planned = receipt.optLong("occurrenceAt", -1)
        if (planned > 0) CenterCaption("计划时间：${centerDate(planned)} · 领取延后 ${maxOf(0L, receipt.optLong("claimedAt", planned) - planned) / 1000} 秒")
        if (receipt.optString("status") == "delivery_unknown") CenterCaption("投递情况仍未知；刷新记录只核对原记录，不会重发。")
        val goalState = receipt.optJSONObject("goalState")
        if (goalState != null) CenterCaption("原任务：" + reminderGoalStateLabel(goalState.optString("state")))
        if (receipt.optString("kind") == "phone_goal" && receipt.optString("status") in setOf("awaiting_user", "blocked_notifications")) TextButton(onClick = { controller.prepareReminderOccurrence(receipt.optString("id")) }) { Text("查看原任务目标", fontSize = 12.sp) }
    }
    if (create || editing != null) key(editing?.optString("id"), editing?.optInt("revision")) { ReminderEditor(state, controller, editing) { create = false; editing = null } }
    deleting?.let { rule -> ConfirmAction("删除这个提醒？", rule.optString("title"), "删除提醒", { deleting = null }) { controller.requestFeature("automation", "delete", JSONObject().put("id", rule.optString("id")).put("revision", rule.optInt("revision"))); deleting = null } }
}

private fun reminderGoalStateLabel(state: String): String = when (state) {
    "succeeded" -> "已由原结果确认完成"
    "running" -> "执行中"
    "paused", "waiting_user", "awaiting_user", "awaiting_authorization" -> "等待你核对或授权"
    "failed" -> "执行失败，请查看原任务"
    "cancelled" -> "已取消"
    "reconciling", "delivery_unknown", "host_evidence_unavailable", "evidence_mismatch", "unverified_result" -> "仍需核对，尚无完成确认"
    "stopping" -> "正在停止，尚未确认释放"
    "expired", "missed_window" -> "有效时间已过"
    else -> "尚无执行完成确认"
}

/** Native editor draft only. Same normalizer/preview/save shape; no Book write, grant or model call. */
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
internal fun reminderRuleDraft(original: JSONObject?, title: String, phone: Boolean, goal: String, schedule: JSONObject, misfire: String, overlap: String, maxCatchUp: Int): JSONObject {
    val policy = JSONObject(original?.optJSONObject("policy")?.toString() ?: "{}")
    policy.put("misfire", misfire).put("overlap", overlap).put("maxCatchUp", maxCatchUp)
    return JSONObject().put("title", title.trim()).put("kind", if (phone) "phone_goal" else "reminder").put("goal", goal.trim()).put("schedule", schedule).put("policy", policy).apply {
        if (original == null) put("enabled", true) else put("id", original.getString("id")).put("revision", original.getInt("revision"))
    }
}

internal fun reminderPreservedSchedule(original: JSONObject): JSONObject = dev.pocketengine.features.automation.AutomationSchedule.normalize(original).put("scheduleVersion", dev.pocketengine.features.automation.AutomationSchedule.SCHEDULE_VERSION)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderEditor(state: PocketUiState, controller: PocketWorldController, original: JSONObject?, close: () -> Unit) {
    val initialSchedule = remember { original?.optJSONObject("schedule")?.let { JSONObject(it.toString()) } }
    val initialPolicy = remember { original?.optJSONObject("policy") }
    var title by rememberSaveable { mutableStateOf(original?.optString("title") ?: "") }
    var type by rememberSaveable { mutableStateOf(initialSchedule?.optString("type") ?: "once") }
    var typeMenu by remember { mutableStateOf(false) }
    var time by rememberSaveable { mutableStateOf(initialSchedule?.optString("time", "09:00") ?: "09:00") }
    var minutes by rememberSaveable { mutableStateOf(if (initialSchedule?.optString("type") == "interval") (initialSchedule.optLong("intervalMillis") / 60000L).toString() else "30") }
    var zone by rememberSaveable { mutableStateOf(initialSchedule?.optString("zone", ZoneId.systemDefault().id) ?: ZoneId.systemDefault().id) }
    var weekdays by rememberSaveable { mutableStateOf(initialSchedule?.optJSONArray("daysOfWeek")?.let { days -> (0 until days.length()).joinToString(",") { days.getInt(it).toString() } } ?: "1,2,3,4,5") }
    var monthDay by rememberSaveable { mutableStateOf((initialSchedule?.optInt("dayOfMonth", 1) ?: 1).toString()) }
    var cron by rememberSaveable { mutableStateOf(initialSchedule?.optString("expression", "0 9 * * 1-5") ?: "0 9 * * 1-5") }
    var lastDay by rememberSaveable { mutableStateOf(initialSchedule?.optString("shortMonthPolicy") == "last_day") }
    var skipGap by rememberSaveable { mutableStateOf(initialSchedule?.optString("dstGap") == "skip") }
    var laterOverlap by rememberSaveable { mutableStateOf(initialSchedule?.optString("dstOverlap") == "later") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var phone by rememberSaveable { mutableStateOf(original?.optString("kind") == "phone_goal") }
    var goal by rememberSaveable { mutableStateOf(original?.optString("goal") ?: "") }
    var misfire by rememberSaveable { mutableStateOf(initialPolicy?.optString("misfire", "skip") ?: "skip") }
    var overlap by rememberSaveable { mutableStateOf(initialPolicy?.optString("overlap", "buffer_one") ?: "buffer_one") }
    var maxCatchUp by rememberSaveable { mutableStateOf((initialPolicy?.optInt("maxCatchUp", 3) ?: 3).toString()) }
    var submittedRequest by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val busy = "automation.save" in state.moduleBusy
    val types = listOf("once" to "一次", "interval" to "固定间隔", "daily" to "每天", "weekly" to "每周", "monthly" to "每月", "cron" to "Cron 表达式")
    val calendar = type in setOf("daily", "weekly", "monthly", "cron")
    val repeating = type != "once"
    fun timingKey() = listOf(type, minutes, time, zone, weekdays, monthDay, lastDay, skipGap, laterOverlap, cron).joinToString("|")
    val initialTiming = rememberSaveable { timingKey() }
    fun schedule(now: Long) = if (initialSchedule != null && timingKey() == initialTiming) reminderPreservedSchedule(initialSchedule) else reminderScheduleDraft(type, minutes, time, zone, weekdays, monthDay, lastDay, skipGap, laterOverlap, now, cron)
    val now = System.currentTimeMillis()
    val draft = runCatching { schedule(now) }
    val previewResult = draft.mapCatching { dev.pocketengine.features.automation.AutomationSchedule.preview(it, now, 3) }
    val preview = previewResult.getOrNull()
    val nextExists = (preview?.optJSONArray("occurrences")?.length() ?: 0) > 0
    val receivedRequest = state.moduleResultInputs["automation.save"]?.optString("_requestId")
    LaunchedEffect(busy, receivedRequest) {
        if (submittedRequest != null && receivedRequest == submittedRequest && !busy && state.moduleErrors["automation.save"] == null && state.moduleResults["automation.save"]?.optJSONObject("saveReceipt")?.optString("state") == "saved") close()
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Color.White, title = { Text(if (original == null) "新建提醒" else "编辑提醒") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (original != null) CenterCaption("正在编辑规则版本 ${original.optInt("revision")}。保存保留当前启停状态与已用次数；旧版本未投递的提醒会取消。")
        OutlinedTextField(title, { title = it.take(200) }, label = { Text("提醒内容") }, maxLines = 3)
        Box {
            OutlinedButton(onClick = { typeMenu = true }, enabled = !busy) { Text("时间安排：" + (types.firstOrNull { it.first == type }?.second ?: "请选择安排") + " ▾") }
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) { types.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { type = value; typeMenu = false }) } }
        }
        if (calendar) {
            if (type == "cron") {
                OutlinedTextField(cron, { cron = it.take(dev.pocketengine.features.automation.AutomationSchedule.MAX_CRON_LENGTH) }, label = { Text("Cron：分 时 日 月 周") }, singleLine = true)
                CenterCaption("例：0 9 * * 1-5 表示工作日09:00。五字段仅支持数字、*、逗号、递增范围、*/步长和范围/步长；周日为0。日与周至少一项覆盖全部，按当地分钟取值；不支持秒、年、别名或特殊日期语法。每次查找下一次安排最多向后8年。")
            } else OutlinedTextField(time, { time = it.take(5) }, label = { Text("当地时间 HH:mm") }, singleLine = true)
            OutlinedTextField(zone, { zone = it.take(80) }, label = { Text("时区，例如 Asia/Shanghai") }, singleLine = true)
        } else OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(6) }, label = { Text(if (type == "interval") "每隔多少分钟" else "多少分钟后") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        if (type == "interval") CenterCaption(if (original == null) "首次约在所填分钟数后；随后按这个固定起点间隔。改时区不改间隔，后台调度可能延后。" else "仅改提醒内容会保留原固定起点；修改分钟数后，从保存时计算新的首次时间。")
        if (type == "weekly") FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEachIndexed { index, label ->
                val day = (index + 1).toString()
                FilterChip(selected = day in weekdays.split(','), onClick = { val selected = weekdays.split(',').filter { it.isNotBlank() }.toMutableSet(); if (!selected.add(day)) selected.remove(day); weekdays = selected.sorted().joinToString(",") }, label = { Text(label) }, enabled = !busy)
            }
        }
        if (type == "monthly") {
            OutlinedTextField(monthDay, { monthDay = it.filter(Char::isDigit).take(2) }, label = { Text("每月几日（1–31）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("短月使用最后一天", Modifier.weight(1f), fontSize = 13.sp); Switch(lastDay, { lastDay = it }, enabled = !busy) }
            CenterCaption(if (lastDay) "例如31日遇到2月，会使用2月最后一天。" else "当月没有所选日期时，跳过当月。")
        }
        if (calendar) {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起夏令时选项" else "夏令时选项") }
            if (advanced) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("不存在的当地时间跳过", Modifier.weight(1f), fontSize = 13.sp); Switch(skipGap, { skipGap = it }, enabled = !busy) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("重复时间选择较晚一次", Modifier.weight(1f), fontSize = 13.sp); Switch(laterOverlap, { laterOverlap = it }, enabled = !busy) }
            }
        }
        previewResult.exceptionOrNull()?.message?.let { Text(it, color = Danger, fontSize = 12.sp) }
        if (preview != null && !nextExists) Text("支持的日期范围内没有下一次安排，请修改时间。", color = Danger, fontSize = 12.sp)
        preview?.optJSONArray("occurrences").objects().forEach { occurrence ->
            CenterCaption("预计：" + occurrence.optString("local").replace('T', ' ') + " · " + occurrence.optString("zone") + " · " + occurrence.optString("offset"))
            when (occurrence.optString("dstResolution")) {
                "gap_shifted_forward" -> CenterCaption("原当地时间 ${occurrence.optString("requestedLocal").replace('T', ' ')} 不存在，已按所选策略向后移。")
                "overlap_earlier" -> CenterCaption("当地时间重复，使用较早一次。")
                "overlap_later" -> CenterCaption("当地时间重复，使用较晚一次。")
            }
        }
        CenterCaption("日历版本1。预览为计划时间；新建或修改固定间隔时，保存会重算首次起点。固定间隔预览为UTC。系统可能延后，不保证秒级准点。")
        Row(verticalAlignment = Alignment.CenterVertically) { Text("提醒我开始手机任务", Modifier.weight(1f), fontSize = 13.sp); Switch(phone, { phone = it }, enabled = !busy) }
        if (phone) OutlinedTextField(goal, { goal = it.take(4000) }, label = { Text("手机任务目标") }, minLines = 2, maxLines = 5)
        if (repeating) {
            Text("错过安排时间时", fontSize = 14.sp)
            listOf("skip" to "跳过旧实例，只处理最新一次", "coalesce" to "合并错过的实例为一次", "catch_up" to "按原安排逐次补处理").forEach { (value, label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = misfire == value, onClick = { misfire = value }, enabled = !busy); Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f)) } }
            if (misfire == "catch_up") OutlinedTextField(maxCatchUp, { maxCatchUp = it.filter(Char::isDigit).take(2) }, label = { Text("本轮最多补处理次数（1–20）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("原任务仍未结束时", fontSize = 14.sp)
            listOf("buffer_one" to "保留一个待处理实例", "skip" to "跳过新实例", "allow" to "允许并行（仍各自需要原任务授权）").forEach { (value, label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = overlap == value, onClick = { overlap = value }, enabled = !busy); Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f)) } }
            CenterCaption("投递未知或旧任务结果未核对时，不会盲目重发。")
        }
        submitError?.let { Text(it, color = Danger, fontSize = 12.sp) }
        state.moduleErrors["automation.save"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    } }, confirmButton = { Button(onClick = {
        val currentDraft = runCatching { schedule(System.currentTimeMillis()) }
        if (currentDraft.isSuccess) {
        submitError = null
        submittedRequest = controller.requestFeature("automation", "save", JSONObject().put("rule", reminderRuleDraft(original, title, phone, goal, currentDraft.getOrThrow(), if (repeating) misfire else "skip", if (repeating) overlap else "buffer_one", maxCatchUp.toIntOrNull() ?: 3)))
        } else submitError = currentDraft.exceptionOrNull()?.message ?: "时间安排当前不可用，请核对后重试"
    }, enabled = !busy && previewResult.isSuccess && nextExists && (misfire != "catch_up" || (maxCatchUp.toIntOrNull() ?: 0) in 1..20) && title.isNotBlank() && (!phone || goal.isNotBlank())) { Text(if (busy) "保存中" else if (original == null) "创建提醒" else "保存修改") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}

