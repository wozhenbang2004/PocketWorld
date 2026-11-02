package dev.pocketengine.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost as Host
import dev.pocketengine.features.devices.NativeDeviceTaskScopeHost
import java.io.File
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

class DeviceWorkspaceFixtureActivity : ComponentActivity()

/** EXPLICIT SYNTHETIC NATIVE-PRODUCER FIXTURE. Actual production MyDevicesPanel renders and
 * handles Android semantics/back/lifecycle. No TLS/device/model/account/key/installation is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w393dp-h852dp-mdpi", shadows = [OfficialAarResources::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class DeviceWorkspaceSourceUiTest {
    private val output get() = File(requireNotNull(System.getProperty("c2.captureDirectory")))
    private val localId = "pw1_" + "a".repeat(64)
    private val peerId = "pw1_" + "b".repeat(64)
    private fun obj(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().also { json -> pairs.forEach { json.put(it.first, it.second) } }
    private inner class Fixture {
        var read = 0; var receiverMutation = 0; var inviteMutation = 0; var pairingConfirmed = 0; var goalsConfirmed = 0; var stops = 0
        var revoked = false; var future = false; var receiver = false; var connectionUpdates = 0
        var offerAbandoned = false; var grantsChanged = 0; var chosenGrantRevision: Host.GrantChoice? = null
        var showRecovery = false; var recoveryOpened = 0; var recoveryClosed = 0; var recoveryRead = 0
        var resultsConfirmed = 0; var pendingCancelled = 0; var historyQueries = 0; var historyClosed = 0
        var historyStops = 0
        var chosenHistory: List<String> = emptyList()
        var pairState = "pending_owner_confirmation"; var readFailure = false
        val now get() = System.currentTimeMillis()
        val peer get() = obj("deviceId" to peerId, "label" to "示例 B 手机 · 家庭资料整理", "platform" to "android", "certificateFingerprint" to "b".repeat(64), "instanceId" to "fixture-b-instance", "authorizationEpoch" to 2, "revoked" to revoked,
            "connection" to obj("state" to "OFFLINE", "observedAt" to now, "unconfirmedTransportWrite" to true),
            "revocation" to obj("locallyRevoked" to revoked, "peerAcknowledged" to false),
            "capabilitySnapshot" to obj("capturedAt" to now - 10_000, "validUntil" to now - 5_000, "capabilities" to JSONArray().put(obj("id" to "phone.tools.main", "implemented" to true, "installed" to true, "authorized" to true, "ready" to true, "reason" to "fixture_expired_not_current_readiness"))),
            "grantScopes" to JSONArray().put(obj("grantId" to "fixture-grant", "state" to if (revoked) "revoked" else "active", "toolBudget" to 20, "modelBudget" to 4, "expiresAt" to now + 60_000, "capabilities" to JSONArray().put("tool.invoke").put("task.submitGoal"), "resources" to JSONArray().put(obj("screenMode" to "main_screen")), "allowDelegation" to false)))
        fun pairView() = obj("inviteId" to "fixture-invite", "inviterDeviceId" to localId, "inviterFingerprint" to "a".repeat(64), "joinerDeviceId" to peerId, "joinerFingerprint" to "b".repeat(64), "expiresAt" to now + 60_000)
        private fun pairingAction(reviewed: JSONObject? = null): Host.PairingReviewAction = object : Host.PairingReviewAction {
            override fun view(): JSONObject = pairView().also { value ->
                if (reviewed != null) value.put("grantReview", reviewed)
                else value.put("grantOptions", JSONArray().put(obj("optionId" to "fixture-tools", "label" to "示例 · 仅主屏明确工具", "capabilities" to JSONArray().put("tool.invoke"), "toolNames" to JSONArray().put("phone_observe"), "resources" to JSONArray().put(obj("screenMode" to "main_screen")), "allowedOrigins" to JSONArray().put(peerId), "toolBudgetMax" to 20, "modelBudgetMax" to 0, "expiresAtMax" to now + 3_600_000, "allowDelegation" to false, "maxDelegationDepth" to 0, "historyReadExpiresAtMax" to now + 30L * 86_400_000, "historyReadMethods" to JSONArray().put("operation.get").put("task.get").put("task.events"))))
            }
            override fun reviewGrant(choice: Host.GrantChoice): Host.PairingReviewAction {
                check(choice.optionId == "fixture-tools" && choice.toolBudget in 0..20 && choice.modelBudget == 0 && !choice.allowDelegation)
                chosenHistory = choice.historyReadMethods
                val reviewed = obj("digest" to "c".repeat(64), "capabilities" to JSONArray().put("tool.invoke"), "toolNames" to JSONArray().put("phone_observe"), "resources" to JSONArray().put(obj("screenMode" to "main_screen")), "allowedOrigins" to JSONArray().put(peerId), "toolBudget" to choice.toolBudget, "modelBudget" to choice.modelBudget, "expiresAt" to choice.expiresAt, "allowDelegation" to choice.allowDelegation, "maxDelegationDepth" to choice.maxDelegationDepth)
                if (choice.historyReadExpiresAt > 0) reviewed.put("historyRead", obj("expiresAt" to choice.historyReadExpiresAt, "methods" to JSONArray(choice.historyReadMethods)))
                return pairingAction(reviewed)
            }
            override fun confirmBothFingerprints() { check(reviewed != null); pairingConfirmed++; pairState = "confirmed_for_mtls" }
            override fun reject() { }
        }
        private fun grantEditAction(selected: Host.GrantChoice? = null): Host.GrantReviewAction = object : Host.GrantReviewAction {
            val resource = obj("screenMode" to "capabilities")
            val downstream = obj("screenMode" to "capabilities", "projectId" to "fixture-project-d", "checkoutId" to "fixture-checkout-d", "runtimeId" to "fixture-runtime-d")
            override fun view(): JSONObject {
                val view = obj("reviewKind" to "grant_revision", "deviceId" to peerId, "certificateFingerprint" to "b".repeat(64), "currentEpoch" to 2)
                if (selected == null) return view.put("currentGrant", peer.getJSONArray("grantScopes").getJSONObject(0)).put("grantOptions", JSONArray().put(obj("optionId" to "fixture-goal-scope", "label" to "示例 · 处理目标并按范围协作", "capabilities" to JSONArray().put("task.submitGoal"), "toolNames" to JSONArray().put("phone_request_user").put("phone_finish"), "resources" to JSONArray().put(resource), "toolBudgetMax" to 400, "modelBudgetMax" to 1000, "expiresAtMax" to now + 30L * 86_400_000, "allowDelegation" to true, "maxDelegationDepth" to 2)))
                    .put("originOptions", JSONArray().put(obj("optionId" to "fixture-origin-c", "deviceId" to "pw1_" + "c".repeat(64), "label" to "已认证来源 C", "certificateFingerprint" to "c".repeat(64))))
                    .put("forwardingOptions", JSONArray().put(obj("optionId" to "fixture-forward-d", "targetDeviceId" to "pw1_" + "d".repeat(64), "label" to "已授权下游 D", "resourceBinding" to downstream, "toolNames" to JSONArray().put("project_read_file"), "capabilities" to JSONArray().put("tool.invoke"), "toolBudgetMax" to 400, "modelBudgetMax" to 1000, "expiresAtMax" to now + 30L * 86_400_000)))
                val grant = obj("digest" to "f".repeat(64), "label" to "示例 · 设备目标与明确下游", "capabilities" to JSONArray().put("task.submitGoal"), "toolNames" to JSONArray().put("phone_request_user").put("phone_finish"), "resources" to JSONArray().put(resource), "toolBudget" to selected.toolBudget, "modelBudget" to selected.modelBudget, "expiresAt" to selected.expiresAt, "allowDelegation" to selected.allowDelegation, "maxDelegationDepth" to selected.maxDelegationDepth, "allowedOrigins" to JSONArray().put(peerId), "retainedToolsReservedOrUsed" to 8, "retainedModelsReservedOrUsed" to 1)
                if (selected.additionalOriginOptionIds.isNotEmpty()) grant.getJSONArray("allowedOrigins").put("pw1_" + "c".repeat(64))
                if (selected.forwardingOptionIds.isNotEmpty()) grant.put("forwardingScope", obj("targetResources" to JSONArray().put(obj("targetDeviceId" to "pw1_" + "d".repeat(64), "resourceBinding" to downstream)), "toolNames" to JSONArray().put("project_read_file"), "capabilities" to JSONArray().put("tool.invoke"), "toolBudget" to selected.forwardingToolBudget, "modelBudget" to selected.forwardingModelBudget, "maxDelegationDepth" to 2))
                return view.put("grantReview", grant)
            }
            override fun reviewGrant(choice: Host.GrantChoice): Host.GrantReviewAction { check(choice.optionId == "fixture-goal-scope"); chosenGrantRevision = choice; return grantEditAction(choice) }
            override fun confirm() { check(selected != null); grantsChanged++ }
        }
        val host = Host(
            { read++; if (readFailure) throw java.io.IOException("Explicit synthetic read failure; no action executed"); obj("format" to Host.FORMAT, "capturedAt" to now,
                "local" to obj("deviceId" to localId, "certificateFingerprint" to "a".repeat(64), "receiverEnabled" to receiver, "listenerReady" to false, "receiverReason" to "隔离示例 · 没有启动真实监听"),
                "peers" to JSONArray().put(peer),
                "pendingPairings" to JSONArray().put(pairView().put("state", pairState).put("reviewAvailable", pairState == "pending_owner_confirmation")),
                "taskLinks" to obj("links" to JSONArray(), "hasMore" to false),
                "pairingOffersToRecover" to if (showRecovery && !offerAbandoned) JSONArray().put(obj("inviteId" to "fixture-old-offer", "inviterDeviceId" to peerId, "inviterFingerprint" to "b".repeat(64), "expiresAt" to now - 1000, "recoveryOnly" to true, "candidateDigest" to "e".repeat(64), "abandonAvailable" to true)) else JSONArray(),
                "committedPairingsToRecover" to if (showRecovery) JSONArray().put(obj("inviteId" to "fixture-committed", "peerDeviceId" to peerId, "peerFingerprint" to "b".repeat(64), "localFingerprint" to "a".repeat(64), "recoveryState" to "available")) else JSONArray()) },
            object : Host.ReceiverOwner {
                override fun enableAvailability() = Host.Availability.ready()
                override fun disableAvailability() = Host.Availability.ready()
                override fun setEnabled(enabled: Boolean) { receiverMutation++; receiver = enabled }
            },
            object : Host.PairingOwner {
                override fun inviteAvailability() = Host.Availability.blocked("示例未配置真实配对监听，不能创建凭据")
                override fun localAddresses() = listOf("192.0.2.10")
                override fun recoveryAddresses() = listOf("192.0.2.10")
                override fun openCommittedRecovery(id: String, address: String): Host.RecoveryEndpointAction { check(showRecovery && id == "fixture-committed" && address == "192.0.2.10"); recoveryOpened++; return object : Host.RecoveryEndpointAction {
                    var closed = false
                    override fun view() = obj("inviteId" to id, "host" to address, "port" to 12345, "expiresAt" to now + 30_000, "listening" to true, "capturedAt" to now, "localFingerprint" to "a".repeat(64), "peerFingerprint" to "b".repeat(64))
                    override fun close() { if (!closed) recoveryClosed++; closed = true }
                } }
                override fun recoverPairing(id: String, address: String, port: Int) { check(showRecovery && id == "fixture-old-offer" && address == "192.0.2.20" && port == 23456); recoveryRead++ }
                override fun reviewPairingAbandonment(id: String): Host.AbandonAction { check(showRecovery && !offerAbandoned && id == "fixture-old-offer"); return object : Host.AbandonAction {
                    override fun view() = obj("inviteId" to id, "candidateDigest" to "e".repeat(64), "inviterDeviceId" to peerId, "inviterFingerprint" to "b".repeat(64), "localOnly" to true, "remoteRevocationAcknowledged" to false)
                    override fun confirm() { check(!offerAbandoned); offerAbandoned = true }
                } }
                override fun advancePairing(id: String) { throw AssertionError("No network in fixture") }
                override fun createInvitation(address: String): String { inviteMutation++; throw AssertionError("No credentials in UI fixture") }
                override fun joinAvailability() = Host.Availability.blocked("示例未配置隔离 TLS，不能连接真实设备")
                override fun inspectInvitation(invitation: String): Host.PairingReviewAction = throw AssertionError("No network in UI fixture")
                override fun reviewCandidate(id: String) = pairingAction()
                override fun connectAvailability(id: String) = Host.Availability.blocked("隔离fixture不创建连接")
                override fun disconnectAvailability(id: String) = Host.Availability.blocked("隔离fixture没有真实主动连接")
                override fun connect(id: String) { throw AssertionError("No real connection in fixture") }
                override fun disconnect(id: String) { throw AssertionError("No real connection in fixture") }
                override fun refreshPeerFacts(id: String) { check(id == peerId) /* Explicit synthetic cached facts only; no network. */ }
                override fun reviewPeerGrant(id: String): Host.GrantReviewAction { check(id == peerId); return grantEditAction() }
                override fun reviewConnectionUpdate(id: String, raw: String): Host.ConnectionReviewAction = object : Host.ConnectionReviewAction {
                    override fun view() = obj("deviceId" to id, "certificateFingerprint" to "b".repeat(64), "host" to "192.0.2.20", "controlPort" to 2345, "bulkPort" to 2346)
                    override fun confirm() { connectionUpdates++ }
                }
                override fun revokeAvailability(id: String) = if (revoked) Host.Availability.blocked("本机已撤销") else Host.Availability.ready()
                override fun revoke(id: String) { revoked = true }
            },
            object : Host.GoalOwner {
                override fun reviewAvailability(id: String) = if (id == "fixture-goal") Host.Availability.ready() else Host.Availability.blocked("不是待审阅的原目标")
                override fun review(id: String): Host.GoalReviewAction { return object : Host.GoalReviewAction {
                    override fun view() = obj("taskId" to id, "reviewId" to "fixture-native-review", "revision" to 3, "model" to "示例 · 本机已选 API 模型", "provider" to "openai_responses", "source" to "API profile / fixture", "toolBudget" to 12, "modelBudget" to 3, "deadline" to now + 60_000, "policyToolBudget" to 20, "policyModelBudget" to 4, "policyExpiresAt" to now + 120_000, "resourceBinding" to obj("screenMode" to "main_screen"), "origin" to obj("originDeviceId" to peerId, "grantId" to "fixture-grant", "authorizationEpoch" to 2))
                    override fun confirm(useForFutureGoals: Boolean) { goalsConfirmed++; future = useForFutureGoals }
                } }
            },
            object : Host.TaskControl {
                override fun stopAvailability(id: String) = if (id == "fixture-pending" && pendingCancelled > 0) Host.Availability.blocked("Synthetic original SQL target cancelled") else Host.Availability.ready()
                override fun pauseAvailability(id: String) = Host.Availability.blocked("Synthetic fixture has no retained paused session")
                override fun resumeAvailability(id: String) = Host.Availability.blocked("Synthetic fixture cannot resume real execution")
                override fun pendingCancelAvailability(id: String) = if (id == "fixture-pending" && pendingCancelled == 0) Host.Availability.ready() else Host.Availability.blocked("Synthetic fixture has no uncancelled original pending Goal")
                override fun resultReviewAvailability(id: String) = if (id == "fixture-result") Host.Availability.ready() else Host.Availability.blocked("Synthetic fixture has no native idle result")
                override fun cancelPendingGoal(id: String) { check(id == "fixture-pending"); pendingCancelled++ }
                override fun reviewResult(id: String): Host.ResultAction { check(id == "fixture-result"); return object : Host.ResultAction {
                    override fun view() = obj("taskId" to id, "runId" to "fixture-original-run", "executorEpoch" to 2, "resultDigest" to "d".repeat(64), "historicalReviewOnly" to true, "nativeRunnerExited" to true, "nativeDispatchClosed" to true, "summary" to "示例 · 文件整理结果已返回，等待核对", "evidence" to obj("syntheticDisplayOnly" to true), "remoteAuthorizationRevoked" to false)
                    override fun confirm(completed: Boolean) { check(completed); resultsConfirmed++ }
                } }
                override fun historyLinks(id: String) = obj("links" to if (id == "fixture-history") JSONArray().put(obj("operationId" to "fixture-original-op", "targetDeviceId" to peerId, "holdState" to "unknown")) else JSONArray(), "hasMore" to false)
                override fun openHistory(id: String, operationId: String): Host.HistoryAction { check(id == "fixture-history" && operationId == "fixture-original-op"); return object : Host.HistoryAction {
                    var closed = false
                    override fun view() = obj("taskId" to id, "operationId" to operationId, "targetDeviceId" to peerId, "resourceBinding" to obj("screenMode" to "capabilities"), "readOnly" to true, "newRun" to false)
                    override fun query(): JSONObject { check(!closed); historyQueries++; return obj("unknown" to true, "readOnly" to true, "newRun" to false, "retrySubmitted" to false, "unknownRefunded" to false, "operationReceipt" to obj("status" to "unknown", "accepted" to false, "syntheticDisplayOnly" to true)) }
                    override fun requestStop(): JSONObject { check(!closed); historyStops++; return obj("unknown" to true, "managementOnly" to true, "stopped" to false, "retrySubmitted" to false) }
                    override fun close() { if (!closed) historyClosed++; closed = true }
                } }
                override fun pause(id: String) { throw AssertionError("No real execution") }
                override fun resume(id: String) { throw AssertionError("No real execution") }
                override fun reviewApproval(id: String, approvalId: String): Host.ApprovalAction = throw AssertionError("No native pending operation in fixture")
                override fun requestStop(id: String) { stops++ }
            })
        val task get() = taskFromJson(obj("id" to "fixture-goal", "goal" to "示例 · 整理手机资料，并把结果交回原任务", "status" to "awaiting_user", "summary" to "隔离示例：等待 B 机主确认自己的模型，不代表已执行", "deviceExecutionKind" to "GOAL", "updatedAt" to now, "origin" to obj("kind" to "paired_device", "originDeviceId" to peerId, "callerDeviceId" to peerId, "targetDeviceId" to localId, "grantId" to "fixture-grant", "authorizationEpoch" to 2)))
    }
    private fun composeViews(view: View): List<View> = buildList { if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(composeViews(view.getChildAt(i))) }
    private fun nodes(root: View) = composeViews(root).flatMap { (it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).getAllSemanticsNodes(mergingEnabled = true) }
    private fun texts(root: View) = nodes(root).flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { text -> text.text }.orEmpty() }
    private fun tagged(root: View, tag: String) = nodes(root).first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun clickTag(root: View, tag: String) { val node = tagged(root, tag); assertFalse("disabled $tag", node.config.contains(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); settle(root) }
    private fun clickText(root: View, text: String) { val node = nodes(root).first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true && it.config.contains(SemanticsActions.OnClick) }; assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); settle(root) }
    private fun setText(root: View, tag: String, value: String) { val node = tagged(root, tag); assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString(value))); settle(root) }
    private fun settle(root: View) {
        fun layout(view: View) { if (view is androidx.compose.ui.node.RootForTest) view.measureAndLayoutForTest(); if (view is ViewGroup) for (i in 0 until view.childCount) layout(view.getChildAt(i)) }
        repeat(6) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60)); root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 393, 852); layout(root); Thread.sleep(12) }
    }
    private fun awaitDialog(root: View): android.app.Dialog {
        val until = System.nanoTime() + 6_000_000_000L
        while (System.nanoTime() < until) { settle(root); ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.let { return it } }
        error("Native dialog did not appear; actual source: ${texts(root)}")
    }
    private fun capture(root: View, name: String) {
        settle(root); output.mkdirs(); val bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap)); val pixels = IntArray(393 * 852); bitmap.getPixels(pixels, 0, 393, 0, 0, 393, 852)
        assertTrue("Current source render is not blank", pixels.toSet().size > 16)
        File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        File(output, "$name-semantics.txt").writeText(texts(root).joinToString("\n")); println("DEVICE_UI_NATIVE_FIXTURE $name 393x852 API35; NOT REAL PAIRING")
    }
    @Test fun nativeListDetailBackReopenAndApprovalSemantics() {
        val fixture = Fixture(); var openTask: String? = null; var models = 0
        val pending = Robolectric.buildActivity(DeviceWorkspaceFixtureActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible().windowFocusChanged(true).get(); val decor = activity.window.decorView
        var showing by mutableStateOf(true)
        val originalTasks = listOf(fixture.task)
        activity.setContent { PocketWorldTheme { if (showing) MyDevicesPanel(fixture.host, originalTasks, { it.run() }, {}, { openTask = it }, { models++ }, { showing = false }) else Button(onClick = { showing = true }) { Text("重开我的设备") } } }
        try {
            settle(decor); assertEquals(0, fixture.receiverMutation); assertEquals(0, fixture.inviteMutation); assertEquals(0, fixture.pairingConfirmed); assertTrue(texts(decor).contains("当前离线"))
            capture(decor, "01-device-list")
            clickTag(decor, "device_peer_$peerId"); assertTrue(texts(decor).contains("任务与成果")); assertTrue(texts(decor).any { it.contains("设备离线") }); capture(decor, "02-device-detail")
            clickTag(decor, "device_identity_toggle"); assertTrue(texts(decor).contains(peerId)); clickTag(decor, "device_grant_toggle"); assertTrue(texts(decor).contains("执行授权有效"))
            clickTag(decor, "device_task_open_fixture-goal"); assertEquals("fixture-goal", openTask)
            clickTag(decor, "device_goal_review_fixture-goal"); val goal = awaitDialog(decor); val goalRoot = goal.window!!.decorView; settle(goalRoot); assertEquals(0, fixture.goalsConfirmed); capture(goalRoot, "03-goal-native-review")
            clickTag(goalRoot, "device_goal_future_policy"); clickTag(goalRoot, "device_goal_model_confirm"); settle(decor); assertEquals(1, fixture.goalsConfirmed); assertTrue(fixture.future); assertFalse(goal.isShowing)
            activity.onBackPressedDispatcher.onBackPressed(); settle(decor); assertTrue(texts(decor).contains("你的设备")); assertEquals(0, fixture.receiverMutation)
            clickText(decor, "核对新设备的双端指纹"); val pair = awaitDialog(decor); val pairRoot = pair.window!!.decorView; settle(pairRoot)
            assertTrue(tagged(pairRoot, "device_pair_review_grant").config.contains(SemanticsProperties.Disabled)); assertEquals(0, fixture.pairingConfirmed)
            clickTag(pairRoot, "device_pair_scope_fixture-tools"); clickTag(pairRoot, "device_pair_edit_limits"); setText(pairRoot, "device_pair_tool_budget", "12"); setText(pairRoot, "device_pair_model_budget", "0"); clickTag(pairRoot, "device_pair_history_enable"); capture(pairRoot, "04-pair-native-grant-choice")
            clickTag(pairRoot, "device_pair_review_grant"); settle(pairRoot)
            assertTrue(tagged(pairRoot, "device_pair_confirm").config.contains(SemanticsProperties.Disabled)); capture(pairRoot, "05-pair-grant-and-double-fingerprint")
            clickTag(pairRoot, "device_pair_fingerprints_checked"); clickTag(pairRoot, "device_pair_confirm"); settle(decor); assertEquals(1, fixture.pairingConfirmed); assertEquals(setOf("operation.get", "task.get", "task.events"), fixture.chosenHistory.toSet()); assertFalse(pair.isShowing)
            capture(decor,"09-pairing-waiting-not-connected")
            fixture.readFailure = true; clickTag(decor,"device_workspace_refresh"); capture(decor,"10-read-error-retains-known-state"); assertEquals(1,fixture.pairingConfirmed);fixture.readFailure=false
            clickTag(decor, "device_workspace_back"); assertTrue(texts(decor).contains("重开我的设备")); clickText(decor, "重开我的设备"); settle(decor); assertTrue(texts(decor).contains("你的设备")); assertEquals(1, fixture.pairingConfirmed); assertEquals(1, fixture.goalsConfirmed); assertEquals(0, models)
            File(output, "journey.json").writeText(obj("syntheticNativeProducerFixture" to true, "actualProductionComposeSource" to true, "physicalDevice" to false, "noNetworkKeysModelOrCredentialGeneration" to true, "readDidNotOptIn" to true, "originalTaskIdRouted" to (openTask == "fixture-goal"), "uncheckedPairConfirmDisabled" to true, "backCloseReopen" to true, "futurePolicyNativeCallbackOnce" to (fixture.goalsConfirmed == 1 && fixture.future)).toString(2))
        } finally { pending.pause().stop().destroy() }
    }
    @Test fun sourceScopeChoiceUsesNativeOptionIdsAndNonOverlappingShares() {
        val now = System.currentTimeMillis(); val child = "pw1_" + "d".repeat(64)
        val resource = obj("screenMode" to "capabilities", "projectId" to "fixture-project", "checkoutId" to "fixture-checkout", "runtimeId" to "fixture-runtime")
        val nativeView = obj("taskId" to "fixture-owner-task", "goal" to "示例 · 在电脑开发项目，并把资料整理交给另一台设备", "toolsAvailable" to 100, "modelsAvailable" to 20, "deadlineMaximum" to now + 86_400_000, "sourceOperationOverheadAtLeast" to 1,
            "options" to JSONArray().put(obj("optionId" to "fixture-option-b", "sourceKey" to "fixture-route-b", "targetDeviceId" to peerId, "kind" to "goal", "resourceBinding" to resource, "toolBudgetMaximum" to 100, "modelBudgetMaximum" to 20, "deadlineMaximum" to now + 86_400_000,
                "toolDeclarations" to JSONArray().put(obj("toolName" to "project_read_file", "effect" to "read")), "diagnostics" to obj("isolation" to false),
                "downstreamOptions" to JSONArray().put(obj("optionId" to "fixture-share-d", "targetDeviceId" to child, "resourceBinding" to obj("screenMode" to "capabilities"))))))
        var chosen: NativeDeviceTaskScopeHost.Selection? = null
        var reviewView by mutableStateOf<JSONObject?>(null); var confirmed = 0
        val pending = Robolectric.buildActivity(DeviceWorkspaceFixtureActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible().windowFocusChanged(true).get(); val root = activity.window.decorView
        activity.setContent { PocketWorldTheme {
            if (reviewView == null) DeviceScopeSelection(nativeView, false, Modifier.fillMaxSize()) { chosen = it }
            else DeviceScopeReview(reviewView!!, nativeView, false, Modifier.fillMaxSize(), {}) { confirmed++ }
        } }
        try {
            settle(root); assertTrue(tagged(root,"device_scope_review").config.contains(SemanticsProperties.Disabled))
            clickTag(root,"device_scope_option_fixture-option-b"); capture(root,"06-source-device-scope")
            clickTag(root,"device_scope_share_fixture-share-d"); assertTrue(tagged(root,"device_scope_review").config.contains(SemanticsProperties.Disabled))
            setText(root,"device_scope_share_tools_fixture-share-d","10");setText(root,"device_scope_share_models_fixture-share-d","2")
            nodes(root).firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, 520f); settle(root)
            capture(root,"07-source-explicit-downstream-shares"); clickTag(root,"device_scope_review")
            val route = requireNotNull(chosen).routes().single(); assertEquals("fixture-option-b",route.optionId());assertEquals(NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY,route.context())
            assertEquals("fixture-share-d",route.shares().single().optionId());assertEquals(route.toolBudget(),route.localToolShare()+route.shares().sumOf { it.toolBudget() });assertEquals(route.modelBudget(),route.localModelShare()+route.shares().sumOf { it.modelBudget() })
            // Safe display fixture, NOT an opaque approval: product confirmation still uses the
            // actual SourceScopeReview retained by DeviceTaskScopeDialog/the original TaskStore.
            reviewView = obj("goal" to nativeView.getString("goal"), "routes" to JSONArray().put(obj("optionId" to route.optionId(), "targetDeviceId" to peerId, "kind" to "goal", "resourceBinding" to resource,
                "toolDeclarations" to obj("project_read_file" to "read"), "toolBudget" to route.toolBudget(), "modelBudget" to route.modelBudget(), "localToolShare" to route.localToolShare(), "localModelShare" to route.localModelShare(), "deadline" to route.deadline(),
                "downstreamShares" to JSONArray().put(obj("targetDeviceId" to child, "resourceBinding" to obj("screenMode" to "capabilities"), "toolBudget" to 10, "modelBudget" to 2)))))
            settle(root); assertTrue(tagged(root,"device_scope_confirm").config.contains(SemanticsProperties.Disabled)); capture(root,"08-source-exact-scope-review")
            clickTag(root,"device_scope_consent"); clickTag(root,"device_scope_confirm"); assertEquals(1,confirmed)
            File(output,"source-scope-choices.json").writeText(obj("syntheticReadModel" to true,"actualProductionCompose" to true,"nativeApprovalConfirmed" to false,"onlyOptionIdsAndFiniteAmountsReturned" to true,"nonOverlappingShares" to true,"context" to "goal_only").toString(2))
        } finally { pending.pause().stop().destroy() }
    }
    @Test fun pairedControlsKeepStopIndependentAndReviewSameResult() {
        val f = Fixture(); var task by mutableStateOf(f.task); var hold = false; val queued = mutableListOf<Runnable>()
        val pending = Robolectric.buildActivity(DeviceWorkspaceFixtureActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible().windowFocusChanged(true).get(); val root = activity.window.decorView
        activity.setContent { PocketWorldTheme { DevicePairedTaskControls(task, f.host, { if (hold) queued += it else it.run() }, { it.run() }, 0L, {}, {}) } }
        try {
            settle(root); hold = true; clickTag(root, "paired_task_goal_model"); assertEquals(1, queued.size)
            clickTag(root, "paired_task_stop"); assertEquals(1, f.stops); assertEquals(0, f.goalsConfirmed)
            hold = false; queued.removeAt(0).run(); settle(root); val goal = awaitDialog(root); goal.onBackPressed(); settle(root); assertEquals(0, f.goalsConfirmed)
            task = taskFromJson(obj("id" to "fixture-pending", "goal" to "示例 · 尚未开始的设备目标", "status" to "awaiting_user", "updatedAt" to f.now, "deviceExecutionKind" to "GOAL", "origin" to obj("kind" to "paired_device")))
            settle(root); clickTag(root, "paired_task_cancel_pending"); assertEquals(1, f.pendingCancelled); assertEquals(1, f.stops)
            task = taskFromJson(obj("id" to "fixture-result", "goal" to "示例 · 核对已结束执行的成果", "status" to "needs_confirmation", "updatedAt" to f.now, "deviceExecutionKind" to "GOAL", "runId" to "fixture-original-run", "coordinatorEpoch" to 2, "origin" to obj("kind" to "paired_device")))
            settle(root); capture(root, "11-paired-original-task-controls"); clickTag(root, "paired_task_review_result"); val dialog = awaitDialog(root); val d = dialog.window!!.decorView; settle(d)
            assertTrue(tagged(d, "paired_task_result_confirm").config.contains(SemanticsProperties.Disabled)); capture(d, "12-paired-historical-result-review")
            clickTag(d, "paired_task_result_checked"); clickTag(d, "paired_task_result_confirm"); settle(root); assertEquals(1, f.resultsConfirmed); assertEquals(0, f.goalsConfirmed)
        } finally { pending.pause().stop().destroy() }
    }
    @Test fun historyReadKeepsUnknownAndClosesNativeWindowOnBack() {
        val f = Fixture(); val task = taskFromJson(obj("id" to "fixture-history", "goal" to "示例 · 暂停后核对电脑执行结果", "status" to "paused", "updatedAt" to f.now))
        val pending = Robolectric.buildActivity(DeviceWorkspaceFixtureActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible().windowFocusChanged(true).get(); val root = activity.window.decorView
        activity.setContent { PocketWorldTheme { DeviceTaskHistoryControls(task, f.host, { it.run() }, 0L, {}) } }
        try {
            settle(root); clickTag(root, "device_task_history_open_fixture-original-op"); val dialog = awaitDialog(root); val d = dialog.window!!.decorView; settle(d)
            assertEquals(0, f.historyStops); clickTag(d, "device_task_history_stop"); assertEquals(1, f.historyStops); assertTrue(texts(d).any { it.contains("停止结果尚未确认") })
            assertEquals(0, f.historyQueries); clickTag(d, "device_task_history_query"); assertEquals(1, f.historyQueries); assertTrue(texts(d).contains("执行结果仍未确认")); capture(d, "13-original-task-unknown-history")
            dialog.onBackPressed(); settle(root); assertEquals(1, f.historyClosed); assertEquals(0, f.stops)
            clickTag(root, "device_task_history_open_fixture-original-op"); settle(awaitDialog(root).window!!.decorView); assertEquals(1, f.historyQueries)
        } finally { pending.pause().stop().destroy() }
        assertEquals(2, f.historyClosed)
    }
    @Test fun recoveryLocatorStaysReadOnlyOfflineAndClosesEndpointOnBack() {
        val f = Fixture(); f.showRecovery = true
        val pending = Robolectric.buildActivity(DeviceWorkspaceFixtureActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible().windowFocusChanged(true).get(); val root = activity.window.decorView
        activity.setContent { PocketWorldTheme { MyDevicesPanel(f.host, emptyList(), { it.run() }, {}, {}, {}, {}) } }
        try {
            settle(root); clickTag(root, "device_pair_recovery_toggle"); clickTag(root, "device_pair_recovery_provide_fixture-committed")
            val choice = awaitDialog(root); val c = choice.window!!.decorView; settle(c); clickTag(c, "device_pair_recovery_confirm"); settle(root)
            val endpoint = awaitDialog(root); val e = endpoint.window!!.decorView; settle(e); capture(e, "14-committed-pair-recovery-endpoint")
            assertEquals(1, f.recoveryOpened); endpoint.onBackPressed(); settle(root); assertEquals(1, f.recoveryClosed)
            clickTag(root, "device_pair_recovery_offer_fixture-old-offer"); val offer = awaitDialog(root); val o = offer.window!!.decorView; settle(o)
            assertTrue(tagged(o, "device_pair_recovery_confirm").config.contains(SemanticsProperties.Disabled)); setText(o, "device_pair_recovery_host", "192.0.2.20"); setText(o, "device_pair_recovery_port", "23456"); capture(o, "15-pair-recovery-original-pin-and-locator")
            clickTag(o, "device_pair_recovery_confirm"); settle(root); assertEquals(1, f.recoveryRead); assertEquals(0, f.pairingConfirmed); assertTrue(texts(root).contains("当前离线")); assertEquals(0, f.receiverMutation)
            clickTag(root, "device_pair_abandon_fixture-old-offer"); val abandon = awaitDialog(root); val a = abandon.window!!.decorView; settle(a); assertTrue(tagged(a, "device_pair_abandon_confirm").config.contains(SemanticsProperties.Disabled)); capture(a, "16-pair-abandonment-local-only")
            clickTag(a, "device_pair_abandon_checked"); clickTag(a, "device_pair_abandon_confirm"); settle(root); assertTrue(f.offerAbandoned); assertEquals(0, f.pairingConfirmed); assertEquals(0, f.receiverMutation)
        } finally { pending.pause().stop().destroy() }
    }
    @Test fun grantRevisionUsesNativeOriginAndExactDownstreamOptionsWithoutPairing() {
        val f = Fixture(); val pending = Robolectric.buildActivity(DeviceWorkspaceFixtureActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible().windowFocusChanged(true).get(); val root = activity.window.decorView
        activity.setContent { PocketWorldTheme { MyDevicesPanel(f.host, emptyList(), { it.run() }, {}, {}, {}, {}) } }
        try {
            settle(root); clickTag(root, "device_peer_$peerId"); clickTag(root, "device_grant_toggle"); clickTag(root, "device_grant_edit"); val dialog = awaitDialog(root); val d = dialog.window!!.decorView; settle(d)
            clickTag(d, "device_pair_scope_fixture-goal-scope"); clickTag(d, "device_grant_origins_toggle"); clickTag(d, "device_grant_origin_fixture-origin-c"); clickTag(d, "device_grant_forwarding_toggle"); clickTag(d, "device_grant_forward_fixture-forward-d")
            setText(d, "device_grant_forward_tools", "0"); setText(d, "device_grant_forward_models", "0"); assertTrue(tagged(d, "device_pair_review_grant").config.contains(SemanticsProperties.Disabled)); setText(d, "device_grant_forward_tools", "5")
            nodes(d).firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, 450f); settle(d); capture(d, "17-native-grant-origins-and-exact-downstream-choice")
            clickTag(d, "device_pair_review_grant"); settle(d); val choice = requireNotNull(f.chosenGrantRevision); assertEquals(listOf("fixture-origin-c"), choice.additionalOriginOptionIds); assertEquals(listOf("fixture-forward-d"), choice.forwardingOptionIds); assertEquals(5, choice.forwardingToolBudget); assertEquals(0, choice.forwardingModelBudget)
            assertTrue(tagged(d, "device_grant_revision_confirm").config.contains(SemanticsProperties.Disabled)); capture(d, "18-native-grant-revision-review-not-repair")
            clickTag(d, "device_grant_revision_checked"); clickTag(d, "device_grant_revision_confirm"); settle(root); assertEquals(1, f.grantsChanged); assertEquals(0, f.pairingConfirmed)
        } finally { pending.pause().stop().destroy() }
    }
    @Test fun coldPendingGoalCanBeCancelledLocallyAfterTaskSheetReopen() {
        val f = Fixture(); var showing by mutableStateOf(true); var openedModels = 0
        // Safe UI read-model only: no retained Admission/session is supplied by this fixture.
        // Real original-SQL cold cancellation/CAS is covered by phone 4cf60bb direct cases.
        val cold = taskFromJson(obj("id" to "fixture-pending", "goal" to "示例 · 重启后尚未开始的设备目标", "status" to "interrupted", "updatedAt" to f.now, "runId" to "", "coordinatorEpoch" to 0, "deviceExecutionKind" to "GOAL", "origin" to obj("kind" to "paired_device")))
        val pending = Robolectric.buildActivity(DeviceWorkspaceFixtureActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = pending.setup().visible().windowFocusChanged(true).get(); val root = activity.window.decorView
        activity.setContent { PocketWorldTheme { Column {
            if (showing) { DevicePairedTaskControls(cold, f.host, { it.run() }, { it.run() }, 0L, {}, { openedModels++ }); Button(onClick = { showing = false }) { Text("关闭这项任务") } }
            else Button(onClick = { showing = true }) { Text("重新打开这项任务") }
        } } }
        try {
            settle(root); assertTrue(nodes(root).none { it.config.getOrNull(SemanticsProperties.TestTag) == "paired_task_goal_model" }); assertEquals(0, f.pendingCancelled)
            clickText(root, "关闭这项任务"); clickText(root, "重新打开这项任务"); settle(root); clickTag(root, "paired_task_cancel_pending")
            assertEquals(1, f.pendingCancelled); assertEquals(0, f.stops); assertEquals(0, f.goalsConfirmed); assertEquals(0, openedModels); assertEquals("interrupted", cold.status)
            assertTrue(texts(root).contains("已取消本机尚未开始的目标。")); assertTrue(nodes(root).none { it.config.getOrNull(SemanticsProperties.TestTag) == "paired_task_cancel_pending" })
        } finally { pending.pause().stop().destroy() }
    }
    @Test fun nativeReviewCannotBeReplayedCrossWindowOrAfterBack() {
        val fixture = Fixture(); val first = fixture.host.openOwnerWindow(); val second = fixture.host.openOwnerWindow()
        val candidate = first.reviewCandidate("fixture-invite")
        try { first.confirmPairing(candidate); fail("Fingerprints without exact native grant review accepted") } catch (_: SecurityException) { }
        val pair = first.reviewPairingGrant(candidate, Host.GrantChoice("fixture-tools", 12, 0, fixture.now + 60_000, false, 0))
        try { second.confirmPairing(pair); fail("Cross-window native proof accepted") } catch (_: SecurityException) { }
        first.discardReview(); try { first.confirmPairing(pair); fail("Back-invalidated proof accepted") } catch (_: SecurityException) { }
        val fresh = first.reviewPairingGrant(first.reviewCandidate("fixture-invite"), Host.GrantChoice("fixture-tools", 12, 0, fixture.now + 60_000, false, 0)); first.confirmPairing(fresh)
        try { first.confirmPairing(fresh); fail("Repeated pairing confirmation accepted") } catch (_: SecurityException) { }
        val connection = first.reviewConnectionUpdate(peerId, "SYNTHETIC METADATA ONLY")
        try { second.confirmConnectionUpdate(connection); fail("Cross-window connection update accepted") } catch (_: SecurityException) { }
        first.discardReview(); try { first.confirmConnectionUpdate(connection); fail("Back-invalidated address review accepted") } catch (_: SecurityException) { }
        val currentAddress = first.reviewConnectionUpdate(peerId, "SYNTHETIC METADATA ONLY"); first.confirmConnectionUpdate(currentAddress)
        try { first.confirmConnectionUpdate(currentAddress); fail("Connection review replay accepted") } catch (_: SecurityException) { }
        assertEquals(1, fixture.connectionUpdates)
        val oldResult = first.reviewResult("fixture-result"); first.discardReview()
        try { first.confirmResult(oldResult, true); fail("Back-invalidated result review accepted") } catch (_: SecurityException) { }
        val result = first.reviewResult("fixture-result"); try { second.confirmResult(result, true); fail("Cross-window result accepted") } catch (_: SecurityException) { }
        first.confirmResult(result, true); try { first.confirmResult(result, true); fail("Result review replay accepted") } catch (_: SecurityException) { }
        val history = first.openHistory("fixture-history", "fixture-original-op"); first.discardReview(); try { history.query(); fail("Closed read window queried") } catch (_: SecurityException) { }
        try { history.requestStop(); fail("Closed original operation stop window used") } catch (_: SecurityException) { }
        assertEquals(0, fixture.historyStops)
        assertEquals(1, fixture.resultsConfirmed); assertEquals(0, fixture.historyQueries); assertEquals(1, fixture.historyClosed)
        val goal = first.reviewGoal("fixture-goal"); first.close(); try { first.confirmGoal(goal, true); fail("Closed native goal review accepted") } catch (_: IllegalStateException) { }
        assertEquals(1, fixture.pairingConfirmed); assertEquals(0, fixture.goalsConfirmed); second.close()
        fixture.host.close(); try { fixture.host.openOwnerWindow(); fail("Closed native owner reopened a review window") } catch (_: IllegalStateException) { }
    }
}
