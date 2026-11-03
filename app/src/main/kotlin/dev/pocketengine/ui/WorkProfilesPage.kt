package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.controlplane.NativeWorkProfileHost
import dev.pocketengine.features.controlplane.WorkProfile
import org.json.JSONArray
import org.json.JSONObject

/** Same original Settings surfaces and task rows. This page stores references, never grants or secrets. */
@Composable
internal fun WorkProfilesPage(host: NativeWorkProfileHost, runWork: (Runnable) -> Unit, runControl: (Runnable) -> Unit,
    start: (String, NativeWorkProfileHost.Selection, RunControl, (Result<JSONObject>) -> Unit) -> Unit,
    openTask: (String) -> Unit, openDomain: (String) -> Unit, back: () -> Unit) {
    val page = remember(host) { host.newOwnerPage() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var foreground by remember(page) { mutableStateOf(false) }
    var busy by remember(page) { mutableStateOf(false) }
    var running by remember(page) { mutableStateOf(false) }
    var snapshot by remember(page) { mutableStateOf(JSONObject()) }
    var choices by remember(page) { mutableStateOf(JSONObject()) }
    var editor by remember(page) { mutableStateOf<JSONObject?>(null) }
    var original by remember(page) { mutableStateOf<JSONObject?>(null) }
    var review by remember(page) { mutableStateOf<NativeWorkProfileHost.Review?>(null) }
    var reviewed by remember(page) { mutableStateOf(JSONObject()) }
    var control by remember(page) { mutableStateOf<RunControl?>(null) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    var resultTask by remember(page) { mutableStateOf<String?>(null) }
    var name by remember(page) { mutableStateOf("") }
    var goal by remember(page) { mutableStateOf("") }
    var picker by remember(page) { mutableStateOf<String?>(null) }
    var removing by remember(page) { mutableStateOf<JSONObject?>(null) }
    var leaving by remember(page) { mutableStateOf<(() -> Unit)?>(null) }
    var consent by remember(review) { mutableStateOf(false) }

    DisposableEffect(page, lifecycle) {
        fun sync() {
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            page.setForeground(foreground)
            if (!foreground && !running) { control?.cancel(); review = null; consent = false }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }; lifecycle.addObserver(observer); sync()
        onDispose { alive = false; if (!running) control?.cancel(); page.close(); lifecycle.removeObserver(observer) }
    }
    fun failure(error: Throwable): String = when (error) {
        is dev.pocketengine.store.SettingsStore.PersistenceUncertain -> "保存结果未确认，请检查存储并重开应用核对；没有按内存值继续。"
        is InterruptedException -> "本次操作已停止，请核对原任务回执。"
        else -> "本次操作未完成，请刷新原来源后重新审阅；已创建的原任务请在任务列表核对，不自动重试。"
    }
    fun <T> work(action: () -> T, accept: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        val executing = running
        try { runWork(Runnable {
            val outcome = runCatching(action)
            main.post { if (alive) { busy = false; running = false; if (executing) { review = null; consent = false }; outcome.fold(accept) {
                notice = failure(it)
            } } }
        }) } catch (_: Exception) { busy = false; running = false; notice = "原工作队列暂不可用，请稍后重开；不自动重试。" }
    }
    fun refresh() { val observation = RunControl(); control = observation; work({ page.snapshot() to page.choices(observation) }) { snapshot = it.first; choices = it.second; review = null } }
    fun edit(row: JSONObject?) {
        review = null; original = row?.let { JSONObject(it.toString()) }
        editor = row?.let { JSONObject(it.toString()) } ?: WorkProfile.definition("draft-profile", 1, "新工作配置"); name = editor!!.getString("name")
    }
    fun leave(action: () -> Unit) {
        if (busy && !running) { notice = "正在核对或保存，请等本次操作完成后再返回；预检可以取消。"; return }
        if (running || goal.isNotBlank() || editor?.let { WorkProfileDrafts.changed(it, original, name) } == true) leaving = action
        else { review = null; action() }
    }
    fun returnBack() = leave { if (!running && editor != null) { editor = null; review = null; goal = "" } else back() }
    fun openOriginal(domain: String) = leave { openDomain(domain) }
    fun begin() {
        val inspected = review ?: return
        val originalControl = control ?: return
        val wanted = goal.trim()
        if (busy || !foreground || !consent || wanted.isEmpty() || !WorkProfileDrafts.runnable(reviewed)) return
        busy = true; running = true; notice = null
        val complete: (Result<JSONObject>) -> Unit = { outcome -> main.post { if (alive) {
            busy = false; running = false; review = null; consent = false
            outcome.fold({ resultTask = it.getString("taskId"); goal = ""; if (it.optString("status") == "failed") notice = it.optString("content") }, { notice = failure(it) })
        } } }
        try { runWork(Runnable {
            runCatching { page.select(inspected, inspected.digest, originalControl) }.fold({ selected ->
                try { start(wanted, selected, originalControl, complete) } catch (unavailable: Exception) { complete(Result.failure(unavailable)) }
            }, { complete(Result.failure(it)) })
        }) } catch (unavailable: Exception) { complete(Result.failure(unavailable)) }
    }
    LaunchedEffect(page) { refresh() }
    BackHandler { returnBack() }
    Column(Modifier.fillMaxSize().testTag("work_profiles_page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { returnBack() }) { Text("返回") }
            Text("工作配置", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (busy) TextButton(onClick = { val current = control; if (current != null) runControl(Runnable { current.cancel() }) }) { Text(if (running) "请求停止" else "取消预检") }
        }
        HorizontalDivider(color = Line)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            resultTask?.let { id -> TextButton(onClick = { openTask(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看这次原任务与回执") } }
            if (editor == null) {
                SettingsSurface("一次选好，同一原任务执行", "引用已有模型、技能、插件工具与 JS 扩展；不复制凭据，不启用来源，也不改变权限。") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { edit(null) }, enabled = !busy && foreground) { Text("新建工作配置") }
                        TextButton(onClick = { refresh() }, enabled = !busy) { Text("刷新原来源") }
                    }
                    TextButton(onClick = { openOriginal("models") }) { Text("管理原模型与连接") }
                }
                val rows = profileRows(snapshot.optJSONArray("profiles"))
                if (rows.isEmpty()) Text("还没有工作配置。从已有连接和已启用工具中选择，不会自动安装或授权。", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
                rows.forEach { row -> PocketTaskCard(row.optString("name"), "已保存引用", "模型 ${row.optJSONObject("modelPolicies")?.length() ?: 0} 项 · 技能 ${row.optJSONArray("skillRefs")?.length() ?: 0} 项 · 插件 ${row.optJSONArray("pluginRefs")?.length() ?: 0} 项", "修订 ${row.optLong("revision")}", null, "选择与预检", false) { if (!busy && foreground) edit(row) } }
            } else {
                val draft = editor!!
                SettingsSurface(name.ifBlank { "工作配置" }, if (original == null) "新配置 · 保存只记录原引用" else "已保存修订 ${original!!.optLong("revision")} · 编辑后须重新预检") {
                    OutlinedTextField(name, { name = it.take(80); review = null }, enabled = !busy && foreground, label = { Text("配置名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { val toSave = JSONObject(draft.toString()).put("name", name); val old = original; work({ page.save(old?.optString("profileId").orEmpty(), old?.optLong("revision") ?: 0, toSave.getString("name"), toSave) to page.snapshot() }) { original = it.first; editor = JSONObject(it.first.toString()); snapshot = it.second; name = it.first.getString("name"); review = null } }, enabled = !busy && foreground && name.isNotBlank()) { Text("保存引用") }
                        if (original != null) TextButton(onClick = { removing = original }, enabled = !busy && foreground) { Text("移除配置") }
                    }
                    TextButton(onClick = { refresh() }, enabled = !busy && foreground) { Text("刷新来源，不丢弃当前编辑") }
                }
                SettingsSurface("模型", "主任务和摘要都须明确选择。原连接或模型组变化会重新核对，不补全未审阅的默认目标。") {
                    listOf("MAIN" to "主任务", "SUMMARY" to "摘要", "RELAY" to "外部 Harness 网关（可选 API）").forEach { (purpose, title) ->
                        val target = draft.getJSONObject("modelPolicies").optJSONObject(purpose)
                        TextButton(onClick = { picker = purpose }, enabled = !busy && foreground, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$title · ${target?.optString("groupId", target.optString("modelId")) ?: "请选择原模型"}") }
                    }
                    if (draft.getJSONObject("modelPolicies").has("RELAY")) TextButton(onClick = { editor = WorkProfileDrafts.withoutRelayModel(draft); review = null }, enabled = !busy && foreground) { Text("不指定本任务网关模型，保留原宿主选择") }
                    Text("网关模型仅由原生主人批准的准确子任务范围消费，不代表远端配置已修改。控制结束后，可在原电脑任务页审阅同一任务的仅网关恢复，不重启主任务或续给预算。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    TextButton(onClick = { openOriginal("accounts") }) { Text("账号模型须先在原账号页选择") }
                    val concurrency = draft.getJSONObject("resourcePreferences").optInt("maxConcurrentModelCalls", 0)
                    TextButton(onClick = { picker = "resourcePreferences" }, enabled = !busy && foreground, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("本任务模型并发 · ${if (concurrency == 0) "保留原宿主限制" else "最多 $concurrency 路"}") }
                    Text("配置上限只收窄这项原任务，与账号、网关及原范围限制共同生效；不提高预算，不降低其他任务的并行能力。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                }
                SettingsSurface("任务指导、技能与工具", "任务指导引用原 Skill 文档全文，作为本任务低优先级参考，不自动选用技能执行权限；未选的插件与扩展不进入工具面。") {
                    listOf("instructions" to "任务指导", "skills" to "技能", "plugins" to "插件工具", "extensions" to "JS 扩展").forEach { (kind, title) ->
                        val field = when(kind) { "instructions" -> "instructionRefs"; "skills" -> "skillRefs"; "plugins" -> "pluginRefs"; else -> "extensionRefs" }
                        TextButton(onClick = { picker = kind }, enabled = !busy && foreground, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$title · 已选 ${draft.getJSONArray(field).length()} 项") }
                    }
                    ProfileSelectedReferences(draft, busy || !foreground) { editor = it; review = null }
                    TextButton(onClick = { openOriginal("pluginCatalog") }) { Text("在原目录管理安装、MCP 与源码审批") }
                }
                SettingsSurface("预检后，再明确启动", "预检只核原来源，不运行工具，不代表配置已应用或目标已连通。此入口接入主任务、摘要、任务指导参考及已支持的技能与工具选用。") {
                    Text("团队、MEMBER 与运行目标配置尚未接入本入口；已有这些字段可保留与预检，但不会静默忽略后运行。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    Button(onClick = { val saved = original ?: return@Button; val observing = RunControl(); control = observing; work({ page.review(saved.getString("profileId"), saved.getLong("revision"), observing) }) { review = it; reviewed = it.view() } }, enabled = !busy && foreground && original != null && name == original!!.optString("name") && WorkProfile.fingerprint(draft) == WorkProfile.fingerprint(original!!)) { Text("预检这项已保存修订") }
                    if (review != null) {
                        Text(if (reviewed.optBoolean("ready")) "原来源预检通过 · 尚未运行" else "预检有阻塞 · 未启动", style = MaterialTheme.typography.titleSmall)
                        profileRows(reviewed.optJSONArray("items")).forEach { item -> Text("${item.optString("kind")} · ${item.optString("id")} · ${item.optString("status")}${if (item.optBoolean("optional") && !item.optBoolean("included")) "（明确省略可选项）" else ""}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
                        if (!WorkProfileDrafts.runnable(reviewed)) Text("该配置尚不能由本入口运行。请补齐两项模型；若含尚未接入的字段，请保留原定义并新建只选用已支持来源的配置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        OutlinedTextField(goal, { goal = it.take(4000) }, enabled = !busy && foreground, label = { Text("本次原任务目标") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }, enabled = !busy && foreground); Text("核对以上引用，按原任务范围开始；外部效果仍逐项走原授权。", style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = { begin() }, enabled = !busy && foreground && consent && goal.isNotBlank() && WorkProfileDrafts.runnable(reviewed), modifier = Modifier.heightIn(min = 48.dp).testTag("work_profile_start_exact")) { Text("按这项修订开始原任务") }
                    }
                }
            }
        }
    }
    val selectedDraft = editor
    if (picker != null && selectedDraft != null) ProfileSourcePicker(picker!!, choices, selectedDraft, busy,
        { picker = null }, { editor = it; review = null }, { openOriginal(it) })
    leaving?.let { action -> AlertDialog(onDismissRequest = { leaving = null }, title = { Text(if (running) "离开这次运行页面？" else "放弃未保存的编辑？") }, text = { Text(if (running) "离开页面不会停止已启动的原任务，也不会撤回已有外部效果。请从任务列表查看回执或明确停止；若仍在启动预检，离开会使本次审阅失效。" else "当前未保存的配置引用或本次任务目标不会保留。已保存的定义、原任务与回执不变。") }, confirmButton = { TextButton(onClick = { leaving = null; if (!running) control?.cancel(); review = null; action() }) { Text(if (running) "离开本页" else "放弃并返回") } }, dismissButton = { TextButton(onClick = { leaving = null }) { Text("继续留在这里") } }) }
    removing?.let { row -> AlertDialog(onDismissRequest = { if (!busy) removing = null }, title = { Text("移除这项工作配置？") }, text = { Text("只移除可重复选用的定义。原任务冻结引用、权限与回执保留，不停止或重放任务。") }, confirmButton = { TextButton(onClick = { work({ page.remove(row.getString("profileId"), row.getLong("revision")); page.snapshot() }) { snapshot = it; removing = null; original = null; editor = null; review = null } }, enabled = !busy && foreground) { Text("移除定义") } }, dismissButton = { TextButton(onClick = { removing = null }, enabled = !busy) { Text("保留") } }) }
}

private fun profileRows(rows: JSONArray?): List<JSONObject> = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }

@Composable
private fun ProfileSelectedReferences(definition: JSONObject, disabled: Boolean, changed: (JSONObject) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起已选引用" else "核对已选引用与可选项") }
    if (expanded) listOf("instructionRefs", "skillRefs", "pluginRefs", "extensionRefs").forEach { field -> profileRows(definition.optJSONArray(field)).forEach { ref ->
        Text(ref.optString("id") + when(field) { "instructionRefs" -> " · 任务指导参考 / 修订 ${ref.optLong("revision")}"; "skillRefs" -> " · 修订 ${ref.optLong("revision")}"; "pluginRefs" -> " · 安装 ${ref.optString("installationId").takeLast(8)} / 代次 ${ref.optLong("stateRevision")}"; else -> " · 版本 ${ref.optString("version")} / 审批 ${ref.optLong("approvalRevision")}" }, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ref.optBoolean("optional"), { changed(WorkProfileDrafts.withOptional(definition, field, ref.getString("id"), it)) }, enabled = !disabled); Text("明确可选：来源不满足时省略，不自动替换", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { changed(WorkProfileDrafts.withoutReference(definition, field, ref.getString("id"))) }, enabled = !disabled) { Text("移除这项引用，不改原来源") }
    } }
}

@Composable
private fun ProfileSourcePicker(kind: String, choices: JSONObject, definition: JSONObject, busy: Boolean, close: () -> Unit, changed: (JSONObject) -> Unit, openDomain: (String) -> Unit) {
    if (kind == "resourcePreferences") {
        AlertDialog(onDismissRequest = close, title = { Text("本任务模型并发上限") }, text = {
            Column {
                Text("只在这项原任务内减少同时派发。等待使用原总期限，暂停、停止或撤权时不继续派发；已执行调用与已用预算不回滚。", style = MaterialTheme.typography.bodySmall)
                listOf<Int?>(null, 1, 2).forEach { limit -> TextButton(onClick = { changed(WorkProfileDrafts.withModelConcurrency(definition, limit)); close() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (limit == null) "保留原宿主限制，不另设上限" else "本任务最多 $limit 路，同时遵守原限制") } }
            }
        }, confirmButton = { TextButton(onClick = close) { Text("返回，不改变") } })
        return
    }
    val models = kind in setOf("MAIN", "SUMMARY", "RELAY")
    val skillSource = kind in setOf("instructions", "skills")
    val section = choices.optJSONObject("sections")?.optJSONObject(if (kind == "instructions") "skills" else kind)
    AlertDialog(onDismissRequest = close, title = { Text(if (models) "选择原${when(kind) { "MAIN" -> "主任务"; "RELAY" -> "API 网关"; else -> "摘要" }}模型" else "选择原${when(kind) { "instructions" -> "任务指导（Skill 文档）"; "skills" -> "技能"; "plugins" -> "插件工具"; else -> "JS 扩展" }}") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (kind == "instructions") Text("主人选择的任务指导仅进入原请求参考层，不提升为系统指令或工具权限。全文超出原参考窗口时明确停止，请减选后重审；不会静默截断。", style = MaterialTheme.typography.bodySmall)
            if (kind == "RELAY") Text("仅选已有 API 连接或允许外部网关用途的模型组。账号计划不能用于外部 Harness；不重配目标，也不新增预算。", style = MaterialTheme.typography.bodySmall)
            if (models) {
                val rows = profileRows(WorkProfileDrafts.modelChoices(choices, kind))
                if (rows.isEmpty()) Text("暂无可用原连接，请先在模型或账号页配置；不会生成或自动替换模型。", style = MaterialTheme.typography.bodySmall)
                rows.forEach { row -> TextButton(onClick = { changed(WorkProfileDrafts.withModel(definition, kind, row.getJSONObject("reference"))); close() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(row.optString("label") + " · " + row.optString("detail")) } }
            } else if (section?.optBoolean("available") != true) Text("原来源暂不可读取，不能当作空目录或已就绪。请回原管理页核对。", style = MaterialTheme.typography.bodySmall)
            else {
                val rows = profileRows(section.optJSONArray("items"))
                if (rows.isEmpty()) Text("原目录没有条目。请先在原管理页导入并审阅。", style = MaterialTheme.typography.bodySmall)
                rows.forEach { row ->
                    Text(row.optString("name", row.optString("id")), style = MaterialTheme.typography.titleSmall)
                    if (kind == "plugins") profileRows(row.optJSONArray("tools")).forEach { tool ->
                        val selected = WorkProfileDrafts.hasPluginTool(definition, row, tool.getString("name"))
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(selected, { changed(WorkProfileDrafts.withPluginTool(definition, row, tool.getString("name"), it)) }, enabled = !busy && (selected || row.optBoolean("enabled") && tool.optBoolean("granted"))); Text(tool.optString("name") + if (tool.optBoolean("granted")) " · 原权限已审阅" else " · 未获原权限", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        val field = when(kind) { "instructions" -> "instructionRefs"; "skills" -> "skillRefs"; else -> "extensionRefs" }
                        val exactRef = if (skillSource) WorkProfileDrafts.skillReference(row) else WorkProfileDrafts.extensionReference(row)
                        val selected = WorkProfileDrafts.matchesReference(definition, field, exactRef)
                        val available = if (skillSource) row.optBoolean("active") && row.optBoolean("contentVerified") else row.optBoolean("enabled") && row.optBoolean("approved")
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(selected, { val ref = if (skillSource) WorkProfileDrafts.skillReference(row) else WorkProfileDrafts.extensionReference(row); changed(WorkProfileDrafts.withReference(definition, field, ref, it)) }, enabled = !busy && (selected || available)); Text(if (available) "选择当前准确修订${if (WorkProfileDrafts.contains(definition, field, row.getString("id")) && !selected) "（替换已有旧引用）" else ""}" else "未启用或未审阅，须先回原来源处理", style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider(color = Line)
                }
            }
        } }, confirmButton = { TextButton(onClick = close) { Text("完成选择") } }, dismissButton = { TextButton(onClick = { close(); openDomain(if (models) "models" else when(kind) { "instructions" -> "skills"; "extensions" -> "pluginCatalog"; else -> kind }) }) { Text("回原来源管理") } })
}
