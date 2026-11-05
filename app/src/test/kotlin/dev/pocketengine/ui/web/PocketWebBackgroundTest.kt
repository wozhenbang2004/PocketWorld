package dev.pocketengine.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.EngineApplication
import dev.pocketengine.media.MediaController
import dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery
import dev.pocketengine.phone.setup.OwnerPhoneApps
import dev.pocketengine.testutil.ReadyApplicationFixture
import dev.pocketengine.ui.ComposeActivity
import dev.pocketengine.ui.PocketUiState
import dev.pocketengine.ui.PocketWorldController
import java.security.Provider
import java.security.Security
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real Controller, owner I/O queue, display policy and Android clipboard. No device-task success is inferred. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PocketWebBackgroundTest {
    private val keys = object : Provider("PocketWebBackground-${UUID.randomUUID()}", 1.0, "Explicit test-only AndroidKeyStore") {
        init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
    }
    @Before fun installKeys() { assertTrue(Security.insertProviderAt(keys, 1) > 0) }
    @After fun removeKeys() { Security.removeProvider(keys.name) }

    private class Reply {
        var calls = 0; var value: JSONObject? = null; var error: String? = null
        val callback: (JSONObject?, String?) -> Unit = { result, failure -> calls++; value = result; error = failure }
    }
    private class Rig : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        private val owner: ActivityController<ComposeActivity>
        val activity: ComposeActivity
        val media: MediaController
        val controller: PocketWorldController
        var bridgeGeneration = 0L
        val background: PocketWebBackground
        val clipboard: ClipboardManager
        val worker: ExecutorService
        init {
            ReadyApplicationFixture.initializeHost(app)
            owner = Robolectric.buildActivity(ComposeActivity::class.java); activity = owner.get()
            media = MediaController(activity, app::publish)
            ComposeActivity::class.java.getDeclaredField("media").apply { isAccessible = true }.set(activity, media)
            controller = PocketWorldController(activity, app)
            worker = PocketWorldController::class.java.getDeclaredField("io").apply { isAccessible = true }.get(controller) as ExecutorService
            foreground(true)
            state(controller.state.copy(backgroundSetupOpen = true, features = JSONObject(controller.state.features.toString())
                .put("display", app.features.module("display")!!.snapshot())))
            background = PocketWebBackground(controller,
                { PocketWebWindowTicket(bridgeGeneration, controller.webWindowTicket()) },
                { it.accepts(bridgeGeneration, controller.webWindowTicket()) }, {})
            clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("owner marker", "unchanged owner marker"))
        }
        fun foreground(value: Boolean) { PocketWorldController::class.java.getDeclaredField("voiceForeground").apply { isAccessible = true }.setBoolean(controller, value) }
        fun domain(value: String?) { PocketWorldController::class.java.getDeclaredField("ownerDomain").apply { isAccessible = true }.set(controller, value) }
        fun state(value: PocketUiState) { PocketWorldController::class.java.getDeclaredMethod("setState", PocketUiState::class.java).apply { isAccessible = true }.invoke(controller, value) }
        fun property(name: String, value: Any) {
            val setter = "set" + name.replaceFirstChar { it.uppercase() }
            PocketWorldController::class.java.declaredMethods.single { (it.name == setter || it.name.startsWith(setter + '$')) && it.parameterCount == 1 }
                .apply { isAccessible = true }.invoke(controller, value)
        }
        fun display(changes: JSONObject) {
            val value = JSONObject(controller.state.feature("display").toString())
            changes.keys().forEach { value.put(it, changes.get(it)) }
            state(controller.state.copy(features = JSONObject(controller.state.features.toString()).put("display", value)))
        }
        fun drain() { worker.submit {}.get(5, TimeUnit.SECONDS); Shadows.shadowOf(Looper.getMainLooper()).idle() }
        fun finish(reply: Reply): Reply { repeat(10) { if (reply.calls == 0) drain() }; assertEquals(1, reply.calls); return reply }
        fun request(action: String, args: JSONObject = JSONObject()): Reply = Reply().also { background.dispatch(action, args, it.callback); finish(it) }
        fun reject(action: String, args: JSONObject) {
            val reply = Reply()
            assertThrows(RuntimeException::class.java) { background.dispatch(action, args, reply.callback) }
            assertEquals("Rejected commands must not masquerade as accepted or mutate in a callback", 0, reply.calls)
        }
        fun holdQueue(): CountDownLatch {
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            worker.execute { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(started.await(3, TimeUnit.SECONDS)); return release
        }
        fun clip() = clipboard.primaryClip!!.getItemAt(0).text.toString()
        override fun close() {
            try { background.clear(); controller.detach() }
            finally { try { media.close(); owner.close() } finally { ReadyApplicationFixture.closeHost(app) } }
        }
    }
    private fun copy(user: Int = 0, version: Long = 1) = OwnerPhoneApps.Selection("Test copy", "dev.example.test", user,
        10L + user, user * 100000 + 10001, version, "native-installation-$user-$version", user == 0, SystemClock.uptimeMillis())
    private fun policy(handle: String, revision: Long = 3) = JSONObject().put("selectionHandle", handle)
        .put("automation", false).put("ownerShare", true).put("expectedRevision", revision)

    @Test fun privateForegroundSetupIsRequiredAndLeavingItRetiresHandles() {
        Rig().use { r ->
            val initial = AndroidPhoneAudioRecovery.silenceEnabled(r.app)
            for (domain in listOf("groups", "teams")) {
                r.domain(domain); assertSame(JSONObject.NULL, r.background.snapshot())
                r.reject("background.audio.set", JSONObject().put("enabled", false))
            }
            r.domain(null); r.foreground(false); r.reject("background.audio.set", JSONObject().put("enabled", false))
            r.foreground(true); r.state(r.controller.state.copy(backgroundSetupOpen = false))
            r.reject("background.diagnostics.preview", JSONObject())
            assertEquals(initial, AndroidPhoneAudioRecovery.silenceEnabled(r.app))
        }
    }

    @Test fun appCopyHandlesNeverAcceptWebPackageIdentityOrAnotherWindow() {
        Rig().use { r ->
            r.property("phoneOwnerApps", listOf(copy(), copy(10)))
            r.display(JSONObject().put("appAccess", JSONObject().put("available", true).put("revision", 3)))
            val apps = (r.background.snapshot() as JSONObject).getJSONArray("apps")
            val handle = apps.getJSONObject(0).getString("selectionHandle")
            assertNotEquals(handle, apps.getJSONObject(1).getString("selectionHandle"))
            assertEquals(0, apps.getJSONObject(0).getInt("userId")); assertEquals(10, apps.getJSONObject(1).getInt("userId"))
            assertFalse(apps.toString().contains("native-installation"))
            r.reject("background.apps.set", policy("forged"))
            r.reject("background.apps.set", policy(handle).put("packageName", "another.app"))
            r.reject("background.apps.set", policy(handle).put("userId", 10))
            r.reject("background.apps.set", policy(handle, 2))
            r.bridgeGeneration++
            r.reject("background.apps.set", policy(handle))
            assertFalse(r.controller.phoneOwnerPolicyBusy)
        }
    }

    @Test fun refreshedInstallationAndPendingListingCannotReuseAnOldAppSelection() {
        Rig().use { r ->
            r.property("phoneOwnerApps", listOf(copy()))
            r.display(JSONObject().put("appAccess", JSONObject().put("available", true).put("revision", 3)))
            val first = (r.background.snapshot() as JSONObject).getJSONArray("apps").getJSONObject(0).getString("selectionHandle")
            r.property("phoneOwnerApps", listOf(copy(version = 2)))
            r.reject("background.apps.set", policy(first))
            val second = (r.background.snapshot() as JSONObject).getJSONArray("apps").getJSONObject(0).getString("selectionHandle")
            r.property("phoneOwnerAppsLoading", true)
            assertEquals(0, (r.background.snapshot() as JSONObject).getJSONArray("apps").length())
            r.reject("background.apps.set", policy(second)); assertFalse(r.controller.phoneOwnerPolicyBusy)
        }
    }

    @Test fun runningTaskMayDisableFutureSilencingButCannotEnableItOrRestoreAudio() {
        Rig().use { r ->
            AndroidPhoneAudioRecovery.setOwnerSilenceEnabled(r.app, true)
            r.display(JSONObject().put("audioSilenceEnabled", true).put("taskOwned", true).put("bootstrapBusy", false))
            r.reject("background.audio.set", JSONObject().put("enabled", true))
            r.reject("background.audio.restore", JSONObject().put("confirmed", true))
            val result = r.request("background.audio.set", JSONObject().put("enabled", false))
            assertNull(result.error); assertTrue(result.value!!.getBoolean("persisted"))
            assertFalse(result.value!!.getBoolean("enabled")); assertFalse(AndroidPhoneAudioRecovery.silenceEnabled(r.app))
            assertFalse((r.background.snapshot() as JSONObject).getBoolean("audioBusy"))
        }
    }

    @Test fun queuedAudioMutationCannotExecuteAfterItsOwnerNavigationChanges() {
        Rig().use { r ->
            AndroidPhoneAudioRecovery.setOwnerSilenceEnabled(r.app, false)
            val release = r.holdQueue(); val reply = Reply()
            try {
                r.background.dispatch("background.audio.set", JSONObject().put("enabled", true), reply.callback)
                r.state(r.controller.state.copy(conversationId = "changed-owner-window"))
            } finally { release.countDown() }
            r.finish(reply); assertNull(reply.value); assertNotNull(reply.error)
            assertFalse(AndroidPhoneAudioRecovery.silenceEnabled(r.app))
            assertFalse((r.background.snapshot() as JSONObject).getBoolean("audioBusy"))
        }
    }

    @Test fun queuedAudioMutationCannotExecuteAfterAppLeavesForeground() {
        Rig().use { r ->
            AndroidPhoneAudioRecovery.setOwnerSilenceEnabled(r.app, false)
            val release = r.holdQueue(); val reply = Reply()
            try {
                r.background.dispatch("background.audio.set", JSONObject().put("enabled", true), reply.callback)
                r.foreground(false); r.background.clear()
            } finally { release.countDown() }
            r.finish(reply); assertNull(reply.value); assertNotNull(reply.error)
            assertFalse(AndroidPhoneAudioRecovery.silenceEnabled(r.app))
        }
    }

    @Test fun clearingTheWebWindowRetiresAPendingNativeDiagnosticEvenWhenControllerWindowIsUnchanged() {
        Rig().use { r ->
            val release = r.holdQueue(); val reply = Reply()
            try { r.background.dispatch("background.diagnostics.preview", JSONObject(), reply.callback); r.background.clear() }
            finally { release.countDown() }
            r.finish(reply); assertNull(reply.value); assertNotNull(reply.error)
            assertFalse((r.background.snapshot() as JSONObject).getBoolean("diagnosticBusy"))
            val fresh = r.request("background.diagnostics.preview")
            assertNull(fresh.error); assertTrue(fresh.value!!.getString("text").contains("pocketworld.phone.diagnostics.v1"))
            assertEquals("unchanged owner marker", r.clip())
        }
    }

    @Test fun diagnosticsCopyUsesOnlyTheExactPreviewHandleInItsOriginalWindow() {
        Rig().use { r ->
            val preview = r.request("background.diagnostics.preview").value!!
            assertEquals("unchanged owner marker", r.clip())
            val handle = preview.getString("reportHandle")
            r.reject("background.diagnostics.copy", JSONObject().put("reportHandle", "forged"))
            r.reject("background.diagnostics.copy", JSONObject().put("reportHandle", handle).put("text", "forged"))
            val result = r.request("background.diagnostics.copy", JSONObject().put("reportHandle", handle))
            assertNull(result.error); assertTrue(result.value!!.getBoolean("copied")); assertEquals(preview.getString("text"), r.clip())
            r.bridgeGeneration++; r.reject("background.diagnostics.copy", JSONObject().put("reportHandle", handle))
            assertEquals(0, r.app.tasks.list().length())
        }
    }

    @Test fun rejectedNativeExecutorSettlesAudioAndDiagnosticCallbacksWithoutStuckBusyState() {
        Rig().use { r ->
            r.worker.shutdownNow()
            val audio = Reply(); r.background.dispatch("background.audio.set", JSONObject().put("enabled", false), audio.callback)
            assertEquals(1, audio.calls); assertNull(audio.value); assertNotNull(audio.error)
            val diagnostic = Reply(); r.background.dispatch("background.diagnostics.preview", JSONObject(), diagnostic.callback)
            assertEquals(1, diagnostic.calls); assertNull(diagnostic.value); assertNotNull(diagnostic.error)
            val snapshot = r.background.snapshot() as JSONObject
            assertFalse(snapshot.getBoolean("audioBusy")); assertFalse(snapshot.getBoolean("diagnosticBusy"))
        }
    }
}
