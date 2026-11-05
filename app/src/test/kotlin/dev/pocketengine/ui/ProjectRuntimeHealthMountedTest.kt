package dev.pocketengine.ui

import android.app.Application
import dev.pocketengine.features.local.runtime.RuntimeHealthTest
import dev.pocketengine.ui.web.PocketWebProjectRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Original native runtime health DTO/presentation now consumed by the single React details section.
 * Synthetic catalog/seal inputs remain metadata only: no installer/probe/repair/device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectRuntimeHealthMountedTest {
    private fun text(view: JSONObject): String = view.getJSONArray("details").let { rows -> (0 until rows.length()).joinToString("\n") { rows.getString(it) } }
    @Test fun installedRuntimeStillOffersItsOriginalReadOnlyRecoveryPreview() {
        val original = RuntimeHealthTest.ownerPreviewForUi(true, "ready", false); val before = original.toString()
        val view = ProjectRuntimeHealth.presentation(original)
        assertEquals("手机开发工具可用", view.getString("headline")); assertFalse(view.getString("caption").contains("候选发布目录"))
        val details = text(view)
        assertTrue(details.contains("当前运行代次：" + "d".repeat(64))); assertTrue(details.contains("候选发布目录：")); assertTrue(details.contains("尚不是新运行代次"))
        assertTrue(details.contains("HOME、项目文件、原执行回执")); assertTrue(details.contains("原作业未结束或释放仍未确认时")); assertTrue(details.contains("不恢复旧授权、不重放操作"))
        assertTrue(view.getBoolean("readOnly")); assertFalse(view.getBoolean("preparationEnabled")); assertEquals(setOf("projectRuntime.read"), PocketWebProjectRuntime.COMMANDS)
        // Repeated React refresh only reprojects the original observation; no preparation callback exists.
        assertEquals(view.toString(), ProjectRuntimeHealth.presentation(original).toString()); assertEquals(before, original.toString())
    }
    @Test fun currentFailureShowsItsCategoryWithoutGainingPreparationPermission() {
        val view = ProjectRuntimeHealth.presentation(RuntimeHealthTest.ownerPreviewForUi(false, "failed", false), preparationEnabled = false)
        assertTrue(text(view).contains("准备问题：网络")); assertTrue(text(view).contains("runtime_download_http_503"))
        assertTrue(view.getString("admissionMessage").contains("请先处理原项目任务中的操作或审批")); assertFalse(view.getBoolean("preparationEnabled")); assertTrue(view.getBoolean("preparationRequiresOriginalReview"))
        assertFalse(view.toString().contains("private fixture URL")); assertTrue(view.getBoolean("readOnly"))
    }
    @Test fun supersededFailureIsNotPresentedAsTheCurrentGenerationFault() {
        val view = ProjectRuntimeHealth.presentation(RuntimeHealthTest.ownerPreviewForUi(true, "failed", true))
        assertTrue(text(view).contains("先前准备观察已过期")); assertFalse(text(view).contains("准备问题：网络")); assertFalse(view.toString().contains("runtime_download_http_503"))
        assertFalse(view.getString("headline").contains("开发工具需要处理")); assertTrue(view.isNull("phase")); assertFalse(view.getBoolean("preparationEnabled"))
    }
    @Test fun missingOrMalformedPreviewFactsCannotInventPreservationOrActivation() {
        assertNull(ProjectRuntimeHealth.original(JSONObject().put("health", JSONObject().put("format", "future"))))
        val runtime = RuntimeHealthTest.ownerPreviewForUi(true, "ready", false); val preview = runtime.getJSONObject("health").getJSONObject("recoveryPreview")
        preview.put("homeRetained", "true").put("projectFilesRetained", JSONObject.NULL).put("originalReceiptsRetained", false)
        preview.put("candidateCatalogIdentity", "unknown").put("activationRequiresOriginalJobsIdle", "true")
        val details = text(ProjectRuntimeHealth.presentation(runtime))
        assertTrue(details.contains("候选发布目录：尚不可核对")); assertFalse(details.contains("原恢复范围保留：")); assertFalse(details.contains("原作业未结束或释放仍未确认时"))
        runtime.put("canExecute", "true").put("preparationAvailable", "true")
        val malformed = ProjectRuntimeHealth.presentation(runtime, preparationEnabled = true)
        assertFalse(malformed.getBoolean("executable")); assertFalse(malformed.getBoolean("preparationEnabled"))
    }
}
