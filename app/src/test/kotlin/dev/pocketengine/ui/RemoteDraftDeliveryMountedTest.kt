package dev.pocketengine.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.pocketengine.features.remote.RemoteTaskDrafts
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual original Compose panel/callbacks. Native SQL/HTTP/Pi dispatch is separately
 * tested by RemotePiDraftOwnerJourneyTest, not simulated by these callback records.
 * No screenshot, touch/device, vendor CLI/account, or visual proof is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h1000dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteDraftDeliveryMountedTest {
    private fun compose(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) repeat(view.childCount) { compose(view.getChildAt(it))?.let { found -> return found } }
        return null
    }
    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) { result += node; node.children.forEach(::visit) }
        WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }.forEach { root -> compose(root)?.let { visit((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode) } }
        return result
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    private fun source(worker: String, protocol: String) = JSONObject().put("taskId", "task").put("runId", "run").put("coordinatorEpoch", 1).put("profileId", "computer").put("serviceBase", "https://fixture.invalid").put("environmentId", "workspace")
        .put("worker", worker).put("protocol", protocol).put("protocolVerified", true).put("cancelRequested", false).put("jobId", "job").put("jobOperationId", "operation")
        .put("threadId", "session").put("sessionId", "session").put("turnId", "turn").put("executorStatus", "running").put("controls", JSONObject().put("inputSteering", true).put("threadResume", false).put("cancel", true)).apply { if (worker == "claude") put("controlProtocolVerified", true).put("sessionIdentityConfirmed", true).put("promptRequestId", "prompt") }
    private fun pi(settled: Boolean) = source("pi", "pi_rpc_v1").put("noTools", true).put("promptRequestId", "prompt").apply {
        remove("turnId")
        if (settled) { put("executorStatus", "completed").put("nativeSettled", true).put("resourcesHeld", false).put("processCleanupProof", JSONObject().put("processGroupStoppedVerified", true)); getJSONObject("controls").put("threadResume", true) }
    }
    private fun snapshot(source: JSONObject, canContinue: Boolean, queued: String? = null, savedSource: JSONObject = source): JSONObject {
        val drafts = JSONArray()
        if (queued != null) drafts.put(JSONObject().put("id", UUID.randomUUID().toString()).put("revision", 1).put("state", "queued").put("text", "queued owner input").put("mode", queued).put("scope", RemoteTaskDrafts.scope(savedSource, "a".repeat(64), queued)))
        return JSONObject().put("taskId", "task").put("source", source).put("canContinue", canContinue).put("inputDrafts", drafts)
    }
    private inner class Fixture(snapshot: JSONObject) : AutoCloseable {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        init { try { owner.get().setContent { MaterialTheme { Column { RemoteDraftPanel(snapshot, true) { command, input -> sent += command to JSONObject(input.toString()) } } } }; frame() } catch (failure: Throwable) { close(); throw failure } }
        fun frame() { repeat(48) { shadowOf(Looper.getMainLooper()).runOneTask(); WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach { view -> view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST)); view.layout(0, 0, view.measuredWidth, view.measuredHeight) } } }
        fun node(label: String) = nodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null } ?: error("Missing original draft UI action $label; texts=${nodes().map(::text)}")
        fun enabled(label: String) = node(label).config.getOrNull(SemanticsProperties.Disabled) == null
        fun click(label: String) { assertTrue(label, enabled(label)); assertTrue(node(label).config[SemanticsActions.OnClick].action!!.invoke()); frame() }
        fun edit(value: String) { val node = nodes().first { it.config.getOrNull(SemanticsActions.SetText)?.action != null }; assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frame() }
        fun selectQueued() = click("待明确发送 · queued owner input")
        override fun close() { owner.pause().stop().destroy(); repeat(8) { shadowOf(Looper.getMainLooper()).runOneTask() } }
    }
    @Test fun runningNoToolsPiShowsTheRealLimitationAndOnlyQueuesLocalIntent() {
        Fixture(snapshot(pi(false), false)).use { f ->
            assertTrue(nodes().any { text(it).contains("不代表已具备Pi编程能力") }); assertTrue(nodes().any { text(it).contains("保存和排队本身不发送") }); assertTrue(nodes().any { text(it).contains("不会回滚") })
            assertFalse(nodes().any { text(it).contains("当前Codex turn补充") }); assertTrue(f.sent.isEmpty())
            f.edit("one explicit local Pi draft"); f.click("保存并排队")
            assertEquals(1, f.sent.size); assertEquals("saveDraft", f.sent.single().first)
            val input = f.sent.single().second; assertEquals("next_turn", input.getString("mode")); assertTrue(input.getBoolean("queued")); assertEquals("one explicit local Pi draft", input.getString("text")); assertFalse(input.has("source"))
        }
    }
    @Test fun unsettledPiCannotSendEvenWhenAStalePresentationBooleanSaysContinue() {
        Fixture(snapshot(pi(false), true, "next_turn")).use { f -> f.selectQueued(); assertFalse(f.enabled("明确发送这条并继续原会话")); assertTrue(f.sent.isEmpty()) }
    }
    @Test fun settledPiExplicitSendReferencesOnlyTheOriginalQueuedDraft() {
        val view = snapshot(pi(true), true, "next_turn"); val id = view.getJSONArray("inputDrafts").getJSONObject(0).getString("id")
        Fixture(view).use { f ->
            f.selectQueued(); assertTrue(f.sent.isEmpty()); f.click("明确发送这条并继续原会话")
            assertEquals("sendDraft", f.sent.single().first); val input = f.sent.single().second; assertEquals(2, input.length()); assertEquals(id, input.getString("draftId")); assertEquals(1, input.getLong("draftRevision"))
            assertTrue(nodes().any { text(it).contains("不会借用其他继续表单的模型/档位选择") })
        }
    }
    @Test fun codexLiveUsesOriginalDraftReferenceWhileClaudeNeverAdvertisesMountedLiveInput() {
        Fixture(snapshot(source("codex_app_server", "codex_app_server"), false, "steer")).use { f ->
            f.selectQueued(); assertTrue(nodes().any { text(it).contains("接收回执不等于模型已经采用") }); f.click("明确发送这条到原turn"); assertEquals("sendDraft", f.sent.single().first); assertEquals(2, f.sent.single().second.length())
        }
        Fixture(snapshot(source("claude", "claude_stream_json"), false, "next_turn")).use { f ->
            f.selectQueued(); assertFalse(nodes().any { text(it).contains("当前Codex turn补充") }); assertTrue(nodes().any { text(it).contains("本适配器未挂接Claude运行中补充") }); assertFalse(f.enabled("明确发送这条并继续原会话")); assertTrue(f.sent.isEmpty())
        }
    }
    @Test fun verifiedPiQueueShowsUnknownConsumptionAndSendsOnlyTheExplicitSavedDraft() {
        val source = pi(false).put("inputSteeringVerified", true)
        val view = snapshot(source, false, "steer")
        val id = view.getJSONArray("inputDrafts").getJSONObject(0).getString("id")
        Fixture(view).use { f ->
            assertTrue(nodes().any { text(it).contains("队列接收不证明模型已处理") })
            assertFalse(nodes().any { text(it).contains("当前Codex turn补充") })
            f.selectQueued(); assertTrue(f.sent.isEmpty()); f.click("明确追加这条到原Pi队列")
            assertEquals("sendDraft", f.sent.single().first); assertEquals(2, f.sent.single().second.length())
            assertEquals(id, f.sent.single().second.getString("draftId")); assertEquals(1, f.sent.single().second.getLong("draftRevision"))
            assertFalse(f.sent.single().second.has("expectedTurnId")); assertFalse(f.sent.single().second.has("source"))
        }
    }
    @Test fun stoppedOrUnknownPiQueueKeepsSavedInputButCannotDispatchIt() {
        val saved = pi(false).put("inputSteeringVerified", true)
        listOf(JSONObject(saved.toString()).put("cancelRequested", true), JSONObject(saved.toString()).put("steeringUnknown", true), JSONObject(saved.toString()).put("steeringUnknown", true).put("inputSteeringVerified", false)).forEach { stopped ->
            Fixture(snapshot(stopped, false, "steer", saved)).use { f ->
                f.selectQueued(); assertFalse(f.enabled("明确追加这条到原Pi队列")); assertTrue(f.sent.isEmpty())
                if (!stopped.optBoolean("inputSteeringVerified")) {
                    assertTrue(nodes().any { text(it).contains("既有操作请按原消息回执核对") })
                    assertFalse(nodes().any { text(it).contains("尚未发送给模型") || text(it).contains("未挂接Claude / Pi") })
                }
            }
        }
    }
    @Test fun changedPiToolIdentityKeepsTheDraftVisibleButCannotInheritSaveOrSendCapability() {
        val saved = pi(true); val changed = JSONObject(saved.toString()).put("noTools", false)
        Fixture(snapshot(changed, true, "next_turn", saved)).use { f ->
            f.selectQueued(); assertFalse(f.enabled("保存并排队")); assertFalse(f.enabled("明确发送这条并继续原会话")); assertTrue(f.sent.isEmpty())
        }
    }
    @Test fun editedQueuedTextMustBeExplicitlySavedBeforeAnySend() {
        Fixture(snapshot(pi(true), true, "next_turn")).use { f ->
            f.selectQueued(); f.edit("new unsaved clipboard or owner requirement")
            assertFalse("send must not silently use the prior SQL text instead of the visible edited draft", f.enabled("明确发送这条并继续原会话"))
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun capturedSendCannotDispatchAfterTheVisibleQueuedEditorChanges() {
        Fixture(snapshot(pi(true), true, "next_turn")).use { f ->
            f.selectQueued(); val old = f.node("明确发送这条并继续原会话").config[SemanticsActions.OnClick].action!!
            f.edit("changed after the old click was captured"); old.invoke(); f.frame()
            assertTrue("old send callback cannot send text no longer confirmed in the editor", f.sent.isEmpty())
        }
    }
    @Test fun capturedSendCannotRetargetAnotherQueuedDraftAfterSelectionChanges() {
        val view = snapshot(pi(true), true, "next_turn")
        val original = view.getJSONArray("inputDrafts").getJSONObject(0)
        view.getJSONArray("inputDrafts").put(JSONObject(original.toString()).put("id", UUID.randomUUID().toString()).put("text", "other queued input"))
        Fixture(view).use { f ->
            f.selectQueued(); val old = f.node("明确发送这条并继续原会话").config[SemanticsActions.OnClick].action!!
            f.click("待明确发送 · other queued input"); old.invoke(); f.frame()
            assertTrue("old send must not borrow the newly selected draft's id/revision", f.sent.isEmpty())
        }
    }

}
