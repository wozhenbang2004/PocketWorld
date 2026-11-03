package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.teams.TeamCoordinator
import dev.pocketengine.features.teams.TeamResearchHost
import dev.pocketengine.store.TaskStore
import org.json.JSONObject

/** Same original pool; explicit owner decision, not an account quota screen or an automatic restart. */
@Composable internal fun TeamResearchBudgetControls(
    run: JSONObject, openPage: (String) -> TeamResearchHost.Page, runWork: (Runnable) -> Unit
) {
    if (run.optString("researchExecutionFormat") != TeamCoordinator.RESEARCH_FORMAT) return
    val runId = run.getString("id")
    var expanded by rememberSaveable(runId) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起研究次数审阅" else "研究次数与继续") }
    if (!expanded) return
    var retry by remember(runId) { mutableIntStateOf(0) }
    val opened = remember(runId, retry) { runCatching { openPage(runId) } }
    val page = opened.getOrNull()
    if (page == null) {
        Text("原研究次数暂不可读：${opened.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { retry++ }) { Text("重新读取原研究") }; return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var current by remember(page) { mutableStateOf<JSONObject?>(null) }
    var models by rememberSaveable(runId) { mutableStateOf("") }
    var reads by rememberSaveable(runId) { mutableStateOf("") }
    var pending by remember(page) { mutableStateOf<TaskStore.TeamResearchBudgetReview?>(null) }
    var shown by remember(page) { mutableStateOf<JSONObject?>(null) }
    var accepted by remember(pending) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    var wakeStatus by remember(page) { mutableStateOf<JSONObject?>(null) }
    var wakeEnabled by remember(page) { mutableStateOf(false) }
    var wakePeer by remember(page) { mutableStateOf(false) }
    var wakePerRound by remember(page) { mutableStateOf("1") }
    var wakeTotal by remember(page) { mutableStateOf("2") }
    var wakeMinutes by remember(page) { mutableStateOf("10") }
    var wakeReview by remember(page) { mutableStateOf<TaskStore.TeamWakePolicyReview?>(null) }
    var wakeShown by remember(page) { mutableStateOf<JSONObject?>(null) }
    var wakeAccepted by remember(wakeReview) { mutableStateOf(false) }
    fun showWakeStatus(value: JSONObject) {
        wakeStatus = value; wakeReview = null; wakeShown = null; wakeAccepted = false
        val policy = value.optJSONObject("policy")
        wakeEnabled = policy?.optBoolean("enabled") == true && policy.optLong("expiresAt") > value.optLong("observedAt")
        wakePeer = wakeEnabled && policy?.optJSONArray("allowedSources")?.let { sources -> (0 until sources.length()).any { sources.optString(it) == "DIRECTED_PEER" } } == true
        wakePerRound = (policy?.optInt("perRoundLimit", 1) ?: 1).toString()
        wakeTotal = (policy?.optInt("cumulativeLimit", 2) ?: 2).toString()
    }
    DisposableEffect(page, lifecycle) {
        fun foreground() {
            val active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            page.setForeground(active)
            if (!active) { wakeReview = null; wakeShown = null; wakeAccepted = false }
        }
        val observer = LifecycleEventObserver { _, _ -> foreground() }; lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; page.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, apply: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable { val value = runCatching(action); main.post { if (alive) { busy = false; value.fold(apply) { notice = it.message ?: "未确认保存；请重读，不重复批准" } } } }) }
        catch (_: Exception) { busy = false; notice = "原工作队列未接手，未保存或启动" }
    }
    LaunchedEffect(page) { work({ page.current() }) { current = it; val pool = it.getJSONObject("parentBudget"); if (models.isEmpty()) models = pool.getInt("modelRequestLimit").toString(); if (reads.isEmpty()) reads = pool.getInt("toolOperationLimit").toString() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamCaption("达到本次调用或读取上限会保留检查点和已完成内容。这是本任务的执行上限，不代表模型账户或订阅余额。先等原成员安全暂停；未知操作不能靠追加次数自动重做。")
        current?.let { facts ->
            val pool = facts.getJSONObject("parentBudget")
            Text("模型 ${pool.getInt("modelRequestsReservedOrUsed")} / ${pool.getInt("modelRequestLimit")} · 读取/消息 ${pool.getInt("toolOperationsReservedOrUsed")} / ${pool.getInt("toolOperationLimit")}", style = MaterialTheme.typography.bodyMedium)
            TeamCaption("包括已用及预留；整理历史的摘要请求也计入同一模型上限。剩余模型 ${pool.getInt("remainingModelRequests")}，读取 ${pool.getInt("remainingToolOperations")}。")
            val states = pool.getJSONObject("reservationStates")
            listOf("reserved" to "预留", "started" to "已派发", "settled" to "已结算", "unknown" to "结果未确定").forEach { (key, label) -> states.optJSONObject(key)?.let { counts -> TeamCaption("$label：模型 ${counts.optInt("modelRequests")}，读取/消息 ${counts.optInt("toolOperations")}") } }
            facts.optJSONObject("parentExecutionScope")?.takeIf { it.optBoolean("scopePresent") }?.let { scope -> TeamCaption("原父任务累计剩余：模型 ${scope.optInt("modelCallsRemaining")}、工具 ${scope.optInt("toolsRemaining")}；本页不能扩大父总额或延长资料授权。") }
            OutlinedTextField(models, { models = it; pending = null; shown = null }, enabled = !busy, label = { Text("新累计模型请求上限（含摘要，最多 ${facts.getInt("modelEngineeringMaximum")}）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(reads, { reads = it; pending = null; shown = null }, enabled = !busy, label = { Text("新累计读取/消息上限（最多 ${facts.getInt("toolEngineeringMaximum")}）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { work({ page.review(models.toInt(), reads.toInt()) }) { pending = it; shown = it.view(); accepted = false } }, enabled = !busy && models.toIntOrNull() != null && reads.toIntOrNull() != null) { Text("核对准确已用与本次追加") }
        }
        pending?.let { exact -> shown?.let { facts ->
            val old = facts.getJSONObject("parentBudget")
            TeamCaption("模型 ${old.getInt("modelRequestLimit")} → ${facts.getInt("proposedModelRequestLimit")}；读取/消息 ${old.getInt("toolOperationLimit")} → ${facts.getInt("proposedToolOperationLimit")}。旧已用、预留和未确定用量均保留；工具集、资料范围和期限不变。")
            Row { Checkbox(accepted, { accepted = it }, enabled = !busy); Text("我明确批准以上原累计新上限，不自动启动或重做") }
            Button(onClick = { work({ page.approve(exact, facts.getString("digest")) }) { saved -> pending = null; shown = null; current = JSONObject(facts.toString()).put("parentBudget", saved.getJSONObject("parentBudget")); notice = "次数已保存，尚未启动。请刷新原团队后，明确继续原成员；已完成读取与结果不会重做。" } }, enabled = accepted && !busy) { Text("仅保存本次追加次数") }
        } }
        TextButton(onClick = { work({ page.synchronize() }) { current = it; pending = null; notice = "已重读原保存次数，未启动执行。" } }, enabled = !busy) { Text("重读保存的次数（不启动）") }
        TextButton(onClick = { work({ page.wakePolicyStatus() }, ::showWakeStatus) }, enabled = !busy) { Text("审阅研究成员自动续接") }
        wakeStatus?.let { observed ->
            val policy = observed.optJSONObject("policy")
            val observedAt = observed.getLong("observedAt")
            val active = policy?.optBoolean("enabled") == true && policy.optLong("expiresAt") > observedAt
            TeamCaption("研究自动续接：${if (active) "已启用" else "未启用或已到期"}；策略版本 ${observed.optLong("policyRevision")}；同原父预算已实际续接 ${observed.optJSONObject("nativeClaims")?.optInt("total") ?: 0} 次。保存或关闭不清零。")
            observed.optString("researchWakeNotice").takeIf { it.isNotEmpty() }?.let { TeamCaption(it) }
            TeamCaption("原父任务已授权的控制消息，可继续已存在的同成员、同阶段研究检查点。另勾选下项才允许同队peer，旧控制策略不会自动升级。消息内容不授新权限；后台消息、写入、脚本与手机操作未开放。主人暂停/停止、待答问题、待审批、未知结果、材料撤回、原范围到期或预算不足仍阻止执行。")
            policy?.let { TeamCaption("当前策略截至 ${java.util.Date(it.getLong("expiresAt"))}；每轮 ${it.getInt("perRoundLimit")} 次，原父累计 ${it.getInt("cumulativeLimit")} 次。") }
            Row { Checkbox(wakeEnabled, { wakeEnabled = it; wakeReview = null; wakeShown = null }, enabled = !busy); Text("明确允许以上原范围内的自动续接") }
            Row { Checkbox(wakePeer, { wakePeer = it; wakeReview = null; wakeShown = null }, enabled = !busy); Text("另行允许同队已结算成员消息续接（默认关闭）") }
            TeamCaption("peer只在发送者原请求已确定结束、真实Runner和读取端口已释放后考虑；双方须保持同一有效资料范围覆盖。只继续原安全检查点，不重做已完成分工、不解除主人暂停、不恢复冷/旧父控制。仍共用上述单轮、累计与原父预算。")
            OutlinedTextField(wakePerRound, { wakePerRound = it; wakeReview = null; wakeShown = null }, label = { Text("每轮自动续接上限（1–8）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(wakeTotal, { wakeTotal = it; wakeReview = null; wakeShown = null }, label = { Text("同原父预算累计上限（每轮上限–64）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(wakeMinutes, { wakeMinutes = it; wakeReview = null; wakeShown = null }, label = { Text("策略有效分钟（1–1440，不延长原范围）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            val validWake = wakePerRound.toIntOrNull()?.let { per -> per in 1..8 && wakeTotal.toIntOrNull()?.let { it in per..64 } == true } == true && wakeMinutes.toLongOrNull()?.let { it in 1L..1440L } == true
            OutlinedButton(onClick = { work({ page.reviewWakePolicy(wakeEnabled, wakePeer, wakePerRound.toInt(), wakeTotal.toInt(), Math.addExact(System.currentTimeMillis(), Math.multiplyExact(wakeMinutes.toLong(), 60000L)), observed.optLong("policyRevision")) }) { wakeReview = it; wakeShown = it.view(); wakeAccepted = false } }, enabled = !busy && validWake) { Text("核对研究自动续接策略（不启动）") }
            wakeReview?.let { exact -> wakeShown?.let { shownPolicy ->
                val proposed = shownPolicy.getJSONObject("policy")
                TeamCaption("本次${if (proposed.getBoolean("enabled")) "启用" else "关闭"}原父控制消息续接，peer ${if (proposed.getJSONArray("allowedSources").let { sources -> (0 until sources.length()).any { sources.optString(it) == "DIRECTED_PEER" } }) "明确允许" else "未允许"}；每轮 ${proposed.getInt("perRoundLimit")} 次，累计 ${proposed.getInt("cumulativeLimit")} 次；已实际续接 ${shownPolicy.getJSONObject("originalClaims").getInt("total")} 次。实际策略截至 ${java.util.Date(proposed.getLong("expiresAt"))}，不超过原研究范围期限。")
                TeamCaption("保存不启动、不补预算、不扩大资料或工具权限；旧消息不因策略修改而重新采用。离开前台、来源或策略变化后须重新审阅；停止请求也不等于撤回已发生的效果。")
                Row { Checkbox(wakeAccepted, { wakeAccepted = it }, enabled = !busy); Text("我明确确认上述来源、次数与期限，仅保存策略") }
                Button(onClick = { work({ page.approveWakePolicy(exact, shownPolicy.getString("digest")); page.wakePolicyStatus() }) { showWakeStatus(it); notice = "研究策略已保存，未启动或增加次数。仅后续新消息在原安全点复核后可续接；旧意图保留。" } }, enabled = !busy && wakeAccepted) { Text("仅保存研究续接策略（不启动）") }
            } }
            val intents = observed.optJSONArray("intents")
            if (intents != null && intents.length() > 0) {
                TeamCaption("保留 ${intents.length()} 条原意图，显示最近 ${minOf(8, intents.length())} 条；截至 ${java.util.Date(observedAt)}，实际派发仍会复核。")
                for (index in maxOf(0, intents.length() - 8) until intents.length()) {
                    val intent = intents.getJSONObject(index)
                    val reason = if (intent.optString("status") in setOf("queued", "blocked")) when {
                        policy?.optBoolean("enabled") != true -> "policy_disabled"
                        intent.optLong("policyRevision") != observed.optLong("policyRevision") -> "policy_revision_changed"
                        policy.optLong("expiresAt") <= observedAt -> "expired"
                        observed.optString("nativeParentControlState") == "stopped_or_ended" -> "owner_stopped"
                        observed.optString("nativeParentControlState") == "owner_paused" -> "owner_paused"
                        observed.optString("nativeParentControlState") in setOf("unbound_cold_or_legacy", "control_generation_changed") -> "source_currentness_unknown"
                        else -> intent.optString("reason")
                    } else intent.optString("reason")
                    Text("${intent.getJSONObject("binding").getJSONObject("target").getString("memberId")} · ${intent.optString("status")} · ${researchWakeReason(reason)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { work({ page.wakePolicyStatus() }, ::showWakeStatus) }, enabled = !busy) { Text("重读研究策略与阻止原因（不重投）") }
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun researchWakeReason(reason: String): String = when (reason) {
    "policy_disabled" -> "策略已关闭，原消息保留"
    "policy_revision_changed" -> "策略已换版，旧消息不会重新采用"
    "expired" -> "策略已到期，请重审后只用新消息"
    "owner_paused" -> "主人已暂停，不自动恢复"
    "owner_stopped" -> "原任务已停止，不自动恢复"
    "not_safe_idle" -> "尚非安全点，或仍待回答/审批"
    "original_effect_unknown", "original_outcome_unknown_query_only" -> "原结果未确定，只核对不重投"
    "original_continuation_active_or_unknown" -> "同成员原续接仍活跃或未知"
    "stale_parent_run_epoch", "stale_target_identity_run_epoch" -> "父执行或目标身份已换代"
    "source_revoked", "source_currentness_unknown" -> "原来源已撤回或无法核对"
    "original_budget_exhausted" -> "原预算不足，不自动补额"
    "cumulative_wake_limit", "round_wake_limit" -> "已达明确续接上限"
    "original_completed" -> "原研究续接已真实完成"
    "original_failed", "original_cancelled" -> "原续接失败或取消，不自动重试"
    else -> reason
}
