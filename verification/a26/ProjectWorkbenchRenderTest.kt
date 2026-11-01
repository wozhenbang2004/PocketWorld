package dev.pocketengine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import dev.pocketengine.EngineApplication
import java.io.File
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
import org.robolectric.annotation.GraphicsMode

/** Actual ComposeActivity/Controller and native owner project; a Robolectric viewport only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, qualifiers = "w412dp-h892dp-mdpi", shadows = [OfficialAarResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectWorkbenchRenderTest {
    @Test fun currentNativeProjectFirstEntryRendersAtPhoneWidth() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val selected = app.dispatchOwnerAction("createDevelopmentProject", JSONObject().put("name", "多文件网页 · 第一次手机项目").put("template", "web"))
        val owner = Robolectric.buildActivity(ComposeActivity::class.java)
        // Config.NONE omits APK resources; this is AppTheme's actual framework parent.
        owner.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        owner.setup()
        try {
            val activity = owner.get()
            val field = ComposeActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
            val controller = field.get(activity) as PocketWorldController
            controller.returnToDevelopmentProject(selected.getString("projectId"))
            val deadline = System.nanoTime() + 8_000_000_000L
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
                if (controller.developmentCheckoutId == selected.getString("id") && controller.state.moduleResults["local.project.inspect"] != null) break
                Thread.sleep(20)
            }
            assertEquals(selected.getString("id"), controller.developmentCheckoutId)
            assertNotNull(controller.state.moduleResults["local.project.inspect"])
            repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)) }
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 412, 892)
            val bitmap = Bitmap.createBitmap(412, 892, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val output = File(System.getProperty("a26.screenshotDir"), "project-first-entry-412x892.png")
                output.parentFile.mkdirs()
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                println("A26D currentComposeActivityRobolectricViewport=412x892; nativeProjectSelected=true; screenshot=" + output.name + "; AndroidDeviceAndInteractivePreviewJS=false")
            } finally { bitmap.recycle() }
        } finally {
            owner.pause().stop().destroy()
            app.onTerminate()
            app.features.close()
        }
    }
}
