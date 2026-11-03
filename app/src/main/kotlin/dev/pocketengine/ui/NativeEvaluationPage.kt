package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.evaluation.EvaluationDataset
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Owner-only evaluation leaf. Trial progress and cancellation remain attached to the original task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeEvaluationPage(controller: PocketWorldController) {
    if (!controller.nativeEvaluationOpen) return
    var snapshot by remember { mutableStateOf(JSONObject()) }
    var review by remember { mutableStateOf<JSONObject?>(null) }
    var comparison by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    fun command(name: String, input: JSONObject = JSONObject()) {
        if (busy) return
        busy = true; error = null
        controller.evaluationCommand(name, input) { result, failure ->
            busy = false; error = failure
            if (result != null) when {
                result.has("reviewId") -> review = result
                result.has("changes") -> comparison = result
                else -> snapshot = result
            }
        }
    }
    val study = snapshot.optJSONObject("study")
    val rows = snapshot.optJSONArray("tasks").objects()
    val running = rows.any { it.optBoolean("running") }
    val studyId = study?.optString("id").orEmpty()
    LaunchedEffect(Unit) { command("snapshot") }
    LaunchedEffect(snapshot, running) {
        if (running) { delay(1400); if (!busy) command("snapshot", JSONObject().put("studyId", studyId)) }
    }
    ModalBottomSheet(onDismissRequest = controller::closeNativeEvaluation, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("任务评测", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = controller::closeNativeEvaluation) { Text("返回") }
            }
            Text("合成场景 · 原任务执行 · 独立效果核对", style = MaterialTheme.typography.labelMedium)
            Text("从真实工具执行和结果读回判断质量。每项最多6次模型请求，使用你当前明确选择的模型；不会操作真实应用。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { command("create") }, enabled = !busy && !running) { Text("新建实验") }
                TextButton(onClick = { command("snapshot", JSONObject().put("studyId", studyId)) }, enabled = !busy) { Text("刷新") }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (study == null) Text("先建立一组实验，再逐项确认运行。", style = MaterialTheme.typography.bodyMedium)
            else {
                val model = study.getJSONObject("model")
                Text(model.optString("model"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${model.optString("provider")} · ${snapshot.optString("datasetId")} v${snapshot.optString("datasetVersion")}", style = MaterialTheme.typography.labelSmall)
                val frozen = study.optString("frozenStrategy")
                val tuning = snapshot.optJSONObject("tuning") ?: JSONObject()
                Text("调参池 ${tuning.optInt("completed")}/4", style = MaterialTheme.typography.titleSmall)
                for (caseId in EvaluationDataset.TUNE) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (caseId == "tune-a") "订单 A" else "订单 B", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        for (strategy in EvaluationDataset.STRATEGIES) {
                            val existing = rows.find { it.getJSONObject("evaluation").getJSONObject("trial").let { t -> t.optString("caseId") == caseId && t.optString("strategy") == strategy } }
                            TextButton(enabled = !busy && !running && frozen.isBlank() && existing == null,
                                onClick = { command("review", JSONObject().put("studyId", studyId).put("caseId", caseId).put("strategy", strategy)) }) {
                                Text(if (strategy == "baseline") "简洁" else "先核对")
                            }
                        }
                    }
                }
                if (tuning.optInt("completed") == 4) {
                    Text("简洁 ${tuning.optInt("baselinePassed")}/2 · 先核对 ${tuning.optInt("candidatePassed")}/2；假成功分别 ${tuning.optInt("baselineFalseSuccess")} / ${tuning.optInt("candidateFalseSuccess")}", style = MaterialTheme.typography.bodySmall)
                    if (frozen.isBlank()) {
                        Text("冻结一种策略后才开放保留池。冻结后本组不再调参，保留池结果不进入策略推荐。", style = MaterialTheme.typography.bodySmall)
                        Row {
                            for (strategy in EvaluationDataset.STRATEGIES) TextButton(enabled = !busy,
                                onClick = { command("freeze", JSONObject().put("studyId", studyId).put("revision", study.getLong("revision")).put("strategy", strategy)) }) {
                                Text("冻结" + if (strategy == "baseline") "简洁" else "先核对")
                            }
                        }
                    }
                }
                if (frozen.isNotBlank()) {
                    HorizontalDivider()
                    Text("保留池 · 已冻结 ${if (frozen == "baseline") "简洁" else "先核对"}", style = MaterialTheme.typography.titleSmall)
                    Row {
                        for (caseId in EvaluationDataset.HOLDOUT) {
                            val exists = rows.any { it.getJSONObject("evaluation").getJSONObject("trial").optString("caseId") == caseId }
                            TextButton(enabled = !busy && !running && !exists,
                                onClick = { command("review", JSONObject().put("studyId", studyId).put("caseId", caseId).put("strategy", frozen)) }) { Text(if (caseId == "holdout-a") "运行保留 A" else "运行保留 B") }
                        }
                    }
                }
                if (rows.isNotEmpty()) {
                    HorizontalDivider()
                    Text("原任务与结果", style = MaterialTheme.typography.titleSmall)
                    for (row in rows) {
                        val evaluation = row.getJSONObject("evaluation"); val trial = evaluation.getJSONObject("trial"); val probe = evaluation.optJSONObject("probe")
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("${trial.optString("caseId")} · ${if (trial.optString("strategy") == "baseline") "简洁" else "先核对"}", style = MaterialTheme.typography.bodyMedium)
                                Text(if (row.optBoolean("running")) "原任务执行中" else evaluationLabel(probe?.optString("classification") ?: "unverified"), style = MaterialTheme.typography.labelMedium)
                                if (probe != null) Text("来源 ${if (probe.optBoolean("grounded")) "已核对" else "未证实"} · 效果 ${if (probe.isNull("effectMatches")) "未知" else if (probe.optBoolean("effectMatches")) "符合" else "不符"}", style = MaterialTheme.typography.labelSmall)
                            }
                            if (row.optBoolean("running")) TextButton(enabled = !busy, onClick = { command("cancel", JSONObject().put("taskId", row.getString("taskId"))) }) { Text("停止") }
                            else TextButton(enabled = !busy, onClick = { command("probe", JSONObject().put("taskId", row.getString("taskId"))) }) { Text("只读核对") }
                            TextButton(onClick = { controller.closeNativeEvaluation(); controller.focusTask(row.getString("taskId")) }) { Text("任务") }
                        }
                    }
                }
                val studies = snapshot.optJSONArray("studies").objects()
                if (studies.size > 1) {
                    TextButton(onClick = { details = !details }) { Text(if (details) "收起历史与差异" else "历史实验与回归差异") }
                    if (details) for (archived in studies.filter { it.optString("id") != studyId }) {
                        Row {
                            TextButton(enabled = !busy, onClick = { comparison = null; command("snapshot", JSONObject().put("studyId", archived.getString("id"))) }) { Text("实验 ${archived.getString("id").take(8)}") }
                            TextButton(enabled = !busy, onClick = { command("compare", JSONObject().put("beforeStudyId", archived.getString("id")).put("afterStudyId", studyId)) }) { Text("与当前对照") }
                        }
                    }
                    comparison?.let { diff ->
                        Text("同版本已配对 ${diff.optInt("matchedCases")} 项", style = MaterialTheme.typography.bodySmall)
                        diff.optJSONArray("changes").objects().forEach { delta -> Text("${delta.optString("caseId")} · ${evaluationLabel(delta.optString("before"))} → ${evaluationLabel(delta.optString("after"))}${if (delta.optBoolean("regression")) "（回归）" else ""}", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    review?.let { selected ->
        AlertDialog(onDismissRequest = { if (!busy) review = null }, title = { Text("运行这项合成试验？") },
            text = { Text("${selected.getJSONObject("trial").getString("caseId")} · ${selected.getJSONObject("model").getString("model")}\n最多6次物理模型请求、6次工具；会消耗该连接额度。\n只在本机私有合成目录读写，不发送聊天、凭据、私人记忆或真实文件。结果归档到原任务。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { val id = selected.getString("reviewId"); review = null; command("start", JSONObject().put("reviewId", id).put("confirmed", true)) }) { Text("确认运行") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { review = null }) { Text("取消") } })
    }
}

private fun evaluationLabel(value: String) = when (value) {
    "success" -> "真实效果通过"
    "false_success" -> "假成功：声明完成，效果未达标"
    "actual_failure" -> "真实未完成"
    "handover" -> "请求主人接管"
    "skipped" -> "前置条件不可用，未运行"
    "harness_error" -> "执行环境或证据不可核对"
    "source_changed" -> "来源修订已变化"
    "unknown_effect" -> "原操作效果未知，未重放"
    "cancelled" -> "已停止，实际回执保留"
    else -> "尚未完成独立核对"
}
