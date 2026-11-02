package dev.pocketengine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pocketengine.R
import dev.pocketengine.features.conversation.TaskPresentation

@Composable
internal fun conversationDateLabel(label: String): String = stringResource(when(label) {
    "置顶" -> R.string.w8_cv_date_pinned; "今天" -> R.string.w8_cv_date_today; "最近七天" -> R.string.w8_cv_date_week; "更早" -> R.string.w8_cv_date_earlier; "其他日期" -> R.string.w8_cv_date_other; else -> R.string.w8_cv_date_unknown
})

@Composable
internal fun conversationTaskLabel(status: String): String = stringResource(when(status) {
    "queued" -> R.string.w8_cv_state_queued; "preparing" -> R.string.w8_cv_state_preparing; "running" -> R.string.w8_cv_state_running
    "pausing" -> R.string.w8_cv_state_pausing; "paused" -> R.string.w8_cv_state_paused; "stopping", "cancelling" -> R.string.w8_cv_state_stopping
    "verifying" -> R.string.w8_cv_state_verifying; "needs_confirmation" -> R.string.w8_cv_state_confirmation
    "waiting_approval", "awaiting_approval" -> R.string.w8_cv_state_approval; "reconciling", "unknown" -> R.string.w8_cv_state_unknown
    "completed" -> R.string.w8_cv_state_completed; "cancelled", "canceled", "stopped" -> R.string.w8_cv_state_cancelled
    "failed" -> R.string.w8_cv_state_failed; "interrupted" -> R.string.w8_cv_state_interrupted; "awaiting_user", "waiting_user" -> R.string.w8_cv_state_waiting; else -> R.string.w8_cv_state_unrecorded
})

@Composable
internal fun conversationTaskNext(status: String): String = stringResource(when(status) {
    "reconciling", "unknown" -> R.string.w8_cv_next_unknown; "needs_confirmation" -> R.string.w8_cv_next_confirmation
    "waiting_approval", "awaiting_approval" -> R.string.w8_cv_next_approval; "paused", "cancelled", "interrupted", "failed", "awaiting_user" -> R.string.w8_cv_next_continue
    "completed" -> R.string.w8_cv_next_completed; else -> R.string.w8_cv_next_original
})

@Composable
internal fun conversationOperationLabel(operation: TaskPresentation.Operation): String = stringResource(when {
    operation.uncertain -> R.string.w8_cv_operation_unknown; operation.state == "prepared" -> R.string.w8_cv_operation_prepared
    operation.awaitingApproval -> R.string.w8_cv_state_approval; operation.label == "工具返回错误" -> R.string.w8_cv_operation_error
    operation.label == "调用已接受" -> R.string.w8_cv_operation_accepted; operation.state == "receipt" -> R.string.w8_cv_operation_receipt; else -> R.string.w8_cv_state_unrecorded
})
