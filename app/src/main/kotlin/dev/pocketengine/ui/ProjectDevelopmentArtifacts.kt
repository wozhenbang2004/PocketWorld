package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.local.preview.ProjectOwnerAction
import dev.pocketengine.features.local.preview.ProjectPreviewArtifact
import kotlinx.coroutines.delay
import org.json.JSONObject

private data class CollectionRequest(val command: String, val id: String)

/** One selected output on the original owner-tracked collection route; no execution authority here. */
@Composable
internal fun ProjectGeneratedArtifactSection(
    build: JSONObject, original: JSONObject, state: PocketUiState, controller: PocketWorldController,
    ownerBusy: Boolean, onPreview: (ProjectPreviewArtifact.Verified?) -> Unit, onBlocking: (Boolean) -> Unit
) {
    val checkout = build.getString("checkoutId")
    val source = build.getString("sourceDigest")
    val buildIdentity = build.getString("receiptIdentity")
    var path by remember(checkout, buildIdentity) { mutableStateOf("dist/index.html") }
    var selection by remember(checkout, buildIdentity) { mutableStateOf<JSONObject?>(null) }
    var confirmation by remember(checkout, buildIdentity) { mutableStateOf<JSONObject?>(null) }
    var pending by remember(checkout, buildIdentity) { mutableStateOf<CollectionRequest?>(null) }
    var collectionId by remember(checkout, buildIdentity) { mutableStateOf("") }
    var collected by remember(checkout, buildIdentity) { mutableStateOf<JSONObject?>(null) }
    var dispatchUnknown by remember(checkout, buildIdentity) { mutableStateOf(false) }
    var failureText by remember(checkout, buildIdentity) { mutableStateOf<String?>(null) }
    var previewReady by remember(checkout, buildIdentity) { mutableStateOf(false) }
    val latestOwnerBusy by rememberUpdatedState(ownerBusy)
    fun submit(command: String, input: JSONObject) {
        val payload = JSONObject(input.toString()).put("checkoutId", checkout)
        failureText = null
        pending = CollectionRequest(command, controller.requestFeature("local", command, payload))
    }
    fun refresh() { if (collectionId.isNotBlank()) submit("getJob", JSONObject().put("jobId", collectionId).put("termux", true)) }
    val request = pending
    val key = request?.let { "local.${it.command}" }
    val response = key?.let { state.moduleResults[it] }
    val metadata = key?.let { state.moduleResultInputs[it] }
    LaunchedEffect(request?.id, response?.toString(), metadata?.optString("_requestId"), key?.let { state.moduleErrors[it] }) {
        val active = pending ?: return@LaunchedEffect
        val rawJob = active.command in setOf("termux.collect", "getJob")
        val matches = if (rawJob) LocalWorkbenchGuard.result(response, metadata, active.id, checkout) != null
            else ProjectPreviewArtifact.current(response, metadata, active.id, checkout, source)
        if (matches) {
            try {
                val result = JSONObject(response.toString())
                when (active.command) {
                    "project.generatedArtifactPlan" -> { ProjectOwnerAction.reviewedCollection(result, build, path); selection = result }
                    "termux.collect", "getJob" -> {
                        val actualId = result.getString("id")
                        if (!actualId.matches(Regex("[A-Za-z0-9_-]{1,80}")) || active.command == "getJob" && actualId != collectionId) error("回收回执身份不一致")
                        if (source != result.optString("sourceDigest")) error("回收回执属于其他源码版本")
                        collectionId = actualId; collected = result; dispatchUnknown = false
                        if (collectionSucceeded(result)) {
                            val frozen = selection ?: error("缺少主人已核对的回收方案")
                            pending = null
                            submit("project.generatedArtifact", JSONObject().put("collectionPlan", frozen).put("collectionJobId", collectionId))
                            return@LaunchedEffect
                        }
                    }
                    "project.generatedArtifact" -> {
                        val frozen = selection ?: error("缺少主人已核对的回收方案")
                        val verified = ProjectPreviewArtifact.verifyCollected(result, build, frozen, collectionId)
                        collected = result.getJSONObject("collectionReceipt"); previewReady = true; onPreview(verified)
                    }
                }
            } catch (failure: Exception) {
                failureText = failure.message ?: "回收文件未通过原构建和回收回执核对"
                if (active.command == "termux.collect") dispatchUnknown = true
            }
            pending = null
        } else if (key != null && key !in state.moduleBusy && metadata?.optString("_requestId") == active.id && state.moduleErrors[key] != null) {
            failureText = state.moduleErrors[key]
            if (active.command == "termux.collect") dispatchUnknown = true
            pending = null
        }
    }
    val finished = collectionFinished(collected)
    val blocking = dispatchUnknown || collectionId.isNotEmpty() && !finished || pending?.command == "termux.collect"
    LaunchedEffect(blocking) { onBlocking(blocking) }
    DisposableEffect(checkout, buildIdentity) { onDispose { onBlocking(false) } }
    val waiting = collectionId.isNotEmpty() && collected?.optString("status") in setOf("queued", "running", "awaiting_result")
    LaunchedEffect(collectionId, waiting) {
        while (waiting) {
            delay(1800)
            if (pending == null && !latestOwnerBusy) refresh()
        }
    }
    val busy = ownerBusy || pending != null
    Text("回收一个生成产物", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
    CollectionCaption("只取此构建版本目录中选定的完整 UTF-8 文本，最多 32000 字节。源码、APK、二进制及多文件资源不在此入口范围。HTML/CSS/JS 仍需单独打开交互预览。")
    OutlinedTextField(path, { path = it; selection = null; previewReady = false }, label = { Text("生成文件相对路径，如 dist/index.html") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy && !blocking && collectionId.isEmpty())
    if (collectionId.isEmpty() && !dispatchUnknown) {
        OutlinedButton(onClick = { submit("project.generatedArtifactPlan", JSONObject(original.toString()).put("path", path)) }, enabled = !busy && path.isNotBlank()) { Text("核对单文件回收方案") }
        selection?.let { frozen ->
            CollectionCaption("路径 ${frozen.optString("path")}\n构建 ${frozen.optString("buildJobId")} · 源码 ${frozen.optString("sourceDigest").take(12)}")
            val warnings = frozen.optJSONArray("warnings")
            if (warnings != null) for (index in 0 until warnings.length()) CollectionCaption(warnings.optString(index))
            Button(onClick = { confirmation = JSONObject(frozen.toString()) }, enabled = !busy) { Text("确认并回收这一文件") }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    failureText?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 21.sp) }
    if (dispatchUnknown) CollectionCaption("派发未取得确定回执。请到「执行回执」核对原操作；本页不重新提交。")
    collected?.let { receipt ->
        CollectionCaption("回收 ${collectionId.take(12)} · ${collectionStatus(receipt.optString("status"))}")
        if (receipt.has("exitCode") && !receipt.isNull("exitCode")) CollectionCaption("回收退出码 ${receipt.opt("exitCode")}；它不改变原构建结果。")
        if (receipt.optBoolean("outputTruncated")) CollectionCaption("回收回执已截断，不能进入预览。")
        if (previewReady) CollectionCaption("字节、路径、源码版本和两份原生回执已核对；下方显示实际回收文本。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { refresh() }, enabled = !busy) { Text("核对原回收回执") }
            if (blocking) TextButton(onClick = {
                controller.requestFeature("local", "cancelJob", JSONObject().put("jobId", collectionId).put("termux", true)); refresh()
            }, enabled = !busy) { Text("登记停止要求", color = Danger) }
        }
        if (blocking) CollectionCaption("不明、取消或尚未完成的回收只查询原 job，不会自动重试；停止要求不保证 Termux 进程已结束。")
        if (finished) TextButton(onClick = { selection = null; collectionId = ""; collected = null; previewReady = false; failureText = null; onPreview(null) }, enabled = !busy) { Text("选择另一个生成文件") }
    }
    confirmation?.let { frozen ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("只回收这个生成文件？") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(frozen.optString("path"), fontWeight = FontWeight.Medium)
                CollectionCaption("构建 ${frozen.optString("buildJobId")}\n源码 ${frozen.optString("sourceDigest")}\n最多 ${frozen.optInt("maxBytes")} 字节")
                CollectionCaption("通过原任务入口登记新的回收操作。只读取选定文件，不覆盖源码；回收完成后先显示文本，交互 HTML 由你另行打开。")
            }
        }, confirmButton = { Button(onClick = {
            try {
                val action = ProjectOwnerAction.reviewedCollection(frozen, build, frozen.getString("path"))
                selection = JSONObject(frozen.toString()); collected = null; previewReady = false; dispatchUnknown = true; onPreview(null)
                submit(action.getString("command"), action.getJSONObject("payload")); confirmation = null
            } catch (failure: Exception) { failureText = failure.message; confirmation = null }
        }, enabled = !busy && !blocking) { Text("确认这次回收") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
}

private fun collectionFinished(value: JSONObject?): Boolean {
    val code = value?.opt("exitCode")
    return value != null && value.optString("status") in setOf("completed", "failed") && (code is Int || code is Long) && value.opt("receiptReceived") == true && !value.optBoolean("cancelRequested")
}
private fun collectionSucceeded(value: JSONObject): Boolean = collectionFinished(value) && value.optString("status") == "completed" && (value.opt("exitCode") as Number).toLong() == 0L && value.opt("outputTruncated") == false
private fun collectionStatus(value: String) = when (value) { "completed" -> "已收到回收结果，继续核对字节"; "failed" -> "回收失败，保留原回执"; "unknown" -> "结果不明，仅核对"; "cancel_requested" -> "停止要求已登记，尚未确认终止"; "awaiting_result" -> "已派发，等待真实回执"; "running" -> "回收中"; "queued" -> "已登记"; else -> value }
@Composable private fun CollectionCaption(text: String) { if (text.isNotBlank()) Text(text, color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(vertical = 4.dp)) }
