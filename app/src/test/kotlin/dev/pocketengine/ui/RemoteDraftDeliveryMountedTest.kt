package dev.pocketengine.ui

import android.app.Application
import dev.pocketengine.features.remote.RemoteTaskDrafts
import dev.pocketengine.ui.web.PocketWebRemoteTasks
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production Web projection delegates original input capability and scope to native rules.
 * Local editor/save/send payloads and stale UI closures execute the production React component in remote-task-workspace.test.ts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class RemoteDraftDeliveryMountedTest {
    private fun source(worker: String, protocol: String) = JSONObject().put("taskId", "task").put("runId", "run").put("coordinatorEpoch", 1).put("profileId", "computer").put("serviceBase", "https://fixture.invalid").put("environmentId", "workspace")
        .put("worker", worker).put("protocol", protocol).put("protocolVerified", true).put("cancelRequested", false).put("jobId", "job").put("jobOperationId", "operation")
        .put("threadId", "session").put("sessionId", "session").put("turnId", "turn").put("executorStatus", "running").put("controls", JSONObject().put("inputSteering", true).put("threadResume", false).put("cancel", true)).apply { if (worker == "claude") put("controlProtocolVerified", true).put("sessionIdentityConfirmed", true).put("promptRequestId", "prompt") }
    private fun pi(settled: Boolean) = source("pi", "pi_rpc_v1").put("noTools", true).put("promptRequestId", "prompt").apply {
        remove("turnId")
        if (settled) { put("executorStatus", "completed").put("nativeSettled", true).put("resourcesHeld", false).put("processCleanupProof", JSONObject().put("processGroupStoppedVerified", true)); getJSONObject("controls").put("threadResume", true) }
    }
    private fun configured(worker: String) = source(worker, if (worker == "muse_msp") "muse_msp_v1" else "acp_v1")
        .put("promptRequestId", "prompt").put("executorStatus", "completed").put("nativeSettled", true)
        .put("promptTerminalConfirmed", true).put("resumeSupported", true)
        .put("harnessSelection", JSONObject().put("protocol", "pocketworld.harness-selection.v1").put("workerId", worker)
            .put("configurationRevision", "b".repeat(64)).put("capabilityRevision", "c".repeat(64)).put("ownerManagedExecution", true))
        .apply { getJSONObject("controls").put("threadResume", true).put("inputSteering", false) }
    private fun snapshot(source: JSONObject, canContinue: Boolean, queued: String? = null, savedSource: JSONObject = source): JSONObject {
        val drafts = JSONArray()
        if (queued != null) drafts.put(JSONObject().put("id", UUID.randomUUID().toString()).put("revision", 1).put("state", "queued").put("text", "queued owner input").put("mode", queued).put("scope", RemoteTaskDrafts.scope(savedSource, "a".repeat(64), queued)))
        return JSONObject().put("taskId", "task").put("profileBindingHash", "a".repeat(64)).put("source", source).put("canContinue", canContinue).put("inputDrafts", drafts)
    }
    private fun projected(source: JSONObject, canContinue: Boolean = false, mode: String? = null, savedSource: JSONObject = source) = PocketWebRemoteTasks.presentSnapshot(snapshot(source, canContinue, mode, savedSource))
    private fun delivery(value: JSONObject, mode: String) = value.getJSONObject("inputDelivery").getJSONObject(mode)
    @Test fun runningNoToolsPiCanOnlyQueueLocalIntentAndNeverClaimsModelUse() {
        val value = projected(pi(false)); val input = value.getJSONObject("inputDelivery")
        assertTrue(input.getBoolean("protocolEvidenceReported")); assertFalse(input.getBoolean("authorizesExecution")); assertFalse(delivery(value, "currentTurn").getBoolean("adapterMounted")); assertTrue(delivery(value, "nextTurn").getBoolean("adapterMounted")); assertFalse(delivery(value, "nextTurn").getBoolean("observedAvailable")); assertFalse(delivery(value, "nextTurn").getBoolean("automaticallySent")); assertTrue(delivery(value, "nextTurn").getBoolean("noToolsSessionOnly")); assertFalse(delivery(value, "interrupt").getBoolean("reversesCompletedEffects"))
        assertEquals("next_turn", value.getJSONObject("draftScopes").getJSONObject("next_turn").getString("mode"))
    }
    @Test fun unsettledPiCannotBorrowAStaleCanContinueBoolean() {
        val value = projected(pi(false), true, "next_turn")
        assertTrue(value.getBoolean("canContinue")); assertFalse(delivery(value, "nextTurn").getBoolean("observedAvailable")); assertTrue(value.getJSONArray("inputDrafts").getJSONObject(0).getBoolean("targetCurrent"))
    }
    @Test fun settledPiOnlyUsesTheOriginalSavedScopeAndRevision() {
        val value = projected(pi(true), true, "next_turn"); val row = value.getJSONArray("inputDrafts").getJSONObject(0)
        assertTrue(delivery(value, "nextTurn").getBoolean("observedAvailable")); assertTrue(row.getBoolean("targetCurrent")); assertEquals(1L, row.getLong("revision")); assertEquals("queued", row.getString("state")); assertTrue(RemoteTaskDrafts.same(row.getJSONObject("scope"), value.getJSONObject("draftScopes").getJSONObject("next_turn")))
    }
    @Test fun codexUsesItsOriginalTurnWhileClaudeHasNoMountedLiveInput() {
        val codex = projected(source("codex_app_server", "codex_app_server"), false, "steer")
        assertTrue(delivery(codex, "currentTurn").getBoolean("observedAvailable")); assertTrue(delivery(codex, "currentTurn").getBoolean("requiresExactTurn")); assertFalse(delivery(codex, "currentTurn").getBoolean("acceptanceProvesModelUse")); assertTrue(codex.getJSONArray("inputDrafts").getJSONObject(0).getBoolean("targetCurrent"))
        val claude = projected(source("claude", "claude_stream_json"), false, "next_turn")
        assertFalse(delivery(claude, "currentTurn").getBoolean("adapterMounted")); assertFalse(delivery(claude, "nextTurn").getBoolean("observedAvailable"))
    }
    @Test fun verifiedPiQueueStillReportsUnknownConsumptionAndExactPromptScope() {
        val value = projected(pi(false).put("inputSteeringVerified", true), false, "steer"); val current = delivery(value, "currentTurn")
        assertTrue(current.getBoolean("observedAvailable")); assertEquals("native_queue", current.getString("delivery")); assertEquals("unknown", current.getString("consumed")); assertFalse(current.getBoolean("acceptanceProvesModelUse")); assertTrue(value.getJSONArray("inputDrafts").getJSONObject(0).getBoolean("targetCurrent"))
        assertEquals("prompt", value.getJSONObject("draftScopes").getJSONObject("steer").getString("promptRequestId"))
    }
    @Test fun stoppedOrUnknownPiQueueRetainsSavedInputWithoutDispatchCapability() {
        val saved = pi(false).put("inputSteeringVerified", true)
        listOf(JSONObject(saved.toString()).put("cancelRequested", true), JSONObject(saved.toString()).put("steeringUnknown", true), JSONObject(saved.toString()).put("steeringUnknown", true).put("inputSteeringVerified", false)).forEach { changed ->
            val value = projected(changed, false, "steer", saved)
            assertEquals("queued owner input", value.getJSONArray("inputDrafts").getJSONObject(0).getString("text")); assertFalse(delivery(value, "currentTurn").getBoolean("observedAvailable"))
        }
    }
    @Test fun changedPiToolIdentityKeepsOriginalDraftButCannotInheritTarget() {
        val saved = pi(true); val value = projected(JSONObject(saved.toString()).put("noTools", false), true, "next_turn", saved)
        assertFalse(value.getJSONObject("inputDelivery").getBoolean("protocolEvidenceReported")); assertFalse(value.getJSONArray("inputDrafts").getJSONObject(0).getBoolean("targetCurrent")); assertEquals("queued owner input", value.getJSONArray("inputDrafts").getJSONObject(0).getString("text")); assertFalse(value.getJSONObject("draftScopes").has("next_turn"))
    }
    @Test fun configuredHarnessOnlyExposesProvenOriginalNextTurn() {
        for (worker in listOf("acp_owner_agent", "muse_msp")) {
            val value = projected(configured(worker), true, "next_turn")
            assertTrue(value.getBoolean("draftEditorAvailable")); assertFalse(delivery(value, "currentTurn").getBoolean("adapterMounted")); assertTrue(delivery(value, "nextTurn").getBoolean("observedAvailable")); assertTrue(value.getJSONArray("inputDrafts").getJSONObject(0).getBoolean("targetCurrent"))
            val scope = value.getJSONObject("draftScopes").getJSONObject("next_turn"); assertEquals("b".repeat(64), scope.getString("configurationRevision")); assertEquals("c".repeat(64), scope.getString("capabilityRevision"))
        }
    }
    @Test fun configuredHarnessCannotBorrowCanContinueWithoutActualSettlementAndResume() {
        val saved = configured("acp_owner_agent")
        for (source in listOf(JSONObject(saved.toString()).put("nativeSettled", false), JSONObject(saved.toString()).put("resumeSupported", false), JSONObject(saved.toString()).put("protocolVerified", false))) {
            val value = projected(source, true, "next_turn", saved)
            assertFalse(value.getBoolean("draftEditorAvailable")); assertFalse(delivery(value, "nextTurn").getBoolean("observedAvailable")); assertEquals(1, value.getJSONArray("inputDrafts").length())
        }
    }
}
