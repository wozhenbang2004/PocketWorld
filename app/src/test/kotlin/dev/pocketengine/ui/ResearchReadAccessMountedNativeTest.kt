package dev.pocketengine.ui

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.FeatureHost
import dev.pocketengine.features.teams.*
import dev.pocketengine.features.workspace.WorkspaceModule
import dev.pocketengine.store.SecretVault
import dev.pocketengine.store.SettingsStore
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.web.PocketWebTeamEvidence
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/** Actual Web presentation adapter -> original member Page -> native SQL scope review.
 * The controlled coordinator never reads public HTTP or calls a model. React consent has pure tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ResearchReadAccessMountedNativeTest {
    private class Reply {
        var calls = 0; var value: JSONObject? = null; var error: String? = null
        val done: (JSONObject?, String?) -> Unit = { value, error -> calls++; this.value = value; this.error = error }
    }
    private class Fixture : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as Application
        val tasks: TaskStore; val features: FeatureHost; val workspace: WorkspaceModule; val coordinator: TeamCoordinator
        val host: TeamMemberEvidenceHost; val bridge: PocketWebTeamEvidence
        val run = UUID.randomUUID().toString(); val project: String
        val release = CountDownLatch(1); val io = Executors.newSingleThreadExecutor()
        @Volatile var ownerWindow = 0
        @Volatile var foreground = true
        init {
            app.deleteDatabase("pocket-world.db"); tasks = TaskStore(app); features = FeatureHost({}, tasks) { false }
            workspace = WorkspaceModule(FeatureContext(app, SettingsStore(app), SecretVault(app)) {}); features.register(workspace)
            project = workspace.handle("create", JSONObject().put("name", "原项目 Web scope")).getString("id")
            workspace.handle("write", JSONObject().put("projectId", project).put("path", "notes.txt").put("expectedVersion", 0).put("content", "WEB_NATIVE_SELECTED_FILE"))
            host = TeamMemberEvidenceHost(features, tasks, { error("no private source was selected") }) { error("no private source metadata was selected") }
            val entered = CountDownLatch(1)
            coordinator = TeamCoordinator(File(app.filesDir, "research-web-${UUID.randomUUID()}.json"), TeamCoordinator.ToolGateway { _, _, _, _ -> error("no model in an owner-selection test") }) {}
            coordinator.setBudgetHost(TaskStoreTeamWriteHost(tasks, TeamWorkspaceAccess(workspace)) {})
            coordinator.setResearchFactory { _, _, _, _ -> entered.countDown(); release.await(60, TimeUnit.SECONDS); JSONObject().put("status", "interrupted").put("message", "owner metadata fixture only") }
            tasks.beginExternal("teams", "start", run, JSONObject().put("goal", "显选研究范围"))
            val definition = JSONObject().put("id", UUID.randomUUID().toString()).put("name", "原研究 Web").put("mode", "collaborate").put("leaderId", "a").put("members", JSONArray().put(JSONObject().put("id", "a").put("name", "原成员").put("role", "只读").put("model", "fixture")))
            coordinator.start(definition, "不自动执行或分享", 1, ModelConfig("https://fixture.invalid/v1", "", "fixture", false, 8, 10), run, host, null)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            bridge = PocketWebTeamEvidence({ ownerWindow }, { foreground }, { r, m -> host.open(coordinator, r, m) }, io::execute)
        }
        fun start(action: String, args: JSONObject): Reply = Reply().also { try { bridge.dispatch(action, args, it.done) } catch (failure: Exception) { it.done(null, failure.message) } }
        fun finish(reply: Reply): Reply {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (reply.calls == 0 && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, reply.calls); return reply
        }
        fun request(action: String, args: JSONObject): JSONObject { val reply = finish(start(action, args)); assertNull(reply.error, reply.error); return reply.value ?: error("Missing original receipt") }
        fun reject(action: String, args: JSONObject) { val reply = finish(start(action, args)); assertNull(reply.value); assertNotNull(reply.error) }
        fun page() = request("teamEvidence.open", JSONObject().put("runId", run).put("memberId", "a"))
        fun review(shared: Boolean = false): JSONObject {
            val page = page(); val projects = page.getJSONObject("catalogue").getJSONArray("researchProjects")
            val choice = (0 until projects.length()).map { projects.getJSONObject(it) }.single { it.getString("id") == project }
            val files = request("teamEvidence.files", JSONObject().put("pageHandle", page.getString("pageHandle")).put("projectHandle", choice.getString("choiceHandle")))
            val file = files.getJSONArray("files").getJSONObject(0)
            val input = JSONObject().put("pageHandle", page.getString("pageHandle")).put("scope", if (shared) "current_members" else "member")
                .put("records", JSONArray()).put("sources", JSONArray()).put("skills", JSONArray()).put("resources", JSONArray())
                .put("webOrigins", JSONArray().put("https://example.org")).put("projectFiles", JSONArray().put(file.getString("choiceHandle")))
            assertEquals(0L, tasks.teamMemberMaterialScope(run, "a").getLong("revision"))
            val review = request("teamEvidence.review", input)
            val scope = if (shared) review.getJSONObject("review").getJSONArray("members").getJSONObject(0).getJSONObject("review") else review.getJSONObject("review")
            val original = scope.getJSONObject("researchReadAccess").getJSONArray("projectFiles").getJSONObject(0)
            assertEquals("notes.txt", original.getString("path")); assertEquals(1, original.getInt("version"))
            request("teamEvidence.consent", JSONObject().put("reviewHandle", review.getString("reviewHandle")).put("accepted", true))
            return review
        }
        fun confirm(review: JSONObject) = JSONObject().put("reviewHandle", review.getString("reviewHandle"))
        fun unchanged() { assertEquals(0L, tasks.teamMemberMaterialScope(run, "a").getLong("revision")); assertEquals(0, tasks.teamBudgetSnapshot(run).getInt("toolOperationsReservedOrUsed")) }
        fun hold(): CountDownLatch { val entered = CountDownLatch(1); val release = CountDownLatch(1); io.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }; assertTrue(entered.await(5, TimeUnit.SECONDS)); return release }
        override fun close() {
            bridge.clear(); io.shutdownNow(); release.countDown(); coordinator.close()
            for (name in listOf("coordinator", "members")) assertTrue((TeamCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(coordinator) as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
            features.close(); (TaskStore::class.java.getDeclaredField("db").apply { isAccessible = true }.get(tasks) as SQLiteDatabase).close()
        }
    }
    @Test fun changedDraftRetiresTheOldMemberConfirmation() {
        Fixture().use { f -> val original = f.review(); f.request("teamEvidence.cancelReview", f.confirm(original)); f.reject("teamEvidence.confirm", f.confirm(original)); f.unchanged() }
    }
    @Test fun realNativeReviewConfirmsOnlyTheExactSelectedFileAndSiteWithoutStartingARead() {
        Fixture().use { f ->
            val review = f.review(); val result = f.request("teamEvidence.confirm", f.confirm(review)); assertTrue(result.getBoolean("scopeSaved")); assertFalse(result.getBoolean("executionAuthorized"))
            val saved = f.tasks.teamMemberMaterialScope(f.run, "a"); assertEquals(1L, saved.getLong("revision"))
            val scope = saved.getJSONObject("researchReadAccess"); assertEquals("https://example.org", scope.getJSONArray("webOrigins").getString(0))
            val file = scope.getJSONArray("projectFiles").getJSONObject(0); assertEquals(f.project, file.getString("projectId")); assertEquals("notes.txt", file.getString("path")); assertEquals(1, file.getInt("version")); assertEquals(64, file.getString("sha256").length)
            assertEquals(0, f.tasks.teamBudgetSnapshot(f.run).getInt("toolOperationsReservedOrUsed")); f.reject("teamEvidence.confirm", f.confirm(review)); assertEquals(1L, f.tasks.teamMemberMaterialScope(f.run, "a").getLong("revision"))
        }
    }
    @Test fun changedSharedDraftRetiresTheOldCurrentMembersConfirmation() {
        Fixture().use { f -> val original = f.review(shared = true); assertFalse(original.getJSONObject("review").getBoolean("newMembersInherit")); f.request("teamEvidence.cancelReview", f.confirm(original)); f.reject("teamEvidence.confirm", f.confirm(original)); f.unchanged() }
    }
    @Test fun withdrawingMemberConsentRejectsAnAlreadyQueuedConfirm() {
        Fixture().use { f ->
            val review = f.review(); val release = f.hold(); val queued = f.start("teamEvidence.confirm", f.confirm(review))
            f.request("teamEvidence.consent", f.confirm(review).put("accepted", false)); release.countDown()
            assertNull(f.finish(queued).value); assertNotNull(queued.error); f.unchanged()
        }
    }
    @Test fun withdrawingAndRestoringSharedConsentCannotReviveTheCapturedOldConfirm() {
        Fixture().use { f ->
            val review = f.review(shared = true); val release = f.hold(); val queued = f.start("teamEvidence.confirm", f.confirm(review))
            f.request("teamEvidence.consent", f.confirm(review).put("accepted", false)); f.request("teamEvidence.consent", f.confirm(review).put("accepted", true)); release.countDown()
            assertNull(f.finish(queued).value); assertNotNull(queued.error); f.unchanged()
        }
    }
    @Test fun changedFileVersionCannotBeSilentlySubstitutedDuringConfirmation() {
        Fixture().use { f ->
            val review = f.review(); f.workspace.handle("write", JSONObject().put("projectId", f.project).put("path", "notes.txt").put("expectedVersion", 1).put("content", "CHANGED_FILE_AFTER_REVIEW"))
            f.reject("teamEvidence.confirm", f.confirm(review)); f.unchanged()
        }
    }
    @Test fun losingAndRestoringForegroundDoesNotReviveAQueuedApproval() {
        Fixture().use { f ->
            val review = f.review(); val release = f.hold(); val queued = f.start("teamEvidence.confirm", f.confirm(review))
            f.foreground = false; f.bridge.clear(); f.ownerWindow++; f.foreground = true; release.countDown()
            assertNull(f.finish(queued).value); assertNotNull(queued.error); f.unchanged(); f.reject("teamEvidence.confirm", f.confirm(review))
        }
    }
}
