package dev.pocketengine.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import java.time.Duration
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog

/** Actual mounted namespace/candidate/dialog controls -> Controller -> native host/Book/SQLite.
 * Original source setup uses real workspace bytes; there is no mocked successful review. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h1600dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MemoryAssertionReviewMountedNativeTest {
    private var root: View? = null
    private fun compose(value: View): View? {
        if (value.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return value
        if (value is ViewGroup) repeat(value.childCount) { compose(value.getChildAt(it))?.let { found -> return found } }
        return null
    }
    private fun nodes(window: View? = root): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) { result += node; node.children.forEach(::visit) }
        window?.let { assertTrue(it.isAttachedToWindow); compose(it)?.let { view -> visit((view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner).rootSemanticsNode) } }
        return result
    }
    private fun dialog() = nodes(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView)
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    private fun contains(node: SemanticsNode, label: String): Boolean = text(node).contains(label) || node.children.any { contains(it, label) }
    private inner class Fixture : AutoCloseable {
        val native = ProjectMemoryReviewUiNativeTest.Fixture()
        val calls = mutableListOf<String>()
        init { ShadowChoreographer.setPaused(true); ShadowChoreographer.setFrameDelay(Duration.ofMillis(16)) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        private val frames = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + frames)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val recomposition = scope.launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        private val view = ComposeView(activity.get()).also { it.setParentCompositionContext(recomposer) }
        private var time = 0L
        fun mount() {
            native.enter(); root = activity.get().window.decorView
            view.setContent { MaterialTheme {
                val state = native.controller.state
                MemoryReviewNamespacePage(state.ownerSnapshot("memoryReview"), state.ownerResponses("memoryReview"), state.ownerPending("memoryReview"), state.ownerErrors["memoryReview"],
                    { command, payload -> calls += command; native.controller.requestOwnerDomain("memoryReview", command, payload) }, native.controller::selectMemoryNamespace,
                    native.controller::openOwnerSource, {}, native.controller::dismissOwnerSource, {})
            } }
            activity.get().setContentView(view); frame()
        }
        fun frame() {
            native.settle()
            fun layout(value: View) { if (value is RootForTest) value.measureAndLayoutForTest(); if (value is ViewGroup) repeat(value.childCount) { layout(value.getChildAt(it)) } }
            repeat(7) {
                Snapshot.sendApplyNotifications(); time += 32_000_000L; frames.sendFrame(time); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                val windows = mutableListOf(activity.get().window.decorView)
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { windows += it }
                windows.forEach { window -> window.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4800, View.MeasureSpec.AT_MOST)); window.layout(0, 0, window.measuredWidth, window.measuredHeight); layout(window); window.invalidate() }
                Thread.sleep(5)
            }
        }
        fun button(label: String, modal: Boolean = false, partial: Boolean = false): SemanticsNode {
            val all = if (modal) dialog() else nodes()
            return all.firstOrNull { (if (partial) text(it).contains(label) else text(it) == label) && it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
                ?: error("Missing mounted action $label: ${all.map(::text)}")
        }
        fun click(label: String, modal: Boolean = false, partial: Boolean = false) {
            val node = button(label, modal, partial); assertNull(node.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frame()
        }
        fun edit(label: String, value: String) {
            val node = dialog().firstOrNull { it.config.getOrNull(SemanticsActions.SetText)?.action != null && contains(it, label) }
                ?: error("Missing actual field $label: ${dialog().map(::text)}")
            assertNull(node.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frame()
        }
        override fun close() { root = null; view.disposeComposition(); recomposer.close(); recomposition.cancel(); scope.cancel(); activity.pause().stop().destroy(); repeat(8) { shadowOf(Looper.getMainLooper()).runOneTask() }; native.close() }
    }

    @Test fun unknownSubjectRequiresExplicitSelectionAndAPlanSurvivesMountedReviewAcceptanceAndOldButtons() {
        Fixture().use { f ->
            val pending = MemoryAssertionReviewNativeTest.stage(f.native, "同名人物的远航计划，尚未实现")
            f.mount(); f.click(pending.getString("text"), partial = true); f.click("核对主体与关系…")
            assertTrue(dialog().any { text(it).contains("不会把第三人的描述默认归到主人") })
            assertNotNull(f.button("保存核对，继续待审", modal = true).config.getOrNull(SemanticsProperties.Disabled))
            f.click("主体：新人物", modal = true); f.edit("主体显示名", "同名人物"); f.edit("描述哪件事", "旅行计划"); f.edit("事实值或关系目标名称", "远航")
            f.click("计划或想象（未证实发生）", modal = true); f.click("核对时间（可选，未知可留空）", modal = true); f.edit("发生或计划时间", "1970-01-02")
            val old = f.button("保存核对，继续待审", modal = true).config[SemanticsActions.OnClick].action!!
            assertTrue(old.invoke()); assertTrue(old.invoke()); f.frame(); assertEquals(1, f.calls.count { it == "reviewCandidateAssertion" })
            val result = f.native.response("reviewCandidateAssertion"); assertTrue(result.toString(), result.getBoolean("ok"))
            val reviewed = result.getJSONObject("result").getJSONObject("candidate")
            assertEquals(pending.getLong("revision") + 1, reviewed.getLong("revision")); assertEquals("imagined", reviewed.getJSONObject("assertion").getString("kind")); assertNotEquals("owner", reviewed.getJSONObject("assertion").getString("subjectId"))
            assertTrue(nodes().any { text(it).contains("主体与关系已保存到待审候选") }); old.invoke(); f.frame(); assertEquals(1, f.calls.count { it == "reviewCandidateAssertion" })
            f.click("接受为普通记忆"); f.click("确认接受", modal = true)
            assertTrue(f.native.response("acceptCandidate").getBoolean("ok")); val saved = f.native.response("acceptCandidate").getJSONObject("result").getJSONObject("record")
            val cognition = f.native.result("cognitionProjection", JSONObject().put("subjectId", saved.getJSONObject("assertion").getString("subjectId")))
            assertEquals(0, cognition.getJSONArray("current").length()); assertEquals("imagined_not_real_world_fact", cognition.getJSONArray("history").getJSONObject(0).getString("assertionStatus"))
        }
    }

    @Test fun aSingleDonorCanBeReadExactlyAndCancelledOrCrossScopeDialogsCannotResend() {
        Fixture().use { f ->
            f.native.enter(); val donor = MemoryAssertionReviewNativeTest.person(f.native, "研发的小林喜欢咖啡", "小林", "咖啡")
            val pending = MemoryAssertionReviewNativeTest.stage(f.native, "研发的小林负责新项目")
            val project = f.native.create("另一个记忆范围")
            f.mount(); f.click(pending.getString("text"), partial = true); f.click("核对主体与关系…")
            fun select() { f.click("选择已有主体", modal = true); f.click(donor.getJSONObject("assertion").getString("subjectId"), modal = true, partial = true); f.edit("描述哪件事", "负责项目"); f.edit("事实值或关系目标名称", "新项目") }
            select(); val cancelled = f.button("保存核对，继续待审", modal = true).config[SemanticsActions.OnClick].action!!
            f.click("取消", modal = true); cancelled.invoke(); f.frame(); assertEquals(0, f.calls.count { it == "reviewCandidateAssertion" }); assertFalse(MemoryAssertionReviewNativeTest.candidate(f.native, pending).has("assertion"))
            f.click("核对主体与关系…"); select(); f.click("保存核对，继续待审", modal = true)
            val reviewed = f.native.response("reviewCandidateAssertion").getJSONObject("result").getJSONObject("candidate")
            assertEquals(1, reviewed.getJSONArray("recordRefs").length()); assertTrue(nodes().any { text(it).contains("身份依据 · 父记忆的精确版本") })
            f.click(donor.getString("id"), partial = true); assertEquals(1, f.calls.count { it == "getParentRecord" }); assertTrue(f.native.response("getParentRecord").getBoolean("ok"))
            assertTrue(dialog().any { text(it).contains(donor.getString("text")) }); f.click("返回候选", modal = true)
            f.click("核对主体与关系…"); val stale = f.button("保存核对，继续待审", modal = true).config[SemanticsActions.OnClick].action!!
            val calls = f.calls.count { it == "reviewCandidateAssertion" }; f.native.choose(project); f.frame(); stale.invoke(); f.frame(); assertEquals(calls, f.calls.count { it == "reviewCandidateAssertion" })
            f.native.choose(null); f.frame(); stale.invoke(); cancelled.invoke(); f.frame(); assertEquals(calls, f.calls.count { it == "reviewCandidateAssertion" })
            assertEquals(reviewed.getLong("revision"), MemoryAssertionReviewNativeTest.candidate(f.native, reviewed).getLong("revision"))
        }
    }
}
