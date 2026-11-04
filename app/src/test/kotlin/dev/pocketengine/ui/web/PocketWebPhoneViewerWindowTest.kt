package dev.pocketengine.ui.web

import dev.pocketengine.EngineApplication
import dev.pocketengine.media.voice.input.VoiceDraftInput
import dev.pocketengine.media.voice.tasks.OwnerTaskAnnouncements
import dev.pocketengine.media.voice.tasks.VoiceTaskReadout
import dev.pocketengine.ui.ComposeActivity
import dev.pocketengine.ui.PocketUiState
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.ProjectWorkbenchSession
import dev.pocketengine.ui.UiTask
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowApplication

/** Real controller navigation setters and viewer callback fencing, with controlled
 * foreground/frame inputs. No engine startup, task admission or device pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [PocketWebPhoneViewerWindowTest.NoStartup::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PocketWebPhoneViewerWindowTest {
    @Implements(EngineApplication::class)
    class NoStartup : ShadowApplication() { @Implementation fun onCreate() {} }

    private class Fixture : AutoCloseable {
        val controller = PocketWorldController(ComposeActivity(), RuntimeEnvironment.getApplication() as EngineApplication)
        private val announcements = OwnerTaskAnnouncements({ error("No task read expected") }, { false },
            Proxy.newProxyInstance(VoiceTaskReadout.Audio::class.java.classLoader, arrayOf(VoiceTaskReadout.Audio::class.java)) { _, _, _ ->
                error("No audio operation expected")
            } as VoiceTaskReadout.Audio, {})
        var pendingCurrent: (() -> Boolean)? = null
        var pendingReply: ((JSONObject?, String?, String) -> Unit)? = null
        var result: JSONObject? = null
        var errorCode = ""
        val viewer = PocketWebPhoneViewer(controller::webPrivateOwnerVisible,
            { PocketWebWindowTicket(1, controller.webPhoneViewerWindowTicket()) },
            { it.accepts(1, controller.webPhoneViewerWindowTicket()) },
            { id -> controller.state.tasks.find { it.id == id } },
            { _, current, reply -> pendingCurrent = current; pendingReply = reply }, { 1000L })

        init {
            setField("projectWorkbench\$delegate", lazyOf(ProjectWorkbenchSession(RuntimeEnvironment.getApplication())))
            setField("taskAnnouncements\$delegate", lazyOf(announcements))
            setField("voiceForeground", true)
            update(PocketUiState(conversationId = "original-conversation", activeTaskId = "a", tasks = listOf(task("a"), task("b"))))
        }
        private fun field(name: String) = PocketWorldController::class.java.getDeclaredField(name).apply { isAccessible = true }
        private fun setField(name: String, value: Any?) { field(name).set(controller, value) }
        fun update(value: PocketUiState) {
            PocketWorldController::class.java.getDeclaredMethod("setState", PocketUiState::class.java).apply { isAccessible = true }.invoke(controller, value)
        }
        fun window(name: String, value: String) {
            PocketWorldController::class.java.getDeclaredMethod(name, String::class.java).apply { isAccessible = true }.invoke(controller, value)
        }
        fun openB() {
            viewer.dispatch("phone.viewer.open", JSONObject().put("taskId", "b").put("runId", "run-b").put("epoch", 1)) { value, error, code ->
                assertNull(error); assertEquals("", code); result = value
            }
            val handle = result!!.getString("viewerId")
            result = null
            viewer.dispatch("phone.viewer.frame", JSONObject().put("viewerId", handle).put("afterFrameGeneration", -1)) { value, _, code -> result = value; errorCode = code }
            assertTrue(pendingCurrent!!.invoke())
        }
        fun reply() = pendingReply!!.invoke(JSONObject().put("taskId", "b").put("runId", "run-b").put("epoch", 1)
            .put("sessionId", "screen-b").put("displayId", 12).put("state", "ready").put("frameGeneration", 1L).put("capturedAt", 100L)
            .put("sourceWidth", 320).put("sourceHeight", 480).put("previewWidth", 320).put("previewHeight", 480)
            .put("mime", "image/jpeg").put("imageBase64", "controlled-frame"), null, "")
        override fun close() {
            viewer.clear(); setField("closed", true); announcements.close()
            (field("voiceInput").get(controller) as VoiceDraftInput).close()
            // This controller never attaches or submits work; close its allocated executors.
            for (name in listOf("io", "controls", "realtimeIo", "phonePreviewIo")) (field(name).get(controller) as ExecutorService).shutdownNow()
        }
    }

    @Test fun unrelatedFallbackPhoneChangeKeepsSelectedPreviewWhileApprovalTicketExpires() {
        Fixture().use { f ->
            f.openB()
            val viewerTicket = f.controller.webPhoneViewerWindowTicket()
            val approvalTicket = f.controller.webWindowTicket()
            f.update(f.controller.state.copy(activeTaskId = "b", tasks = listOf(task("b"))))
            assertNotEquals("Existing approval windows must still follow their original active run", approvalTicket, f.controller.webWindowTicket())
            assertEquals(viewerTicket, f.controller.webPhoneViewerWindowTicket())
            assertTrue(f.pendingCurrent!!.invoke())
            f.reply()
            assertEquals("b", f.result!!.getString("taskId")); assertEquals("screen-b", f.result!!.getString("sessionId"))
            assertEquals("", f.errorCode)
        }
    }

    @Test fun conversationGroupAndProjectNavigationEachRejectTheLateSelectedFrame() {
        for (change in listOf("conversation", "task", "project", "group", "teams")) Fixture().use { f ->
            f.openB(); val ticket = f.controller.webPhoneViewerWindowTicket()
            when (change) {
                "conversation" -> f.update(f.controller.state.copy(conversationId = "next-conversation"))
                "task" -> f.update(f.controller.state.copy(requestedTaskRevision = f.controller.state.requestedTaskRevision + 1))
                "project" -> f.controller.projectWorkbench.selectView("fixture-project", 1)
                "group" -> f.window("setGroupTaskPage", "fixture-group-window")
                "teams" -> f.window("setOwnerDomain", "teams")
            }
            assertNotEquals(change, ticket, f.controller.webPhoneViewerWindowTicket())
            assertFalse(change, f.pendingCurrent!!.invoke())
            f.reply()
            assertNull(change, f.result); assertEquals(change, "phone_viewer_stale", f.errorCode)
        }
    }

    companion object {
        private fun task(id: String) = UiTask(id, "Phone $id", "running", "", "background", 100L,
            JSONObject().put("id", id).put("kind", "phone").put("actorId", "owner").put("spaceId", "private")
                .put("runId", "run-$id").put("coordinatorEpoch", 1))
    }
}
