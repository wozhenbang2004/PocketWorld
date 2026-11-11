package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost as Host
import dev.pocketengine.features.devices.DeviceProtocol
import dev.pocketengine.store.TaskStoreDeviceTasks
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.isDeviceWorkspaceTask
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.workspaceTaskLinks
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opaque native reviews stay in their original device Window. JSON is only a view/selection. */
internal class PocketWebDevices(private val controller: PocketWorldController, private val window: () -> Host.Window?, private val visible: () -> Boolean) {
    private data class Review(val handle: String, val kind: String, val native: Any, val view: JSONObject, val window: Host.Window, val generation: Long, val expires: Long)
    private data class TaskControl(val taskId: String, val runId: String, val epoch: Int, val pending: TaskStoreDeviceTasks.PendingGoalCancellation? = null)
    private class History(val original: Host.HistoryWindow) : AutoCloseable {
        val stopSubmitted = AtomicBoolean(false)
        override fun close() = original.close()
    }
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = AtomicReference<String?>(null)
    private val controlInFlight = AtomicReference<String?>(null)
    private val generation = AtomicLong(0)
    @Volatile private var review: Review? = null
    @Volatile private var latest = JSONObject()
    @Volatile private var desktop: Host.DesktopWindow? = null
    private val desktopGeneration = AtomicLong(0)
    private val desktopInFlight = AtomicReference<String?>(null)
    companion object {
        val COMMANDS = setOf("devices.discovery.start", "devices.discovery.stop", "devices.discovery.reviewConnection", "devices.snapshot", "devices.createInvitation", "devices.inspectInvitation", "devices.reviewPairing", "devices.advancePairing", "devices.reviewPeerGrant",
            "devices.reviewGrant", "devices.reviewConnection", "devices.reviewRelay", "devices.reviewAction", "devices.reviewGoal", "devices.reviewResult", "devices.reviewApproval",
            "devices.confirm", "devices.reject", "devices.discard", "devices.recoveryAddresses", "devices.openRecovery", "devices.recoverPairing", "devices.reviewAbandonment", "devices.openTask", "devices.readTask", "devices.reviewResumeRequest",
            "devices.openHistory", "devices.queryHistory", "devices.stopHistory",
            "devices.desktop.open", "devices.desktop.negotiate", "devices.desktop.status",
            "devices.desktop.control", "devices.desktop.input", "devices.desktop.close")
        private fun fields(value: JSONObject, vararg allowed: String) = PocketWebGroupReviews.fields(value, *allowed)
        private fun text(value: JSONObject, key: String, max: Int = 240) = PocketWebGroupReviews.text(value, key, max)
        private fun integer(value: JSONObject, key: String, min: Long, max: Long) = PocketWebGroupReviews.integer(value, key, min, max)
        private fun bool(value: JSONObject, key: String) = PocketWebGroupReviews.bool(value, key)
        private fun strings(value: JSONArray?): List<String> = if (value == null) emptyList() else (0 until value.length()).map { index ->
            val item = value.get(index); require(item is String && item.isNotBlank()) { "设备范围选项必须是准确字符串" }; item
        }
        private fun peer(snapshot: JSONObject, id: String): JSONObject = snapshot.optJSONArray("peers").objects().singleOrNull { it.opt("deviceId") == id } ?: error("这台设备不在原读取窗口，请刷新")
        private fun samePeer(a: JSONObject, b: JSONObject): Boolean = listOf("deviceId", "certificateFingerprint", "instanceId", "authorizationEpoch", "revoked").all { a.opt(it) == b.opt(it) }
    }
    @Synchronized fun clear() {
        // Keep running-lane gates until their own callbacks settle; an invalidated read cannot
        // race a new review on this same native Window and close its resources.
        generation.incrementAndGet(); review = null; latest = JSONObject()
        desktopGeneration.incrementAndGet(); desktop?.close(); desktop = null
        desktopInFlight.set(null); runCatching { window()?.closeDesktop() }
        runCatching { window()?.stopLanDiscovery() }
        runCatching { window()?.discardReview() }
    }
    @Synchronized private fun discard(original: Host.Window, handle: String = ""): Boolean {
        if (handle.isNotBlank() && review?.handle != handle) return false
        generation.incrementAndGet(); review = null
        original.discardReview(); return true
    }
    private fun captureSnapshot(original: Host.Window, ids: Array<String>): JSONObject {
        val value = original.snapshot(ids)
        val actions = value.optJSONObject("taskActions") ?: JSONObject()
        // The original aggregate omits pending resume requests; retain the full native per-task view.
        ids.take(40).forEach { id ->
            try { actions.put(id, original.taskActions(id)) }
            catch (_: Exception) { actions.put(id, JSONObject().put("capturedAt", System.currentTimeMillis()).put("unavailable", true)) }
        }
        return value.put("taskActions", actions)
    }
    private fun taskIds(): Array<String> = (controller.state.tasks.filter(::isDeviceWorkspaceTask).map { it.id } + workspaceTaskLinks(latest).map { it.optString("parentTaskId") }).filter { it.isNotBlank() }.distinct().toTypedArray()
    fun taskForNavigation(args: JSONObject): String {
        fields(args, "taskId"); check(visible() && controller.webPrivateOwnerVisible())
        return text(args, "taskId").also { require(it in taskIds()) { "请从原设备任务记录打开" } }
    }
    private fun current(original: Host.Window, epoch: Long) {
        check(visible() && controller.webPrivateOwnerVisible() && window() === original && generation.get() == epoch) { "原设备窗口已关闭，请先核对原回执" }
    }
    @Synchronized private fun store(kind: String, native: Any, view: JSONObject, original: Host.Window, epoch: Long): JSONObject {
        try { current(original, epoch) } catch (closed: Exception) {
            // A native read may finish after a background/Back event. Close its hidden resources.
            (native as? AutoCloseable)?.let { runCatching { it.close() } }
            runCatching { original.discardReview() }; throw closed
        }
        val value = Review(UUID.randomUUID().toString(), kind, native, JSONObject(view.toString()), original, epoch, System.currentTimeMillis() + 120_000)
        review = value
        return JSONObject().put("kind", kind).put("viewHandle", value.handle).put("view", view)
    }
    @Synchronized private fun selected(args: JSONObject, original: Host.Window, epoch: Long, consume: Boolean = true): Review {
        current(original, epoch)
        val value = review?.takeIf { it.handle == text(args, "viewHandle") && it.window === original && it.generation == epoch } ?: error("原设备审阅已关闭或使用，请重新核对")
        if (consume) review = null
        check(value.expires > System.currentTimeMillis()) { "原设备审阅已过期" }
        return value
    }
    private fun choice(args: JSONObject, view: JSONObject): Host.GrantChoice {
        fields(args, "viewHandle", "optionId", "toolBudget", "modelBudget", "durationMinutes", "historyDays", "historyReadMethods", "additionalOriginOptionIds", "forwardingOptionIds", "forwardingToolBudget", "forwardingModelBudget", "wholeDisplayCapture")
        val option = view.optJSONArray("grantOptions").objects().singleOrNull { it.opt("optionId") == text(args, "optionId", 128) } ?: error("请明确选择原生列出的权限范围")
        val tools = integer(args, "toolBudget", option.optLong("toolBudgetMin"), option.getLong("toolBudgetMax")).toInt()
        val models = integer(args, "modelBudget", option.optLong("modelBudgetMin"), option.getLong("modelBudgetMax")).toInt()
        val duration = integer(args, "durationMinutes", 1, 43_200)
        val origins = strings(args.getJSONArray("additionalOriginOptionIds")); val forwarding = strings(args.getJSONArray("forwardingOptionIds"))
        require(origins.size <= 8 && origins.toSet().size == origins.size && forwarding.size <= 8 && forwarding.toSet().size == forwarding.size)
        require(origins.all { id -> view.optJSONArray("originOptions").objects().any { it.opt("optionId") == id } }) { "转交来源不属于原生已认证选项" }
        val forwarded = forwarding.map { id -> view.optJSONArray("forwardingOptions").objects().singleOrNull { it.opt("optionId") == id } ?: error("下游范围已变化") }
        require(forwarded.isEmpty() || option.opt("allowDelegation") == true) { "当前范围不允许继续委派" }
        val forwardTools = integer(args, "forwardingToolBudget", 0, minOf(tools.toLong(), forwarded.minOfOrNull { it.getLong("toolBudgetMax") } ?: 0)).toInt()
        val forwardModels = integer(args, "forwardingModelBudget", 0, minOf(models.toLong(), forwarded.minOfOrNull { it.getLong("modelBudgetMax") } ?: 0)).toInt()
        require(forwarded.isEmpty() || forwardTools + forwardModels > 0) { "请选择有限下游次数" }
        val now = System.currentTimeMillis()
        val expires = minOf(now + duration * 60_000, option.getLong("expiresAtMax"), forwarded.minOfOrNull { it.getLong("expiresAtMax") } ?: Long.MAX_VALUE)
        require(expires > now) { "原权限选项已经到期" }
        val days = integer(args, "historyDays", 0, 30); val methods = strings(args.getJSONArray("historyReadMethods"))
        require(methods.toSet().size == methods.size && methods.all { it in strings(option.optJSONArray("historyReadMethods")) } && (days == 0L) == methods.isEmpty()) { "请明确选择原生支持的结果读取期限与方法" }
        val historyExpires = if (days == 0L) 0 else minOf(now + days * 86_400_000, option.getLong("historyReadExpiresAtMax"))
        require(days == 0L || historyExpires > now)
        val whole = bool(args, "wholeDisplayCapture"); require(!whole || option.opt("canGrantWholeDisplayCapture") == true) { "此范围未开放整屏截图" }
        val delegation = origins.isNotEmpty() || forwarding.isNotEmpty()
        return Host.GrantChoice(option.getString("optionId"), tools, models, expires, delegation, if (delegation) 2 else 0,
            historyExpires, methods, origins, forwarding, forwardTools, forwardModels, whole)
    }

    private fun desktopCurrent(original: Host.Window, epoch: Long) {
        check(visible() && controller.webPrivateOwnerVisible() && window() === original && desktopGeneration.get() == epoch) {
            "原桌面窗口已关闭，请重新连接"
        }
    }
    private fun dispatchDesktop(action: String, args: JSONObject, original: Host.Window, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        if (action == "devices.desktop.close") {
            fields(args, "sessionId"); val id = text(args, "sessionId")
            val current = desktop
            if (current?.id() == id) {
                desktopGeneration.incrementAndGet(); desktop = null; desktopInFlight.set(null)
                val closed = original.closeDesktop(id)
                done(JSONObject().put("value", closed), null)
                return
            }
            done(JSONObject().put("value", JSONObject().put("sessionId", id).put("closed", false).put("remoteCloseConfirmed", false)), null)
            return
        }
        val call = UUID.randomUUID().toString()
        check(desktopInFlight.compareAndSet(null, call)) { "请先等待当前桌面操作的回执" }
        val epoch = if (action == "devices.desktop.open") desktopGeneration.incrementAndGet() else desktopGeneration.get()
        if (action == "devices.desktop.open") { desktop?.close(); desktop = null }
        val work = Runnable {
            var value: JSONObject? = null; var failure: String? = null; var opened: Host.DesktopWindow? = null
            try {
                desktopCurrent(original, epoch)
                if (action == "devices.desktop.open") {
                    fields(args, "deviceId", "grantId", "resourceBinding")
                    val deviceId = text(args, "deviceId"); val grantId = text(args, "grantId")
                    val binding = args.getJSONObject("resourceBinding")
                    DeviceProtocol.validateResourceBinding("desktop-v2", binding)
                    val selected = peer(original.snapshot(), deviceId)
                    require(!selected.optBoolean("revoked")) { "此设备已撤销配对" }
                    require(selected.optJSONArray("receivedGrantScopes").objects().any { grant ->
                        grant.optString("grantId") == grantId && grant.optLong("expiresAt") > System.currentTimeMillis() &&
                            strings(grant.optJSONArray("capabilities")).contains("desktop.viewer") &&
                            grant.optJSONArray("resources").objects().any { DeviceProtocol.resourceEquals(it, binding) }
                    }) { "请从电脑当前已授予的桌面范围打开" }
                    val source = original.openDesktop(deviceId, grantId, binding, Host.DesktopCurrent { desktopCurrent(original, epoch) }); opened = source
                    value = source.view()
                    synchronized(this) { desktopCurrent(original, epoch); desktop = source }
                } else {
                    val source = desktop ?: error("原桌面会话已关闭")
                    check(source.id() == text(args, "sessionId")) { "桌面请求属于旧会话" }
                    val input = JSONObject(args.toString()).also { it.remove("sessionId") }
                    val operation = when (action) {
                        "devices.desktop.negotiate" -> { fields(args, "sessionId", "offer"); "negotiate" }
                        "devices.desktop.status" -> { fields(args, "sessionId"); "status" }
                        "devices.desktop.control" -> {
                            fields(args, "sessionId", "action", "expectedRevision")
                            text(args, "action").also { require(it in setOf("take", "release")); input.remove("action") }
                        }
                        "devices.desktop.input" -> { fields(args, "sessionId", "controlLease", "frameRef", "input"); "input" }
                        else -> error("桌面操作尚未接入")
                    }
                    value = source.request(operation, input)
                }
                desktopCurrent(original, epoch)
            } catch (error: Exception) {
                if (action == "devices.desktop.open") opened?.close()
                failure = if (error is org.json.JSONException) "桌面回执不完整，请重新连接核对。" else error.message?.take(400) ?: "桌面操作结果待核对"
            }
            main.post {
                desktopInFlight.compareAndSet(call, null)
                if (runCatching { desktopCurrent(original, epoch) }.isFailure) {
                    opened?.close(); done(null, "原桌面窗口已关闭，未重放输入")
                } else done(JSONObject().put("value", value?.let { PocketWebState.publicValue(it) } ?: JSONObject.NULL), failure)
            }
        }
        try { controller.runDeviceWorkspaceWork(work) }
        catch (failure: Exception) { desktopInFlight.compareAndSet(call, null); done(null, failure.message ?: "桌面工作线程不可用") }
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val original = window() ?: error("原设备窗口已关闭，请重新进入")
        current(original, generation.get())
        if (action.startsWith("devices.desktop.")) { dispatchDesktop(action, args, original, done); return }
        if (action == "devices.discovery.stop") {
            fields(args); discard(original); original.stopLanDiscovery()
            done(JSONObject().put("value", JSONObject().put("candidatesDisabled", true).put("stopRequested", true)).put("snapshot", captureSnapshot(original, taskIds())), null); return
        }
        if (action == "devices.discard") {
            fields(args, "viewHandle")
            val handle = if (args.has("viewHandle")) text(args, "viewHandle") else ""
            if (handle.isBlank()) original.stopLanDiscovery()
            val closed = discard(original, handle)
            done(JSONObject().put("closed", closed), null); return
        }
        require(action in COMMANDS) { "设备操作尚未接通" }
        val controlLane = action == "devices.reviewResumeRequest" || action == "devices.reviewAction" && args.optString("operation").startsWith("task.") ||
            action == "devices.confirm" && (review?.native is TaskControl || review?.kind == "resume")
        val gate = if (controlLane) controlInFlight else inFlight
        val call = UUID.randomUUID().toString()
        check(gate.compareAndSet(null, call)) { "请先等待已有设备操作回执" }
        // Original local task control uses the existing control lane, independent of slow peer reads.
        if (controlLane && action != "devices.confirm") discard(original)
        val epoch = generation.get()
        val ids = taskIds()
        fun taskId(): String = text(args, "taskId").also { require(it in ids) { "请从原设备任务记录选择" } }
        val work = Runnable {
            var snapshot: JSONObject? = null; var value: JSONObject? = null; var error: String? = null
            try {
                current(original, epoch)
                val before by lazy { captureSnapshot(original, ids) }
                value = when (action) {
                    "devices.discovery.start" -> {
                        fields(args, "advertise", "acknowledged"); require(bool(args, "acknowledged")) { "请先确认本次局域网查找及广播范围" }
                        original.startLanDiscovery(bool(args, "advertise")); JSONObject().put("startRequested", true)
                    }
                    "devices.discovery.reviewConnection" -> {
                        fields(args, "deviceId", "candidateId"); val id = text(args, "deviceId"); peer(before, id)
                        val native = original.reviewDiscoveredConnection(id, text(args, "candidateId"))
                        store("connection", native, native.view(), original, epoch)
                    }
                    "devices.snapshot" -> { fields(args, "deviceId"); if (args.has("deviceId")) { val id = text(args, "deviceId"); peer(before, id); original.refreshPeerFacts(id) }; JSONObject().put("readOnly", true) }
                    "devices.createInvitation" -> {
                        fields(args, "address"); val address = text(args, "address", 512)
                        require(address in strings(before.getJSONObject("actions").optJSONArray("localAddresses"))) { "请选择本机当前可用地址或已配置中继" }
                        JSONObject().put("kind", "invitation").put("invitation", original.createInvitation(address)).put("displayOnce", true)
                    }
                    "devices.inspectInvitation" -> { fields(args, "raw"); val native = original.inspectInvitation(text(args, "raw", 16384)); store("pair", native, native.view(), original, epoch) }
                    "devices.reviewPairing", "devices.advancePairing" -> {
                        fields(args, "candidateId"); val id = text(args, "candidateId")
                        require(before.optJSONArray("pendingPairings").objects().any { it.optString("candidateId", it.optString("inviteId")) == id }) { "原配对候选已变化" }
                        if (action == "devices.advancePairing") { original.advancePairing(id); JSONObject().put("queriedOriginalPairing", true) }
                        else { val native = original.reviewCandidate(id); store("pair", native, native.view(), original, epoch) }
                    }
                    "devices.reviewPeerGrant" -> { fields(args, "deviceId"); val id = text(args, "deviceId"); peer(before, id); val native = original.reviewPeerGrant(id); store("grant", native, native.view(), original, epoch) }
                    "devices.reviewGrant" -> {
                        val old = selected(args, original, epoch, consume = false); val selection = choice(args, old.view)
                        require(old.kind in setOf("pair", "grant")) { "这个原审阅不接受权限选择" }
                        selected(args, original, epoch)
                        when (old.kind) {
                            "pair" -> { val native = original.reviewPairingGrant(old.native as Host.PairReview, selection); store("pair", native, native.view(), original, epoch) }
                            "grant" -> { val native = original.reviewGrantRevision(old.native as Host.GrantReview, selection); store("grant", native, native.view(), original, epoch) }
                            else -> error("这个原审阅不接受权限选择")
                        }
                    }
                    "devices.reviewConnection" -> {
                        fields(args, "deviceId", "raw", "serviceBase"); val id = text(args, "deviceId"); peer(before, id)
                        require(args.has("raw") != args.has("serviceBase"))
                        val native = if (args.has("raw")) original.reviewConnectionUpdate(id, text(args, "raw", 16384)) else {
                            val service = text(args, "serviceBase", 512)
                            require(before.getJSONObject("actions").optJSONArray("relayLeases").objects().any { it.opt("serviceBase") == service }) { "请选择已配置的原中继" }
                            original.reviewRelayConnection(id, service)
                        }
                        store("connection", native, native.view(), original, epoch)
                    }
                    "devices.reviewRelay" -> { fields(args, "raw"); val native = original.reviewRelayLease(text(args, "raw", 4096)); store("relay", native, native.view(), original, epoch) }
                    "devices.reviewGoal" -> { fields(args, "taskId"); val native = original.reviewGoal(taskId()); store("goal", native, native.view(), original, epoch) }
                    "devices.reviewResult" -> { fields(args, "taskId"); val native = original.reviewResult(taskId()); store("result", native, native.view(), original, epoch) }
                    "devices.reviewApproval" -> { fields(args, "taskId", "approvalId"); val native = original.reviewApproval(taskId(), text(args, "approvalId")); store("approval", native, native.view(), original, epoch) }
                    "devices.readTask" -> {
                        fields(args, "taskId"); val id = taskId()
                        JSONObject().put("kind", "taskDetails").put("taskId", id).put("actions", original.taskActions(id)).put("history", original.historyLinks(id))
                    }
                    "devices.reviewResumeRequest" -> {
                        fields(args, "taskId", "resumeRequestId", "reviewRevision"); val id = taskId(); val actions = original.taskActions(id)
                        val request = actions.optJSONObject("resumeRequest") ?: error("原任务没有待核对的恢复请求")
                        require(request.opt("ownerActionRequired") == true && request.opt("taskId") == id && request.opt("resumeRequestId") == text(args, "resumeRequestId") &&
                            request.optLong("reviewRevision", -1) == integer(args, "reviewRevision", 1, Long.MAX_VALUE)) { "原恢复请求或审阅版本已变化" }
                        val view = JSONObject(request.toString()).put("canApprove", request.opt("stale") == false && actions.getJSONObject("resume").opt("available") == true)
                        original.discardReview(); store("resume", view, view, original, epoch)
                    }
                    "devices.openHistory" -> {
                        fields(args, "taskId", "operationId"); val id = taskId(); val operation = text(args, "operationId")
                        require(original.historyLinks(id).optJSONArray("links").objects().any { it.opt("parentTaskId") == id && it.opt("operationId") == operation }) { "原操作不在这项任务的关联记录内" }
                        val native = original.openHistory(id, operation); store("history", History(native), native.view(), original, epoch)
                    }
                    "devices.queryHistory", "devices.stopHistory" -> {
                        fields(args, "viewHandle", "acknowledged")
                        val old = selected(args, original, epoch, consume = false); require(old.kind == "history") { "请先打开原操作回执" }
                        val history = old.native as History
                        val result = if (action == "devices.stopHistory") {
                            require(bool(args, "acknowledged")) { "请明确核对这次原操作与设备范围" }
                            check(history.stopSubmitted.compareAndSet(false, true)) { "本窗口已提交原操作停止，请只查询回执" }
                            current(original, epoch); history.original.requestStop()
                        } else history.original.query()
                        JSONObject().put("kind", "historyReceipt").put("viewHandle", old.handle).put("taskId", old.view.getString("taskId"))
                            .put("operationId", old.view.getString("operationId")).put("targetDeviceId", old.view.getString("targetDeviceId"))
                            .put("stopSubmitted", history.stopSubmitted.get()).put("receipt", result)
                    }
                    "devices.reviewAction" -> {
                        fields(args, "operation", "deviceId", "taskId", "serviceBase", "inviterDeviceId")
                        val operation = text(args, "operation", 40)
                        val view = JSONObject().put("operation", operation)
                        var native: Any = view
                        when (operation) {
                            "receiver.enable", "receiver.disable" -> { original.receiverAvailability(operation == "receiver.enable").also { require(it.available) { it.reason } }; view.put("local", before.getJSONObject("local")) }
                            "peer.connect", "peer.disconnect", "peer.revoke" -> { val target = peer(before, text(args, "deviceId")); val available = target.getJSONObject("actions").getJSONObject(operation.substringAfter('.')); require(available.opt("available") == true) { available.optString("reason") }; view.put("peer", target) }
                            "task.stop", "task.pause", "task.resume", "task.cancelPending" -> {
                                val id = taskId(); val actions = original.taskActions(id)
                                val availability = actions.getJSONObject(if (operation == "task.cancelPending") "pendingCancel" else operation.substringAfter('.'))
                                require(availability.opt("available") == true) { availability.optString("reason") }
                                if (operation == "task.resume") require(actions.optJSONObject("resumeRequest")?.opt("ownerActionRequired") != true) { "请先核对对方这次原执行的恢复请求" }
                                if (operation == "task.cancelPending" || operation == "task.stop" && actions.getJSONObject("pendingCancel").opt("available") == true) {
                                    val pending = controller.reviewPendingDeviceGoalCancellation(id)
                                    val actual = pending.view(); require(actual.opt("taskId") == id)
                                    native = TaskControl(id, "", 0, pending); view.put("pendingGoal", actual).put("taskId", id)
                                } else {
                                    val actual = controller.readOriginalDeviceTask(id)
                                    require(actual.opt("id") == id && actual.optJSONObject("origin")?.opt("kind") == "paired_device") { "不是这项原设备任务" }
                                    val run = text(actual, "runId"); val runEpoch = integer(actual, "coordinatorEpoch", 1, Int.MAX_VALUE.toLong()).toInt()
                                    native = TaskControl(id, run, runEpoch)
                                    view.put("taskId", id).put("runId", run).put("executorEpoch", runEpoch).put("goal", actual.optString("goal"))
                                        .put("origin", actual.optJSONObject("origin")).put("resourceBinding", actual.optJSONObject("deviceExecutionScope")?.optJSONObject("resourceBinding"))
                                }
                            }
                            "relay.remove" -> { val service = text(args, "serviceBase", 512); val inviter = text(args, "inviterDeviceId"); val lease = before.getJSONObject("actions").optJSONArray("relayLeases").objects().singleOrNull { it.opt("serviceBase") == service && it.opt("inviterDeviceId") == inviter } ?: error("原临时中继租约已变化"); view.put("lease", lease) }
                            else -> error("设备操作未接通")
                        }
                        original.discardReview(); store("action", native, view, original, epoch)
                    }
                    "devices.confirm", "devices.reject" -> {
                        fields(args, "viewHandle", "acknowledged", "useForFutureGoals", "completed", "approved")
                        if (action == "devices.confirm") require(bool(args, "acknowledged")) { "请明确核对本次设备范围与影响" }
                        val old = selected(args, original, epoch)
                        var nativeReceipt: JSONObject? = null
                        if (action == "devices.reject") { require(old.kind == "pair"); original.rejectPairing(old.native as Host.PairReview) }
                        else {
                            require(bool(args, "acknowledged")) { "请明确核对本次设备范围与影响" }
                            when (old.kind) {
                                "pair" -> original.confirmPairing(old.native as Host.PairReview)
                                "grant" -> original.confirmGrantRevision(old.native as Host.GrantReview)
                                "connection" -> original.confirmConnectionUpdate(old.native as Host.ConnectionReview)
                                "relay" -> original.confirmRelayLease(old.native as Host.RelayLeaseReview)
                                "goal" -> original.confirmGoal(old.native as Host.GoalReview, bool(args, "useForFutureGoals"))
                                "result" -> original.confirmResult(old.native as Host.ResultReview, bool(args, "completed"))
                                "approval" -> original.decideApproval(old.native as Host.ApprovalReview, bool(args, "approved"))
                                "abandon" -> original.confirmPairingAbandonment(old.native as Host.AbandonReview)
                                "resume" -> {
                                    val approved = bool(args, "approved"); val request = old.view
                                    require(!approved || request.opt("canApprove") == true) { "原恢复请求已经过期或原执行暂不可继续" }
                                    current(original, epoch)
                                    original.decideResumeRequest(request.getString("taskId"), request.getString("resumeRequestId"), request.getLong("reviewRevision"), approved)
                                }
                                "action" -> {
                                    val view = old.view; val operation = view.getString("operation")
                                    view.optJSONObject("peer")?.let { check(samePeer(it, peer(before, it.getString("deviceId")))) { "原设备身份或授权已变化，请重新核对" } }
                                    when (operation) {
                                        "receiver.enable", "receiver.disable" -> { check(view.getJSONObject("local").opt("receiverEnabled") == before.getJSONObject("local").opt("receiverEnabled")) { "设备接收状态已变化" }; original.setReceiverEnabled(operation == "receiver.enable") }
                                        "peer.connect" -> original.connectPeer(view.getJSONObject("peer").getString("deviceId"))
                                        "peer.disconnect" -> original.disconnectPeer(view.getJSONObject("peer").getString("deviceId"))
                                        "peer.revoke" -> original.revoke(view.getJSONObject("peer").getString("deviceId"))
                                        "task.stop", "task.pause", "task.resume", "task.cancelPending" -> {
                                            val control = old.native as TaskControl
                                            current(original, epoch)
                                            nativeReceipt = if (control.pending != null) controller.confirmPendingDeviceGoalCancellation(control.pending)
                                                else controller.controlOriginalDeviceTask(control.taskId, control.runId, control.epoch, if (operation == "task.stop") "cancel" else operation.substringAfter('.'))
                                            controller.deviceTaskWorkspaceChanged(control.taskId)
                                        }
                                        "relay.remove" -> {
                                            val lease = view.getJSONObject("lease")
                                            val actual = before.getJSONObject("actions").optJSONArray("relayLeases").objects().singleOrNull { it.opt("serviceBase") == lease.opt("serviceBase") && it.opt("inviterDeviceId") == lease.opt("inviterDeviceId") }
                                            require(actual != null && DeviceProtocol.canonical(actual) == DeviceProtocol.canonical(lease)) { "原中继租约已变化，请重新核对" }
                                            current(original, epoch); original.removeRelayLease(lease.getString("serviceBase"), lease.getString("inviterDeviceId"))
                                        }
                                    }
                                }
                                else -> error("原审阅不接受确认")
                            }
                        }
                        JSONObject().put("kind", old.kind).put("operation", old.view.optString("operation", action)).put("nativeCallReturned", true).put("receipt", nativeReceipt ?: JSONObject.NULL)
                    }
                    "devices.recoveryAddresses" -> { fields(args); JSONObject().put("addresses", JSONArray(original.recoveryAddresses())) }
                    "devices.openRecovery" -> {
                        fields(args, "inviteId", "address"); val id = text(args, "inviteId"); val address = text(args, "address", 512)
                        require(before.optJSONArray("committedPairingsToRecover").objects().any { it.opt("inviteId") == id }) { "请从原已提交配对记录选择" }
                        require(address in original.recoveryAddresses()); val native = original.openCommittedRecovery(id, address); store("recovery", native, native.view(), original, epoch)
                    }
                    "devices.recoverPairing" -> {
                        fields(args, "inviteId", "host", "port"); val id = text(args, "inviteId")
                        require(before.optJSONArray("pairingOffersToRecover").objects().any { it.opt("inviteId") == id }) { "请从原本机待配对记录选择" }
                        original.recoverPairing(id, text(args, "host", 512), integer(args, "port", 0, 65535).toInt()); JSONObject().put("queriedOriginalPairing", true)
                    }
                    "devices.reviewAbandonment" -> { fields(args, "inviteId"); val id = text(args, "inviteId"); require(before.optJSONArray("pairingOffersToRecover").objects().any { it.opt("inviteId") == id }); val native = original.reviewPairingAbandonment(id); store("abandon", native, native.view(), original, epoch) }
                    else -> error("设备操作尚未接通")
                }
                current(original, epoch)
            } catch (failure: Exception) { if (action == "devices.discovery.start" && runCatching { current(original, epoch) }.isFailure) original.stopLanDiscovery(); error = if (failure is org.json.JSONException) "原设备信息不完整，请刷新核对。" else failure.message?.take(400) ?: "设备操作结果待核对" }
            try { current(original, epoch); snapshot = captureSnapshot(original, ids) } catch (_: Exception) { }
            val result = JSONObject().put("value", value ?: JSONObject.NULL).put("snapshot", snapshot ?: JSONObject.NULL)
            main.post {
                gate.compareAndSet(call, null)
                if (runCatching { current(original, epoch) }.isFailure) done(null, "原设备窗口已变化，请读取原回执，不要重复提交")
                else {
                    snapshot?.let { latest = JSONObject(it.toString()) }
                    done(PocketWebState.publicValue(result) as JSONObject, error)
                }
            }
        }
        try {
            if (controlLane) controller.runDeviceWorkspaceControlWork(work) else controller.runDeviceWorkspaceWork(work)
        } catch (failure: Exception) { gate.compareAndSet(call, null); done(null, failure.message ?: "原设备工作线程不可用") }
    }
}
