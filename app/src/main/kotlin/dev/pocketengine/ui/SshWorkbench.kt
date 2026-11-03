package dev.pocketengine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun NativeSshWorkbench(profileId: String, state: PocketUiState, controller: PocketWorldController) {
    val profile = state.feature("ssh").optJSONArray("profiles").objects().find { it.optString("id") == profileId } ?: return
    var mode by remember(profileId) { mutableIntStateOf(0) }
    var terminal by remember(profileId) { mutableStateOf<SshTerminalBinding?>(null) }
    var openConfirmation by remember { mutableStateOf(false) }
    var doctorProfile by remember { mutableStateOf<JSONObject?>(null) }
    val trusted = profile.optBoolean("hostKeyTrusted") && profile.optBoolean("credentialsConfigured")
    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Line)
    Text("当前连接：" + profile.optString("name"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    SshCaption("初始目录：" + profile.optString("workspace"))
    TextButton(onClick = { doctorProfile = JSONObject(profile.toString()) }, enabled = trusted) { Text("核对实际环境（私人只读）") }
    SshTaskComposer(profile, state, controller)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("命令", "文件", "终端").forEachIndexed { index, label -> FilterChip(selected = mode == index, onClick = { mode = index }, label = { Text(label) }) }
    }
    when (mode) {
        0 -> SshExec(profileId, trusted, state, controller)
        1 -> SshFiles(profileId, trusted, state, controller)
        2 -> {
            val opened = state.moduleResults["ssh.openPty"].takeIf { state.moduleResultInputs["ssh.openPty"]?.optString("profileId") == profileId }
            val openingUnknown = opened != null && opened.optString("status") == "unknown" && !ownerConfirmedExternalNotOccurred(opened, state.tasks)
            SshCaption("交互式 Shell 拥有此账号的远端权限；初始工作目录不是沙箱。下方展示实际文本记录，不模拟完整终端界面。")
            OutlinedButton(onClick = { openConfirmation = true }, enabled = trusted && !openingUnknown && "ssh.openPty" !in state.moduleBusy) { Text(if ("ssh.openPty" in state.moduleBusy) "正在建立终端" else "打开交互式终端") }
            LaunchedEffect(opened) { if (opened?.optString("status") == "running" && opened.optBoolean("ptyConfirmed")) terminal = sshTerminalBinding(opened) }
            opened?.let { receipt -> if (receipt.optString("message").isNotBlank()) SshCaption(receipt.optString("message")); if (receipt.optString("status") == "unknown") SshReceiptTask(receipt, controller) }
            state.feature("ssh").optJSONArray("terminals").objects().filter { it.optString("profileId") == profileId }.forEach { metadata ->
                val binding = sshTerminalBinding(metadata)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("终端 · " + taskStatus(metadata.optString("status")), fontSize = 13.sp); SshCaption("连接时限：" + metadata.optLong("expiresAt")) }
                    TextButton(onClick = { terminal = binding }, enabled = binding != null) { Text("查看文本记录", fontSize = 12.sp) }
                }
            }
        }
    }
    if (openConfirmation) AlertDialog(onDismissRequest = { openConfirmation = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("打开远端交互式 Shell？") }, text = { Text("连接：" + profile.optString("name") + "\n初始目录：" + profile.optString("workspace") + "\n\n该 Shell 使用此账号的实际远端权限。关闭手机连接不保证远端进程终止。") }, confirmButton = { Button(onClick = { controller.requestFeature("ssh", "openPty", JSONObject().put("profileId", profileId).put("cols", 80).put("rows", 24).put("timeoutSeconds", 600).put("allowInteractiveShell", true)); openConfirmation = false }) { Text("打开这次终端") } }, dismissButton = { TextButton(onClick = { openConfirmation = false }) { Text("取消") } })
    terminal?.let { binding -> SshTerminalView(binding, state, controller) { terminal = null } }
    doctorProfile?.let { captured -> PrivateSshDoctorPage(captured, controller) { doctorProfile = null } }
}

@Composable
private fun SshExec(profileId: String, trusted: Boolean, state: PocketUiState, controller: PocketWorldController) {
    var command by remember(profileId) { mutableStateOf("") }
    val result = state.moduleResults["ssh.exec"].takeIf { state.moduleResultInputs["ssh.exec"]?.optString("profileId") == profileId }
    val uncertain = result != null && result.optString("status") == "unknown" && !ownerConfirmedExternalNotOccurred(result, state.tasks)
    val busy = "ssh.exec" in state.moduleBusy
    SshCaption("命令从所选工作目录执行。请核对实际命令；退出码为零仍需确认业务结果。")
    OutlinedTextField(command, { command = it }, label = { Text("POSIX 命令") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    Button(onClick = { controller.requestFeature("ssh", "exec", JSONObject().put("profileId", profileId).put("command", command).put("timeoutSeconds", 60)) }, enabled = trusted && !busy && !uncertain && command.isNotBlank() && command.length <= 12000, modifier = Modifier.padding(top = 10.dp)) { Text(if (busy) "等待命令回执" else "执行这条命令") }
    result?.let { receipt ->
        SshCaption(receipt.optString("message", taskStatus(receipt.optString("status"))))
        if (receipt.has("exitCode")) Text("退出码：" + receipt.optInt("exitCode"), fontSize = 12.sp, color = SecondaryInk)
        listOf("stdout" to "标准输出", "stderr" to "错误输出").forEach { (key, title) -> if (receipt.optString(key).isNotBlank()) { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp)); SelectionContainer { Text(receipt.optString(key), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) } } }
        SshReceiptTask(receipt, controller)
    }
    val running = state.tasks.firstOrNull { it.raw.optString("feature") == "ssh" && it.raw.optString("command") == "exec" && it.raw.optJSONObject("externalRefs")?.optString("profileId") == profileId && it.status == "running" }
    if (busy && running != null) TextButton(onClick = { controller.requestFeature("ssh", "cancel", JSONObject().put("operationId", running.raw.optString("runId"))) }) { Text("请求中断本机连接", color = Danger, fontSize = 12.sp) }
    if (uncertain) SshCaption("命令是否仍在远端运行尚不确定。先在原任务核对，不直接重放命令。")
}

@Composable
private fun SshFiles(profileId: String, trusted: Boolean, state: PocketUiState, controller: PocketWorldController) {
    var directory by remember(profileId) { mutableStateOf("") }
    var editingPath by remember(profileId) { mutableStateOf<String?>(null) }
    var binaryPath by remember(profileId) { mutableStateOf<String?>(null) }
    val listed = state.moduleResults["ssh.listFiles"].takeIf { state.moduleResultInputs["ssh.listFiles"]?.optString("profileId") == profileId }
    SshCaption("路径相对于所选工作区。读取文本文件后，才可按真实版本保存；不跟随符号链接。")
    OutlinedButton(onClick = { binaryPath = "" }, enabled = trusted) { Text("原文件上传/下载（≤20MiB）", fontSize = 12.sp) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(directory, { directory = it }, label = { Text("相对目录，留空为工作区") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { controller.requestFeature("ssh", "listFiles", JSONObject().put("profileId", profileId).put("path", directory.trim())) }, enabled = trusted && "ssh.listFiles" !in state.moduleBusy) { Text("读取") }
    }
    if (listed != null && listed.has("files")) {
        SshCaption("当前结果：" + listed.optString("path").ifBlank { "工作区根目录" })
        listed.optJSONArray("files").objects().forEach { entry ->
            val path = sshChildPath(listed.optString("path"), entry.optString("name"))
            Row(Modifier.fillMaxWidth().clickable(enabled = path != null && !entry.optBoolean("symlink")) {
                if (path != null) {
                    if (entry.optBoolean("directory")) { directory = path; controller.requestFeature("ssh", "listFiles", JSONObject().put("profileId", profileId).put("path", path)) }
                    else editingPath = path
                }
            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.optString("name"), Modifier.weight(1f), fontSize = 13.sp)
                Text(if (entry.optBoolean("symlink")) "符号链接" else if (entry.optBoolean("directory")) "目录 ›" else entry.optLong("bytes").toString() + " 字节", color = SecondaryInk, fontSize = 11.sp)
                if (path != null && !entry.optBoolean("directory") && !entry.optBoolean("symlink")) TextButton(onClick = { binaryPath = path }) { Text("收发", fontSize = 12.sp) }
            }
            HorizontalDivider(color = Line)
        }
    }
    editingPath?.let { path -> SftpTextEditor(profileId, path, state, controller) { editingPath = null } }
    binaryPath?.let { path -> SshBinaryFileDialog(profileId, path, state, controller) { binaryPath = null } }
}

internal fun sshChildPath(directory: String, name: String): String? {
    if (name.isBlank() || name in setOf(".", "..") || name.contains('/') || name.contains('\\') || name.any { it.code < 32 || it.code == 127 }) return null
    if (directory.startsWith('/') || directory.contains('\\') || directory.split('/').any { it == "." || it == ".." }) return null
    return if (directory.isBlank()) name else directory + "/" + name
}

@Composable
private fun SftpTextEditor(profileId: String, path: String, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var content by remember(profileId, path) { mutableStateOf("") }
    var baseline by remember(profileId, path) { mutableStateOf("") }
    var version by remember(profileId, path) { mutableStateOf("") }
    var initialized by remember(profileId, path) { mutableStateOf(false) }
    var readRequest by remember(profileId, path) { mutableStateOf<String?>(null) }
    var submitted by remember(profileId, path) { mutableStateOf<SftpWriteRequest?>(null) }
    var writeReceipt by remember(profileId, path) { mutableStateOf<JSONObject?>(null) }
    var localError by remember(profileId, path) { mutableStateOf<String?>(null) }
    var showRemote by remember(profileId, path) { mutableStateOf(false) }
    var failedWrite by remember(profileId, path) { mutableStateOf(false) }
    var confirmWrite by remember { mutableStateOf(false) }
    var cooperativeAccepted by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val readInput = state.moduleResultInputs["ssh.readFile"]
    val writeInput = state.moduleResultInputs["ssh.writeFile"]
    val read = SshFileReceiptGuard.result(state.moduleResults["ssh.readFile"], readInput, readRequest, profileId, path)
    val write = submitted?.let { SshFileReceiptGuard.result(state.moduleResults["ssh.writeFile"], writeInput, it.id, profileId, path, it.version) }
    val saving = "ssh.writeFile" in state.moduleBusy
    val loading = "ssh.readFile" in state.moduleBusy
    val priorWrite = state.moduleResults["ssh.writeFile"].takeIf { SshFileReceiptGuard.target(writeInput, profileId, path) }
    val unresolvedTask = state.tasks.firstOrNull { it.raw.optString("feature") == "ssh" && it.raw.optJSONObject("externalRefs")?.optString("profileId") == profileId && it.raw.optString("command") in setOf("writeFile", "uploadFile", "exec", "openPty") && (it.status in setOf("reconciling", "unknown") || unresolvedOperations(it).isNotEmpty()) }
    val uncertain = failedWrite || unresolvedTask != null || priorWrite?.optString("status") == "unknown" && !ownerConfirmedExternalNotOccurred(priorWrite, state.tasks)
    fun reload() { readRequest = controller.requestFeature("ssh", "readFile", JSONObject().put("profileId", profileId).put("path", path)); localError = null }
    LaunchedEffect(profileId, path) { reload() }
    LaunchedEffect(read) {
        if (read?.optString("status") == "completed" && read.has("content") && read.optString("version").matches(Regex("sha256:[a-f0-9]{64}"))) {
            val observed = read.getString("content")
            if (!initialized) content = observed else showRemote = content != observed
            baseline = observed; version = read.getString("version"); initialized = true
        }
    }
    LaunchedEffect(write, writeInput?.toString()) {
        val pending = submitted ?: return@LaunchedEffect
        if (write != null) {
            writeReceipt = JSONObject(write.toString())
            if (SshFileReceiptGuard.saved(write, pending.content)) {
                baseline = pending.content; version = write.getString("version"); showRemote = false
            } else if (write.optString("status") == "completed") { failedWrite = true; localError = "写回执与本次实际内容不一致，请先到原任务核对。" }
            submitted = null
        } else if (ProjectWorkbenchGuard.failedRequest(writeInput, pending.id)) {
            failedWrite = true; localError = state.moduleErrors["ssh.writeFile"] ?: "未收到本次保存的确定回执，请核对原任务。"; submitted = null
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) { if (content != baseline) discard = true else close() } }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(path, fontSize = 16.sp) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (readInput?.optString("_requestId") == readRequest) state.moduleErrors["ssh.readFile"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
            localError?.let { Text(it, color = Danger, fontSize = 12.sp) }
            if (!loading && !saving) TextButton(onClick = { reload() }) { Text(if (initialized) "重新读取远端版本并比较" else "重新读取") }
            if (showRemote) { SshCaption("重新读取的当前远端版本（你的编辑保留在下方）"); SelectionContainer { Text(baseline, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) } }
            OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp), minLines = 8, maxLines = 18, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp), enabled = initialized && !saving && !loading && !uncertain)
            writeReceipt?.optString("message")?.takeIf { it.isNotBlank() }?.let { SshCaption(it) }
            (writeReceipt ?: priorWrite)?.let { SshReceiptTask(it, controller) }
            unresolvedTask?.let { task -> TextButton(onClick = { controller.focusTask(task.id) }) { Text("核对这个账号的原任务") } }
            if (uncertain) SshCaption("写入结果不明，已停止重复保存。保留本地内容，可复制后到原任务和远端核对；不会自动重试。")
        }
    }, confirmButton = { Button(onClick = { cooperativeAccepted = false; confirmWrite = true }, enabled = initialized && content != baseline && submitted == null && !saving && !loading && !uncertain) { Text(if (saving || submitted != null) "等待写入回执" else "保存到远端") } }, dismissButton = { TextButton(onClick = { if (content != baseline) discard = true else close() }, enabled = !saving) { Text("关闭") } })
    if (confirmWrite) AlertDialog(onDismissRequest = { confirmWrite = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("保存这份远端文件？") }, text = { Column {
        Text(path, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        SshCaption("会检查已读取的文件版本，在协作锁内替换内容。其他不遵守这个锁的程序仍可能同时修改文件，这不是全局原子比较并交换。")
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cooperativeAccepted, { cooperativeAccepted = it }); Text("已核对修改，接受本次协作校验写入", fontSize = 12.sp) }
    } }, confirmButton = { Button(onClick = { val frozen = content; val reviewedVersion = version; val request = controller.requestFeature("ssh", "writeFile", JSONObject().put("profileId", profileId).put("path", path).put("content", frozen).put("expectedVersion", reviewedVersion).put("acceptCooperativeWrite", true)); submitted = SftpWriteRequest(request, frozen, reviewedVersion); localError = null; confirmWrite = false }, enabled = cooperativeAccepted && submitted == null && !saving && !uncertain) { Text("确认写入") } }, dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text("取消") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("关闭远端文件编辑？") }, text = { Text("当前未确认保存的编辑内容会从本地编辑区移除。远端结果以实际回执和核对为准。") }, confirmButton = { TextButton(onClick = { discard = false; close() }) { Text("关闭编辑区") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("保留编辑") } })
}

private data class SftpWriteRequest(val id: String, val content: String, val version: String)

@Composable
internal fun SshReceiptTask(receipt: JSONObject, controller: PocketWorldController) {
    val task = receipt.optString("hostTaskId").ifBlank { receipt.optString("taskId") }
    if (task.isNotBlank()) TextButton(onClick = { controller.focusTask(task) }) { Text("查看原任务与核对结果", fontSize = 12.sp) }
}
