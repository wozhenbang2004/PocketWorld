package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.skills.SkillExecution
import org.json.JSONArray

/** This is a UI fence; only the native HostPort can validate/mint an actual execution approval. */
internal object SkillExecutionReviewGuard {
    @JvmStatic fun canApprove(displayedDigest: String?, currentDigest: String?, expiresAt: Long, now: Long, busy: Boolean, acknowledged: Boolean): Boolean =
        displayedDigest != null && displayedDigest.matches(Regex("[a-f0-9]{64}")) && displayedDigest == currentDigest &&
            expiresAt > now && now >= 0 && !busy && acknowledged
}

/** Native leaf only. currentPlanDigest must come from the host's current-plan validation. */
@Composable
internal fun SkillExecutionReviewPage(
    plan: SkillExecution.Plan?,
    currentPlanDigest: String?,
    nowMillis: Long,
    busy: Boolean,
    message: String?,
    scriptPreview: String?,
    scriptPreviewPlanDigest: String?,
    onReadScript: (SkillExecution.Plan) -> Unit,
    onApprove: (SkillExecution.Plan, String) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    val digest = plan?.digest
    val review = remember(digest) { plan?.review() }
    var acknowledged by remember(digest) { mutableStateOf(false) }
    var details by remember(digest) { mutableStateOf(false) }
    var submitted by remember(digest) { mutableStateOf(false) }
    val current = plan != null && SkillExecutionReviewGuard.canApprove(digest, currentPlanDigest, plan.expiresAt, nowMillis, busy || submitted, acknowledged)
    BackHandler { onBack() }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回") }
                Text("审阅本次脚本执行", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (busy || submitted) TextButton(onClick = onCancel) { Text("停止") }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, fontSize = 14.sp, lineHeight = 22.sp) }
                if (plan == null || review == null) {
                    Text("当前脚本执行不可用", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("需要可验证文件、网络、超时和取消限制的执行端，以及本任务的具体授权。技能文件仍可作为参考读取。", fontSize = 14.sp, lineHeight = 22.sp)
                } else {
                    val source = review.getJSONObject("package")
                    val request = review.getJSONObject("request")
                    val backend = review.getJSONObject("backend")
                    Text(request.getString("scriptPath"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    ExecutionReviewValue("运行时", "${backend.getString("runtimeId")} · ${backend.getString("runtimeVersion")}")
                    ExecutionReviewValue("执行位置", "${backend.getString("backendId")} / ${backend.getString("workspaceId")}")
                    if (backend.optString("workspaceMode") == "isolated_copy") {
                        Text("脚本写入本次独立执行副本；文件成果需另行审阅导入，项目原文件不会自动改变。", fontSize = 14.sp, lineHeight = 22.sp)
                    }
                    if (backend.optString("processPolicy") == "single_process") {
                        Text("此执行端仅运行单个 Python 进程，脚本不能创建子进程或线程。", fontSize = 13.sp, lineHeight = 21.sp)
                    }
                    ExecutionReviewValue("工作目录", review.getString("cwd"))
                    ExecutionReviewValue("参数 · 每行是一项原始 argv", executionReviewLines(review.getJSONArray("argv")))
                    ExecutionReviewValue("可读取的工作区路径", executionReviewLines(request.getJSONArray("readPaths")))
                    ExecutionReviewValue("可写入的工作区路径", executionReviewLines(request.getJSONArray("writePaths")))
                    ExecutionReviewValue("输出目标", executionReviewLines(request.getJSONArray("outputPaths")))
                    Text("不以 / 结尾的范围仅包含该文件；以 / 结尾的范围包含该目录及子目录。", fontSize = 13.sp, lineHeight = 21.sp)
                    ExecutionReviewValue("网络与输入", "网络禁止；标准输入关闭。技能包只读挂载。")
                    ExecutionReviewValue("执行与输出上限", "${request.getInt("timeoutSeconds")} 秒；输出 ${request.getInt("maxOutputBytes")} 字节；回执 ${request.getInt("maxResultChars")} 字符。")
                    ExecutionReviewValue("脚本 SHA-256", review.getString("scriptSha256"))
                    ExecutionReviewValue("运行时 SHA-256", backend.getString("runtimeSha256"))
                    if (backend.optString("runtimeImageSha256").isNotEmpty()) {
                        ExecutionReviewValue("运行时镜像 SHA-256", backend.getString("runtimeImageSha256"))
                    }
                    TextButton(onClick = { onReadScript(plan) }, enabled = !busy && !submitted) { Text("读取当前版本的脚本内容") }
                    if (scriptPreview != null && scriptPreviewPlanDigest == digest) {
                        SelectionContainer { Text(scriptPreview, fontSize = 13.sp, lineHeight = 21.sp) }
                    }
                    TextButton(onClick = { details = !details }) { Text(if (details) "收起包与任务身份" else "查看包、资源和任务身份") }
                    if (details) {
                        ExecutionReviewValue("技能包", "${source.getString("id")} · revision ${source.getInt("revision")}\n${source.getString("digest")}")
                        ExecutionReviewValue("计划 SHA-256", plan.digest)
                        ExecutionReviewValue("任务 / 运行 / 代次", "${plan.run().taskId}\n${plan.run().runId}\n${plan.run().epoch}")
                        ExecutionReviewValue("执行端安装 / 工作区版本", "${backend.getString("installationId")}\n${backend.getString("workspaceRevision")}")
                        val resources = review.getJSONArray("resources")
                        for (i in 0 until resources.length()) {
                            val resource = resources.getJSONObject(i)
                            ExecutionReviewValue(resource.getString("path"), "${resource.getInt("bytes")} 字节\n${resource.getString("sha256")}")
                        }
                    }
                    if (currentPlanDigest != digest || nowMillis >= plan.expiresAt) Text("计划已变化或过期，请重新审阅后执行。", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    Text("批准仅覆盖本次显示的计划。输出是未验证的外部内容；进程退出或接收回执仍需核对任务结果。", fontSize = 13.sp, lineHeight = 21.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = !busy && !submitted)
                        Text("我已审阅脚本、参数和本次读写范围", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = {
                        if (SkillExecutionReviewGuard.canApprove(digest, currentPlanDigest, plan.expiresAt, nowMillis, busy || submitted, acknowledged)) {
                            submitted = true
                            onApprove(plan, plan.digest)
                        }
                    }, enabled = current, modifier = Modifier.fillMaxWidth()) { Text(if (submitted) "本次请求已提交，请核对原回执" else "批准本次执行") }
                    if (submitted && !busy) TextButton(onClick = onCancel) { Text("请求停止原操作") }
                }
            }
        }
    }
}

@Composable
private fun ExecutionReviewValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, fontSize = 14.sp, lineHeight = 22.sp) }
    }
}

private fun executionReviewLines(values: JSONArray): String = if (values.length() == 0) "无" else
    (0 until values.length()).joinToString("\n") { index -> values.getString(index) }
