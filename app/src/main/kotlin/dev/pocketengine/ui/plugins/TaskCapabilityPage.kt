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

private data class SkillArtifactExport(val operation: String, val id: String, val sha256: String, val name: String, val privatePublication: Boolean = false)

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
    var removeConfig by remember(taskId) { mutableStateOf<JSONObject?>(null) }
    var script by remember { mutableStateOf<String?>(null) }
    var scriptDigest by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var export by remember { mutableStateOf<SkillArtifactExport?>(null) }
    var visibleExecutions by remember(taskId) { mutableIntStateOf(6) }
    var showPhoneConfiguration by remember(taskId) { mutableStateOf(false) }
    var phoneInterpreter by remember(taskId) { mutableStateOf("python3") }
    var phoneCheckout by remember(taskId) { mutableStateOf("") }
    var phoneReads by remember(taskId) { mutableStateOf("") }
    var phoneWrites by remember(taskId) { mutableStateOf("output/") }
    var phoneOutputs by remember(taskId) { mutableStateOf("output/result.txt") }
    var phoneCwd by remember(taskId) { mutableStateOf(".") }
    var phoneDependencies by remember(taskId) { mutableStateOf(false) }
    var phoneImmediateArtifacts by remember(taskId) { mutableStateOf(false) }
    var observedArtifactRevision by remember(taskId) { mutableLongStateOf(-1) }
    var logOperation by remember(taskId) { mutableStateOf<String?>(null) }
    var logPage by remember(taskId) { mutableStateOf<JSONObject?>(null) }
    var logCursor by remember(taskId) { mutableLongStateOf(0) }
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
        work({ val before = host.privateArtifactRevision(taskId); val actual = readSnapshot(); check(actual.optString("taskId") == taskId) { "能力来源不是此任务" }; val pending = host.pendingReview(taskId); Pair(before, Triple(actual, pending, pending?.let(host::validateReview))) }) {
            (before, state) -> val (actual, pending, digest) = state; snapshot = actual; review = pending; currentDigest = digest; observedArtifactRevision = before
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
            val bytes = if (selected.privatePublication) host.readPublishedArtifact(taskId, selected.operation, selected.id, selected.sha256) else readArtifact(selected.operation, selected.id, selected.sha256)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: throw IllegalStateException("无法打开导出位置")
            bytes.size
        }) { notice = "已导出原任务成果（$it 字节），未自动写入其他项目" }
    }
    LaunchedEffect(taskId) { refresh() }
    LaunchedEffect(taskId) { while (true) { delay(1000); now = System.currentTimeMillis(); if (!busy && runCatching { host.pendingReview(taskId) !== review || host.privateArtifactRevision(taskId) != observedArtifactRevision }.getOrDefault(false)) refresh() } }
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
        else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
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
                    val phone = snapshot.optJSONObject("phoneExecution") ?: JSONObject()
                    Caption(when { configuration.optBoolean("recoveryRequired") -> if(configuration.optBoolean("recoveryAvailable")) "原执行配置损坏，脚本执行未启用；原字节仍保留。可从下方重新准备手机或读取电脑配置，审阅确认后先留存旧文件再替换；不会恢复旧批准或重跑原任务。" else "原执行配置不能安全读取或留存，脚本未启用；请核对原配置文件。原任务回执保留，不会删除文件、切换电脑或恢复旧批准。"; !configuration.optBoolean("configured") -> "可在这台手机独立执行 Python / Shell Skill，也可使用电脑隔离执行端。先选择地点，每次具体脚本仍须你审阅。"; configuration.optString("executionLocation") == "this_phone" -> "当前地点：这台手机。已保存固定解释器与输入输出选择；不提供同 UID 文件/网络 OS 沙箱。"; else -> "当前地点：电脑 Companion · ${configuration.optJSONObject("deployment")?.optString("profileId")}。每次运行仍核对原隔离证据。" })
                    OutlinedButton(onClick = { showPhoneConfiguration = true }, enabled = !busy) { Text("准备手机 Python / Shell") }
                    if (!phone.optBoolean("available")) Caption("手机工具尚未可执行。请到项目页的工具准备中核对原环境；这里不会自动下载或改用电脑。")
                    TextButton(onClick = { pickConfig.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("读取电脑隔离端配置") }
                    if (configuration.optBoolean("configured")) TextButton(onClick = { removeConfig = JSONObject(configuration.toString()) }, enabled = !busy) { Text("停用并移除执行配置", color = Danger, fontSize = 12.sp) }
                    }
                    if (snapshot.has("capabilityPreparationMessage")) Caption(snapshot.getString("capabilityPreparationMessage"))
                    OwnerSkillInvocationControls(snapshot, busy || review != null) { input, generation ->
                        val taskPin = snapshot.getJSONObject("ownerInvocation").getString("taskPin")
                        work({
                            val app = context.applicationContext as? dev.pocketengine.EngineApplication ?: throw IllegalStateException("原任务宿主暂不可用")
                            val selected = host.selectForOwner(window, taskId, taskPin, input, generation, app.taskSkillCapabilitiesForOwner(taskId))
                            try { app.startOwnerSkillOnTask(selected) } catch (failed: Exception) { selected.close(); throw failed }
                            readSnapshot()
                        }) { snapshot = it; notice = "本次主人选择已进入原任务，请在出现的执行计划中核对并批准；没有自动运行。" }
                    }
                    if (jsHost != null) TaskJsSelectionControls(taskId, jsHost, runWork)
                    val executions = snapshot.optJSONArray("executions").objects()
                    executions.takeLast(visibleExecutions).reversed().forEach { operation ->
                        val operationId = operation.getString("operationId"); val receipt = operation.optJSONObject("receipt")
                        val native = operation.getJSONObject("arguments").getJSONObject("nativeSkill")
                        if (receipt == null && !native.has("claimedAt") && snapshot.optBoolean("reviewPending")) return@forEach
                        HorizontalDivider(color = Line); Heading("原任务脚本回执 · ${scriptReceiptLabel(receipt?.optString("status") ?: operation.optString("state"))}")
                        if (receipt == null && !native.has("claimedAt")) Caption("没有脚本派发记录。原审阅已结束或需核对登记，核对不会恢复批准或运行脚本。")
                        val onPhone = native.optJSONObject("deployment")?.optString("backendId") == "phone-managed-skill-v1"
                        Caption((if (onPhone) "地点：这台手机。" else "地点：电脑 Companion。") + "进程回执和文件成果不等于任务目标完成。未知结果只查询原操作，不会重跑。")
                        val publications = native.optJSONObject("scriptSharedArtifacts")
                        publications?.keys()?.asSequence()?.toList()?.sorted()?.forEach { role ->
                            val published = publications.getJSONObject(role)
                            if (published.optString("state") == "published") {
                                Caption("运行中已取得私有成果发布回执 · ${published.optString("path")} · ${published.optLong("bytes")} 字节。文件导出时重核相同 SHA，不代表脚本或任务已完成。")
                                TextButton(onClick = { export = SkillArtifactExport(operationId, published.getString("attachmentId"), published.getString("sha256"), published.getString("path").substringAfterLast('/'), true); exportFile.launch(export!!.name) }, enabled = !busy) { Text("导出这份私有成果", fontSize = 12.sp) }
                            } else Caption("私有成果保存结果待核 · ${published.optString("path")}。不会重新导入、重跑脚本或把未知结果当作已分享。")
                        }
                        if (onPhone && native.has("nativeManagedJob")) TextButton(onClick = { work({ host.readExecutionLog(taskId, operationId, 0, 16000) }) { logOperation = operationId; logPage = it; logCursor = it.optLong("nextCursor", it.optLong("nextSequence", 0)) } }, enabled = !busy) { Text("读取原进程日志") }
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
        AlertDialog(onDismissRequest = { if (!busy) configReview = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("审阅固定执行配置") }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val phone = value.optString("executionLocation") == "this_phone"
                val recovery = value.optJSONObject("configurationRecovery")
                val recovering = recovery?.optBoolean("required") == true
                if (phone && value.optJSONObject("nativeExecutionContract")?.optBoolean("immediatePrivateArtifacts") == true) Caption("已选择：本次脚本运行中可将准确输出路径保存为本应用内的私人任务附件；沿原总成果预算，不公开上传，不自动分享其他应用。每次具体脚本仍须再次审阅。")
                if (recovering) Caption("此次将恢复不可用的执行配置：确认后先在本机留存准确旧字节（${recovery?.optLong("originalBytes")} B，SHA-256 ${recovery?.optString("originalSha256")}），再保存下面的新配置。不会恢复旧脚本批准、重建原进程或改变未知效果；之后运行仍需新的具体审阅。")
                Text(if (phone) "这台手机 · 原托管运行环境" else "已有电脑连接：${deployment.getString("profileId")}\n${deployment.getString("serviceBase")}", fontSize = 14.sp, lineHeight = 22.sp)
                Caption("${request.getString("runtimeId")} · ${request.getString("workspaceId")}\n" + if (phone) "原生入口只复制明确输入到单次副本，不自动写回原项目。同 UID 进程仍可能读写应用数据、联网或创建脱离后代；这不是 OS 沙箱。标准输入关闭，具体版本、依赖和预算在每次执行前审阅。" else "网络禁止；空环境；单进程。保存后仍需真实执行端探测，本页不会部署解释器。")
                SelectionContainer { Text("读取：${request.getJSONArray("readPaths")}\n写入：${request.getJSONArray("writePaths")}\n成果：${request.getJSONArray("outputPaths")}\n配置 SHA-256：${retained.digest}", fontSize = 12.sp, lineHeight = 20.sp) }
                Row { Checkbox(configAcknowledged, { configAcknowledged = it }, enabled = !busy); Text((if (recovering) "我确认先留存原配置，再保存新配置；" else "") + if (phone) "我了解同 UID / 网络可达风险；只选择手机执行地点，仍需逐次审阅具体 Skill" else "我信任所选连接与固定部署配置；保存配置不批准任何任务脚本", fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f)) }
            }
        }, confirmButton = { Button(onClick = { work({ host.installConfiguration(window, retained, retained.digest); readSnapshot() }) { snapshot = it; configReview = null; notice = "执行地点与范围已保存；实际脚本仍需本次具体审阅" } }, enabled = !busy && configAcknowledged) { Text("保存此配置") } }, dismissButton = { TextButton(onClick = { configReview = null }, enabled = !busy) { Text("取消") } })
    }
    if (showPhoneConfiguration) AlertDialog(onDismissRequest = { if (!busy) showPhoneConfiguration = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("选择手机执行范围") }, text = {
        val phone = snapshot.optJSONObject("phoneExecution") ?: JSONObject()
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Caption("只保存本机地点与准确的输入/输出选择，不运行脚本。手机同 UID 不是 OS 沙箱，只执行你信任的技能。")
            Row { FilterChip(phoneInterpreter == "python3", { phoneInterpreter = "python3" }, label = { Text("Python") }); Spacer(Modifier.width(8.dp)); FilterChip(phoneInterpreter == "sh", { phoneInterpreter = "sh"; phoneDependencies = false }, label = { Text("Shell") }) }
            TextButton(onClick = { phoneCheckout = ""; phoneReads = "" }, enabled = !busy) { Text(if (phoneCheckout.isEmpty()) "✓ 空临时工作区" else "使用空临时工作区") }
            phone.optJSONArray("checkouts").objects().forEach { checkout -> TextButton(onClick = { phoneCheckout = checkout.getString("checkoutId") }, enabled = !busy) { Text((if (phoneCheckout == checkout.optString("checkoutId")) "✓ " else "") + checkout.optString("name")) } }
            if (phoneCheckout.isNotEmpty()) OutlinedTextField(phoneReads, { phoneReads = it }, label = { Text("复制输入：每行一个准确文件或目录/") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !busy)
            OutlinedTextField(phoneCwd, { phoneCwd = it }, label = { Text("副本中的工作目录（默认 .）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(phoneWrites, { phoneWrites = it }, label = { Text("声明写入范围：每行一项") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !busy)
            OutlinedTextField(phoneOutputs, { phoneOutputs = it }, label = { Text("取回成果：每行一个准确文件") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !busy)
            if (phoneInterpreter == "python3") Row { Checkbox(phoneDependencies, { phoneDependencies = it }, enabled = !busy); Text("允许准备技能原包 requirements.lock 中精确版本+SHA-256的 PyPI wheel；不跑源码安装脚本。", fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f)) }
            Row { Checkbox(phoneImmediateArtifacts, { phoneImmediateArtifacts = it }, enabled = !busy); Text("允许脚本运行中用 pocketworld share 将上方准确输出文件即刻保存到本应用私人任务；计入原成果预算，不公开上传或自动外发。默认关闭。", fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f)) }
            Caption("最多复制 200 文件/2MiB。执行上限 120 秒，具体请求可更短；保留输出/成果最多64KiB。手机环境尚未准备时不会开始执行或自动换地点。")
            if (!phone.optBoolean("available")) Text("请先在项目页准备原手机工具环境，再回此任务重新核对。", color = Danger, fontSize = 13.sp)
        }
    }, confirmButton = { Button(onClick = {
        fun paths(value: String) = org.json.JSONArray(value.lines().map { it.trim() }.filter { it.isNotEmpty() })
        val selection = JSONObject().put("format", "pocketworld.skill-phone-configuration.v1").put("runtimeId", phoneInterpreter).put("checkoutId", phoneCheckout).put("workingDirectory", phoneCwd.trim()).put("readPaths", paths(phoneReads)).put("writePaths", paths(phoneWrites)).put("outputPaths", paths(phoneOutputs)).put("timeoutSeconds",120).put("maxOutputBytes",65536).put("maxResultChars",12000).put("preparePinnedDependencies",phoneDependencies).put("immediatePrivateArtifacts",phoneImmediateArtifacts)
        work({ host.inspectPhoneConfiguration(taskId, selection) }) { configReview = it; showPhoneConfiguration = false }
    }, enabled = !busy && snapshot.optJSONObject("phoneExecution")?.optBoolean("available") == true) { Text("核对这份选择") } }, dismissButton = { TextButton(onClick = { showPhoneConfiguration = false }, enabled = !busy) { Text("取消") } })
    logOperation?.let { operation -> AlertDialog(onDismissRequest = { if (!busy) { logOperation = null; logPage = null } }, title = { Text("手机脚本日志") }, text = {
        val page = logPage
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Caption("原进程字节日志，有缓存上限；结束状态以原回执为准。读取不会重跑脚本。")
            SelectionContainer { Text(page?.let(::skillLogText) ?: "尚无日志", fontSize = 13.sp, lineHeight = 21.sp) }
            if (page?.optBoolean("logIncompleteTail") == true) Caption("原日志末条未写完整，仅显示此前完整记录；原字节与未知状态保留，未重新运行脚本。")
            else if (page?.optBoolean("truncated") == true || page?.optBoolean("outputTruncated") == true) Caption("原日志已达缓存或本页范围上限；未将缺失字节当完整输出。")
        }
    }, confirmButton = { TextButton(onClick = { work({ host.readExecutionLog(taskId, operation, logCursor,16000) }) { logPage = it; logCursor = it.optLong("nextCursor",it.optLong("nextSequence",logCursor)) } }, enabled = !busy) { Text("读取下一页") } }, dismissButton = { TextButton(onClick = { logOperation = null; logPage = null }, enabled = !busy) { Text("关闭") } }) }
    removeConfig?.let { observed -> AlertDialog(onDismissRequest = { removeConfig = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("停用这个执行配置？") }, text = { Text("将使旧审阅失效，并请求停止仍在执行的原脚本。原任务和已有成果保留，已发生的效果不会撤回。\n所选配置 SHA-256：${observed.optString("digest")}") }, confirmButton = { TextButton(onClick = { work({ host.removeConfiguration(window, observed.getLong("revision"), observed.getString("stateFingerprint")); readSnapshot() }) { snapshot = it; removeConfig = null; notice = "配置已移除，原操作仍可按原收据核对" } }, enabled = !busy) { Text("停用并移除", color = Danger) } }, dismissButton = { TextButton(onClick = { removeConfig = null }) { Text("取消") } }) }
}

@Composable private fun Caption(text: String) { Text(text, color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp) }
@Composable private fun Heading(text: String) { Text(text, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
private fun taskSkillReadiness(status: String): String = when (status) { "active" -> "参考包当前有效"; "missingCapabilities" -> "还缺所列工具"; "inactive" -> "尚未启用或已过期，请到技能管理核对"; "invalid" -> "包或来源已变化，不能使用旧批准"; else -> "等待核对" }
private fun scriptReceiptLabel(status: String): String = when (status) { "completed" -> "执行端已结束（退出码0）"; "failed" -> "执行端报告失败"; "cancelled" -> "执行端已停止"; "timed_out" -> "执行端已超时"; "rejected" -> "原请求已拒绝"; "unknown" -> "效果待核对"; "accepted", "running" -> "等待执行端终态"; else -> "原登记待核对" }

/** Presentation only: ordered original pipe chunks, decoded as lossy UTF-8.
 * It neither alters the original binary log nor treats stderr text as status. */
private fun skillLogText(page: JSONObject): String {
    val chunks = page.optJSONArray("chunks") ?: return page.optString("message", "没有可读取的原日志页")
    val bytes = java.io.ByteArrayOutputStream()
    for (i in 0 until chunks.length()) {
        val chunk = chunks.getJSONObject(i)
        val raw = java.util.Base64.getDecoder().decode(chunk.getString("dataBase64"))
        require(raw.size == chunk.getInt("bytes") && bytes.size() + raw.size <= 262144) { "原日志页字节范围无法核对" }
        bytes.write(raw)
    }
    return if (bytes.size() == 0) "本页尚无新增输出" else bytes.toString("UTF-8")
}
