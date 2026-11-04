package dev.pocketengine.ui.web

import dev.pocketengine.ui.UiTask
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real viewer state machine with a controlled frame reader; no device pixels or SQL admission claimed. */
class PocketWebPhoneViewerTest {
    private fun task(id: String = "a", run: String = "run-$id", status: String = "running", origin: String? = null) =
        UiTask(id, "原手机$id", status, "", "background", 100L, JSONObject().put("id", id).put("kind", "phone")
            .put("actorId", "owner").put("spaceId", "private").put("runId", run).put("coordinatorEpoch", 1)
            .also { if (origin != null) it.put("origin", JSONObject().put("kind", origin)) })

    private data class Reply(val value: JSONObject?, val error: String?, val code: String)
    private data class Pending(val input: JSONObject, val current: () -> Boolean, val done: (JSONObject?, String?, String) -> Unit)
    private inner class Fixture {
        var time = 1000L
        var visible = true
        var window = PocketWebWindowTicket(1, Triple(1L, 1, 1L) to 1L)
        val tasks = mutableListOf(task("a"), task("b"))
        val pending = mutableListOf<Pending>()
        val replies = mutableListOf<Reply>()
        val viewer = PocketWebPhoneViewer({ visible }, { window }, { it == window }, { id -> tasks.find { it.id == id } },
            { input, current, done -> pending += Pending(input, current, done) }, { time })
        fun send(action: String, input: JSONObject) { viewer.dispatch("phone.viewer.$action", input) { value, error, code -> replies += Reply(value, error, code) } }
        fun open(id: String = "a"): String {
            send("open", JSONObject().put("taskId", id).put("runId", "run-$id").put("epoch", 1))
            return replies.last().value!!.getString("viewerId")
        }
        fun frame(handle: String, after: Long = -1) { time += 200; send("frame", JSONObject().put("viewerId", handle).put("afterFrameGeneration", after)) }
        fun result(id: String = "a", session: String = "screen-$id", display: Int = 11, generation: Long = 1, state: String = "ready") =
            JSONObject().put("taskId", id).put("runId", "run-$id").put("epoch", 1).put("sessionId", session).put("displayId", display)
                .put("frameGeneration", generation).put("capturedAt", if (state == "pending") 0 else generation * 100)
                .put("state", state).put("sourceWidth", if (state == "pending") 0 else 1080).put("sourceHeight", if (state == "pending") 0 else 1920)
                .put("previewWidth", if (state == "pending") 0 else 540).put("previewHeight", if (state == "pending") 0 else 960)
                .put("mime", "image/jpeg").also { if (state == "ready") it.put("imageBase64", "/9j/test-frame") }
        fun reply(value: JSONObject, index: Int = pending.lastIndex) { pending[index].done(value, null, "") }
    }

    @Test fun pausedPhoneStillReadsAndFirstPendingResponsePinsOnlyItsDisplay() {
        val f = Fixture(); f.tasks[0] = task(status = "paused")
        val handle = f.open(); f.frame(handle)
        assertFalse(f.pending[0].input.has("expectedSessionId"))
        f.reply(f.result(generation = 0, state = "pending"))
        assertNull(f.replies.last().error)
        f.frame(handle, 0)
        val input = f.pending.last().input
        assertEquals("screen-a", input.getString("expectedSessionId"))
        assertEquals(11, input.getInt("expectedDisplayId"))
        assertEquals("a", input.getString("taskId"))
        assertEquals("run-a", input.getString("expectedRunId"))
        assertEquals(1, input.getInt("expectedEpoch"))
        f.reply(f.result(generation = 2))
        assertEquals("ready", f.replies.last().value!!.getString("state"))
        assertEquals("paused", f.tasks[0].status)
    }

    @Test fun changingSelectedPhoneRejectsTheOldInflightImageAndOldCloseCannotCloseNewViewer() {
        val f = Fixture(); val first = f.open(); f.frame(first)
        val second = f.open("b")
        assertFalse(f.pending[0].current())
        f.reply(f.result(), 0)
        assertEquals("phone_viewer_stale", f.replies.last().code)
        f.send("close", JSONObject().put("viewerId", first))
        f.frame(second); f.reply(f.result("b", display = 12))
        assertEquals("b", f.replies.last().value!!.getString("taskId"))
        assertEquals(2, f.pending.size)
    }

    @Test fun runReplacementBackgroundAndWindowReplacementEachDiscardLatePixels() {
        for (change in 0..2) {
            val f = Fixture(); val handle = f.open(); f.frame(handle)
            when (change) {
                0 -> f.tasks[0] = task(run = "new-run")
                1 -> f.viewer.foreground(false)
                else -> f.window = f.window.copy(bridge = 2)
            }
            assertFalse(f.pending[0].current())
            f.reply(f.result())
            assertNull(f.replies.last().value)
            assertEquals("phone_viewer_stale", f.replies.last().code)
        }
    }

    @Test fun substitutedSessionDisplayOrRegressedFrameIsNeverEmitted() {
        for (change in 0..2) {
            val f = Fixture(); val handle = f.open(); f.frame(handle); f.reply(f.result(generation = 2))
            f.frame(handle, 2)
            f.reply(when (change) {
                0 -> f.result(session = "other-screen", generation = 3)
                1 -> f.result(display = 99, generation = 3)
                else -> f.result(generation = 1)
            })
            assertNull(f.replies.last().value)
            assertEquals("phone_viewer_stale", f.replies.last().code)
            f.frame(handle, 2)
            assertEquals(2, f.pending.size)
        }
    }

    @Test fun mainDisplayAndPairedOrMalformedOriginAreRejected() {
        val f = Fixture(); val handle = f.open(); f.frame(handle); f.reply(f.result(display = 0))
        assertEquals("phone_viewer_stale", f.replies.last().code)
        for (origin in listOf("paired_device", "group", "channel")) {
            f.tasks[0] = task(origin = origin)
            f.send("open", JSONObject().put("taskId", "a").put("runId", "run-a").put("epoch", 1))
            assertEquals("phone_viewer_stale", f.replies.last().code)
        }
        f.tasks[0] = task().apply { raw.put("origin", "invalid") }
        f.send("open", JSONObject().put("taskId", "a").put("runId", "run-a").put("epoch", 1))
        assertEquals("phone_viewer_stale", f.replies.last().code)
        f.tasks[0] = task(origin = "team_phone").apply { raw.put("parentTaskId", "team-parent") }
        assertTrue(f.open().isNotEmpty())
    }

    @Test fun onlyOneFrameCanBeInflightAndTransientFailureKeepsTheSameScopedViewer() {
        val f = Fixture(); val handle = f.open(); f.frame(handle); f.frame(handle)
        assertEquals(1, f.pending.size)
        assertEquals("preview_unavailable", f.replies.last().code)
        f.pending[0].done(null, "临时读取失败", "preview_unavailable")
        f.frame(handle); f.reply(f.result())
        assertEquals("a", f.replies.last().value!!.getString("taskId"))
        assertEquals("running", f.tasks[0].status)
    }

    @Test fun geometryChangeCanReportPendingWithoutResettingTheViewer() {
        val f = Fixture(); val handle = f.open(); f.frame(handle); f.reply(f.result(generation = 2))
        f.frame(handle, 2); f.reply(f.result(generation = 3, state = "pending"))
        assertEquals("pending", f.replies.last().value!!.getString("state"))
        f.frame(handle, 3); f.reply(f.result(generation = 4))
        assertEquals(4L, f.replies.last().value!!.getLong("frameGeneration"))
    }

    @Test fun closeAndTaskEndStopReadsWithoutChangingTheTask() {
        val f = Fixture(); val handle = f.open()
        f.send("close", JSONObject().put("viewerId", handle)); f.frame(handle)
        assertEquals("phone_viewer_stale", f.replies.last().code)
        assertEquals("running", f.tasks[0].status)
        val second = f.open(); f.tasks[0] = task(status = "completed"); f.frame(second)
        assertEquals("phone_viewer_ended", f.replies.last().code)
        assertTrue(f.pending.isEmpty())
    }
}
