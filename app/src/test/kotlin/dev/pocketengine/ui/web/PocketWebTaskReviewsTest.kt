package dev.pocketengine.ui.web

import dev.pocketengine.ui.UiTask
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM authority-fence tests. These fixtures never enter TaskStore. */
class PocketWebTaskReviewsTest {
    private class Clock(var millis: Long = 1_000L) {
        val reviews = PocketWebTaskReviews { millis }
    }

    private fun task(id: String = "task-a"): UiTask {
        val raw = JSONObject()
            .put("id", id)
            .put("goal", "核对原任务的本次操作")
            .put("status", "waiting_approval")
            .put("runId", "run-a")
            .put("epoch", 7)
            .put("version", 11)
            .put("approval", JSONObject().put("id", "approval-a").put("tool", "phone.tap"))
            .put("scope", JSONObject().put("toolBudget", 4).put("modelBudget", 2))
        return UiTask(id, raw.getString("goal"), "waiting_approval", "原审阅快照",
            "background", 123_456L, raw)
    }

    private fun detached(task: UiTask): UiTask = task.copy(raw = JSONObject(task.raw.toString()))

    private fun reject(block: () -> Unit) {
        val failure = assertThrows(IllegalStateException::class.java) { block() }
        assertTrue("Rejected review must explain why it was not accepted", !failure.message.isNullOrBlank())
    }

    private fun rejectsChangedTask(change: (UiTask) -> UiTask) {
        val clock = Clock()
        val original = task()
        val handle = clock.reviews.create(original, setOf("tasks.approve"))
        val changed = change(detached(original))
        reject { clock.reviews.take(handle, "tasks.approve", listOf(changed)) }
        // A failed attempt does not leave a reusable old approval reference.
        reject { clock.reviews.take(handle, "tasks.approve", listOf(detached(original))) }
    }

    @Test fun forgedHandleCannotApproveAnOtherwiseMatchingTask() {
        val clock = Clock()
        val original = task()
        clock.reviews.create(original, setOf("tasks.approve"))
        reject { clock.reviews.take("forged-review-handle", "tasks.approve", listOf(original)) }
    }

    @Test fun missingOriginalPlanDoesNotAllowPositiveApprovalButStillAllowsRejection() {
        val clock = Clock(); val original = task()
        val unread = clock.reviews.create(original, setOf("tasks.approve"))
        reject { clock.reviews.take(unread, "tasks.approve", listOf(original), "approval-a") }
        val rejectOnly = clock.reviews.create(original, setOf("tasks.approve"))
        assertEquals(original.id, clock.reviews.take(rejectOnly, "tasks.approve", listOf(original)).id)
    }

    @Test fun positiveApprovalCannotBorrowAnotherPlansReadReceipt() {
        val clock = Clock(); val original = task()
        val wrongPlan = clock.reviews.create(original, setOf("tasks.approve"), setOf("approval-b"))
        reject { clock.reviews.take(wrongPlan, "tasks.approve", listOf(original), "approval-a") }
        val exact = clock.reviews.create(original, setOf("tasks.approve"), setOf("approval-a"))
        assertEquals(original.id, clock.reviews.take(exact, "tasks.approve", listOf(original), "approval-a").id)
        reject { clock.reviews.take(exact, "tasks.approve", listOf(original), "approval-a") }
    }

    @Test fun exactUnchangedSnapshotIsReturnedOnceAndDoesNotShareMutableJson() {
        val clock = Clock()
        val original = task()
        val current = detached(original)
        val expectedRaw = original.raw.toString()
        val handle = clock.reviews.create(original, setOf("tasks.approve"))
        // The caller's original object is not the stored review or the current window.
        original.raw.getJSONObject("scope").put("toolBudget", 999)

        val reviewed = clock.reviews.take(handle, "tasks.approve", listOf(current))
        assertNotSame(original, reviewed)
        assertNotSame(original.raw, reviewed.raw)
        assertNotSame(current.raw, reviewed.raw)
        assertEquals(current.id, reviewed.id)
        assertEquals(current.goal, reviewed.goal)
        assertEquals(current.status, reviewed.status)
        assertEquals(current.summary, reviewed.summary)
        assertEquals(current.executionMode, reviewed.executionMode)
        assertEquals(current.updatedAt, reviewed.updatedAt)
        assertEquals(expectedRaw, reviewed.raw.toString())
        reject { clock.reviews.take(handle, "tasks.approve", listOf(current)) }
    }

    @Test fun rawDetailsChangingInPlaceInvalidateTheOriginalReview() {
        val clock = Clock()
        val current = task()
        val handle = clock.reviews.create(current, setOf("tasks.approve"))
        current.raw.getJSONObject("approval").put("tool", "phone.swipe")
        reject { clock.reviews.take(handle, "tasks.approve", listOf(current)) }
    }

    @Test fun aPlanChangingWithoutANewTimestampInvalidatesItsOriginalApprovalHandle() {
        val current = task()
        current.raw.put("executionScope", JSONObject().put("revision", 4).put("executionPhase", "planning")
            .put("planRevision", 2).put("planArtifactRef", "proposal-a").put("planHash", "a".repeat(64)))
        val clock = Clock()
        val handle = clock.reviews.create(current, setOf("tasks.approve"), setOf("approval-a"))
        current.raw.getJSONObject("executionScope").put("planRevision", 3).put("planArtifactRef", "proposal-b").put("planHash", "b".repeat(64))
        assertEquals(123_456L, current.updatedAt)
        reject { clock.reviews.take(handle, "tasks.approve", listOf(current), "approval-a") }
    }

    @Test fun changedRunCannotBorrowThePreviousRunsReview() {
        rejectsChangedTask { it.apply { raw.put("runId", "run-b") } }
    }

    @Test fun changedEpochInvalidatesTheReview() {
        rejectsChangedTask { it.apply { raw.put("epoch", 8) } }
    }

    @Test fun changedVersionInvalidatesTheReview() {
        rejectsChangedTask { it.apply { raw.put("version", 12) } }
    }

    @Test fun changedPresentationStatusInvalidatesTheReviewEvenWhenRawIsUnchanged() {
        rejectsChangedTask { it.copy(status = "running") }
    }

    @Test fun changedRawStatusInvalidatesTheReviewEvenWhenPresentationIsUnchanged() {
        rejectsChangedTask { it.apply { raw.put("status", "running") } }
    }

    @Test fun changedUpdateVersionInvalidatesTheReview() {
        rejectsChangedTask { it.copy(updatedAt = it.updatedAt + 1) }
    }

    @Test fun missingOrDifferentTaskCannotSatisfyTheOriginalReview() {
        val clock = Clock()
        val original = task()
        val missing = clock.reviews.create(original, setOf("tasks.approve"))
        reject { clock.reviews.take(missing, "tasks.approve", emptyList()) }
        val replaced = clock.reviews.create(original, setOf("tasks.approve"))
        reject { clock.reviews.take(replaced, "tasks.approve", listOf(task("task-b"))) }
    }

    @Test fun actionOutsideTheReviewedScopeIsRejectedAndConsumesTheHandle() {
        val clock = Clock()
        val original = task()
        val handle = clock.reviews.create(original, setOf("tasks.approve"))
        reject { clock.reviews.take(handle, "tasks.resolve", listOf(original)) }
        reject { clock.reviews.take(handle, "tasks.approve", listOf(original)) }
    }

    @Test fun reviewExpiresAtItsDeadline() {
        val clock = Clock()
        val original = task()
        val handle = clock.reviews.create(original, setOf("tasks.approve"))
        clock.millis += 120_000L
        reject { clock.reviews.take(handle, "tasks.approve", listOf(original)) }
    }

    @Test fun reviewRemainsUsableUntilTheLastMillisecondBeforeItsDeadline() {
        val clock = Clock()
        val original = task()
        val handle = clock.reviews.create(original, setOf("tasks.approve"))
        clock.millis += 119_999L
        assertEquals(original.raw.toString(), clock.reviews.take(handle, "tasks.approve", listOf(original)).raw.toString())
    }

    @Test fun clearInvalidatesEveryPendingReviewAndNewReviewStartsFresh() {
        val clock = Clock()
        val first = task()
        val second = task("task-b")
        val firstHandle = clock.reviews.create(first, setOf("tasks.approve"))
        val secondHandle = clock.reviews.create(second, setOf("tasks.confirm"))
        clock.reviews.clear()
        reject { clock.reviews.take(firstHandle, "tasks.approve", listOf(first, second)) }
        reject { clock.reviews.take(secondHandle, "tasks.confirm", listOf(first, second)) }
        val freshHandle = clock.reviews.create(first, setOf("tasks.approve"))
        assertEquals(first.id, clock.reviews.take(freshHandle, "tasks.approve", listOf(first)).id)
    }
    private fun ordinaryQuestionTask(): UiTask {
        val taskId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val runId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val conversation = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val raw = JSONObject().put("id", taskId).put("goal", "原任务").put("kind", "assistant")
            .put("executionMode", "capabilities").put("actorId", "owner").put("spaceId", "private")
            .put("status", "awaiting_user").put("runId", runId).put("coordinatorEpoch", 7).put("conversationId", conversation)
            .put("ownerInputMailbox", JSONObject().put("open", false).put("runId", runId).put("epoch", 7).put("conversationId", conversation))
            .put("evidence", JSONObject().put("ownerQuestion", JSONObject().put("format", "pocketworld.owner-question.v1")
                .put("replyKind", "answer").put("approval", false).put("takeover", false).put("taskId", taskId)
                .put("runId", runId).put("coordinatorEpoch", 7).put("requestId", requestId).put("waitRevision", 3).put("question", "选择哪一项？")))
        return UiTask(taskId, "原任务", "awaiting_user", "等待回答", "capabilities", 1L, raw)
    }

    @Test fun ordinaryQuestionReviewBindsTheOriginalWaitAndIsSingleUse() {
        val original = ordinaryQuestionTask()
        val question = dev.pocketengine.features.assistant.OwnerQuestionInputs.pageQuestion(original.raw)
        assertNotNull(question);assertFalse(question.getBoolean("answered"))
        val reviews = Clock().reviews
        val handle = reviews.create(original, setOf("tasks.answerQuestion"))
        val accepted = reviews.take(handle, "tasks.answerQuestion", listOf(detached(original)))
        assertEquals(question.getJSONObject("identity").toString(), dev.pocketengine.features.assistant.OwnerQuestionInputs.pageQuestion(accepted.raw).getJSONObject("identity").toString())
        reject { reviews.take(handle, "tasks.answerQuestion", listOf(detached(original))) }
    }

    @Test fun ordinaryQuestionRejectsChangedWaitAndNonPrivateOrApprovalScope() {
        val original = ordinaryQuestionTask();val clock = Clock();val handle = clock.reviews.create(original, setOf("tasks.answerQuestion"))
        val changed = detached(original);changed.raw.getJSONObject("evidence").getJSONObject("ownerQuestion").put("waitRevision", 4)
        reject { clock.reviews.take(handle, "tasks.answerQuestion", listOf(changed)) }
        reject { clock.reviews.take(handle, "tasks.answerQuestion", listOf(detached(original))) }
        val group = detached(original);group.raw.put("origin", JSONObject().put("kind", "group"))
        assertNull(dev.pocketengine.features.assistant.OwnerQuestionInputs.pageQuestion(group.raw))
        val approval = detached(original);approval.raw.getJSONObject("evidence").getJSONObject("ownerQuestion").put("approval", true)
        assertNull(dev.pocketengine.features.assistant.OwnerQuestionInputs.pageQuestion(approval.raw))
    }
    @Test fun questionFeedbackTaskIdComesOnlyFromReviewedIdentity() {
        val original = ordinaryQuestionTask()
        val identity = dev.pocketengine.features.assistant.OwnerQuestionInputs.pageQuestion(original.raw).getJSONObject("identity")
        val input = dev.pocketengine.ui.ownerQuestionWebInput(identity, "原回答")
        assertEquals(original.id, input.getString("taskId"))
        assertEquals(identity.toString(), input.getJSONObject("questionIdentity").toString())
        identity.put("taskId", "different")
        assertEquals(original.id, input.getString("taskId"))
        assertEquals(original.id, input.getJSONObject("questionIdentity").getString("taskId"))
        assertThrows(IllegalArgumentException::class.java) { dev.pocketengine.ui.ownerQuestionWebInput(input.getJSONObject("questionIdentity"), " ") }
    }
    @Test fun questionReceiptCannotTurnMissingOrRejectedIntoAcceptance() {
        assertThrows(IllegalStateException::class.java) { dev.pocketengine.ui.requireOwnerQuestionAccepted(null) }
        assertThrows(IllegalStateException::class.java) { dev.pocketengine.ui.requireOwnerQuestionAccepted(JSONObject()) }
        assertThrows(IllegalStateException::class.java) { dev.pocketengine.ui.requireOwnerQuestionAccepted(JSONObject().put("accepted", false)) }
        val accepted = JSONObject().put("accepted", true).put("continuationState", "saved_owner_action_required")
        assertSame(accepted, dev.pocketengine.ui.requireOwnerQuestionAccepted(accepted))
    }
}
