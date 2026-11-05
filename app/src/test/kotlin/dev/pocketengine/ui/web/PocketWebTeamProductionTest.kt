package dev.pocketengine.ui.web

import dev.pocketengine.features.remote.RemoteProjectAssignments
import dev.pocketengine.features.teams.AgentSlot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** These exercise the original host normalizer plus the Web source fence; no execution grants are fabricated. */
class PocketWebTeamProductionTest {
    private val taskId = "11111111-1111-4111-8111-111111111111"
    private val snapshotId = "22222222-2222-4222-8222-222222222222"
    private val packageId = "33333333-3333-4333-8333-333333333333"
    private fun capabilities() = JSONObject().put("writeAssignmentsConfigured", true).put("selectedFileRemoteProductionConfigured", true)
    private fun limits() = JSONObject().put("maxWallTimeSeconds", 120).put("maxOutputBytes", 65536).put("maxContributionBytes", 131072)
    private fun pin() = JSONObject().put("id", packageId).put("sha256", "a".repeat(64)).put("manifestIdentity", "b".repeat(64))
    private fun artifact() = pin().put("kind", "project_bundle").put("mode", "source_project").put("projectId", "project-a").put("versionSequence", 3).put("fileCount", 2)
    private fun task() = JSONObject().put("id", taskId).put("goal", "原电脑任务").put("actorId", "owner").put("spaceId", "private")
        .put("remoteResultSnapshots", JSONArray().put(JSONObject().put("snapshotId", snapshotId).put("snapshotVersion", "revision-a").put("status", "completed")
            .put("source", JSONObject().put("profileId", "computer-a").put("environmentId", "workspace-a"))
            .put("files", JSONArray().put(JSONObject().put("path", "src/main.ts").put("text", "private source body")))))
    private fun sources() = PocketWebTeamProduction.sources(JSONArray().put(artifact()), listOf(task()))
    private fun remote(full: Boolean = false): JSONObject {
        val selection = JSONObject().put("limits", limits()).put("internalInferenceBillingUnboundedAcknowledged", true)
        if (full) selection.put("mode", RemoteProjectAssignments.MODE).put("packageReference", pin()).put("allowCommands", true)
            .put("sameUserExecutionRiskAcknowledged", true).put("privateSourceTransferAcknowledged", true)
        else selection.put("sourceTaskId", taskId).put("snapshotId", snapshotId)
        return JSONObject().put("id", "author").put("name", "作者").put("role", "修改原文件").put("runner", "codex_app_server")
            .put("device", JSONObject().put("profileId", "computer-a").put("environmentId", "workspace-a"))
            .put("writeFiles", JSONArray().put("src/main.ts")).put("remoteProduction", selection)
    }
    private fun validate(member: JSONObject = remote(), shown: JSONObject = sources(), current: JSONObject = sources(), flags: JSONObject = capabilities()) {
        PocketWebTeamProduction.validateDefinition(JSONArray().put(AgentSlot.normalize(member, 0)), flags, shown, current)
    }

    @Test fun catalogContainsOnlyOriginalPrivateCompletedSnapshotsAndNonRetiredSourcePackagesWithoutBodies() {
        val other = task().put("spaceId", "group")
        val failed = task().also { it.getJSONArray("remoteResultSnapshots").getJSONObject(0).put("status", "failed") }
        val value = PocketWebTeamProduction.sources(JSONArray().put(artifact()).put(artifact().put("availability", "retired")), listOf(task(), other, failed))
        assertEquals(1, value.getJSONArray("packages").length()); assertEquals(1, value.getJSONArray("snapshots").length())
        assertEquals("src/main.ts", value.getJSONArray("snapshots").getJSONObject(0).getJSONArray("files").getString(0))
        assertFalse(value.toString().contains("private source body"))
    }

    @Test fun originalSourceAndCompletePackageBothPassExistingHostNormalizerAndReadModelFence() {
        validate(); validate(remote(true))
        val member = AgentSlot.normalize(remote(true), 0)
        assertEquals(pin().toString(), member.getJSONObject("remoteProduction").getJSONObject("packageReference").toString())
    }

    @Test fun selectedSnapshotCannotBorrowAnotherComputerEnvironmentTaskOrUnshownSource() {
        for (key in listOf("profileId", "environmentId")) {
            val changed = remote(); changed.getJSONObject("device").put(key, "changed")
            assertThrows(IllegalArgumentException::class.java) { validate(changed) }
        }
        val changed = remote(); changed.getJSONObject("remoteProduction").put("sourceTaskId", packageId)
        assertThrows(IllegalArgumentException::class.java) { validate(changed) }
        assertThrows(IllegalArgumentException::class.java) { validate(shown = JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) { validate(current = JSONObject()) }
        val revised = sources(); revised.getJSONArray("snapshots").getJSONObject(0).put("snapshotVersion", "revision-b")
        assertThrows(IllegalArgumentException::class.java) { validate(current = revised) }
    }

    @Test fun completePackageIsPinnedToTheShownAndStillPresentExactHashAndManifest() {
        for (key in listOf("sha256", "manifestIdentity")) {
            val changed = remote(true); changed.getJSONObject("remoteProduction").getJSONObject("packageReference").put(key, "c".repeat(64))
            assertThrows(IllegalArgumentException::class.java) { validate(changed) }
        }
        val retired = PocketWebTeamProduction.sources(JSONArray().put(artifact().put("availability", "retired")), listOf(task()))
        assertThrows(IllegalArgumentException::class.java) { validate(remote(true), current = retired) }
    }

    @Test fun unsupportedHostsAndOverlappingFileRangesCannotReachSaveReview() {
        for (key in listOf("writeAssignmentsConfigured", "selectedFileRemoteProductionConfigured"))
            assertThrows(IllegalArgumentException::class.java) { validate(flags = capabilities().put(key, false)) }
        val local = JSONObject().put("id", "local").put("name", "检查").put("role", "检查").put("runner", "model").put("writeFiles", JSONArray().put("src/main.ts"))
        val members = JSONArray().put(AgentSlot.normalize(remote(), 0)).put(AgentSlot.normalize(local, 1))
        assertThrows(IllegalArgumentException::class.java) { PocketWebTeamProduction.validateDefinition(members, capabilities(), sources(), sources()) }
        val unsafe = remote().put("writeFiles", JSONArray().put("../escape"))
        assertThrows(IllegalArgumentException::class.java) { validate(unsafe) }
    }

    @Test fun originalHostStillRejectsAuthorityInjectionMissingConsentAndOverBudgetLimits() {
        val injected = remote(); injected.getJSONObject("remoteProduction").put("grantId", "forged")
        assertThrows(SecurityException::class.java) { validate(injected) }
        val excessive = remote(); excessive.getJSONObject("remoteProduction").getJSONObject("limits").put("maxContributionBytes", 524289)
        assertThrows(SecurityException::class.java) { validate(excessive) }
        for (key in listOf("allowCommands", "sameUserExecutionRiskAcknowledged", "privateSourceTransferAcknowledged", "internalInferenceBillingUnboundedAcknowledged")) {
            val missing = remote(true); missing.getJSONObject("remoteProduction").put(key, false)
            assertThrows(SecurityException::class.java) { validate(missing) }
        }
    }
}
