package dev.pocketengine.ui.web

import dev.pocketengine.features.remote.RemoteHarnessSelection
import dev.pocketengine.features.remote.RemoteProjectAssignments
import dev.pocketengine.features.workspace.VersionedWorkspace
import org.json.JSONArray
import org.json.JSONObject

/** Readable source references only. Actual job, command and write authority stay in the original hosts. */
internal object PocketWebTeamProduction {
    private fun objects(value: JSONArray?): List<JSONObject> = (0 until (value?.length() ?: 0)).mapNotNull { value?.optJSONObject(it) }

    fun sources(artifacts: JSONArray?, tasks: List<JSONObject>): JSONObject {
        val packages = JSONArray()
        objects(artifacts).filter {
            it.optString("kind") == "project_bundle" && it.optString("mode") == "source_project" && it.optString("availability") != "retired"
        }.forEach { artifact ->
            packages.put(JSONObject().also { out ->
                listOf("id", "sha256", "manifestIdentity", "projectId", "versionSequence", "fileCount").forEach { key -> out.put(key, artifact.opt(key)) }
            })
        }
        val snapshots = JSONArray()
        tasks.filter { it.optString("actorId", "owner") == "owner" && it.optString("spaceId", "private") == "private" }.forEach { task ->
            objects(task.optJSONArray("remoteResultSnapshots")).filter { it.optString("status") == "completed" }.forEach snapshot@{ snapshot ->
                val source = snapshot.optJSONObject("source") ?: return@snapshot
                if (source.optString("profileId").isNotBlank() && source.optString("environmentId").isNotBlank()) {
                    snapshots.put(JSONObject().put("sourceTaskId", task.optString("id")).put("snapshotId", snapshot.optString("snapshotId"))
                        .put("goal", task.optString("goal").take(160)).put("profileId", source.optString("profileId"))
                        .put("environmentId", source.optString("environmentId")).put("snapshotVersion", snapshot.optString("snapshotVersion"))
                        .put("files", JSONArray(objects(snapshot.optJSONArray("files")).map { it.optString("path") })))
                }
            }
        }
        return JSONObject().put("packages", packages).put("snapshots", snapshots)
    }

    /** Both the shown catalog and the current native read model must still contain the exact source. */
    fun validateDefinition(members: JSONArray, capabilities: JSONObject, shownSources: JSONObject, currentSources: JSONObject) {
        require(capabilities.opt("writeAssignmentsConfigured") == true) { "当前宿主尚未接入项目文件分工，请刷新团队页" }
        val used = mutableSetOf<String>()
        objects(members).forEach { member ->
            val files = member.getJSONArray("writeFiles")
            require(files.length() in 1..8) { "每名成员需要一至八个负责文件" }
            for (index in 0 until files.length()) {
                require(files.opt(index) is String) { "负责文件必须是项目相对路径" }
                val path = VersionedWorkspace.path(files.getString(index))
                require(used.add(path)) { "不同成员不能同时负责同一文件：$path" }
            }
            val runner = member.optString("runner")
            if (runner == "model") {
                require(member.opt("device") == "phone" && !member.has("remoteProduction")) { "本机模型成员不能继承电脑制作范围" }
            } else {
                require(capabilities.opt("selectedFileRemoteProductionConfigured") == true && RemoteHarnessSelection.isProductionWorker(runner)) { "请选择已接入的电脑文件制作执行器" }
                val device = member.getJSONObject("device")
                val selection = member.getJSONObject("remoteProduction")
                if (RemoteProjectAssignments.full(selection)) {
                    val pin = selection.getJSONObject("packageReference")
                    fun selected(sources: JSONObject) = objects(sources.optJSONArray("packages")).singleOrNull { source ->
                        listOf("id", "sha256", "manifestIdentity").all { source.optString(it) == pin.optString(it) }
                    }
                    require(selected(shownSources) != null && selected(currentSources) != null) { "完整源项目包已变化或不在原目录，请刷新后重新核对" }
                } else {
                    fun selected(sources: JSONObject) = objects(sources.optJSONArray("snapshots")).singleOrNull { source ->
                        listOf("sourceTaskId", "snapshotId").all { source.optString(it) == selection.optString(it) }
                            && listOf("profileId", "environmentId").all { source.optString(it) == device.optString(it) }
                    }
                    val shown = selected(shownSources); val current = selected(currentSources)
                    require(shown != null && current != null && shown.optString("snapshotVersion") == current.optString("snapshotVersion")
                        && shown.optJSONArray("files")?.toString() == current.optJSONArray("files")?.toString()) { "原文件快照或电脑环境已变化，请刷新后重新核对" }
                }
            }
        }
    }
}
