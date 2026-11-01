package dev.pocketengine.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import dev.pocketengine.features.memory.SourceRef

/** Native owner file selection only. It opens the existing exact-source preview and model-use
 * confirmation; constructing an address never sends text or grants a member/model any read. */
@Composable
internal fun WorkspaceMemorySourceControl(projectId: String, path: String, state: PocketUiState,
    controller: PocketWorldController, offset: Int = 0, chars: Int = 24000) {
    val sourceId = remember(projectId, path, offset, chars) {
        runCatching { SourceRef.workspaceSourceId(projectId, path, offset, chars) }.getOrNull()
    }
    var open by remember(sourceId) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = sourceId != null) { Text("从当前文件范围提炼记忆候选") }
    if (open && sourceId != null) SourceLearningDialog("workspace_file", sourceId, state, controller) { open = false }
}
