package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ModelConnectionService
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Existing native model selection in a temporary surface; no task, inference or draft is created. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerModelPicker(
    service: ModelConnectionService,
    worker: (Runnable) -> Unit,
    changed: () -> Unit,
    onManage: () -> Unit,
    onAccounts: () -> Unit,
    onDismiss: () -> Unit
) {
    var state by remember(service) { mutableStateOf(JSONObject()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val alive = remember(service) { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(service) { onDispose { alive.set(false) } }
    fun execute(close: Boolean = false, action: () -> JSONObject) {
        if (busy || !alive.get()) return
        busy = true; error = null
        try { worker(Runnable {
            val result = runCatching(action)
            main.post {
                if (alive.get()) {
                    busy = false
                    result.fold({ state = it; changed(); if (close) onDismiss() }, {
                        error = if (it is IllegalArgumentException || it is IllegalStateException || it is SecurityException) it.message else "模型选择未能保存，请重新读取当前连接。"
                    })
                }
            }
        }) } catch (_: Exception) { busy = false; error = "本机工作线程暂不可用，请稍后重新打开。" }
    }
    LaunchedEffect(service) { execute { service.connections(); service.modelPickerSnapshot() } }
    val models = state.optJSONArray("models")
    val rows = (0 until (models?.length() ?: 0)).mapNotNull { models?.optJSONObject(it) }
        .filter { query.isBlank() || it.optString("modelId").contains(query, true) || it.optString("connectionName").contains(query, true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("选择下一任务的模型", style = MaterialTheme.typography.titleLarge)
            Text("只用于下一个新聊天或手机任务；在途任务及其连续补充保持原模型。关闭此面板会保留输入内容、附件与引用。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, { query = it }, label = { Text("搜索模型或连接") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (!state.optBoolean("preferencesAvailable", true) || state.optBoolean("recentUseSaveFailed")) Text("收藏或最近记录暂不可保存；原记录保留，模型选择仍可用。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(rows, key = { it.optString("connectionId") + "\u0000" + it.optString("modelId") }) { row ->
                    val available = row.optBoolean("available")
                    val binding = row.optJSONObject("binding")?.let(ModelBinding::fromJson)
                    Row(Modifier.fillMaxWidth().clickable(enabled = available && !busy && binding != null) {
                        execute(true) { service.selectPickerModel(binding!!, row.getLong("connectionRevision"), row.getString("modelId")) }
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.optString("modelId") + if (row.optBoolean("selected")) " · 当前" else "", style = MaterialTheme.typography.titleSmall)
                            Text(row.optString("connectionName") + " · " + row.optString("provider"), style = MaterialTheme.typography.bodySmall)
                            Text(if (!available) pickerUnavailableReason(row.optString("unavailableReason")) else "图片：${pickerCapability(row.optJSONObject("imageInput"))} · 工具：${pickerCapability(row.optJSONObject("toolCalls"))}", style = MaterialTheme.typography.labelSmall)
                            if (row.optLong("lastUsedAt") > 0) Text("最近成功调用：${modelTime(row.optLong("lastUsedAt"))}", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(enabled = !busy && state.optBoolean("preferencesAvailable", true) && (available || row.optBoolean("favorite")), onClick = {
                            execute {
                                if (row.optBoolean("favorite")) service.removeModelFavorite(row.getString("connectionId"), row.getString("modelId"))
                                else service.favoriteModel(binding!!, row.getLong("connectionRevision"), row.getString("modelId"), true)
                            }
                        }) { Text(if (row.optBoolean("favorite")) "已收藏" else "收藏") }
                    }
                    HorizontalDivider()
                }
            }
            if (rows.isEmpty() && !busy) Text(if (query.isBlank()) "没有已保存的 API 模型，可先添加连接。" else "没有匹配的模型。", modifier = Modifier.padding(vertical = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAccounts, enabled = !busy) { Text("ChatGPT 套餐模型") }
                TextButton(onClick = onManage, enabled = !busy) { Text("管理连接与能力") }
            }
        }
    }
}

internal fun pickerCapability(value: JSONObject?) = if (value?.optBoolean("known") == true) { if (value.optBoolean("value")) "支持" else "不支持" } else "未知"
internal fun pickerUnavailableReason(reason: String) = when (reason) {
    "connection_disabled" -> "连接已停用，请先核对并启用。"
    "catalog_model_unavailable" -> "原目录引用已过期或不可用；请刷新，或在连接中明确手填。"
    "model_reference_not_supported" -> "此模型 ID 无法用于现有用途引用；请在原连接页选择。"
    "catalog_protocol_mismatch" -> "目录声明使用其他接口协议；请在原连接页核对协议或手填模型。"
    else -> "原连接不可用，未自动替换。"
}
