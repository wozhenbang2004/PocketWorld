package dev.pocketengine.ui

internal fun taskOverviewAttention(task: UiTask): String? = when {
    (task.raw.opt("historyHasUnconfirmedOperations") as? Boolean ?: task.raw.optBoolean("hasUnresolvedOperations")) || task.status in setOf("reconciling", "reconciliation_required", "unknown") -> "结果待核对 · 查看原操作回执"
    task.status in setOf("waiting_approval", "awaiting_approval", "waiting_permission", "awaiting_permission") -> "需要授权 · 查看本次操作范围"
    task.status in setOf("awaiting_user", "waiting_user") -> "需要你处理 · 打开原任务查看问题"
    task.status == "needs_confirmation" -> "待确认结果 · 查看成果后决定"
    task.status in setOf("paused", "interrupted") -> "等待继续 · 核对原任务状态"
    else -> null
}

internal fun taskOverviewMatches(task: UiTask, filter: String): Boolean {
    val attention = taskOverviewAttention(task) != null
    val ended = task.status in setOf("completed", "failed", "cancelled", "canceled", "stopped")
    return when (filter) {
        "attention" -> attention
        "active" -> !attention && task.status in setOf("queued", "preparing", "running", "pausing", "stopping", "cancelling", "verifying")
        "ended" -> ended && !attention
        else -> true
    }
}
