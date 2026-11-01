package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.store.TaskStore
import dev.pocketengine.features.teams.TaskStoreTeamWriteHost
import org.json.JSONObject

/** One explicit owner scope, not one approval dialog for every private draft write. */
@Composable internal fun TeamDraftScopeControls(runId: String, openPage: (String) -> TaskStoreTeamWriteHost.DraftScopePage, runWork: (Runnable) -> Unit) {
    var openingAttempt by remember(runId) { mutableIntStateOf(0) }
    val opened = remember(runId, openingAttempt) { runCatching { openPage(runId) } }
    val page = opened.getOrNull()
    if (page == null) {
        Text("原隔离草稿审阅暂不可用：${opened.exceptionOrNull()?.message ?: "原团队任务尚未恢复"}", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        TextButton(onClick = { openingAttempt++ }) { Text("重新核对原团队") }
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var current by remember(page) { mutableStateOf(JSONObject()) }
    var review by remember(page) { mutableStateOf<TaskStore.TeamDraftReview?>(null) }
    var shown by remember(page) { mutableStateOf(JSONObject()) }
    var accepted by remember(review) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    DisposableEffect(page, lifecycle) {
        fun foreground() = page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> foreground() }
        lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; page.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, accept: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable {
            val result = runCatching(action)
            main.post { if (alive) { busy = false; result.fold(accept) { notice = it.message ?: "请重新核对原团队授权，未重放文件操作" } } }
        }) } catch (_: Exception) { busy = false; notice = "原工作队列不可用，尚未开始" }
    }
    LaunchedEffect(page) { work({ page.snapshot() }) { current = it } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamCaption("一次明确授权后，成员可在各自隔离草稿内连续准备、编辑和冻结；不会因此写原项目。")
        if (current.optString("state") == "active") {
            TeamCaption("当前范围剩余 ${current.optInt("remainingOperations")} 次草稿操作；到期、撤销、改派或修订后须重审。回答问题不延长授权或预算。")
            TextButton(onClick = { work({ page.revoke(current.getString("grantId")) }) { current = it; review = null; notice = "已撤销后续草稿派发；已发生的操作保留，未知操作仍须核对。" } }, enabled = !busy) { Text("撤销隔离草稿授权") }
        }
        OutlinedButton(onClick = { work({ page.review() }) { review = it; shown = it.view(); accepted = false } }, enabled = !busy) { Text("审阅本次隔离草稿范围与计划预算") }
        review?.let { exact ->
            shown.optJSONArray("targets").objects().forEach { target ->
                Text("${target.optString("memberName", target.optString("memberId"))} · 原分工修订 ${target.optInt("assignmentRevision")}", fontSize = 14.sp)
                target.optJSONArray("files").objects().forEach { file -> TeamCaption("${file.optString("path")} · 基线 v${file.optInt("baseVersion")} · ${file.optString("baseHash").take(12)}") }
            }
            TeamCaption("仅准备独立草稿、编辑这些路径、冻结私有成果；原项目整合仍需独立审查和准确版本批准。")
            TeamCaption("本次模型请求上限 ${shown.optInt("previousModelRequestLimit")} → ${shown.optInt("proposedModelRequestLimit")}；工具操作上限 ${shown.optInt("previousToolOperationLimit")} → ${shown.optInt("proposedToolOperationLimit")}。")
            TeamCaption("最低准备/写入/冻结 ${shown.optInt("minimumDraftOperations")} 次，加最终逐文件整合共 ${shown.optInt("minimumToolOperationsIncludingIntegration")} 次；模型规划 ${shown.optInt("estimatedModelRequests")} 次，显式重试余量 ${shown.optInt("explicitRetryMargin")}。原父操作剩余 ${shown.optInt("parentRemainingOperations")}，不增加父上限。token/费用未形成硬上限。")
            TeamCaption("期限沿原父授权：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(shown.optLong("expiresAt")))}；后续范围变化不会自动续期。")
            if (!shown.optBoolean("canCompleteMinimumPlan")) Text("原父预算不足以完成最低计划；请先调整原父任务，不启动必然中断的制作。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Row { Checkbox(accepted, { accepted = it }, enabled = !busy); Text("我确认以上准确范围与本次可见预算变更", fontSize = 13.sp) }
            Button(onClick = { work({ page.approve(exact, shown.getString("digest")) }) { current = it; review = null; notice = "原范围与预算已保存，${it.optInt("continuationsQueued")} 名等待成员已回原分工继续。" } }, enabled = !busy && accepted && shown.optBoolean("canCompleteMinimumPlan")) { Text(if (busy) "正在保存原授权…" else "允许隔离草稿内自主工作并继续") }
        }
        notice?.let { Text(it, fontSize = 13.sp) }
    }
}
