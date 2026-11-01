package dev.pocketengine.ui

import org.junit.Assert.*
import org.junit.Test

class SkillExecutionReviewPageTest {
    private val digest = "a".repeat(64)

    @Test fun approvalRequiresExactCurrentPlanExplicitReviewAndLiveDeadline() {
        assertTrue(SkillExecutionReviewGuard.canApprove(digest, digest, 2000, 1000, false, true))
        assertFalse(SkillExecutionReviewGuard.canApprove(digest, "b".repeat(64), 2000, 1000, false, true))
        assertFalse(SkillExecutionReviewGuard.canApprove(digest, digest, 2000, 2000, false, true))
        assertFalse(SkillExecutionReviewGuard.canApprove(digest, digest, 2000, 1000, true, true))
        assertFalse(SkillExecutionReviewGuard.canApprove(digest, digest, 2000, 1000, false, false))
        assertFalse(SkillExecutionReviewGuard.canApprove(null, null, 2000, 1000, false, true))
        assertFalse(SkillExecutionReviewGuard.canApprove("", "", 2000, 1000, false, true))
        assertFalse(SkillExecutionReviewGuard.canApprove(digest, digest, 2000, -1, false, true))
    }
}
