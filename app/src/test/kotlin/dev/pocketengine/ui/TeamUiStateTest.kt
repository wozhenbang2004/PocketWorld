package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeamUiStateTest {
    private fun slot(runner: String = "model") = JSONObject().put("id", "member-0").put("name", "甲").put("role", "核对资料").put("runner", runner).put("device", JSONObject().put("profileId", "profile-a").put("environmentId", "env-a"))
    private fun team(member: JSONObject) = JSONObject().put("id", "team-a").put("members", JSONArray().put(member))

    @Test fun savedDefinitionsNeverBecomeExecutionHistory() {
        val definition = team(slot())
        assertFalse(isTeamRunResult("save", definition))
        assertFalse(isTeamRunResult("probeMember", definition.put("goal", "same-looking response")))
        assertTrue(isTeamRunResult("get", definition))
        assertFalse(isTeamRunResult("get", JSONObject().put("goal", "missing run identity").put("members", JSONArray())))
    }

    @Test fun codexRequiresActualReceiptBoundToExactProbedSlot() {
        val member = slot("codex_app_server")
        val definition = team(member)
        val state = PocketUiState(baseUrl = "https://model.example/v1", modelName = "default")
        assertFalse(teamLaunchable(definition, state))
        val proof = JSONObject().put("slot", member.toString()).put("receipt", JSONObject().put("available", true))
        val verified = state.copy(teamMemberProbes = mapOf("team-a:member-0" to proof))
        assertTrue(teamLaunchable(definition, verified))
        assertFalse(teamLaunchable(team(JSONObject(member.toString()).put("model", "changed")), verified))
        assertFalse(teamLaunchable(team(JSONObject(member.toString()).put("device", JSONObject().put("profileId", "other").put("environmentId", "env-a"))), verified))
        assertFalse(teamLaunchable(definition, verified.copy(teamMemberProbes = mapOf("team-a:member-0" to JSONObject(proof.toString()).put("receipt", JSONObject().put("available", false))))))
    }

    @Test fun unsupportedRunnersCannotBorrowModelAvailability() {
        val configured = PocketUiState(baseUrl = "https://model.example/v1", modelName = "default")
        assertTrue(teamLaunchable(team(slot()), configured))
        assertFalse(teamLaunchable(team(slot("claude")), configured))
        assertFalse(teamLaunchable(team(slot("kimi")), configured))
        assertFalse(teamLaunchable(team(slot()), PocketUiState()))
    }

    @Test fun lateGenerationOrOlderSnapshotCannotReplaceCurrentMemberState() {
        val current = JSONObject().put("generation", 3).put("updatedAt", 100).put("status", "paused")
        assertSame(current, mergeTeamSnapshot(current, JSONObject().put("generation", 2).put("updatedAt", 200).put("status", "running")))
        assertSame(current, mergeTeamSnapshot(current, JSONObject().put("generation", 3).put("updatedAt", 90).put("status", "running")))
        val newer = mergeTeamSnapshot(current, JSONObject().put("generation", 4).put("updatedAt", 95).put("status", "queued"))
        assertEquals("queued", newer.getString("status"))
    }

    @Test fun taskNavigationUsesDurableRelationsAndNeverParsesIdsFromText() {
        assertTrue(teamRunReferences(taskFromJson(JSONObject().put("id", "phone-a").put("kind", "phone").put("summary", "team-id in model prose"))).isEmpty())
        val task = taskFromJson(JSONObject().put("id", "parent-a").put("relatedTasks", JSONArray().put(JSONObject().put("kind", "teams").put("runId", "team-task-a"))))
        assertEquals(listOf("team-task-a"), teamRunReferences(task))
    }
    @Test fun revisionWinsOverClockAndContinuationIsARunButPlanIsNot() {
        val current = JSONObject().put("id", "run-a").put("generation", 3).put("revision", 12).put("updatedAt", 100).put("status", "paused")
        assertSame(current, mergeTeamSnapshot(current, JSONObject(current.toString()).put("revision", 11).put("updatedAt", 200).put("status", "running")))
        assertEquals("running", mergeTeamSnapshot(current, JSONObject(current.toString()).put("revision", 13).put("updatedAt", 90).put("status", "running")).getString("status"))
        val run = JSONObject().put("id", "run-a").put("goal", "协作").put("members", JSONArray())
        assertTrue(isTeamRunResult("continueMessages", run))
        assertFalse(isTeamRunResult("savePlan", run))
    }

    @Test fun dependencyLabelsNeverUseBusinessCompleted() {
        assertEquals("等待前置分工", teamMemberStatus("waiting_dependency"))
        assertEquals("前置未完成，已阻断", teamMemberStatus("blocked_dependency"))
        assertEquals("部分分工未完成", teamMemberStatus("partial"))
        assertNotEquals(taskStatus("completed"), teamMemberStatus("completed"))
    }

    @Test fun receiptSelectionNeverBorrowsAnotherRunOrDefinitionsAcknowledgement() {
        val state = PocketUiState(teamOwnerReceipts = mapOf(
            "run-a-request" to JSONObject().put("requestId", "run-a-request").put("command", "feedback").put("runId", "run-a").put("ok", false),
            "run-b-request" to JSONObject().put("requestId", "run-b-request").put("command", "resume").put("runId", "run-b").put("ok", true),
            "plan-a-request" to JSONObject().put("requestId", "plan-a-request").put("command", "savePlan").put("teamId", "team-a").put("ok", true)
        ))
        assertEquals(setOf("run-a-request"), teamOwnerReceipts(state, runId = "run-a").keys)
        assertEquals(setOf("plan-a-request"), teamOwnerReceipts(state, teamId = "team-a").keys)
        assertEquals(setOf("run-b-request", "plan-a-request"), teamOwnerReceipts(state, "run-b", "team-a").keys)
        assertTrue(teamOwnerReceipts(state, "missing", "missing").isEmpty())
        assertNotEquals(taskStatus("completed"), teamRunStatus("completed"))
    }

    @Test fun claudeSelectionKeepsRiskChoiceAndDoesNotBorrowCodexProbe() {
        val selected = AgentSlotDraft("member-0", "Claude", "Review", "", "claude", "computer-a", "env-a", externalExecutionRiskAcknowledged = true).json()
        assertTrue(selected.getBoolean("externalExecutionRiskAcknowledged"))
        assertEquals("claude", selected.getString("runner"))
        val receipt = JSONObject().put("slot", selected.toString()).put("receipt", JSONObject().put("available", true).put("cliFlagsVerified", true).put("protocolVerified", false))
        val state = PocketUiState(baseUrl = "https://model.example/v1", modelName = "default", teamMemberProbes = mapOf("team-a:member-0" to receipt))
        assertTrue(teamLaunchable(team(selected), state))
        assertFalse(teamLaunchable(team(JSONObject(selected.toString()).put("runner", "codex_app_server")), state))
        assertEquals("Claude Code", teamRunnerLabel("claude"))
    }

    @Test fun configuredHarnessRequiresItsOwnVerifiedProtocolAndExactModelModeBinding() {
        val selected = AgentSlotDraft("member-0", "电脑成员", "Review", "reported-model", "acp_owner_agent", "computer-a", "env-a",
            externalExecutionRiskAcknowledged = true, modeId = "normal").json()
        assertEquals("normal", selected.getString("modeId"))
        val proof = JSONObject().put("slot", selected.toString()).put("receipt", JSONObject().put("available", true).put("protocolVerified", false))
        val unverified = PocketUiState(teamMemberProbes = mapOf("team-a:member-0" to proof))
        assertFalse(teamLaunchable(team(selected), unverified))
        val verified = unverified.copy(teamMemberProbes = mapOf("team-a:member-0" to JSONObject(proof.toString()).put("receipt", JSONObject().put("available", true).put("protocolVerified", true))))
        assertTrue(teamLaunchable(team(selected), verified))
        assertFalse(teamLaunchable(team(JSONObject(selected.toString()).put("modeId", "different")), verified))
        assertFalse(teamLaunchable(team(JSONObject(selected.toString()).put("runner", "muse_msp")), verified))
        assertFalse(teamLaunchable(team(JSONObject(selected.toString()).put("externalExecutionRiskAcknowledged", false)), verified))
    }

    @Test fun configuredEditorReadinessRequiresCurrentDirectoryAndNativeSelectionContract() {
        val worker = JSONObject().put("id", "acp_owner_agent").put("configurationRevision", "a".repeat(64))
        val probe = JSONObject().put("worker", "acp_owner_agent").put("available", true).put("protocolVerified", true)
            .put("protocol", "acp_v1").put("executionPolicy", "owner_managed")
            .put("configurationRevision", "a".repeat(64)).put("capabilityRevision", "b".repeat(64))
        assertTrue(nativeHarnessProbeReady(probe, worker))
        assertFalse(nativeHarnessProbeReady(probe, JSONObject(worker.toString()).put("configurationRevision", "c".repeat(64))))
        assertFalse(nativeHarnessProbeReady(JSONObject(probe.toString()).put("protocolVerified", false), worker))
        assertFalse(nativeHarnessProbeReady(JSONObject(probe.toString()).put("protocol", "muse_msp_v1"), worker))
        assertFalse(nativeHarnessProbeReady(JSONObject(probe.toString()).put("worker", "acp_other"), worker))
        assertFalse(nativeHarnessProbeReady(JSONObject(probe.toString()).put("capabilityRevision", "unknown"), worker))
        assertTrue(externalTeamHarness("acp_custom_harness"))
        assertTrue(externalTeamHarness("muse_msp"))
        assertTrue(externalTeamHarness("pi"))
        assertFalse(externalTeamHarness("gemini_acp"))
        assertFalse(externalTeamHarness("acp_9invalid"))
        assertFalse(dev.pocketengine.features.remote.RemoteHarnessSelection.isProductionWorker("pi"))
    }

    @Test fun usableAccountModelCanLaunchWithoutAnApiUrl() {
        val account = PocketUiState(modelRoute = "account", modelName = "account-model", accountModelAvailable = true)
        assertTrue(account.baseUrl.isEmpty())
        assertTrue(teamLaunchable(team(slot()), account))
        assertTrue(teamLaunchable(team(slot().put("model", "account-model")), account))
        assertFalse(teamLaunchable(team(slot().put("model", "another-model")), account))
    }

    @Test fun accountRouteNeverBorrowsApiUrlOrAnUnavailableNativeModel() {
        val account = PocketUiState(modelRoute = "account", modelName = "account-model", accountModelAvailable = true)
        assertFalse(teamLaunchable(team(slot()), account.copy(accountModelAvailable = false)))
        assertFalse(teamLaunchable(team(slot()), account.copy(accountModelAvailable = false, baseUrl = "https://old.example/v1")))
        assertFalse(teamLaunchable(team(slot()), account.copy(modelName = "")))
        assertFalse(teamLaunchable(team(slot()), account.copy(modelRoute = "invalid", baseUrl = "https://old.example/v1")))
        assertFalse(teamLaunchable(team(slot()), account.copy(modelRoute = "api_key")))
        assertFalse(teamLaunchable(team(slot()), PocketUiState(baseUrl = "https://model.example/v1")))
    }

    @Test fun accountReadinessKeepsProduceAndExactRemoteProbeGates() {
        val local = slot()
        val remote = slot("codex_app_server").put("id", "remote")
        val mixed = team(local).put("members", JSONArray().put(local).put(remote))
        val account = PocketUiState(modelRoute = "account", modelName = "account-model", accountModelAvailable = true)
        assertFalse(teamLaunchable(mixed, account))
        val proof = JSONObject().put("slot", remote.toString()).put("receipt", JSONObject().put("available", true))
        val verified = account.copy(teamMemberProbes = mapOf("team-a:remote" to proof))
        assertTrue(teamLaunchable(mixed, verified))
        assertFalse(teamLaunchable(mixed, verified.copy(accountModelAvailable = false)))
        assertFalse(teamLaunchable(JSONObject(mixed.toString()).put("mode", "produce"), verified))
        assertFalse(teamLaunchable(JSONObject(mixed.toString()).put("members", JSONArray()), verified))
        assertFalse(teamLaunchable(team(slot("kimi")), verified))
    }

}
