package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueuedOwnerInputsPresentationTest {
    private fun receipt(status:String="queued",queueRevision:Long=1)=JSONObject()
        .put("clientMessageId","client").put("messageId","message").put("conversationId","conversation")
        .put("taskId","task").put("runId","run").put("epoch",1).put("status",status)
        .put("queueRevision",queueRevision).put("ownerMessageRevision",3)

    @Test fun claimedIsNotCalledConsumedAndNextBoundaryIsNotImmediateCancellation() {
        assertEquals("任务已接手，正在纳入上下文",queuedInputStatus(JSONObject().put("queueState","claimed")))
        assertEquals("等待下一个安全边界",queuedInputStatus(JSONObject().put("queueState","pending").put("mode","steer")))
        assertEquals("待本轮结束后处理",queuedInputStatus(JSONObject().put("queueState","pending").put("mode","queue")))
    }
    @Test fun withdrawnReceiptCannotRegressToLateQueuedOrUnknownAcknowledgement() {
        val withdrawn=receipt("withdrawn",3)
        assertSame(withdrawn,ChatInputRules.mergeReceipt(withdrawn,receipt("queued",1)))
        assertSame(withdrawn,ChatInputRules.mergeReceipt(withdrawn,receipt("unconfirmed",1)))
    }
    @Test fun editedQueueRevisionCannotBeOverwrittenByAnOlderModeReply() {
        val edited=receipt(queueRevision=3).put("mode","steer")
        assertSame(edited,ChatInputRules.mergeReceipt(edited,receipt(queueRevision=1).put("mode","queue")))
        assertEquals("withdrawn",ChatInputRules.mergeReceipt(edited,receipt("withdrawn",4))!!.getString("status"))
    }
    @Test fun queueAcknowledgementCannotSwitchOriginalTaskOrAcceptMalformedVersion() {
        assertNull(ChatInputRules.mergeReceipt(receipt(),receipt("withdrawn",2).put("taskId","foreign")))
        assertNull(ChatInputRules.mergeReceipt(null,receipt("withdrawn",2).put("queueRevision","2")))
        assertNull(ChatInputRules.mergeReceipt(null,receipt("withdrawn",2).put("queueRevision",0)))
        assertNull(ChatInputRules.mergeReceipt(null,receipt("withdrawn",2).put("ownerMessageRevision",0)))
    }
    @Test fun editingIdentityIsCapturedRatherThanReboundWhenAnotherRowIsRead() {
        val original=QueuedOwnerInputEditor("message",2,3,"old","queue","new","steer",1,1,false)
        val page=QueuedOwnerInputsPage(1,"task","conversation",7,reading=false,editor=original)
        val refreshed=page.copy(items=listOf(JSONObject().put("messageId","another").put("queueRevision",9)))
        assertSame(original,refreshed.editor);assertEquals("message",refreshed.editor!!.message)
        assertEquals(2L,refreshed.editor!!.queueRevision);assertEquals("new",refreshed.editor!!.text)
    }
    @Test fun refreshCancelsPendingDetailAndPreservesLoadedEditorWithoutRebinding() {
        val edit=QueuedOwnerInputEditor("message",2,3,"old","queue","new","steer",0,0,false)
        val waiting=QueuedOwnerInputsPage(1,"task","conversation",7,reading=false,readingMessage="another",editor=edit,error="old error")
        val refreshed=waiting.readingList()
        assertTrue(refreshed.reading);assertNull(refreshed.readingMessage);assertNull(refreshed.error)
        assertSame(edit,refreshed.editor);assertEquals(1L,refreshed.window)
    }

}
