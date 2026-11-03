package dev.pocketengine.ui.web

import dev.pocketengine.features.assistant.OwnerPhoneQuestions
import dev.pocketengine.ui.UiTask
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PocketWebOwnerQuestionsTest {
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val run = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val request = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val hold = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    private fun task(phone: Boolean = true): JSONObject {
        val question = JSONObject().put("format", "pocketworld.owner-question.v1").put("replyKind", "answer")
            .put("approval", false).put("takeover", false).put("taskId", id).put("runId", run)
            .put("coordinatorEpoch", 2).put("requestId", request).put("waitRevision", 3).put("question", "选择哪项？")
            .put("options", JSONArray().put(JSONObject().put("id", "first").put("label", "第一项"))
                .put(JSONObject().put("id", "second").put("label", "第二项")))
        val conversation = if (phone) "" else "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        return JSONObject().put("id", id).put("runId", run).put("coordinatorEpoch", 2).put("status", "awaiting_user")
            .put("actorId", "owner").put("spaceId", "private").put("kind", if (phone) "phone" else "assistant")
            .put("executionMode", if (phone) "background" else "capabilities").put("conversationId", conversation)
            .put("ownerInputMailbox", JSONObject().put("open", false).put("runId", run).put("epoch", 2).put("conversationId", conversation))
            .put("evidence", JSONObject().put("ownerQuestion", question)).also {
                if (phone) it.put("ownerPhoneQuestionHold", JSONObject().put("format", OwnerPhoneQuestions.HOLD)
                    .put("taskId", id).put("runId", run).put("epoch", 2).put("requestId", request).put("waitRevision", 3)
                    .put("holdId", hold).put("pauseRevision", 1).put("waitGeneration", 1).put("executionAuthorized", false))
            }
    }

    private fun submission(raw: JSONObject = task()) = PocketWebOwnerQuestions.prepare(raw,
        PocketWebOwnerQuestions.ANSWER, JSONObject().put("reviewHandle", "review").put("text", "原回答"))

    private fun receipt(original: PocketWebOwnerQuestions.Submission): JSONObject = JSONObject(original.identity.toString())
        .put("accepted", true).put("answerSaved", true).put("messageId", "saved-message").put("executionAuthorized", false)

    @Test fun nativePhoneAndCapabilityViewsExposeOnlyAnswerPresentation() {
        for (phone in listOf(true, false)) {
            val view = PocketWebOwnerQuestions.view(task(phone))!!
            assertEquals(if (phone) "phone" else "capabilities", view.getString("kind"))
            assertEquals(2, view.getJSONArray("options").length())
            assertEquals(PocketWebOwnerQuestions.commands, PocketWebOwnerQuestions.actions(view))
            for (privateKey in listOf("identity", "holdId", "noticeToken", "grant")) assertFalse(view.has(privateKey))
        }
    }

    @Test fun changedHoldRunOrWaitCannotBorrowTheReviewedQuestion() {
        val raw = task()
        val baseKey = PocketWebOwnerQuestions.view(raw)!!.getString("questionKey")
        raw.getJSONObject("ownerPhoneQuestionHold").put("pauseRevision", 2)
        assertNotEquals(baseKey, PocketWebOwnerQuestions.view(raw)!!.getString("questionKey"))
        raw.put("runId", "ffffffff-ffff-4fff-8fff-ffffffffffff")
        assertNull(PocketWebOwnerQuestions.view(raw))
        assertThrows(IllegalStateException::class.java) { submission(raw) }
        val oldWait = task()
        oldWait.getJSONObject("evidence").getJSONObject("ownerQuestion").put("waitRevision", 4)
        assertNull(PocketWebOwnerQuestions.view(oldWait))
        val released = task().also { it.remove("ownerPhoneQuestionHold") }
        assertNull(PocketWebOwnerQuestions.view(released))
        assertTrue(PocketWebOwnerQuestions.blocksGenericResume(released))
    }

    @Test fun reviewDeadlineAndPhysicalHoldChangesRejectBeforeReplyRouting() {
        var now = 1_000L
        val reviews = PocketWebTaskReviews { now }
        val raw = task()
        val ui = UiTask(id, "原手机任务", "awaiting_user", "等待回答", "background", 1L, raw)
        val handle = reviews.create(ui, PocketWebOwnerQuestions.commands)
        now += 120_000L
        assertThrows(IllegalStateException::class.java) { reviews.take(handle, PocketWebOwnerQuestions.CHOOSE, listOf(ui)) }
        val current = reviews.create(ui, PocketWebOwnerQuestions.commands)
        raw.getJSONObject("ownerPhoneQuestionHold").put("waitGeneration", 2)
        assertThrows(IllegalStateException::class.java) { reviews.take(current, PocketWebOwnerQuestions.ANSWER, listOf(ui)) }
    }

    @Test fun answeredHistoryDoesNotPermanentlyBlockALaterPauseOrDifferentRun() {
        for (phone in listOf(true, false)) {
            val raw = task(phone)
            assertTrue(PocketWebOwnerQuestions.blocksGenericResume(raw))
            val question = raw.getJSONObject("evidence").getJSONObject("ownerQuestion")
            question.put("replyConsumed", true).put("replyMessageId", "saved-answer")
            raw.put("ownerQuestionReplyState", JSONObject().put("format", "pocketworld.owner-question-reply-state.v1")
                .put("runId", run).put("epoch", 2).put("requestId", request).put("messageId", "saved-answer")
                .put("state", "original_worker_requested").put("ownerAction", "open_original_task"))
            assertTrue("The queued original continuation still owns this wait", PocketWebOwnerQuestions.blocksGenericResume(raw))
            raw.put("status", "paused")
            assertFalse("A later owner pause is not an unanswered question", PocketWebOwnerQuestions.blocksGenericResume(raw))
            raw.put("status", "awaiting_user").put("runId", "ffffffff-ffff-4fff-8fff-ffffffffffff")
            assertFalse("Old-run evidence cannot lock the current run", PocketWebOwnerQuestions.blocksGenericResume(raw))
        }
    }

    @Test fun savedContinuationOutcomesAndExactMarkersDistinguishPendingFromHistoricalHold() {
        val raw = task()
        raw.getJSONObject("evidence").getJSONObject("ownerQuestion").put("replyConsumed", true).put("replyMessageId", "saved-answer")
        val state = JSONObject().put("format", "pocketworld.owner-question-reply-state.v1")
            .put("runId", run).put("epoch", 2).put("requestId", request).put("messageId", "saved-answer")
            .put("state", "original_worker_requested").put("ownerAction", "open_original_task")
        raw.put("ownerQuestionReplyState", state)
        assertTrue(PocketWebOwnerQuestions.blocksGenericResume(raw))
        val marker = JSONObject().put("format", "pocketworld.owner-question-continuation.v1")
            .put("runId", run).put("epoch", 2).put("requestId", request).put("waitRevision", 3)
            .put("messageId", "saved-answer").put("phase", "claimed")
        raw.put("ownerQuestionContinuation", marker)
        assertFalse("Claimed typed continuation does not permanently lock its retained hold", PocketWebOwnerQuestions.blocksGenericResume(raw))
        marker.put("waitRevision", 2)
        assertTrue("A marker for another wait cannot settle this pending answer", PocketWebOwnerQuestions.blocksGenericResume(raw))
        marker.put("waitRevision", 3).put("phase", "unavailable")
        assertFalse("The original worker settled unavailable; ordinary owner controls remain", PocketWebOwnerQuestions.blocksGenericResume(raw))
        raw.remove("ownerQuestionContinuation")
        for (outcome in listOf("saved_budget_exhausted", "saved_scope_expired", "saved_execution_unavailable")) {
            state.put("state", outcome)
            assertFalse("Saved/blocked is not an unconsumed question", PocketWebOwnerQuestions.blocksGenericResume(raw))
        }
    }

    @Test fun optionsAreValidatedAgainstNativeStoredDataAndAnsweredQuestionsAreReadOnly() {
        val raw = task()
        val args = JSONObject().put("reviewHandle", "review").put("optionId", "second")
        val chosen = PocketWebOwnerQuestions.prepare(raw, PocketWebOwnerQuestions.CHOOSE, args)
        assertEquals("second", chosen.optionId); assertNull(chosen.text)
        args.put("optionId", "forged")
        assertThrows(SecurityException::class.java) { PocketWebOwnerQuestions.prepare(raw, PocketWebOwnerQuestions.CHOOSE, args) }
        args.put("optionId", "second").put("label", "批准所有操作")
        assertThrows(IllegalArgumentException::class.java) { PocketWebOwnerQuestions.prepare(raw, PocketWebOwnerQuestions.CHOOSE, args) }
        raw.getJSONObject("evidence").getJSONObject("ownerQuestion").put("replyConsumed", true)
        assertTrue(PocketWebOwnerQuestions.view(raw)!!.getBoolean("answered"))
        assertTrue(PocketWebOwnerQuestions.actions(PocketWebOwnerQuestions.view(raw)).isEmpty())
        assertThrows(IllegalStateException::class.java) { submission(raw) }
    }

    @Test fun approvalForeignOriginsAndOtherAssistantModesDoNotGainAnAnswerRoute() {
        assertNull(PocketWebOwnerQuestions.view(task().put("origin", JSONObject().put("kind", "paired_device"))))
        assertNull(PocketWebOwnerQuestions.view(task(false).put("executionMode", "chat")))
        val approval = task()
        approval.getJSONObject("evidence").getJSONObject("ownerQuestion").put("approval", true)
        assertNull(PocketWebOwnerQuestions.view(approval))
    }

    @Test fun receiptMustConfirmTheExactOriginalSaveButNeedNotAuthorizeContinuation() {
        val original = submission()
        assertThrows(IllegalStateException::class.java) { PocketWebOwnerQuestions.savedReceipt(original, JSONObject().put("accepted", true)) }
        assertThrows(IllegalStateException::class.java) { PocketWebOwnerQuestions.savedReceipt(original, receipt(original).put("expired", true)) }
        for (name in listOf("taskId", "runId", "requestId", "coordinatorEpoch", "waitRevision")) {
            val changed = receipt(original).put(name, if (name in setOf("coordinatorEpoch", "waitRevision")) 99 else "another")
            assertThrows(IllegalStateException::class.java) { PocketWebOwnerQuestions.savedReceipt(original, changed) }
        }
        for (state in listOf("saved_budget_exhausted", "saved_scope_expired", "saved_execution_unavailable", "already_saved")) {
            val accepted = PocketWebOwnerQuestions.savedReceipt(original, receipt(original).put("continuationState", state).put("duplicate", state == "already_saved"))
            assertTrue(accepted.getBoolean("answerSaved")); assertEquals(state, accepted.getString("continuationState"))
            assertEquals(original.questionKey, accepted.getString("questionKey"))
        }
    }
}
