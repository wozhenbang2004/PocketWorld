package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.controlplane.NativeWorkProfileHost
import org.json.JSONObject

/** Inline disclosure on the same task, using its retained intent/current run, not the reusable list. */
@Composable
internal fun WorkProfileTaskRecoveryPanel(task: JSONObject, host: NativeWorkProfileHost,
    runWork: (Runnable) -> Unit, runControl: (Runnable) -> Unit,
    originalSnapshot: (String, String, Int) -> JSONObject,
    inspect: (NativeWorkProfileHost.Page, String, String, Int, RunControl) -> NativeWorkProfileHost.Review,
    continueTask: (String, NativeWorkProfileHost.Selection, RunControl, (Result<JSONObject>) -> Unit) -> Unit) {
    val id = task.getString("id")
    val run = task.optString("runId")
    val epoch = task.optInt("coordinatorEpoch")
    val scope = task.optJSONObject("executionScope")
    val page = remember(host, id, run, epoch, scope?.optLong("revision")) { host.newOwnerPage() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var foreground by remember(page) { mutableStateOf(false) }
    var expanded by remember(id) { mutableStateOf(false) }
    var busy by remember(page) { mutableStateOf(false) }
    var continuing by remember(page) { mutableStateOf(false) }
    var frozen by remember(page) { mutableStateOf<JSONObject?>(null) }
    var review by remember(page) { mutableStateOf<NativeWorkProfileHost.Review?>(null) }
    var reviewed by remember(page) { mutableStateOf(JSONObject()) }
    var control by remember(page) { mutableStateOf<RunControl?>(null) }
    var consent by remember(review) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    val recoverable = task.optString("status") in setOf("queued", "paused", "awaiting_user", "needs_confirmation", "interrupted", "failed", "cancelled")

    DisposableEffect(page, lifecycle) {
        fun sync() {
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            page.setForeground(foreground)
            if (!foreground && !continuing) { control?.cancel(); review = null; consent = false }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer); sync()
        onDispose { alive = false; if (!continuing) control?.cancel(); page.close(); lifecycle.removeObserver(observer) }
    }
    fun failure(error: Throwable): String = if (error is InterruptedException)
        "本次核对或继续已停止，请查看这项原任务的回执；不自动重试。"
    else "原选择、来源、代次或范围已变化，请刷新原任务并重新审阅。原任务与累计预算保留，没有改用默认模型或创建替代任务。"
    fun <T> work(action: () -> T, accept: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable {
            val result = runCatching(action)
            main.post { if (alive) { busy = false; result.fold(accept, { notice = failure(it) }) } }
        }) } catch (unavailable: Exception) { busy = false; notice = failure(unavailable) }
    }
    fun refresh() { review = null; work({ originalSnapshot(id, run, epoch) }) { frozen = it } }
    fun begin() {
        val inspected = review ?: return
        val originalControl = control ?: return
        if (busy || !foreground || !consent || scope == null || !recoverable || !WorkProfileDrafts.runnable(reviewed)) return
        busy = true; continuing = true; notice = null
        val complete: (Result<JSONObject>) -> Unit = { result -> main.post { if (alive) {
            busy = false; continuing = false; review = null; consent = false
            result.fold({ notice = if (it.optString("status") == "failed") it.optString("content") else "本次回执已回到这项原任务。请核对结果；没有续给预算或重放旧操作。" }, { notice = failure(it) })
        } } }
        try { runWork(Runnable {
            runCatching { page.select(inspected, inspected.digest, originalControl) }.fold({ selected ->
                try { continueTask(id, selected, originalControl, complete) } catch (unavailable: Exception) { complete(Result.failure(unavailable)) }
            }, { complete(Result.failure(it)) })
        }) } catch (unavailable: Exception) { complete(Result.failure(unavailable)) }
    }
    LaunchedEffect(page, expanded) { if (expanded) refresh() }
    Column(Modifier.fillMaxWidth().testTag("original_task_work_profile")) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (expanded) "收起原任务工作配置" else "原任务工作配置 · 核对引用与继续")
        }
        if (expanded) SettingsSurface("保留原选择，继续同一任务", "冻结引用不随全局模型、来源更新或可重复定义的移除而替换。预检只核对当前来源，不表示已经执行。") {
            Text(if (run.isEmpty()) "原目标尚未启动 · 保留的工作配置意图" else "原执行代次 $epoch · ${run.takeLast(8)}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            frozen?.let { original ->
                Text(original.optJSONObject("definition")?.optString("name").orEmpty() + " · 配置修订 ${original.optLong("profileRevision")}", style = MaterialTheme.typography.titleSmall)
                val policies = original.optJSONObject("modelPolicies")
                listOf("MAIN" to "主任务", "SUMMARY" to "摘要").forEach { (purpose, label) ->
                    val binding = policies?.optJSONObject(purpose)
                    Text("$label · ${binding?.optString("model", binding.optString("modelId")) ?: "原快照未指定"}", style = MaterialTheme.typography.bodySmall)
                }
                policies?.optJSONObject("RELAY")?.let { relay -> Text("原任务活动网关模型 · ${relay.optString("model")}；原网关审批仍需单独核对，不代表目标配置已应用。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
                val concurrency = original.optJSONObject("resourcePreferences")?.optInt("maxConcurrentModelCalls", 0) ?: 0
                Text(if (concurrency == 0) "模型并发保留原宿主限制" else "本任务模型并发最多 $concurrency 路，继续时仍与原宿主限制求交；旧代次未退出的调用仍占位。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                val instructions = original.optJSONObject("definition")?.optJSONArray("instructionRefs")?.length() ?: 0
                if (instructions > 0) Text("保留 $instructions 项准确任务指导引用，每次请求在原参考层重新核对全文，不增加技能执行权限。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            }
            if (scope == null) Text("此原目标尚无主人执行范围。请先在上方“任务范围与累计预算”明确配置，再重新核对；本入口不创建默认授权。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else Text("保留累计预算：工具 ${scope.optInt("toolsUsed")} / ${scope.optInt("toolLimit")} · 模型 ${scope.optInt("modelCallsUsed")} / ${scope.optInt("modelCallLimit")}。到期、耗尽、未核对效果及待审批仍由原门阻塞。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            if (!recoverable) Text("此任务当前状态仅可查看，不从这里并发启动或重开已完成任务。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { refresh() }, enabled = !busy) { Text("刷新冻结记录") }
                if (busy) TextButton(onClick = { val current = control; if (current != null) try { runControl(Runnable { current.cancel() }) } catch (_: Exception) { notice = "停止请求尚未入队，请用原任务停止入口核对。" } }) { Text(if (continuing) "请求停止原任务" else "取消预检") }
            }
            Button(onClick = {
                val observing = RunControl(); control = observing; review = null
                work({ inspect(page, id, run, epoch, observing) }) { review = it; reviewed = it.view() }
            }, enabled = !busy && foreground && frozen != null) { Text("核对这项原任务的准确来源") }
            if (review != null) {
                Text(if (reviewed.optBoolean("ready")) "原来源预检通过 · 尚未继续" else "原来源有阻塞 · 未继续", style = MaterialTheme.typography.titleSmall)
                val items = reviewed.optJSONArray("items")
                for (index in 0 until (items?.length() ?: 0)) {
                    val item = items!!.getJSONObject(index)
                    Text("${item.optString("kind")} · ${item.optString("id")} · ${item.optString("status")}${if (item.optBoolean("optional") && !item.optBoolean("included")) "（明确省略可选项）" else ""}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                }
                if (!WorkProfileDrafts.runnable(reviewed)) Text("原配置含本入口尚未应用的字段或模型不完整。只保留引用，不静默忽略、改写原任务或替换模型。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it }, enabled = !busy && foreground && scope != null && recoverable)
                    Text("只继续这项原任务，保留原范围、累计预算与旧回执；新的外部效果仍逐项走原授权。", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { begin() }, enabled = !busy && foreground && scope != null && recoverable && consent && WorkProfileDrafts.runnable(reviewed), modifier = Modifier.heightIn(min = 48.dp).testTag("continue_original_work_profile_task")) { Text("明确继续这项原任务") }
            }
        }
    }
}
