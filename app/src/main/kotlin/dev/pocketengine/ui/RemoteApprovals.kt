package dev.pocketengine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.json.JSONArray
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

internal data class RemoteApprovalTarget(val profileId: String, val jobId: String, val approvals: List<JSONObject>)

internal fun remoteApprovalTarget(task: UiTask): RemoteApprovalTarget? {
    if (task.raw.optString("feature") != "remote") return null
    val evidence = task.raw.optJSONObject("evidence") ?: JSONObject()
    val profile = task.raw.optJSONObject("externalRefs")?.optString("profileId").orEmpty().ifBlank { evidence.optString("profileId") }
    val job = task.raw.optString("externalId").ifBlank { evidence.optString("id") }
    if (profile.isBlank() || job.isBlank()) return null
    val approvals = (task.raw.optJSONArray("pendingApprovals") ?: evidence.optJSONArray("pendingApprovals")).objects()
    return RemoteApprovalTarget(profile, job, approvals)
}

@Composable
internal fun RemoteTaskApprovals(task: UiTask, controller: PocketWorldController) {
    if (task.raw.optString("feature") != "remote" || task.raw.optString("actorId") != "owner" || task.raw.optString("spaceId") != "private") return
    var open by remember(task.id) { mutableStateOf(false) }
    var inputsOpen by remember(task.id) { mutableStateOf(false) }
    var continuationInputsOpen by remember(task.id) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    DisposableEffect(lifecycle) { val observer=LifecycleEventObserver { _,_->foreground=lifecycle.currentState==Lifecycle.State.RESUMED }; lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) } }
    LaunchedEffect(controller.remoteAttentionNotice, foreground) { if(foreground&&controller.remoteAttentionNotice?.optString("taskId")==task.id){inputsOpen=false;continuationInputsOpen=false;open=true;controller.consumeRemoteAttention(task.id)} }
    OutlinedButton(onClick = { inputsOpen = false; continuationInputsOpen=false;open = true }, modifier = Modifier.padding(top = 16.dp)) { Text("核对原电脑任务与会话") }
    if (open) RemoteOwnerDialog(task.id, controller, { open=false;inputsOpen=false;continuationInputsOpen=true }) { open = false }
    if(continuationInputsOpen)RemoteContinuationInputDialog(task.id,controller){continuationInputsOpen=false;open=true}
    if (task.raw.has("remoteInputScope")) TextButton(onClick = { open = false; continuationInputsOpen=false;inputsOpen = true }) { Text("原私人附件暂存与传输回执") }
    if (inputsOpen) RemoteInputDialog(null, task.id, controller) { inputsOpen = false }
}

/** Existing team/connection summaries route into the real native task; they never POST approvals. */
@Composable
internal fun RemoteApprovalBlock(profileId: String, jobId: String, approvals: List<JSONObject>, controller: PocketWorldController) {
    if (approvals.isEmpty()) return
    val task = controller.state.tasks.find { it.raw.optString("feature") == "remote" && it.raw.optString("externalId") == jobId && it.raw.optJSONObject("externalRefs")?.optString("profileId") == profileId }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(Rounded).background(Soft).padding(16.dp)) {
        Text("电脑 Agent 请求本次授权", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("需从匹配的本机原任务核对当前会话、具体输入和单次决定；此摘要不授予权限。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        if (task != null) TextButton(onClick = { controller.focusTask(task.id) }) { Text("打开原任务审阅") }
        else Text("尚未找到匹配的本机原任务，不能在此审批。", color = SecondaryInk, fontSize = 12.sp)
    }
}

internal fun remoteOwnerReviewMatches(snapshot: JSONObject, taskId: String): Boolean = snapshot.optString("taskId") == taskId && snapshot.optString("windowId").isNotBlank() && snapshot.optString("reviewId").isNotBlank() && snapshot.opt("fresh") == true
internal fun remoteApprovalClaim(task: JSONObject?, requestId: String): JSONObject? = task?.optJSONArray("operations").objects().find { it.optString("tool") == "remote_approval" && it.optString("runId") == task?.optString("runId") && it.optJSONObject("arguments")?.optString("requestId") == requestId }

/** Read policy for this page only; never a task admission, write retry, or remote liveness claim. */
internal fun remoteReadShouldPoll(snapshot: JSONObject): Boolean {
    if (snapshot.optString("queryStatus") in setOf("unconfirmed", "changed", "paused")) return false
    if (snapshot.optString("eventReadStatus") == "not_available") return false // Keep the original cursor/cache; manual reconnect may query again.
    if (snapshot.optString("eventReadStatus") == "confirmed" && snapshot.optJSONObject("eventRead")?.optBoolean("hasMore") == true) return true // Original fixed-head read only, including terminal jobs.
    val task = snapshot.optJSONObject("task")
    if (task?.optBoolean("cancelRequested") == true || task?.optString("status") in setOf("cancelled", "completed", "stopping")) return false
    return snapshot.optJSONObject("source")?.optString("executorStatus") in setOf("queued", "running", "stopping", "awaiting_approval", "awaiting_input", "unknown")
}

@Composable
private fun RemoteOwnerDialog(taskId: String, controller: PocketWorldController, openContinuationInputs: () -> Unit, close: () -> Unit) {
    DisposableEffect(taskId) { val window = controller.enterRemoteTask(taskId); onDispose { controller.leaveRemoteTask(window) } }
    val state = controller.state
    val snapshot = state.ownerSnapshot("remoteTask")
    val source = snapshot.optJSONObject("source") ?: JSONObject()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,_->foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val fresh = foreground && remoteOwnerReviewMatches(snapshot, taskId)
    val busy = state.ownerPending.any { it.startsWith("remoteTask.") }
    val readBusy = "remoteTask.open" in state.ownerPending || "remoteTask.refresh" in state.ownerPending
    // Only an explicit button press changes this local nonce. Snapshots/timestamps do not.
    var reconnectNonce by remember(taskId) { mutableIntStateOf(0) }
    var prompt by remember(taskId) { mutableStateOf("") }
    val coldRecovery = dev.pocketengine.features.remote.RemoteColdRecovery.source(source)
    var useTaskGateway by remember(taskId) { mutableStateOf(false) }
    var recoveryAccepted by remember(taskId,source.optString("jobOperationId"),source.optJSONObject("recoveryProof")?.optString("proofId")) { mutableStateOf(false) }
    var recoveryTimeout by remember(taskId) { mutableStateOf("300") }
    var localError by remember(taskId) { mutableStateOf<String?>(null) }
    var submitted by remember(taskId) { mutableStateOf<Pair<String, String>?>(null) }
    var selectedSnapshot by remember(taskId, source.optString("jobOperationId")) { mutableStateOf<JSONObject?>(null) }
    val continuation = state.ownerResponses("remoteTask")["continue"]
    LaunchedEffect(continuation) {
        val pending = submitted
        if (pending != null && continuation?.optString("requestId") == pending.first) {
            val operation = continuation.optJSONObject("result")?.optJSONObject("operation")
            if (continuation.optBoolean("ok") && operation?.optString("state") in setOf("remote_job_active", "remote_job_receipt") && prompt == pending.second) prompt = ""
            submitted = null
        }
    }
    fun send(command: String, payload: JSONObject = JSONObject()) {
        try { val request = controller.requestRemoteTask(command, payload.put("reviewId", snapshot.optString("reviewId"))); if (command == "continue") submitted = request to prompt; localError = null }
        catch (failure: Exception) { localError = failure.message }
    }
    val latestBusy by rememberUpdatedState(busy)
    val latestSnapshot by rememberUpdatedState(snapshot)
    LaunchedEffect(taskId,foreground,reconnectNonce) {
        if(!foreground)return@LaunchedEffect
        delay(250)
        if(!latestBusy)send("refresh") // Only original GET; never create/answer/resume.
        while(true) {
            delay(if(latestSnapshot.optString("eventReadStatus")=="confirmed"&&latestSnapshot.optJSONObject("eventRead")?.optBoolean("hasMore")==true) 500 else 4000)
            if(latestBusy)continue
            if(!remoteReadShouldPoll(latestSnapshot))break // A manual reconnect may restart; never an effect self-trigger.
            send("refresh")
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("原电脑会话", fontSize = 21.sp, fontWeight = FontWeight.SemiBold); TextButton(onClick = close) { Text("返回") } }
                Text(if (snapshot.optBoolean("teamRemoteWriter")) "团队所选文件副本 · 这里只审原job单次请求。不是全项目/Git或OS隔离；完整命令build/test仍未支持。回原团队继续制作与独审，离开/切后台使本次审阅失效。" else "只查询原执行；明确续接会在同一个本机任务建立新的执行代次。离开页面使本次审阅失效，不会撤回已派发的操作。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp)
                if (source.length() > 0) {
                    SelectionContainer { Text("""电脑：${source.optString("profileId")}
地址：${source.optString("serviceBase")}
环境：${source.optString("environmentId")}
执行器：${source.optString("worker")}
请求模型：${source.optString("model").ifBlank { "未指定" }}
执行端报告：${source.optString("reportedModel").ifBlank { "未报告" }}
会话：${source.optString("sessionId", source.optString("threadId"))}
原任务：${source.optString("jobId")}
操作：${source.optString("jobOperationId")}""", fontSize = 12.sp, lineHeight = 21.sp) }
                    Text("执行端报告和协议回执不等于工具或业务成功；Claude工具限制不是操作系统只读沙箱。运行中Codex补充仍使用原任务的独立输入入口。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp)
                }
                HarnessConfigurationPanel(taskId, snapshot, controller, busy, foreground)
                RemoteReadStatusPanel(snapshot, readBusy, foreground && !busy) { reconnectNonce++ }
                if(source.optString("executorStatus")=="interrupted") {
                    Text(if(coldRecovery) "已核对原进程组停止、原会话最后一轮终止；原job仍是中断。未补造旧输出、成果或费用，业务是否成功仍须核对。" else "原执行因服务故障中断，不能由失联状态推断已停止。先只读核对原进程与准确会话终态，不会重发旧任务、答案或批准。",fontSize=14.sp,lineHeight=21.sp)
                    if(source.optJSONObject("coldRecovery")?.optJSONObject("lastInspection")?.optString("status")=="unconfirmed") Text("最近一次只读核对未能确认当前恢复条件，旧核对仅保留作历史；不会按旧记录开启下一轮。",fontSize=14.sp,color=MaterialTheme.colorScheme.error)
                    if(fresh&&source.optJSONObject("controls")?.optBoolean("readSessionRecovery")==true) OutlinedButton(onClick={send("inspectRecovery")},enabled=!busy) { Text("只读核对原会话的恢复条件") }
                    else if(!coldRecovery) Text("此旧任务或执行器缺少可核对证据时，仍只读保留；需在原电脑确认并停止原执行，不会自动另建任务。",fontSize=13.sp,color=SecondaryInk)
                }
                RemoteAttentionPanel(snapshot, fresh && !busy, controller, ::send)
                RemoteProgressPanel(snapshot)
                RemoteQuestionPanel(snapshot,busy) { id,hash,answers -> send("answerQuestion",JSONObject().put("requestId",id).put("requestHash",hash).put("answers",answers)) }
                RemoteResultPanel(snapshot, fresh && !busy) { path -> send("readArtifact", JSONObject().put("path", path)) }
                RemoteTaskFilesPanel(snapshot, fresh && !busy, ::send)
                RemoteProjectHistoryPanel(snapshot, fresh && !busy, controller, ::send)
                RemoteSnapshotPanel(snapshot, fresh && !busy, selectedSnapshot?.optString("snapshotId"),
                    capture = { paths -> send("captureSnapshot", JSONObject().put("paths", paths)) },
                    read = { id, path -> send("readSnapshot", JSONObject().put("snapshotId", id).put("path", path)) },
                    select = { selectedSnapshot = it },
                    save = { id, path -> send("saveSnapshot", JSONObject().put("snapshotId", id).put("path", path)) },
                    openAttachment = controller::openAttachmentOutcome)
                RemoteConversationResultPanel(snapshot, fresh && !busy, controller, ::send)
                RemoteDraftPanel(snapshot, fresh && !busy, ::send)
                RemotePreviewPanel(snapshot, controller)

                if (fresh && snapshot.optBoolean("canContinue")) {
                    Text("继续这个会话", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick=openContinuationInputs,enabled=!busy){Text("选择新文件，在这个会话继续")}
                    OutlinedTextField(prompt, { prompt = it }, label = { Text("本次明确的新要求") }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
                    if(coldRecovery) {
                        OutlinedTextField(recoveryTimeout,{recoveryTimeout=it},label={Text("本次新执行时限（10–900秒）")},supportingText={Text("沿用原电脑、工作区和读写边界；超时会按原监督流程请求停止并核对。每流输出仍有界，内部模型次数、tokens和费用不可硬限；旧未知费用不清零。")},modifier=Modifier.fillMaxWidth())
                        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Checkbox(checked=recoveryAccepted,onCheckedChange={recoveryAccepted=it},enabled=!busy);Text("我明确授权这次新执行及所示时限，理解内部计费未知；不恢复任何旧批准。",fontSize=14.sp,lineHeight=21.sp,modifier=Modifier.weight(1f)) }
                    }
                    if (!coldRecovery && !snapshot.optBoolean("teamRemoteWriter")) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = useTaskGateway, onCheckedChange = { useTaskGateway = it }, enabled = !busy)
                        Text("这轮使用手机原任务模型网关：先准备，配置后再明确确认执行。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = {
                        val input = JSONObject().put("prompt", prompt)
                        if(coldRecovery)input.put("timeoutSeconds",recoveryTimeout.toIntOrNull()).put("recoveryOwnerAcknowledged",recoveryAccepted)
                        selectedSnapshot?.let { input.put("snapshotId", it.getString("snapshotId")).put("snapshotVersion", it.getString("snapshotVersion")) }
                        send(if (useTaskGateway && !coldRecovery) "gateway.prepareContinuation" else "continue", input)
                    }, enabled = !busy && submitted == null && prompt.isNotBlank() && prompt.length <= 12000 && (!coldRecovery || recoveryAccepted && (recoveryTimeout.toIntOrNull() ?: 0) in 10..900)) { Text(if (useTaskGateway && !coldRecovery) "仅准备网关新轮，暂不执行" else if (selectedSnapshot == null) "确认续接原会话" else "按所选快照同版本续接") }
                    if (selectedSnapshot != null) Text("派发前和原电脑启动前均核对所选文件版本；漂移时拒绝旧续接，不把快照自动改绑新文件。此操作仍可能修改原工作区。", fontSize = 11.sp, lineHeight = 19.sp, color = SecondaryInk)
                } else snapshot.optString("reason").takeIf { it.isNotBlank() }?.let { Text(it, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp) }
                source.optJSONArray("pendingApprovals").objects().forEach { approval ->
                    val id = approval.optString("requestId")
                    val claimed = remoteApprovalClaim(snapshot.optJSONObject("task"), id)
                    HorizontalDivider(color = Line)
                    Text("仅这一次工具请求 · " + id, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    RemoteApprovalSummary(approval)
                    Text("上述内容来自电脑，属于待审阅的不可信输入；不是本机指令或永久权限。sent/resolved仅是协议回执。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
                    if (claimed != null) Text("决定已登记，只能查询原回执，不会再发送。", color = SecondaryInk, fontSize = 12.sp)
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { send("approve", JSONObject().put("requestId", id).put("decision", "decline")) }, enabled = fresh && !busy && id.isNotBlank()) { Text("拒绝本次") }
                        Button(onClick = { send("approve", JSONObject().put("requestId", id).put("decision", "accept")) }, enabled = fresh && !busy && id.isNotBlank() && approval.opt("canAccept") == true) { Text("允许本次") }
                        if (!snapshot.optBoolean("teamRemoteWriter")) TextButton(onClick = { send("approve", JSONObject().put("requestId", id).put("decision", "cancel")) }, enabled = fresh && !busy && id.isNotBlank()) { Text("取消") }
                    }
                }
                snapshot.optJSONObject("task")?.optJSONArray("operations").objects().filter { it.optString("tool") == "remote_approval" }.takeLast(8).forEach { operation ->
                    val args = operation.optJSONObject("arguments") ?: JSONObject()
                    Text("本机单次决定：${args.optString("requestId")} · ${args.optString("decision")} · ${if (operation.optString("state") == "remote_approval_receipt") "已收到协议回执，非工具成功" else "决定已登记，仅查询原回执"}", color = SecondaryInk, fontSize = 11.sp, lineHeight = 20.sp)
                }
                state.ownerResponses("remoteTask").values.filter { it.optString("taskId") == taskId }.maxByOrNull { it.optLong("sequence") }?.takeIf { !it.optBoolean("ok") }?.let { Text(it.optString("error", "操作未完成"), color = Danger, fontSize = 12.sp, lineHeight = 21.sp) }
                (localError ?: state.ownerErrors["remoteTask"])?.let { Text(it, color = Danger, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun RemoteReadStatusPanel(snapshot: JSONObject, reading: Boolean, enabled: Boolean, reconnect: () -> Unit) {
    val query = snapshot.optString("queryStatus")
    Text(when {
        reading -> "正在查询原任务"
        query == "unconfirmed" -> "暂时无法读取原电脑"
        query == "changed" -> "原任务信息已变化，请重新核对"
        query == "paused" -> "本页读取已暂停"
        snapshot.optBoolean("fresh") -> "已取得原任务回执"
        else -> "当前显示上次读取内容"
    }, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val last = snapshot.optLong("lastSuccessfulReadAt")
    if (last > 0) Text("上次成功读取（手机时间）：" + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(last)), fontSize = 13.sp, color = SecondaryInk)
    Text(if (query == "unconfirmed") "失联不代表远端已停止。下方如有缓存，只能作为上次记录；重新连接后才能核对当前进度、审批和问题。" else "由原任务查询更新，不是实时直播。重新连接只读取同一执行，不新建任务、重发答案或批准操作。", fontSize = 14.sp, lineHeight = 22.sp, color = SecondaryInk)
    OutlinedButton(onClick = reconnect, enabled = enabled) { Text(if (query == "unconfirmed") "只读重新连接原任务" else "重新查询原任务（不重发）") }
}

@Composable
private fun RemoteSnapshotPanel(
    snapshot: JSONObject, canAct: Boolean, selectedId: String?,
    capture: (JSONArray) -> Unit, read: (String, String) -> Unit, select: (JSONObject?) -> Unit,
    save: (String, String) -> Unit, openAttachment: (JSONObject) -> Unit
) {
    val source = snapshot.optJSONObject("source") ?: JSONObject()
    val snapshots = snapshot.optJSONObject("task")?.optJSONArray("remoteResultSnapshots").objects()
    var paths by remember(snapshot.optString("taskId")) { mutableStateOf("") }
    var manualPaths by remember(snapshot.optString("taskId")) { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    HorizontalDivider(color = Line)
    Text("已冻结版本与继续", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    Text("冻结只采集你明确选择的文件，不递归读取其他内容。快照证明采集时的真实字节与来源，不证明这些代码全部由该job创作。凭据/私密运行目录会拒绝。单文件256KiB、本次512KiB，原job总计1MiB/4份。", fontSize = 13.sp, lineHeight = 19.sp, color = SecondaryInk)
    snapshot.optJSONObject("task")?.optJSONArray("operations").objects().filter { it.optString("tool") == "remote_snapshot" }.takeLast(4).forEach { operation ->
        val receipt = operation.optJSONObject("receipt")
        val message = when {
            operation.optString("state") == "unknown" -> "回执待核对；只查询原编号，不会重新采集"
            receipt?.optString("status") == "rejected" -> receipt.optString("message", "原采集已拒绝，未保存新快照")
            else -> "已保存原文件版本；不证明目标已完成"
        }
        Text("快照操作 ${operation.optString("operationId")} · $message", fontSize = 13.sp, lineHeight = 19.sp, color = SecondaryInk)
    }
    if (!snapshot.optBoolean("teamRemoteWriter")) TextButton(onClick = { manualPaths = !manualPaths }) { Text(if (manualPaths) "收起手动路径" else "按路径手动指定（高级）") }
    if (manualPaths && !snapshot.optBoolean("teamRemoteWriter") && source.optString("executorStatus") in setOf("completed", "failed", "cancelled")) {
        OutlinedTextField(paths, { paths = it }, label = { Text("明确文件路径，每行一个（1–8个）") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            try {
                val values = paths.lines().map(String::trim).filter(String::isNotEmpty)
                dev.pocketengine.features.remote.RemoteResultSnapshots.paths(JSONArray(values))
                confirmation = snapshot.optString("reviewId") to values; error = null
            } catch (failure: Exception) { error = failure.message }
        }, enabled = canAct && snapshot.optBoolean("canCaptureSnapshot") && paths.isNotBlank()) { Text("核对并保存指定文件快照") }
        if (canAct && !snapshot.optBoolean("canCaptureSnapshot")) Text("原操作仍有回执待核对；先查询原编号，不会另建采集或自动重试。", fontSize = 13.sp, color = SecondaryInk)
    } else if (manualPaths) Text("原job结算后才能采集；运行/未知效果时只查询原编号。", fontSize = 13.sp, color = SecondaryInk)
    snapshots.forEach { saved ->
        Text("${saved.optString("snapshotId")} · ${saved.optLong("bytes")}字节 · 原job ${saved.optString("jobId")}", fontSize = 13.sp)
        SelectionContainer { Text("版本 " + saved.optString("snapshotVersion"), fontSize = 13.sp) }
        saved.optJSONArray("files").objects().forEach { file ->
            TextButton(onClick = { read(saved.optString("snapshotId"), file.optString("path")) }, enabled = canAct) { Text("查看冻结文件 · " + file.optString("path"), fontSize = 14.sp) }
            val local = snapshot.optJSONArray("snapshotAttachments").objects().find { it.optString("snapshotId") == saved.optString("snapshotId") && it.optString("path") == file.optString("path") }?.optJSONObject("attachment")
            if (!snapshot.optBoolean("teamRemoteWriter")) {
                if (local == null) TextButton(onClick = { save(saved.optString("snapshotId"), file.optString("path")) }, enabled = canAct) { Text("存入手机原件") }
                else TextButton(onClick = { openAttachment(local) }) { Text("打开手机原件") }
            }
        }
        if (!snapshot.optBoolean("teamRemoteWriter") && saved.optString("jobId") == source.optString("jobId")) TextButton(onClick = { select(if (selectedId == saved.optString("snapshotId")) null else JSONObject(saved.toString())) }, enabled = canAct) {
            Text(if (selectedId == saved.optString("snapshotId")) "已选此版本继续（点击取消）" else "以此版本明确继续", fontSize = 14.sp)
        }
    }
    snapshot.optJSONObject("snapshotFile")?.let { file ->
        Text("冻结原件：${file.optString("path")} · ${file.optLong("bytes")}字节", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        SelectionContainer { Text("SHA-256 ${file.optString("sha256")}\n文件版本 ${file.optString("fileVersion")}", fontSize = 13.sp) }
        Text("来自原job结算后明确采集的workspace；原字节已固定，不等于作者身份或业务成功证明。", fontSize = 13.sp, lineHeight = 19.sp, color = SecondaryInk)
        if (file.optString("preview") == "utf8_text") SelectionContainer { Text(file.optString("text"), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) }
        else Text("二进制原件已核对完整字节与SHA，不伪装为可读文字。", fontSize = 13.sp, color = SecondaryInk)
        if (file.optBoolean("viewTruncated")) Text("预览已截断；SHA对应完整冻结原件。", fontSize = 13.sp, color = SecondaryInk)
    }
    error?.let { Text(it, color = Danger, fontSize = 14.sp) }
    confirmation?.let { reviewed ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("仅保存这些明确文件") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原job：" + source.optString("jobId"), fontSize = 14.sp)
                SelectionContainer { Text(reviewed.second.joinToString("\n"), fontSize = 14.sp) }
                Text("真实内容将保存在此原job的电脑端记录中。不会递归扫描，不批准任何命令或团队写入。", fontSize = 14.sp)
            }
        }, confirmButton = { Button(onClick = { capture(JSONArray(reviewed.second)); confirmation = null }, enabled = canAct && reviewed.first == snapshot.optString("reviewId")) { Text("确认保存本次指定字节") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("返回核对") } })
    }
}

@Composable
private fun RemoteApprovalSummary(approval: JSONObject) {
    var raw by remember(approval.optString("requestId")) { mutableStateOf(false) }
    val input = approval.optJSONObject("input") ?: JSONObject()
    val command = approval.opt("command")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty().ifBlank { input.optString("command") }
    val cwd = approval.optString("cwd").ifBlank { input.optString("cwd") }
    val path = input.optString("file_path").ifBlank { input.optString("path") }
    Text("工具：" + approval.optString("tool").ifBlank { approval.optString("method", "执行端未报告") }, fontSize = 13.sp)
    if (command.isNotBlank()) SelectionContainer { Text("命令：$command", fontSize = 12.sp, lineHeight = 20.sp) }
    if (cwd.isNotBlank()) Text("工作目录：$cwd", fontSize = 12.sp)
    if (path.isNotBlank()) Text("目标路径：$path", fontSize = 12.sp)
    if (approval.optString("reason").isNotBlank()) Text(approval.optString("reason"), fontSize = 12.sp, lineHeight = 20.sp)
    val fileChanges = approval.optJSONArray("files").objects()
    fileChanges.forEach { file ->
        Text("本次所选文件：" + file.optString("path"), fontSize = 12.sp)
        SelectionContainer { Text(file.optString("diff"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) }
    }
    if (approval.optBoolean("projectCommandRisk")) {
        Text("仅批准上面这一条命令。它以电脑当前用户权限运行，工作目录不是操作系统隔离；可能读取该用户可访问的数据或改变本机/网络状态。", fontSize = 14.sp, lineHeight = 22.sp, color = Danger)
        Text("这不允许会话级放行、修改执行策略、网络扩权或扩大根目录。若命令范围不明确，请拒绝并说明目标。", fontSize = 14.sp, lineHeight = 22.sp, color = SecondaryInk)
    } else if (approval.optString("scopeReason").isNotBlank()) Text("仅明确所选路径的单次文件修改可批准；命令/会话/网络扩权及未知文件不会获准。", fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk)
    if (command.isBlank() && path.isBlank() && fileChanges.isEmpty()) Text("执行端没有提供完整命令/文件摘要，请展开原文核对访问范围。", fontSize = 12.sp, color = SecondaryInk)
    TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起协议原文" else "展开工具输入与协议原文", fontSize = 12.sp) }
    if (raw) SelectionContainer { Text(approval.toString(2), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp) }
}

@Composable
private fun RemoteResultPanel(snapshot: JSONObject, canRead: Boolean, read: (String) -> Unit) {
    val result = snapshot.optJSONObject("result")
    var expanded by remember(snapshot.optJSONObject("source")?.optString("jobOperationId")) { mutableStateOf(false) }
    var path by remember(snapshot.optString("taskId")) { mutableStateOf("") }
    if (result != null && (result.optString("output").isNotBlank() || result.optString("diff").isNotBlank())) {
        HorizontalDivider(color = Line)
        Text("执行端实际回执与改动", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text("job ${result.optString("jobId")} · turn ${result.optString("turnId").ifBlank { "未报告" }} · ${if (snapshot.optBoolean("fresh")) "本次查询" else "上次缓存，待重查"}", fontSize = 11.sp, color = SecondaryInk)
        if (result.has("exitCode") && !result.isNull("exitCode")) Text("退出码：" + result.optInt("exitCode"), fontSize = 12.sp)
        Text("这是执行器报告的输出/diff，不等于当前Git工作树或冻结产物；退出码0也不自动证明目标完成。", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起结果正文" else "查看输出与diff") }
        if (expanded) listOf("output" to "输出", "diff" to "执行器报告的diff").forEach { (key, label) ->
            if (result.optString(key).isNotBlank()) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                SelectionContainer { Text(result.optString(key), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) }
                if (result.optBoolean(key + "ViewTruncated") || key == "output" && result.optBoolean("outputTruncated")) Text("正文显示有截断，不能视作完整日志。", fontSize = 11.sp, color = SecondaryInk)
            }
        }
    }
    if (snapshot.optJSONObject("source")?.optString("jobId").orEmpty().isNotBlank()) {
        OutlinedTextField(path, { path = it }, label = { Text("原工作区内的相对文件路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { read(path.trim()) }, enabled = canRead && path.isNotBlank() && path.length <= 2048) { Text("核对当前文件字节与SHA（只读）") }
        Text("仅原job绑定的Companion本地工作区支持安全读取；SSH/Windows暂不可用会明确拒绝，不绕过为任意路径下载。", fontSize = 11.sp, lineHeight = 19.sp, color = SecondaryInk)
    }
    snapshot.optJSONObject("artifact")?.let { artifact ->
        Text("本次读取：${artifact.optString("path")} · ${artifact.optLong("bytes")}字节", fontSize = 12.sp)
        SelectionContainer { Text("SHA-256 " + artifact.optString("sha256"), fontSize = 10.sp) }
        Text("这是读取当时的工作区字节，不是该turn的不可变成果。", color = SecondaryInk, fontSize = 11.sp)
        if (artifact.optString("preview") == "utf8_text") SelectionContainer { Text(artifact.optString("text"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) }
        else Text("二进制文件已核对大小与SHA，不伪装为可读文字。", color = SecondaryInk, fontSize = 12.sp)
        if (artifact.optBoolean("viewTruncated")) Text("文字预览已截断，SHA对应完整读取字节。", color = SecondaryInk, fontSize = 11.sp)
    }
}
