package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.ssh.SshTerminalScreen
import dev.pocketengine.features.ssh.SshTerminalWebView
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.coroutines.delay
import org.json.JSONObject

internal data class SshTerminalBinding(val terminalId: String, val hostTaskId: String, val runId: String, val profileId: String) {
    fun payload(): JSONObject = JSONObject().put("terminalId", terminalId).put("hostTaskId", hostTaskId).put("runId", runId)
}
internal fun sshTerminalBinding(value: JSONObject): SshTerminalBinding? {
    val terminal = value.optString("terminalId")
    val task = value.optString("hostTaskId").ifBlank { value.optString("taskId") }
    val run = value.optString("runId")
    val profile = value.optString("profileId")
    if (listOf(terminal, task, run, profile).any { it.isBlank() || it == "null" }) return null
    return SshTerminalBinding(terminal, task, run, profile)
}

/** Bounded private UI buffer. Preserve incomplete UTF-8 between real PTY read packets. */
internal class PtyTextBuffer(private val limit: Int = 120000) {
    private var pending = ByteArray(0)
    private val raw = StringBuilder()
    fun append(encoded: String, truncated: Boolean, finished: Boolean): String {
        if (truncated) { pending = ByteArray(0); raw.append("\n[较早的远端输出已被覆盖]\n") }
        val bytes = ByteBuffer.wrap(pending + Base64.getDecoder().decode(encoded))
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val chars = CharBuffer.allocate(bytes.remaining() + 4)
        decoder.decode(bytes, chars, finished)
        if (finished) decoder.flush(chars)
        pending = ByteArray(bytes.remaining()).also { bytes.get(it) }
        chars.flip()
        raw.append(chars)
        if (raw.length > limit) raw.delete(0, raw.length - limit)
        return raw.toString().replace(Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"), "").replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "").filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 && it.code != 127 }
    }
}

@Composable
internal fun SshTerminalView(binding: SshTerminalBinding, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    val screen = remember(binding.terminalId) { SshTerminalScreen() }
    var frame by remember(binding.terminalId) { mutableStateOf(screen.view(80, 24)) }
    var renderer by remember(binding.terminalId) { mutableStateOf<SshTerminalWebView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    DisposableEffect(binding.terminalId, lifecycle) { val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState == Lifecycle.State.RESUMED }; lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer); renderer?.release(); renderer = null } }
    var cursor by remember(binding.terminalId) { mutableLongStateOf(0) }
    var input by remember(binding.terminalId) { mutableStateOf("") }
    var appendNewline by remember(binding.terminalId) { mutableStateOf(true) }
    var automatic by remember(binding.terminalId) { mutableStateOf(true) }
    var resize by remember { mutableStateOf(false) }
    var cols by remember { mutableStateOf("80") }
    var rows by remember { mutableStateOf("24") }
    var closing by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val latestState by rememberUpdatedState(state)
    val latestCursor by rememberUpdatedState(cursor)
    val read = state.moduleResults["ssh.readPty"].takeIf { state.moduleResultInputs["ssh.readPty"]?.optString("terminalId") == binding.terminalId }
    val write = state.moduleResults["ssh.writePty"].takeIf { state.moduleResultInputs["ssh.writePty"]?.optString("terminalId") == binding.terminalId }
    val metadata = state.feature("ssh").optJSONArray("terminals").objects().find { it.optString("terminalId") == binding.terminalId }
    val status = metadata?.optString("status") ?: read?.optString("status") ?: "unknown"
    val task = state.tasks.find { it.id == binding.hostTaskId }
    val unresolved = task?.let(::unresolvedOperations).orEmpty()
    val blocked = unresolved.isNotEmpty() || task?.status == "reconciling" || write?.optString("status") == "unknown" && !ownerReviewedPtyInput(write, task)
    val writing = "ssh.writePty" in state.moduleBusy
    val canWrite = foreground && status == "running" && !blocked && !writing
    LaunchedEffect(binding.terminalId) { controller.getTask(binding.hostTaskId) }
    LaunchedEffect(read) {
        val requested = state.moduleResultInputs["ssh.readPty"]
        if (read != null && requested?.optLong("cursor", -1) == cursor && read.has("dataBase64")) {
            try { screen.append(read.optString("dataBase64"), cursor, read.optLong("cursor", cursor), read.optBoolean("truncated")); cursor = screen.cursor(); frame = screen.view(frame.optInt("cols", 80), frame.optInt("rows", 24)) }
            catch (_: Exception) { localError = "终端字节范围无法核对，未推进本机读取位置或重放任何输入。" }
        }
    }
    val resized = state.moduleResults["ssh.resizePty"].takeIf { state.moduleResultInputs["ssh.resizePty"]?.optString("terminalId") == binding.terminalId }
    LaunchedEffect(resized) { if (resized?.optBoolean("accepted") == true) { val original = state.moduleResultInputs["ssh.resizePty"]; if (original != null) frame = screen.view(original.optInt("cols", 80), original.optInt("rows", 24)) } }
    LaunchedEffect(binding.terminalId, automatic, foreground) {
        while (automatic && foreground) {
            if ("ssh.readPty" !in latestState.moduleBusy) controller.requestFeature("ssh", "readPty", binding.payload().put("cursor", latestCursor))
            delay(1000)
        }
    }
    fun send(bytes: ByteArray) {
        if (!canWrite) return
        if (bytes.size > 65536) { localError = "单次终端输入最多64KiB"; return }
        input = ""
        controller.requestFeature("ssh", "writePty", binding.payload().put("dataBase64", Base64.getEncoder().encodeToString(bytes)))
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = close) { Text("收起") }
                    Text("SSH 交互终端", Modifier.weight(1f), fontSize = 18.sp)
                    TextButton(onClick = { closing = true }) { Text("关闭连接", color = Danger, fontSize = 12.sp) }
                }
                Text(taskStatus(status) + " · xterm.js VT/TUI屏幕", fontSize = 13.sp, color = SecondaryInk)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("自动读取", fontSize = 11.sp)
                    Switch(automatic, { automatic = it })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { controller.requestFeature("ssh", "readPty", binding.payload().put("cursor", cursor)) }, enabled = "ssh.readPty" !in state.moduleBusy) { Text("读取输出", fontSize = 11.sp) }
                    TextButton(onClick = { resize = !resize }) { Text("尺寸", fontSize = 11.sp) }
                }
                localError?.let { Text(it, fontSize = 12.sp, color = Danger) }
                listOf("ssh.readPty", "ssh.writePty", "ssh.resizePty", "ssh.closePty").mapNotNull { state.moduleErrors[it] }.distinct().forEach { Text(it, fontSize = 12.sp, color = Danger) }
                AndroidView(factory = { context -> SshTerminalWebView(context) { localError = it }.also { renderer = it; it.display(frame) } }, update = { it.display(frame) }, modifier = Modifier.weight(1f).fillMaxWidth())
                if (frame.optBoolean("gap")) Text("早期字节超出保留窗口，屏幕已按剩余字节重建，不代表完整TUI状态。可明确发送 Ctrl+L 请求程序重绘。", fontSize = 13.sp, color = SecondaryInk)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { for ((label, value) in listOf("↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "Tab" to "\t", "Esc" to "\u001b", "↵" to "\r")) TextButton(onClick = { send(value.toByteArray(Charsets.UTF_8)) }, enabled = canWrite) { Text(label) } }
                if (blocked) {
                    Text("输入结果或会话状态需要核对，已暂停后续输入；读取输出不会重放命令。", fontSize = 11.sp, lineHeight = 19.sp, color = SecondaryInk)
                    TextButton(onClick = { controller.focusTask(binding.hostTaskId) }) { Text("到原任务核对结果", fontSize = 11.sp) }
                }
                if (resize) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cols, { cols = it.filter(Char::isDigit).take(3) }, label = { Text("列") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(rows, { rows = it.filter(Char::isDigit).take(3) }, label = { Text("行") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    TextButton(onClick = { val c = cols.toIntOrNull(); val r = rows.toIntOrNull(); if (c != null && r != null && c in 20..300 && r in 5..120) controller.requestFeature("ssh", "resizePty", binding.payload().put("cols", c).put("rows", r)) }, enabled = status == "running" && (cols.toIntOrNull() ?: 0) in 20..300 && (rows.toIntOrNull() ?: 0) in 5..120 && "ssh.resizePty" !in state.moduleBusy) { Text("调整") }
                }
                OutlinedTextField(input, { input = it }, label = { Text("要发送的文本") }, minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth(), enabled = canWrite)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(appendNewline, { appendNewline = it }); Text("附带换行", fontSize = 11.sp)
                    TextButton(onClick = { send(byteArrayOf(3)) }, enabled = canWrite) { Text("Ctrl+C", fontSize = 13.sp) }
                    TextButton(onClick = { send(byteArrayOf(12)) }, enabled = canWrite) { Text("Ctrl+L", fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { val bytes = (input + if (appendNewline) "\n" else "").toByteArray(Charsets.UTF_8); send(bytes) }, enabled = canWrite && input.isNotEmpty()) { Text(if (writing) "发送中" else "发送") }
                }
                Text("收起可稍后返回；关闭连接后，远端进程仍需核对。", fontSize = 10.sp, color = SecondaryInk, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        BackHandler { close() }
    }
    if (closing) AlertDialog(onDismissRequest = { closing = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("关闭这个 SSH 连接？") }, text = { Text("将关闭本机 PTY 连接。远端进程是否终止仍需核对，不会自动重连或重放输入。") }, confirmButton = { TextButton(onClick = { input = ""; automatic = false; controller.requestFeature("ssh", "closePty", binding.payload()); closing = false }, enabled = "ssh.closePty" !in state.moduleBusy) { Text("关闭本机连接", color = Danger) } }, dismissButton = { TextButton(onClick = { closing = false }) { Text("取消") } })
}

internal fun ownerReviewedPtyInput(receipt: JSONObject?, task: UiTask?): Boolean {
    val operationId = receipt?.optString("hostOperationId").orEmpty()
    if (operationId.isBlank() || task == null) return false
    val operation = task.raw.optJSONArray("operations").objects().find { it.optString("operationId") == operationId } ?: return false
    return operation.optString("state") == "resolved" && operation.optJSONObject("receipt")?.optString("source") == "owner_reconciliation"
}
