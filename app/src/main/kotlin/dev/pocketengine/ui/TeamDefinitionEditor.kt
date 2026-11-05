package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentSlotDraft(val id: String, val name: String, val role: String, val model: String, val runner: String, val profileId: String = "", val environmentId: String = "", val writeFiles: String = "", val sourceTaskId: String = "", val snapshotId: String = "", val wallSeconds: String = "120", val outputBytes: String = "65536", val contributionBytes: String = "131072", val remoteBillingAcknowledged: Boolean = false, val fullProject: Boolean = false, val packageId: String = "", val packageSha: String = "", val packageManifest: String = "", val remoteCommandsAcknowledged: Boolean = false, val sourceTransferAcknowledged: Boolean = false, val externalExecutionRiskAcknowledged: Boolean = false, val modeId: String = "", val phoneDevice: String = "", val phoneAssignment: String = "", val phoneDependencies: List<String> = emptyList()) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("role", role).put("model", model).put("runner", runner).put("device", if (runner == "model") "phone" else if (runner == "phone_agent" && phoneDevice.isNotBlank()) JSONObject(phoneDevice) else JSONObject().put("profileId", profileId).put("environmentId", environmentId)).also {
        if(externalTeamHarness(runner) && runner != "codex_app_server")it.put("externalExecutionRiskAcknowledged",externalExecutionRiskAcknowledged)
        if(externalTeamHarness(runner) && modeId.isNotBlank())it.put("modeId",modeId)
        if(runner == "phone_agent") { it.put("assignment", phoneAssignment); it.put("dependsOn", JSONArray(phoneDependencies)) }
        if(writeFiles.isNotBlank())it.put("writeFiles",JSONArray(writeFiles.lines().map { it.trim() }.filter { it.isNotBlank() }))
        if(externalTeamHarness(runner)&&validRemoteLimits()&&remoteBillingAcknowledged) {
            val limits = JSONObject().put("maxWallTimeSeconds", wallSeconds.toInt()).put("maxOutputBytes", outputBytes.toInt()).put("maxContributionBytes", contributionBytes.toInt())
            if (fullProject && validRemoteSource()) it.put("remoteProduction", JSONObject().put("mode", dev.pocketengine.features.remote.RemoteProjectAssignments.MODE).put("packageReference", JSONObject().put("id", packageId).put("sha256", packageSha).put("manifestIdentity", packageManifest)).put("limits", limits).put("allowCommands", true).put("sameUserExecutionRiskAcknowledged", remoteCommandsAcknowledged).put("privateSourceTransferAcknowledged", sourceTransferAcknowledged).put("internalInferenceBillingUnboundedAcknowledged", true))
            else if (!fullProject && sourceTaskId.isNotBlank() && snapshotId.isNotBlank()) it.put("remoteProduction", JSONObject().put("sourceTaskId", sourceTaskId).put("snapshotId", snapshotId).put("limits", limits).put("internalInferenceBillingUnboundedAcknowledged", true))
        }
    }
    fun validRemoteSource(): Boolean = if (fullProject) packageId.isNotBlank() && packageSha.matches(Regex("[a-f0-9]{64}")) && packageManifest.matches(Regex("[a-f0-9]{64}")) && remoteCommandsAcknowledged && sourceTransferAcknowledged else sourceTaskId.isNotBlank() && snapshotId.isNotBlank()
    fun validRemoteLimits(): Boolean { val wall=wallSeconds.toIntOrNull()?:return false;val output=outputBytes.toIntOrNull()?:return false;val contribution=contributionBytes.toIntOrNull()?:return false;return wall in 10..900&&output in 1024..196608&&contribution in 1024..(if(fullProject)16777216 else 524288) }
}

internal fun teamLaunchable(team: JSONObject, state: PocketUiState): Boolean = team.optString("mode") != "produce" && team.optJSONArray("members").objects().let { members -> members.isNotEmpty() && members.all { member ->
    when (member.optString("runner", "model")) {
        "model" -> state.modelName.isNotBlank() && when (state.modelRoute) {
            "account" -> state.accountModelAvailable && member.optString("model").let { it.isBlank() || it == state.modelName }
            "api_key" -> state.baseUrl.isNotBlank()
            else -> false
        }
        "phone_agent" -> false // Phone starts use the per-member scope review in the phone workspace.
        else -> {
            val runner = member.optString("runner")
            val saved = state.teamMemberProbes[team.optString("id") + ":" + member.optString("id")]
            externalTeamHarness(runner) && saved?.optString("slot") == member.toString() && saved.optJSONObject("receipt")?.optBoolean("available") == true
                && (!configuredTeamHarness(runner) || saved.optJSONObject("receipt")?.optBoolean("protocolVerified") == true)
                && (runner == "codex_app_server" || member.optBoolean("externalExecutionRiskAcknowledged"))
        }
    }
} }
