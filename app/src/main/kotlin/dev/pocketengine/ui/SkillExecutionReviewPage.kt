package dev.pocketengine.ui

internal object SkillExecutionReviewGuard {
    @JvmStatic fun canApprove(displayedDigest: String?, currentDigest: String?, expiresAt: Long, now: Long, busy: Boolean, acknowledged: Boolean): Boolean =
        displayedDigest != null && displayedDigest.matches(Regex("[a-f0-9]{64}")) && displayedDigest == currentDigest &&
            expiresAt > now && now >= 0 && !busy && acknowledged
}

/** Native leaf only. currentPlanDigest must come from the host's current-plan validation. */
