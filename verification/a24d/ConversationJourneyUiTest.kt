package dev.pocketengine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.sun.net.httpserver.HttpServer
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.attachments.AttachmentRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
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
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/** Current formal App + actual original controller/SQLite/Runner/HTTP; no business Host stub. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, qualifiers = "w393dp-h852dp-mdpi", shadows = [OfficialAarResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class ConversationJourneyUiTest {
    @Before fun keyFixture() { org.robolectric.shadows.ShadowChoreographer.setPaused(true); org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16)); java.security.Security.addProvider(object : java.security.Provider("ConversationJourneyKeys", 1.0, "Synthetic local fixture key") {
        init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
    }) }
    @After fun removeKeys() { java.security.Security.removeProvider("ConversationJourneyKeys") }
    private fun composeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) composeRoot(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun roots(activity: ComposeActivity): List<View> = (listOf(activity.window.decorView) + ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }).distinct()
    private fun nodes(activity: ComposeActivity): List<SemanticsNode> {
        return roots(activity).flatMap { view -> composeRoot(view)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = true).orEmpty() }
    }
    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString("") { it.text } else ""
    private fun tagged(activity: ComposeActivity, tag: String) = nodes(activity).first { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag }
    private fun frame(activity: ComposeActivity) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        fun layout(view: View) { if (view is RootForTest) view.measureAndLayoutForTest(); if (view is ViewGroup) for (i in 0 until view.childCount) layout(view.getChildAt(i)) }
        repeat(5) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)); roots(activity).forEach(::layout) }
    }
    private fun await(activity: ComposeActivity, check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!check() && System.nanoTime() < until) { frame(activity); Thread.sleep(10) }
        frame(activity)
        if (!check()) { val field = ComposeActivity::class.java.getDeclaredField("controller"); field.isAccessible = true; val state = (field.get(activity) as PocketWorldController).state; println("NATIVE_JOURNEY_TIMEOUT busy=${state.chatBusy} submitting=${state.chatSubmitting} active=${state.chatActiveTurn} receipts=${state.chatReceipts} tasks=${state.tasks.map { it.status + ":" + it.summary }} error=${state.error}") }
        assertTrue("formal native journey condition not reached", check())
    }
    private fun click(activity: ComposeActivity, label: String) {
        val target = nodes(activity).filter { text(it).contains(label) && it.config.contains(SemanticsActions.OnClick) }.minByOrNull { text(it).length }
        assertNotNull("visible native action: $label", target); assertFalse(target!!.config.contains(SemanticsProperties.Disabled)); assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke()); frame(activity)
    }
    private fun clickTag(activity: ComposeActivity, tag: String) { val target = tagged(activity, tag); assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke()); frame(activity) }
    private fun composer(activity: ComposeActivity, value: String) { assertTrue(tagged(activity, "conversation_composer").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frame(activity) }
    private fun scroll(activity: ComposeActivity, index: Int) { assertTrue(tagged(activity, "conversation_messages").config[SemanticsActions.ScrollToIndex].action!!.invoke(index)); frame(activity) }
    private fun save(activity: ComposeActivity, name: String) {
        val bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        roots(activity).forEach { it.draw(canvas) }
        val target = File(System.getProperty("pocketworld.a24d.renders"), name); target.parentFile!!.mkdirs(); target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(target.length() > 2000)
    }
    private class Fixture : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val bodies = CopyOnWriteArrayList<JSONObject>(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val activity: org.robolectric.android.controller.ActivityController<ComposeActivity>
        val controller: PocketWorldController
        init {
            server.createContext("/") { exchange -> try {
                val n = bodies.size; bodies.add(JSONObject(String(exchange.requestBody.readAllBytes(), Charsets.UTF_8)))
                if (n == 0) { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
                val message = if (n == 0) JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "read-original-attachments").put("type", "function").put("function", JSONObject().put("name", "attachments_list").put("arguments", "{}"))))
                else JSONObject().put("role", "assistant").put("content", when (n) {
                    1 -> "## 首轮比较\n\n保留原始约束，先比较交通与预算。\n\n```text\n原样复制：不要预订🙂\n```"
                    2 -> "## 补充后的结果\n\n| 方案 | 下一步 |\n| --- | --- |\n| 城市近郊 | 核对时间 |\n| 周边古镇 | 核对预算 |\n\n结果供主人核对。"
                    else -> "## 原任务修正结果\n\n已经按明确补充缩短比较；未进行预订。"
                })
                val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", if (n == 0) "tool_calls" else "stop"))).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.write(response)
            } finally { exchange.close() } }
            server.start(); app.getSharedPreferences("private_settings", 0).edit().putString("baseUrl", "http://127.0.0.1:" + server.address.port).putString("model", "native-journey-fixture").putString("provider", "openai_chat").putInt("maxSteps", 8).commit()
            val pending = Robolectric.buildActivity(ComposeActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            activity = pending.setup().visible().apply { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)) }.windowFocusChanged(true)
            val field = ComposeActivity::class.java.getDeclaredField("controller"); field.isAccessible = true; controller = field.get(activity.get()) as PocketWorldController
        }
        override fun close() { release.countDown(); activity.pause().stop().destroy(); app.features.module("assistant").close(); server.stop(0) }
    }
    @Test fun selectedAttachmentQueuedRepliesOriginalTaskAndConversationFollowupKeepDraftAndPosition() {
        Fixture().use { f ->
            val activity = f.activity.get(); await(activity) { f.controller.state.feature("assistant").optBoolean("sendAcknowledgements") }
            val file = AttachmentRepository.get(f.app).book.importBytes(ByteArrayInputStream("REAL-ORIGINAL-WEEKEND-CONSTRAINTS".toByteArray()), "周末要求.txt", "text/plain", JSONObject())
            f.controller.toggleChatAttachment(file); composer(activity, "比较两个周末方案，暂时不要预订"); click(activity, "发送")
            assertTrue(f.entered.await(5, TimeUnit.SECONDS)); await(activity) { !f.controller.state.chatSubmitting && f.controller.state.chatActiveTurn != null }
            val original = JSONObject(f.controller.state.chatActiveTurn.toString()); composer(activity, "补充：把交通和总预算放在一起"); click(activity, "加入原任务")
            await(activity) { !f.controller.state.chatSubmitting && f.controller.state.chatReceipts.size == 2 }; f.release.countDown()
            await(activity) { f.bodies.size == 3 && !f.controller.state.chatBusy && f.controller.state.messages.count { it.role == "assistant" } == 2 }
            assertNull("ordinary successful admission must not leave a global processing notice", f.controller.state.notice)
            assertTrue(f.controller.state.chatReceipts.values.all { it.optString("status") == "consumed" }); assertEquals(1, f.app.tasks.list().length())
            val id = original.getString("taskId"); await(activity) { f.controller.state.tasks.any { it.id == id && it.status == "completed" } }
            scroll(activity, 0); assertTrue(nodes(activity).any { text(it).contains("周末要求.txt") }); clickTag(activity, "conversation_file_" + file.getString("id"))
            await(activity) { f.controller.state.attachmentOpens.values.any { it.requested } }; val view = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW, view.action); assertEquals(file.getString("id"), view.data!!.lastPathSegment); assertTrue(view.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val draftFile = AttachmentRepository.get(f.app).book.importBytes(ByteArrayInputStream("DRAFT-ONLY-PRIVATE-CONTENT".toByteArray()), "未发送资料.txt", "text/plain", JSONObject())
            f.controller.toggleChatAttachment(draftFile); composer(activity, "保留这段未发送的新草稿"); scroll(activity, f.controller.state.messages.lastIndex); save(activity, "01-current-rich-conversation.png")
            val reading = tagged(activity, "conversation_messages").config[SemanticsProperties.VerticalScrollAxisRange].value()
            clickTag(activity, "conversation_open_task"); await(activity) { nodes(activity).any { text(it) == "返回对话" } }; assertEquals(3, f.bodies.size); save(activity, "02-original-task-result.png")
            click(activity, "返回对话"); await(activity) { nodes(activity).any { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "conversation_composer" } }
            assertEquals("保留这段未发送的新草稿", tagged(activity, "conversation_composer").config[SemanticsProperties.EditableText].text); assertEquals(draftFile.getString("id"), f.controller.state.selectedChatAttachments.single().id)
            assertEquals(reading, tagged(activity, "conversation_messages").config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f); save(activity, "03-return-keeps-draft.png")
            assertEquals(3, f.bodies.size); assertEquals(1, f.app.tasks.list().length())
            f.controller.removeChatAttachment(draftFile.getString("id")); composer(activity, "缩短原比较结果，保留不预订约束"); click(activity, "发送")
            await(activity) { f.bodies.size == 4 && !f.controller.state.chatBusy }; assertEquals(original.getString("conversationId"), f.controller.state.conversationId)
            assertEquals("Completed work stays completed; an explicit new conversational request has its own task", 2, f.app.tasks.list().length()); assertEquals("completed", f.app.tasks.get(id, false).getString("status"))
            val request = f.bodies[3].toString(); assertTrue(request.contains("缩短原比较结果")); assertTrue(request.contains("REAL-ORIGINAL-WEEKEND-CONSTRAINTS")); assertTrue(request.contains("补充：把交通和总预算放在一起")); assertFalse(request.contains("DRAFT-ONLY-PRIVATE-CONTENT")); assertFalse(request.contains("保留这段未发送的新草稿"))
        }
    }
    @Test fun stoppedTaskContinuesOnlyAfterVisibleOwnerReviewAndKeepsSameTaskIdentity() {
        Fixture().use { f ->
            val activity = f.activity.get(); await(activity) { f.controller.state.feature("assistant").optBoolean("sendAcknowledgements") }
            val file = AttachmentRepository.get(f.app).book.importBytes(ByteArrayInputStream("ORIGINAL-RESUME-DOCUMENT".toByteArray()), "原任务资料.txt", "text/plain", JSONObject())
            f.controller.toggleChatAttachment(file); composer(activity, "先检查原资料，保留约束"); click(activity, "发送"); assertTrue(f.entered.await(5, TimeUnit.SECONDS))
            await(activity) { f.controller.state.chatActiveTurn != null }; val original = JSONObject(f.controller.state.chatActiveTurn.toString()); click(activity, "停止回复")
            await(activity) { !f.controller.state.chatBusy && f.controller.state.tasks.any { it.id == original.getString("taskId") && it.status == "cancelled" } }; f.release.countDown(); assertEquals(1, f.bodies.size)
            composer(activity, "尚未发送的另一条问题"); clickTag(activity, "conversation_open_task"); await(activity) { nodes(activity).any { text(it) == "返回对话" } }
            val feedback = nodes(activity).first { it.config.contains(SemanticsActions.SetText) && text(it).contains("补充要求") }
            assertTrue(feedback.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("主人明确继续原任务，核对这份资料"))); frame(activity)
            assertTrue("explicit feedback is entered in the original task sheet", nodes(activity).any { it.config.contains(SemanticsProperties.EditableText) && it.config[SemanticsProperties.EditableText].text == "主人明确继续原任务，核对这份资料" })
            assertEquals(1, f.bodies.size); click(activity, "继续此任务"); assertEquals(1, f.bodies.size); click(activity, "继续执行")
            await(activity) { f.bodies.size == 2 && f.controller.state.tasks.any { it.id == original.getString("taskId") && it.raw.optString("runId") != original.getString("runId") && it.status == "completed" } }
            await(activity) { f.controller.state.messages.any { it.role == "assistant" && it.content.contains("首轮比较") } }
            assertEquals(1, f.app.tasks.list().length()); assertTrue("original attachment bytes remain in resumed request", f.bodies[1].toString().contains("ORIGINAL-RESUME-DOCUMENT")); assertTrue("explicit original-task feedback reaches the request", f.bodies[1].toString().contains("主人明确继续原任务")); assertFalse("the unsent chat draft is not sent by task continuation", f.bodies[1].toString().contains("尚未发送的另一条问题"))
            click(activity, "返回对话"); await(activity) { nodes(activity).any { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "conversation_composer" } }
            assertEquals("尚未发送的另一条问题", tagged(activity, "conversation_composer").config[SemanticsProperties.EditableText].text)
            assertNull("completed continuation must not leave a stale running notice", f.controller.state.notice)
            save(activity, "05-explicit-original-task-resume.png")
        }
    }
    @Test fun changedOriginalAttachmentBytesShowLocalErrorWithoutLaunchingDifferentContent() {
        Fixture().use { f ->
            val activity = f.activity.get(); await(activity) { f.controller.state.feature("assistant").optBoolean("sendAcknowledgements") }
            val book = AttachmentRepository.get(f.app).book; val file = book.importBytes(ByteArrayInputStream("original".toByteArray()), "原资料.txt", "text/plain", JSONObject())
            f.controller.toggleChatAttachment(file); val ref = f.controller.state.selectedChatAttachments.single(); Files.write(book.file(ref.id).toPath(), "replaced".toByteArray())
            f.controller.openSelectedChatAttachment(ref); await(activity) { f.controller.state.attachmentOpens[attachmentOpenKey(ref)]?.error != null }
            assertTrue(f.controller.state.attachmentOpens[attachmentOpenKey(ref)]!!.error!!.contains("摘要")); assertNull(Shadows.shadowOf(activity).nextStartedActivity); assertEquals(0, f.bodies.size); assertEquals(0, f.app.tasks.list().length()); save(activity, "04-original-attachment-error.png")
        }
    }
    @Test fun realMediaWorkflowShowsSavedArtifactBeforeServiceProtocolAndOpeningDoesNotGenerateAgain() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val posted = java.util.concurrent.atomic.AtomicReference<JSONObject>(); val posts = java.util.concurrent.atomic.AtomicInteger()
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> try {
            fun respond(value: JSONObject) { val bytes = value.toString().toByteArray(); exchange.responseHeaders.add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes) }
            when {
                exchange.requestURI.path == "/prompt" -> { val request = JSONObject(String(exchange.requestBody.readAllBytes())); posted.set(request); posts.incrementAndGet(); respond(JSONObject().put("prompt_id", request.getString("prompt_id"))) }
                exchange.requestURI.path.startsWith("/history") -> {
                    val request = posted.get(); val queueItem = JSONArray().put(1).put(request.getString("prompt_id")).put(request.getJSONObject("prompt")).put(JSONObject(request.getJSONObject("extra_data").toString()).put("client_id", request.getString("client_id")))
                    respond(JSONObject().put(request.getString("prompt_id"), JSONObject().put("prompt", queueItem).put("status", JSONObject().put("completed", true).put("status_str", "success")).put("outputs", JSONObject().put("9", JSONObject().put("images", JSONArray().put(JSONObject().put("filename", "real-result.png").put("subfolder", "").put("type", "output")))))))
                }
                exchange.requestURI.path.startsWith("/view") -> { exchange.sendResponseHeaders(200, png.size.toLong()); exchange.responseBody.write(png) }
                else -> respond(JSONObject().put("queue_pending", JSONArray()).put("queue_running", JSONArray()))
            }
        } finally { exchange.close() } }
        server.start(); val pending = Robolectric.buildActivity(ComposeActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activityControl = pending.setup().visible().apply { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)) }.windowFocusChanged(true)
        val activity = activityControl.get(); val field = ComposeActivity::class.java.getDeclaredField("controller"); field.isAccessible = true; val controller = field.get(activity) as PocketWorldController
        try {
            controller.requestFeature("media", "configure", JSONObject().put("provider", "comfyui").put("baseUrl", "http://127.0.0.1:" + server.address.port)); await(activity) { controller.state.feature("media").optBoolean("configured") }
            val arguments = JSONObject().put("configurationRevision", controller.state.feature("media").getString("configurationRevision")).put("workflow", JSONObject().put("6", JSONObject().put("class_type", "CLIPTextEncode").put("inputs", JSONObject().put("text", "明确的本地协议fixture"))))
            controller.requestFeature("media", "prepareSubmission", JSONObject().put("submissionCommand", "submitWorkflow").put("arguments", arguments)); await(activity) { controller.state.moduleResults["media.prepareSubmission"] != null }
            val id = controller.state.moduleResults["media.prepareSubmission"]!!.getString("taskId"); assertEquals(0, posts.get()); controller.focusTask(id)
            await(activity) { controller.state.moduleResults["media.getPreparedSubmission"]?.optString("taskId") == id }; click(activity, "核对工作流输入"); click(activity, "核对完成，提交这个原任务"); click(activity, "提交原任务")
            await(activity) { posts.get() == 1 && controller.state.tasks.any { it.id == id && MediaOwnerUi.operation(it.raw) != null } }; click(activity, "取回原产物")
            await(activity) { controller.state.tasks.any { it.id == id && it.status == "needs_confirmation" && taskResultAttachments(it).isNotEmpty() } }
            val file = taskResultAttachments(controller.state.tasks.first { it.id == id }).single(); assertArrayEquals(png, Files.readAllBytes(AttachmentRepository.get(app).book.file(file.id).toPath()))
            val resultHeading = nodes(activity).first { text(it) == "已保存成果" }; val protocolHeading = nodes(activity).first { text(it) == "媒体原操作" }
            assertTrue("real saved results precede service protocol controls", resultHeading.boundsInRoot.top < protocolHeading.boundsInRoot.top); save(activity, "06-real-media-result-first.png")
            clickTag(activity, "conversation_file_" + file.id); await(activity) { controller.state.attachmentOpens[attachmentOpenKey(file)]?.requested == true }; assertEquals(1, posts.get()); assertEquals("needs_confirmation", app.tasks.get(id, false).getString("status")); assertEquals(1, app.tasks.list().length())
        } finally { ShadowDialog.getShownDialogs().forEach { it.dismiss() }; activityControl.pause().stop().destroy(); app.features.close(); server.stop(0) }
    }
}
