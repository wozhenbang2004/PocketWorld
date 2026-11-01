package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.runtime.models.ModelTargetRef
import org.json.JSONArray
import org.json.JSONObject

/** References the original connections. Saving a group does not send a request or copy a key. */
@Composable
internal fun ModelGroupsPanel(snapshot: JSONObject, connections: JSONObject, busy: Boolean,
    save: (String, Long, String, JSONObject, () -> Unit) -> Unit,
    select: (String, ModelTargetRef?) -> Unit) {
    val groups = jsonRows(snapshot.optJSONArray("groups"))
    val bindings = snapshot.optJSONObject("purposeBindings") ?: JSONObject()
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    HorizontalDivider()
    Text("按用途选择模型", style = MaterialTheme.typography.titleSmall)
    Text("模型组只引用已有连接。候选按顺序筛选；运行开始后冻结，不随默认选择漂移。摘要未单独选择时使用启动时的原单连接。本地服务需独立选择和受众许可，不继承主任务组。", style = MaterialTheme.typography.bodySmall)
    listOf("MAIN" to "主任务", "SUMMARY" to "摘要", "RELAY" to "本地服务").forEach { (purpose, title) ->
        val ref = bindings.optJSONObject(purpose)
        Text("$title：${if (ref == null) "启动时的单连接" else ref.optString("groupId", ref.optString("modelId")) + " · v" + ref.optLong("groupRevision")}", style = MaterialTheme.typography.bodySmall)
        if (ref != null) TextButton(enabled = !busy, onClick = { select(purpose, null) }) { Text("$title 改回单连接") }
    }
    TextButton(enabled = !busy, onClick = { expanded = !expanded }) { Text(if (expanded) "收起模型组" else "管理模型组 · ${groups.size} 组") }
    if (expanded) {
        if (groups.isEmpty()) Text("从下方已有 API 连接中选择候选，创建第一组。未配置的账号不会自动加入。", style = MaterialTheme.typography.bodySmall)
        groups.forEach { group ->
            Text(group.optString("name"), style = MaterialTheme.typography.titleSmall)
            val candidates = jsonRows(group.optJSONArray("candidates"))
            Text(candidates.joinToString(" → ") { it.optJSONObject("target")?.optString("modelId").orEmpty() }, style = MaterialTheme.typography.bodySmall)
            val policy = group.optJSONObject("attemptPolicy") ?: JSONObject()
            Text("修订 ${group.optLong("revision")} · 最多 ${policy.optInt("maxAttempts", 1)} 次尝试 / ${policy.optInt("maxRouteSwitches")} 次切换", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy, onClick = { select("MAIN", ModelTargetRef.group(group.getString("groupId"), group.getLong("revision"))) }) { Text("用于主任务") }
                TextButton(enabled = !busy, onClick = { select("SUMMARY", ModelTargetRef.group(group.getString("groupId"), group.getLong("revision"))) }) { Text("用于摘要") }
                TextButton(enabled = !busy, onClick = { editing = group }) { Text("编辑") }
            }
            TextButton(enabled = !busy && candidates.any { it.optJSONArray("allowedAudiences")?.toString()?.contains("EXTERNAL_RELAY") == true }, onClick = { select("RELAY", ModelTargetRef.group(group.getString("groupId"), group.getLong("revision"))) }) { Text("用于本地服务（需重启服务）") }
            HorizontalDivider()
        }
        TextButton(enabled = !busy && jsonRows(connections.optJSONArray("connections")).isNotEmpty(), onClick = { creating = true }) { Text("从已有连接创建模型组") }
    }
    if (creating || editing != null) {
        val original = editing
        ModelGroupEditor(original, connections, busy, { creating = false; editing = null }) { name, definition ->
            save(original?.optString("groupId").orEmpty(), original?.optLong("revision") ?: 0L, name, definition) { creating = false; editing = null }
        }
    }
}

@Composable
private fun ModelGroupEditor(original: JSONObject?, connections: JSONObject, busy: Boolean, close: () -> Unit, save: (String, JSONObject) -> Unit) {
    var name by remember(original) { mutableStateOf(original?.optString("name").orEmpty()) }
    var chosen by remember(original) { mutableStateOf(jsonRows(original?.optJSONArray("candidates"))) }
    var attempts by remember(original) { mutableIntStateOf(original?.optJSONObject("attemptPolicy")?.optInt("maxAttempts", 1) ?: 1) }
    var switches by remember(original) { mutableStateOf(original?.optJSONObject("attemptPolicy")?.optInt("maxRouteSwitches", 0) == 1) }
    var external by remember(original) { mutableStateOf(chosen.any { it.optJSONArray("allowedAudiences")?.toString()?.contains("EXTERNAL_RELAY") == true }) }
    val profiles = jsonRows(connections.optJSONArray("connections"))
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(if (original == null) "新模型组" else "编辑模型组") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("用途名称，例如：编码") }, singleLine = true, enabled = !busy)
            Text("候选 ${chosen.size}/8 · 上方优先。供主任务、摘要和成员明确选择，只引用原连接，不复制凭据。", style = MaterialTheme.typography.bodySmall)
            chosen.forEachIndexed { index, candidate ->
                val target = candidate.getJSONObject("target")
                Text("${index + 1}. ${target.optString("modelId")}", style = MaterialTheme.typography.bodyMedium)
                Text(target.optString("connectionId"), style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(enabled = !busy && index > 0, onClick = { chosen = chosen.toMutableList().also { java.util.Collections.swap(it, index, index - 1) } }) { Text("上移") }
                    TextButton(enabled = !busy && index + 1 < chosen.size, onClick = { chosen = chosen.toMutableList().also { java.util.Collections.swap(it, index, index + 1) } }) { Text("下移") }
                    TextButton(enabled = !busy, onClick = { chosen = chosen.filterIndexed { i, _ -> i != index } }) { Text("移除") }
                }
            }
            profiles.forEach { profile ->
                val id = profile.optString("connectionId", profile.optString("id"))
                val binding = profile.optJSONObject("binding")
                val exactId = binding?.optString("connectionId") ?: id
                val model = profile.optString("model")
                if (chosen.none { it.getJSONObject("target").optString("connectionId") == exactId && it.getJSONObject("target").optString("modelId") == model })
                    TextButton(enabled = !busy && chosen.size < 8 && profile.optBoolean("enabled") && model.isNotBlank(), onClick = {
                        chosen = chosen + JSONObject().put("candidateId", "candidate-${java.util.UUID.randomUUID()}").put("target", ModelTargetRef.exact(exactId, model).toJson()).put("enabled", true)
                    }) { Text("加入 ${profile.optString("name", model)} · $model") }
            }
            TextButton(enabled = !busy, onClick = { attempts = if (attempts == 3) 1 else attempts + 1 }) { Text("最多 $attempts 次物理尝试 · 点按调整") }
            Row { Checkbox(switches, { switches = it }, enabled = !busy && attempts > 1); Text("瞬时故障时允许切换一次候选", style = MaterialTheme.typography.bodySmall) }
            Row { Checkbox(external, { external = it }, enabled = !busy); Text("允许本地服务使用此 API 组（仍需独立选择）", style = MaterialTheme.typography.bodySmall) }
            Text("401/403/额度/政策拒绝不换号；输出开始后不拼接第二模型。每次尝试都记用量，未知不按0；仍受原任务预算与总期限限制。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && chosen.isNotEmpty(), onClick = {
            val accounts = JSONArray(); val ids = mutableSetOf<String>(); val rows = JSONArray()
            chosen.forEach { candidate ->
                val row = JSONObject(candidate.toString()); val id = row.getJSONObject("target").getString("connectionId")
                if (ids.add(id)) accounts.put(id)
                row.put("allowedPurposes", JSONArray(if (external) listOf("MAIN", "SUMMARY", "MEMBER", "RELAY") else listOf("MAIN", "SUMMARY", "MEMBER")))
                row.put("allowedAudiences", JSONArray(if (external) listOf("APP_OWNER", "EXTERNAL_RELAY") else listOf("APP_OWNER")))
                rows.put(row)
            }
            save(name.trim(), JSONObject().put("allowedAccounts", JSONObject().put("mode", "EXPLICIT").put("refs", accounts)).put("candidates", rows).put("attemptPolicy", JSONObject().put("maxAttempts", attempts).put("maxRouteSwitches", if (attempts > 1 && switches) 1 else 0)))
        }) { Text(if (busy) "保存中…" else "保存组（不发请求）") } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("取消") } })
}

private fun jsonRows(value: JSONArray?): List<JSONObject> = if (value == null) emptyList() else (0 until value.length()).mapNotNull { value.optJSONObject(it) }
