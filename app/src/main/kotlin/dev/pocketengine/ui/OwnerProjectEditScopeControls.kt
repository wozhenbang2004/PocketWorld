package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.local.project.OwnerProjectEditScope
import org.json.JSONObject

/** The original task sheet's progressive-disclosure scope editor, not another approval activity. */
@Composable internal fun OwnerProjectEditScopeControls(taskId: String, openPage: (String) -> OwnerProjectEditScope.Page, runWork: (Runnable) -> Unit) {
    var open by remember(taskId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("owner_project_edit_scope")) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 2.dp)) { Text("常规文件编辑范围 ${if (open) "▾" else "▸"}", fontSize = 12.sp) }
        if (open) OwnerProjectEditScopePanel(taskId, openPage, runWork)
    }
}
@Composable private fun OwnerProjectEditScopePanel(taskId: String, openPage: (String) -> OwnerProjectEditScope.Page, runWork: (Runnable) -> Unit) {
    var attempt by remember(taskId) { mutableIntStateOf(0) }
    val opened = remember(taskId, attempt) { runCatching { openPage(taskId) } }
    val page = opened.getOrNull()
    if (page == null) { Text(opened.exceptionOrNull()?.message ?: "请先核对原项目任务", color = MaterialTheme.colorScheme.error, fontSize = 12.sp); TextButton(onClick = { attempt++ }) { Text("重新核对原任务") }; return }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var snapshot by remember(page) { mutableStateOf(JSONObject()) }
    var project by remember(page) { mutableStateOf("") }
    var paths by remember(page) { mutableStateOf("") }
    var create by remember(page) { mutableStateOf(false) }
    var operations by remember(page) { mutableStateOf("12") }
    var minutes by remember(page) { mutableStateOf("30") }
    var review by remember(page) { mutableStateOf<OwnerProjectEditScope.Review?>(null) }
    var shown by remember(page) { mutableStateOf(JSONObject()) }
    var consent by remember(review) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    DisposableEffect(page, lifecycle) {
        fun foreground() = page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> foreground() }; lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; page.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, accept: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable { val result = runCatching(action); main.post { if (alive) { busy = false; result.fold(accept) { notice = it.message ?: "原范围未保存，请重新核对；没有重放文件操作" } } } }) }
        catch (_: Exception) { busy = false; notice = "原工作队列不可用，尚未保存" }
    }
    LaunchedEffect(page) { work({ page.snapshot() }) { snapshot = it; project = it.optJSONObject("binding")?.optJSONObject("developmentContext")?.optString("projectId") ?: "" } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("一次同意这些路径内的常规修改；可另选创建新文件。每次仍检查当前文件版本、原操作预算与真实回执。", fontSize = 12.sp, color = SecondaryInk)
        val scope = snapshot.optJSONObject("scope") ?: JSONObject()
        if (scope.optString("state") in setOf("scope_active", "scope_waiting_run", "exhausted")) {
            Text("当前范围剩余 ${scope.optInt("remainingOperations")} 次原操作 · ${if (scope.optString("state") == "scope_waiting_run") "等待你明确继续原任务" else "仅原执行本轮"}", fontSize = 12.sp)
            TextButton(onClick = { work({ page.revoke(scope.getString("id")) }) { snapshot = JSONObject(snapshot.toString()).put("scope", it); review = null; notice = "已撤销后续范围派发；已发生和未知效果仍保留原记录。" } }, enabled = !busy) { Text("撤销常规编辑范围") }
        }
        val fixedProject = snapshot.optJSONObject("binding")?.optJSONObject("developmentContext")?.optString("projectId")
        if (fixedProject == null) snapshot.optJSONArray("projects").objects().forEach { item ->
            FilterChip(selected = project == item.optString("id"), onClick = { project = item.optString("id"); review = null }, label = { Text(item.optString("name", item.optString("id")), fontSize = 12.sp) }, enabled = !busy)
        } else Text("仅此任务已选择的项目副本：$fixedProject", fontSize = 12.sp, color = SecondaryInk)
        OutlinedTextField(paths, { paths = it; review = null }, label = { Text("文件或目录/（每行一个，最多32项）") }, placeholder = { Text("src/main.js\nsrc/components/") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Row { Checkbox(create, { create = it; review = null }, enabled = !busy); Text("同时允许在这些明确路径内创建文件（不含删除）", fontSize = 12.sp, modifier = Modifier.weight(1f).padding(top = 10.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(operations, { operations = it; review = null }, label = { Text("原操作上限") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
            OutlinedTextField(minutes, { minutes = it; review = null }, label = { Text("最长分钟") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
        }
        OutlinedButton(onClick = { work({ page.prepare(project, paths, create, minutes.toIntOrNull() ?: 0, operations.toIntOrNull() ?: 0) }) { review = it; shown = it.view(); consent = false } }, enabled = !busy && project.isNotBlank() && paths.isNotBlank()) { Text("核对这次编辑范围") }
        review?.let { exact ->
            val policy = shown.getJSONObject("policy")
            val projectId = policy.getJSONObject("target").optString("projectId")
            val projectName = snapshot.optJSONArray("projects").objects().find { it.optString("id") == projectId }?.optString("name") ?: projectId
            Text("仅项目 $projectName · ${projectId.take(12)}\n" + policy.getJSONArray("paths").objectsOrStrings().joinToString("\n") + "\n允许修改${if (policy.optBoolean("allowCreate")) "和创建" else "既有文件"}；最多 ${policy.optInt("operationLimit")} 次原操作，至 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(policy.optLong("expiresAt")))}。", fontSize = 12.sp)
            Text("不增加原任务预算；仅本轮或下一次你明确继续的原执行，暂停、撤销、换身份或到期后失效。命令、删除、外发、发布与手机敏感动作仍按原边界审批。未知写结果先核对，不重放。", fontSize = 12.sp, color = SecondaryInk)
            Row { Checkbox(consent, { consent = it }, enabled = !busy); Text("我同意以上准确路径、动作、期限与原操作上限", fontSize = 12.sp, modifier = Modifier.weight(1f).padding(top = 10.dp)) }
            Button(onClick = { work({ page.approve(exact, shown.getString("digest")) }) { snapshot = JSONObject(snapshot.toString()).put("scope", it); review = null; notice = "范围已记入原任务审批；没有自动启动或重放。若原任务已停在审批，请明确继续原任务。" } }, enabled = consent && !busy, modifier = Modifier.testTag("owner_edit_scope_approve")) { Text("允许范围内连续编辑") }
        }
        notice?.let { Text(it, fontSize = 12.sp) }
    }
}
private fun org.json.JSONArray.objectsOrStrings(): List<String> = (0 until length()).map { optString(it) }
