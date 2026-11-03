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
import androidx.compose.ui.semantics.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/** Actual original tool-card component; synthetic projection checks navigation semantics,
 * not a live provider, full App, physical phone or visual/pixel acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h1000dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TaskJobMountedTest {
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
    @Test fun unknownJobNavigatesOnlyToOriginalTaskAndNeverOffersAReplacementExecutionOrStop() {
        val row = JSONObject().put("jobKey", "remote:original-task:old-run:2:original-operation").put("taskId", "original-task").put("runId", "old-run").put("epoch", 2).put("operationId", "original-operation").put("domain", "remote").put("state", "remote_job_unknown").put("observingOnly", true).put("executionAuthorized", false).put("statusSource", "original_operation_journal").put("status", "available").put("projectLogCandidate", false)
        val record = TaskExecutionPresentation.records(JSONObject().put("entries", JSONArray().put(row)), "jobs").single()
        val opened = mutableListOf<String>(); val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        fun frame() { repeat(48) { shadowOf(Looper.getMainLooper()).runOneTask(); WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach { view -> view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST)); view.layout(0, 0, view.measuredWidth, view.measuredHeight) } } }
        try {
            owner.get().setContent { MaterialTheme { Column { TaskJobRecord(record) { opened += it } } } }; frame()
            assertTrue(nodes().any { text(it).contains("原结果仍待核对") }); assertTrue(nodes().any { text(it).contains("代次 2") })
            assertTrue(opened.isEmpty()); assertFalse(nodes().any { text(it) in setOf("停止作业", "重试", "重新执行", "批准") })
            val button = nodes().first { text(it) == "回到原任务" && it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke()); frame(); assertEquals(listOf("original-task"), opened)
        } finally { owner.pause().stop().destroy(); repeat(8) { shadowOf(Looper.getMainLooper()).runOneTask() } }
    }
}
