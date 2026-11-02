package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray

/** Product page over native owner producers. Opening, refreshing and viewing never enable a
 * receiver, create an identity, start a model, grant permission or replay an unknown operation. */
@Composable
internal fun MyDevicesPanel(
    host: NativeDeviceWorkspaceHost,
    tasks: List<UiTask>,
    runOnWorker: (Runnable) -> Unit,
    changed: () -> Unit,
    openTask: (String) -> Unit,
    openModels: () -> Unit,
    back: () -> Unit,
    nativeRevision: Long = 0,
    runOnControl: (Runnable) -> Unit = runOnWorker
) {
    val window = remember(host) { host.openOwnerWindow() }
    val alive = remember(window) { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var snapshot by remember(window) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(window) { mutableStateOf<String?>(null) }
    var error by remember(window) { mutableStateOf<String?>(null) }
    var stoppingTasks by remember(window) { mutableStateOf<Set<String>>(emptySet()) }
    var notice by remember(window) { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pairing by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.PairReview?>(null) }
    var goal by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.GoalReview?>(null) }
    var grantRevision by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.GrantReview?>(null) }
    var connectionReview by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.ConnectionReview?>(null) }
    var connectionUpdateId by remember(window) { mutableStateOf<String?>(null) }
    var abandonment by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.AbandonReview?>(null) }
    var recoveryChoice by remember(window) { mutableStateOf<JSONObject?>(null) }
    var recoveryAddresses by remember(window) { mutableStateOf<List<String>>(emptyList()) }
    var recoveryEndpoint by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.RecoveryEndpoint?>(null) }
    var recoveryEndpointView by remember(window) { mutableStateOf<JSONObject?>(null) }
    val recoveryGeneration = remember(window) { java.util.concurrent.atomic.AtomicLong() }
    var addDevice by remember { mutableStateOf(false) }
    // Invitations contain bearer credentials: no rememberSaveable, logging, task or clipboard write.
    var invitation by remember(window) { mutableStateOf<String?>(null) }
    var confirmDisable by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf<JSONObject?>(null) }
    var confirmDisconnect by remember { mutableStateOf<JSONObject?>(null) }
    val listScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    LaunchedEffect(selectedId) { detailScroll.scrollTo(0) }
    val relevantTaskIds = remember(tasks, snapshot) { (tasks.filter(::isDeviceWorkspaceTask).map { it.id } + workspaceTaskLinks(snapshot).map { it.optString("parentTaskId") }).distinct().toTypedArray() }
    val latestTaskIds by rememberUpdatedState(relevantTaskIds)
    val readTrigger = nativeRevision.toString() + ":" + tasks.filter { it.id in relevantTaskIds }.map { "${it.id}:${it.updatedAt}:${it.status}:${it.raw.optLong("deviceStateRevision")}" }.joinToString("|")
    var lastReadTrigger by remember(window) { mutableStateOf<String?>(null) }

    fun execute(label: String, success: String? = null, action: () -> Unit = {}) {
        if (busy != null || !alive.get()) return
        busy = label; error = null; notice = null; lastReadTrigger = readTrigger
        val readTaskIds = latestTaskIds
        try {
            runOnWorker(Runnable {
                var next: JSONObject? = null
                var failure: String? = null
                try { action(); next = window.snapshot(readTaskIds) }
                catch (problem: Exception) {
                    failure = when (problem) { is org.json.JSONException -> "无法读取这份设备信息，请重新复制邀请或刷新状态。"; is java.io.IOException -> "连接或回执尚未确认，请查看已有结果后再继续。"; else -> problem.message?.take(400) ?: "设备操作尚未完成，请刷新后核对。" }
                    // A failed/uncertain mutation is not retried. Read its actual state once.
                    try { next = window.snapshot(readTaskIds) } catch (_: Exception) { }
                }
                val read = next; val message = failure
                main.post {
                    if (alive.get()) {
                        if (read != null) snapshot = read
                        error = message; busy = null
                        if (message == null) notice = success
                        if (success != null) changed()
                    }
                }
            })
        } catch (_: Exception) { busy = null; error = "本机设备工作线程不可用，请返回后重新打开。" }
    }
    fun requestTaskStop(id: String) {
        if (!alive.get() || id in stoppingTasks) return
        stoppingTasks = stoppingTasks + id
        try { runOnControl(Runnable {
            var failure: String? = null
            try { window.requestStop(id) } catch (problem: Exception) { failure = problem.message?.take(250) ?: "停止请求尚未确认，请查看任务状态。" }
            val message = failure
            main.post { if (alive.get()) {
                stoppingTasks = stoppingTasks - id; error = message
                if (message == null) notice = "已请求停止，等待执行端确认。"
                changed() // Read the original producer; never changes a task to stopped here.
            } }
        }) } catch (_: Exception) { stoppingTasks = stoppingTasks - id; error = "停止入口暂不可用，请使用任务通知中的停止按钮。" }
    }
    fun dismissReview() { recoveryGeneration.incrementAndGet(); window.discardReview(); pairing = null; goal = null; connectionReview = null; grantRevision = null; recoveryChoice = null; abandonment = null; recoveryEndpoint = null; recoveryEndpointView = null }
    fun goBack() { dismissReview(); if (selectedId != null) selectedId = null else back() }
    DisposableEffect(window) { onDispose { alive.set(false); window.close(); invitation = null } }
    LaunchedEffect(window, readTrigger, busy == null) { if (busy == null && lastReadTrigger != readTrigger) execute("读取设备") }
    BackHandler {
        when {
            pairing != null || goal != null || connectionReview != null || grantRevision != null || abandonment != null || recoveryChoice != null || recoveryEndpoint != null -> dismissReview()
            connectionUpdateId != null -> connectionUpdateId = null
            invitation != null -> invitation = null
            addDevice -> addDevice = false
            confirmDisable -> confirmDisable = false
            confirmRevoke != null -> confirmRevoke = null
            confirmDisconnect != null -> confirmDisconnect = null
            else -> goBack()
        }
    }
    val peers = snapshot?.optJSONArray("peers").objects()
    val selected = peers.firstOrNull { it.optString("deviceId") == selectedId }
    val local = snapshot?.optJSONObject("local") ?: JSONObject()
    val actions = snapshot?.optJSONObject("actions") ?: JSONObject()
    val pending = snapshot?.optJSONArray("pendingPairings").objects()

    Column(Modifier.fillMaxSize().background(PageSurface).testTag("device_workspace")) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::goBack, modifier = Modifier.heightIn(min = 48.dp).testTag("device_workspace_back")) { Text("返回") }
            Text(if (selectedId == null) "我的设备" else selected?.let(::deviceName) ?: "设备详情",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { execute("刷新设备") { selectedId?.let { window.refreshPeerFacts(it) } } }, enabled = busy == null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("device_workspace_refresh")) { Text(if (busy == null) "刷新" else "读取中") }
        }
        HorizontalDivider(color = Line, thickness = .5.dp)
        if (snapshot == null && busy != null) {
            Column(Modifier.padding(22.dp)) { Text("正在读取本机设备状态…", style = MaterialTheme.typography.bodyMedium) }
        }
        Column(Modifier.weight(1f).fillMaxWidth()
            .verticalScroll(if (selectedId == null) listScroll else detailScroll)
            .padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            error?.let { DeviceMessage("还没有核对成功", it, "warning", "device_workspace_error") }
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SecondaryInk, modifier = Modifier.testTag("device_workspace_notice")) }
            if (snapshot != null) {
                if (selectedId == null) {
                    DeviceLocalReceiver(local, actions, busy != null,
                        enable = { execute("启用接收", "已读取启用后的真实状态。") { window.setReceiverEnabled(true) } },
                        disable = { confirmDisable = true })
                    if (pending.isNotEmpty()) {
                        DeviceSectionTitle("需要你确认", "比较两台设备显示的指纹")
                        DeviceContentSurface {
                            pending.forEachIndexed { index, candidate ->
                                DevicePendingPairing(candidate, busy != null) {
                                    if (candidate.optBoolean("advanceAvailable")) execute("核对配对状态", "已更新配对状态。") { window.advancePairing(candidate.optString("candidateId", candidate.optString("inviteId"))) }
                                    else execute("读取配对审阅") {
                                        val review = window.reviewCandidate(candidate.optString("candidateId", candidate.optString("inviteId")))
                                        main.post { if (alive.get()) pairing = review }
                                    }
                                }
                                if (index < pending.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = Line, thickness = .5.dp)
                            }
                        }
                    }
                    if (snapshot?.optBoolean("hasMorePendingPairings") == true) Text("还有 ${snapshot?.optInt("hiddenPendingPairingCount")} 项配对等待处理，当前显示前 8 项。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    val localOffers = snapshot?.optJSONArray("pairingOffersToRecover").objects()
                    val committedPairs = snapshot?.optJSONArray("committedPairingsToRecover").objects()
                    if (localOffers.isNotEmpty() || committedPairs.isNotEmpty()) DeviceRecoveryChoices(localOffers, committedPairs, busy != null,
                        abandon = { original -> execute("读取本机待配对记录") { val native = window.reviewPairingAbandonment(original.getString("inviteId")); main.post { if (alive.get()) abandonment = native } } },
                        receive = { original -> recoveryGeneration.incrementAndGet(); recoveryChoice = JSONObject(original.toString()).put("side", "offer") },
                        provide = { original -> val revision = recoveryGeneration.incrementAndGet(); execute("读取恢复地址") {
                            val actual = window.recoveryAddresses(); main.post { if (alive.get() && recoveryGeneration.get() == revision) { recoveryAddresses = actual; recoveryChoice = JSONObject(original.toString()).put("side", "committed") } }
                        } })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("你的设备", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                        OutlinedButton(onClick = { addDevice = true }, enabled = busy == null,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("device_add")) { AffectGlyph("plus", Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text("配对设备") }
                    }
                    if (peers.isEmpty()) {
                        DeviceContentSurface {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("连接你的手机与电脑", style = MaterialTheme.typography.titleMedium)
                                Text("配对后可把任务交给另一台设备，也可以继续在本机工作。", style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
                                Text("还没有已配对设备。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                            }
                        }
                    } else DeviceContentSurface {
                        peers.forEachIndexed { index, peer ->
                            DevicePeerRow(peer, deviceTasksForPeer(tasks, peer.optString("deviceId"), snapshot).size) { selectedId = peer.optString("deviceId") }
                            if (index < peers.lastIndex) HorizontalDivider(Modifier.padding(start = 66.dp), color = Line, thickness = .5.dp)
                        }
                    }
                    if (snapshot?.optBoolean("hasMorePeers") == true) Text("还有更多设备，当前显示一页。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    val shown = tasks.filter(::isDeviceWorkspaceTask).take(12)
                    if (shown.isNotEmpty()) {
                        DeviceSectionTitle("设备任务", "")
                        DeviceContentSurface {
                            shown.forEachIndexed { index, task ->
                                DeviceTaskRow(task, null, snapshot?.optJSONObject("taskActions")?.optJSONObject(task.id),
                                    busy != null, { openTask(task.id) },
                                    { execute("读取本机模型审阅") { val review = window.reviewGoal(task.id); main.post { if (alive.get()) goal = review } } },
                                    { requestTaskStop(task.id) }, stopBusy = task.id in stoppingTasks)
                                if (index < shown.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Line, thickness = .5.dp)
                            }
                        }
                    }
                    if (tasks.count(::isDeviceWorkspaceTask) > 12) Text("当前显示近期 12 项，更多任务可在任务列表查看。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    if (snapshot?.optBoolean("hasMoreTaskActions") == true) Text("部分任务操作请在任务详情中处理。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    DeviceSnapshotFootnote(snapshot)
                } else if (selected == null) {
                    DeviceMessage("这台设备不在当前读取页", "保留了原设备身份，没有把另一行当作它。返回设备列表刷新后再打开。", "question")
                    TextButton(onClick = { selectedId = null }) { Text("返回设备列表") }
                } else {
                    DevicePeerDetails(selected, tasks, snapshot, busy != null, openTask,
                        reviewGoal = { task -> execute("读取本机模型审阅") { val review = window.reviewGoal(task.id); main.post { if (alive.get()) goal = review } } },
                        stop = { task -> requestTaskStop(task.id) }, stoppingTasks = stoppingTasks,
                        connect = { execute("连接设备", "正在建立连接，请以实际连接状态为准。") { window.connectPeer(selected.optString("deviceId")) } },
                        disconnect = { confirmDisconnect = selected }, editGrant = { execute("读取设备权限") { val native = window.reviewPeerGrant(selected.optString("deviceId")); main.post { if (alive.get()) grantRevision = native } } }, updateConnection = { connectionUpdateId = selected.optString("deviceId") }, revoke = { confirmRevoke = selected })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (addDevice) DeviceAddDialog(actions, busy != null,
        close = { addDevice = false },
        create = { address -> execute("创建短时邀请") { val value = window.createInvitation(address); main.post { if (alive.get()) { invitation = value; addDevice = false } } } },
        inspect = { raw -> execute("核对原设备邀请") { val review = window.inspectInvitation(raw); main.post { if (alive.get()) { pairing = review; addDevice = false } } } })
    connectionUpdateId?.let { id -> DeviceConnectionInputDialog(busy != null, { connectionUpdateId = null }) { raw ->
        execute("核对连接地址") {
            val native = window.reviewConnectionUpdate(id, raw)
            main.post { if (alive.get()) { connectionReview = native; connectionUpdateId = null } }
        }
    } }
    connectionReview?.let { native -> DeviceConnectionReviewDialog(native.view(), busy != null, ::dismissReview) {
        execute("更新连接地址", "连接地址已更新；连接状态仍以实际验证结果为准。") {
            try { window.confirmConnectionUpdate(native) } finally { main.post { if (alive.get()) connectionReview = null } }
        }
    } }
    abandonment?.let { original -> DevicePairingAbandonment(original.view(), busy != null, ::dismissReview) {
        execute("放弃本机待配对", "本机待配对记录已放弃，对端状态未确认。") { try { window.confirmPairingAbandonment(original) } finally { main.post { if (alive.get()) abandonment = null } } }
    } }
    recoveryChoice?.let { original -> DeviceRecoveryLocator(original, recoveryAddresses, busy != null, ::dismissReview) { hostAddress, port ->
        val revision = recoveryGeneration.get()
        if (original.optString("side") == "committed") execute("开启结果恢复端口") {
            if (recoveryGeneration.get() != revision) throw IllegalStateException("恢复窗口已关闭")
            val native = window.openCommittedRecovery(original.getString("inviteId"), hostAddress)
            val safe = native.view()
            main.post { if (alive.get() && recoveryGeneration.get() == revision) { recoveryEndpoint = native; recoveryEndpointView = safe; recoveryChoice = null } else native.close() }
        } else execute("核对原配对结果", "已核对原配对结果。设备仍需使用当前地址建立连接。") {
            if (recoveryGeneration.get() != revision) throw IllegalStateException("恢复窗口已关闭")
            window.recoverPairing(original.getString("inviteId"), hostAddress, port)
            main.post { if (alive.get() && recoveryGeneration.get() == revision) recoveryChoice = null }
        }
    } }
    recoveryEndpointView?.let { endpoint -> DeviceRecoveryEndpoint(endpoint, ::dismissReview) }
    invitation?.let { value -> DeviceInvitationDialog(value) { invitation = null } }
    pairing?.let { review -> DevicePairingReview(review.view(), busy != null, ::dismissReview,
        reviewGrant = { choice -> execute("审阅准确配对授权") {
            try { val updated = window.reviewPairingGrant(review, choice); main.post { if (alive.get()) pairing = updated } }
            catch (failure: Exception) { main.post { if (alive.get()) pairing = null }; throw failure }
        } },
        confirm = { execute("确认双端身份", "本机指纹核对已提交；只有后续双向 TLS 与原生授权完成才算可用。") { try { window.confirmPairing(review) } finally { main.post { if (alive.get()) pairing = null } } } },
        reject = { execute("拒绝配对", "本机已拒绝这次配对，请核对最新状态。") { try { window.rejectPairing(review) } finally { main.post { if (alive.get()) pairing = null } } } }) }
    grantRevision?.let { native -> DevicePairingReview(native.view(), busy != null, ::dismissReview,
        reviewGrant = { choice -> execute("核对权限修改") {
            try { val next = window.reviewGrantRevision(native, choice); main.post { if (alive.get()) grantRevision = next } }
            catch (failure: Exception) { main.post { if (alive.get()) grantRevision = null }; throw failure }
        } },
        confirm = { execute("确认权限修改", "权限已更新，已有任务与未确认操作仍按各自结果核对。") { try { window.confirmGrantRevision(native) } finally { main.post { if (alive.get()) grantRevision = null } } } },
        reject = ::dismissReview, editingGrant = true) }
    goal?.let { review -> DeviceGoalModelReview(review.view(), busy != null, ::dismissReview, openModels = { dismissReview(); openModels() },
        confirm = { future -> execute("确认本机模型", "本机模型选择已交回原任务，实际执行状态见原任务。") { try { window.confirmGoal(review, future) } finally { main.post { if (alive.get()) goal = null } } } }) }
    if (confirmDisable) DeviceConfirmDialog("停用这台手机的设备接收？", "不再接受新的设备请求。已经接受的任务是否停止，仍须核对原任务；不会因为关闭连接就显示已停止。", "停用接收", busy != null, { confirmDisable = false }) {
        execute("停用接收", "已读取停用后的真实状态；正在执行的任务仍按原回执显示。") { window.setReceiverEnabled(false); main.post { if (alive.get()) confirmDisable = false } }
    }
    confirmDisconnect?.let { peer -> DeviceConfirmDialog("停止主动连接${deviceName(peer)}？", "这不会停止设备上已接受的任务。未确认的操作仍需查看执行结果。", "停止主动连接", busy != null, { confirmDisconnect = null }) {
        execute("停止主动连接", "已停止本机主动连接；任务结果仍按执行端回执显示。") { window.disconnectPeer(peer.optString("deviceId")); main.post { if (alive.get()) confirmDisconnect = null } }
    } }
    confirmRevoke?.let { peer -> DeviceConfirmDialog("撤销与${deviceName(peer)}的信任？", "本机撤销将立即使旧授权失效。对端是否收到撤销回执单独显示，离线不等于对端已知；正在发生的动作仍需核对。", "撤销信任", busy != null, { confirmRevoke = null }) {
        execute("撤销设备信任", "已读取本机撤销状态；对端确认仍以实际回执为准。") { window.revoke(peer.optString("deviceId")); main.post { if (alive.get()) confirmRevoke = null } }
    } }
}

@Composable
private fun DeviceRecoveryChoices(offers: List<JSONObject>, committed: List<JSONObject>, busy: Boolean, abandon: (JSONObject) -> Unit, receive: (JSONObject) -> Unit, provide: (JSONObject) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    DeviceContentSurface { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DeviceDisclosure("配对结果恢复", expanded, { expanded = !expanded }, "device_pair_recovery_toggle")
        if (expanded) {
            Text("仅核对双方已经确认、仍然有效的配对结果。未确认的邀请不能在到期后继续配对。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            offers.forEach { original -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("设备 ${original.optString("inviterDeviceId").takeLast(4)} · 读取已提交结果", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { receive(original) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_pair_recovery_offer_${original.optString("inviteId")}")) { Text("核对") }
                if (original.optBoolean("abandonAvailable")) TextButton(onClick = { abandon(original) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_pair_abandon_${original.optString("inviteId")}")) { Text("放弃本机") }
            } }
            if (offers.size >= 8 || committed.size >= 8) Text("当前显示最多 8 项原配对记录。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            committed.forEach { original -> Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("设备 ${original.optString("peerDeviceId").takeLast(4)} · 提供原配对结果", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { provide(original) }, enabled = !busy && original.optString("recoveryState") == "available", modifier = Modifier.heightIn(min = 48.dp).testTag("device_pair_recovery_provide_${original.optString("inviteId")}")) { Text("提供端口") }
                }
                if (original.optString("recoveryState") != "available") Text("原权限已到期、撤销或改变，不能恢复旧结果。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            } }
        }
    } }
}

@Composable
private fun DevicePairingAbandonment(view: JSONObject, busy: Boolean, close: () -> Unit, confirm: () -> Unit) {
    var checked by remember(view) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text("放弃本机待配对？") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本机将不再接收这次邀请的配对结果。对端可能已经保存配对，这里不会替对端撤销，请在对端分别核对。", style = MaterialTheme.typography.bodyMedium)
            DeviceFingerprint("原设备指纹", view.optString("inviterFingerprint"))
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) { checked = !checked }.testTag("device_pair_abandon_checked"), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, null); Text("我明白这只影响本机", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
        }
    }, confirmButton = { Button(onClick = confirm, enabled = checked && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_pair_abandon_confirm")) { Text("只放弃本机记录") } }, dismissButton = { TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") } })
}

@Composable
private fun DeviceRecoveryLocator(original: JSONObject, addresses: List<String>, busy: Boolean, close: () -> Unit, confirm: (String, Int) -> Unit) {
    val providing = original.optString("side") == "committed"
    var host by remember(original) { mutableStateOf(if (providing && addresses.size == 1) addresses.single() else "") }
    var port by remember(original) { mutableStateOf("") }
    val value = port.toIntOrNull()
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text(if (providing) "提供原配对结果" else "核对原配对结果") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (providing) {
                Text("选择本机实际局域网地址。恢复端口只开放 30 秒，退出页面将关闭。", style = MaterialTheme.typography.bodyMedium)
                if (addresses.isEmpty()) Text("当前没有可用局域网地址。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                addresses.forEach { address -> Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.RadioButton) { host = address }, verticalAlignment = Alignment.CenterVertically) { RadioButton(host == address, null); Text(address, style = MaterialTheme.typography.bodyMedium) } }
                DeviceFingerprint("接收结果的原设备", original.optString("peerFingerprint"))
            } else {
                Text("先在另一台设备的‘配对结果恢复’中提供端口，再填写它显示的地址与端口。地址只用于寻找设备，身份仍按原完整指纹核对。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(host, { if (it.length <= 64) host = it }, label = { Text("局域网数字地址") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("device_pair_recovery_host"))
                OutlinedTextField(port, { if (it.length <= 5 && it.all(Char::isDigit)) port = it }, label = { Text("恢复端口") }, singleLine = true, enabled = !busy, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("device_pair_recovery_port"))
                DeviceFingerprint("原设备完整指纹", original.optString("inviterFingerprint"))
            }
        }
    }, confirmButton = { Button(onClick = { confirm(host, value ?: 0) }, enabled = !busy && host.isNotBlank() && (providing && host in addresses || !providing && value != null && value in 1..65535), modifier = Modifier.heightIn(min = 48.dp).testTag("device_pair_recovery_confirm")) { Text(if (providing) "开启恢复端口" else "读取已提交结果") } }, dismissButton = { TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun DeviceRecoveryEndpoint(endpoint: JSONObject, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text("原配对结果恢复端口") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text("${endpoint.optString("host")}  ·  ${endpoint.optInt("port")}", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace) }
            Text("请在原设备填写上述地址和端口。入口截至 ${deviceTime(endpoint.optLong("expiresAt"))}；到期后需要重新开启，不延长原权限。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            DeviceFingerprint("本机指纹", endpoint.optString("localFingerprint")); DeviceFingerprint("原配对设备", endpoint.optString("peerFingerprint"))
            Text("端口开放状态读取于 ${deviceTime(endpoint.optLong("capturedAt"))}，退出将关闭端口。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        }
    }, confirmButton = { TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp).testTag("device_pair_recovery_close")) { Text("关闭恢复端口") } })
}

@Composable
private fun DeviceLocalReceiver(local: JSONObject, actions: JSONObject, busy: Boolean, enable: () -> Unit, disable: () -> Unit) {
    val enabled = local.optBoolean("receiverEnabled")
    val action = actions.optJSONObject(if (enabled) "disable" else "enable")
    DeviceContentSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceMark("phone")
                Column(Modifier.weight(1f)) {
                    Text("这台手机", style = MaterialTheme.typography.titleMedium)
                    Text(if (!enabled) "设备接收未启用" else if (local.optBoolean("listenerReady")) "本机接收通道已启动" else "已启用 · 接收通道尚未就绪", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                }
                TextButton(onClick = if (enabled) disable else enable, enabled = !busy && actionAvailable(action), modifier = Modifier.heightIn(min = 48.dp).testTag("device_receiver_toggle")) { Text(if (enabled) "停用" else "启用") }
            }
            if (local.optString("receiverReason").isNotBlank()) Text(local.optString("receiverReason"), style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            if (!actionAvailable(action)) DeviceBlockedReason(action)
            if (local.optString("deviceId").isBlank()) Text("启用后可接收其他设备的任务。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            else Text("仅接收你已配对并授权的设备。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        }
    }
}

@Composable
private fun DevicePeerRow(peer: JSONObject, taskCount: Int, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open).padding(16.dp).testTag("device_peer_${peer.optString("deviceId")}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DeviceMark(peer.optString("platform"))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(deviceName(peer), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(deviceConnectionLabel(peer), style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            Text(if (peer.optBoolean("revoked")) "信任已撤销" else "已配对 · ${if (taskCount == 0) "暂无任务" else "近期 $taskCount 项任务"}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        }
        AffectGlyph("chevron-right", Modifier.size(16.dp), SecondaryInk)
    }
}

@Composable
private fun DevicePeerDetails(peer: JSONObject, tasks: List<UiTask>, snapshot: JSONObject?, busy: Boolean,
    openTask: (String) -> Unit, reviewGoal: (UiTask) -> Unit, stop: (UiTask) -> Unit, stoppingTasks: Set<String>, connect: () -> Unit, disconnect: () -> Unit, editGrant: () -> Unit, updateConnection: () -> Unit, revoke: () -> Unit) {
    val peerId = peer.optString("deviceId")
    val associated = deviceTasksForPeer(tasks, peerId, snapshot)
    var technical by rememberSaveable(peerId) { mutableStateOf(false) }
    var grants by rememberSaveable(peerId) { mutableStateOf(false) }
    DeviceContentSurface {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceMark(peer.optString("platform")); Column(Modifier.weight(1f)) {
                    Text(deviceName(peer), style = MaterialTheme.typography.titleLarge)
                    Text(deviceConnectionLabel(peer), style = MaterialTheme.typography.bodyMedium, color = SecondaryInk, modifier = Modifier.semantics { stateDescription = deviceConnectionLabel(peer) })
                }
            }
            Text(deviceConnectionDetail(peer), style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
            peer.optJSONObject("connection")?.takeIf { it.optBoolean("unconfirmedTransportWrite") }?.let { Text("有操作结果待确认，请查看执行结果。", style = MaterialTheme.typography.bodyMedium) }
            val connectionActions = peer.optJSONObject("actions")
            val connectAction = connectionActions?.optJSONObject("connect")
            val disconnectAction = connectionActions?.optJSONObject("disconnect")
            if (actionAvailable(connectAction)) Button(onClick = connect, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_peer_connect")) { Text("连接此设备") }
            else if (peer.optJSONObject("connection")?.optString("state") !in setOf("ONLINE", "DEGRADED") && !peer.optBoolean("revoked")) DeviceBlockedReason(connectAction)
            if (actionAvailable(disconnectAction)) TextButton(onClick = disconnect, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_peer_disconnect"), contentPadding = PaddingValues(0.dp)) { Text("停止主动连接") }
            if (!peer.optBoolean("revoked")) TextButton(onClick = updateConnection, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_peer_update_connection"), contentPadding = PaddingValues(0.dp)) { Text("更新连接地址") }
            val revoke = peer.optJSONObject("revocation")
            if (peer.optBoolean("revoked")) Text(if (revoke?.optBoolean("peerAcknowledged") == true) "本机信任已撤销 · 对端已回执" else "本机信任已撤销 · 对端尚未确认", style = MaterialTheme.typography.bodyMedium)
        }
    }
    DeviceSectionTitle("任务与成果", "")
    if (associated.isEmpty()) Text("这台设备暂时没有任务。", style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
    else DeviceContentSurface {
        associated.forEachIndexed { index, task ->
            DeviceTaskRow(task, peer, snapshot?.optJSONObject("taskActions")?.optJSONObject(task.id), busy,
                { openTask(task.id) }, { reviewGoal(task) }, { stop(task) }, stopBusy = task.id in stoppingTasks)
            DeviceChildLinks(snapshot, peerId, task.id, { openTask(task.id) })
            if (index < associated.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Line, thickness = .5.dp)
        }
    }
    val capabilities = peer.optJSONObject("capabilitySnapshot")
    DeviceSectionTitle("可用能力", "")
    DeviceContentSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val rows = capabilities?.optJSONArray("capabilities").objects()
            if (rows.isEmpty()) Text("连接后可查看这台设备的可用能力。", style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
            else {
                val expired = capabilities?.optLong("validUntil", 0)?.let { it <= System.currentTimeMillis() } != false
                rows.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(deviceCapabilityName(row.optString("id")), style = MaterialTheme.typography.titleSmall)
                        Text(deviceCapabilityState(row, expired), style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                        if (!expired && deviceCapabilityReason(row.optString("reason")).isNotBlank()) Text(deviceCapabilityReason(row.optString("reason")), style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    }
                }
                Text("核对时间 ${deviceTime(capabilities?.optLong("capturedAt") ?: 0)}${if (expired) " · 已过期，请刷新" else " · 有效至 ${deviceTime(capabilities?.optLong("validUntil") ?: 0)}"}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            }
            HorizontalDivider(color = Line, thickness = .5.dp)
            DeviceDisclosure("两端权限", grants, { grants = !grants }, "device_grant_toggle")
            if (!peer.optBoolean("revoked")) TextButton(onClick = editGrant, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_grant_edit"), contentPadding = PaddingValues(0.dp)) { Text("调整本机授予的权限") }
            if (grants) {
                val scopes = peer.optJSONArray("grantScopes").objects()
                Text("允许这台设备在本机使用", style = MaterialTheme.typography.titleSmall)
                if (scopes.isEmpty()) Text("本机尚未授予任务权限。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                scopes.forEach { scope -> DeviceGrantScope(scope) }
                if (peer.optBoolean("hasMoreGrantScopes")) Text("还有未展开的授权记录，本窗口并非完整列表。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                HorizontalDivider(color = Line, thickness = .5.dp)
                Text("本机可在对端使用", style = MaterialTheme.typography.titleSmall)
                val received = peer.optJSONArray("receivedGrantScopes").objects()
                if (received.isEmpty()) Text("连接后刷新，可核对对端授予的范围。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                else {
                    Text("以下是上次收到的对端授权回执，执行时仍会核对对端当前状态。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    received.forEach { DeviceGrantScope(it) }
                }
                if (peer.optBoolean("hasMoreReceivedGrantScopes")) Text("还有未展开的对端授权回执。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            }
        }
    }
    DeviceContentSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DeviceDisclosure("身份与安全", technical, { technical = !technical }, "device_identity_toggle")
            if (technical) {
                DeviceFact("设备身份", peerId, mono = true)
                DeviceFingerprint("已配对证书指纹", peer.optString("certificateFingerprint", peer.optString("fingerprint")))
                DeviceFact("实例", peer.optString("instanceId"), mono = true)
                DeviceFact("授权版本", peer.optLong("authorizationEpoch").toString())
                capabilities?.optJSONArray("capabilities").objects().filter { it.optString("reason").isNotBlank() }.forEach { DeviceFact("能力诊断 · ${it.optString("id")}", it.optString("reason"), mono = true) }
                Text("更换证书或实例必须重新进行原生核对，不会静默信任新身份。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                if (!peer.optBoolean("revoked")) {
                    val revokeAction = peer.optJSONObject("actions")?.optJSONObject("revoke")
                    OutlinedButton(onClick = revoke, enabled = !busy && actionAvailable(revokeAction), modifier = Modifier.heightIn(min = 48.dp).testTag("device_revoke")) { Text("撤销这台设备的信任") }
                    DeviceBlockedReason(revokeAction)
                }
            }
        }
    }
    DeviceSnapshotFootnote(snapshot)
}

@Composable
private fun DevicePendingPairing(candidate: JSONObject, busy: Boolean, review: () -> Unit) {
    val reviewAvailable = candidate.optBoolean("advanceAvailable") || candidate.optBoolean("reviewAvailable", candidate.optString("state") in setOf("", "pending_owner_confirmation"))
    Row(Modifier.fillMaxWidth().clickable(enabled = !busy && reviewAvailable, role = Role.Button, onClick = review).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AffectGlyph("question", Modifier.size(20.dp)); Column(Modifier.weight(1f)) {
            Text("核对新设备的双端指纹", style = MaterialTheme.typography.titleSmall)
            Text(pairingStateLabel(candidate.optString("state")), style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        }; if (reviewAvailable) AffectGlyph("chevron-right", Modifier.size(16.dp), SecondaryInk)
    }
}

@Composable
private fun DeviceGrantScope(scope: JSONObject) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (scope.optBoolean("receivedScope")) "已收到授权回执" else when (scope.optString("state")) { "active" -> "执行授权有效"; "expired" -> "执行授权已到期"; "revoked" -> "授权已撤销"; "stale_epoch" -> "旧授权代次 · 不能执行"; "identity_changed" -> "本机身份不可用 · 旧授权不能执行"; else -> "授权状态待核对" }, style = MaterialTheme.typography.titleSmall)
        val names = scope.optJSONArray("capabilities")?.let { values -> (0 until values.length()).map { deviceMethodName(values.optString(it)) } }.orEmpty()
        Text(names.joinToString(" · ").ifBlank { "没有读取到具体方法范围" }, style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        Text("累计上限：工具 ${scope.optLong("toolBudget")} · 模型 ${scope.optLong("modelBudget")} · 至 ${deviceTime(scope.optLong("expiresAt"))}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        val usage = scope.optJSONObject("budgetStatus")
        if (usage?.optString("budgetState") == "known") Text("可分配给新任务：工具 ${usage.optLong("toolsAvailableForNewAllocations")} · 模型 ${usage.optLong("modelsAvailableForNewAllocations")}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        else Text("剩余用量尚未读取", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        DeviceHistoryPolicy(scope)
        DeviceDelegationPolicy(scope)
        val resources = scope.optJSONArray("resources").objects()
        resources.forEach { DeviceResourceScope(it) }
    }
}

@Composable
private fun DeviceAddDialog(actions: JSONObject, busy: Boolean, close: () -> Unit, create: (String) -> Unit, inspect: (String) -> Unit) {
    var raw by remember { mutableStateOf("") }
    val addresses = actions.optJSONArray("localAddresses")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
    var selectedAddress by remember(addresses) { mutableStateOf(addresses.singleOrNull()) }
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text("配对你的另一台设备") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("已有另一台设备的邀请", style = MaterialTheme.typography.titleSmall)
            Text("从可信的另一台设备复制短时邀请。接着核对两台设备显示的完整指纹。", style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
            OutlinedTextField(raw, { if (it.toByteArray(Charsets.UTF_8).size <= 16_384) raw = it }, label = { Text("粘贴原设备邀请") }, modifier = Modifier.fillMaxWidth().testTag("device_pair_invitation_input"), minLines = 3, maxLines = 5, enabled = !busy)
            Button(onClick = { inspect(raw) }, enabled = !busy && raw.isNotBlank() && actionAvailable(actions.optJSONObject("join")), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_pair_inspect")) { Text("核对邀请与身份") }
            DeviceBlockedReason(actions.optJSONObject("join"))
            HorizontalDivider(color = Line, thickness = .5.dp)
            Text("让另一台设备来连接", style = MaterialTheme.typography.titleSmall)
            Text("在本机创建一次性邀请，交给你信任的另一台设备。到期后需要重新创建。", style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
            if (addresses.size > 1) {
                Text("选择另一台设备可访问的本机地址", style = MaterialTheme.typography.bodySmall)
                addresses.forEach { address -> Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { selectedAddress = address }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selectedAddress == address, onClick = null); Text(address, style = MaterialTheme.typography.bodyMedium)
                } }
            }
            OutlinedButton(onClick = { selectedAddress?.let(create) }, enabled = !busy && selectedAddress != null && actionAvailable(actions.optJSONObject("invite")), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_pair_create_invite")) { Text("创建本机短时邀请") }
            DeviceBlockedReason(actions.optJSONObject("invite"))
        } }, confirmButton = { TextButton(onClick = close) { Text("返回") } })
}

@Composable
private fun DeviceConnectionInputDialog(busy: Boolean, close: () -> Unit, inspect: (String) -> Unit) {
    var raw by remember { mutableStateOf("") }
    val bounded = raw.isNotBlank() && raw.toByteArray(Charsets.UTF_8).size <= 16_384
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text("更新设备连接地址") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("复制这台设备的新邀请。只更新地址，不改变设备权限。", style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
            OutlinedTextField(raw, { raw = it }, label = { Text("粘贴这台设备的邀请") }, enabled = !busy, minLines = 4, maxLines = 9, modifier = Modifier.fillMaxWidth().testTag("device_connection_invitation"))
            if (raw.toByteArray(Charsets.UTF_8).size > 16_384) Text("邀请超过 16 KiB，请重新复制。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { Button(onClick = { inspect(raw) }, enabled = !busy && bounded, modifier = Modifier.heightIn(min = 48.dp)) { Text("核对设备与地址") } },
        dismissButton = { TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun DeviceConnectionReviewDialog(view: JSONObject, busy: Boolean, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text("确认新的连接地址") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("地址匹配已配对身份，连接时仍会验证这台设备。", style = MaterialTheme.typography.bodyMedium, color = SecondaryInk)
            DeviceFact("设备", "设备 ${view.optString("deviceId").takeLast(4)}")
            DeviceFact("连接地址", view.optString("host"))
            DeviceFact("控制 / 成果端口", "${view.optInt("controlPort")} / ${view.optInt("bulkPort")}")
            DeviceFingerprint("已配对设备指纹", view.optString("certificateFingerprint"))
        } }, confirmButton = { Button(onClick = confirm, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_connection_update_confirm")) { Text("更新并连接") } },
        dismissButton = { TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun DeviceInvitationDialog(raw: String, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text("只交给你信任的设备") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("这段邀请含一次性配对凭据，不要公开发送。另一台设备读取后，还须在本机核对双端指纹；邀请本身不是业务授权。", style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text(raw, fontSize = 12.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("device_invitation_secret")) }
        } }, confirmButton = { TextButton(onClick = close) { Text("已交给另一台设备 · 关闭") } })
}

@Composable
private fun DevicePairingReview(view: JSONObject, busy: Boolean, close: () -> Unit,
    reviewGrant: (NativeDeviceWorkspaceHost.GrantChoice) -> Unit, confirm: () -> Unit, reject: () -> Unit, editingGrant: Boolean = false) {
    val grant = view.optJSONObject("grantReview")
    val options = view.optJSONArray("grantOptions").objects()
    var selected by remember(view) { mutableStateOf<String?>(null) }
    var scopeQuery by remember(view) { mutableStateOf("") }
    var toolBudget by remember(view) { mutableStateOf("") }
    var modelBudget by remember(view) { mutableStateOf("") }
    var durationMinutes by remember(view) { mutableIntStateOf(15) }
    var budgetPreset by remember(view) { mutableIntStateOf(0) }
    var extraOrigins by remember(view) { mutableStateOf<Set<String>>(emptySet()) }
    var forwardingIds by remember(view) { mutableStateOf<Set<String>>(emptySet()) }
    var forwardingTools by remember(view) { mutableStateOf("") }
    var forwardingModels by remember(view) { mutableStateOf("") }
    var showOrigins by remember(view) { mutableStateOf(false) }
    var showForwarding by remember(view) { mutableStateOf(false) }
    var showCurrent by remember(view) { mutableStateOf(false) }
    var editLimits by remember(view) { mutableStateOf(false) }
    var checked by remember(view) { mutableStateOf(false) }
    var wholeDisplayCapture by remember(view) { mutableStateOf(false) }
    var retainHistory by remember(view) { mutableStateOf(false) }
    var historyDays by remember(view) { mutableIntStateOf(7) }
    var historyMethods by remember(view) { mutableStateOf<Set<String>>(emptySet()) }
    val option = options.firstOrNull { it.optString("optionId") == selected }
    val tools = toolBudget.toIntOrNull()
    val models = modelBudget.toIntOrNull()
    val forwardOptions = view.optJSONArray("forwardingOptions").objects()
    val chosenForward = forwardOptions.filter { it.optString("optionId") in forwardingIds }
    val allowDelegation = extraOrigins.isNotEmpty() || chosenForward.isNotEmpty()
    val forwardingToolCount = forwardingTools.toIntOrNull() ?: 0
    val forwardingModelCount = forwardingModels.toIntOrNull() ?: 0
    val forwardReady = chosenForward.size == forwardingIds.size && (chosenForward.isEmpty() || option?.optBoolean("allowDelegation") == true && tools != null && tools >= 1 && models != null && models >= 1 && forwardingToolCount in 0..minOf(tools, chosenForward.minOf { it.optInt("toolBudgetMax") }) && forwardingModelCount in 0..minOf(models, chosenForward.minOf { it.optInt("modelBudgetMax") }) && forwardingToolCount + forwardingModelCount > 0)
    val expiry = minOf(System.currentTimeMillis() + durationMinutes * 60_000L, option?.optLong("expiresAtMax", 0) ?: 0, chosenForward.minOfOrNull { it.optLong("expiresAtMax") } ?: Long.MAX_VALUE)
    val historyExpiry = if (retainHistory) minOf(System.currentTimeMillis() + historyDays * 86_400_000L, option?.optLong("historyReadExpiresAtMax") ?: 0) else 0
    val selectionReady = forwardReady && extraOrigins.size <= 8 && forwardingIds.size <= 8 && (!retainHistory || historyMethods.isNotEmpty() && historyExpiry > System.currentTimeMillis()) && option != null && tools != null && tools in option.optInt("toolBudgetMin", 0)..option.optInt("toolBudgetMax", 0) &&
        models != null && models in option.optInt("modelBudgetMin", 0)..option.optInt("modelBudgetMax", 0) && expiry > System.currentTimeMillis()
    val exactGrant = grant != null && grant.optString("digest").matches(Regex("[a-f0-9]{64}")) && grant.optJSONArray("capabilities") != null && grant.optJSONArray("resources") != null
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text(if (editingGrant) { if (grant == null) "调整设备权限" else "确认权限修改" } else if (grant == null) "这台设备可以做什么？" else "核对授权与双端身份") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (grant == null) {
                Text(if (editingGrant) "下面的选择将替换本机授予对端的权限。未选择的转交来源和下游范围将不再允许，已有用量不会清零。" else "选择另一台设备可以在这里做什么。双方分别决定各自的权限。", style = MaterialTheme.typography.bodyMedium)
                if (editingGrant) view.optJSONObject("currentGrant")?.let { current ->
                    DeviceDisclosure("当前权限", showCurrent, { showCurrent = !showCurrent }, "device_grant_revision_current")
                    if (showCurrent) { DeviceGrantContents(current); DeviceFact("累计上限", "工具 ${current.optLong("toolBudget")} · 模型 ${current.optLong("modelBudget")}"); DeviceHistoryPolicy(current) }
                }
                if (options.isEmpty()) Text(view.optString("grantOptionsUnavailableReason").ifBlank { "暂时没有可授予的权限，请先检查本机设置。" }, style = MaterialTheme.typography.bodyMedium)
                if (options.size > 6) OutlinedTextField(scopeQuery, { scopeQuery = it.take(120) }, placeholder = { Text("搜索应用或项目") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val filtered = options.filter { scopeQuery.isBlank() || it.optString("label").contains(scopeQuery, true) }
                filtered.take(30).forEach { candidate ->
                    val id = candidate.optString("optionId")
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.RadioButton) {
                        selected = id; toolBudget = minOf(12, candidate.optInt("toolBudgetMax")).coerceAtLeast(0).toString(); modelBudget = minOf(3, candidate.optInt("modelBudgetMax")).coerceAtLeast(0).toString(); extraOrigins = emptySet(); forwardingIds = emptySet(); forwardingTools = ""; forwardingModels = ""; showOrigins = false; showForwarding = false; editLimits = false; budgetPreset = 0; durationMinutes = 15; retainHistory = false; historyMethods = emptySet(); wholeDisplayCapture = false
                    }.testTag("device_pair_scope_$id"), verticalAlignment = Alignment.Top) {
                        RadioButton(selected == id, onClick = null)
                        Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(candidate.optString("label").ifBlank { id }, style = MaterialTheme.typography.titleSmall)
                            DeviceGrantContents(candidate)
                        }
                    }
                }
                if (filtered.size > 30) Text("还有更多范围，输入名称可快速找到。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                if (option != null) {
                    Text("授权期内累计上限", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("短期试用", "日常协作", "长任务").forEachIndexed { index, label ->
                            FilterChip(selected = budgetPreset == index, enabled = !busy, onClick = {
                                budgetPreset = index; durationMinutes = listOf(15, 24 * 60, 30 * 24 * 60)[index]
                                toolBudget = minOf(listOf(12, 200, 400)[index], option.optInt("toolBudgetMax")).coerceAtLeast(0).toString()
                                modelBudget = minOf(listOf(3, 200, 1000)[index], option.optInt("modelBudgetMax")).coerceAtLeast(0).toString()
                            }, label = { Text(label) })
                        }
                    }
                    Text("工具 ${tools ?: "—"} 次 · 模型 ${models ?: "—"} 次 · ${when (durationMinutes) { 15 -> "15 分钟"; 60 -> "1 小时"; 24 * 60 -> "1 天"; else -> "30 天" }}", style = MaterialTheme.typography.bodyMedium)
                    Text("执行截至 ${deviceTime(expiry)}。次数按整个授权期累计，任务结束不会补满。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    Text("模型费用由所选服务计费，本页不估算。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    TextButton(onClick = { editLimits = !editLimits }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_pair_edit_limits")) { Text(if (editLimits) "收起上限调整" else "调整次数与期限") }
                    if (editLimits) {
                    OutlinedTextField(toolBudget, { if (it.length <= 3 && it.all(Char::isDigit)) toolBudget = it }, label = { Text("工具次数 · ${option.optInt("toolBudgetMin")}–${option.optInt("toolBudgetMax")}") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("device_pair_tool_budget"), enabled = !busy)
                    OutlinedTextField(modelBudget, { if (it.length <= 4 && it.all(Char::isDigit)) modelBudget = it }, label = { Text("模型次数 · ${option.optInt("modelBudgetMin")}–${option.optInt("modelBudgetMax")}") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("device_pair_model_budget"), enabled = !busy)
                    Text("授权期限", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 60, 24 * 60, 30 * 24 * 60).filter { System.currentTimeMillis() + it * 60_000L <= option.optLong("expiresAtMax") + 1_000 }.forEach { minutes ->
                            FilterChip(selected = durationMinutes == minutes, onClick = { durationMinutes = minutes }, enabled = !busy,
                                label = { Text(when (minutes) { 15 -> "15 分钟"; 60 -> "1 小时"; 24 * 60 -> "1 天"; else -> "30 天" }) })
                        }
                    }
                    }
                    if (option.optBoolean("canGrantWholeDisplayCapture")) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) { wholeDisplayCapture = !wholeDisplayCapture }.testTag("device_grant_whole_capture"), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(wholeDisplayCapture, onCheckedChange = null)
                            Text("允许整屏截图（与整屏输入分开）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        if (wholeDisplayCapture) Text("截图可能包含其他可见应用或通知。只在你明确允许的任务范围内读取。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    }
                    val supportedHistory = option.optJSONArray("historyReadMethods")
                    if (supportedHistory != null && supportedHistory.length() > 0) {
                        HorizontalDivider(color = Line, thickness = .5.dp)
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) {
                            retainHistory = !retainHistory
                            historyMethods = if (retainHistory) (0 until supportedHistory.length()).map { supportedHistory.getString(it) }.toSet() else emptySet()
                        }.testTag("device_pair_history_enable"), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(retainHistory, onCheckedChange = null)
                            Text("执行到期后仍允许查看任务与结果", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        if (retainHistory) {
                            Text("结果读取授权截至 ${deviceTime(historyExpiry)}，不延长执行期限；仅限仍保留的任务与结果。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1, 7, 30).forEach { days -> FilterChip(selected = historyDays == days, enabled = !busy, onClick = { historyDays = days }, label = { Text("$days 天") }) } }
                            (0 until supportedHistory.length()).forEach { index -> val method = supportedHistory.getString(index)
                                Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) { historyMethods = if (method in historyMethods) historyMethods - method else historyMethods + method }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(method in historyMethods, onCheckedChange = null)
                                    Text(deviceHistoryMethodName(method), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    val originOptions = view.optJSONArray("originOptions").objects()
                    DeviceDisclosure("转交来源${if (extraOrigins.isEmpty()) " · 未增加" else " · 已选 ${extraOrigins.size} 台"}", showOrigins, { showOrigins = !showOrigins }, "device_grant_origins_toggle")
                    if (showOrigins) {
                        Text("直接来自本次配对设备的任务已包含。若要接收其他设备发起、经它转交的任务，请明确选择来源。首次需在这里配对并允许原发起设备。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                        if (originOptions.isEmpty()) Text("没有其他已认证的来源设备。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                        originOptions.forEach { origin -> val id = origin.optString("optionId")
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) { extraOrigins = if (id in extraOrigins) extraOrigins - id else extraOrigins + id }.testTag("device_grant_origin_$id"), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(id in extraOrigins, onCheckedChange = null); Text(origin.optString("label"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                        if (view.optBoolean("hasMoreOriginOptions")) Text("当前只列出一部分已认证设备。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    }
                    if (option.optBoolean("allowDelegation")) {
                        DeviceDisclosure("继续委派${if (forwardingIds.isEmpty()) " · 关闭" else " · 已选 ${forwardingIds.size} 个范围"}", showForwarding, { showForwarding = !showForwarding }, "device_grant_forwarding_toggle")
                        if (showForwarding) {
                            Text("只列出已经认识并允许本次来源的下游设备与准确资源。这里选择上限，发起任务时还需分配互不重复的份额。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                            forwardOptions.forEach { target -> val id = target.optString("optionId")
                                Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) {
                                    val next = if (id in forwardingIds) forwardingIds - id else forwardingIds + id; forwardingIds = next
                                    val selectedTargets = forwardOptions.filter { it.optString("optionId") in next }
                                    if (selectedTargets.isEmpty()) { forwardingTools = ""; forwardingModels = "" }
                                    else {
                                        forwardingTools = minOf(tools ?: 0, selectedTargets.minOf { it.optInt("toolBudgetMax") }, forwardingTools.toIntOrNull() ?: (tools ?: 0)).toString()
                                        forwardingModels = minOf(models ?: 0, selectedTargets.minOf { it.optInt("modelBudgetMax") }, forwardingModels.toIntOrNull() ?: (models ?: 0)).toString()
                                    }
                                }.testTag("device_grant_forward_$id"), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(id in forwardingIds, onCheckedChange = null)
                                    Column(Modifier.weight(1f)) { Text(target.optString("label"), style = MaterialTheme.typography.bodyMedium); DeviceResourceScope(target.optJSONObject("resourceBinding") ?: JSONObject()) }
                                }
                            }
                            if (chosenForward.isNotEmpty()) {
                                Text("本授权期内继续委派的累计上限", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(forwardingTools, { if (it.length <= 3 && it.all(Char::isDigit)) forwardingTools = it }, label = { Text("下游工具次数") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("device_grant_forward_tools"))
                                OutlinedTextField(forwardingModels, { if (it.length <= 4 && it.all(Char::isDigit)) forwardingModels = it }, label = { Text("下游模型次数") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("device_grant_forward_models"))
                                Text("执行期限也会受所选下游授权约束。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                            }
                            if (view.optBoolean("hasMoreForwardingOptions")) Text("当前只列出一部分可选的下游范围。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                        }
                    } else Text("没有可继续委派的已认证下游范围。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                }
            } else {
                Text(if (editingGrant) "确认这次权限修改。旧执行授权将重新核对，未确认操作的占用不会退还。" else "确认下面的权限，再比较两台设备显示的指纹。", style = MaterialTheme.typography.bodyMedium)
                if (!exactGrant) Text("准确授权摘要不完整，不能确认或签署。", style = MaterialTheme.typography.bodyMedium)
                DeviceGrantContents(grant)
                DeviceFact("授权预算", "工具 ${grant.optLong("toolBudget")} 次 · 模型 ${grant.optLong("modelBudget")} 次")
                DeviceFact("执行期限", deviceTime(grant.optLong("expiresAt")))
                DeviceHistoryPolicy(grant)
                DeviceDelegationPolicy(grant)
                if (editingGrant) {
                    DeviceFingerprint("已配对设备指纹", view.optString("certificateFingerprint"))
                    if (grant.has("retainedToolsReservedOrUsed")) Text("已有占用：工具 ${grant.optLong("retainedToolsReservedOrUsed")} · 模型 ${grant.optLong("retainedModelsReservedOrUsed")}，不会清零。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                } else {
                    DeviceFingerprint("发出邀请的设备 · ${shortDeviceId(view.optString("inviterDeviceId"))}", view.optString("inviterFingerprint"))
                    DeviceFingerprint("加入的设备 · ${shortDeviceId(view.optString("joinerDeviceId"))}", view.optString("joinerFingerprint"))
                    Text("邀请有效至 ${deviceTime(view.optLong("expiresAt"))}。另一台设备也确认后，才能完成配对。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) { checked = !checked }.testTag(if (editingGrant) "device_grant_revision_checked" else "device_pair_fingerprints_checked"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, if (busy) null else { value -> checked = value }); Text(if (editingGrant) "我确认以上权限修改，已有用量不会清零" else "我同意这些权限，并确认两台设备的两个指纹均一致", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        } }, confirmButton = {
            if (grant == null) Button(onClick = { if (option != null && tools != null && models != null) reviewGrant(NativeDeviceWorkspaceHost.GrantChoice(option.optString("optionId"), tools, models, expiry, allowDelegation, if (allowDelegation) 2 else 0, historyExpiry, historyMethods.sorted(), extraOrigins.sorted(), forwardingIds.sorted(), if (chosenForward.isEmpty()) 0 else forwardingToolCount, if (chosenForward.isEmpty()) 0 else forwardingModelCount, wholeDisplayCapture)) },
                enabled = !busy && selectionReady, modifier = Modifier.heightIn(min = 48.dp).testTag("device_pair_review_grant")) { Text(if (editingGrant) "核对权限修改" else "继续核对身份") }
            else Button(onClick = confirm, enabled = exactGrant && checked && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag(if (editingGrant) "device_grant_revision_confirm" else "device_pair_confirm")) { Text(if (busy) "提交中" else if (editingGrant) "确认修改权限" else "同意权限并确认配对") }
        }, dismissButton = { TextButton(onClick = reject, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editingGrant) "取消修改" else "拒绝这次配对") } })
}

@Composable
private fun DeviceDelegationPolicy(grant: JSONObject) {
    var identities by remember(grant) { mutableStateOf(false) }
    val forwarding = grant.optJSONObject("forwardingScope")
    if (forwarding != null) {
        Text("允许继续交给以下准确范围：", style = MaterialTheme.typography.titleSmall)
        forwarding.optJSONArray("targetResources").objects().forEach { pair -> Text("设备 ${pair.optString("targetDeviceId").takeLast(4)}", style = MaterialTheme.typography.bodyMedium); DeviceResourceScope(pair.optJSONObject("resourceBinding") ?: JSONObject()) }
        DeviceFact("下游可用工具", forwarding.optJSONArray("toolNames").deviceStrings().joinToString(" · "))
        if ("project_execute" in forwarding.optJSONArray("toolNames").deviceStrings()) Text("下游执行代码可能访问该系统用户可访问的资源，指定项目不表示系统级隔离。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        DeviceFact("下游任务类型", forwarding.optJSONArray("capabilities").deviceStrings().joinToString(" · ") { if (it == "task.submitGoal") "处理目标" else if (it == "tool.invoke") "调用工具" else it })
        Text("以上工具与类型共同适用于列明的设备范围，不代表任意工具都在每台设备上可用。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        Text("下游累计上限：工具 ${forwarding.optLong("toolBudget")} · 模型 ${forwarding.optLong("modelBudget")}；最多两条设备边，不允许再增加一跳。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
    } else Text(if (grant.optBoolean("allowDelegation")) "允许列明来源经配对设备转交任务，不允许继续交给其他设备。" else "不接收其他来源的转交任务，也不继续委派。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
    if (grant.optBoolean("allowDelegation")) {
        DeviceDisclosure("转交设备的完整身份", identities, { identities = !identities }, "device_grant_delegation_identity")
        if (identities) {
            grant.optJSONArray("allowedOrigins").deviceStrings().forEach { DeviceFact("允许的原发起设备", it, mono = true) }
            forwarding?.optJSONArray("targetResources").objects().map { it.optString("targetDeviceId") }.distinct().forEach { DeviceFact("允许的下游设备", it, mono = true) }
        }
    }
}

@Composable
private fun DeviceHistoryPolicy(grant: JSONObject) {
    val history = grant.optJSONObject("historyRead")
    if (history == null) Text("执行到期后未另行授权读取结果。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
    else {
        if (grant.optString("historyState") in setOf("revoked", "stale_epoch", "identity_changed")) Text("结果读取授权已失效。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        DeviceFact("结果读取期限", deviceTime(history.optLong("expiresAt")))
        val methods = history.optJSONArray("methods")
        if (methods != null) Text((0 until methods.length()).joinToString(" · ") { deviceHistoryMethodName(methods.getString(it)) }, style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
    }
}
private fun deviceHistoryMethodName(method: String) = when (method) { "operation.get" -> "操作回执"; "task.get" -> "任务结果"; "task.events" -> "任务进展"; "artifact.describe" -> "成果说明"; "artifact.readChunk" -> "成果内容"; else -> "未支持的读取方法" }

@Composable
private fun DeviceGrantContents(scope: JSONObject) {
    if (scope.optString("label").isNotBlank()) Text(scope.optString("label"), style = MaterialTheme.typography.titleSmall)
    if (scope.optString("effectSummary").isNotBlank()) Text(scope.optString("effectSummary"), style = MaterialTheme.typography.bodySmall)
    val capabilities = scope.optJSONArray("capabilities")
    Text(if (capabilities == null) "未提供可授权方法" else (0 until capabilities.length()).map { deviceMethodName(capabilities.optString(it)) }.distinct().joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
    val tools = scope.optJSONArray("toolNames")
    if (tools != null && tools.length() > 0) Text("准确工具：" + (0 until tools.length()).joinToString("、") { tools.optString(it) }, style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
    scope.optJSONArray("resources").objects().forEach { DeviceResourceScope(it) }
    if (scope.has("isolation") && !scope.optBoolean("isolation")) Text("执行代码可能访问该系统用户可访问的资源，项目目录不是系统沙箱。", style = MaterialTheme.typography.bodySmall)
    val origins = scope.optJSONArray("allowedOrigins")
    if (origins != null) Text(scope.optString("allowedOriginSummary").ifBlank { "允许来源：" + (0 until origins.length()).joinToString("、") { "设备 " + origins.optString(it).takeLast(4) } }, style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
}

@Composable
private fun DeviceResourceScope(resource: JSONObject) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(deviceResourceText(resource), style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        resource.keys().asSequence().sorted().filter { it != "screenMode" }.forEach { key ->
            val label = when (key) { "packageNames" -> "允许应用"; "allowWholeDisplayCapture" -> "整屏截图"; "allowWholeDisplayInput" -> "整屏输入"; "projectId" -> "准确项目"; "checkoutId" -> "准确工作副本"; "runtimeId" -> "运行环境"; "displayId" -> "指定屏幕"; "resourceId" -> "资源身份"; "kind" -> "资源类型"; else -> key }
            DeviceFact(label, when (resource.opt(key)) { true -> "允许"; false -> "不允许"; else -> resource.opt(key)?.toString().orEmpty() }, mono = key.endsWith("Id"))
        }
    }
}

@Composable
internal fun DeviceGoalModelReview(view: JSONObject, busy: Boolean, close: () -> Unit, openModels: () -> Unit, confirm: (Boolean) -> Unit) {
    var future by remember(view.optString("reviewId")) { mutableStateOf(false) }
    var contextExpanded by remember(view.optString("reviewId")) { mutableStateOf(false) }
    val accountUnproven = view.optString("source") == "local_account"
    val origin = view.optJSONObject("origin") ?: JSONObject()
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text("用这台手机的模型处理") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("此任务来自你的另一台设备，按你确认的权限执行。", style = MaterialTheme.typography.bodyMedium)
            if (view.optString("goal").isNotBlank()) Text(view.optString("goal"), style = MaterialTheme.typography.titleSmall)
            DeviceFact("本机选定模型", view.optString("model").ifBlank { "未读取到模型" })
            DeviceFact("提供方与来源", listOf(deviceModelProvider(view.optString("provider")), when (view.optString("source")) { "local_api" -> "本机 API 连接"; "local_account" -> "本机账号"; else -> view.optString("source") }).filter { it.isNotBlank() }.joinToString(" · "))
            DeviceResourceScope(view.optJSONObject("resourceBinding") ?: JSONObject())
            DeviceFact("本次上限", "工具 ${view.optLong("toolBudget")} 次 · 模型 ${view.optLong("modelBudget")} 次")
            DeviceFact("本次期限", deviceTime(view.optLong("deadline")))
            if (accountUnproven) Text("此账号暂不支持跨设备目标，请更换为已授权的 API 模型。", style = MaterialTheme.typography.bodyMedium)
            else Text("只使用你在这台手机上配置的模型。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            if (view.optString("contextSummary").isNotBlank() || view.optInt("contextArtifactCount") > 0) {
                TextButton(onClick = { contextExpanded = !contextExpanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (contextExpanded) "收起附带资料" else "查看目标附带的资料") }
                if (contextExpanded) {
                    Text(view.optString("contextSummary"), style = MaterialTheme.typography.bodyMedium)
                    Text("${view.optInt("contextArtifactCount")} 个文件引用 · 仅本任务使用", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                }
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) { future = !future }.testTag("device_goal_future_policy"), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(future, if (busy) null else { value -> future = value }); Text("以后同一配对授权、模型与范围内的目标自动处理", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (future) Text("策略上限：工具 ${view.optLong("policyToolBudget")} 次 · 模型 ${view.optLong("policyModelBudget")} 次；至 ${deviceTime(view.optLong("policyExpiresAt"))}。模型、配对代次或范围改变时重新审阅，既有任务预算不会因此补满。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            TextButton(onClick = openModels, enabled = !busy) { Text("更换模型") }
        } }, confirmButton = { Button(onClick = { confirm(future) }, enabled = !busy && !accountUnproven && view.optString("model").isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("device_goal_model_confirm")) { Text(if (busy) "提交中" else "确认模型并继续") } },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("暂不处理") } })
}

@Composable
private fun DeviceConfirmDialog(title: String, text: String, label: String, busy: Boolean, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text(title) }, text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { Button(onClick = confirm, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "提交中" else label) } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("保留") } })
}

@Composable
internal fun DeviceContentSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().pocketDepth(PocketDepth.CONTENT, 14.dp), color = Color.White, shape = RoundedCornerShape(14.dp), border = BorderStroke(.5.dp, Line)) { Column(content = content) }
}
@Composable
private fun DeviceMark(platform: String) {
    Box(Modifier.size(38.dp).background(Soft, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        DeviceOutline(platform, Modifier.size(22.dp))
    }
}
@Composable
private fun DeviceOutline(platform: String, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val phone = platform.contains("android", true) || platform.contains("phone", true)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
        val left = size.width * if (phone) .28f else .08f; val top = size.height * .12f
        val width = size.width - left * 2; val height = size.height * if (phone) .76f else .57f
        drawRoundRect(Ink, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(width, height), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
        if (phone) drawLine(Ink, androidx.compose.ui.geometry.Offset(size.width * .44f, size.height * .76f), androidx.compose.ui.geometry.Offset(size.width * .56f, size.height * .76f), strokeWidth = 1.5.dp.toPx())
        else {
            drawLine(Ink, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .7f), androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .86f), strokeWidth = 1.5.dp.toPx())
            drawLine(Ink, androidx.compose.ui.geometry.Offset(size.width * .3f, size.height * .86f), androidx.compose.ui.geometry.Offset(size.width * .7f, size.height * .86f), strokeWidth = 1.5.dp.toPx())
        }
    }
}
@Composable
internal fun DeviceSectionTitle(title: String, detail: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }); if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = SecondaryInk) } }
@Composable
internal fun DeviceFact(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(label, style = MaterialTheme.typography.bodySmall, color = SecondaryInk); SelectionContainer { Text(value.ifBlank { "尚未读取" }, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif) } }
}
@Composable
private fun DeviceFingerprint(label: String, fingerprint: String) { DeviceFact(label, fingerprint.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.chunked(8)?.joinToString(" ") ?: "未取得完整指纹 · 不能确认", mono = true) }
@Composable
private fun DeviceDisclosure(label: String, expanded: Boolean, click: () -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = click).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.bodySmall, color = SecondaryInk); Spacer(Modifier.width(6.dp)); AffectGlyph(if (expanded) "chevron" else "chevron-right", Modifier.size(14.dp), SecondaryInk)
    }
}
@Composable
private fun DeviceMessage(title: String, detail: String, icon: String, tag: String = "device_workspace_message") { DeviceContentSurface { Column(Modifier.padding(16.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { AffectGlyph(icon); Text(title, style = MaterialTheme.typography.titleSmall) }; Text(detail, style = MaterialTheme.typography.bodyMedium, color = SecondaryInk) } } }
@Composable
private fun DeviceBlockedReason(action: JSONObject?) { if (!actionAvailable(action)) Text(action?.optString("reason")?.ifBlank { "暂时不可用，请刷新后重试。" } ?: "暂时不可用，请刷新后重试。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
@Composable
private fun DeviceSnapshotFootnote(snapshot: JSONObject?) { Text("更新于 ${deviceTime(snapshot?.optLong("capturedAt") ?: 0)}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
internal fun actionAvailable(value: JSONObject?) = value?.optBoolean("available") == true
internal fun shortDeviceId(value: String) = if (value.isBlank()) "身份未读取" else if (value.length <= 22) value else value.take(12) + "…" + value.takeLast(6)
internal fun deviceName(peer: JSONObject) = peer.optString("label", peer.optString("name")).ifBlank { "已配对设备" }
internal fun deviceTime(time: Long) = if (time < 1) "尚未读取" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
internal fun deviceConnectionLabel(peer: JSONObject): String = when {
    peer.optBoolean("revoked") -> "本机已撤销信任"
    else -> when (peer.optJSONObject("connection")?.optString("state")) {
        "CONNECTING" -> "正在建立加密连接"
        "ONLINE" -> "加密连接在线"
        "DEGRADED" -> "连接不稳定"
        "OFFLINE" -> "当前离线"
        "REVOKED" -> "连接已撤销"
        "INCOMPATIBLE" -> "协议不兼容 · 未自动重试"
        else -> "实时连接尚未读取"
    }
}
private fun deviceConnectionDetail(peer: JSONObject): String {
    val connection = peer.optJSONObject("connection") ?: return "连接状态暂不可用。"
    return when (connection.optString("state")) {
        "ONLINE", "DEGRADED" -> "已建立加密连接。任务仍按各自的授权范围执行。"
        "CONNECTING" -> "仍在连接或验证身份，尚未确认可以发出业务请求。"
        "INCOMPATIBLE" -> "原生传输报告协议不兼容，请核对两端版本后再连接。"
        "REVOKED" -> "原连接不再受信任，重新启用接收不会恢复旧授权。"
        else -> "设备离线，已接受的任务可能仍在进行。"
    }
}
private fun pairingStateLabel(state: String) = when (state) { "waiting_remote_confirmation" -> "等待另一台设备确认 · 点按核对"; "confirmed_for_mtls" -> "身份已确认 · 等待完成配对"; "paired" -> "配对回执已产生 · 请核对当前信任"; "rejected" -> "本机已拒绝"; else -> "等待本机核对" }
private fun deviceModelProvider(value: String) = when (value) { "openai_responses" -> "Responses API"; "openai_chat" -> "Chat Completions API"; "anthropic" -> "Anthropic API"; "gemini" -> "Gemini API"; else -> value }
private fun deviceCapabilityName(id: String) = when (id) { "phone.tools.main" -> "手机主屏工具"; "phone.tools.background" -> "手机后台屏工具"; "agent.goal" -> "在此设备处理完整目标"; else -> id.ifBlank { "未标明的能力" } }
private fun deviceCapabilityReason(reason: String) = when (reason) {
    "local_model_unavailable" -> "这台设备还没有可用的已授权模型。"
    "native_health_unavailable" -> "尚未取得本机原生健康检查。"
    "permission_or_resource_unavailable" -> "本机权限或指定资源暂不可用。"
    else -> if (reason.isBlank()) "" else "条件尚未就绪"
}
private fun deviceCapabilityState(row: JSONObject, expired: Boolean) = when { expired -> "状态已过期，连接后刷新"; !row.optBoolean("implemented") -> "尚未实现"; !row.optBoolean("installed") -> "未安装所需组件"; !row.optBoolean("authorized") -> "尚未获得本机授权"; row.optBoolean("busy") -> "设备正忙"; row.optBoolean("ready") -> "核对时可用"; else -> "当前条件未就绪" }
private fun deviceMethodName(id: String) = when (id) { "tool.invoke" -> "调用明确工具（不使用模型）"; "task.submitGoal" -> "委派目标（本机授权模型）"; "task.cancel" -> "请求原任务停止"; "operation.cancel" -> "请求这次设备操作停止"; "task.pause" -> "请求原任务暂停"; "task.get", "task.events", "operation.get" -> "读取原任务与回执"; "task.acquireControl" -> "申请原任务控制租约"; else -> id }
internal fun deviceResourceText(value: JSONObject): String {
    val mode = when (value.optString("screenMode")) { "main_screen" -> "手机主屏"; "background" -> "手机后台屏"; "capabilities" -> "本机能力"; else -> value.optString("kind").ifBlank { "原资源范围" } }
    return listOf(mode, value.optString("projectId").takeIf { it.isNotBlank() }?.let { "项目 ${shortDeviceId(it)}" }.orEmpty(), value.optString("resourceId")).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun JSONArray?.deviceStrings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
