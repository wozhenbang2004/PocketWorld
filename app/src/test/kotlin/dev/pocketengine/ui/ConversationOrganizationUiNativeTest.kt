package dev.pocketengine.ui

import dev.pocketengine.EngineApplication
import dev.pocketengine.testutil.ReadyApplicationFixture
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Original Controller/Workbench/SQLite. Rename-dialog cancellation belongs to the Web interaction test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConversationOrganizationUiNativeTest {
    private fun seed(f: OwnerWebTestFixture, title: String, at: Long): String {
        val id = UUID.randomUUID().toString(); val repo = f.app.tasks.conversationRepository()
        repo.transaction { repo.saveConversation(JSONObject().put("conversationId", id).put("title", title).put("createdAt", at).put("updatedAt", at)); null }
        f.controller.refresh(); f.drain(); return id
    }
    private fun original(f: OwnerWebTestFixture, id: String) = f.app.features.ownerConversationWorkbench("getOrganization", JSONObject().put("conversationId", id))
    private fun change(f: OwnerWebTestFixture, id: String, revision: Long, title: String? = null, pinned: Boolean? = null): Pair<JSONObject?, String?> {
        var completed = false; var result: JSONObject? = null; var error: String? = null
        f.controller.organizeConversation(id, revision, title, pinned) { value, failure -> result = value; error = failure; completed = true }
        f.await { completed }; return result to error
    }
    @Test fun explicitOriginalRenamePersistsAndAnOlderRevisionCannotOverwriteIt() {
        OwnerWebTestFixture().use { f ->
            val id = seed(f, "原会话名称", 100)
            val before = original(f, id); assertEquals("原会话名称", before.getString("title")); assertEquals(0L, before.getLong("organizationRevision"))
            val (receipt, error) = change(f, id, 0, title = "主人手工标题")
            assertNull(error); assertNotNull(receipt); assertEquals("主人手工标题", original(f, id).getString("title")); assertEquals(1L, original(f, id).getLong("organizationRevision"))
            val rejected = change(f, id, 0, title = "过期窗口的未保存名字")
            assertNull(rejected.first); assertNotNull(rejected.second); assertEquals("主人手工标题", original(f, id).getString("title")); assertEquals(1L, original(f, id).getLong("organizationRevision")); assertEquals(0, f.app.tasks.list().length())
        }
    }
    @Test fun originalRefreshKeepsPinnedConversationOutsideTheLegacyTwentyRowWindow() {
        OwnerWebTestFixture().use { f ->
            val old = seed(f, "被置顶的早期原会话", 100)
            repeat(21) { seed(f, "较新的原会话$it", 1000L + it) }
            f.app.features.ownerConversationWorkbench("updateOrganization", JSONObject().put("conversationId", old).put("expectedRevision", 0).put("changes", JSONObject().put("pinOrder", 1)))
            f.controller.refresh(); f.await { f.controller.state.conversations.firstOrNull()?.id == old }
            assertEquals(20, f.controller.state.conversations.size); assertEquals(old, f.controller.state.conversations.first().id)
            assertEquals(1L, f.controller.conversationOrganizationItem(f.controller.state.conversations.first()).pinOrder); assertEquals(0, f.app.tasks.list().length())
        }
    }
    @Test fun pinAndUnpinPersistOriginalRevisionsWithoutStartingTask() {
        OwnerWebTestFixture().use { f ->
            val now = System.currentTimeMillis(); val old = seed(f, "较早的对话", now - 30 * 86400000L); seed(f, "今天的对话", now)
            assertNull(change(f, old, 0, pinned = true).second); assertEquals(1L, original(f, old).getLong("pinOrder"))
            assertNull(change(f, old, 1, pinned = false).second); assertEquals(0L, original(f, old).getLong("pinOrder")); assertEquals(2L, original(f, old).getLong("organizationRevision")); assertEquals(0, f.app.tasks.list().length())
        }
    }
    @Test fun originalWebSelectionRejectsReplacementClosureAndPauseResumeWindows() {
        OwnerWebTestFixture().use { f ->
            val id = seed(f, "原窗口标题", 100)
            f.await { f.controller.state.conversations.any { it.id == id && f.controller.conversationOrganizationItem(it).organizationRevision == 0L } }
            fun read() = f.call("conversation.organization.read", JSONObject().put("conversationId", id))
            fun save(selection: JSONObject) = f.envelope("conversation.organization.save", JSONObject().put("selectionHandle", selection.getString("selectionHandle")).put("title", "旧窗口不能写入").put("pinned", true))
            val old = read(); val current = read(); assertNotEquals(old.getString("selectionHandle"), current.getString("selectionHandle")); assertFalse(save(old).getBoolean("ok"))
            f.call("conversation.organization.close", JSONObject().put("selectionHandle", current.getString("selectionHandle"))); assertFalse(save(current).getBoolean("ok"))
            val paused = read(); f.controller.realtimeForeground(false); f.bridge.foreground(false); f.controller.realtimeForeground(true); f.bridge.foreground(true)
            assertFalse(save(paused).getBoolean("ok")); assertEquals("原窗口标题", original(f, id).getString("title")); assertEquals(0L, original(f, id).getLong("pinOrder")); assertEquals(0L, original(f, id).getLong("organizationRevision")); assertEquals(0, f.app.tasks.list().length())
        }
    }
}
