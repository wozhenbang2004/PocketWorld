package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Native workspace catalog identity. This view object grants no task or file execution authority. */
internal data class UiWorkspaceArtifact(
    val id: String, val projectId: String, val path: String, val version: Int,
    val revisionId: String, val sha256: String, val mimeType: String,
    val taskId: String?, val runId: String?
) {
    fun matches(raw: JSONObject) = from(raw) == this
    companion object {
        private val uuid = Regex("[a-f0-9-]{36}")
        fun from(raw: JSONObject): UiWorkspaceArtifact? {
            val id = raw.optString("id"); val project = raw.optString("projectId"); val path = raw.optString("path")
            val revision = raw.optString("sourceRevisionId"); val sha = raw.optString("sha256")
            if (!uuid.matches(id) || !uuid.matches(project) || !uuid.matches(revision) || !Regex("[a-f0-9]{64}").matches(sha) || raw.optInt("sourceVersion") < 1 || raw.optString("kind") != "text_file") return null
            try { dev.pocketengine.features.workspace.VersionedWorkspace.path(path) } catch (_: IllegalArgumentException) { return null }
            val task = raw.optString("taskId"); val run = raw.optString("runId")
            if (raw.optString("source") == "task_workspace") {
                if (!uuid.matches(task) || !uuid.matches(run)) return null
            } else if (raw.optString("source") != "owner_workspace" || task.isNotBlank() || run.isNotBlank()) return null
            return UiWorkspaceArtifact(id, project, path, raw.getInt("sourceVersion"), revision, sha, raw.optString("mimeType"), task.takeIf(String::isNotBlank), run.takeIf(String::isNotBlank))
        }
    }
}

internal data class UiWorkspaceArtifactDiff(val text: String, val version: Int, val revisionId: String, val truncated: Boolean, val readAt: Long)
internal data class UiWorkspaceArtifactView(val source: UiWorkspaceArtifact, val fromTaskId: String? = null, val content: String? = null, val loading: Boolean = true, val error: String? = null, val diff: UiWorkspaceArtifactDiff? = null, val diffLoading: Boolean = false, val diffError: String? = null)
internal data class UiArtifactEditorOrigin(val source: UiWorkspaceArtifact, val fromTaskId: String?)
internal data class UiProjectTaskReturn(val projectId: String, val taskId: String)

internal fun workspaceArtifacts(state: PocketUiState, taskId: String? = null, projectId: String? = null): List<UiWorkspaceArtifact> =
    state.feature("workspace").optJSONArray("artifacts").objects().mapNotNull(UiWorkspaceArtifact::from)
        .filter { (taskId == null || it.taskId == taskId) && (projectId == null || it.projectId == projectId) }
        .distinctBy { it.id }

@Composable
internal fun WorkspaceArtifactRow(artifact: UiWorkspaceArtifact, controller: PocketWorldController, fromTaskId: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(artifact.path, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("固定成果 v${artifact.version} · " + if (artifact.taskId != null) "来自原任务" else "由你保存", fontSize = 11.sp, color = SecondaryInk)
        TextButton(onClick = { controller.openWorkspaceArtifact(artifact, fromTaskId) }, modifier = Modifier.testTag("workspace_artifact_" + artifact.id)) { Text("查看成果与来源", fontSize = 12.sp) }
    }
}

@Composable
internal fun WorkspaceArtifactHeader(state: PocketUiState, controller: PocketWorldController) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("已保存成果", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = controller::refreshWorkspaceArtifacts, enabled = "workspace.listArtifacts" !in state.moduleBusy) { Text("重新读取列表", fontSize = 11.sp) }
    }
    Text(if (state.workspaceArtifactsReadAt > 0) "本机固定文件 · 列表读取于 " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(state.workspaceArtifactsReadAt)) else "本机保存的固定文件；不代表执行端仍在线。", fontSize = 11.sp, color = SecondaryInk)
    state.moduleErrors["workspace.listArtifacts"]?.let { Text("成果列表未更新，保留上次读取内容：$it", color = Danger, fontSize = 12.sp, lineHeight = 20.sp) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceArtifactSheet(view: UiWorkspaceArtifactView, state: PocketUiState, controller: PocketWorldController) {
    val source = view.source
    ModalBottomSheet(onDismissRequest = controller::closeWorkspaceArtifact, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = androidx.compose.ui.graphics.Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding()) {
            TextButton(onClick = controller::closeWorkspaceArtifact, modifier = Modifier.testTag("workspace_artifact_return")) { Text(if (state.filePath != null) "返回编辑区" else if (view.fromTaskId != null) "返回原任务" else "返回项目") }
            Text("已保存成果", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(source.path, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            Text((state.projects.firstOrNull { it.id == source.projectId }?.name ?: "原项目") + " · 固定版本 v${source.version}", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 6.dp))
            Text("这是保存时的固定文件。源文件之后的修改不会替换这份成果。", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk, modifier = Modifier.padding(top = 8.dp))
            Text("这里对比并修改已保存的项目版本。运行副本请回原项目核对，不会自动同步或执行。", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk, modifier = Modifier.padding(top = 6.dp))
            if (view.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            view.error?.let {
                Text(it, color = Danger, fontSize = 13.sp, lineHeight = 21.sp)
                if (state.filePath != null) TextButton(onClick = controller::closeWorkspaceArtifact) { Text("回到现有编辑区") }
                TextButton(onClick = { controller.openWorkspaceArtifact(source, view.fromTaskId) }) { Text("重新读取原成果") }
            }
            view.content?.let { content ->
                SelectionContainer { Text(content, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState()).padding(vertical = 16.dp).testTag("workspace_artifact_content")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = controller::editWorkspaceArtifactSource, modifier = Modifier.testTag("workspace_artifact_edit_source")) { Text("继续修改源文件") }
                    TextButton(onClick = controller::compareWorkspaceArtifact, enabled = !view.diffLoading) { Text(if (view.diff == null) "比较当前源文件" else "重新读取差异") }
                }
                if (view.diffLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                view.diffError?.let { Text(it, color = Danger, fontSize = 12.sp, lineHeight = 20.sp) }
                view.diff?.let { comparison ->
                    Text("与源文件 v${comparison.version} 的差异", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
                    Text("读取于 " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(comparison.readAt)) + "；不是实时文件视图。", fontSize = 11.sp, color = SecondaryInk)
                    if (comparison.text.isEmpty()) Text("这次读取的源文件内容与成果相同。", fontSize = 12.sp)
                    else SelectionContainer { Text(comparison.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp).testTag("workspace_artifact_diff")) }
                    if (comparison.truncated) Text("差异超过显示上限；请打开源文件核对完整内容。", fontSize = 12.sp, color = Danger)
                }
                Row { TextButton(onClick = { controller.focusWorkspaceArtifactProject() }) { Text("查看原项目") }; TextButton(onClick = { controller.exportArtifact(source.id) }, enabled = !state.exportPending) { Text("导出这份成果") } }
            }
            Text("源版本 ${source.revisionId.take(8)} · SHA256 ${source.sha256.take(12)}", fontSize = 10.sp, color = SecondaryInk, modifier = Modifier.padding(top = 10.dp, bottom = 22.dp))
        }
    }
}
