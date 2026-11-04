package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageIdentityProjectionTest {
    private fun reply() = JSONObject().put("role", "assistant").put("content", "原答复")
        .put("taskId", "task").put("nativeRunId", "run-one").put("nativeEpoch", 1).put("nativeReplyId", "reply")
        .put("replyToMessageIds", JSONArray().put("request-one").put("request-two"))
    private fun result(kind: String) = reply().also { it.remove("nativeReplyId") }
        .put("nativeDeliveryKind", kind).put("nativeDeliveryId", "$kind:task:run-one:1")
        .put("nativeStatus", "failed").put("taskEndedAt", 1793764860000L).put("summaryTruncated", false).put("summaryChars", 3)
        .put("taskReference", JSONObject().put("taskId", "task").put("runId", "run-one").put("epoch", 1))

    @Test fun nativeReplyKeepsEveryConsumedRequestWithoutExposingOtherMetadata() {
        val message = reply().put("privateContext", "must stay native")
        val context = requireNotNull(UiReplyContext.fromNativeMessage(message))
        assertEquals(listOf("request-one", "request-two"), context.replyToMessageIds)
        message.getJSONArray("replyToMessageIds").put("late-request")
        assertEquals(2, context.replyToMessageIds.size)
        assertFalse(context.toJson().toString().contains("must stay native"))
        assertNull(UiReplyContext.fromNativeMessage(reply().put("importAssignedIdentity", true)))
        assertNull(UiReplyContext.fromNativeMessage(reply().put("role", "user")))
    }
    @Test fun aLaterRunCannotChangeTheFrozenDeliveredResult() {
        for (kind in listOf("owner_work", "automation", "team")) {
            val native = result(kind); val frozen = requireNotNull(UiTaskResult.fromNativeMessage(native))
            native.put("nativeRunId", "run-two").put("nativeStatus", "completed")
            assertEquals("run-one", frozen.runId); assertEquals("failed", frozen.status)
            assertNull(UiTaskResult.fromNativeMessage(native))
        }
    }
    @Test fun incompleteOrConflictingMetadataHidesOnlyTheBadge() {
        for (field in listOf("nativeDeliveryId", "nativeDeliveryKind", "nativeEpoch", "taskReference", "taskEndedAt", "summaryChars")) {
            val native = result("owner_work"); native.remove(field)
            assertNull(field, UiTaskResult.fromNativeMessage(native)); assertEquals("原答复", native.getString("content"))
        }
        assertNull(UiTaskResult.fromNativeMessage(result("owner_work").put("nativeEpoch", "1")))
        assertNull(UiTaskResult.fromNativeMessage(result("owner_work").put("status", "completed")))
        assertNull(UiTaskResult.fromNativeMessage(result("phone_handoff")))
    }
}
