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
}
