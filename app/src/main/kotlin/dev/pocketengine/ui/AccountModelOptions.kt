package dev.pocketengine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.runtime.models.AccountCatalogFacts
import org.json.JSONObject

/** Read-only catalog facts plus explicit owner options; the native workflow fetches and validates again. */
@Composable
internal fun AccountModelOptions(
    model: JSONObject,
    reference: JSONObject,
    selected: JSONObject?,
    current: Boolean,
    onSelect: (JSONObject) -> Unit,
    onDismiss: () -> Unit
) {
    val key = reference.toString()
    val metadata = remember(key, model.toString()) { runCatching { AccountCatalogFacts.fromJson(model.getJSONObject("catalogFacts")) }.getOrNull() }
    val sameSelection = selected?.optJSONObject("nativeRef")?.let { native ->
        native.optString("accountId") == reference.optString("accountId") && native.optLong("accountVersion") == reference.optLong("accountVersion") && native.optString("modelId") == reference.optString("modelId")
    } == true
    var effort by remember(key) { mutableStateOf(if (sameSelection) selected?.optJSONObject("reasoning")?.optString("level").orEmpty() else "") }
    var output by remember(key) { mutableStateOf((if (sameSelection) selected?.optInt("maxOutputTokens", 4096) ?: 4096 else 4096).toString()) }
    var image by remember(key) { mutableStateOf((if (sameSelection) selected?.optInt("imageTokenReserve", 0) ?: 0 else 0).toString()) }
    var menu by remember(key) { mutableStateOf(false) }
    var failure by remember(key) { mutableStateOf<String?>(null) }
    val levels = metadata?.facts?.reasoningLevels()?.takeIf { it.known }?.value.orEmpty()
    val outputValue = output.toIntOrNull()
    val imageValue = image.toIntOrNull()
    val valid = metadata != null && current && outputValue != null && outputValue in 128..32768 && imageValue != null && imageValue in 0..2000000 && (effort.isEmpty() || effort in levels)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(model.optString("displayName", reference.optString("modelId"))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val window = metadata?.facts?.contextWindow()
                val limit = metadata?.facts?.outputTokens()
                Text(if (window?.known == true) "当前账号目录上下文：${window.value.tokens()} tokens" else "上下文：目录未声明，保留本机字节安全上限")
                Text(if (limit?.known == true) "目录最大输出：${limit.value} tokens" else "最大输出：未知，不按模型名称补值")
                val modalities = metadata?.inputModalities
                Text(if (modalities?.known == true) "目录输入：${modalities.value.joinToString(" / ")}；本应用只发送已接入且获准的类型" else "输入类型：目录未声明；图片仍需独立核对")
                TextButton(onClick = { menu = true }, enabled = metadata != null) { Text("推理设置：${effort.ifEmpty { "服务默认（不额外发送）" }}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("服务默认（不额外发送）") }, onClick = { effort = ""; menu = false })
                    levels.forEach { level -> DropdownMenuItem(text = { Text(level) }, onClick = { effort = level; menu = false }) }
                }
                OutlinedTextField(value = output, onValueChange = { if (it.length <= 8) output = it }, label = { Text("本地输出预留 · 128–32768") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("这是输入准入的本地预留，不会发送 max_output_tokens，也不能限制服务端输出。目录有最大输出时，准入至少预留该容量。")
                OutlinedTextField(value = image, onValueChange = { if (it.length <= 8) image = it }, label = { Text("每张图片本地预留 · 0表示未配置") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("图片预留不是服务商实际计量。已知上下文但预留为0时，不发送图片；文本仍可用。")
                Text("这些事实只属于这个账号及授权版本。刷新、换号或模型移除后重新核对；模型选择不代表推理或工具权限。")
                if (!current) Text("账号或目录已变化，请关闭后重新读取。", color = Danger)
                failure?.let { Text(it, color = Danger) }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = {
            try {
                onSelect(JSONObject(reference.toString()).put("reasoningEffort", effort).put("outputReserve", outputValue).put("imageTokenReserve", imageValue))
                onDismiss()
            } catch (_: Exception) { failure = "选择未提交，请重新读取当前目录后再试。" }
        }) { Text("选择此模型和选项") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回目录") } }
    )
}
