package dev.pocketengine.ui

import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.pocketengine.phone.observation.PhoneObservationPreview
import kotlinx.coroutines.delay

/** One disclosure on the original task page. No timer polls, capture loop, retained image cache or input surface. */
@Composable
internal fun InlinePhoneObservation(task: UiTask, state: PocketUiState, controller: PocketWorldController) {
    val identity = controller.phoneInlinePreview
    val opened = identity != null && identity.matchesTask(task.raw) && state.inspectionTaskId == task.id
    val observation = state.inspectionObservation.takeIf { opened }
    val context = LocalContext.current
    val window = LocalView.current
    val lifecycle = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeUnless { base -> base === it } }
            .filterIsInstance<LifecycleOwner>().firstOrNull()
    }
    val run = task.raw.optString("runId")
    val epoch = task.raw.opt("coordinatorEpoch")
    DisposableEffect(task.id, run, epoch, lifecycle, window) {
        fun discard() { if (controller.phoneInlinePreview?.taskId == task.id) controller.closeInspection() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) discard() }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) discard() }
        lifecycle?.lifecycle?.addObserver(observer)
        window.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        onDispose { lifecycle?.lifecycle?.removeObserver(observer); if (window.viewTreeObserver.isAlive) window.viewTreeObserver.removeOnWindowFocusChangeListener(focus); discard() }
    }
    LaunchedEffect(task.id, run, epoch, state.activeTaskId) {
        val current = controller.phoneInlinePreview
        if (current != null && (!current.matchesTask(task.raw) || state.activeTaskId != task.id)) controller.closeInspection()
    }
    val observationId = observation?.optString("observationId")
    val at = observation?.optLong("observedAtUptimeMs", -1) ?: -1
    var now by remember(observationId, at) { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(observationId, at) {
        now = SystemClock.uptimeMillis()
        val remaining = 120_001L - (now - at)
        if (at >= 0 && remaining in 1..120_001) { delay(remaining); now = SystemClock.uptimeMillis() }
    }
    val presentation = remember(identity, observation, now) { PhoneObservationPreview.present(identity, observation, now) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).testTag("phone_inline_preview")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (opened) controller.closeInspection() else controller.openInlinePhonePreview(task, window) },
                enabled = opened || state.activeTaskId == task.id && task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private",
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { stateDescription = if (opened) "已展开" else "已收起" }) {
                Text(if (opened) "收起最近画面" else "展开最近画面")
            }
            OutlinedButton(onClick = { if (opened) controller.expandInlinePhonePreview() else controller.openInspection(task.id) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("独立屏与接管") }
        }
        if (opened) {
            Text("${taskStatus(task.status)} · ${if (presentation.hasFrame() && !presentation.fresh()) "旧快照，仅供查看" else "最近观察，非实时视频"}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (presentation.hasFrame()) {
                ObservationFrame(observation, interactive = false, location = "原任务独立屏", modifier = Modifier.fillMaxWidth().height(208.dp).padding(top = 8.dp)) { _, _ -> }
                Text(presentation.packageName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            } else {
                val message = when (presentation.reason()) {
                    "owner_handoff" -> "应用已交给主人或位置待核对；没有读取主屏。请打开原接管页处理。"
                    "no_app_content" -> "独立屏尚无可核对的 App 画面。"
                    "no_image" -> "原观察未包含图片。运行中不会为预览追加截图。"
                    "original_run_changed" -> "原任务画面身份已变化，请收起后重新核对。"
                    "invalid_image_metadata" -> "原图片尺寸无法核对，未显示画面。"
                    else -> if (state.inspectionBusy) "正在读取原任务的最近观察…" else "尚未取得原任务的画面；可以稍后主动刷新。"
                }
                Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
            }
            state.inspectionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text("运行时只读已有快照；暂停后可主动刷新原副屏。图片仅供查看，操作需进入接管页。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = controller::observeInspection, enabled = !state.inspectionBusy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (state.inspectionBusy) "读取中…" else if (task.status in setOf("paused", "awaiting_user")) "刷新原副屏" else "读取最近观察")
            }
        }
    }
}
