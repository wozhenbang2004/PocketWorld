package dev.pocketengine.ui.web

import dev.pocketengine.ui.UiPhoneResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PocketWebPhoneResultNavigationTest {
    private val task = "11111111-1111-4111-8111-111111111111"
    private val run = "22222222-2222-4222-8222-222222222222"

    private fun nativeMessage() = JSONObject()
        .put("role", "assistant").put("content", "原执行已结束")
        .put("taskId", task).put("nativeRunId", run).put("nativeEpoch", 1)
        .put("nativeDeliveryId", "phone_handoff:$task:$run:1")
        .put("nativeStatus", "failed").put("status", "failed").put("taskEndedAt", 1_790_000_000_000L)
        .put("summaryTruncated", false).put("summaryChars", "原执行已结束".length)
        .put("taskReference", JSONObject().put("taskId", task).put("runId", run).put("epoch", 1))

    private fun allowed(
        requestedTask: String = task,
        section: String? = null,
        taskView: String? = null,
        privateVisible: Boolean = true,
        conversation: String? = "original-conversation",
        results: Sequence<UiPhoneResult?> = sequenceOf(UiPhoneResult.fromNativeMessage(nativeMessage())),
    ) = canReadTaskFromVisiblePhoneResult(requestedTask, section, taskView, privateVisible, conversation, results)

    @Test fun aVerifiedVisibleResultCanReadItsOriginalTaskWithoutTheRecentTaskCache() {
        assertTrue(allowed())
        assertFalse(allowed(requestedTask = "another-task"))
        assertFalse(allowed(requestedTask = ""))
        // A deleted result or a newly selected conversation has no retained message selection.
        assertFalse(allowed(results = emptySequence()))
    }

    @Test fun connectionTaskAndNonprivateWindowsCannotUseAConversationResult() {
        for (section in listOf("channels", "groups", "models", "devices", "chat")) {
            assertFalse(section, allowed(section = section))
        }
        assertFalse(allowed(taskView = task))
        assertFalse(allowed(privateVisible = false))
        assertFalse(allowed(conversation = null))
        assertFalse(allowed(conversation = " "))
    }

    @Test fun ordinaryMessageTaskIdsAndUnverifiedWebShapedMetadataCannotAuthorizeTheRead() {
        val ordinary = JSONObject().put("role", "assistant").put("content", "普通回复").put("taskId", task)
        val webShaped = JSONObject(ordinary.toString()).put("phoneResult", requireNotNull(UiPhoneResult.fromNativeMessage(nativeMessage())).toJson())
        val conflicting = nativeMessage().also { it.getJSONObject("taskReference").put("runId", "different-run") }
        val imported = nativeMessage().put("importAssignedIdentity", true)
        for (message in listOf(ordinary, webShaped, conflicting, imported)) {
            val result = UiPhoneResult.fromNativeMessage(message)
            assertNull(result)
            assertFalse(allowed(results = sequenceOf(result)))
        }
    }
}
