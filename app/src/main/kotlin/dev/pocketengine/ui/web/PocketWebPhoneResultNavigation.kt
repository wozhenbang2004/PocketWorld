package dev.pocketengine.ui.web

import dev.pocketengine.ui.UiPhoneResult
import dev.pocketengine.ui.UiTaskResult

/** Read-only lookup from the native conversation projection, independent of the recent task cache. */
internal fun canReadTaskFromVisiblePhoneResult(
    taskId: String,
    section: String?,
    taskViewId: String?,
    privateOwnerVisible: Boolean,
    conversationId: String?,
    visibleResults: Sequence<UiPhoneResult?>,
): Boolean = section == null && taskViewId == null && privateOwnerVisible &&
    !conversationId.isNullOrBlank() && taskId.isNotBlank() && visibleResults.any { it?.taskId == taskId }

internal fun canReadTaskFromVisibleTaskResult(
    taskId: String, section: String?, taskViewId: String?, privateOwnerVisible: Boolean,
    conversationId: String?, visibleResults: Sequence<UiTaskResult?>,
): Boolean = section == null && taskViewId == null && privateOwnerVisible &&
    !conversationId.isNullOrBlank() && taskId.isNotBlank() && visibleResults.any { it?.taskId == taskId }
