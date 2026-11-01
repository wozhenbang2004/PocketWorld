package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.NativePluginHost
import org.json.JSONArray
import org.json.JSONObject

/** Task preparation stays beside this task's other capabilities, not a second global plugin manager. */
@Composable internal fun TaskJsSelectionControls(taskId: String, host: NativePluginHost, runWork: (Runnable) -> Unit) {
    val page = remember(taskId, host) { host.newTaskSelection(taskId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var current by remember(page) { mutableStateOf(JSONObject()) }
    var selected by remember(page) { mutableStateOf<Set<String>>(emptySet()) }
    var expanded by remember(page) { mutableStateOf(false) }
    var busy by remember(page) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    var reviewed by remember(page) { mutableStateOf<NativePluginHost.SelectionReview?>(null) }
    var acknowledged by remember(reviewed) { mutableStateOf(false) }
    DisposableEffect(page, lifecycle) {
        fun foreground() = page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> foreground() }
        lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; page.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, accept: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable { val result = runCatching(action); main.post { if (alive) { busy = false; result.fold(accept) { notice = it.message ?: "请读取原任务的当前选用，未重新提交" } } } }) }
        catch (_: Exception) { busy = false; notice = "工作队列不可用，本次选用未开始" }
    }
    fun show(snapshot: JSONObject) {
        check(snapshot.optString("taskId") == taskId) { "选用不属于原任务" }
        current = snapshot
        selected = jsSelectionRows(snapshot.optJSONArray("available")).filter { it.optBoolean("selected") && it.optBoolean("selectionCurrent") }.map { it.getString("id") }.toSet()
    }
    LaunchedEffect(page) { work({ page.snapshot() }, ::show) }
    HorizontalDivider(color = Line)
    Text("本任务的 JavaScript 扩展", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Text("只选这项任务需要的纯计算扩展，最多 16 个。选用不批准源码、不增加工具权限；没有网络、文件或宿主工具桥。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
    notice?.let { Text(it, color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp) }
    val available = jsSelectionRows(current.optJSONArray("available"))
    val prior = jsSelectionRows(current.optJSONObject("selection")?.optJSONArray("programs"))
    if (current.has("selection")) {
        if (current.getJSONObject("selection").optString("mode") == "inherit_enabled") Text("尚未单独选用，保留原先全局已启用集合；保存后只使用本页明确选择的版本。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
        val missing = prior.filter { ref -> available.none { it.optString("id") == ref.optString("id") && it.optBoolean("selectionCurrent") } }
        if (missing.isNotEmpty()) Text("原选用已撤权或改版：${missing.joinToString("、") { it.optString("id") }}。不会自动换版或静默跳过；请重新选择当前版本。", color = Danger, fontSize = 13.sp, lineHeight = 21.sp)
        if (!current.optBoolean("editable")) Text("当前原任务仍在执行或有待核对动作。请先暂停并核对回执，再修改选用；此页不会重启任务。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
        if (available.isEmpty()) Text("暂无已批准并启用的扩展。可保存空集合，明确不挂载 JS；安装与源码批准仍在插件管理完成。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
        TextButton(onClick = { expanded = !expanded }, enabled = !busy) { Text(if (expanded) "收起选用列表" else "选择需要的扩展（${selected.size} / 16）") }
        if (expanded) available.forEach { source ->
            val id = source.getString("id")
            Row(Modifier.fillMaxWidth()) {
                Checkbox(id in selected, { checked -> selected = if (checked) selected + id else selected - id }, enabled = !busy && current.optBoolean("editable") && (id in selected || selected.size < 16))
                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                    Text(source.optString("name"), fontSize = 14.sp)
                    Text("$id · ${source.optString("version")}", color = SecondaryInk, fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { work({ page.review(selected) }) { reviewed = it } }, enabled = !busy && current.optBoolean("editable") && selected.size <= 16) { Text("审阅本任务选用") }
            TextButton(onClick = { work({ page.snapshot() }, ::show) }, enabled = !busy) { Text("重新读取") }
        }
    }
    if (!current.has("selection") && !busy) TextButton(onClick = { work({ page.snapshot() }, ::show) }) { Text("读取本任务选用") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    reviewed?.let { retained ->
        val data = retained.view()
        AlertDialog(onDismissRequest = { if (!busy) reviewed = null }, title = { Text("仅用于这项原任务") }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(data.getJSONObject("task").optString("goal"), fontSize = 14.sp, lineHeight = 22.sp)
                val programs = jsSelectionRows(data.optJSONArray("programs"))
                if (programs.isEmpty()) Text("不挂载任何 JavaScript 扩展。")
                programs.forEach { source ->
                    Text("${source.optString("name")} · ${source.optString("version")}", fontSize = 14.sp)
                    SelectionContainer { Text("${source.optString("installationId")}\n源码 SHA-256：${source.optString("sourceSha256")}", fontSize = 11.sp, lineHeight = 18.sp) }
                }
                Text("保存后需从原任务明确继续，旧执行代次不能刷新这份选用。源码改版、撤权、停用后须重新选用；已有任务结果和回执不删除。", fontSize = 13.sp, lineHeight = 21.sp)
                Row { Checkbox(acknowledged, { acknowledged = it }, enabled = !busy); Text("我已核对本任务及准确版本；保存不会开始运行", fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f)) }
            }
        }, confirmButton = { Button(onClick = { work({ page.save(retained, retained.digest); page.snapshot() }) { show(it); reviewed = null; notice = "已保存本任务选用，没有运行扩展；请回原任务明确继续" } }, enabled = acknowledged && !busy) { Text("保存选用，不运行") } }, dismissButton = { TextButton(onClick = { reviewed = null }, enabled = !busy) { Text("取消") } })
    }
}
private fun jsSelectionRows(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
