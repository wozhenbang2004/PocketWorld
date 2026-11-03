package dev.pocketengine.ui

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.FeatureHost
import dev.pocketengine.features.teams.*
import dev.pocketengine.features.workspace.WorkspaceModule
import dev.pocketengine.store.SecretVault
import dev.pocketengine.store.SettingsStore
import dev.pocketengine.store.TaskStore
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
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
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.LooperMode

/** Actual original Compose controls and native owner/SQL review. No screenshot,
 * vendor inference, public HTTP, full App, or visual acceptance is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h1000dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ResearchReadAccessMountedNativeTest {
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

    private inner class Fixture : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as Application
        lateinit var tasks: TaskStore
        lateinit var features: FeatureHost
        lateinit var workspace: WorkspaceModule
        lateinit var coordinator: TeamCoordinator
        lateinit var host: TeamMemberEvidenceHost
        val run = UUID.randomUUID().toString()
        lateinit var project: String
        val release = CountDownLatch(1)
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        init {
          try {
            app.deleteDatabase("pocket-world.db"); tasks = TaskStore(app); features = FeatureHost({}, tasks) { false }
            workspace = WorkspaceModule(FeatureContext(app, SettingsStore(app), SecretVault(app)) {}); features.register(workspace)
            project = workspace.handle("create", JSONObject().put("name", "原项目 UI scope")).getString("id")
            workspace.handle("write", JSONObject().put("projectId", project).put("path", "notes.txt").put("expectedVersion", 0).put("content", "UI_NATIVE_SELECTED_FILE"))
            host = TeamMemberEvidenceHost(features, tasks, { error("no private source was selected") }) { error("no private source metadata was selected") }
            val entered = CountDownLatch(1)
            coordinator = TeamCoordinator(File(app.filesDir, "research-ui-${UUID.randomUUID()}.json"), TeamCoordinator.ToolGateway { _, _, _, _ -> error("no model in an owner-selection test") }) {}
            coordinator.setBudgetHost(TaskStoreTeamWriteHost(tasks, TeamWorkspaceAccess(workspace)) {})
            coordinator.setResearchFactory { _, _, _, _ -> entered.countDown(); release.await(60, TimeUnit.SECONDS); JSONObject().put("status", "interrupted").put("message", "owner UI metadata fixture only") }
            tasks.beginExternal("teams", "start", run, JSONObject().put("goal", "显选研究范围"))
            val definition = JSONObject().put("id", UUID.randomUUID().toString()).put("name", "原研究 UI").put("mode", "collaborate").put("leaderId", "a").put("members", JSONArray().put(JSONObject().put("id", "a").put("name", "原成员").put("role", "只读").put("model", "fixture")))
            coordinator.start(definition, "不自动执行或分享", 1, ModelConfig("https://fixture.invalid/v1", "", "fixture", false, 8, 10), run, host, null)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.get().setContent { MaterialTheme { Column { TeamMemberMaterialControls(coordinator.get(run), { r, m -> host.open(coordinator, r, m) }, { it.run() }) } } }
            frame()
          } catch (failure: Throwable) { try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure }
        }
        // Popup position polling can keep Robolectric's immediate queue nonempty.
        // Drive bounded real main-loop tasks instead of draining that queue forever.
        fun frame() { repeat(48) {
            shadowOf(Looper.getMainLooper()).runOneTask()
            WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach { view ->
                view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
        } }
        fun action(label: String): () -> Boolean = nodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null }?.config?.get(SemanticsActions.OnClick)?.action ?: error("Missing original UI action $label; text=${nodes().map(::text)}")
        fun click(label: String) { assertTrue(action(label).invoke()); frame() }
        fun site(value: String) { val editor = nodes().first { it.config.getOrNull(SemanticsProperties.EditableText) != null && it.config.getOrNull(SemanticsActions.SetText)?.action != null }; assertTrue(editor.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frame() }
        fun toggleLastCheckbox() { val box = nodes().last { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }; assertTrue(box.config[SemanticsActions.OnClick].action!!.invoke()); frame() }
        fun prepareReview(shared: Boolean = false) {
            click("成员能读哪些资料"); click("从原项目选择文件"); click("原项目 UI scope"); toggleLastCheckbox(); site("https://example.org")
            assertEquals(0, tasks.teamMemberMaterialScope(run, "a").getLong("revision"))
            click(if (shared) "一次审阅给本次当前成员共享" else "审阅本成员的精确版本与范围")
            assertTrue(nodes().any { text(it).contains("notes.txt") && text(it).contains("版本 1") })
            toggleLastCheckbox()
        }
        override fun close() {
            owner.pause().stop().destroy(); repeat(8) { shadowOf(Looper.getMainLooper()).runOneTask() }; release.countDown(); if (::coordinator.isInitialized) coordinator.close()
            if (::coordinator.isInitialized) for (name in listOf("coordinator", "members")) { val f = TeamCoordinator::class.java.getDeclaredField(name).also { it.isAccessible = true }; assertTrue((f.get(coordinator) as ExecutorService).awaitTermination(5, TimeUnit.SECONDS)) }
            if (::features.isInitialized) features.close(); if (::tasks.isInitialized) (TaskStore::class.java.getDeclaredField("db").also { it.isAccessible = true }.get(tasks) as SQLiteDatabase).close()
        }
    }
    @Test fun changedResearchDraftRejectsAnAlreadyCapturedOldConfirmCallback() {
        Fixture().use { f ->
            f.prepareReview(); val oldConfirm = f.action("确认本成员读取范围"); f.site("https://docs.example.org")
            assertFalse(nodes().any { text(it) == "确认本成员读取范围" })
            oldConfirm.invoke(); f.frame()
            assertEquals("old queued UI callback cannot share removed or superseded scope", 0, f.tasks.teamMemberMaterialScope(f.run, "a").getLong("revision"))
            assertEquals(0, f.tasks.teamBudgetSnapshot(f.run).getInt("toolOperationsReservedOrUsed"))
        }
    }
    @Test fun actualOriginalUiConfirmsOnlyTheReviewedNativeFileAndSiteWithoutStartingARead() {
        Fixture().use { f ->
            f.prepareReview(); f.click("确认本成员读取范围")
            val saved = f.tasks.teamMemberMaterialScope(f.run, "a"); assertEquals(1, saved.getLong("revision"))
            val scope = saved.getJSONObject("researchReadAccess"); assertEquals("https://example.org", scope.getJSONArray("webOrigins").getString(0))
            val file = scope.getJSONArray("projectFiles").getJSONObject(0); assertEquals(f.project, file.getString("projectId")); assertEquals("notes.txt", file.getString("path")); assertEquals(1, file.getInt("version")); assertEquals(64, file.getString("sha256").length)
            assertEquals(0, f.tasks.teamBudgetSnapshot(f.run).getInt("toolOperationsReservedOrUsed"))
        }
    }
    @Test fun changedSharedResearchDraftRejectsAnAlreadyCapturedOldConfirmCallback() {
        Fixture().use { f ->
            f.prepareReview(shared = true); val oldConfirm = f.action("一次确认以上成员共享"); f.site("https://docs.example.org")
            assertFalse(nodes().any { text(it) == "一次确认以上成员共享" })
            oldConfirm.invoke(); f.frame()
            assertEquals("queued shared confirmation cannot restore superseded scope", 0, f.tasks.teamMemberMaterialScope(f.run, "a").getLong("revision"))
            assertEquals(0, f.tasks.teamBudgetSnapshot(f.run).getInt("toolOperationsReservedOrUsed"))
        }
    }
    @Test fun withdrawnConsentRejectsAnAlreadyCapturedMemberConfirmCallback() {
        Fixture().use { f ->
            f.prepareReview(); val oldConfirm = f.action("确认本成员读取范围"); f.toggleLastCheckbox()
            oldConfirm.invoke(); f.frame()
            assertEquals("queued confirmation must recheck current consent", 0, f.tasks.teamMemberMaterialScope(f.run, "a").getLong("revision"))
        }
    }
    @Test fun withdrawnConsentRejectsAnAlreadyCapturedSharedConfirmCallback() {
        Fixture().use { f ->
            f.prepareReview(shared = true); val oldConfirm = f.action("一次确认以上成员共享"); f.toggleLastCheckbox()
            oldConfirm.invoke(); f.frame()
            assertEquals("queued shared confirmation must recheck current consent", 0, f.tasks.teamMemberMaterialScope(f.run, "a").getLong("revision"))
        }
    }

}
