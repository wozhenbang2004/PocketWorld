package dev.pocketengine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Text

/** The task carries the entry, never a caller-supplied profile JSON as authority. */
@Composable
internal fun WorkProfileTaskRecoveryControls(task: UiTask, controller: PocketWorldController) {
    if (!WorkProfileDrafts.originalOwnerTask(task.raw)) return
    val host = remember(controller) { runCatching { controller.nativeWorkProfiles() }.getOrNull() }
    if (host == null) { Text("原工作配置来源宿主暂不可用，原任务与引用保留；请稍后重新打开核对。"); return }
    WorkProfileTaskRecoveryPanel(task.raw, host, controller::runWorkProfileWork,
        controller::runWorkProfileControl, controller::originalTaskWorkProfileSnapshot,
        controller::reviewOriginalTaskWorkProfile, controller::continueOwnerWorkProfile, controller::reviewWorkProfileProject, controller::continueOwnerWorkProfileProject)
}
