package dev.pocketengine.ui.web

import dev.pocketengine.ui.UiPhoneResult

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
