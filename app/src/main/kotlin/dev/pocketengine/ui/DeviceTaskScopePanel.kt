package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pocketengine.features.devices.NativeDeviceTaskScopeHost
import dev.pocketengine.features.devices.DeviceProtocol
import dev.pocketengine.store.TaskStoreDeviceTasks
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** An entry on the existing owner task. It never creates/resumes a run or turns a paired-device
 * task into an owner task. The native host rechecks the original task owner before reading options. */
@Composable
internal fun DeviceTaskScopeEntry(task: UiTask, host: NativeDeviceTaskScopeHost?, runOnWorker: (Runnable) -> Unit, changed: () -> Unit) {
    if (task.raw.optJSONObject("origin")?.optString("kind") == "paired_device") return
    val alive = remember(task.id, host) { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var opening by remember(task.id, host) { mutableStateOf(false) }
    var session by remember(task.id, host) { mutableStateOf<DeviceScopeSession?>(null) }
    var error by remember(task.id, host) { mutableStateOf<String?>(null) }
    DisposableEffect(alive) { onDispose { alive.set(false); session?.window?.close() } }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = {
            if (host != null && !opening) {
                opening = true; error = null
                try { runOnWorker(Runnable {
                    var opened: NativeDeviceTaskScopeHost.Window? = null
                    try {
                        opened = host.open(task.id); val next = DeviceScopeSession(opened, opened.view())
                        main.post { if (alive.get()) { session = next; opening = false } else next.window.close() }
                    } catch (failure: Exception) {
                        opened?.close(); main.post { if (alive.get()) { opening = false; error = scopeFailure(failure) } }
                    }
                }) } catch (_: Exception) { opening = false; error = "设备范围暂时无法读取，请重新打开任务。" }
            }
        }, enabled = host != null && !opening, modifier = Modifier.heightIn(min = 48.dp).testTag("device_task_scope_open"), contentPadding = PaddingValues(0.dp)) {
            AffectGlyph("globe", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (opening) "正在读取设备范围…" else "设备协作范围")
        }
        if (host == null) Text("设备连接尚未初始化。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
    }
    session?.let { current -> DeviceTaskScopeDialog(current, runOnWorker, changed) { current.window.close(); session = null } }
}

private data class DeviceScopeSession(val window: NativeDeviceTaskScopeHost.Window, val view: JSONObject)
private data class DeviceScopeDraft(val tools: String, val models: String, val deadline: Long,
    val shares: Map<String, DeviceScopeShareDraft> = emptyMap(), val advanced: Boolean = false)
private data class DeviceScopeShareDraft(val tools: String = "", val models: String = "")

@Composable
private fun DeviceTaskScopeDialog(session: DeviceScopeSession, runOnWorker: (Runnable) -> Unit, changed: () -> Unit, close: () -> Unit) {
    val alive = remember(session) { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var review by remember(session) { mutableStateOf<TaskStoreDeviceTasks.SourceScopeReview?>(null) }
    var view by remember(session) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    DisposableEffect(session) { onDispose { alive.set(false); session.window.close() } }
    BackHandler(onBack = close)
    fun execute(action: () -> Unit) {
        if (busy || !alive.get()) return
        busy = true; error = null
        try { runOnWorker(Runnable {
            try { action() } catch (failure: Exception) { main.post { if (alive.get()) error = scopeFailure(failure) } }
            finally { main.post { if (alive.get()) busy = false } }
        }) } catch (_: Exception) { busy = false; error = "本机工作线程暂不可用，请重新打开任务。" }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = PageSurface) {
            Column(Modifier.fillMaxSize().testTag("device_task_scope")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (review == null) "设备协作范围" else "确认这次设备范围", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp).testTag("device_task_scope_close")) { Text("关闭") }
                }
                HorizontalDivider(color = Line, thickness = .5.dp)
                if (error != null) Text(error!!, Modifier.padding(horizontal = 20.dp, vertical = 12.dp).testTag("device_task_scope_error"), style = MaterialTheme.typography.bodyMedium)
                if (review == null) DeviceScopeSelection(session.view, busy, Modifier.weight(1f)) { selection ->
                    execute {
                        val native = session.window.review(selection); val safe = native.view()
                        main.post { if (alive.get()) { review = native; view = safe } }
                    }
                } else DeviceScopeReview(view ?: JSONObject(), session.view, busy, Modifier.weight(1f), close) {
                    val original = review ?: return@DeviceScopeReview
                    execute {
                        session.window.confirm(original)
                        main.post { if (alive.get()) { changed(); close() } }
                    }
                }
            }
        }
    }
}

/** Only OptionIds and finite numeric choices cross back to the native host. This renderer never
 * parses resources, grants, model JSON or target strings into a permission/review. */
@Composable
internal fun DeviceScopeSelection(nativeView: JSONObject, busy: Boolean, modifier: Modifier = Modifier,
    review: (NativeDeviceTaskScopeHost.Selection) -> Unit) {
    val options = nativeView.optJSONArray("options").objects()
    val selectedBefore = nativeView.optJSONObject("selected")?.optJSONArray("routes").objects()
    val toolsAvailable = nativeView.optInt("toolsAvailable").coerceAtLeast(0)
    val modelsAvailable = nativeView.optInt("modelsAvailable").coerceAtLeast(0)
    val overhead = nativeView.optInt("sourceOperationOverheadAtLeast", 1).coerceAtLeast(1)
    var drafts by remember(nativeView) { mutableStateOf<Map<String, DeviceScopeDraft>>(mutableMapOf<String, DeviceScopeDraft>().apply {
        options.forEach { option -> selectedBefore.firstOrNull { it.optString("sourceKey") == option.optString("sourceKey") }?.let { existing ->
            val selectedShares = existing.optJSONArray("downstreamShares").objects()
            val shares = option.optJSONArray("downstreamOptions").objects().mapNotNull { child ->
                selectedShares.firstOrNull { it.optString("targetDeviceId") == child.optString("targetDeviceId") && DeviceProtocol.canonical(it.optJSONObject("resourceBinding")) == DeviceProtocol.canonical(child.optJSONObject("resourceBinding")) }
                    ?.let { child.optString("optionId") to DeviceScopeShareDraft(it.optInt("toolBudget").toString(), it.optInt("modelBudget").toString()) }
            }.toMap()
            put(option.optString("optionId"), DeviceScopeDraft(existing.optInt("toolBudget").toString(), existing.optInt("modelBudget").toString(), existing.optLong("deadline"), shares))
        } }
    }) }
    fun setDraft(id: String, value: DeviceScopeDraft) { drafts = drafts + (id to value) }
    fun newDraft(option: JSONObject): DeviceScopeDraft {
        val now = System.currentTimeMillis(); val goal = option.optString("kind") == "goal"
        return DeviceScopeDraft(minOf(option.optInt("toolBudgetMaximum"), ((toolsAvailable - overhead).coerceAtLeast(0) / 2).coerceAtLeast(1)).toString(),
            (if (goal) minOf(option.optInt("modelBudgetMaximum"), (modelsAvailable / 2).coerceAtLeast(1)) else 0).toString(),
            minOf(option.optLong("deadlineMaximum"), nativeView.optLong("deadlineMaximum"), now + 86_400_000))
    }
    val selectedOptions = options.filter { drafts.containsKey(it.optString("optionId")) }
    val totalTools = drafts.values.sumOf { it.tools.toIntOrNull()?.toLong() ?: 0 }
    val totalModels = drafts.values.sumOf { it.models.toIntOrNull()?.toLong() ?: 0 }
    val inputError = when {
        selectedOptions.size > 16 -> "一次最多选择 16 个设备范围。"
        selectedOptions.any { option -> !scopeDraftValid(option, drafts.getValue(option.optString("optionId")), nativeView.optLong("deadlineMaximum")) } -> "请核对选中设备的次数、期限与下游份额。"
        else -> null
    }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(nativeView.optString("goal").ifBlank { "这次任务" }, style = MaterialTheme.typography.titleMedium, maxLines = 3)
            DeviceContentSurface { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本次可分配", style = MaterialTheme.typography.titleSmall)
                Text("工具 $toolsAvailable 次 · 模型 $modelsAvailable 次", style = MaterialTheme.typography.bodyLarge)
                Text("调用设备本身也会消耗工具次数。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            } }
            val missing = selectedBefore.count { before -> options.none { it.optString("sourceKey") == before.optString("sourceKey") } }
            if (missing > 0) Text("有 $missing 个已确认范围暂不在候选中。修改范围不会返还未核对操作占用的次数。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            if (nativeView.optBoolean("hasMoreOptions")) Text("当前只显示一部分设备范围。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            DeviceSectionTitle("选择设备与工作范围", "只列出已验证连接和授权的选项")
            if (options.isEmpty()) DeviceContentSurface { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("还没有可选的设备范围", style = MaterialTheme.typography.titleSmall)
                Text("先连接设备并确认它的授权，再重新打开这里。", style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
            } }
            options.forEach { option ->
                val id = option.optString("optionId"); val draft = drafts[id]
                DeviceContentSurface {
                    Row(Modifier.fillMaxWidth().testTag("device_scope_option_$id").clickable(enabled = !busy, role = Role.Checkbox) {
                        if (draft == null) {
                            val conflicts = options.filter { it.optString("sourceKey") == option.optString("sourceKey") }.map { it.optString("optionId") }.toSet()
                            drafts = drafts.filterKeys { it !in conflicts } + (id to newDraft(option))
                        } else drafts = drafts - id
                    }.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(checked = draft != null, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(scopeTargetLabel(option.optString("targetDeviceId")), style = MaterialTheme.typography.titleMedium)
                            Text(if (option.optString("kind") == "goal") "处理目标 · 模型由设备机主决定" else "调用工具 · 不使用目标设备模型", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                            scopeCapabilityStatus(option.optJSONObject("diagnostics"))?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
                            Text(deviceResourceText(option.optJSONObject("resourceBinding") ?: JSONObject()), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (draft != null) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("工具 ${draft.tools.ifBlank { "—" }} 次 · 模型 ${draft.models.ifBlank { "—" }} 次", style = MaterialTheme.typography.titleSmall)
                        Text("截至 ${deviceTime(draft.deadline)}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { setDraft(id, newDraft(option).copy(shares = draft.shares, advanced = draft.advanced)) }, enabled = !busy) { Text("均衡分配") }
                            OutlinedButton(onClick = { setDraft(id, draft.copy(tools = minOf(option.optInt("toolBudgetMaximum"), (toolsAvailable - overhead).coerceAtLeast(0)).toString(), models = (if (option.optString("kind") == "goal") minOf(option.optInt("modelBudgetMaximum"), modelsAvailable) else 0).toString())) }, enabled = !busy) { Text("使用本次余量") }
                        }
                        TextButton(onClick = { setDraft(id, draft.copy(advanced = !draft.advanced)) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text(if (draft.advanced) "收起次数与期限" else "调整次数与期限") }
                        if (draft.advanced) {
                            ScopeNumber("工具次数（对端授权上限 ${option.optInt("toolBudgetMaximum")}）", draft.tools, !busy, "device_scope_tools_$id") { setDraft(id, draft.copy(tools = it)) }
                            if (option.optString("kind") == "goal") ScopeNumber("模型次数（对端授权上限 ${option.optInt("modelBudgetMaximum")}）", draft.models, !busy, "device_scope_models_$id") { setDraft(id, draft.copy(models = it)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("15 分钟" to 900_000L, "1 天" to 86_400_000L, "7 天" to 604_800_000L).forEach { (label, duration) ->
                                    OutlinedButton(onClick = { setDraft(id, draft.copy(deadline = minOf(System.currentTimeMillis() + duration, option.optLong("deadlineMaximum"), nativeView.optLong("deadlineMaximum")))) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
                                }
                            }
                        }
                        ScopeExactResource(option.optJSONObject("resourceBinding") ?: JSONObject(), option.optJSONArray("toolDeclarations").objects())
                        if (option.optJSONObject("diagnostics")?.optBoolean("isolation", true) == false) Text("执行代码可能访问该系统用户可访问的资源。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                        val children = option.optJSONArray("downstreamOptions").objects()
                        if (children.isNotEmpty() && option.optString("kind") == "goal") {
                            HorizontalDivider(color = Line, thickness = .5.dp)
                            Text("允许继续交给其他设备", style = MaterialTheme.typography.titleSmall)
                            Text("从本次分配中划出份额，不重复计算。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                            children.forEach { child ->
                                val childId = child.optString("optionId"); val share = draft.shares[childId]
                                Row(Modifier.fillMaxWidth().testTag("device_scope_share_$childId").clickable(enabled = !busy, role = Role.Checkbox) { setDraft(id, draft.copy(shares = draft.shares.toMutableMap().apply { if (share == null) put(childId, DeviceScopeShareDraft()) else remove(childId) })) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = share != null, onCheckedChange = null)
                                    Column(Modifier.weight(1f)) { Text(scopeTargetLabel(child.optString("targetDeviceId")), style = MaterialTheme.typography.bodyMedium); Text(deviceResourceText(child.optJSONObject("resourceBinding") ?: JSONObject()), style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
                                }
                                if (share != null) {
                                    ScopeNumber("分给这台设备的工具次数", share.tools, !busy, "device_scope_share_tools_$childId") { value -> setDraft(id, draft.copy(shares = draft.shares + (childId to share.copy(tools = value)))) }
                                    ScopeNumber("分给这台设备的模型次数", share.models, !busy, "device_scope_share_models_$childId") { value -> setDraft(id, draft.copy(shares = draft.shares + (childId to share.copy(models = value)))) }
                                }
                            }
                            Text("${scopeTargetLabel(option.optString("targetDeviceId"))} 自身保留：工具 ${(draft.tools.toIntOrNull() ?: 0) - draft.shares.values.sumOf { it.tools.toIntOrNull() ?: 0 }} · 模型 ${(draft.models.toIntOrNull() ?: 0) - draft.shares.values.sumOf { it.models.toIntOrNull() ?: 0 }}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                        }
                    }
                }
            }
            ScopeContextSummary()
        }
        Surface(color = Color.White, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已选 ${selectedOptions.size} 个范围 · 工具 $totalTools · 模型 $totalModels", style = MaterialTheme.typography.bodyMedium)
                inputError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
                Button(onClick = {
                    review(NativeDeviceTaskScopeHost.Selection(selectedOptions.map { option ->
                        val draft = drafts.getValue(option.optString("optionId")); val tools = draft.tools.toInt(); val models = draft.models.toInt()
                        NativeDeviceTaskScopeHost.RouteChoice(option.optString("optionId"), tools, models, draft.deadline,
                            tools - draft.shares.values.sumOf { it.tools.toInt() }, models - draft.shares.values.sumOf { it.models.toInt() },
                            draft.shares.map { (id, share) -> NativeDeviceTaskScopeHost.ShareChoice(id, share.tools.toInt(), share.models.toInt()) }, NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY)
                    }))
                }, enabled = !busy && inputError == null && (selectedOptions.isNotEmpty() || selectedBefore.isNotEmpty()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_scope_review")) { Text(if (busy) "正在核对…" else if (selectedOptions.isEmpty()) "移除设备范围并核对" else "核对这次范围") }
            }
        }
    }
}

@Composable
internal fun DeviceScopeReview(view: JSONObject, source: JSONObject, busy: Boolean, modifier: Modifier, close: () -> Unit, confirm: () -> Unit) {
    var consent by remember(view) { mutableStateOf(false) }
    val routes = view.optJSONArray("routes").objects()
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(view.optString("goal").ifBlank { "这次任务" }, style = MaterialTheme.typography.titleMedium)
            if (routes.isEmpty()) Text("不再为这个任务添加设备范围。已经发出的操作仍需核对结果。", style = MaterialTheme.typography.bodyMedium)
            routes.forEach { route -> DeviceContentSurface { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(scopeTargetLabel(route.optString("targetDeviceId")), style = MaterialTheme.typography.titleMedium)
                Text(if (route.optString("kind") == "goal") "目标委派 · 目标设备使用机主批准的模型" else "工具调用 · 目标设备不调用模型", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                val declarations = route.optJSONObject("toolDeclarations")?.let { data -> data.keys().asSequence().map { JSONObject().put("toolName", it).put("effect", data.optString(it)) }.toList() }.orEmpty()
                ScopeExactResource(route.optJSONObject("resourceBinding") ?: JSONObject(), declarations)
                Text("本次总额：工具 ${route.optInt("toolBudget")} · 模型 ${route.optInt("modelBudget")}", style = MaterialTheme.typography.bodyLarge)
                Text("该设备自身份额：工具 ${route.optInt("localToolShare")} · 模型 ${route.optInt("localModelShare")}", style = MaterialTheme.typography.bodyMedium)
                route.optJSONArray("downstreamShares").objects().forEach { share ->
                    HorizontalDivider(color = Line, thickness = .5.dp)
                    Text("→ ${scopeTargetLabel(share.optString("targetDeviceId"))}：工具 ${share.optInt("toolBudget")} · 模型 ${share.optInt("modelBudget")}", style = MaterialTheme.typography.bodyMedium)
                    ScopeExactResource(share.optJSONObject("resourceBinding") ?: JSONObject(), emptyList())
                }
                Text("执行截至 ${deviceTime(route.optLong("deadline"))}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                val option = source.optJSONArray("options").objects().firstOrNull { it.optString("optionId") == route.optString("optionId") }
                if (option?.optJSONObject("diagnostics")?.optBoolean("isolation", true) == false) Text("执行代码可能访问该系统用户可访问的资源。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            } } }
            ScopeContextSummary()
            Text("同一范围内不再重复询问；新增设备、权限或次数时，需要再次确认。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) { consent = !consent }.testTag("device_scope_consent").padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = null); Text("允许这个任务按以上范围使用设备", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        Surface(color = Color.White, shadowElevation = 8.dp) { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text("暂不修改") }
            Button(onClick = confirm, enabled = consent && !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("device_scope_confirm")) { Text(if (busy) "正在确认…" else "确认任务范围") }
        } }
    }
}

@Composable
private fun ScopeContextSummary() { DeviceContentSurface { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("共享内容", style = MaterialTheme.typography.titleSmall)
    Text("仅这个任务的目标。未共享附件、私人记忆或整个工作区。", style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
} } }

@Composable
private fun ScopeExactResource(resource: JSONObject, tools: List<JSONObject>) {
    var expanded by remember(resource.toString()) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起范围详情" else "范围详情" ) }
    if (expanded) SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("projectId" to "项目", "checkoutId" to "项目副本", "runtimeId" to "运行环境", "resourceId" to "资源", "rootId" to "工作目录").forEach { (key, label) -> if (resource.optString(key).isNotBlank()) DeviceFact(label, resource.getString(key)) }
        resource.optJSONArray("packageNames")?.let { apps -> Text("指定应用：${(0 until apps.length()).joinToString("、") { apps.optString(it) }}", style = MaterialTheme.typography.bodySmall) }
        if (resource.has("allowWholeDisplayInput")) Text(if (resource.optBoolean("allowWholeDisplayInput")) "允许整屏输入" else "仅指定应用的节点操作", style = MaterialTheme.typography.bodySmall)
        if (resource.has("allowWholeDisplayCapture")) Text(if (resource.optBoolean("allowWholeDisplayCapture")) "允许整屏截图" else "不允许整屏截图", style = MaterialTheme.typography.bodySmall)
        tools.forEach { tool -> Text("${tool.optString("toolName")} · ${when (tool.optString("effect")) { "read" -> "只读"; "write" -> "改动"; "external" -> "外部操作"; else -> "未标明" }}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
    } }
}
@Composable
private fun ScopeNumber(label: String, value: String, enabled: Boolean, tag: String, change: (String) -> Unit) { OutlinedTextField(value, { if (it.length <= 5 && it.all(Char::isDigit)) change(it) }, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(12.dp)) }
private fun scopeTargetLabel(id: String) = "设备 ${id.takeLast(4)}"
private fun scopeDraftValid(option: JSONObject, draft: DeviceScopeDraft, taskDeadline: Long): Boolean {
    val tools = draft.tools.toIntOrNull() ?: return false; val models = draft.models.toIntOrNull() ?: return false
    if (tools < (if (option.optString("kind") == "goal") 0 else 1) || tools > option.optInt("toolBudgetMaximum") || models < (if (option.optString("kind") == "goal") 1 else 0) || models > option.optInt("modelBudgetMaximum") || draft.deadline <= System.currentTimeMillis() || draft.deadline > minOf(taskDeadline, option.optLong("deadlineMaximum"))) return false
    var childTools = 0L; var childModels = 0L
    draft.shares.values.forEach { val t = it.tools.toIntOrNull() ?: return false; val m = it.models.toIntOrNull() ?: return false; if (t < 0 || m < 0 || t.toLong() + m < 1) return false; childTools += t; childModels += m }
    return childTools <= tools && childModels <= models && (draft.shares.isEmpty() || models - childModels >= 1 && tools - childTools >= 1)
}
private fun scopeFailure(error: Exception): String = when {
    error is java.io.IOException -> "连接或回执暂不可用。没有重新派发任务，请稍后核对。"
    error.message?.contains("真实剩余") == true -> "分配超过任务剩余次数。未确认的操作仍占用次数，请调整后核对。"
    error.message?.contains("源范围只属于") == true -> "只能在自己的进行中任务里设置设备范围。"
    else -> error.message?.take(300) ?: "任务范围暂时无法确认，请重新打开后核对。"
}

private fun scopeCapabilityStatus(diagnostics: JSONObject?): String? {
    if (diagnostics == null) return null
    return when {
        diagnostics.optBoolean("ready") -> "读取时执行条件已就绪"
        diagnostics.optBoolean("canSubmitForLocalReview") -> "需要这台设备的机主确认模型"
        else -> when (diagnostics.optString("reason")) {
            "receiver_disabled" -> "这台设备尚未开启任务接收"
            "native_executor_busy" -> "这台设备正在执行另一项任务"
            "local_owner_model_review_required", "local_owner_model_policy_unconfigured" -> "需要这台设备的机主选择模型"
            "selected_policy_api_model_unavailable" -> "这台设备选择的模型暂不可用"
            "permission_or_resource_unavailable" -> "设备权限或指定资源暂未就绪"
            else -> if (diagnostics.has("ready")) "执行条件仍需核对" else null
        }
    }
}
