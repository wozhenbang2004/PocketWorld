package dev.pocketengine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.graphics.Rect
import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.text.AnnotatedString
import com.sun.net.httpserver.HttpServer
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.AttachmentRepository
import dev.pocketengine.phone.OwnerScreenContext
import dev.pocketengine.phone.PhoneAccessibilityService
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog

/** Real App/controller/SQLite/mailbox/attachment projection/Runner/local HTTP.
 * The second test's selected screen payload is an explicit native source fixture;
 * it does not claim Android hardware capture, cloud inference or physical-device validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, qualifiers = "w393dp-h852dp-mdpi", shadows = [OfficialAarResources::class, QuickPanelApplicationJourneyUiTest.Screen::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class QuickPanelApplicationJourneyUiTest {
    companion object { val treeReads = java.util.concurrent.atomic.AtomicInteger() }
    /** The platform supplies a real AccessibilityNodeInfo fixture; all source/port code is formal. */
    @Implements(PhoneAccessibilityService::class)
    class Screen {
        @Implementation fun observationSafetyFailure(): String? = null
        @Implementation fun foregroundRoot(): AccessibilityNodeInfo {
            treeReads.incrementAndGet()
            return AccessibilityNodeInfo.obtain().apply {
                packageName = "fixture.orders"; className = "android.widget.TextView"
                text = "EXPLICIT-SCREEN-NODE-FROM-ANDROID-SOURCE · 订单199元，尚未提交。"
                setVisibleToUser(true); setBoundsInScreen(Rect(0, 0, 393, 700))
            }
        }
        @Implementation fun takeScreenshot(display: Int, executor: java.util.concurrent.Executor, callback: AccessibilityService.TakeScreenshotCallback) {
            throw AssertionError("The formal native entry in this fixture explicitly selected text only")
        }
    }
    @Before fun setup() {
        treeReads.set(0)
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        java.security.Security.addProvider(object : java.security.Provider("QuickPanelJourneyKeys", 1.0, "Explicit local fixture key") {
            init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
        })
    }
    @After fun cleanup() { java.security.Security.removeProvider("QuickPanelJourneyKeys") }
    private fun root(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) root(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun windows(activity: ComposeActivity) = (listOf(activity.window.decorView) + ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }).distinct()
    private fun nodes(activity: ComposeActivity): List<SemanticsNode> = windows(activity).flatMap { root(it)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = true).orEmpty() }
    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString("") { it.text } else ""
    private fun tagged(activity: ComposeActivity, tag: String) = nodes(activity).first { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag }
    private fun frame(activity: ComposeActivity) {
        fun layout(view: View) { if (view is RootForTest) view.measureAndLayoutForTest(); if (view is ViewGroup) for (i in 0 until view.childCount) layout(view.getChildAt(i)) }
        repeat(4) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)); windows(activity).forEach(::layout) }
    }
    private fun await(activity: ComposeActivity, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!condition() && System.nanoTime() < until) frame(activity)
        frame(activity); assertTrue("Actual native QuickPanel application journey did not reach expected state", condition())
    }
    private fun draft(activity: ComposeActivity, value: String) {
        assertTrue(tagged(activity, "conversation_composer").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frame(activity)
    }
    private fun nativeView(panel: Any): View {
        val method = panel.javaClass.getDeclaredMethod("view"); method.isAccessible = true; return method.invoke(panel) as View
    }
    private fun nativeButton(panel: Any, label: String): Button {
        fun find(view: View): Button? {
            if (view is Button && view.text.toString() == label) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return requireNotNull(find(nativeView(panel))) { "Actual native QuickPanel button: $label" }
    }
    private class Fixture : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val bodies = CopyOnWriteArrayList<JSONObject>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val activityControl: org.robolectric.android.controller.ActivityController<ComposeActivity>
        val activity: ComposeActivity
        val controller: PocketWorldController
        init {
            server.createContext("/") { exchange -> try {
                val n = bodies.size; bodies.add(JSONObject(String(exchange.requestBody.readAllBytes(), Charsets.UTF_8)))
                if (n == 0) { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
                val message = if (n == 0) JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "inspect-original-context").put("type", "function").put("function", JSONObject().put("name", "attachments_list").put("arguments", "{}"))))
                    else JSONObject().put("role", "assistant").put("content", "已核对原任务和你明确选择的来源；没有提交订单。")
                val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", if (n == 0) "tool_calls" else "stop"))).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.write(response)
            } finally { exchange.close() } }
            server.start()
            app.getSharedPreferences("private_settings", 0).edit().putString("baseUrl", "http://127.0.0.1:" + server.address.port).putString("model", "quick-panel-native-fixture").putString("provider", "openai_chat").putBoolean("sendScreenshots", true).putBoolean("supportsImageInput", true).putInt("maxSteps", 8).commit()
            val pending = Robolectric.buildActivity(ComposeActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            activityControl = pending.setup().visible().apply { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)) }.windowFocusChanged(true)
            activity = activityControl.get(); val field = ComposeActivity::class.java.getDeclaredField("controller"); field.isAccessible = true; controller = field.get(activity) as PocketWorldController
        }
        override fun close() { release.countDown(); ShadowDialog.getShownDialogs().forEach { it.dismiss() }; activityControl.pause().stop().destroy(); app.features.module("assistant").close(); server.stop(0) }
    }
    private fun start(f: Fixture): JSONObject {
        await(f.activity) { f.controller.state.feature("assistant").optBoolean("sendAcknowledgements") }
        f.controller.sendChat("比较订单，先核对资料，不要提交。")
        assertTrue(f.entered.await(5, TimeUnit.SECONDS)); await(f.activity) { f.controller.state.chatActiveTurn != null && !f.controller.state.chatSubmitting }
        return JSONObject(f.controller.state.chatActiveTurn.toString())
    }
    @Test fun serviceFailureDoesNotClaimOpenedAndSuccessfulWindowKeepsOriginalDraftAndTask() {
        Fixture().use { f ->
            val original = start(f); draft(f.activity, "保留这段没有发送的对话草稿")
            val service = Robolectric.buildService(PhoneAccessibilityService::class.java).create()
            val connect = PhoneAccessibilityService::class.java.getDeclaredMethod("onServiceConnected"); connect.isAccessible = true; connect.invoke(service.get())
            try {
                val task = taskFromJson(f.app.tasks.get(original.getString("taskId"), false))
                f.controller.dismissNotice()
                f.controller.openQuickPanel(task.copy(raw = JSONObject(task.raw.toString()).put("runId", UUID.randomUUID().toString())))
                frame(f.activity)
                assertNull("A rejected native service open cannot manufacture an opened notice", f.controller.state.notice)
                val panelField = PhoneAccessibilityService::class.java.getDeclaredField("quickPanel"); panelField.isAccessible = true
                assertNull(panelField.get(service.get())); assertEquals(1, f.bodies.size); assertEquals(1, f.app.tasks.list().length())
                f.controller.openQuickPanel(task); frame(f.activity)
                val panel = requireNotNull(panelField.get(service.get()))
                val visible = panel.javaClass.getDeclaredMethod("visible"); visible.isAccessible = true
                assertEquals(true, visible.invoke(panel)); assertTrue(f.controller.state.notice!!.contains("已打开"))
                assertEquals("Opening the floating entry does not read screen nodes", 0, treeReads.get())
                assertEquals("保留这段没有发送的对话草稿", tagged(f.activity, "conversation_composer").config[SemanticsProperties.EditableText].text)
                assertEquals(1, f.bodies.size); assertEquals(original.getString("runId"), f.app.tasks.get(task.id, false).getString("runId"))
                val panelView = panel.javaClass.getDeclaredMethod("view"); panelView.isAccessible = true; val view = panelView.invoke(panel) as View
                view.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.AT_MOST)); view.layout(0, 0, 180, view.measuredHeight)
                val bitmap = Bitmap.createBitmap(180, view.measuredHeight, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
                val image = File(System.getProperty("pocketworld.a24.entry.renders", "build/test-renders/a24-entry"), "04-real-service-opened-entry.png")
                image.parentFile!!.mkdirs(); image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle(); assertTrue(image.length() > 1000)
                (panel as AutoCloseable).close()
                f.controller.openQuickPanel(task.copy(raw = JSONObject(task.raw.toString()).put("runId", UUID.randomUUID().toString())))
                frame(f.activity)
                assertNull("A later failed reopen must clear the previous opened notice", f.controller.state.notice)
                assertNull(panelField.get(service.get())); assertEquals(1, f.bodies.size)
            } finally { service.destroy() }
        }
    }
    @Test fun reviewedContextAndImmutableImageReachOriginalMessageTaskWithOneImportAndOriginalReceipt() {
        Fixture().use { f ->
            val original = start(f); draft(f.activity, "这段草稿和本次捕获无关，继续保留")
            val id = original.getString("taskId"); val run = original.getString("runId"); val epoch = original.getInt("epoch")
            val service = Robolectric.buildService(PhoneAccessibilityService::class.java).create()
            val connect = PhoneAccessibilityService::class.java.getDeclaredMethod("onServiceConnected"); connect.isAccessible = true; connect.invoke(service.get())
            try {
                f.controller.openQuickPanel(taskFromJson(f.app.tasks.get(id, false))); frame(f.activity)
                val panelField = PhoneAccessibilityService::class.java.getDeclaredField("quickPanel"); panelField.isAccessible = true
                val panel = requireNotNull(panelField.get(service.get()))
                assertEquals(0, treeReads.get()); assertEquals(1, f.bodies.size)
                assertTrue(nativeButton(panel, "补充原任务").performClick()); assertTrue(nativeButton(panel, "捕获并预览").performClick())
                val previewField = panel.javaClass.getDeclaredField("preview"); previewField.isAccessible = true
                await(f.activity) { previewField.get(panel) != null }
                val preview = previewField.get(panel) as OwnerScreenContext.Preview
                assertTrue(preview.text.contains("EXPLICIT-SCREEN-NODE-FROM-ANDROID-SOURCE")); assertFalse(preview.hasScreenshot())
                assertEquals(service.get().observationSessionId(), preview.source.sessionId); assertEquals(0, preview.source.displayId); assertEquals(-1, preview.target.executionDisplayId)
                assertTrue(treeReads.get() >= 2); assertEquals(1, f.app.tasks.get(id, false).getJSONArray("ownerInputs").length())
                assertTrue(nativeButton(panel, "发送到原任务").performClick())
                await(f.activity) { f.app.tasks.get(id, false).getJSONArray("ownerInputs").length() == 2 && previewField.get(panel) == null }
                assertEquals(1, f.app.tasks.list().length()); assertEquals(1, f.bodies.size)
                (panel as AutoCloseable).close()
            } finally { service.destroy() }
            val controlField = EngineApplication::class.java.getDeclaredField("capabilityControls"); controlField.isAccessible = true
            val control = (controlField.get(f.app) as Map<*, *>)[id] as RunControl
            val target = OwnerScreenContext.Target(id, run, epoch, "capabilities", null, -1)
            val sourceIdentity = OwnerScreenContext.SourceIdentity("selected-native-source-fixture", 0)
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=")
            val reads = java.util.concurrent.atomic.AtomicInteger()
            OwnerScreenContext(target, control, { bound, live ->
                assertSame(control, live); f.app.tasks.ownerScreenContextTarget(bound.taskId, bound.runId, bound.epoch, live); Unit
            }, object : OwnerScreenContext.Source {
                override fun identity() = sourceIdentity
                override fun capture(image: Boolean, check: OwnerScreenContext.ReadCheck): OwnerScreenContext.Captured {
                    check.check(); assertTrue(image); reads.incrementAndGet()
                    return OwnerScreenContext.Captured(sourceIdentity, "fixture.orders", "明确选中的来源fixture", 42, SystemClock.uptimeMillis(), "EXPLICIT-CONTEXT-FROM-MAIN-APP-FIXTURE\n价格199元，尚未提交。", "available", false, "available", "", "image/png", 1, 1, png)
                }
            }, SystemClock::uptimeMillis).use { capture ->
                assertEquals(0, reads.get()); val selected = capture.capture(true); val client = UUID.randomUUID().toString()
                assertEquals(1, f.app.tasks.list().length()); assertEquals(0, AttachmentRepository.get(f.app).book.list().length())
                val accepted = f.app.submitOwnerScreenContext(capture, selected, client, "只核对来源，不提交。")
                assertEquals("queued", accepted.getString("status")); assertEquals(id, accepted.getString("taskId")); assertEquals(run, accepted.getString("runId")); assertEquals(epoch, accepted.getInt("epoch"))
                val duplicate = f.app.submitOwnerScreenContext(capture, selected, client, "只核对来源，不提交。")
                assertEquals(accepted.getString("messageId"), duplicate.getString("messageId")); assertEquals(1, AttachmentRepository.get(f.app).book.list().length())
                val receipt = f.app.ownerScreenContextReceipt(original, client); assertEquals(accepted.getString("messageId"), receipt.getString("messageId"))
                val rows = f.app.tasks.get(id, false).getJSONArray("ownerInputs").objects()
                assertEquals(1, rows.count { it.optString("clientMessageId") == client }); assertEquals(1, rows.single { it.optString("clientMessageId") == client }.getJSONArray("attachments").length())
                assertFalse(rows.toString().contains("base64")); assertFalse(control.isCancelled()); assertFalse(control.isPaused()); assertEquals(1, f.bodies.size)
                f.release.countDown(); await(f.activity) { f.bodies.size >= 2 && f.app.tasks.get(id, false).optString("status") == "completed" }
                assertEquals(1, f.app.tasks.list().length()); assertEquals(run, f.app.tasks.get(id, false).getString("runId")); assertEquals(1, reads.get())
                assertTrue(f.bodies[1].toString().contains("EXPLICIT-CONTEXT-FROM-MAIN-APP-FIXTURE")); assertTrue(f.bodies[1].toString().contains("data:image/png;base64,"))
                assertTrue(f.bodies[1].toString().contains("EXPLICIT-SCREEN-NODE-FROM-ANDROID-SOURCE"))
                assertFalse(f.bodies[1].toString().contains("这段草稿和本次捕获无关"))
                assertEquals("这段草稿和本次捕获无关，继续保留", tagged(f.activity, "conversation_composer").config[SemanticsProperties.EditableText].text)
            }
        }
    }
}
