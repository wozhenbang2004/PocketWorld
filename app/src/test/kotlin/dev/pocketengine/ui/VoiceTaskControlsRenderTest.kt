package dev.pocketengine.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Duration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

class VoiceTaskRenderActivity : ComponentActivity()

/** Actual production leaf in a native rendering fixture; callbacks are not task/provider acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceTaskControlsRenderTest {
    private class Surface(status: String) : AutoCloseable {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val opened = mutableListOf<String>()
        val original = JSONObject().put("taskId", "11111111-1111-4111-8111-111111111111").put("runId", "22222222-2222-4222-8222-222222222222").put("epoch", 1).put("revision", 4).put("status", status).put("summary", "原任务已经整理了明天的安排。请打开原任务查看结果并核验；这里的记录不代表已发送消息。")
        val view = JSONObject().put("available", true).put("bindingId", "view-1").put("viewToken", "version-4").put("cancelToken", "run-1-cancel").put("goal", "整理明天的安排，并在发送前让我确认").put("message", "以原任务当前记录为准；停止说话与取消任务分别控制").put("task", original).put("canCancel", status == "running").put("readout", JSONObject())
        val state = mutableStateOf(JSONObject().put("voiceTask", view).put("session", JSONObject().put("state", "disconnected")))
        val owner = Robolectric.buildActivity(VoiceTaskRenderActivity::class.java).setup().visible()
        val activity = owner.get()
        private val width = (360 * activity.resources.displayMetrics.density).toInt()
        private val height = (800 * activity.resources.displayMetrics.density).toInt()
        init {
            activity.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Ink, onPrimary = Color.White, secondary = Ink, background = Color.White, surface = Color.White, surfaceVariant = Soft, onSurface = Ink, onSurfaceVariant = SecondaryInk, outline = Color(0xffcbd0d5), outlineVariant = Line, error = Danger), shapes = Shapes(small = RoundedCornerShape(12.dp), medium = Rounded, large = RoundedCornerShape(24.dp))) { Column(Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(16.dp)) {
                VoiceTaskControls(state.value, { name, payload -> calls += name to JSONObject(payload.toString()) }, { opened += it })
            } } }
            frame()
        }
        fun frame() { repeat(12) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)); activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));activity.window.decorView.layout(0, 0, width, height) } }
        private fun compose(view: View): View? { if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view;if (view is ViewGroup) repeat(view.childCount) { compose(view.getChildAt(it))?.let { result -> return result } };return null }
        fun nodes(): List<SemanticsNode> {
            val result = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) { result += node;node.children.forEach(::visit) }
            WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }.forEach { root -> compose(root)?.let { visit((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode) } }
            return result
        }
        private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        fun has(label: String) = nodes().any { text(it).contains(label) }
        fun click(label: String) { val node = nodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null } ?: error("Missing native action $label; nodes=${nodes().map(::text)}");assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());frame() }
        fun save(name: String) {
            frame();val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);var copied = -1
            compose(activity.window.decorView)?.let { it.javaClass.getMethod("invalidateDescendants").invoke(it) };activity.window.decorView.invalidate()
            PixelCopy.request(activity.window, bitmap, { copied = it }, Handler(Looper.getMainLooper()));shadowOf(Looper.getMainLooper()).idle();assertEquals(PixelCopy.SUCCESS, copied)
            val directory = File(System.getProperty("b5.renderOutput", "build/voice-task-render-fixture"));directory.mkdirs();File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) };bitmap.recycle()
        }
        override fun close() { owner.pause().stop().destroy() }
    }
    @Test fun completedOriginalResultRemainsVisibleAfterHangupAndStopOnlyTargetsReadout() {
        Surface("completed").use { ui ->
            assertTrue(ui.has("整理明天的安排"));assertTrue(ui.has("原任务记录为完成"));ui.click("查看原任务与产物");assertEquals(listOf(ui.original.getString("taskId")), ui.opened)
            ui.click("朗读原任务记录");assertEquals("readVoiceTask", ui.calls.last().first);assertEquals("version-4", ui.calls.last().second.getString("viewToken"));ui.save("completed-original-after-hangup")
            val next = JSONObject(ui.state.value.toString());next.getJSONObject("voiceTask").put("readout", JSONObject().put("active", true).put("state", "speaking").put("readoutId", "read-1"));ui.state.value = next;ui.frame();ui.click("停止说话，任务继续")
            assertEquals("interruptOutput", ui.calls.last().first);assertEquals("read-1", ui.calls.last().second.getString("readoutId"));assertFalse(ui.calls.any { it.first == "cancelVoiceTask" })
        }
    }
    @Test fun taskCancellationRequiresNativeReviewAndRetainsExactVisibleVersion() {
        Surface("running").use { ui ->
            ui.click("取消这项任务");assertTrue(ui.has("取消当前这一轮任务？"));assertTrue(ui.calls.isEmpty());ui.save("cancel-original-native-review")
            ui.click("取消当前执行");assertEquals("cancelVoiceTask", ui.calls.single().first);assertEquals("view-1", ui.calls.single().second.getString("bindingId"));assertEquals("run-1-cancel", ui.calls.single().second.getString("viewToken"))
        }
    }
}
