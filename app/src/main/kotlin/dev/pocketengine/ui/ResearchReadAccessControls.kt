package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.teams.TeamResearchReadScope
import org.json.JSONArray
import org.json.JSONObject

internal data class ResearchReadFileChoice(val projectId: String, val path: String, val projectName: String) {
    val key: String get() = "$projectId\u0000$path"
}

internal data class ResearchReadAccessDraft(val webOrigins: String = "", val files: List<ResearchReadFileChoice> = emptyList()) {
    fun selection(): JSONObject {
        require(webOrigins.length <= 8192) { "站点列表过长" }
        val sites = webOrigins.lines().map(String::trim).filter(String::isNotEmpty).map { it.removeSuffix("/") }
        require(sites.size <= 4 && sites.distinct().size == sites.size) { "最多选择四个不重复的公共 HTTPS 站点" }
        sites.forEach { require(TeamResearchReadScope.origin(it) == it) { "只填写站点地址，例如 https://example.org，不含页面路径" } }
        require(files.size <= 32 && files.map { it.key }.distinct().size == files.size) { "最多选择三十二个不重复的项目文件" }
        val picked = JSONArray()
        files.forEach { require(it.projectId.matches(Regex("[a-f0-9-]{36}"))) { "请从原项目列表选择文件" }; TeamResearchReadScope.path(it.path); picked.put(JSONObject().put("projectId", it.projectId).put("path", it.path)) }
        return JSONObject().put("webOrigins", JSONArray(sites)).put("projectFiles", picked)
    }
    fun error(): String? = runCatching { selection() }.exceptionOrNull()?.message
}

/** Uses the original member-material page's selection/review/approval and worker. */
@Composable
internal fun ResearchReadAccessControls(
    draft: ResearchReadAccessDraft,
    projects: JSONArray?,
    projectId: String?,
    files: JSONArray?,
    busy: Boolean,
    change: (ResearchReadAccessDraft) -> Unit,
    selectProject: (String) -> Unit
) {
    var projectMenu by remember { mutableStateOf(false) }
    val available = projects.objects()
    val projectName = available.firstOrNull { it.optString("id") == projectId }?.optString("name")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("成员可主动查阅的资料", style = MaterialTheme.typography.titleSmall)
        TeamCaption("默认不开放。网站只用匿名公共读取；项目只读本页明确选择的文字文件。实际版本会在下一步审阅中列出。")
        OutlinedTextField(draft.webOrigins, { if (it.length <= 8192) change(draft.copy(webOrigins = it)) },
            label = { Text("允许的公共 HTTPS 站点，每行一个，最多四个") },
            placeholder = { Text("https://example.org") }, enabled = !busy, minLines = 2,
            modifier = Modifier.fillMaxWidth())
        Box {
            OutlinedButton(onClick = { projectMenu = true }, enabled = !busy && available.isNotEmpty()) { Text(projectName ?: "从原项目选择文件") }
            DropdownMenu(projectMenu, { projectMenu = false }) {
                available.forEach { project -> DropdownMenuItem(text = { Text(project.optString("name")) }, onClick = { projectMenu = false; selectProject(project.getString("id")) }) }
            }
        }
        if (available.isEmpty()) TeamCaption("还没有可选择的原项目。")
        if (projectId != null && !busy && files?.length() == 0) TeamCaption("这个项目没有可分享的文字文件。")
        files.objects().forEach { file ->
            val path = file.getString("path")
            val choice = ResearchReadFileChoice(file.getString("projectId"), path, projectName ?: "原项目")
            val selected = draft.files.any { it.key == choice.key }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, { next -> change(draft.copy(files = if (next) draft.files + choice else draft.files.filterNot { it.key == choice.key })) }, enabled = !busy && (selected || draft.files.size < 32))
                Column { Text(path); TeamCaption("当前版本 ${file.optInt("version")} · ${file.optLong("sizeBytes")} 字节") }
            }
        }
        if (draft.files.isNotEmpty()) {
            TeamCaption("已选 ${draft.files.size} 个文件；切换项目不会自动共享其他文件。")
            draft.files.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${file.projectName} / ${file.path}", Modifier.weight(1f))
                    TextButton(onClick = { change(draft.copy(files = draft.files.filterNot { it.key == file.key })) }, enabled = !busy) { Text("移除") }
                }
            }
        }
        draft.error()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun ResearchReadAccessReview(scope: JSONObject?) {
    if (scope == null) return
    val origins = scope.optJSONArray("webOrigins")
    val files = scope.optJSONArray("projectFiles")
    if ((origins?.length() ?: 0) == 0 && (files?.length() ?: 0) == 0) return
    TeamCaption("只读研究范围：匿名公共站点 ${origins?.length() ?: 0} 个，原项目文件 ${files?.length() ?: 0} 个。账号和登录状态不共享。")
    for (i in 0 until (origins?.length() ?: 0)) Text(origins!!.getString(i))
    files.objects().forEach { file -> TeamCaption("${file.getString("path")} · 版本 ${file.getInt("version")} · 内容 ${file.getString("sha256").take(12)} · ${file.getLong("sizeBytes")} 字节") }
}
