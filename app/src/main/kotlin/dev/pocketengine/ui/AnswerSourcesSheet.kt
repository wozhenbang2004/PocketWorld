package dev.pocketengine.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.util.Base64
import org.json.JSONObject

/** This modal keeps the underlying answer, list anchor and composer draft mounted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnswerSourcesSheet(anchor: JSONObject, read: (JSONObject, Int, (JSONObject?, String?) -> Unit) -> Unit, frame: (JSONObject, (JSONObject?, String?) -> Unit) -> Unit, controller: PocketWorldController, back: () -> Unit) {
    var selected by remember(anchor.toString()) { mutableIntStateOf(-1) }
    var result by remember(anchor.toString()) { mutableStateOf<JSONObject?>(null) }
    var rawCitation by remember { mutableStateOf<JSONObject?>(null) }
    var picture by remember { mutableStateOf<String?>(null) }
    var structuredOpen by remember(selected) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }; var alive by remember(anchor.toString()) { mutableStateOf(true) }
    DisposableEffect(anchor.toString()) { controller.enterOwnerDomain("taskMemory"); onDispose { alive = false; generation++; controller.leaveOwnerDomain("taskMemory") } }
    fun returnToList() { selected = -1; result = null; rawCitation = null; picture = null; generation++; loading = false; error = null }
    BackHandler { if (rawCitation != null) rawCitation = null else if (selected >= 0) returnToList() else back() }
    ModalBottomSheet(onDismissRequest = back, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { if (rawCitation != null) rawCitation = null else if (selected >= 0) returnToList() else back() }) { Text(if (rawCitation != null) "返回这项来源" else if (selected >= 0) "返回本次来源" else "返回原回答") }
            Text("回答保存的来源", style = MaterialTheme.typography.titleLarge)
            Text("原任务 ${anchor.optString("taskId")}\n原轮次 ${anchor.optString("runId")}\n回答 ${anchor.optString("answerId")}", style = MaterialTheme.typography.labelSmall)
            Text("这里读取当时保留的文本和观察；可读来源不证明答案已核实。未保存的历史画面不会用当前屏幕补造。", style = MaterialTheme.typography.bodySmall)
            if (anchor.optBoolean("coverageLimited")) Text("这份来源清单有覆盖上限，未列全的来源不等于不存在。", style = MaterialTheme.typography.bodySmall)
            if (rawCitation != null) {
                dev.pocketengine.ui.memory.TaskMemoryRawCitationPanel(anchor.getString("taskId"), anchor.getString("runId"), rawCitation!!, controller.state.ownerResponses("taskMemory"), { command, payload -> controller.requestOwnerDomain("taskMemory", command, payload) }, { task -> back(); controller.focusTask(task) }, { rawCitation = null }, controller.state.memoryRefreshRevision)
            } else if (selected < 0) {
                val entries = anchor.optJSONArray("entries")
                if (entries == null || entries.length() == 0) Text("这条回答没有保留可定位的网页或观察清单；原记忆来源仍可从消息菜单核对。")
                for (i in 0 until (entries?.length() ?: 0)) {
                    val entry = entries!!.getJSONObject(i)
                    TextButton(onClick = {
                        selected = i; loading = true; error = null; val request = ++generation
                        read(anchor, i) { value, failure -> if (alive && request == generation) { result = value; error = failure; loading = false } }
                    }, modifier = Modifier.fillMaxWidth()) { Text("${i + 1} · ${when (entry.optString("type")) { "browser_source_retained" -> "保存的网页观察"; "observation" -> "手机观察"; else -> "精确原文引用" }} · 原事件 ${entry.optLong("eventOrder")}") }
                }
            } else result?.let { value ->
                if (!value.optBoolean("available")) Text("原来源已改变、清理或暂不可读。没有回落到新版本。")
                else {
                    Text(value.optString("title", "原来源范围"), style = MaterialTheme.typography.titleMedium)
                    if (value.optString("url").isNotBlank()) Text(value.getString("url"), style = MaterialTheme.typography.bodySmall)
                    value.optJSONObject("sourceIdentity")?.let { source -> Text("来源 ${source.optString("sourceId")} · 观察 ${source.optString("observationId")} · 文档 ${source.optString("documentId")}", style = MaterialTheme.typography.labelSmall) }
                    if (value.has("body")) SelectionContainer { Text(value.getString("body"), style = MaterialTheme.typography.bodyMedium) }
                    value.optJSONObject("structuredProjection")?.let { fields -> TextButton(onClick = { structuredOpen = !structuredOpen }) { Text(if (structuredOpen) "收起当时保存的结构化范围" else "查看当时保存的结构化范围") }; if (structuredOpen) SelectionContainer { Text(fields.toString(2), style = MaterialTheme.typography.bodySmall) } }
                    value.optJSONObject("range")?.let { range -> Text("UTF-16字符范围 ${range.optInt("offset")} + ${range.optInt("chars")} / ${range.optInt("totalChars")}", style = MaterialTheme.typography.labelSmall) }
                    val jumps = value.optJSONArray("jumps")
                    for (i in 0 until (jumps?.length() ?: 0)) {
                        val jump = jumps!!.getJSONObject(i)
                        TextButton(onClick = { rawCitation = JSONObject(jump.toString()) }) { Text("读取原文 ${i + 1} · ${jump.optString("sourceId")}") }
                    }
                    val savedFrame = value.optJSONObject("frame")
                    if (savedFrame != null && picture == null) TextButton(onClick = { loading = true; val request = ++generation; frame(savedFrame) { returned, failure -> if (alive && request == generation) { picture = returned?.optString("dataUrl")?.takeIf { returned.optBoolean("available") && it.isNotEmpty() }; error = failure ?: if (picture == null) "该观察的原图已清理或未保留。" else null; loading = false } } }) { Text("打开同一观察的保存图") }
                    else if (savedFrame == null) Text("历史画面未保留", style = MaterialTheme.typography.bodySmall)
                    picture?.let { encoded -> val bitmap = remember(encoded) { runCatching { val bytes = Base64.getDecoder().decode(encoded.substringAfter(",")); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }; bitmap?.let { Image(it.asImageBitmap(), "原观察保存图", Modifier.fillMaxWidth()) } }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(18.dp))
        }
    }
}
