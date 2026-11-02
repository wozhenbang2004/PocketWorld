package dev.pocketengine.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.channels.ChannelTaskHostTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/** Formal TaskSheet/Controller semantics. Child data is an explicit native UI projection fixture;
 * this does not claim a real team run or replace the coordinator's generation/approval gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, qualifiers = "w393dp-h852dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class TeamChildTaskSheetUiTest {
    @Before fun keys() { java.security.Security.insertProviderAt(object : java.security.Provider("TeamChildUiKeys", 1.0, "Synthetic native UI keystore") { init { put("KeyStore.AndroidKeyStore", ChannelTaskHostTest.FixtureKeyStore::class.java.name) } }, 1) }
    @After fun removeKeys() { java.security.Security.removeProvider("TeamChildUiKeys") }
    private fun root(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) root(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun roots(activity: ComposeActivity) = listOf(activity.window.decorView) + ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }
    private fun nodes(activity: ComposeActivity) = roots(activity).flatMap { root(it)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = true).orEmpty() }
    private fun frame(activity: ComposeActivity) {
        fun layout(view: View) { if (view is RootForTest) view.measureAndLayoutForTest(); if (view is ViewGroup) for (i in 0 until view.childCount) layout(view.getChildAt(i)) }
        repeat(5) { Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)); roots(activity).forEach(::layout) }
    }
    private fun child(kind: String, status: String): UiTask {
        val key = if (kind == "team_research") "researchTaskId" else "assignmentId"
        val identity = JSONObject().put("teamRunId", "original-team-run").put(key, "original-child").put("memberId", "original-member")
        return taskFromJson(JSONObject().put("id", "original-child").put("kind", "assistant").put("goal", "原分工与真实记录").put("status", status).put("actorId", "owner").put("spaceId", "private").put("parentTaskId", "original-parent").put("origin", JSONObject(identity.toString()).put("kind", kind)).put(if (kind == "team_research") "teamResearch" else "teamAssignment", identity))
    }
    @Test fun nativeChildDetailsHidePrivateContinuationAndReturnExactLocatorWithoutExecuting() {
        val pending = Robolectric.buildActivity(ComposeActivity::class.java)
        pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val lifecycle = pending.setup().visible().windowFocusChanged(true)
        try {
            val activity = lifecycle.get()
            val field = ComposeActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
            val controller = field.get(activity) as PocketWorldController
            listOf("team_assignment", "team_review", "team_integration", "team_research").forEachIndexed { index, kind ->
                val task = child(kind, listOf("awaiting_user", "paused", "failed")[index % 3])
                var closed = false
                activity.setContent { PocketWorldTheme { TaskSheet(task, false, controller) { closed = true } } }
                frame(activity)
                val all = nodes(activity)
                val labels = all.filter { it.config.contains(SemanticsProperties.Text) }.flatMap { it.config[SemanticsProperties.Text].map { text -> text.text } }
                assertFalse(labels.contains("继续此任务"))
                assertFalse(labels.contains("网页与研究工作区"))
                assertFalse(labels.contains("补充要求"))
                val back = all.single { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "task_return_team_child" }
                assertTrue(back.config[SemanticsActions.OnClick].action!!.invoke())
                assertTrue(closed)
                assertEquals("original-team-run", controller.state.teamRunId)
                assertEquals("original-child", controller.state.teamAssignmentFocus!!.getString("assignmentId"))
                assertEquals("original-member", controller.state.teamAssignmentFocus!!.getString("memberId"))
                assertEquals("original-parent", controller.state.teamAssignmentFocus!!.getString("parentTaskId"))
                controller.closeTeam()
            }
        } finally { lifecycle.pause().stop().destroy() }
    }
}
