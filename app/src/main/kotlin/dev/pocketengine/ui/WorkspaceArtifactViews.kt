package dev.pocketengine.ui

import org.json.JSONObject

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
