package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Explicit native private transfer, separate from chat selection and the ordinary text submit. */
@Composable
internal fun RemoteInputDialog(request: JSONObject?, originalTask: String, controller: PocketWorldController, close: () -> Unit) {
    var window by remember(originalTask, request?.toString()) { mutableStateOf<String?>(null) }
    DisposableEffect(originalTask, request?.toString()) {
        val opened = controller.enterRemoteInputs(request, originalTask); window = opened
        onDispose { controller.leaveRemoteTask(opened) }
    }
    val state = controller.state
    val snapshot = state.ownerSnapshot("remoteTask").takeIf { it.optString("windowId") == window } ?: JSONObject()
    val task = snapshot.optJSONObject("task")
    val scope = snapshot.optJSONObject("scope")
    val selected = snapshot.optJSONArray("files").objects()
    val available = snapshot.optJSONArray("availableFiles").objects()
    val registered = snapshot.optBoolean("registered")
    val pending = state.ownerPending.any { it.startsWith("remoteTask.") }
    val busy = snapshot.optBoolean("busy") || pending
    var selectedIds by remember(window) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(snapshot.optJSONArray("files")?.toString()) { selectedIds = selected.map { it.optString("attachmentId") }.toSet() }
    var error by remember(window) { mutableStateOf<String?>(null) }
    var confirm by remember(window) { mutableStateOf(false) }
    var billing by remember(window) { mutableStateOf(false) }
    var details by remember(window) { mutableStateOf(false) }
    val review = snapshot.optJSONObject("review")
    LaunchedEffect(review?.optString("digest"), review?.optLong("expiresAt")) { confirm = review != null; billing = false }
    fun send(command: String, payload: JSONObject = JSONObject()) {
        try { controller.requestRemoteTask(command, payload); error = null } catch (failure: Exception) { error = failure.message }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val latestBusy by rememberUpdatedState(snapshot.optBoolean("busy") || state.ownerPending.any { it == "remoteTask.confirmInputs" })
    val inspecting by rememberUpdatedState(state.ownerPending.any { it == "remoteTask.inspectInputs" })
    LaunchedEffect(window, foreground) {
        if (window == null || !foreground) return@LaunchedEffect
        delay(300); if (!inspecting) send("inspectInputs")
        while (true) { delay(1000); if (latestBusy && !inspecting) send("inspectInputs") }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("把选定原件交给这项远端任务", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = close) { Text("返回") }
                }
                Text("这里的选择独立于聊天附件。确认后只发送列出的实际原件；不会自动带上其他文件、账号凭据或会话 cookies。", fontSize = 14.sp, lineHeight = 22.sp)
                val target = snapshot.optJSONObject("request") ?: request ?: JSONObject()
                Text("目标：${target.optString("profileId")} · ${target.optString("environmentId")} · ${remoteWorkerLabel(target.optString("worker"))}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("工作区：" + (scope?.optString("workspace") ?: snapshot.optJSONObject("environment")?.optString("workspace").orEmpty()), fontSize = 14.sp, lineHeight = 22.sp)
                if (!registered) {
                    Text("选择私人原件（最多8个，每个256KiB，总计512KiB）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (available.isEmpty()) Text("暂无可选私人原件；先在原附件入口导入，再重新打开。群技术副本不能作为私人附件。", fontSize = 14.sp, lineHeight = 22.sp, color = SecondaryInk)
                    available.forEach { file ->
                        val id = file.optString("id")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = id in selectedIds, enabled = !busy && (id in selectedIds || selectedIds.size < 8), onCheckedChange = { choose ->
                                val next = if (choose) selectedIds + id else selectedIds - id
                                selectedIds = next
                                val refs = JSONArray(); available.filter { it.optString("id") in next }.forEach { refs.put(JSONObject().put("id", it.optString("id")).put("sha256", it.optString("sha256"))) }
                                send("selectInputs", JSONObject().put("files", refs))
                            })
                            Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                                Text(file.optString("name"), fontSize = 14.sp)
                                Text("${file.optLong("size")} 字节 · ${file.optString("mimeType")}", fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk)
                            }
                        }
                    }
                } else Text("原任务已登记，目标与附件范围不会改绑；未知只查询原编号，不自动第二次发送 body 或创建 job。", fontSize = 14.sp, lineHeight = 22.sp)
                if (selected.isNotEmpty()) {
                    Text((if (registered) "已登记原件范围：" else "已核原件：") + "${selected.size} 个 · ${selected.sumOf { it.optLong("bytes") }} 字节", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    selected.forEach { file -> Text("${file.optString("name")} · ${file.optLong("bytes")} 字节 · ${file.optString("mimeType")}", fontSize = 14.sp, lineHeight = 22.sp) }
                    Text("类型是原件声明；上传成功不代表 CLI 能理解任意格式。", fontSize = 13.sp, color = SecondaryInk)
                    Button(onClick = { send("reviewInputs") }, enabled = !busy) { Text(if (registered) "重新核对原范围" else "核对目标与外传范围") }
                }
                Text(snapshot.optString("message", "正在读取原生范围"), fontSize = 14.sp, lineHeight = 22.sp)
                if (snapshot.optInt("fileIndex", -1) >= 0) Text("第 ${snapshot.optInt("fileIndex") + 1} 个文件已写入传输 ${snapshot.optLong("writtenBytes")} 字节；远端 hash 以回执为准。", fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk)
                snapshot.optJSONObject("inputState")?.let { actual ->
                    Text("远端阶段：${actual.optString("phase")} · 已核 hash ${actual.optLong("verifiedBytes")} / ${actual.optLong("bytes")} 字节", fontSize = 14.sp)
                    Text(if (actual.optBoolean("workerStarted")) "原 worker 报告已启动，后续审批和结果仍在原任务。" else "尚无原 worker 已启动的回执；ready 前不会启动。", fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk)
                }
                if (registered) {
                    OutlinedButton(onClick = { send("queryInputs") }, enabled = !busy) { Text("查询原 job（不重发）") }
                    TextButton(onClick = { send("cancelInputs") }, enabled = state.ownerPending.none { it == "remoteTask.cancelInputs" }) { Text("请求停止本次原任务") }
                    TextButton(onClick = { controller.focusTask(snapshot.optString("taskId")); close() }) { Text("打开原任务与成果") }
                }
                Text("输入副本可能保留，取消不等于远端删除。离页/切后台会撤销本次尚未派发的确认，已发字节不能撤回。", fontSize = 14.sp, lineHeight = 22.sp, color = SecondaryInk)
                (error ?: state.ownerErrors["remoteTask"])?.let { Text(it, fontSize = 14.sp, color = Danger) }
            }
        }
    }
    if (confirm && review != null && scope != null) {
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text("仅向这个准确目标发送这些原件") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer { Text("${scope.optString("profileId")}\n${scope.optString("serviceBase")}\n${scope.optString("workspace")}/${scope.optString("inputDirectory")}", fontSize = 14.sp, lineHeight = 22.sp) }
                scope.optJSONArray("files").objects().forEach { file -> SelectionContainer { Text("${file.optString("name")} · ${file.optLong("bytes")} 字节\n${file.optString("mimeType")}\nSHA-256 ${file.optString("sha256")}", fontSize = 13.sp, lineHeight = 20.sp) } }
                Text("这是所选输入目录，不是 OS 读写/网络沙箱。不会自动批准工具命令，也不保证删除已上传副本。", fontSize = 14.sp, lineHeight = 22.sp)
                Text("原任务时限：${scope.optJSONObject("request")?.optInt("timeoutSeconds", 300)} 秒。内部模型调用次数、token 与费用不能在此硬限制。", fontSize = 14.sp, lineHeight = 22.sp)
                Row { Checkbox(billing, { billing = it }); Text("我已了解上述外传范围与远端 CLI 计费边界，确认仅执行这次明确任务。", fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp).weight(1f)) }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起目标全文" else "查看本次目标全文") }
                if (details) SelectionContainer { Text(scope.optJSONObject("request")?.optString("prompt").orEmpty(), fontSize = 14.sp, lineHeight = 22.sp) }
            }
        }, confirmButton = { Button(onClick = {
            send("confirmInputs", JSONObject().put("digest", review.optString("digest")).put("billingAcknowledged", billing)); confirm = false
        }, enabled = billing && !busy) { Text("确认发送本次原件") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回核对") } })
    }
}
