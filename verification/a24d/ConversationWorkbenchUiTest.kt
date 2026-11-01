package dev.pocketengine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.conversation.ConversationHistory
import dev.pocketengine.features.conversation.TaskPresentation
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, qualifiers = "w400dp-h900dp-mdpi", shadows = [OfficialAarResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class ConversationWorkbenchUiTest {
    @org.junit.Before fun controlledFrames() { org.robolectric.shadows.ShadowChoreographer.setPaused(true); org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16)) }
    private fun host(content: @Composable () -> Unit) = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().apply { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)) }.windowFocusChanged(true).also { control ->
        control.get().setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff222428), surface = Color.White, background = Color.White, surfaceVariant = Color(0xfff5f6f7), onSurface = Color(0xff222428), onSurfaceVariant = Color(0xff72777f), outlineVariant = Color(0xffe9ebee))) {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(18.dp)) { content() } }
            }
        }
        frame(control.get().window.decorView)
    }
    private fun frame(view: View) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        fun layout(current: View) { if (current is RootForTest) current.measureAndLayoutForTest(); if (current is ViewGroup) for (i in 0 until current.childCount) layout(current.getChildAt(i)) }
        repeat(5) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)); layout(view) }
    }
    private fun root(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) root(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun nodes(view: View): List<SemanticsNode> {
        return requireNotNull(root(view)).semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
    }
    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString("") { it.text } else ""
    private fun click(view: View, label: String) {
        val node = nodes(view).firstOrNull { text(it).startsWith(label) && it.config.contains(SemanticsActions.OnClick) }
        assertNotNull("actual rendered click target: $label", node); assertFalse(node!!.config.contains(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frame(view)
    }
    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(400, 900, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap)); var changed = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) changed++
        assertTrue("native rendered content must be visible", changed > 2500)
        val path = File(System.getProperty("pocketworld.a24d.renders", "build/test-renders/a24d"), name); path.parentFile!!.mkdirs(); path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun codeCopyAndTaskNavigationUseRenderedNativeControls() {
        val opened = mutableListOf<String>(); val refreshed = mutableListOf<String>()
        val raw = JSONObject().put("id", "original-task").put("runId", "original-run").put("status", "needs_confirmation").put("goal", "整理回复和产物").put("summary", "工具已有返回，等待你核对最终结果。")
            .put("operations", JSONArray().put(JSONObject().put("operationId", "op").put("tool", "attachments_create_text").put("state", "receipt").put("receipt", JSONObject().put("accepted", true).put("message", "已创建报告.txt"))))
        host { RichConversationMessageBody("## 结果\n\n这里是 **可复核** 的内容。\n\n```kotlin\nprintln(\"hello🙂\")\n```\n"); Spacer(Modifier.height(14.dp)); ConversationTaskJournal(TaskPresentation.fromTaskStore(raw), opened::add, refreshed::add) }.use { control ->
            val view = control.get().window.decorView; assertTrue(nodes(view).any { text(it).contains("结果") }); click(view, "复制代码")
            val clipboard = requireNotNull(control.get().getSystemService(android.content.ClipboardManager::class.java)); assertEquals("println(\"hello🙂\")", clipboard.primaryClip!!.getItemAt(0).text.toString())
            click(view, "查看原任务"); assertEquals(listOf("original-task"), opened); assertTrue(refreshed.isEmpty()); click(view, "工具过程（1）"); click(view, "attachments_create_text调用已接受")
            assertTrue(nodes(view).any { text(it).contains("工具返回") || text(it).contains("该次工具返回") }); save(view, "message-task.png")
        }
    }
    @Test fun historySearchFiltersPersistedRowsAndOpeningKeepsExactConversationIdentity() {
        val opened = mutableListOf<String>(); var creates = 0; val now = System.currentTimeMillis()
        host { ConversationHistoryBrowser(listOf(ConversationHistory.Item("persisted-one", "文档整理", now), ConversationHistory.Item("persisted-two", "模型研究", now - 1000)), "persisted-one", false, opened::add, { creates++ }) }.use { control ->
            val view = control.get().window.decorView; val query = nodes(view).first { it.config.contains(SemanticsActions.SetText) }; assertTrue(query.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("模型"))); frame(view)
            assertFalse(nodes(view).any { text(it).contains("文档整理") }); val row = nodes(view).first { text(it).startsWith("模型研究") && it.config.contains(SemanticsActions.OnClick) }; assertTrue(row.config[SemanticsActions.OnClick].action!!.invoke()); assertEquals(listOf("persisted-two"), opened); assertEquals(0, creates); save(view, "history-search.png")
        }
    }
}
