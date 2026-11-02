package dev.pocketengine.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.pocketengine.EngineApplication
import dev.pocketengine.media.MediaController
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/**
 * Actual mounted product composables, rendered with Robolectric native graphics.
 * The two component fixtures are presentation data only, never TaskStore records.
 * No model, device, command, live preview service or external connection is started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = EngineApplication::class, qualifiers = "w412dp-h820dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PocketWorkroomCaptureTest {
    private val captures = JSONArray()
    private val output get() = File(System.getProperty("uiux.captureOutput", "build/uiux-captures"))

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner)

    private fun compose(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) repeat(view.childCount) {
            compose(view.getChildAt(it))?.let { result -> return result }
        }
        return null
    }

    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) { result += node; node.children.forEach(::visit) }
        WindowInspector.getGlobalWindowViews()
            .filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }
            .forEach { root -> compose(root)?.let {
                val owner = it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner
                visit(owner.rootSemanticsNode)
            } }
        return result
    }

    private fun text(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text)
        ?.joinToString(" ") { it.text }.orEmpty()

    private fun hasText(value: String): Boolean = nodes().any { text(it).contains(value) }

    // Same PAUSED-looper layout and frame approach as VoiceDraftComposerRenderTest.
    private fun frame(window: Window, width: Int, height: Int) {
        repeat(12) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            window.decorView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            window.decorView.layout(0, 0, width, height)
        }
    }

    private fun capture(window: Window, name: String, page: String, kind: String, width: Int, height: Int) {
        frame(window, width, height)
        val root = window.decorView
        assertNotNull("Mounted Compose content for $name", compose(root))
        assertEquals(width, root.width)
        assertEquals(height, root.height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            var copied = -1
            compose(root)?.javaClass?.getMethod("invalidateDescendants")?.invoke(compose(root))
            root.invalidate()
            PixelCopy.request(window, bitmap, { copied = it }, Handler(Looper.getMainLooper()))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("PixelCopy $name", PixelCopy.SUCCESS, copied)
            val colors = HashSet<Int>()
            var visibleSamples = 0
            for (y in 0 until height step 8) for (x in 0 until width step 8) {
                val color = bitmap.getPixel(x, y)
                if (color ushr 24 != 0) visibleSamples++
                colors += color
            }
            assertTrue("Nontransparent mounted image $name", visibleSamples > width * height / 128)
            assertTrue("Image contains rendered detail, not a blank/solid surface: $name", colors.size > 8)
            assertTrue(output.isDirectory || output.mkdirs())
            val file = File(output, "$name.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue("PNG has content $name", file.length() > 4096)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            assertEquals(width, bounds.outWidth)
            assertEquals(height, bounds.outHeight)
            captures.put(JSONObject().put("file", file.name).put("page", page).put("kind", kind)
                .put("widthPx", width).put("heightPx", height).put("viewportWidthDp", 412)
                .put("viewportHeightDp", 820).put("fixture", true))
            File(output, "captures.json").writeText(JSONObject()
                .put("testClass", javaClass.name).put("renderer", "Robolectric API35 NATIVE PAUSED")
                .put("identity", "isolated examples; not a real Android device or execution result")
                .put("sourceRevision", System.getenv("GITHUB_SHA").orEmpty())
                .put("captures", captures).toString(2))
        } finally { bitmap.recycle() }
    }

    /** Only the fixture label/spacing wraps these unchanged, real production components. */
    @Composable
    private fun ComponentFrame(title: String, content: @Composable () -> Unit) {
        PocketWorldTheme {
            Column(Modifier.fillMaxSize().background(PageSurface).padding(top = 24.dp)) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Robolectric 隔离组件示例 · 非真实执行", style = MaterialTheme.typography.bodySmall,
                        color = SecondaryInk)
                }
                Spacer(Modifier.height(16.dp))
                content()
            }
        }
    }

    @Test
    fun captureCurrentNativeWorkroomAndRepresentativeComponents() {
        val provider = object : java.security.Provider("PocketWorkroomCaptureKeyStore", 1.0,
            "Explicit test-only AndroidKeyStore") {
            init { put("KeyStore.AndroidKeyStore",
                dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
        }
        java.security.Security.addProvider(provider)
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val owner = Robolectric.buildActivity(ComposeActivity::class.java).setup().visible()
        val screen = owner.get()
        val controller = field(screen, "controller") as PocketWorldController
        val width = (412 * screen.resources.displayMetrics.density).toInt()
        val height = (820 * screen.resources.displayMetrics.density).toInt()
        try {
            frame(screen.window, width, height)
            assertTrue("Actual App empty-state content is mounted", hasText("想做什么？"))
            assertEquals("Fixture must not start a task", 0, app.tasks.list().length())
            capture(screen.window, "01-workroom-empty", "主 Agent 工作入口空态", "PocketWorldApp page", width, height)

            val allWork = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == "all_work" &&
                    it.config.getOrNull(SemanticsActions.OnClick)?.action != null
            } ?: nodes().firstOrNull {
                text(it) == "全部工作" && it.config.getOrNull(SemanticsActions.OnClick)?.action != null
            } ?: error("Actual native all_work action is not mounted")
            assertTrue(allWork.config[SemanticsActions.OnClick].action!!.invoke())
            frame(screen.window, width, height)
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("Actual ModalBottomSheet dialog window", sheet)
            assertTrue("Actual work-switcher dialog is showing", sheet!!.isShowing)
            val sheetWindow = sheet.window ?: error("Work-switcher dialog has no window")
            frame(sheetWindow, width, height)
            assertTrue("Actual work-switcher entries are mounted", hasText("继续当前工作"))
            capture(sheetWindow, "02-all-work-sheet", "全部工作切换层", "PocketWorldApp ModalBottomSheet page", width, height)
            sheet.dismiss()
            frame(screen.window, width, height)

            val steps = listOf(
                NickStepData("capture-structure", "整理周报结构", NickStepState.COMPLETE),
                NickStepData("capture-build", "在这台手机构建页面", NickStepState.RUNNING,
                    children = listOf(
                        NickSubstepData("capture-write", "写入周报页面", NickStepState.COMPLETE),
                        NickSubstepData("capture-check", "运行构建检查", NickStepState.RUNNING),
                        NickSubstepData("capture-preview", "检查预览入口", NickStepState.PENDING)
                    )),
                NickStepData("capture-delivery", "交付可打开的预览", NickStepState.PENDING)
            )
            screen.setContent {
                ComponentFrame("周报面板 · 当前执行") {
                    NickExecutionTree(steps, Modifier.padding(horizontal = 20.dp))
                }
            }
            frame(screen.window, width, height)
            assertTrue("Actual Nick children are mounted", hasText("运行构建检查"))
            assertTrue(hasText("检查预览入口"))
            capture(screen.window, "03-nick-running-children", "当前执行与子步骤", "NickExecutionTree component", width, height)

            fun task(id: String, goal: String, status: String) = UiTask(
                id, goal, status, "仅用于原生组件排版的隔离示例，没有执行记录。", "background", 0L,
                JSONObject().put("taskId", id).put("goal", goal).put("status", status)
            )
            val fixture = PocketUiState(tasks = listOf(
                task("capture-approval", "在发送周报前审阅操作范围", "waiting_approval"),
                task("capture-unknown", "核对远端回执是否包含本次结果", "unknown"),
                task("capture-running", "整理周报面板的风险与下一步", "running"),
                task("capture-stopped", "已停止的资料整理示例", "stopped")
            ))
            val opened = mutableListOf<String>()
            screen.setContent {
                ComponentFrame("任务 · 跨工作待处理") {
                    TaskAuditList(fixture, controller, { opened += it.id }, { opened += "search" })
                }
            }
            frame(screen.window, width, height)
            assertTrue("Actual task list contains the fixture", hasText("在发送周报前审阅操作范围"))
            assertTrue("Actual task grouping is mounted", hasText("待我处理"))
            capture(screen.window, "04-task-audit-list", "待处理与执行任务列表", "TaskAuditList component", width, height)
            assertTrue(opened.isEmpty())
            assertEquals("Display fixtures never enter TaskStore", 0, app.tasks.list().length())
            assertEquals(4, captures.length())
        } finally {
            // The original Activity detaches Controller io/controls/realtime workers and media.
            owner.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            (field(app, "capabilityWorkers") as ExecutorService).let {
                it.shutdownNow(); it.awaitTermination(2, TimeUnit.SECONDS)
            }
            app.features.close()
            (field(app.tasks, "db") as android.database.sqlite.SQLiteDatabase).close()
            java.security.Security.removeProvider(provider.name)
            for (name in listOf("realtimeAudioOwner", "audioReleaseUnknown")) {
                MediaController::class.java.getDeclaredField(name).also { it.isAccessible = true }
                    .set(null, if (name == "audioReleaseUnknown") false else null)
            }
        }
    }
}
