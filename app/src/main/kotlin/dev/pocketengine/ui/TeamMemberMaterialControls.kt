package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.teams.TeamMemberEvidenceHost
import dev.pocketengine.store.TaskStore
import org.json.JSONArray
import org.json.JSONObject

/** Native selection is explicit and per stable member. Nothing here shares the owner's default private context. */
@Composable internal fun TeamMemberMaterialControls(
    run: JSONObject, openPage: (String, String) -> TeamMemberEvidenceHost.Page, runWork: (Runnable) -> Unit
) {
    val members = run.optJSONArray("members").objects().filterNot { it.optBoolean("exited") }
    if (members.isEmpty()) return
    val runId = run.getString("id")
    var expanded by rememberSaveable(runId) { mutableStateOf(false) }
    var memberId by rememberSaveable(runId) { mutableStateOf(members.first().getString("id")) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起成员资料范围" else "成员能读哪些资料") }
        if (!expanded) return@Column
        TeamCaption("共同目标、主人发到本群的消息和团队公开流正常共享。个人记忆、原文、附件、冻结文件和技能默认不共享；请逐成员选择准确版本与范围。账号、密钥、登录状态和远端私人会话不是可分享资料。")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { members.forEach { member -> FilterChip(memberId == member.optString("id"), { memberId = member.getString("id") }, label = { Text(member.optString("name")) }) } }
        key(runId, memberId) { TeamMemberMaterialPage(runId, memberId, openPage, runWork) }
    }
}

@Composable private fun TeamMemberMaterialPage(
    run: String, member: String, open: (String, String) -> TeamMemberEvidenceHost.Page, runWork: (Runnable) -> Unit
) {
    var opening by remember { mutableIntStateOf(0) }
    val opened = remember(run, member, opening) { runCatching { open(run, member) } }
    val page = opened.getOrNull()
    if (page == null) {
        Text("资料范围暂不可用：${opened.exceptionOrNull()?.message ?: "原成员未恢复"}", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { opening++ }) { Text("重新读取原成员") }; return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var catalogue by remember(page) { mutableStateOf(JSONObject()) }
    var current by remember(page) { mutableStateOf(JSONObject()) }
    var selectedRecords by remember(page) { mutableStateOf(setOf<String>()) }
    var selectedSources by remember(page) { mutableStateOf(setOf<Int>()) }
    var sourceRanges by remember(page) { mutableStateOf(mapOf<Int, Pair<String, String>>()) }
    var selectedSkills by remember(page) { mutableStateOf(setOf<String>()) }
    var skillPaths by remember(page) { mutableStateOf(mapOf<String, String>()) }
    var selectedResources by remember(page) { mutableStateOf(setOf<String>()) }
    var resourceRanges by remember(page) { mutableStateOf(mapOf<String, Pair<String, String>>()) }
    var pending by remember(page) { mutableStateOf<TaskStore.TeamMemberMaterialReview?>(null) }
    var shown by remember(page) { mutableStateOf(JSONObject()) }
    var accepted by remember(pending) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    DisposableEffect(page, lifecycle) {
        fun foreground() = page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> foreground() }; lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; lifecycle.removeObserver(observer); page.close() }
    }
    fun <T> work(action: () -> T, apply: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable { val result = runCatching(action); main.post { if (alive) { busy = false; result.fold(apply) { notice = it.message ?: "资料范围未修改，请重读" } } } }) }
        catch (_: Exception) { busy = false; notice = "原工作队列不可用，未提交" }
    }
    fun toggle(set: Set<String>, id: String) = if (id in set) set - id else set + id
    fun selection(): JSONObject {
        val records = JSONArray(); catalogue.optJSONArray("records").objects().filter { "${it.optString("id")}:${it.optLong("revision")}" in selectedRecords }.forEach { records.put(JSONObject().put("id", it.getString("id")).put("revision", it.getLong("revision"))) }
        val sources = JSONArray(); catalogue.optJSONArray("sources").objects().forEachIndexed { index, row -> if (index in selectedSources) { val range = sourceRanges[index] ?: ("0" to "24000"); sources.put(JSONObject(row.toString()).put("offset", range.first.toInt()).put("chars", range.second.toInt())) } }
        val skills = JSONArray(); catalogue.optJSONArray("skills").objects().filter { it.optString("id") in selectedSkills }.forEach { row -> skills.put(JSONObject().put("id", row.getString("id")).put("revision", row.getInt("revision")).put("digest", row.getString("digest")).put("paths", JSONArray((skillPaths[row.getString("id")] ?: "SKILL.md").lines().map(String::trim).filter(String::isNotEmpty)))) }
        val resources = JSONArray(); catalogue.optJSONArray("resources").objects().filter { "${it.optString("kind")}:${it.optString("id")}" in selectedResources }.forEach { row -> val range = resourceRanges["${row.optString("kind")}:${row.optString("id")}"] ?: ("0" to "12000"); resources.put(JSONObject().put("kind", row.getString("kind")).put("id", row.getString("id")).put("sha256", row.getString("sha256")).put("offset", range.first.toInt()).put("chars", range.second.toInt())) }
        return JSONObject().put("allowedRecords", records).put("allowedSources", sources).put("skills", skills).put("resources", resources)
    }
    LaunchedEffect(page) { work({ page.catalogue() to page.current() }) { catalogue = it.first; current = it.second } }
    TeamCaption("当前范围 ${current.optString("state", "未分享")} · 修订 ${current.optLong("revision")}；本页选择不会在批准前生效。已发给模型的历史不能伪称撤回，后续读取/答案接纳会重新核对。")
    catalogue.optJSONArray("records").objects().forEach { row ->
        val key = "${row.optString("id")}:${row.optLong("revision")}"; Row { Checkbox(key in selectedRecords, { selectedRecords = toggle(selectedRecords, key); pending = null }, enabled = !busy); Column { Text("记忆 r${row.optLong("revision")} · ${row.optString("id").take(12)}"); TeamCaption(row.optString("text").take(160)) } }
    }
    catalogue.optJSONArray("sources").objects().forEachIndexed { index, row ->
        val ref = row.getJSONObject("sourceRef"); Row { Checkbox(index in selectedSources, { selectedSources = if (index in selectedSources) selectedSources - index else selectedSources + index; pending = null }, enabled = !busy); Text("准确来源 ${ref.optString("kind")} · ${ref.optString("sourceId").take(12)} / r${ref.optLong("revision")}") }
        if (index in selectedSources) { val range = sourceRanges[index] ?: ("0" to "24000"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(range.first, { sourceRanges = sourceRanges + (index to (it to range.second)); pending = null }, label = { Text("UTF-16起点") }, enabled = !busy, modifier = Modifier.weight(1f)); OutlinedTextField(range.second, { sourceRanges = sourceRanges + (index to (range.first to it)); pending = null }, label = { Text("范围字符数 ≤24000") }, enabled = !busy, modifier = Modifier.weight(1f)) } }
    }
    catalogue.optJSONArray("skills").objects().forEach { row ->
        val id = row.getString("id"); Row { Checkbox(id in selectedSkills, { selectedSkills = toggle(selectedSkills, id); pending = null }, enabled = !busy); Text("技能 ${row.optString("name", id)} · r${row.optInt("revision")} / ${row.optString("digest").take(12)}") }
        if (id in selectedSkills) OutlinedTextField(skillPaths[id] ?: "SKILL.md", { skillPaths = skillPaths + (id to it); pending = null }, label = { Text("只允许这些准确资源路径，每行一个；不授权脚本执行") }, minLines = 2, enabled = !busy, modifier = Modifier.fillMaxWidth())
    }
    catalogue.optJSONArray("resources").objects().forEach { row ->
        val id = "${row.optString("kind")}:${row.optString("id")}"; Row { Checkbox(id in selectedResources, { selectedResources = toggle(selectedResources, id); pending = null }, enabled = !busy); Text("${row.optString("name")} · ${row.optString("sha256").take(12)}") }
        if (id in selectedResources) { val range = resourceRanges[id] ?: ("0" to "12000"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(range.first, { resourceRanges = resourceRanges + (id to (it to range.second)); pending = null }, label = { Text("UTF-16起点") }, enabled = !busy, modifier = Modifier.weight(1f)); OutlinedTextField(range.second, { resourceRanges = resourceRanges + (id to (range.first to it)); pending = null }, label = { Text("范围字符数 ≤24000") }, enabled = !busy, modifier = Modifier.weight(1f)) } }
    }
    if (catalogue.length() == 0) TeamCaption(if (busy) "正在读取你的可选资料…" else "尚未读取资料；未取得私域默认权限。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { work({ page.review(selection()) }) { pending = it; shown = it.view(); accepted = false } }, enabled = !busy) { Text("审阅本成员的精确版本与范围") }
        TextButton(onClick = { work({ page.catalogue() to page.current() }) { catalogue = it.first; current = it.second; pending = null } }, enabled = !busy) { Text("重读") }
    }
    if (current.optString("state") == "active") TextButton(onClick = { work({ page.revoke(current.getLong("revision")) }) { current = it; pending = null; notice = "已撤回后续读取资格，原已提供历史如实保留。" } }, enabled = !busy) { Text("撤回此成员全部私有资料读取") }
    pending?.let { exact ->
        TeamCaption("此次仅分享：准确记忆 ${shown.getJSONArray("allowedRecords").length()} 个版本，原文 ${shown.getJSONArray("allowedSources").length()} 个范围，技能 ${shown.getJSONArray("skills").length()} 个版本，冻结文件/附件 ${shown.getJSONArray("resources").length()} 个范围。空集合不回退owner私域。")
        shown.getJSONArray("allowedSources").objects().forEach { span -> TeamCaption("${span.getJSONObject("sourceRef").optString("sourceId").take(12)} · ${span.optInt("offset")} 起 ${span.optInt("chars")} UTF-16字符") }
        TeamCaption("仅此成员身份修订 ${shown.optInt("identityRevision")}，分享修订 ${shown.optLong("nextScopeRevision")}；到期 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(shown.getLong("expiresAt")))}。不追加模型/工具预算，不授权文件写入、账号或执行。")
        Row { Checkbox(accepted, { accepted = it }, enabled = !busy); Text("我确认给这名成员上述准确资料范围") }
        Button(onClick = { work({ page.approve(exact, shown.getString("digest")) }) { current = it; pending = null; notice = "新范围已保存；正在使用旧范围的请求不再接纳，继续需原成员安全入口。" } }, enabled = accepted && !busy) { Text("确认本成员读取范围") }
        TextButton(onClick = { pending = null }, enabled = !busy) { Text("暂不分享") }
    }
    notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
