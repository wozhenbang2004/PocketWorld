package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.skills.NativeSkillExecutionHost
import dev.pocketengine.features.skills.SkillExecutionConfiguration
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import org.json.JSONObject

private data class SkillArtifactExport(val operation: String, val id: String, val sha256: String, val name: String)

/** Same-task native leaf. Registry, grants, execution and receipt authority remain in their modules. */
@Composable
internal fun TaskCapabilityPage(taskId: String, host: NativeSkillExecutionHost, runWork: (Runnable) -> Unit,
    readSnapshot: () -> JSONObject, readArtifact: (String, String, String) -> ByteArray,
    onPrepareMcp: (String, String) -> Unit, onBack: () -> Unit, runControlWork: (Runnable) -> Unit = runWork, jsHost: dev.pocketengine.features.NativePluginHost? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    val alive = remember(taskId) { AtomicBoolean(true) }
    val window = remember(host, taskId) { host.openOwnerWindow() }
    val approvalWindow = remember(host, taskId) { host.openOwnerWindow() }
    var snapshot by remember(taskId) { mutableStateOf(JSONObject()) }
    var review by remember(taskId) { mutableStateOf<NativeSkillExecutionHost.Review?>(null) }
    var currentDigest by remember(taskId) { mutableStateOf<String?>(null) }
    var showReview by remember(taskId) { mutableStateOf(false) }
    var configReview by remember(taskId) { mutableStateOf<SkillExecutionConfiguration.Review?>(null) }
    var configAcknowledged by remember(configReview) { mutableStateOf(false) }
    var removeConfig by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf<String?>(null) }
    var scriptDigest by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var export by remember { mutableStateOf<SkillArtifactExport?>(null) }
    var visibleExecutions by remember(taskId) { mutableIntStateOf(6) }
    val latestShowReview by rememberUpdatedState(showReview)
    DisposableEffect(window, approvalWindow, lifecycle) {
        fun foreground() { val active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); window.setForeground(active); approvalWindow.setForeground(active && latestShowReview) }
        val observer = LifecycleEventObserver { _, _ -> foreground() }
        lifecycle.addObserver(observer); foreground()
        onDispose { alive.set(false); window.close(); approvalWindow.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, accept: (T) -> Unit) {
        if (busy || !alive.get()) return
        busy = true; error = null
        try { runWork(Runnable { val result = runCatching(action); main.post { if (alive.get()) { busy = false; result.fold(accept) { error = it.message ?: "原能力回执尚未取得，请返回原任务核对" } } } }) }
        catch (_: Exception) { busy = false; error = "原生工作队列不可用，本次请求未开始" }
    }
    fun refresh() {
        currentDigest = null
        work({ val actual = readSnapshot(); check(actual.optString("taskId") == taskId) { "能力来源不是此任务" }; val pending = host.pendingReview(taskId); Triple(actual, pending, pending?.let(host::validateReview)) }) {
            (actual, pending, digest) -> snapshot = actual; review = pending; currentDigest = digest
        }
    }
    val pickConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work({
            check(uri.scheme == "content") { "请选择系统文件提供器中的执行配置" }
            val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val bytes = ByteArray(4096)
                while (out.size() <= 16384) { val read = stream.read(bytes,0,minOf(bytes.size,16385-out.size())); if (read < 0) break; if (read > 0) out.write(bytes,0,read) }
                out.toByteArray()
            } ?: throw IllegalStateException("无法读取所选配置")
            check(raw.size <= 16384) { "执行配置超过16KB，请使用执行端导出的配置" }
            host.inspectConfiguration(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString())
        }) { configReview = it }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val selected = export; export = null
        if (uri != null && selected != null) work({
            check(uri.scheme == "content") { "请选择系统文件提供器中的导出位置" }
            val bytes = readArtifact(selected.operation, selected.id, selected.sha256)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: throw IllegalStateException("无法打开导出位置")
            bytes.size
        }) { notice = "已导出原任务成果（$it 字节），未自动写入其他项目" }
    }
    LaunchedEffect(taskId) { refresh() }
    LaunchedEffect(taskId) { while (true) { delay(1000); now = System.currentTimeMillis(); if (!busy && runCatching { host.pendingReview(taskId) !== review }.getOrDefault(false)) refresh() } }
    fun leaveReview() { approvalWindow.setForeground(false); showReview = false }
    fun cancelCurrentReview() {
        val retained = review ?: return
        approvalWindow.setForeground(false)
        try { runControlWork(Runnable {
            val failure = runCatching { host.cancelReview(retained) }.exceptionOrNull()
            main.post { if (alive.get()) { error = failure?.message; notice = "已请求停止原操作，请核对已有效果"; leaveReview() } }
        }) } catch (_: Exception) { error = "停止请求未进入控制队列，请使用原任务停止入口" }
    }
    Dialog(onDismissRequest = { window.close(); approvalWindow.close(); onBack() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (showReview) SkillExecutionReviewPage(review?.plan, currentDigest, now, busy, error ?: notice ?: "原任务：${snapshot.optString("goal")}", script, scriptDigest,
            { _ -> review?.let { retained -> work({ host.readScript(retained).getString("content") }) { script = it; scriptDigest = retained.plan.digest } } },
            { _, digest -> review?.let { retained -> work({ host.decide(approvalWindow, retained, digest, true); readSnapshot() }) { snapshot = it; currentDigest = null; notice = "已批准这一次具体计划，执行和成果仍在原任务"; leaveReview() } } },
            ::cancelCurrentReview, ::leaveReview)
        else Surface(Modifier.fillMaxSize(), color = Color.White) {
            BackHandler { window.close(); approvalWindow.close(); onBack() }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { TextButton(onClick = { window.close(); onBack() }) { Text("返回原任务") }; Spacer(Modifier.weight(1f)); TextButton(onClick = ::refresh, enabled = !busy) { Text("刷新") } }
                HorizontalDivider(color = Line)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("这项任务的技能与工具", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text(snapshot.optString("goal"), fontSize = 14.sp, lineHeight = 23.sp)
                    Caption("只准备这项任务可能需要的能力。已有配置会复用；安装、工具可用和本次执行批准分别核对。")
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 21.sp) }; notice?.let { Text(it, fontSize = 13.sp, lineHeight = 21.sp) }
                    if (review != null) {
                        Heading("当前任务正在等你审阅脚本"); Caption("批准前没有派发脚本。返回不会自动批准；审阅过期后原任务会等待。")
                        Button(onClick = { approvalWindow.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)); showReview = true }, enabled = !busy && currentDigest == review?.plan?.digest && now < (review?.plan?.expiresAt ?: 0)) { Text("审阅本次执行") }
                    }
                    val skills = snapshot.optJSONArray("skills").objects()
                    if (skills.isNotEmpty()) { Heading("与目标相关的已安装技能"); skills.forEach { skill -> Text(skill.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.Medium); Caption(skill.optString("description")); Caption("版本 ${skill.optInt("revision")} · ${taskSkillReadiness(skill.optString("status"))}") } }
                    else if (!busy) Caption("当前没有匹配的已安装技能。任务仍可使用原有工具；这里不会自动安装不相关的包。")
                    snapshot.optJSONArray("pluginNeeds").objects().forEach { need ->
                        HorizontalDivider(color = Line); Heading(need.optString("pluginId") + " · " + need.optString("tool"))
                        Caption(if (need.optBoolean("available")) "工具已配置并获工具权限；本次敏感调用仍需原任务按参数批准。" else "此技能缺少这项工具，准备时只核对此服务与所需工具。")
                        if (!need.optBoolean("available")) OutlinedButton(onClick = { onPrepareMcp(need.getString("pluginId"), need.getString("tool")) }, enabled = !busy) { Text("准备这项工具") }
                    }
                    val configuration = snapshot.optJSONObject("configuration") ?: JSONObject()
                    if (snapshot.optBoolean("scriptPreparationRelevant")) {
                    HorizontalDivider(color = Line); Heading("脚本执行位置")
                    Caption(if (configuration.optBoolean("configured")) "已保存 ${configuration.optJSONObject("deployment")?.optString("profileId")} 的固定执行配置；每次运行前仍会核对实际隔离证据。" else "脚本需要已准备的隔离执行端。未配置时不会改用应用进程或普通命令执行。")
                    OutlinedButton(onClick = { pickConfig.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text(if (configuration.optBoolean("configured")) "审阅新的执行配置" else "读取执行端配置") }
                    if (configuration.optBoolean("configured")) TextButton(onClick = { removeConfig = true }, enabled = !busy) { Text("停用并移除执行配置", color = Danger, fontSize = 12.sp) }
                    }
                    if (jsHost != null) TaskJsSelectionControls(taskId, jsHost, runWork)
                    val executions = snapshot.optJSONArray("executions").objects()
                    executions.takeLast(visibleExecutions).reversed().forEach { operation ->
                        val operationId = operation.getString("operationId"); val receipt = operation.optJSONObject("receipt")
                        val native = operation.getJSONObject("arguments").getJSONObject("nativeSkill")
                        if (receipt == null && !native.has("claimedAt") && snapshot.optBoolean("reviewPending")) return@forEach
                        HorizontalDivider(color = Line); Heading("原任务脚本回执 · ${scriptReceiptLabel(receipt?.optString("status") ?: operation.optString("state"))}")
                        if (receipt == null && !native.has("claimedAt")) Caption("没有脚本派发记录。原审阅已结束或需核对登记，核对不会恢复批准或运行脚本。")
                        Caption("进程回执和文件成果不等于任务目标完成。未知结果只查询原操作，不会重跑。")
                        val availability = operation.optJSONObject("artifactAvailability") ?: JSONObject()
                        when (availability.optString("state")) {
                            "not_retrieved" -> Caption("执行端已有终态回执，原文件成果尚未取回。可按这一次原回执读取文件，取回不会运行脚本。")
                            "available" -> Caption("已保留 ${availability.optInt("savedCount")} 份原文件成果，可导出查看。")
                            "partial", "missing" -> {
                                Caption("已保留 ${availability.optInt("savedCount")} 份成果，本次声明的文件未全部生成或超过输出预算。需要补做时返回原任务，新的脚本须重新审阅。")
                                val missing = availability.optJSONArray("missingPaths")
                                if (missing != null && missing.length() > 0) Caption("本次缺少：" + (0 until missing.length()).joinToString("、") { missing.getString(it) })
                                if (availability.optBoolean("truncated")) Caption("文件成果已按本次输出预算截断。")
                            }
                            "not_available" -> Caption("这个终态回执没有可核验的文件成果；已有原任务记录仍保留。")
                        }
                        receipt?.optString("output")?.takeIf { it.isNotEmpty() }?.let { SelectionContainer { Text(it, fontSize = 13.sp, lineHeight = 21.sp) } }
                        if (receipt?.optBoolean("outputTruncated") == true) Caption("输出已按原预算截断")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { work({ host.reconcile(taskId, operationId, false); readSnapshot() }) { snapshot = it; notice = "已核对原操作，没有重新提交" } }, enabled = !busy) { Text("核对原回执") }
                            if (receipt?.optString("status") !in setOf("completed", "failed", "cancelled", "timed_out", "rejected")) TextButton(onClick = { work({ host.reconcile(taskId, operationId, true); readSnapshot() }) { snapshot = it; notice = "已请求停止，请核对原回执" } }, enabled = !busy) { Text("请求停止", color = Danger) }
                        }
                        if (availability.optBoolean("canRetrieve")) OutlinedButton(onClick = {
                            work({ host.retrieveArtifacts(taskId, operationId); readSnapshot() }) { snapshot = it; notice = "已按原回执取回文件成果，没有重新提交脚本" }
                        }, enabled = !busy) { Text("取回原文件成果") }
                        receipt?.optJSONArray("artifacts").objects().forEach { artifact -> TextButton(onClick = { export = SkillArtifactExport(operationId, artifact.getString("artifactId"), artifact.getString("sha256"), artifact.getString("path").substringAfterLast('/')); exportFile.launch(export!!.name) }, enabled = !busy) { Text("导出原成果 · " + artifact.optString("path"), fontSize = 12.sp) } }
                    }
                    if (executions.size > visibleExecutions) TextButton(onClick = { visibleExecutions = minOf(executions.size, visibleExecutions + 6) }, enabled = !busy) { Text("查看更早的原任务回执（还剩 ${executions.size - visibleExecutions} 项）") }
                    if (executions.isNotEmpty()) Caption("查看或导出原成果后，可返回原任务补充要求再继续。取回文件不会恢复旧批准；后续脚本仍需新的具体审阅。")
                }
            }
        }
    }
    configReview?.let { retained ->
        val value = retained.view(); val deployment = value.getJSONObject("deployment"); val request = value.getJSONObject("request")
        AlertDialog(onDismissRequest = { if (!busy) configReview = null }, containerColor = Color.White, title = { Text("审阅固定执行配置") }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已有电脑连接：${deployment.getString("profileId")}\n${deployment.getString("serviceBase")}", fontSize = 14.sp, lineHeight = 22.sp)
                Caption("${request.getString("runtimeId")} · ${request.getString("workspaceId")}\n网络禁止；空环境；单进程。保存后仍需真实执行端探测，本页不会部署解释器。")
                SelectionContainer { Text("读取：${request.getJSONArray("readPaths")}\n写入：${request.getJSONArray("writePaths")}\n成果：${request.getJSONArray("outputPaths")}\n配置 SHA-256：${retained.digest}", fontSize = 12.sp, lineHeight = 20.sp) }
                Row { Checkbox(configAcknowledged, { configAcknowledged = it }, enabled = !busy); Text("我信任所选连接与固定部署配置；保存配置不批准任何任务脚本", fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f)) }
            }
        }, confirmButton = { Button(onClick = { work({ host.installConfiguration(window, retained, retained.digest); readSnapshot() }) { snapshot = it; configReview = null; notice = "配置已保存，实际运行前仍会核对隔离证据" } }, enabled = !busy && configAcknowledged) { Text("保存此配置") } }, dismissButton = { TextButton(onClick = { configReview = null }, enabled = !busy) { Text("取消") } })
    }
    if (removeConfig) AlertDialog(onDismissRequest = { removeConfig = false }, containerColor = Color.White, title = { Text("停用这个执行配置？") }, text = { Text("将使旧审阅失效，并请求停止仍在执行的原脚本。原任务和已有成果保留，已发生的效果不会撤回。") }, confirmButton = { TextButton(onClick = { val revision = snapshot.getJSONObject("configuration").getLong("revision"); work({ host.removeConfiguration(window, revision); readSnapshot() }) { snapshot = it; removeConfig = false; notice = "配置已移除，原操作仍可按原收据核对" } }, enabled = !busy) { Text("停用并移除", color = Danger) } }, dismissButton = { TextButton(onClick = { removeConfig = false }) { Text("取消") } })
}

@Composable private fun Caption(text: String) { Text(text, color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp) }
@Composable private fun Heading(text: String) { Text(text, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
private fun taskSkillReadiness(status: String): String = when (status) { "active" -> "参考包当前有效"; "missingCapabilities" -> "还缺所列工具"; "inactive" -> "尚未启用或已过期，请到技能管理核对"; "invalid" -> "包或来源已变化，不能使用旧批准"; else -> "等待核对" }
private fun scriptReceiptLabel(status: String): String = when (status) { "completed" -> "执行端已结束（退出码0）"; "failed" -> "执行端报告失败"; "cancelled" -> "执行端已停止"; "timed_out" -> "执行端已超时"; "rejected" -> "原请求已拒绝"; "unknown" -> "效果待核对"; "accepted", "running" -> "等待执行端终态"; else -> "原登记待核对" }
