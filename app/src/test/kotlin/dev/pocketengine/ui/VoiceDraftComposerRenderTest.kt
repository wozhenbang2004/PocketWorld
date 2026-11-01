package dev.pocketengine.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.pocketengine.EngineApplication
import dev.pocketengine.media.MediaController
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Actual mounted Activity and original Composer. System-ASR callbacks are explicit SDK shadows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = EngineApplication::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceDraftComposerRenderTest {
    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner)
    private fun compose(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) repeat(view.childCount) { compose(view.getChildAt(it))?.let { result -> return result } }
        return null
    }
    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) { result += node;node.children.forEach(::visit) }
        WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }.forEach { root -> compose(root)?.let { visit((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode) } }
        return result
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    private fun frame(window: Window, width: Int, height: Int) { repeat(12) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));window.decorView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));window.decorView.layout(0, 0, width, height) } }
    private fun save(window: Window, name: String) {
        val root = window.decorView;val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888);var copied = -1
        compose(root)?.javaClass?.getMethod("invalidateDescendants")?.invoke(compose(root));root.invalidate()
        PixelCopy.request(window, bitmap, { copied = it }, Handler(Looper.getMainLooper()));shadowOf(Looper.getMainLooper()).idle();assertEquals(PixelCopy.SUCCESS, copied)
        val directory = File(System.getProperty("b5.inputRenderOutput", "build/voice-input-render"));directory.mkdirs();File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) };bitmap.recycle()
    }
    @Test fun newConversationReleasesStagedMicFenceAndKeepsUnsentEditorText() {
        val provider = object : java.security.Provider("VoiceDraftSwitchRenderKeyStore", 1.0, "Explicit test-only AndroidKeyStore") { init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) } }
        java.security.Security.addProvider(provider)
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val service = ResolveInfo().also { it.serviceInfo = ServiceInfo().also { info -> info.packageName = "fixture.system.speech";info.name = "Recognition" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), service);shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val owner = Robolectric.buildActivity(ComposeActivity::class.java).setup().visible();val screen = owner.get();val controller = field(screen, "controller") as PocketWorldController
        val width = (360 * screen.resources.displayMetrics.density).toInt();val height = (800 * screen.resources.displayMetrics.density).toInt()
        fun frame() = frame(screen.window, width, height)
        fun click(label: String) { val node = nodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null } ?: error("Missing mounted native action $label; nodes=${nodes().map(::text)}");assertFalse("Disabled native action $label", node.config.contains(SemanticsProperties.Disabled));assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());frame() }
        try {
            frame();click("系统听写")
            val text = "这份未发送文字保留在原编辑器，我仍要自己决定是否发送。"
            shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(Bundle().also { it.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) });frame()
            val originalId = controller.state.voiceInput.getString("inputId")
            click("放入原输入，暂不发送");assertTrue(controller.state.voiceInput.optBoolean("staged"));assertFalse(controller.state.voiceInput.optBoolean("reviewAvailable"))
            click("对话");click("新对话")
            assertFalse(controller.state.voiceInput.optBoolean("staged"));assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == text });assertEquals(0, app.tasks.list().length())
            save(screen.window, "new-conversation-retained-editor-mic-ready")
            click("系统听写");assertEquals("listening", controller.state.voiceInput.getString("phase"));assertNotEquals(originalId, controller.state.voiceInput.getString("inputId"));assertEquals(0, app.tasks.list().length())
            save(screen.window, "new-conversation-explicit-new-dictation")
        } finally {
            owner.pause().stop().destroy();shadowOf(Looper.getMainLooper()).idle();(field(app, "capabilityWorkers") as ExecutorService).let { it.shutdownNow();it.awaitTermination(2, TimeUnit.SECONDS) };app.features.close();(field(app.tasks, "db") as android.database.sqlite.SQLiteDatabase).close();java.security.Security.removeProvider(provider.name)
            for (name in listOf("realtimeAudioOwner", "audioReleaseUnknown")) MediaController::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(null, if (name == "audioReleaseUnknown") false else null)
        }
    }
    @Test fun explicitSystemFinalCanBeEditedIntoOriginalComposerWithoutAutomaticSend() {
        val provider = object : java.security.Provider("VoiceDraftRenderKeyStore", 1.0, "Explicit test-only AndroidKeyStore") { init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) } }
        java.security.Security.addProvider(provider)
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val service = ResolveInfo().also { it.serviceInfo = ServiceInfo().also { info -> info.packageName = "fixture.system.speech";info.name = "Recognition" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), service);shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val owner = Robolectric.buildActivity(ComposeActivity::class.java).setup().visible();val screen = owner.get();val controller = field(screen, "controller") as PocketWorldController
        val width = (360 * screen.resources.displayMetrics.density).toInt();val height = (800 * screen.resources.displayMetrics.density).toInt()
        fun frame() = frame(screen.window, width, height)
        fun click(label: String) { val node = nodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null } ?: error("Missing mounted native action $label; nodes=${nodes().map(::text)}");assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());frame() }
        try {
            frame();assertTrue(nodes().any { text(it) == "系统听写" });save(screen.window, "original-composer-system-input")
            click("系统听写");assertTrue(controller.state.voiceInput.optBoolean("active"))
            val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer();val original = "把周三下午三点的项目评审整理为一份待我确认的安排，识别中的姓名和数字需要再核对。"
            shadowOf(recognizer).triggerOnResults(Bundle().also { it.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(original)) });frame()
            assertTrue(nodes().any { text(it) == "核对原对话草稿" });assertEquals(0, app.tasks.list().length())
            val edited = "整理周四下午四点的项目评审安排，任何对外发送都先让我确认。"
            val editor = nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == original }
            assertTrue(editor.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(edited)));frame()
            val dialog = ShadowDialog.getLatestDialog();assertNotNull(dialog);save(dialog.window!!, "final-edit-review-dialog")
            click("放入原输入，暂不发送")
            assertFalse(nodes().any { text(it) == "核对原对话草稿" });assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == edited });assertTrue(controller.state.voiceInput.optBoolean("staged"));assertEquals(0, app.tasks.list().length());save(screen.window, "edited-original-composer-not-sent")
        } finally {
            owner.pause().stop().destroy();shadowOf(Looper.getMainLooper()).idle();(field(app, "capabilityWorkers") as ExecutorService).let { it.shutdownNow();it.awaitTermination(2, TimeUnit.SECONDS) };app.features.close();(field(app.tasks, "db") as android.database.sqlite.SQLiteDatabase).close();java.security.Security.removeProvider(provider.name)
            for (name in listOf("realtimeAudioOwner", "audioReleaseUnknown")) MediaController::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(null, if (name == "audioReleaseUnknown") false else null)
        }
    }
}
