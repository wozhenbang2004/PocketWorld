package dev.pocketengine.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.MutableState
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.local.preview.ProjectBundleWebView
import dev.pocketengine.features.local.preview.ProjectPreviewBundle
import java.nio.file.Files
import java.time.Duration
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Actual production Compose/AndroidView lifecycle, with UI-only selection fixtures.
 * No model, approval, native task, JavaScript execution or device acceptance is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    qualifiers = "w412dp-h892dp-mdpi", shadows = [OfficialAarResources::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectPreviewIdentityTest {
    @Test fun replacingOpenPreviewRecreatesViewAndCloseStopReleaseIt() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val owner = Robolectric.buildActivity(ComposeActivity::class.java)
        owner.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        owner.setup()
        val activity = owner.get()
        val controller = ComposeActivity::class.java.getDeclaredField("controller")
            .apply { isAccessible = true }.get(activity) as PocketWorldController
        val root = Files.createTempDirectory("a26-preview-identity-")
        try {
            val checkout = JSONObject().put("id", "ui-checkout").put("projectId", "ui-project")
            activity.setContent { Column { NativeProjectAgentWorkbench(checkout, null, selectedState(), controller) } }
            pump(activity.window.decorView)
            Files.writeString(root.resolve("index.html"), "<!doctype html><h1>Preview A</h1>")
            val a = ProjectPreviewBundle.capture(root.toFile(), "ui-checkout", "ui-project", "a".repeat(64), "index.html")
            Files.writeString(root.resolve("index.html"), "<!doctype html><h1>Preview B</h1>")
            val b = ProjectPreviewBundle.capture(root.toFile(), "ui-checkout", "ui-project", "b".repeat(64), "index.html")
            select(controller, a)
            pump(activity.window.decorView)
            val first = onlyPreview(activity.window.decorView)
            assertEquals(a.entryUrl, shadowOf(first).lastLoadedUrl)
            assertFalse(released(first))

            // No intervening null/close: this is the original defect's precise transition.
            select(controller, b)
            pump(activity.window.decorView)
            val second = onlyPreview(activity.window.decorView)
            assertNotSame("A different bundle must get a fresh AndroidView factory", first, second)
            assertTrue("The original preview is released", released(first))
            assertFalse("The new preview remains usable", released(second))
            assertEquals(b.entryUrl, shadowOf(second).lastLoadedUrl)

            controller.closeProjectPreview()
            pump(activity.window.decorView)
            assertTrue(released(second))
            assertTrue(previews(activity.window.decorView).isEmpty())
            select(controller, b)
            pump(activity.window.decorView)
            val reopened = onlyPreview(activity.window.decorView)
            assertNotSame(second, reopened)
            assertEquals(b.entryUrl, shadowOf(reopened).lastLoadedUrl)
            owner.pause().stop()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("ON_STOP still destroys the current preview", released(reopened))
            assertNull(controller.projectPreview)
        } finally {
            owner.destroy()
            app.onTerminate()
            app.features.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun anotherTaskCannotMountItsPreviewUnderThisSelectedProjectTask() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val owner = Robolectric.buildActivity(ComposeActivity::class.java)
        owner.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        owner.setup()
        val activity = owner.get()
        val controller = ComposeActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }.get(activity) as PocketWorldController
        val root = Files.createTempDirectory("a26-preview-task-binding-")
        try {
            val checkout = JSONObject().put("id", "ui-checkout").put("projectId", "ui-project")
            activity.setContent { Column { NativeProjectAgentWorkbench(checkout, null, selectedState(), controller) } }
            pump(activity.window.decorView)
            Files.writeString(root.resolve("index.html"), "<!doctype html><h1>Another task's resource</h1>")
            val bundle = ProjectPreviewBundle.capture(root.toFile(), "ui-checkout", "ui-project", "a".repeat(64), "index.html")
            setState(controller, "projectPreview", bundle)
            setState(controller, "projectPreviewTaskId", "another-ui-task")
            pump(activity.window.decorView)
            assertTrue("The same checkout alone must not mount a different task's preview", previews(activity.window.decorView).isEmpty())
            select(controller, bundle)
            pump(activity.window.decorView)
            assertEquals(bundle.entryUrl, shadowOf(onlyPreview(activity.window.decorView)).lastLoadedUrl)
        } finally {
            owner.pause().stop().destroy(); app.onTerminate(); app.features.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    /** UI selection only; controller admission and the task ledger are tested separately. */
    private fun selectedState(): PocketUiState = PocketUiState(tasks = listOf(taskFromJson(JSONObject()
        .put("id", "ui-only-task").put("kind", "assistant").put("actorId", "owner").put("spaceId", "private")
        .put("status", "failed").put("goal", "原项目预览测试").put("operations", JSONArray())
        .put("developmentContext", JSONObject().put("checkoutId", "ui-checkout").put("projectId", "ui-project").put("runtime", "managed_process_v1")))))

    /** Explicitly UI state injection: not a forged production project/task admission. */
    private fun select(controller: PocketWorldController, preview: ProjectPreviewBundle.Verified) {
        setState(controller, "projectPreview", preview)
        setState(controller, "projectPreviewTaskId", "ui-only-task")
    }
    @Suppress("UNCHECKED_CAST")
    private fun setState(controller: PocketWorldController, name: String, value: Any?) {
        val field = PocketWorldController::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(controller) as MutableState<Any?>).value = value
    }
    private fun released(view: ProjectBundleWebView) = ProjectBundleWebView::class.java.getDeclaredField("released")
        .apply { isAccessible = true }.getBoolean(view)
    private fun previews(view: View): List<ProjectBundleWebView> = when (view) {
        is ProjectBundleWebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { previews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun onlyPreview(root: View): ProjectBundleWebView {
        val views = previews(root)
        assertEquals("Exactly one current native preview view", 1, views.size)
        return views.single()
    }
    private fun pump(decor: View) {
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            decor.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 412, 892)
        }
    }
}
