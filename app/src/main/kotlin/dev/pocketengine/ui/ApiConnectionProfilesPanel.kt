package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pocketengine.core.ModelConfig
import org.json.JSONObject

/** Native owner settings only. A saved connection is not a successful provider capability test. */
@Composable
internal fun ApiConnectionProfilesPanel(snapshot: JSONObject, busy: Boolean, operationError: String?,
    save: (String, Long, String, JSONObject, () -> Unit) -> Unit,
    select: (String, Long) -> Unit,
    enable: (String, Long, Boolean) -> Unit) {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    val rows = snapshot.optJSONArray("connections")
    val selected = snapshot.optString("selectedConnectionId")
    Text("API 连接", style = MaterialTheme.typography.titleMedium)
    Text("保存多个连接，明确选择下一次任务使用哪项。不会自动换号或重发；已有任务保留原连接，停用或更换凭据后原代次不可继续使用。", style = MaterialTheme.typography.bodySmall)
    if (rows == null || rows.length() == 0) Text("尚无已保存的 API 连接。新增并保存后，再明确设为主模型。", style = MaterialTheme.typography.bodyMedium)
    if (rows != null) for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val id = row.optString("id")
        val enabled = row.optBoolean("enabled")
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.optString("name") + if (id == selected) " · 保留选择" else "", style = MaterialTheme.typography.titleSmall)
            Text("${row.optString("provider")} · ${row.optString("model")}", style = MaterialTheme.typography.bodySmall)
            Text(row.optString("baseUrl"), style = MaterialTheme.typography.bodySmall)
            Text(if (!enabled) "已停用 · 不会发送此连接的请求" else if (row.optBoolean("catalogEnabled")) "目录严格核对已启用 · 能力与来源见下方" else "手动配置 · 尚未启用目录核对", style = MaterialTheme.typography.bodySmall)
            ConnectionInferenceStatus(row.optJSONObject("inferenceObservation"), row.optJSONObject("binding")?.toString().orEmpty() + "|" + row.optString("model"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editing = JSONObject(row.toString()) }, enabled = !busy) { Text("编辑") }
                TextButton(onClick = { enable(id, row.optLong("revision"), !enabled) }, enabled = !busy) { Text(if (enabled) "停用" else "启用") }
                OutlinedButton(onClick = { select(id, row.optLong("revision")) }, enabled = !busy && enabled) { Text("设为主 API 模型") }
            }
        }
        HorizontalDivider()
    }
    TextButton(onClick = { adding = true }, enabled = !busy && (rows?.length() ?: 0) < snapshot.optInt("maximumConnections", 32)) { Text("新增 API 连接") }
    if (adding || editing != null) {
        val original = editing
        ApiConnectionEditor(original, busy, operationError, { adding = false; editing = null }) { name, values ->
            save(original?.optString("id").orEmpty(), original?.optLong("revision") ?: 0L, name, values) { adding = false; editing = null }
        }
    }
}

@Composable
private fun ApiConnectionEditor(original: JSONObject?, busy: Boolean, operationError: String?, dismiss: () -> Unit, save: (String, JSONObject) -> Unit) {
    val config = original?.optJSONObject("configuration") ?: JSONObject()
    var name by remember(original?.optString("id")) { mutableStateOf(original?.optString("name").orEmpty()) }
    var provider by remember { mutableStateOf(config.optString("provider", ModelConfig.DEFAULT_PROVIDER)) }
    var url by remember { mutableStateOf(config.optString("baseUrl")) }
    var model by remember { mutableStateOf(config.optString("model")) }
    var key by remember { mutableStateOf("") }
    var clearKey by remember { mutableStateOf(false) }
    var cleartext by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val remoteHttp = ModelConfig.cleartextConsentFor(provider, url.trim())
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (original == null) "新增 API 连接" else "编辑原 API 连接") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("只保存连接，不会发起目录或推理请求。保存成功不等于鉴权、工具或图片能力已验证。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("连接名称") }, singleLine = true)
            listOf("openai_chat" to "OpenAI Chat", "openai_responses" to "OpenAI Responses", "anthropic" to "Anthropic", "gemini" to "Gemini").forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(provider == value, { provider = value; cleartext = false }); Text(label) }
            }
            OutlinedTextField(url, { url = it; cleartext = false }, label = { Text("推理接口 Base URL") }, singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("明确填写模型 ID") }, singleLine = true)
            OutlinedTextField(key, { key = it; clearKey = false }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                supportingText = { Text(if (original?.optBoolean("hasApiKey") == true) "留空仅保留同协议、同 origin 的原密钥；改 origin 不会迁移原密钥。" else "留空只适用于无需 key 的可信接口；不会从其他连接借用密钥。") })
            if (original?.optBoolean("hasApiKey") == true) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clearKey, { clearKey = it; if (it) key = "" }); Text("不再使用此连接的 API key（移除引用）") }
            if (remoteHttp.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cleartext, { cleartext = it }); Text("我确认此 HTTP 目标会明文传输凭据与请求内容，建议改用 HTTPS。", style = MaterialTheme.typography.bodySmall) }
            if (original?.optBoolean("catalogEnabled") == true) Text("保留严格目录核对。更改 key、协议或地址将使旧目录能力失效，需重新刷新，不会退回未经目录核对的模式。", style = MaterialTheme.typography.bodySmall)
            (problem ?: operationError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        if (name.isBlank() || url.isBlank() || model.isBlank()) { problem = "请填写连接名称、接口地址与模型 ID。"; return@TextButton }
        if (remoteHttp.isNotEmpty() && !cleartext) { problem = "请明确确认此 HTTP 目标，或改用 HTTPS。"; return@TextButton }
        problem = null
        save(name.trim(), JSONObject().put("provider", provider).put("baseUrl", url.trim()).put("model", model.trim()).put("apiKey", key).put("clearApiKey", clearKey)
            .put("cleartextConsent", if (cleartext) remoteHttp else ""))
    }) { Text("保存连接") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("保留原配置") } })
}
