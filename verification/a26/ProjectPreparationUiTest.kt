package dev.pocketengine.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import dev.pocketengine.EngineApplication
import java.time.Duration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Real production Compose leaf; readiness values are explicit UI fixtures, never install evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    qualifiers = "w412dp-h892dp-mdpi", shadows = [OfficialAarResources::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class ProjectPreparationUiTest {
    @Test fun unpreparedPhoneUsesExplicitPreparationAvailabilityAndActualObservedPhase() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val owner = Robolectric.buildActivity(ComposeActivity::class.java)
        owner.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar); owner.setup()
        val runtime = mutableStateOf(JSONObject().put("canExecute", false).put("preparationAvailable", true)
            .put("preparation", JSONObject().put("phase", "verifying_archive").put("completedBytes", 4096).put("totalBytes", 4096)))
        var requests = 0
        try {
            val activity = owner.get()
            activity.setContent { NativeProjectToolPreparation(runtime.value, null, true, { requests++ }, {}) }
            pump(activity.window.decorView)
            val button = tagged(activity.window.decorView, "project_prepare_phone_tools")
            assertFalse("canExecute=false must not disable an explicitly available preparation", button.config.contains(SemanticsProperties.Disabled))
            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke()); assertEquals(1, requests)
            assertTrue(nodes(activity.window.decorView).any { nodeText(it).contains("verifying_archive") })
            assertTrue("Full download still has a preparation action; no runnable state is inferred", nodes(activity.window.decorView).any { nodeText(it).contains("准备手机开发工具") })
            runtime.value = JSONObject(runtime.value.toString()).also { it.remove("preparationAvailable") }
            pump(activity.window.decorView)
            assertTrue("Availability must be explicit", tagged(activity.window.decorView, "project_prepare_phone_tools").config.contains(SemanticsProperties.Disabled))
            assertEquals(1, requests)
        } finally { owner.pause().stop().destroy(); app.onTerminate(); app.features.close() }
    }

    private fun pump(view: View) { repeat(10) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        view.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY)); view.layout(0, 0, 412, 892)
        fun layout(current: View) { if (current is RootForTest) current.measureAndLayoutForTest(); if (current is ViewGroup) for (index in 0 until current.childCount) layout(current.getChildAt(index)) }
        layout(view)
    } }
    private fun nodes(view: View): List<SemanticsNode> = when (view) {
        is ViewRootForTest -> view.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = true).orEmpty()
        is ViewGroup -> (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun tagged(view: View, tag: String): SemanticsNode = nodes(view).first { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag }
    private fun nodeText(node: SemanticsNode): String = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString("") { it.text } else ""
}
