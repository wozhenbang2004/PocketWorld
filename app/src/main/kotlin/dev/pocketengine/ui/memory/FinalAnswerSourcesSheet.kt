package dev.pocketengine.ui.memory

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.memory.FinalAnswerSources
import dev.pocketengine.runtime.context.CompactionPlan
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiMessage
import dev.pocketengine.ui.UiTask
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The selectors contain identities and hashes only; no answer/source body is republished. */
internal fun finalAnswerSourceSelection(message: UiMessage): JSONObject? = runCatching {
    if (message.role != "assistant" || message.content.isBlank()) return null
    val reference = message.memoryReference ?: return null
    val task = reference.getString("taskId")
    if (message.taskId != null && message.taskId != task) return null
    JSONObject().put("taskId", task).put("runId", reference.getString("runId"))
        .put("epoch", reference.getInt("coordinatorEpoch")).put("requestId", reference.getString("requestId"))
        .put("answerId", reference.getString("answerId")).put("answerFingerprint", CompactionPlan.fingerprint(message.content))
}.getOrNull()

internal fun finalAnswerSourceSelection(task: UiTask): JSONObject? = runCatching {
    val proof = task.raw.optJSONObject("evidence")?.optJSONObject("nativeFinalAnswer") ?: return null
    if (proof.optString("format") != "pocketworld.runner-final-answer.v1" || proof.optString("taskId") != task.id) return null
    JSONObject().put("taskId", task.id).put("runId", proof.getString("runId"))
        .put("epoch", proof.getInt("coordinatorEpoch")).put("answerFingerprint", proof.getString("answerFingerprint"))
}.getOrNull()

/** Every selection owns one cancellable read. Cancellation discards a late native callback even
 * if SQLite finishes after this sheet has closed, refreshed, or moved to another source. */
internal suspend fun readFinalAnswerSourceRequest(
    request: JSONObject, dispatch: (Runnable) -> Unit, read: (JSONObject, RunControl) -> JSONObject
): JSONObject =
    suspendCancellableCoroutine { continuation ->
        val control = RunControl()
        continuation.invokeOnCancellation { control.cancel() }
        try {
            dispatch(Runnable {
                try {
                    val response = read(request, control)
                    control.check()
                    checkedFinalAnswerSourceReceipt(request, response)
                    if (continuation.isActive) continuation.resume(response)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            })
        } catch (failure: Exception) {
            control.cancel()
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }

/** Shared native/Web receipt validation; no source URL or label can replace an original pin. */
internal fun checkedFinalAnswerSourceReceipt(request: JSONObject, response: JSONObject): JSONObject {
    check(response.optString("format") == FinalAnswerSources.FORMAT && response.optString("readId") == request.getString("readId")) { "原来源读取回执不属于这次选择" }
    val expected = request.getJSONObject("answer")
    val actual = response.getJSONObject("answer")
    expected.keys().forEach { key -> check(actual.has(key) && CompactionPlan.fingerprint(expected.get(key)) == CompactionPlan.fingerprint(actual.get(key))) { "原来源回执改变了回答身份或修订" } }
    if (response.optString("status") == "current" && request.has("source")) check(CompactionPlan.fingerprint(request.getJSONObject("source")) == CompactionPlan.fingerprint(response.getJSONObject("source").getJSONObject("pin"))) { "原来源回执不属于选中的来源修订" }
    return response
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinalAnswerSourcesSheet(controller: PocketWorldController, original: JSONObject, memoryReference: JSONObject? = null, close: () -> Unit) {
    val identity = original.toString()
    var answer by remember(identity) { mutableStateOf(JSONObject(identity)) }
    var selected by remember(identity) { mutableStateOf<JSONObject?>(null) }
    var cursor by remember(identity) { mutableIntStateOf(0) }
    var offset by remember(identity) { mutableIntStateOf(0) }
    var previousOffsets by remember(identity) { mutableStateOf(emptyList<Int>()) }
    var refresh by remember(identity) { mutableIntStateOf(0) }
    var originalMemory by remember(identity) { mutableStateOf(false) }
    val memoryRevision = controller.state.memoryRefreshRevision
    val selectedKey = selected?.toString().orEmpty()
    // Remember against the actual displayed request, so an old body disappears immediately on
    // source invalidation, before the replacement read starts on the worker.
    val request = remember(identity, selectedKey, cursor, offset, memoryRevision, refresh) {
        JSONObject().put("answer", JSONObject(answer.toString())).put("readId", UUID.randomUUID().toString())
            .put("cursor", cursor).put("offset", offset).put("maxChars", 8000).also { request -> selected?.let { request.put("source", JSONObject(it.toString())) } }
    }
    var result by remember(request) { mutableStateOf<JSONObject?>(null) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var loading by remember(request) { mutableStateOf(true) }
    LaunchedEffect(request) {
        try {
            val response = readFinalAnswerSourceRequest(request, controller::runPluginCatalogWork, controller::readFinalAnswerSources)
            result = response
            if (response.optString("status") == "current") answer = JSONObject(response.getJSONObject("answer").toString())
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "原来源暂不可读，可能已变化、清理或超过本次读取范围。没有使用当前页面或任务结果代替。" }
        finally { loading = false }
    }
    fun sourceList() { selected = null; offset = 0; previousOffsets = emptyList() }
    BackHandler { if (selected != null) sourceList() else close() }
    if (originalMemory && memoryReference != null) TaskMemoryWorkflowSheet(controller, memoryReference) { originalMemory = false }
    val listScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    LaunchedEffect(selectedKey, offset) { detailScroll.scrollTo(0) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { if (selected != null) sourceList() else close() }, modifier = Modifier.testTag("answer_sources_back")) { Text(if (selected != null) "返回来源列表" else "返回原回答") }
                Spacer(Modifier.weight(1f))
                if (selected != null) TextButton(onClick = close, modifier = Modifier.testTag("answer_sources_return_answer")) { Text("返回原回答") }
                else TextButton(onClick = { refresh++ }, enabled = !loading) { Text("刷新") }
            }
            Text(if (selected == null) "这次回答的来源" else "原保留内容", style = MaterialTheme.typography.titleLarge)
            Text("这里是该次回答前保留的来源与观察；不表示模型逐项采用，也不证明回答已经核实。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val native = result
            if (native != null && native.optString("status") != "current") Text(native.optString("reason", "原来源已不可读"), style = MaterialTheme.typography.bodyMedium)
            if (native != null && native.optString("status") == "current") {
                if (selected == null) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(listScroll).testTag("answer_sources_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (memoryReference != null) TextButton(onClick = { originalMemory = true }) { Text("本次提供的记忆与文本原件") }
                        val entries = native.optJSONArray("entries")
                        if (entries == null || entries.length() == 0) Text("这个保留窗口没有可准确关联的来源；没有从回答中的普通链接推测来源。", style = MaterialTheme.typography.bodyMedium)
                        for (index in 0 until (entries?.length() ?: 0)) {
                            val row = entries!!.getJSONObject(index)
                            Surface(tonalElevation = 0.dp, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().clickable(enabled = !loading && row.optString("status") == "available") {
                                    selected = JSONObject(row.getJSONObject("pin").toString()); offset = 0; previousOffsets = emptyList()
                                }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(row.optString("title", "原来源"), style = MaterialTheme.typography.titleSmall)
                                    row.optString("label").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                                    if (row.optString("status") != "available") Text(row.optString("reason", "此来源不可读"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    else Text("查看原保留范围", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Row {
                            if (cursor > 0) TextButton(onClick = { cursor = (cursor - 20).coerceAtLeast(0) }) { Text("上一组来源") }
                            if (native.optInt("nextCursor", -1) >= 0) TextButton(onClick = { cursor = native.getInt("nextCursor") }) { Text("下一组来源") }
                        }
                        Text("仅显示原 run 当前仍保留的记录，不等于完整历史。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val detail = native.optJSONObject("detail")
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(detailScroll).testTag("answer_sources_detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        detail?.optJSONObject("context")?.let { context ->
                            context.optString("title").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            context.optString("url").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (selected?.optString("kind") in setOf("phone_observation", "browser_observation")) {
                            val frame = native.optJSONObject("frame")
                            if (frame?.optString("status") == "retained") RetainedSourceFrame(frame)
                            else Text(frame?.optString("reason", "未保留对应屏帧") ?: "未保留对应屏帧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (detail != null) {
                            SelectionContainer { Text(detail.optString("body"), style = MaterialTheme.typography.bodyMedium) }
                            detail.optJSONObject("readRange")?.let { range -> Text("原保留文本 ${range.optInt("offset")}–${range.optInt("offset") + range.optInt("chars")} / ${range.optInt("totalChars")} 字符", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Row {
                                if (previousOffsets.isNotEmpty()) TextButton(onClick = { offset = previousOffsets.last(); previousOffsets = previousOffsets.dropLast(1) }) { Text("上一段") }
                                if (detail.optInt("nextOffset", -1) >= 0) TextButton(onClick = { previousOffsets += offset; offset = detail.getInt("nextOffset") }) { Text("下一段") }
                            }
                        }
                        var locator by remember(selectedKey) { mutableStateOf(false) }
                        TextButton(onClick = { locator = !locator }) { Text(if (locator) "收起来源定位" else "查看来源定位") }
                        if (locator) SelectionContainer { Text("task ${answer.optString("taskId")}\nrun ${answer.optString("runId")} · epoch ${answer.optInt("epoch")}\n${selected?.optString("sourceId")}\n修订 ${selected?.optString("sourceRevision")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RetainedSourceFrame(frame: JSONObject) {
    val bitmap = remember(frame.optString("sha256")) {
        runCatching {
            val bytes = Base64.decode(frame.getString("imageBase64"), Base64.NO_WRAP)
            val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000)
            val options = BitmapFactory.Options().also { it.inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1600).coerceAtLeast(1) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) Text("原屏帧无法解码，未使用其他截图代替", style = MaterialTheme.typography.bodySmall)
    else Image(bitmap, contentDescription = "与此原观察准确关联的保留屏帧", modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp))
}
