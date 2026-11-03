package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.NativePluginHost
import dev.pocketengine.features.plugins.distribution.FunctionalPackHost
import org.json.JSONArray
import org.json.JSONObject

/** One reviewed local package, routed to the existing three component authorities and original journal. */
@Composable internal fun FunctionalPackControls(host: NativePluginHost, runWork: (Runnable) -> Unit,
    onOpenPlugins: () -> Unit, onOpenSkills: () -> Unit, onOpenTask: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var expanded by remember(host) { mutableStateOf(false) }
    var generation by remember(host) { mutableIntStateOf(0) }
    val created = remember(host, generation) { runCatching { host.newFunctionalPackPage() } }
    val page = created.getOrNull()
    var alive by remember(page) { mutableStateOf(true) }
    var snapshot by remember(page) { mutableStateOf(JSONObject()) }
    var review by remember(page) { mutableStateOf<FunctionalPackHost.Review?>(null) }
    var busy by remember(page) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    var result by remember(page) { mutableStateOf(JSONObject()) }
    var acknowledged by remember(review) { mutableStateOf(false) }
    var details by remember(review) { mutableStateOf(false) }
    var journal by remember(page) { mutableStateOf<JSONObject?>(null) }
    DisposableEffect(page, lifecycle) {
        fun foreground() = page?.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> foreground() }
        lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; page?.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, accept: (T) -> Unit) {
        if (busy || !alive || page == null) return
        busy = true; notice = null
        try { runWork(Runnable { val outcome = runCatching(action); main.post { if (alive) { busy = false; outcome.fold(accept) { notice = it.message ?: "请核对原任务操作；没有自动重新提交" } } } }) }
        catch (_: Exception) { busy = false; notice = "工作队列不可用，本次包操作未开始" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && page != null) work({
            check(uri.scheme == "content") { "请选择系统文件提供器中的准确功能包" }
            context.contentResolver.openInputStream(uri)?.use { page.inspect(it) } ?: throw IllegalStateException("无法读取所选功能包")
        }) { review = it; result = JSONObject() }
    }
    LaunchedEffect(page) { if (expanded && page != null) work({ page.snapshot() }) { snapshot = it } }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("完整功能包", fontSize = 17.sp, fontWeight = FontWeight.Medium)
    Text("一起核对 Skill、工具声明与纯计算 JS。配方由原任务解释使用，不是自动执行器；静态预检不等于服务、沙箱或业务结果已通过。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
    if (page == null) { Text(created.exceptionOrNull()?.message ?: "真实 SkillBook 尚未挂载，未使用替代安装器", color = Danger, fontSize = 13.sp); return }
    OutlinedButton(onClick = { expanded = !expanded; if (expanded) work({ page.snapshot() }) { snapshot = it } }, enabled = !busy) { Text(if (expanded) "收起功能包" else "导入、版本与原组件记录") }
    if (!expanded) return
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { page.cancel(); notice = "已请求停止后续组件；正在核对原回执，不撤回已发生的效果" }) { Text("停止后续组件", color = Danger) } }
    notice?.let { Text(it, color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp) }
    Text("选择的文件必须是 pocketworld.functional-pack.v1；不执行 npm、shell 或宿主桥。不支持的协议/依赖逐组件说明；可明确选择无受阻依赖的独立组件，其他组件保持受阻，不冒整包成功。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, enabled = !busy) { Text("选择准确功能包") }
        TextButton(onClick = { work({ page.snapshot() }) { snapshot = it } }, enabled = !busy) { Text("读取原登记") }
        TextButton(onClick = { generation++ }, enabled = !busy) { Text("新审阅") }
    }
    review?.let { retained ->
        val data = retained.view(); val pkg = data.optJSONObject("package"); val removing = data.optBoolean("removing")
        Text(if (removing) "卸载前核对原包归属" else if (data.optBoolean("restoring")) "历史原包 · 重新安装审阅" else "本次整包预检", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (pkg != null) {
            Text("${pkg.optString("name")} · ${pkg.optString("version")}", fontSize = 15.sp)
            Text("来源：${pkg.optJSONObject("source")?.optString("label")} · 许可：${pkg.optString("license")}", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            SelectionContainer { Text("准确原包 SHA-256：${pkg.optString("packageSha256")}", fontSize = 11.sp, lineHeight = 18.sp) }
        }
        data.optJSONArray("issues")?.let { issues -> (0 until issues.length()).forEach { Text(issues.optString(it), color = Danger, fontSize = 13.sp, lineHeight = 21.sp) } }
        Text("按组件分别记入原任务，不承诺跨模块原子安装。中途停止或失败会明确保留已确认部分；未知只查询原操作，不回滚已发生的效果，也不自动重提。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
        var chosen by remember(retained) { mutableStateOf(functionalRows(data.optJSONArray("components")).filter { it.optBoolean("selected", true) && it.optBoolean("selectable", true) }.map { it.getString("key") }.toSet()) }
        val selectionReviewed = removing || retained.matchesSelection(chosen)
        if (!removing) {
            Text("独立组件选择", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            functionalRows(data.optJSONArray("components")).forEach { component ->
                val key = component.getString("key")
                Row { Checkbox(key in chosen, { selected ->
                    if (!busy && alive && review === retained && component.optBoolean("selectable", true)) {
                        chosen = if (selected) chosen + key else chosen - key; acknowledged = false
                    }
                }, enabled = !busy && component.optBoolean("selectable", true)); Text("$key · ${if (component.optBoolean("selectable", true)) "可选择" else "受阻，未派发"}", fontSize = 13.sp) }
                component.optJSONObject("inspection")?.optString("reason")?.takeIf { it.isNotBlank() }?.let { Text(it, color = SecondaryInk, fontSize = 12.sp) }
            }
            TextButton(onClick = {
                val selected = chosen.toSet()
                work({ page.selectComponents(retained, JSONArray(selected.toList())) }) { review = it; acknowledged = false }
            }, enabled = !busy && chosen.isNotEmpty()) { Text("重新审阅仅所选独立组件及依赖") }
            if (!selectionReviewed) Text("组件选择已变更，请先重新审阅所选组件，再确认安装。", color = SecondaryInk, fontSize = 13.sp)
            data.optJSONArray("selectionProblems")?.let { problems -> (0 until problems.length()).forEach { Text(problems.optString(it), color = Danger, fontSize = 13.sp) } }
            if (data.optBoolean("selectionExplicit") && !data.optBoolean("wholePackageSelected")) Text("本次为部分组件安装：未选/受阻部分不派发，保留原组件/手工修改，不声明整包或配方可用。", color = SecondaryInk, fontSize = 13.sp)
        }
        functionalRows(data.optJSONArray("components")).forEach { component ->
            Text("${component.optString("key")} · ${functionalAction(component.optString("action"))}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(component.optString("kind") + " · " + component.optJSONObject("inspection")?.optString("message").orEmpty(), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        }
        TextButton(onClick = { details = !details }) { Text(if (details) "收起准确内容与差异" else "阅读所有组件内容、差异与依赖") }
        if (details) functionalRows(data.optJSONArray("components")).forEach { component ->
            val inspected = component.optJSONObject("inspection") ?: JSONObject()
            Text(component.optString("key"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (component.optString("kind") == "executable_js" && inspected.has("source")) SelectionContainer { Text(inspected.getString("source"), fontSize = 12.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace) }
            SelectionContainer { Text(component.toString(2), fontSize = 11.sp, lineHeight = 18.sp) }
        }
        Row { Checkbox(acknowledged, { checked ->
            acknowledged = checked && !busy && alive && review === retained && retained.ready && (removing || retained.matchesSelection(chosen))
        }, enabled = !busy && retained.ready && selectionReviewed); Text(if (removing) "我已核对归属与保留项；仅移除本次准确原组件" else "我已核对准确组件与所需能力；只导入内容，不批准或开始运行", modifier = Modifier.weight(1f), fontSize = 13.sp, lineHeight = 21.sp) }
        Button(onClick = {
            if (review !== retained || !acknowledged || !retained.ready || (!removing && !retained.matchesSelection(chosen))) return@Button
            val selected = chosen.toSet()
            work({
                check(removing || retained.matchesSelection(selected)) { "组件选择已变更，请重新审阅并确认" }
                val actual = page.submit(retained, retained.digest); actual to page.snapshot()
            }) { (actual, current) -> result = actual; snapshot = current; review = null; notice = actual.optString("message") }
        }, enabled = !busy && acknowledged && retained.ready && selectionReviewed) { Text(if (removing) "仅卸载原包确认组件" else if (data.optBoolean("selectionExplicit") && !data.optBoolean("wholePackageSelected")) "仅安装已审所选组件，整包仍部分完成" else "安装为停用组件，不运行") }
        TextButton(onClick = { review = null }, enabled = !busy) { Text("放弃这次审阅") }
    }
    result.optString("taskId").takeIf { it.isNotBlank() }?.let { task -> TextButton(onClick = { onOpenTask(task) }) { Text("打开这次原任务与回执") } }
    if (result.length() > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = onOpenPlugins) { Text("配置与批准工具") }; TextButton(onClick = onOpenSkills) { Text("读取并启用 Skill") } }
    functionalRows(snapshot.optJSONArray("packs")).forEach { pack ->
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text("${pack.optString("name")} · ${pack.optString("version")}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("原登记：${functionalState(pack.optString("state"))} · 组件权限与业务效果须分别核对", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        functionalRows(pack.optJSONArray("components")).forEach { component -> SelectionContainer { Text("${component.optString("key")} · ${component.optString("id")} · ${component.optString("installationId", "修订 " + component.optInt("revision"))}", fontSize = 11.sp, lineHeight = 18.sp) } }
        functionalRows(pack.optJSONArray("excludedComponents")).forEach { component -> Text("${component.optString("key")} · ${if (component.optString("action") == "blocked") "受阻" else "未选择"}，未派发；${component.optJSONObject("inspection")?.optString("reason").orEmpty()}", color = SecondaryInk, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { work({ page.reconcile(pack.getString("id")) to page.snapshot() }) { (actual, current) -> snapshot = current; notice = if (actual.optBoolean("reconciled")) "已只读核对原回执与登记，未重提" else "仍有原未决动作，只可从原任务核对" } }, enabled = !busy) { Text("核对原操作") }
            TextButton(onClick = { work({ page.journal(pack.getString("id")) }) { journal = it } }, enabled = !busy) { Text("原组件回执") }
            TextButton(onClick = { onOpenTask(pack.getString("taskId")) }, enabled = !busy) { Text("原任务") }
        }
        if (pack.optString("state") in setOf("installed", "partial")) TextButton(onClick = { work({ page.reviewRemove(pack.getString("id")) }) { review = it } }, enabled = !busy) { Text("审阅包卸载与保留项", color = Danger) }
        if (pack.optString("state") != "removed") functionalRows(pack.optJSONArray("history")).forEach { version -> TextButton(onClick = { work({ page.restore(pack.getString("id"), version.getString("installationId")) }) { review = it } }, enabled = !busy) { Text("重新审阅历史原包 · ${version.optString("version")}") } }
    }
    journal?.let { task -> AlertDialog(onDismissRequest = { journal = null }, title = { Text("原 TaskStore 组件回执") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(task.optString("goal"), fontSize = 14.sp)
            functionalRows(task.optJSONArray("operations")).forEach { operation -> val args = operation.optJSONObject("arguments"); Text("${args?.optString("componentKey")} · ${operation.optString("state")}", fontSize = 13.sp, fontWeight = FontWeight.Medium); SelectionContainer { Text(operation.optJSONObject("receipt")?.toString(2) ?: "原动作已登记但无确定回执；未自动重提", fontSize = 11.sp, lineHeight = 18.sp) } }
        }
    }, confirmButton = { TextButton(onClick = { journal = null }) { Text("返回包记录") } }) }
}
private fun functionalRows(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
private fun functionalAction(action: String) = when (action) { "install" -> "安装为新停用版本"; "remove" -> "移除原确认安装"; "retain_changed" -> "保留已被手工改版的安装"; "already_absent" -> "原安装已不在，不重复卸载"; else -> action }
private fun functionalState(state: String) = when (state) { "installed" -> "原组件安装已返回"; "partial" -> "已停止，部分保留"; "unknown" -> "结果待核对，不重放"; "installing", "removing" -> "原登记待核对"; "removed" -> "已处理包卸载，原回执保留"; else -> state }
