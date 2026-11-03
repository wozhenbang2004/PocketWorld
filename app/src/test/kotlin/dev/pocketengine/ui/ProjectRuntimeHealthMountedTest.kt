package dev.pocketengine.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.pocketengine.features.local.runtime.RuntimeHealthTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/** Actual existing workbench component and production health DTO, with synthetic
 * catalog/seal inputs. No install, probe, repair, device pixels or OEM claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h1000dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectRuntimeHealthMountedTest {
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
    private inner class Fixture(runtime: JSONObject, enabled: Boolean = true) : AutoCloseable {
        var starts = 0
        var refreshes = 0
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        init { try { owner.get().setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { NativeProjectToolPreparation(runtime, null, enabled, { starts++ }, { refreshes++ }) } } }; frame() } catch (failure: Throwable) { close(); throw failure } }
        fun frame() { repeat(48) { shadowOf(Looper.getMainLooper()).runOneTask(); WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach { view -> view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST)); view.layout(0, 0, view.measuredWidth, view.measuredHeight) } } }
        fun click(label: String) { val node = nodes().first { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null }; assertNull(node.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frame() }
        fun allText() = nodes().joinToString("\n", transform = ::text)
        override fun close() { owner.pause().stop().destroy(); repeat(8) { shadowOf(Looper.getMainLooper()).runOneTask() } }
    }
    @Test fun installedRuntimeStillOffersItsOriginalReadOnlyRecoveryPreview() {
        val original = RuntimeHealthTest.ownerPreviewForUi(true, "ready", false)
        val before = original.toString()
        Fixture(original).use { f ->
            assertTrue(f.allText().contains("手机开发工具可用")); assertFalse(f.allText().contains("候选发布目录"))
            f.click("详情")
            assertTrue(f.allText().contains("当前运行代次：" + "d".repeat(64)))
            assertTrue(f.allText().contains("候选发布目录：")); assertTrue(f.allText().contains("尚不是新运行代次"))
            assertTrue(f.allText().contains("HOME、项目文件、原执行回执"))
            assertTrue(f.allText().contains("原作业未结束或释放仍未确认时"))
            assertTrue(f.allText().contains("不恢复旧授权、不重放操作"))
            assertFalse(nodes().any { text(it) == "准备工具" })
            assertEquals(0, f.starts); assertEquals(0, f.refreshes)
            f.click("刷新环境观察"); assertEquals(1, f.refreshes); assertEquals(0, f.starts)
            f.click("收起"); assertEquals(before, original.toString())
        }
    }
    @Test fun currentFailureShowsItsCategoryWithoutGainingPreparationPermission() {
        Fixture(RuntimeHealthTest.ownerPreviewForUi(false, "failed", false), false).use { f ->
            f.click("详情")
            assertTrue(f.allText().contains("准备问题：网络")); assertTrue(f.allText().contains("runtime_download_http_503"))
            assertTrue(f.allText().contains("请先处理原项目任务中的操作或审批"))
            val prepare = nodes().first { text(it) == "准备工具" && it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            assertNotNull(prepare.config.getOrNull(SemanticsProperties.Disabled))
            assertFalse(f.allText().contains("private fixture URL")); assertEquals(0, f.starts)
        }
    }
    @Test fun supersededFailureIsNotPresentedAsTheCurrentGenerationFault() {
        Fixture(RuntimeHealthTest.ownerPreviewForUi(true, "failed", true)).use { f ->
            f.click("详情")
            assertTrue(f.allText().contains("先前准备观察已过期"))
            assertFalse(f.allText().contains("准备问题：网络")); assertFalse(f.allText().contains("runtime_download_http_503"))
            assertFalse(f.allText().contains("开发工具需要处理")); assertEquals(0, f.starts)
        }
    }
    @Test fun missingOrMalformedPreviewFactsCannotInventPreservationOrActivation() {
        assertNull(ProjectRuntimeHealth.original(JSONObject().put("health", JSONObject().put("format", "future"))))
        val health = RuntimeHealthTest.ownerPreviewForUi(true, "ready", false).getJSONObject("health")
        val preview = health.getJSONObject("recoveryPreview")
        preview.put("homeRetained", "true").put("projectFilesRetained", JSONObject.NULL).put("originalReceiptsRetained", false)
        preview.put("candidateCatalogIdentity", "unknown").put("activationRequiresOriginalJobsIdle", "true")
        val text = ProjectRuntimeHealth.details(health).joinToString("\n")
        assertTrue(text.contains("候选发布目录：尚不可核对"))
        assertFalse(text.contains("原恢复范围保留：")); assertFalse(text.contains("原作业未结束或释放仍未确认时"))
    }
}
