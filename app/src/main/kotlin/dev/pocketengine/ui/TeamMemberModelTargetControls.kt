package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.runtime.models.ModelTargetRef
import org.json.JSONObject

/** Native settings references only. Selecting a route never starts a member or expands its scope. */
@Composable internal fun TeamMemberModelTargetControls(
    run: JSONObject, member: JSONObject, busy: Boolean, snapshot: () -> JSONObject,
    runWork: (Runnable) -> Unit, apply: (JSONObject) -> Unit
) {
    val identity = "${run.getString("id")}:${member.getString("id")}:${member.optInt("configRevision", 1)}"
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    var loading by remember(identity) { mutableStateOf(false) }
    var catalogue by remember(identity) { mutableStateOf<JSONObject?>(null) }
    var notice by remember(identity) { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(identity) { mutableStateOf("") }
    var model by rememberSaveable(identity) { mutableStateOf("") }
    var alive by remember(identity) { mutableStateOf(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(identity) { onDispose { alive = false } }
    fun load() {
        if (busy || loading) return
        loading = true; notice = null
        try { runWork(Runnable {
            val result = runCatching(snapshot)
            main.post { if (alive) { loading = false; result.fold({ catalogue = it }, { notice = it.message ?: "原连接暂不可读，未回退默认连接" }) } }
        }) } catch (_: Exception) { loading = false; notice = "原设置读取未开始，请重试" }
    }
    TextButton(onClick = { expanded = !expanded; if (expanded && catalogue == null) load() }) { Text(if (expanded) "收起明确模型选择" else "选择原模型连接 / 模型组") }
    if (!expanded) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val pin = member.optJSONObject("memberModelSelection")
        val current = member.optJSONObject("modelTarget") ?: pin?.optJSONObject("memberTarget")
        TeamCaption(when (current?.optString("kind")) {
            "exact" -> "当前准确连接 ${current.optString("connectionId")} · ${current.optString("modelId")}"
            "group" -> "当前模型组 ${current.optString("groupId")} · 修订 ${current.optLong("groupRevision")}"
            else -> "尚用本任务捕获的提供方 / 用途配置，不跟随后来的全局默认。"
        })
        TeamCaption("成员模型和整理历史的摘要模型分别固定。团队每个请求位只调用一个原已批准的可用候选；不在失败后额外重试，不扩大账户范围。切换会暂停原成员、使旧资料身份失效，需重审资料并明确继续；不追加次数、工具或期限。")
        catalogue?.let { data ->
            data.optJSONObject("connections")?.optJSONArray("connections").objects().filter { it.optBoolean("enabled") }.forEach { row ->
                val key = "exact:${row.getString("id")}"
                FilterChip(selected == key, { selected = key; model = row.optString("model") }, label = { Text("${row.optString("name")} · ${row.optString("model")}") }, enabled = !busy && !loading)
            }
            data.optJSONObject("nativeSelection")?.optJSONObject("nativeRef")?.let { account ->
                val key = "exact:account:${account.getString("accountId")}"
                FilterChip(selected == key, { selected = key; model = account.getString("modelId") }, label = { Text("原账号页已选模型 · ${account.getString("modelId")}") }, enabled = !busy && !loading)
            }
            data.optJSONObject("modelGroups")?.optJSONArray("groups").objects().forEach { group ->
                val key = "group:${group.getString("groupId")}:${group.getLong("revision")}"
                FilterChip(selected == key, { selected = key; model = "" }, label = { Text("${group.optString("name", group.getString("groupId"))} · r${group.getLong("revision")}") }, enabled = !busy && !loading)
            }
            if (selected.startsWith("exact:")) OutlinedTextField(model, { model = it }, label = { Text("该准确连接的模型 ID") }, singleLine = true, enabled = !busy && !loading && !selected.startsWith("exact:account:"), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                runCatching {
                    val reference = if (selected.startsWith("exact:")) ModelTargetRef.exact(selected.removePrefix("exact:"), model.trim()) else {
                        val group = data.getJSONObject("modelGroups").getJSONArray("groups").objects().single { "group:${it.getString("groupId")}:${it.getLong("revision")}" == selected }
                        ModelTargetRef.group(group.getString("groupId"), group.getLong("revision"))
                    }
                    apply(reference.toJson())
                }.onFailure { notice = it.message ?: "原模型选择未确认，未启动" }
            }, enabled = !busy && !loading && selected.isNotEmpty() && (!selected.startsWith("exact:") || model.isNotBlank() && model.length <= 200)) { Text("保存准确模型并暂停此成员") }
        }
        TextButton(onClick = ::load, enabled = !busy && !loading) { Text(if (loading) "正在读取原配置…" else "刷新原连接与组版本") }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
