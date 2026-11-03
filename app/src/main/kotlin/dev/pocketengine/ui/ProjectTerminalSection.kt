package dev.pocketengine.ui

import android.content.ContextWrapper
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.preview.ProjectTerminalWebView
import dev.pocketengine.features.local.project.ProjectOwnerTerminal
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/** Original PTY screen. Hiding/backgrounding closes only this view and its read
 * observation; the sole original task remains responsible for stop/drain. */
@Composable
internal fun NativeProjectTerminal(task: UiTask) {
    val operations = task.raw.optJSONArray("operations").objects()
    val execution = operations.lastOrNull { row ->
        val actual = row.optJSONObject("receipt")?.optJSONObject("executionReceipt")
        row.optString("tool") == "project_execute" && row.optString("runId") == task.raw.optString("runId") && actual?.optString("terminalMode") == "pty"
    }?.optString("operationId") ?: return
    NativeProjectExecutionView(task, execution, false)
}

/** The same original log renderer, without any input/stop capability token. */
@Composable
internal fun NativeProjectExecutionObservation(task: UiTask, execution: String) {
    NativeProjectExecutionView(task, execution, true)
}

@Composable
private fun NativeProjectExecutionView(task: UiTask, execution: String, observingOnly: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as EngineApplication
    val lifecycle = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeUnless { next -> next === it } }.filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycle }
    var opened by remember(task.id, execution) { mutableStateOf(false) }
    var session by remember(task.id, execution) { mutableStateOf<ProjectOwnerTerminal.Session?>(null) }
    var metadata by remember(task.id, execution) { mutableStateOf<JSONObject?>(null) }
    var logPolicy by remember(task.id, execution) { mutableStateOf<JSONObject?>(null) }
    var activity by remember(task.id, execution) { mutableStateOf<JSONObject?>(null) }
    var web by remember(task.id, execution) { mutableStateOf<ProjectTerminalWebView?>(null) }
    var ready by remember(task.id, execution) { mutableStateOf(false) }
    var takingInput by remember(task.id, execution) { mutableStateOf(false) }
    var active by remember(lifecycle) { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false) }
    var cursor by remember(task.id, execution) { mutableLongStateOf(0) }
    var nativeStatus by remember(task.id, execution) { mutableStateOf("unknown") }
    var notice by remember(task.id, execution) { mutableStateOf<String?>(null) }
    var columns by remember(task.id, execution) { mutableStateOf("80") }
    var rows by remember(task.id, execution) { mutableStateOf("24") }
    var stopped by remember(task.id, execution) { mutableStateOf(false) }
    var incomplete by remember(task.id, execution) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val actions = remember(task.id, execution, opened) { Channel<JSONObject>(16) }
    fun closeView() { takingInput = false; opened = false; ready = false; web?.release(); web = null; session?.close(); session = null; metadata = null; logPolicy = null; activity = null; cursor = 0; actions.close() }
    fun enqueue(action: JSONObject) {
        if (observingOnly || !takingInput || !active || nativeStatus != "running" || stopped || incomplete) return
        if (!actions.trySend(action).isSuccess) { takingInput = false; notice = "输入队列已满，这次未发送；请核对终端后再明确操作。" }
    }
    DisposableEffect(actions) { onDispose { actions.close() } }
    DisposableEffect(task.id, execution, lifecycle) {
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { active = false; closeView() }
            if (event == Lifecycle.Event.ON_START) active = true
        }
        lifecycle?.addObserver(listener)
        onDispose { lifecycle?.removeObserver(listener); web?.release(); session?.close(); actions.close() }
    }
    LaunchedEffect(opened, task.id, execution) {
        if (!opened) return@LaunchedEffect
        val observation = RunControl()
        try {
            val original = withContext(Dispatchers.IO) { if (observingOnly) app.features.openProjectExecutionForOwner(task.id, execution, observation) else app.features.openProjectTerminalForOwner(task.id, execution, observation) }
            if (!opened || !active) { original.close(); return@LaunchedEffect }
            session = original
            metadata = withContext(Dispatchers.IO) { original.metadata() }
            columns = metadata!!.getInt("columns").toString(); rows = metadata!!.getInt("rows").toString(); notice = null; stopped = false; incomplete = false
        } catch (cancelled: CancellationException) { observation.cancel(); throw cancelled }
        catch (failed: Exception) { observation.cancel(); session?.close(); session = null; metadata = null; notice = failed.message?.take(300) ?: "原终端不可读；没有新建或重放。"; opened = false }
    }
    LaunchedEffect(session, opened, active) {
        val original = session ?: return@LaunchedEffect
        for (action in actions) {
            if (!opened || !active || !takingInput) continue
            try {
                val result = withContext(Dispatchers.IO) { original.input(action) }
                if (!result.optBoolean("nativeInputSettled")) { takingInput = false; notice = "本次输入结果待核，没有自动重发。请到原任务查看。" }
                else if (!result.optBoolean("ok")) { takingInput = false; notice = "本次输入未派发，请核对原终端状态。" }
                else if (action.optString("action") == "resize") web?.resized(action.getInt("columns"), action.getInt("rows"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failed: Exception) { takingInput = false; notice = failed.message?.take(240) ?: "本次输入待核，未重发。" }
        }
    }
    LaunchedEffect(session, opened, active, ready) {
        val original = session ?: return@LaunchedEffect
        if (!opened || !active || !ready) return@LaunchedEffect
        while (opened && active && session === original) {
            try {
                if (web?.waitingForParser() != true) {
                    val page = withContext(Dispatchers.IO) { original.read(cursor) }
                    logPolicy = page.optJSONObject("logObservationPolicy")
                    activity = page.optJSONObject("activity")
                    nativeStatus = page.optString("nativeStatus", "unknown")
                    if (nativeStatus != "running" || page.optBoolean("inputUncertain")) takingInput = false
                    if (page.optBoolean("logIncompleteTail")) { incomplete = true; takingInput = false; notice = "原日志末条未写完整，仅显示此前完整记录；原字节保留，已关闭输入，请核对原任务回执。" }
                    else if (page.optBoolean("outputTruncated")) { incomplete = true; takingInput = false; notice = "原日志已达8MiB上限，屏幕不是完整当前输出；已关闭输入，请在原任务明确停止并核对。" }
                    web?.display(page)
                    if (page.optBoolean("terminal") && !page.optBoolean("hasMore") && page.getJSONArray("chunks").length() == 0) break
                }
                delay(500)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failed: Exception) { takingInput = false; nativeStatus = "unknown"; notice = failed.message?.take(240) ?: "原终端输出待核；没有重启。"; break }
        }
    }
    LaunchedEffect(web, takingInput, active, nativeStatus, incomplete) { web?.inputEnabled(!observingOnly && takingInput && active && nativeStatus == "running" && !stopped && !incomplete) }
    OutlinedButton(onClick = { if (opened) closeView() else opened = true }, modifier = Modifier.testTag(if (observingOnly) "project_native_execution_observe_$execution" else "project_native_terminal_open")) { Text(if (observingOnly) { if (opened) "收起原执行日志" else "观察原执行日志" } else if (opened) "收起终端" else "打开交互终端") }
    if (opened && metadata != null) {
        Text("${metadata!!.optString("executable")} · ${metadata!!.optString("workingDirectory")}", style = MaterialTheme.typography.titleSmall)
        WorkbenchCaption(if (observingOnly) "只读显示同一原执行按接收顺序合并的日志画布；原日志仍保留stdout/stderr来源和cursor。收起或切到后台只关观察，不停止进程；需停止时使用上方原任务停止入口。" else "显示这个原PTY的VT屏幕，标准输出与错误合流。关闭屏幕不会停止任务；输入限原启动批准，不支持密码或令牌。")
        runtimeActivityCaption(activity, execution)?.let { WorkbenchCaption(it) }
        if (logPolicy?.optBoolean("automaticLogPageReduced") == true) WorkbenchCaption(logPolicy!!.optString("ownerMessage"))
        key(task.id, execution, metadata) {
            AndroidView(factory = { nativeContext ->
                try { ProjectTerminalWebView.create(nativeContext, metadata!!, object : ProjectTerminalWebView.Events {
                    override fun ready() { ready = true }
                    override fun input(text: String) { enqueue(JSONObject().put("action", "text").put("text", text)) }
                    override fun written(next: Long) { cursor = next }
                    override fun error(message: String) { takingInput = false; notice = message }
                }).also { web = it } }
                catch (failed: Exception) { notice = failed.message; TextView(nativeContext).apply { text = "此WebView暂不能显示隔离终端，可继续在原任务读日志。" } }
            }, modifier = Modifier.fillMaxWidth().height(360.dp).testTag("project_native_vt_screen"))
        }
        if (!observingOnly) {
        Row(Modifier.fillMaxWidth()) { Checkbox(takingInput, { value -> takingInput = value }, enabled = ready && nativeStatus == "running" && !stopped && !incomplete); Text("接管普通终端输入（最多 ${metadata!!.optLong("inputByteLimit")} 字节、${metadata!!.optInt("inputRequestLimit")} 次动作）", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { enqueue(JSONObject().put("action", "text").put("text", "\u0003")) }, enabled = takingInput) { Text("Ctrl-C") }
            OutlinedButton(onClick = { enqueue(JSONObject().put("action", "eof")) }, enabled = takingInput) { Text("行模式 EOF") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(columns, { columns = it.take(3) }, label = { Text("列 4–400") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(rows, { rows = it.take(3) }, label = { Text("行 2–200") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        TextButton(onClick = {
            val width = columns.toIntOrNull(); val height = rows.toIntOrNull()
            if (width == null || height == null || width !in 4..400 || height !in 2..200) notice = "请填写范围内的行列。"
            else { enqueue(JSONObject().put("action", "resize").put("columns", width).put("rows", height)) }
        }, enabled = takingInput) { Text("按原PTY调整窗口") }
        TextButton(onClick = {
            takingInput = false
            scope.launch { try { val result = withContext(Dispatchers.IO) { session?.originalInput() }; notice = when { result?.optBoolean("knownInput") != true -> "这个视图没有待核输入；原任务记录保留。"; result.optBoolean("nativeInputSettled") -> "已取得这个原输入的真实回执；没有再次发送。"; else -> "原输入仍待核，没有重发。" } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failed: Exception) { notice = failed.message?.take(240) ?: "原输入暂不可核对，没有重发。" } }
        }) { Text("只核对上次输入") }
        TextButton(onClick = {
            takingInput = false; stopped = true
            scope.launch { try { val result = withContext(Dispatchers.IO) { session?.stop() }; notice = if (result?.optBoolean("resourcesReleased") == true) "已取得原停止/释放回执。" else "已请求停止，只继续核对原退出与释放。" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failed: Exception) { notice = failed.message?.take(240) ?: "原停止结果待核。" } }
        }, enabled = !stopped) { Text("明确停止原终端进程") }
        }
    }
    notice?.let { Text(it, color = SecondaryInk, style = MaterialTheme.typography.bodySmall) }
}
