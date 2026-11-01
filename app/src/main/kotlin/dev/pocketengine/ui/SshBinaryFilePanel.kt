package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Native original-attachment references only. No inline/base64 file body or model tool is added. */
@Composable
internal fun SshBinaryFileDialog(profileId: String, initialPath: String, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    val profile = state.feature("ssh").optJSONArray("profiles").objects().find { it.optString("id") == profileId }
    val capturedBinding = remember(profileId, initialPath) { profile?.optString("bindingHash") ?: "" }
    var path by remember(profileId, initialPath) { mutableStateOf(initialPath) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var inspect by remember { mutableStateOf<SshBinaryRequest?>(null) }
    var transfer by remember { mutableStateOf<SshBinaryRequest?>(null) }
    var confirmation by remember { mutableStateOf<JSONObject?>(null) }
    var accepted by remember { mutableStateOf(false) }
    var filePickerExpanded by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    val actualPath = path.trim()
    val current = profile != null && capturedBinding.isNotEmpty() && profile.optString("bindingHash") == capturedBinding && profile.optBoolean("credentialsConfigured") && profile.optBoolean("hostKeyTrusted")
    val active = state.tasks.firstOrNull { it.raw.optString("feature") == "ssh" && it.raw.optJSONObject("externalRefs")?.optString("profileId") == profileId && it.status in setOf("queued", "running", "stopping") }
    val unresolved = state.tasks.firstOrNull { it.raw.optString("feature") == "ssh" && it.raw.optJSONObject("externalRefs")?.optString("profileId") == profileId && (it.status in setOf("reconciling", "unknown") || unresolvedOperations(it).isNotEmpty()) }
    val busy = active != null || listOf("inspectFile", "downloadFile", "uploadFile").any { "ssh.$it" in state.moduleBusy }
    val observed = inspect?.let { exactBinaryReceipt(it, state, profileId, capturedBinding) }?.takeIf { it.optString("status") == "completed" && inspect?.path == actualPath }
    val result = transfer?.let { exactBinaryReceipt(it, state, profileId, capturedBinding) }
    val uncertain = result != null && result.optString("status") == "unknown" && !ownerConfirmedExternalNotOccurred(result, state.tasks) || unresolved != null
    val version = observed?.optString("version")?.takeIf { it == "absent" || it.matches(Regex("sha256:[a-f0-9]{64}")) }
    val files = state.feature("attachments").optJSONArray("attachments").objects().filter { it.optString("principalId") == "owner" && it.optString("spaceId") == "private" && it.optLong("size", -1) in 0..20L * 1024 * 1024 && it.optJSONObject("source")?.has("groupSpaceId") != true }
    val selected = files.find { it.optString("id") == selectedId }
    val originalTransfers = state.tasks.filter { task ->
        val refs = task.raw.optJSONObject("externalRefs")
        task.raw.optString("feature") == "ssh" && task.raw.optString("command") in setOf("downloadFile", "uploadFile") && refs?.optString("profileId") == profileId && refs?.optJSONObject("sshNativeRequest")?.optString("profileBinding") == capturedBinding
    }.take(3)
    fun inspectOriginal() {
        val input = JSONObject().put("profileId", profileId).put("_profileBinding", capturedBinding).put("path", actualPath).put("timeoutSeconds", 180)
        val id = controller.requestFeature("ssh", "inspectFile", input)
        inspect = SshBinaryRequest(id, "inspectFile", actualPath, "", "", "")
    }
    AlertDialog(onDismissRequest = { confirmation = null; close() }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("文件收发", fontSize = 17.sp) }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile?.optString("name") ?: "连接已不存在", fontSize = 14.sp)
            Text((profile?.optString("host") ?: "") + " · " + (profile?.optString("workspace") ?: ""), color = SecondaryInk, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!current) Text("连接、凭据或指纹已更新，请重新打开后核对。", color = Danger, fontSize = 12.sp)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                SshBinaryCaption(if ("ssh.inspectFile" in state.moduleBusy) "正在核对文件版本…" else "这个连接正在处理任务，等待实际回执。")
            }
            active?.let { task ->
                Row {
                    TextButton(onClick = { controller.focusTask(task.id) }) { Text("查看正在执行的任务", fontSize = 12.sp) }
                    TextButton(onClick = { controller.requestFeature("ssh", "cancel", JSONObject().put("operationId", task.raw.optString("runId"))) }) { Text("中断本机连接", fontSize = 12.sp, color = Danger) }
                }
            }
            result?.let { receipt ->
                Text(binaryReceiptTitle(receipt), fontSize = 13.sp)
                if (receipt.optBoolean("cleanupReviewRequired")) Text("临时文件或协作锁的清理待核对。", fontSize = 12.sp, color = Danger)
                if (receipt.optString("status") == "completed" && receipt.optBoolean("artifactSaved")) receipt.optJSONObject("attachment")?.let { file ->
                    TextButton(onClick = { controller.openAttachmentOutcome(JSONObject().put("id", file.optString("id")).put("sha256", file.optString("sha256"))) }) { Text("打开已保存的文件", fontSize = 12.sp) }
                }
                SshReceiptTask(receipt, controller)
            }
            if (uncertain) {
                SshBinaryCaption("结果尚未核实，暂不重传。请先核对任务和远端文件。")
                unresolved?.let { task -> TextButton(onClick = { controller.focusTask(task.id) }) { Text("核对这个任务", fontSize = 12.sp) } }
            }
            OutlinedTextField(path, { path = it }, label = { Text("工作区内的文件路径") }, singleLine = true, enabled = !busy && confirmation == null, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = ::inspectOriginal, enabled = current && !busy && !uncertain && validBinaryPath(actualPath)) { Text("核对文件版本", fontSize = 12.sp) }
            if (observed != null) {
                SshBinaryCaption(if (observed.optBoolean("exists")) "远端文件 · " + observed.optLong("bytes") + " bytes · 版本 " + version?.removePrefix("sha256:")?.take(16) + "…" else "此路径当前没有文件，可以新建；父目录需已存在。")
                if (observed.optBoolean("exists") && version != null) TextButton(onClick = { confirmation = JSONObject().put("direction", "download").put("path", actualPath).put("version", version); accepted = false }, enabled = current && !busy && !uncertain) { Text("将这个版本保存到手机", fontSize = 12.sp) }
            } else SshBinaryCaption("先核对这条路径的当前版本，再上传或保存。单文件≤20MiB。")
            HorizontalDivider()
            Text("从手机上传", fontSize = 13.sp)
            if (selected != null) { Text(selected.optString("name"), fontSize = 13.sp); SshBinaryCaption(selected.optLong("size").toString() + " bytes") }
            TextButton(onClick = { filePickerExpanded = !filePickerExpanded }, enabled = !busy) { Text(if (filePickerExpanded) "收起文件选择" else if (selected == null) "选择手机文件" else "更换所选文件", fontSize = 12.sp) }
            if (filePickerExpanded) {
                TextButton(onClick = { controller.pickAttachments() }, enabled = !busy) { Text("从手机导入文件", fontSize = 12.sp) }
                if (files.isEmpty()) SshBinaryCaption("还没有20MiB内的私人附件。导入后再选择，不会自动上传。")
                for (file in files.take(12)) Row {
                    RadioButton(selectedId == file.optString("id"), { selectedId = file.optString("id"); filePickerExpanded = false }, enabled = !busy)
                    Column(Modifier.weight(1f).padding(top = 9.dp)) { Text(file.optString("name"), fontSize = 12.sp); SshBinaryCaption(file.optLong("size").toString() + " bytes") }
                }
                if (files.size > 12) SshBinaryCaption("这里只显示最近12个可选附件；更多文件可在文件与成果页核对。")
            }
            Button(onClick = {
                if (selected != null && version != null) confirmation = JSONObject().put("direction", "upload").put("path", actualPath).put("version", version).put("attachmentId", selected.optString("id")).put("sha256", selected.optString("sha256")).put("name", selected.optString("name")).put("bytes", selected.optLong("size"))
                accepted = false
            }, enabled = current && !busy && !uncertain && selected != null && version != null) { Text("上传这个文件", fontSize = 12.sp) }
            if (originalTransfers.isNotEmpty()) {
                TextButton(onClick = { historyExpanded = !historyExpanded }) { Text(if (historyExpanded) "收起最近传输" else "已保存的文件与最近传输", fontSize = 12.sp) }
                if (historyExpanded) {
                    SshBinaryCaption("当前任务列表中这个连接的最近3项；打开历史不会重新传输。")
                    originalTransfers.forEach { task ->
                        val evidence = task.raw.optJSONObject("evidence") ?: JSONObject()
                        SshBinaryCaption(evidence.optString("path", task.raw.optJSONObject("externalRefs")?.optJSONObject("sshNativeRequest")?.optString("path") ?: "文件") + " · " + taskStatus(task.status))
                        if (evidence.optBoolean("cleanupReviewRequired")) Text("临时文件清理待核对。", fontSize = 12.sp, color = Danger)
                        TextButton(onClick = { controller.focusTask(task.id) }) { Text("查看这个任务", fontSize = 12.sp) }
                        evidence.optJSONObject("attachment")?.takeIf { evidence.optBoolean("artifactSaved") }?.let { file ->
                            TextButton(onClick = { controller.openAttachmentOutcome(JSONObject().put("id", file.optString("id")).put("sha256", file.optString("sha256"))) }) { Text("打开已保存的文件", fontSize = 12.sp) }
                        }
                    }
                }
            }
            listOfNotNull(inspect, transfer).forEach { request -> val key = "ssh.${request.command}"; if (state.moduleResultInputs[key]?.optString("_requestId") == request.id) state.moduleErrors[key]?.let { Text(it, color = Danger, fontSize = 12.sp) } }
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) { Text(if (detailsExpanded) "收起传输详情" else "传输说明与详细回执", fontSize = 12.sp) }
            if (detailsExpanded) {
                SshBinaryCaption("使用现有SSH连接流式收发，单文件最多20MiB。版本核对会读取实际字节并计算SHA256，不只相信文件名或修改时间；二进制不会放进JSON或模型上下文。")
                SshBinaryCaption("上传使用协作锁、临时文件、关闭回执、前后摘要和POSIX rename扩展核对。不遵守锁的其他写者仍可能竞争；工作区路径不是OS沙箱，也不是全局原子CAS。")
                SshBinaryCaption("本机等待时限180秒，到期请求断开；无法据此保证远端进程停止。结果未知时只核对已有任务，不会自动重传、续传或另建传输。文件校验不代表业务测试通过或创作归属。")
                if (version != null) SshBinaryCaption("所选远端版本：" + version)
                selected?.let { SshBinaryCaption("所选手机文件SHA256：" + it.optString("sha256")) }
                result?.let { receipt ->
                    SshBinaryCaption(receipt.optString("message"))
                    if (receipt.has("bytesReadFromLocal")) SshBinaryCaption("手机实际已读取 " + receipt.optLong("bytesReadFromLocal") + " bytes；最后阶段：" + receipt.optString("phase"))
                    if (receipt.optString("stagingPath").isNotEmpty()) SshBinaryCaption("本次暂存文件：" + receipt.optString("stagingPath"))
                    if (receipt.optString("lockPath").isNotEmpty()) SshBinaryCaption("本次协作锁：" + receipt.optString("lockPath"))
                }
            }
            SshBinaryCaption("离开此页不会中断正在执行的传输。")
        }
    }, confirmButton = { TextButton(onClick = { confirmation = null; close() }) { Text("关闭") } })
    confirmation?.let { review ->
        val uploading = review.optString("direction") == "upload"
        val sourceStillSame = !uploading || files.any { it.optString("id") == review.optString("attachmentId") && it.optString("sha256") == review.optString("sha256") && it.optLong("size") == review.optLong("bytes") }
        val same = current && sourceStillSame && review.optString("path") == actualPath && review.optString("version") == version
        AlertDialog(onDismissRequest = { confirmation = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(if (uploading) "上传这个文件？" else "将这个版本保存到手机？") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((profile?.optString("name") ?: "") + " · " + (profile?.optString("host") ?: ""), fontSize = 13.sp)
                SshBinaryCaption(profile?.optString("workspace") ?: "")
                Text(review.optString("path"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                SshBinaryCaption("远端基线：" + review.optString("version"))
                if (uploading) { Text(review.optString("name") + " · " + review.optLong("bytes") + " bytes", fontSize = 13.sp); SshBinaryCaption("手机SHA256：" + review.optString("sha256")); SshBinaryCaption((if (review.optString("version") == "absent") "将在此位置新建文件。" else "这会替换目的文件。") + "只上传以上所选私人附件；其他程序不遵守协作锁时仍可能竞争，工作区路径不是系统级隔离。结果未知时不会自动重传。") }
                else SshBinaryCaption("只将这次核对的版本保存到手机私人附件。文件已变化时拒绝保存其他内容；不证明由某个任务创作。")
                Row { Checkbox(accepted, { accepted = it }, enabled = same); Text(if (uploading) "确认以上文件、目的地和本次写入风险" else "确认保存到手机私人附件", fontSize = 12.sp, modifier = Modifier.weight(1f).padding(top = 12.dp)) }
                if (!same) Text("连接、所选文件或路径版本已变化，请返回重新核对。", color = Danger, fontSize = 12.sp)
            }
        }, confirmButton = { Button(onClick = {
            val command = if (uploading) "uploadFile" else "downloadFile"
            val payload = JSONObject().put("profileId", profileId).put("_profileBinding", capturedBinding).put("path", review.optString("path")).put("expectedVersion", review.optString("version")).put("timeoutSeconds", 180)
            if (uploading) payload.put("attachmentId", review.optString("attachmentId")).put("expectedSha256", review.optString("sha256")).put("acceptCooperativeWrite", true)
            val request = controller.requestFeature("ssh", command, payload)
            transfer = SshBinaryRequest(request, command, review.optString("path"), review.optString("version"), review.optString("attachmentId"), review.optString("sha256")); confirmation = null; inspect = null
        }, enabled = same && accepted && !busy && !uncertain) { Text(if (uploading) "确认上传这个版本" else "保存这个版本") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("返回") } })
    }
}

private data class SshBinaryRequest(val id: String, val command: String, val path: String, val version: String, val attachment: String, val digest: String)
private fun exactBinaryReceipt(request: SshBinaryRequest, state: PocketUiState, profile: String, binding: String): JSONObject? {
    val key = "ssh.${request.command}"; val metadata = state.moduleResultInputs[key]
    if (metadata == null || metadata.optString("_profileBinding") != binding) return null
    if (request.command == "uploadFile" && (metadata.optString("attachmentId") != request.attachment || metadata.optString("expectedSha256") != request.digest)) return null
    return SshFileReceiptGuard.result(state.moduleResults[key], metadata, request.id, profile, request.path, request.version.takeIf { it.isNotEmpty() })
}
private fun validBinaryPath(value: String): Boolean = value.isNotEmpty() && value.length <= 1024 && !value.startsWith('/') && !value.contains('\\') && value.none { it.code < 32 || it.code == 127 || it in "*?[]" } && value.split('/').none { it.isEmpty() || it == "." || it == ".." }
@Composable private fun SshBinaryCaption(text: String) { Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk) }

private fun binaryReceiptTitle(receipt: JSONObject): String = when (receipt.optString("status")) {
    "completed" -> if (receipt.optBoolean("artifactSaved")) "已保存这个版本到手机" else "上传文件已完成字节校验，业务结果仍待验收"
    "unknown" -> "传输结果尚未核实"
    "cancelled" -> "本机等待已取消"
    "failed" -> if (receipt.optString("code") == "version_conflict") "远端文件已更新，未覆盖。请核对最新版本。" else "这次文件收发未完成，请查看任务回执"
    else -> taskStatus(receipt.optString("status"))
}
