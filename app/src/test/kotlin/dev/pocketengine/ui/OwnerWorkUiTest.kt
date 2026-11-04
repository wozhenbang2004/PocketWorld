package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OwnerWorkUiTest {
    private fun task(run: String = "work-run", epoch: Int = 2, status: String = "running") = taskFromJson(JSONObject()
        .put("id", "work").put("executionRole", "owner_work").put("actorId", "owner").put("spaceId", "private")
        .put("conversationId", "conversation").put("runId", run).put("coordinatorEpoch", epoch).put("status", status)
        .put("controls", JSONObject().put("canStop", true)))
    private fun binding(run: String = "work-run", epoch: Int = 2) = JSONObject().put("taskId", "work").put("runId", run).put("epoch", epoch)
    private fun refuses(action: () -> Unit) { try { action(); fail("Expected original identity refusal") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun foregroundAndAnotherConversationCannotBecomeTheTargetedWork() {
        assertEquals("work-run", originalOwnerWorkBinding(binding(), "conversation", listOf(task())).getString("runId"))
        refuses { originalOwnerWorkBinding(binding("foreground-run", 1), "conversation", listOf(task())) }
        refuses { originalOwnerWorkBinding(binding(), "another-conversation", listOf(task())) }
        val foreground = task().also { it.raw.put("executionRole", "conversation_turn") }
        refuses { originalOwnerWorkBinding(binding(), "conversation", listOf(foreground)) }
    }
    @Test fun lateControlsKeepTheOriginalRunInsteadOfFollowingTheTaskId() {
        refuses { originalOwnerWorkBinding(binding(), "conversation", listOf(task("new-run", 3))) }
        refuses { originalOwnerWorkBinding(binding().put("epoch", "2"), "conversation", listOf(task())) }
    }
    @Test fun queuedCancelRetainsEmptyRunAndCannotBeReusedAfterClaim() {
        val expected = binding("", 0)
        assertEquals(0, originalOwnerWorkBinding(expected, "conversation", listOf(task("", 0, "queued"))).getInt("epoch"))
        refuses { originalOwnerWorkBinding(expected, "conversation", listOf(task())) }
    }
    @Test fun reopeningTheComposerFindsItsOriginalWorkSubmissionBeforeAnyIndependentSend() {
        val model = dev.pocketengine.features.assistant.OwnerComposerState.empty()
        val conversation = java.util.UUID.randomUUID().toString()
        val original = model.selectConversation(conversation)
        val draft = model.edit(original.key, original.revision, "这件事的补充仍待原回执", "queue", org.json.JSONArray())
        val target = JSONObject().put("taskId", java.util.UUID.randomUUID().toString()).put("runId", java.util.UUID.randomUUID().toString()).put("epoch", 4)
        val fingerprint = ChatInputRules.fingerprint(draft.text, conversation, "queue", emptyList())
        val pending = model.prepare(java.util.UUID.randomUUID().toString(), draft.key, draft.revision, ownerWorkDraftFingerprint(fingerprint, target), target)
        val restored = dev.pocketengine.features.assistant.OwnerComposerState.fromJson(JSONObject(model.toJson().toString()))
        assertNull(restored.matchingPending(draft.key, fingerprint))
        assertEquals(pending.clientId, matchingOriginalOwnerWorkInput(restored, draft.key, fingerprint)?.clientId)
        assertEquals(target.getString("runId"), matchingOriginalOwnerWorkInput(restored, draft.key, fingerprint)?.originalTarget()?.getString("runId"))
        assertNull(matchingOriginalOwnerWorkInput(restored, "another-draft", fingerprint))
        assertNull(matchingOriginalOwnerWorkInput(restored, draft.key, ChatInputRules.fingerprint("新的独立消息", conversation, "queue", emptyList())))
        assertEquals(draft.text, restored.active().text)
        assertEquals(1, restored.pending().size)
    }
    @Test fun currentNativeWorkViewWinsOverAStoredHeaderWithoutRestoringItsOldControlsOrBody() {
        val header = task().raw.put("goal", "来源失效前的旧目标").put("summary", "旧正文").put("updatedAt", 100L)
            .put("taskProjection", "summary").put("contextAvailable", true)
            .put("controls", JSONObject().put("canStop", true).put("canInput", true).put("canContinue", true))
        val current = JSONObject(header.toString()).put("goal", "原工作来源已不可用于上下文").put("summary", "原来源待核对")
            .put("contextAvailable", false).put("controls", JSONObject().put("canStop", true).put("canInput", false).put("canContinue", false))
        current.remove("taskProjection")
        val payload = JSONObject().put("tasks", org.json.JSONArray().put(header)).put("ownerWorks", org.json.JSONArray().put(current))
        val rows = nativeTaskStateRows(payload)
        assertEquals(1, rows.size)
        val merged = mergeTaskSummary(rows.single(), taskFromJson(header))
        assertEquals("原工作来源已不可用于上下文", merged.goal)
        assertEquals("原来源待核对", merged.summary)
        assertFalse(merged.raw.getBoolean("contextAvailable"))
        assertFalse(merged.raw.getJSONObject("controls").getBoolean("canInput"))
        assertFalse(merged.raw.getJSONObject("controls").getBoolean("canContinue"))
        assertTrue(merged.raw.getJSONObject("controls").getBoolean("canStop"))
        payload.remove("ownerWorks")
        assertEquals("来源失效前的旧目标", nativeTaskStateRows(payload).single().goal)
    }
}
