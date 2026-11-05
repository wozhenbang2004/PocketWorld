package dev.pocketengine.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.NativePluginHost
import dev.pocketengine.features.plugins.PluginsModule
import dev.pocketengine.features.plugins.distribution.FunctionalPackNativeJourneyTest
import dev.pocketengine.features.skills.SkillsModule
import dev.pocketengine.store.SecretVault
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.web.PocketWebFunctionalPacks
import java.io.ByteArrayInputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
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
import org.robolectric.annotation.LooperMode

/** Actual system document choice, web adapter, native package Page and original component/SQL stores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FunctionalPackControlsSelectionTest {
    private data class Reply(var value: JSONObject? = null, var error: String? = null, var delivered: Boolean = false) {
        fun value(): JSONObject { assertTrue("Original adapter reply", delivered); assertNull(error); return requireNotNull(value) }
    }
    private class Fixture : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as Application
        val tasks = TaskStore(app)
        val plugins = PluginsModule(FeatureContext(app, null, SecretVault(app)) {})
        val skills = SkillsModule(FeatureContext(app, null, SecretVault(app)) {})
        val host = NativePluginHost(app, plugins, tasks,
            { binding, control -> tasks.checkExtensionRun(binding.taskId, binding.runId, binding.epoch, control) }, {})
        val queue = ArrayDeque<Runnable>()
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "functional-pack-web-worker").apply { isDaemon = true } }
        val owner = Robolectric.buildActivity(FunctionalPackFixtureActivity::class.java)
        val activity = owner.setup().visible().get()
        var foreground = true
        private var destroyed = false
        val documents = FunctionalPackDocumentPicker(activity.activityResultRegistry, app.contentResolver, { !destroyed }) { 1L }
        val adapter: PocketWebFunctionalPacks
        var pageId = ""
        init {
            host.mountFunctionalPacks(skills)
            adapter = PocketWebFunctionalPacks(host::newFunctionalPackPage, { queue.add(it) }, { foreground && !destroyed }, documents)
        }
        fun request(command: String, args: JSONObject = JSONObject()): Reply {
            val reply = Reply()
            if (command != "open") args.put("pageId", pageId)
            try { adapter.dispatch("functionalPacks.$command", args) { value, failure -> reply.value = value; reply.error = failure; reply.delivered = true } }
            catch (failure: Exception) { reply.error = failure.message; reply.delivered = true }
            return reply
        }
        fun runNext() { worker.submit(queue.removeFirst()).get(10, TimeUnit.SECONDS) }
        fun settle() { shadowOf(Looper.getMainLooper()).idle() }
        fun drain() { while (queue.isNotEmpty()) { runNext(); settle() } }
        fun call(command: String, args: JSONObject = JSONObject()): JSONObject = request(command, args).also { drain() }.value()
        fun open(): JSONObject = call("open").also { pageId = it.getString("pageId") }
        fun inspect(): JSONObject {
            open(); val choice = call("chooseFile").getJSONObject("document")
            val launch = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
            val uri = Uri.parse("content://functional-pack-fixture/exact.json")
            shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(FunctionalPackNativeJourneyTest.packageBytes("ui", false)))
            shadowOf(activity).receiveResult(launch.intent, Activity.RESULT_OK, Intent().setData(uri)); settle()
            assertEquals("selected", documents.snapshot().getString("status"))
            return call("inspect", JSONObject().put("requestId", choice.getString("requestId")))
        }
        fun reviewArgs(review: JSONObject) = JSONObject().put("reviewHandle", review.getString("reviewHandle")).put("selectionRevision", review.getLong("selectionRevision"))
        fun select(review: JSONObject, method: Boolean): JSONObject = call("select", reviewArgs(review).put("selectedKeys", JSONArray().put("data").put("transform").also { if (method) it.put("method") }))
        fun confirm(review: JSONObject): Reply = request("confirm", reviewArgs(review).put("consent", true))
        fun pause() { foreground = false; adapter.clear(); owner.pause() }
        fun destroy() { if (!destroyed) { val wasForeground = foreground; destroyed = true; foreground = false; adapter.clear(); documents.close(); if (wasForeground) owner.pause(); owner.stop().destroy(); settle() } }
        fun seed() { worker.submit { host.newFunctionalPackPage().use { page -> page.setForeground(true); val review = page.inspect(ByteArrayInputStream(FunctionalPackNativeJourneyTest.packageBytes("remove", false))); page.submit(review, review.digest) } }.get(10, TimeUnit.SECONDS) }
        override fun close() { destroy(); worker.shutdownNow(); host.close(); plugins.close() }
    }
    private fun rejected(reply: Reply) { assertTrue(reply.delivered); assertNotNull(reply.error); assertNull(reply.value) }

    @Test fun removingAComponentCannotReacknowledgeTheOlderFullReview() {
        Fixture().use { f ->
            val full = f.inspect(); val changed = f.select(full, false)
            assertFalse(changed.getBoolean("selectionMatches")); rejected(f.confirm(changed))
            assertTrue(f.queue.isEmpty()); assertEquals(0, f.tasks.list().length())
            val reviewed = f.call("reviewSelection", f.reviewArgs(changed))
            assertTrue(reviewed.getBoolean("selectionMatches")); assertFalse(reviewed.getJSONObject("data").getBoolean("wholePackageSelected"))
            val accepted = f.confirm(reviewed); f.drain(); assertEquals("partial", accepted.value().getJSONObject("receipt").getString("status"))
            assertEquals(0, f.skills.snapshot().getJSONArray("skills").length())
            assertEquals(1, f.plugins.snapshot().getJSONArray("plugins").length()); assertEquals(1, f.tasks.list().length())
        }
    }
    @Test fun addingAComponentCannotReacknowledgeTheOlderSubsetReview() {
        Fixture().use { f ->
            val subset = f.call("reviewSelection", f.reviewArgs(f.select(f.inspect(), false)))
            val changed = f.select(subset, true); assertFalse(changed.getBoolean("selectionMatches"))
            rejected(f.confirm(changed)); assertTrue(f.queue.isEmpty()); assertEquals(0, f.tasks.list().length())
            val exactAgain = f.select(changed, false); assertTrue(exactAgain.getBoolean("selectionMatches"))
            val result = f.confirm(exactAgain); f.drain(); result.value()
            assertEquals(0, f.skills.snapshot().getJSONArray("skills").length())
            assertEquals(1, f.plugins.snapshot().getJSONArray("plugins").length()); assertEquals(1, f.tasks.list().length())
        }
    }
    @Test fun aPreviouslyEnabledSubmitCallbackRechecksTheLiveChoiceBeforeQueueing() {
        Fixture().use { f ->
            val full = f.inspect(); val previouslyEnabled = f.reviewArgs(full).put("consent", true)
            f.select(full, false); rejected(f.request("confirm", previouslyEnabled))
            assertTrue("No stale submission may enter the original worker", f.queue.isEmpty()); assertEquals(0, f.tasks.list().length())
        }
    }
    @Test fun queuedSubmitStillRequiresTheOriginalPageInTheForeground() {
        Fixture().use { f ->
            val original = f.confirm(f.inspect()); assertEquals(1, f.queue.size)
            f.pause(); f.drain(); rejected(original)
            assertEquals(0, f.tasks.list().length()); assertEquals(0, f.skills.snapshot().getJSONArray("skills").length())
        }
    }
    @Test fun aReviewReplyAfterDisposalDoesNotRestoreConsentOrInstallAnything() {
        Fixture().use { f ->
            val changed = f.select(f.inspect(), false)
            val delayed = f.request("reviewSelection", f.reviewArgs(changed)); f.runNext()
            f.destroy(); rejected(delayed)
            assertTrue(f.queue.isEmpty()); assertEquals(0, f.tasks.list().length()); assertEquals(0, f.skills.snapshot().getJSONArray("skills").length())
        }
    }
    @Test fun uninstallReviewStillAllowsExplicitAcknowledgementAndExactRemoval() {
        Fixture().use { f ->
            f.seed(); val record = f.open().getJSONObject("snapshot").getJSONArray("packs").getJSONObject(0)
            val review = f.call("reviewRemove", JSONObject().put("id", record.getString("id")).put("installationId", record.getString("installationId")).put("revision", record.getLong("revision")))
            assertTrue(review.getJSONObject("data").getBoolean("removing")); assertTrue(review.getJSONObject("data").getBoolean("ready"))
            rejected(f.request("confirm", f.reviewArgs(review).put("consent", false))); assertTrue(f.queue.isEmpty())
            val confirmed = f.confirm(review); f.drain(); assertEquals("completed", confirmed.value().getJSONObject("receipt").getString("status"))
            assertEquals(0, f.skills.snapshot().getJSONArray("skills").length()); assertEquals(0, f.plugins.snapshot().getJSONArray("plugins").length()); assertEquals(2, f.tasks.list().length())
        }
    }
    @Test fun pickerReturnCanBeReadInANewPageButNeverRestoresTheOldReview() {
        Fixture().use { f ->
            val old = f.inspect(); val selected = f.documents.snapshot().getString("requestId")
            f.pause(); f.foreground = true; f.owner.resume(); f.open()
            rejected(f.confirm(old)); assertEquals(0, f.tasks.list().length())
            // A fresh exact file read is the only way to get a new Review after returning.
            val uri = Uri.parse("content://functional-pack-fixture/exact.json")
            shadowOf(f.app.contentResolver).registerInputStream(uri, ByteArrayInputStream(FunctionalPackNativeJourneyTest.packageBytes("ui", false)))
            val fresh = f.call("inspect", JSONObject().put("requestId", selected))
            assertNotEquals(old.getString("reviewHandle"), fresh.getString("reviewHandle")); assertEquals(0, f.tasks.list().length())
        }
    }
}
class FunctionalPackFixtureActivity : ComponentActivity()
