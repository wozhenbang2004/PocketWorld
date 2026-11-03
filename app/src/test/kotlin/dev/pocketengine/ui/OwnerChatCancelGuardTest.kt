package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OwnerChatCancelGuardTest {
    private fun binding(conversation: String, task: String, run: String, epoch: Int) =
        JSONObject().put("conversationId", conversation).put("taskId", task).put("runId", run).put("epoch", epoch)

    @Test fun originalActiveRunIsBoundWithoutCopyingContents() {
        val original = binding("a", "task-a", "run-a", 2)
        val result = originalChatCancelBinding(original, "a", true, JSONObject(original.toString()))!!
        assertEquals(original.toString(), result.toString())
        assertNotSame(original, result)
    }

    @Test fun delayedClickForARejectsNewConversationBOrNewRunInA() {
        val clickA = binding("a", "task-a", "run-a", 2)
        assertNull(originalChatCancelBinding(clickA, "b", true, binding("b", "task-b", "run-b", 3)))
        assertNull(originalChatCancelBinding(clickA, "a", true, binding("a", "task-b", "run-b", 3)))
        assertNull(originalChatCancelBinding(clickA, "a", true, binding("a", "task-a", "run-a", 3)))
        assertNull(originalChatCancelBinding(clickA, "a", false, clickA))
    }

    @Test fun missingOrMalformedOriginalIdentityNeverTargetsCurrentRun() {
        val actual = binding("a", "task-a", "run-a", 2)
        assertNull(originalChatCancelBinding(JSONObject(), "a", true, actual))
        assertNull(originalChatCancelBinding(binding("a", "task-a", "run-a", 0), "a", true, actual))
        assertNull(originalChatCancelBinding(binding("a", "task-a", "run-a", 2).put("epoch", "2"), "a", true, actual))
        assertNull(originalChatCancelBinding(actual, "a", true, null))
        assertNull(originalChatCancelBinding(actual, "", true, actual))
    }
    @Test fun onlyRealPositiveReceiptConfirmsStopRequest() {
        val yes = JSONObject().put("cancellationRequested", true)
        assertTrue(originalChatCancelReceipt(true, yes)!!.getBoolean("cancellationRequested"))
        assertNull(originalChatCancelReceipt(true, JSONObject().put("cancellationRequested", false)))
        assertNull(originalChatCancelReceipt(true, JSONObject()))
        assertNull(originalChatCancelReceipt(true, JSONObject().put("cancellationRequested", "true")))
        assertNull(originalChatCancelReceipt(false, yes))
        assertNull(originalChatCancelReceipt(false, null))
    }
}
