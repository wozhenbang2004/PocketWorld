package dev.pocketengine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.text.AnnotatedString
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.HostToolSupervisor
import java.io.File
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

/** Actual native workspace/TaskStore/approval/journal and UI. Each method requires its own JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, qualifiers = "w393dp-h852dp-mdpi", shadows = [OfficialAarResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class WorkspaceArtifactJourneyUiTest {
    @Before fun setup() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        java.security.Security.addProvider(object : java.security.Provider("WorkspaceJourneyKeys", 1.0, "Isolated fixture key") {
            init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
        })
    }
    @After fun cleanup() { java.security.Security.removeProvider("WorkspaceJourneyKeys") }
    private fun root(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) root(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun windows(activity: ComposeActivity) = (listOf(activity.window.decorView) + ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }).distinct()
    private fun nodes(activity: ComposeActivity) = windows(activity).flatMap { root(it)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = true).orEmpty() }
    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString("") { it.text } else ""
    private fun tagged(activity: ComposeActivity, tag: String) = nodes(activity).first { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag }
    private fun frame(activity: ComposeActivity) {
        fun layout(view: View) { if (view is RootForTest) view.measureAndLayoutForTest(); if (view is ViewGroup) for (index in 0 until view.childCount) layout(view.getChildAt(index)) }
        repeat(5) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)); windows(activity).forEach(::layout) }
    }
    private fun await(activity: ComposeActivity, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) frame(activity)
        frame(activity); assertTrue("actual native workspace journey did not reach its expected state", condition())
    }
    private fun click(activity: ComposeActivity, label: String) {
        val target = nodes(activity).filter { text(it).contains(label) && it.config.contains(SemanticsActions.OnClick) }.minByOrNull { text(it).length }
        assertNotNull("native action: $label", target); assertFalse(target!!.config.contains(SemanticsProperties.Disabled)); assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke()); frame(activity)
    }
    private fun clickTag(activity: ComposeActivity, tag: String) { assertTrue(tagged(activity, tag).config[SemanticsActions.OnClick].action!!.invoke()); frame(activity) }
    private fun enter(activity: ComposeActivity, tag: String, value: String) { assertTrue(tagged(activity, tag).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frame(activity) }
    private fun save(activity: ComposeActivity, name: String) {
        val bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); windows(activity).forEach { it.draw(canvas) }
        val target = File(System.getProperty("pocketworld.a24d.renders"), name); target.parentFile!!.mkdirs(); target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; assertTrue(target.length() > 2000)
    }
    private class Fixture(extraArtifact: Boolean = false) : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val workspace = app.features.module("workspace")
        val project = workspace.handle("create", JSONObject().put("name", "原项目成果" )).getString("id")
        val task = app.tasks.createCapabilityTask("保存项目报告为固定成果，等待主人核对", "")
        val attempt = app.tasks.startAttempt(task.getString("id"), "capabilities", 8)
        val id = task.getString("id"); val run = attempt.getString("runId")
        val artifacts = mutableListOf<JSONObject>()
        val activityControl: org.robolectric.android.controller.ActivityController<ComposeActivity>
        val activity: ComposeActivity
        val controller: PocketWorldController
        init {
            val control = RunControl(); control.bindHostRun(id, run, attempt.getInt("coordinatorEpoch"))
            app.tasks.authorizeTools(id, run, JSONArray().put("workspace_create_artifact"))
            val journal = app.tasks.journal(id, run, attempt.getInt("coordinatorEpoch"), control)
            val port = HostToolSupervisor(app.features, app.tasks, id, run, attempt.getInt("coordinatorEpoch"), true)
            fun frozen(path: String, content: String) {
                workspace.handle("write", JSONObject().put("projectId", project).put("path", path).put("content", content).put("expectedVersion", 0))
                val args = JSONObject().put("projectId", project).put("path", path).put("version", 1)
                val approval = port.execute("workspace_create_artifact", args, control)
                assertEquals("approval_required", approval.getString("code")); app.tasks.decideCapabilityApproval(id, approval.getString("approvalId"), true)
                val op = journal.beginOperation("workspace_create_artifact", args)
                val artifact = port.execute("workspace_create_artifact", JSONObject(args.toString()).put("_hostOperationId", op), control)
                journal.finishOperation(op, JSONObject().put("ok", true).put("accepted", true).put("data", artifact)); artifacts.add(artifact)
            }
            frozen("src/report.txt", "FROZEN-ORIGINAL\n保留原始结果。")
            if (extraArtifact) frozen("src/second.txt", "SECOND-FROZEN-CONTENT")
            app.tasks.endAttempt(id, run, "needs_confirmation", "固定成果已保存，请主人核对内容。", JSONObject())
            val pending = Robolectric.buildActivity(ComposeActivity::class.java); pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            activityControl = pending.setup().visible().apply { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)) }.windowFocusChanged(true)
            activity = activityControl.get(); val field = ComposeActivity::class.java.getDeclaredField("controller"); field.isAccessible = true; controller = field.get(activity) as PocketWorldController
        }
        fun write(path: String, content: String, version: Int) = workspace.handle("write", JSONObject().put("projectId", project).put("path", path).put("content", content).put("expectedVersion", version))
        fun current(path: String) = workspace.handle("read", JSONObject().put("projectId", project).put("path", path))
        fun saved() = workspace.handle("readArtifact", JSONObject().put("artifactId", artifacts.first().getString("id")))
        override fun close() { ShadowDialog.getShownDialogs().forEach { it.dismiss() }; activityControl.pause().stop().destroy(); app.features.close() }
    }
    @Test fun originalTaskArtifactCurrentSourceEditAndProjectReturnKeepTheirIdentities() {
        Fixture().use { f ->
            await(f.activity) { f.controller.state.tasks.any { it.id == f.id } && workspaceArtifacts(f.controller.state).isNotEmpty() }
            enter(f.activity, "conversation_composer", "这段对话草稿还没有发送")
            f.write("src/report.txt", "CURRENT-SOURCE-v2", 1); f.controller.focusTask(f.id)
            await(f.activity) { nodes(f.activity).any { text(it) == "返回任务" } }
            clickTag(f.activity, "workspace_artifact_" + f.artifacts.first().getString("id")); await(f.activity) { f.controller.state.workspaceArtifactView?.content?.contains("FROZEN-ORIGINAL") == true }
            assertEquals(f.id, f.controller.state.workspaceArtifactView!!.fromTaskId); save(f.activity, "01-fixed-artifact-original-task.png")
            click(f.activity, "比较当前源文件"); await(f.activity) { f.controller.state.workspaceArtifactView?.diff?.version == 2 }
            assertTrue(f.controller.state.workspaceArtifactView!!.diff!!.text.contains("+CURRENT-SOURCE-v2")); save(f.activity, "02-fixed-versus-current-source.png")
            clickTag(f.activity, "workspace_artifact_edit_source"); await(f.activity) { f.controller.state.fileReady && f.controller.state.fileVersion == 2 }
            enter(f.activity, "project_file_editor", "OWNER-EDIT-v3\n这是明确保存的新源码。"); click(f.activity, "保存")
            await(f.activity) { !f.controller.state.fileSaving && f.controller.state.fileVersion == 3 }; assertEquals("OWNER-EDIT-v3\n这是明确保存的新源码。", f.current("src/report.txt").getString("content"))
            clickTag(f.activity, "project_file_return"); await(f.activity) { f.controller.state.workspaceArtifactView?.content?.contains("FROZEN-ORIGINAL") == true }
            assertEquals(1, f.saved().getInt("sourceVersion")); assertEquals(f.artifacts.first().getString("sha256"), f.saved().getString("sha256")); assertEquals("needs_confirmation", f.app.tasks.get(f.id, false).getString("status")); assertEquals(1, f.app.tasks.list().length())
            click(f.activity, "查看原项目"); await(f.activity) { nodes(f.activity).any { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "project_return_original_task" } }; assertEquals(f.project, f.controller.state.projectId); save(f.activity, "03-project-with-original-task-return.png")
            clickTag(f.activity, "project_return_original_task"); await(f.activity) { nodes(f.activity).any { text(it) == "返回任务" } }; click(f.activity, "返回任务")
            assertNull("an already-consumed native approval must not remain a current authorization notice", f.controller.state.notice)
            val tab = nodes(f.activity).first { text(it) == "对话" && it.config.contains(SemanticsProperties.Selected) && it.config.contains(SemanticsActions.OnClick) }; assertTrue(tab.config[SemanticsActions.OnClick].action!!.invoke()); frame(f.activity)
            assertEquals("这段对话草稿还没有发送", tagged(f.activity, "conversation_composer").config[SemanticsProperties.EditableText].text); assertEquals(1, f.app.tasks.list().length())
        }
    }
    @Test fun concurrentSourceSaveKeepsDraftAndOriginalArtifactUntilExplicitDiscard() {
        Fixture().use { f ->
            await(f.activity) { workspaceArtifacts(f.controller.state).isNotEmpty() }; val ref = workspaceArtifacts(f.controller.state).single()
            f.controller.openWorkspaceArtifact(ref); await(f.activity) { f.controller.state.workspaceArtifactView?.content != null }; clickTag(f.activity, "workspace_artifact_edit_source"); await(f.activity) { f.controller.state.fileReady }
            enter(f.activity, "project_file_editor", "UNSAVED-OWNER-DRAFT"); f.write(ref.path, "OTHER-SAVED-WRITER", 1); click(f.activity, "保存")
            await(f.activity) { !f.controller.state.fileSaving && f.controller.state.fileError?.contains("版本冲突") == true }
            assertEquals("UNSAVED-OWNER-DRAFT", f.controller.state.fileContent); assertEquals("OTHER-SAVED-WRITER", f.current(ref.path).getString("content")); assertTrue(f.saved().getString("content").contains("FROZEN-ORIGINAL")); save(f.activity, "04-source-conflict-keeps-draft.png")
            clickTag(f.activity, "project_file_return"); click(f.activity, "继续编辑"); assertEquals("UNSAVED-OWNER-DRAFT", tagged(f.activity, "project_file_editor").config[SemanticsProperties.EditableText].text)
            clickTag(f.activity, "project_file_return"); click(f.activity, "放弃修改并关闭"); await(f.activity) { f.controller.state.workspaceArtifactView?.content != null }
            assertEquals("OTHER-SAVED-WRITER", f.current(ref.path).getString("content")); assertEquals(2, f.current(ref.path).getInt("version")); assertTrue(f.controller.state.workspaceArtifactView!!.content!!.contains("FROZEN-ORIGINAL")); assertEquals(1, f.app.tasks.list().length())
        }
    }
    @Test fun lateOriginalReadAndAnotherFileDraftCannotReplaceCurrentSelection() {
        Fixture(extraArtifact = true).use { f ->
            await(f.activity) { workspaceArtifacts(f.controller.state).size == 2 }; val first = workspaceArtifacts(f.controller.state).first { it.path == "src/report.txt" }; val second = workspaceArtifacts(f.controller.state).first { it.path == "src/second.txt" }
            f.controller.openWorkspaceArtifact(first); f.controller.closeWorkspaceArtifact(); f.controller.openWorkspaceArtifact(second)
            await(f.activity) { f.controller.state.workspaceArtifactView?.content == "SECOND-FROZEN-CONTENT" }; assertEquals(second.id, f.controller.state.workspaceArtifactView!!.source.id)
            f.controller.closeWorkspaceArtifact(); f.controller.selectProject(f.project); f.controller.openFile(first.path); await(f.activity) { f.controller.state.fileReady }
            enter(f.activity, "project_file_editor", "KEEP-THIS-OTHER-FILE-DRAFT"); f.controller.openWorkspaceArtifact(second); await(f.activity) { f.controller.state.workspaceArtifactView?.content != null }
            clickTag(f.activity, "workspace_artifact_edit_source"); assertTrue(f.controller.state.workspaceArtifactView!!.error!!.contains("未保存")); assertEquals(first.path, f.controller.state.filePath); assertEquals("KEEP-THIS-OTHER-FILE-DRAFT", f.controller.state.fileContent); save(f.activity, "05-other-file-draft-not-replaced.png")
            click(f.activity, "回到现有编辑区"); assertEquals("KEEP-THIS-OTHER-FILE-DRAFT", tagged(f.activity, "project_file_editor").config[SemanticsProperties.EditableText].text); assertEquals("SECOND-FROZEN-CONTENT", f.current(second.path).getString("content")); assertEquals(1, f.app.tasks.list().length())
        }
    }
}
