package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.remote.RemoteInputDelivery
import dev.pocketengine.features.remote.RemoteHarnessSelection
import dev.pocketengine.features.remote.RemoteColdRecovery
import dev.pocketengine.features.remote.RemoteJobEvents
import dev.pocketengine.features.remote.RemoteTaskDrafts
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.remoteReadShouldPoll
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Presentation of the existing original-task owner window; it grants no new remote authority. */
internal class PocketWebRemoteTasks(
    private val controller: PocketWorldController,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val taskView: () -> String?,
    private val changed: () -> Unit
) {
    val inputPages = PocketWebRemoteInputs(controller, captureWindow, acceptsWindow, taskView)
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var nativeWindow: String? = null
    private var originalTask: String? = null
    private var snapshot: JSONObject? = null
    private var configuration: JSONObject? = null
    private var busy = false
    private var listing: String? = null
    private var listedRows = emptyMap<String, String>()
    private var listingGeneration = 0L
    private var preview: PocketWebRemotePreview? = null

    companion object {
        private val fields = mapOf(
            "refresh" to emptySet(), "inspectRecovery" to emptySet(),
            "saveDraft" to setOf("draftId", "draftRevision", "mode", "text", "queued"),
            "withdrawDraft" to setOf("draftId", "draftRevision"), "sendDraft" to setOf("draftId", "draftRevision"),
            "clipboard" to setOf("draftId", "draftRevision", "mode"), "setAttention" to setOf("enabled"),
            "approve" to setOf("requestId", "decision"), "answerQuestion" to setOf("requestId", "requestHash", "answers"),
            "readArtifact" to setOf("path"), "listResultFiles" to setOf("path", "offset"),
            "selectResultFile" to setOf("path"), "captureSelectedSnapshot" to emptySet(),
            "captureSnapshot" to setOf("paths"), "readSnapshot" to setOf("snapshotId", "path"),
            "saveSnapshot" to setOf("snapshotId", "path"), "openSavedSnapshot" to setOf("attachmentId"),
            "reviewConversationResult" to setOf("snapshotId", "path", "inputAttachmentId"),
            "reviewConversationAttachment" to setOf("snapshotId", "path", "inputAttachmentId"),
            "confirmConversationResult" to setOf("digest"),
            "sendConversationResult" to setOf("id"), "cancelConversationResult" to setOf("id"),
            "continue" to setOf("prompt", "modelSelection", "snapshotId", "snapshotVersion", "timeoutSeconds", "recoveryOwnerAcknowledged"),
            "project.list" to setOf("offset", "operationId"), "project.diff" to setOf("path"),
            "project.reviewRestore" to setOf("snapshotId", "snapshotVersion", "path"),
            "project.reviewFork" to setOf("prompt", "timeoutSeconds"), "project.confirm" to setOf("digest", "scopeAcknowledged"),
            "project.queryRestore" to setOf("operationId"), "project.openTask" to setOf("taskId")
        ) + mapOf(
            "gateway.inspect" to emptySet(), "gateway.review" to setOf("optionRef"), "gateway.enable" to setOf("acceptedDigest"),
            "gateway.query" to setOf("leaseId"), "gateway.revoke" to setOf("leaseId"), "gateway.configure" to setOf("leaseId"),
            "gateway.reviewParentRun" to emptySet(), "gateway.profileChoices" to emptySet(),
            "gateway.reviewParentProfile" to setOf("profileId", "profileRevision"), "gateway.saveProfileTarget" to setOf("profileId", "profileRevision"),
            "gateway.resumeParentRun" to setOf("acceptedDigest"), "gateway.parentStatus" to emptySet(), "gateway.stopParentRun" to emptySet(),
            "gateway.reviewRun" to setOf("leaseId"), "gateway.run" to setOf("acceptedDigest"),
            "gateway.prepareContinuation" to setOf("prompt", "snapshotId", "snapshotVersion"), "gateway.cancelPrepared" to setOf("operationId"),
            "openHarnessConfig" to setOf("operationId", "restoreOperationId", "selectionFromTask"),
            "previewHarnessConfig" to emptySet(), "queryHarnessConfig" to emptySet(), "cancelHarnessConfigPreview" to emptySet(),
            "confirmHarnessConfig" to setOf("acceptedPlanDigest")
        )
        private val optional = mapOf("project.list" to setOf("operationId"), "continue" to setOf("modelSelection", "snapshotId", "snapshotVersion", "timeoutSeconds", "recoveryOwnerAcknowledged"),
            "gateway.prepareContinuation" to setOf("snapshotId", "snapshotVersion"), "openHarnessConfig" to setOf("operationId", "restoreOperationId", "selectionFromTask"))
        private fun independentReview(command: String) = command.startsWith("gateway.") && command != "gateway.prepareContinuation" || command.endsWith("HarnessConfig") || command == "cancelHarnessConfigPreview"
        val COMMANDS = fields.keys.map { "remoteTask.$it" }.toSet() +
            setOf("remoteTask.open", "remoteTask.close", "remoteSessions.read", "remoteSessions.open") + PocketWebRemotePreview.COMMANDS + PocketWebRemoteInputs.COMMANDS

        /** All lineage and delivery decisions use the same native rules as execution. */
        internal fun presentSnapshot(original: JSONObject): JSONObject {
            val result = JSONObject(original.toString())
            val source = original.optJSONObject("source") ?: JSONObject()
            val delivery = RemoteInputDelivery.describe(source)
            result.put("inputDelivery", delivery)
            result.put("draftEditorAvailable", !RemoteHarnessSelection.isGenericWorker(source.optString("worker")) || delivery.getJSONObject("nextTurn").optBoolean("observedAvailable"))
            result.put("coldRecoveryConfirmed", RemoteColdRecovery.source(source))
            result.put("readPollAllowed", remoteReadShouldPoll(original))
            result.put("readPollDelayMillis", if (original.optString("eventReadStatus") == "confirmed" && original.optJSONObject("eventRead")?.optBoolean("hasMore") == true) 500 else 4000)
            val scopes = JSONObject()
            for (mode in listOf("next_turn", "steer")) runCatching {
                RemoteTaskDrafts.scope(source, original.optString("profileBindingHash"), mode)
            }.getOrNull()?.let { scopes.put(mode, it) }
            result.put("draftScopes", scopes)
            result.put("inputDrafts", JSONArray().also { rows -> original.optJSONArray("inputDrafts").objects().forEach { draft ->
                val scope = scopes.optJSONObject(draft.optString("mode"))
                rows.put(JSONObject(draft.toString()).put("targetCurrent", scope != null &&
                    RemoteTaskDrafts.same(scope, draft.optJSONObject("scope"))))
            } })
            val committed = original.optJSONObject("eventRead")
            val progress = committed ?: original.optJSONObject("result")?.optJSONObject("progress")
            val events = JSONArray(); var rejected = 0
            val eventSource = committed?.optJSONObject("scope") ?: source
            progress?.optJSONArray("events").objects().forEach { event ->
                runCatching { RemoteJobEvents.sourceView(event, eventSource) }.fold(
                    { lineage -> events.put(JSONObject().put("event", event).put("source", lineage)) },
                    { rejected++ }
                )
            }
            // Unconfirmed child data is never made a parent event by the renderer.
            result.remove("eventRead")
            result.optJSONObject("result")?.remove("progress")
            if (progress != null) result.put("progress", JSONObject(progress.toString())
                .removeAndReturn("scope").put("events", events).put("unconfirmedSources", rejected)
                .put("stored", committed != null).put("readStatus", original.optString("eventReadStatus")))
            return PocketWebState.publicValue(result) as JSONObject
        }
        private fun JSONObject.removeAndReturn(name: String): JSONObject { remove(name); return this }
    }

    private fun closeTask() {
        preview?.clear()
        generation++; snapshot = null; configuration = null; busy = false
        val previous = nativeWindow; nativeWindow = null; originalTask = null
        previous?.let(controller::leaveRemoteTask)
    }
    fun clear() { inputPages.clear(); closeTask(); listingGeneration++; listing = null; listedRows = emptyMap() }
    fun attachPreview(container: android.widget.FrameLayout) {
        check(preview == null)
        preview = PocketWebRemotePreview(controller, container, captureWindow, acceptsWindow, taskView, changed)
    }
    fun foreground(value: Boolean) { preview?.foreground(value); if (!value) clear() }
    fun close() { clear(); preview?.close(); preview = null }
    private fun fields(args: JSONObject, names: Set<String>) {
        require(args.keys().asSequence().toSet() == names) { "原电脑任务参数不完整，请重新核对" }
    }
    private fun string(args: JSONObject, name: String, max: Int = 160): String {
        val value = args.opt(name)
        require(value is String && value.isNotBlank() && value.length <= max && '\u0000' !in value) { "原$name 不可核对" }
        return value
    }
    private fun foreground() = controller.webPrivateOwnerVisible()
    private fun taskCurrent(id: String) = foreground() && taskView() == id
    private fun presentation(current: JSONObject, id: String): JSONObject = presentSnapshot(current).put("windowHandle", id).also { shown ->
        configuration?.let { value -> shown.put("configuration", PocketWebState.publicValue(value)).put("configurationOnly", true).put("fresh", false).put("canContinue", false) }
    }
    private fun rows(value: JSONObject): Map<String, String> = buildMap {
        value.optJSONArray("managedConnections").objects().forEach { connection ->
            connection.optJSONArray("contexts").objects().forEach { context ->
                context.optJSONArray("sessions").objects().forEach { row ->
                    val id = row.optString("taskId"); val identity = row.optString("rowIdentity")
                    if (id.isNotBlank() && identity.isNotBlank()) put(id, identity)
                }
            }
        }
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action in PocketWebRemoteInputs.COMMANDS) { inputPages.dispatch(action, args, done); return }
        if (action in PocketWebRemotePreview.COMMANDS) {
            (preview ?: error("原隔离预览宿主尚未接入")).dispatch(action, args, done); return
        }
        if (action == "remoteTask.close") {
            fields(args, setOf("windowHandle"))
            val handle = string(args, "windowHandle")
            if (handle == nativeWindow) closeTask()
            done(JSONObject().put("closed", true), null); changed(); return
        }
        require(foreground()) { "请回到原私人工作区" }
        if (action == "remoteSessions.read") {
            fields(args, emptySet()); val window = captureWindow(); val read = ++listingGeneration
            listing = null; listedRows = emptyMap()
            controller.runEcosystemControlWork(Runnable {
                val result = runCatching { check(acceptsWindow(window) && foreground()); controller.remoteManagedSessions() }
                main.post {
                    if (read != listingGeneration || !acceptsWindow(window) || !foreground()) done(null, "原会话清单窗口已变化，请重新读取")
                    else result.fold({ value ->
                        val handle = UUID.randomUUID().toString(); listing = handle; listedRows = rows(value)
                        done((PocketWebState.publicValue(value) as JSONObject).put("listingHandle", handle), null)
                    }, { done(null, it.message ?: "原会话清单暂不可读取") })
                }
            }); return
        }
        if (action == "remoteSessions.open") {
            fields(args, setOf("listingHandle", "taskId", "rowIdentity"))
            val task = string(args, "taskId"); val identity = string(args, "rowIdentity")
            require(listing == string(args, "listingHandle") && listedRows[task] == identity) { "原任务清单已变化，请重新读取" }
            val id = controller.remoteManagedSessionTask(task, identity)
            require(id == task) { "不是所选原任务" }
            controller.focusTask(id)
            done(JSONObject().put("taskId", id).put("navigationRequested", true), null); changed(); return
        }
        if (action == "remoteTask.open") {
            fields(args, setOf("taskId")); val task = string(args, "taskId")
            require(taskCurrent(task)) { "请先打开这个原电脑任务" }
            closeTask(); val epoch = generation; val window = captureWindow(); originalTask = task; busy = true
            try { nativeWindow = controller.enterRemoteTask(task) { result, error ->
                val original = result?.optJSONObject("snapshot")
                if (epoch != generation || !acceptsWindow(window) || !taskCurrent(task)) {
                    original?.optString("windowId")?.takeIf(String::isNotBlank)?.let(controller::leaveRemoteTask)
                    done(null, "原电脑任务窗口已变化，请重新核对")
                } else {
                    busy = false
                    if (original == null || error != null) done(null, error ?: "原电脑任务没有返回审阅")
                    else if (original.optString("taskId") != task || original.optString("windowId").isBlank()) done(null, "原电脑任务回执身份不符")
                    else {
                        snapshot = JSONObject(original.toString())
                        done(JSONObject().put("snapshot", presentation(original, original.getString("windowId"))), null)
                    }
                    changed()
                }
            } } catch (failure: Exception) { closeTask(); throw failure }
            return
        }
        val command = action.removePrefix("remoteTask.")
        val expected = fields[command] ?: error("未知原电脑任务操作")
        val actualFields = args.keys().asSequence().toSet()
        require(actualFields.all { it in expected + setOf("windowHandle", "reviewId") } &&
            actualFields.containsAll(expected - optional[command].orEmpty() + setOf("windowHandle", "reviewId"))) { "原电脑任务参数不完整或含额外范围" }
        val current = snapshot ?: error("请先读取原电脑任务")
        val id = nativeWindow ?: error("原电脑任务窗口已关闭")
        val task = originalTask ?: error("没有原任务身份")
        require(taskCurrent(task) && !busy && string(args, "windowHandle") == id &&
            string(args, "reviewId") == current.optString("reviewId")) { "原任务或审阅已变化，请重新读取" }
        val payload = JSONObject().put("windowId", id).put("taskId", task).put("reviewId", current.getString("reviewId"))
        expected.filter(args::has).forEach { payload.put(it, args.get(it)) }
        if (independentReview(command)) payload.remove("reviewId")
        require(configuration == null || independentReview(command) || command == "refresh") { "请返回并重新打开原任务后再执行；当前为准确配置审阅窗口" }
        if (command == "openHarnessConfig") {
            require(configuration == null && expected.count(args::has) == 1) { "请选择一个准确原配置来源" }
            when {
                args.has("selectionFromTask") -> {
                    require(args.opt("selectionFromTask") == true)
                    val selection = current.optJSONObject("task")?.optJSONObject("harnessConfigSelection") ?: error("原任务没有冻结配置选择")
                    payload.remove("selectionFromTask"); payload.put("selection", JSONObject(selection.toString()))
                }
                args.has("restoreOperationId") -> {
                    val operation = current.optJSONObject("task")?.optJSONArray("operations").objects().singleOrNull { it.optString("tool") == "harness_config" && it.optString("operationId") == string(args, "restoreOperationId") }
                        ?: error("原配置恢复记录不可核对")
                    require(operation.optJSONObject("receipt")?.optString("state") in setOf("COMMITTED", "RECOVERY_REQUIRED"))
                    val binding = operation.getJSONObject("arguments").getJSONObject("request").getString("bindingId")
                    payload.remove("restoreOperationId"); payload.put("selection", JSONObject().put("bindingId", binding).put("restoreOperationId", operation.getString("operationId")))
                }
                else -> require(current.optJSONObject("task")?.optJSONArray("operations").objects().any { it.optString("tool") == "harness_config" && it.optString("operationId") == string(args, "operationId") }) { "不是本任务的原配置操作" }
            }
        }
        if (command == "openSavedSnapshot") {
            val file = current.optJSONArray("snapshotAttachments").objects().singleOrNull { it.optJSONObject("attachment")?.optString("id") == string(args, "attachmentId") }
                ?.optJSONObject("attachment") ?: error("请重新读取这个原快照附件")
            controller.openAttachmentOutcome(file)
            done(JSONObject().put("snapshot", presentSnapshot(current).put("windowHandle", id)).put("viewerRequested", true), null); return
        }
        if (command == "project.openTask") {
            val operation = current.optJSONObject("projectHistoryOperation") ?: error("尚无原分支任务回执")
            val original = string(args, "taskId")
            require(original == operation.optString("taskId") && operation.optJSONObject("arguments")?.optJSONObject("scope")?.optString("action") == "FORK") { "不是这次真实分支原任务" }
            controller.focusTask(original)
            done(JSONObject().put("snapshot", presentSnapshot(current).put("windowHandle", id)).put("navigationRequested", true), null); changed(); return
        }
        if (command == "sendConversationResult" || command == "cancelConversationResult") {
            val deliveryId = string(args, "id")
            require(current.optJSONArray("conversationResultDeliveries").objects().any { it.optString("id") == deliveryId }) { "不是原任务核对过的成果草稿" }
            require(controller.state.feature("channels").optJSONArray("outbox").objects().any { it.optString("id") == deliveryId && it.optString("status") == "queued" }) { "原成果投递已变化，请核对回执，不要重发" }
            val epoch = generation; val window = captureWindow(); busy = true
            fun stillCurrent() = epoch == generation && acceptsWindow(window) && taskCurrent(task) && nativeWindow == id
            try { controller.requestWebFeatureReceipt("channels", if (command == "sendConversationResult") "outbox.send" else "outbox.cancel", JSONObject().put("id", deliveryId), ::stillCurrent) { result, error ->
                if (!stillCurrent()) done(null, "原成果窗口已变化，请核对原投递回执，不重发")
                else {
                    busy = false
                    val updated = JSONObject(current.toString())
                    val outbox = controller.state.feature("channels").optJSONArray("outbox").objects()
                    updated.put("conversationResultDeliveries", JSONArray().also { deliveries -> current.optJSONArray("conversationResultDeliveries").objects().forEach { old ->
                        deliveries.put(outbox.firstOrNull { it.optString("id") == old.optString("id") } ?: old)
                    } })
                    snapshot = updated
                    done(if (error == null && result != null) JSONObject().put("snapshot", presentSnapshot(updated).put("windowHandle", id)).put("operation", PocketWebState.publicValue(result)) else null, error ?: if (result == null) "原投递回执未确认" else null)
                    changed()
                }
            } } catch (failure: Exception) { busy = false; throw failure }
            return
        }
        if (command in setOf("saveDraft", "sendDraft", "withdrawDraft", "clipboard")) {
            require(string(args, "draftId").matches(Regex("[a-f0-9-]{36}"))) { "原草稿身份无效" }
            val revision = args.opt("draftRevision")
            require((revision is Int || revision is Long) && (revision as Number).toLong() >= 0) { "原草稿版本无效" }
            if (args.has("mode")) require(args.optString("mode") in setOf("next_turn", "steer"))
        }
        if (command == "saveDraft") {
            require(args.opt("queued") is Boolean)
            RemoteTaskDrafts.text(string(args, "text", 12000))
        }
        if (command == "setAttention") require(args.opt("enabled") is Boolean)
        if (command == "approve") require(args.optString("decision") in setOf("accept", "decline", "cancel"))
        if (command == "answerQuestion") require(args.opt("answers") is JSONObject)
        if (command == "clipboard") {
            require(current.optBoolean("fresh")) { "请先查询原任务后再读取剪贴板" }
            val focused = controller.webPresentationActivity().currentFocus ?: error("请聚焦当前工作区再读取剪贴板")
            val text = controller.readRemoteDraftClipboard(current, args.getString("draftId"), args.getLong("draftRevision"), args.getString("mode"), focused)
            done(JSONObject().put("text", text).put("readOnce", true), null); return
        }
        val epoch = generation; val window = captureWindow(); busy = true
        try {
            controller.requestOwnerDomainReceipt("remoteTask", command, payload) { result, error ->
                if (epoch != generation || !acceptsWindow(window) || !taskCurrent(task) || nativeWindow != id) {
                    done(null, "原电脑任务窗口已变化，请查询原回执，不要重复提交")
                } else {
                    busy = false
                    val original = result?.optJSONObject("snapshot")
                    if (original == null || error != null) done(null, error ?: "原操作尚无可核对回执，请查询原任务")
                    else if (original.optString("windowId") != id || original.optString("taskId") != task) done(null, "原操作回执身份不符，请重新核对")
                    else {
                        if (original.optBoolean("configurationOnly")) {
                            configuration = JSONObject(original.toString())
                            original.optJSONObject("harnessGateway")?.let { current.put("harnessGateway", it) }
                            original.optJSONObject("gatewayContinuation")?.let { current.put("gatewayContinuation", it) }
                            snapshot = current
                        } else snapshot = JSONObject(original.toString())
                        val value = JSONObject().put("snapshot", presentation(snapshot!!, id))
                        result.optJSONObject("operation")?.let { value.put("operation", PocketWebState.publicValue(it)) }
                        done(value, null)
                    }
                    changed()
                }
            }
        } catch (failure: Exception) { busy = false; throw failure }
    }
}
