package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.ui.UiTask
import org.json.JSONObject
import java.util.UUID

/** One visible, read-only phone preview. A viewer handle never authorizes phone input. */
internal class PocketWebPhoneViewer(
    private val visible: () -> Boolean,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val findTask: (String) -> UiTask?,
    private val read: (JSONObject, () -> Boolean, (JSONObject?, String?, String) -> Unit) -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private class Viewer(val id: String, val task: String, val run: String, val epoch: Int, val window: PocketWebWindowTicket) {
        var session: String? = null
        var display: Int? = null
        var generation = -1L
        var capturedAt = -1L
    }
    @Volatile private var viewer: Viewer? = null
    @Volatile private var foreground = true
    private var reading = false
    private var nextRead = 0L

    companion object {
        val COMMANDS = setOf("phone.viewer.open", "phone.viewer.frame", "phone.viewer.close")
        private val ended = setOf("completed", "failed", "cancelled", "canceled", "stopped", "interrupted")
    }

    fun clear() { viewer = null }
    fun foreground(value: Boolean) { foreground = value; if (!value) clear() }

    private fun fields(args: JSONObject, vararg names: String) {
        require(args.keys().asSequence().all { it in names }) { "画面请求包含不适用的参数" }
    }
    private fun text(args: JSONObject, name: String): String = (args.opt(name) as? String)
        ?.takeIf { it.isNotBlank() && it.length <= 200 && '\u0000' !in it } ?: error("请核对原画面的$name")
    private fun number(args: JSONObject, name: String, minimum: Long = 0): Long {
        val value = args.opt(name)
        require(value is Int || value is Long) { "请核对原画面的$name" }
        return (value as Number).toLong().also { require(it >= minimum) { "请核对原画面的$name" } }
    }
    private fun identity(value: Viewer): Boolean {
        val task = findTask(value.task) ?: return false
        val raw = task.raw
        val origin = raw.optJSONObject("origin")
        return task.executionMode == "background" && raw.optString("kind") == "phone" &&
            raw.optString("actorId") == "owner" && raw.optString("spaceId") == "private" &&
            (!raw.has("origin") || raw.isNull("origin") || origin?.optString("kind") == "team_phone") &&
            raw.optString("id") == value.task && raw.optString("runId") == value.run &&
            raw.optInt("coordinatorEpoch", -1) == value.epoch
    }
    private fun current(value: Viewer) = viewer === value && foreground && visible() && acceptsWindow(value.window) && identity(value)

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?, String) -> Unit) {
        if (action == "phone.viewer.close") {
            fields(args, "viewerId")
            if (viewer?.id == text(args, "viewerId")) clear()
            done(JSONObject().put("closed", true), null, "")
            return
        }
        if (!foreground || !visible()) { clear(); done(null, "回到应用后可继续查看画面", "phone_viewer_hidden"); return }
        if (action == "phone.viewer.open") {
            fields(args, "taskId", "runId", "epoch")
            val epoch = number(args, "epoch", 1).also { require(it <= Int.MAX_VALUE) }.toInt()
            val next = Viewer(UUID.randomUUID().toString(), text(args, "taskId"), text(args, "runId"), epoch, captureWindow())
            if (!identity(next)) { done(null, "原手机任务已变化，请重新打开", "phone_viewer_stale"); return }
            if (findTask(next.task)?.status in ended) { done(null, "这次手机任务已结束", "phone_viewer_ended"); return }
            viewer = next
            done(JSONObject().put("viewerId", next.id).put("taskId", next.task).put("runId", next.run).put("epoch", next.epoch), null, "")
            return
        }
        require(action == "phone.viewer.frame")
        fields(args, "viewerId", "afterFrameGeneration")
        val original = viewer
        if (original == null || original.id != text(args, "viewerId") || !current(original)) {
            done(null, "原画面窗口已变化，请重新打开", "phone_viewer_stale"); return
        }
        if (findTask(original.task)?.status in ended) { clear(); done(null, "这次手机任务已结束", "phone_viewer_ended"); return }
        if (reading || now() < nextRead) { done(null, "正在读取画面，请稍后重试", "preview_unavailable"); return }
        val after = if (args.has("afterFrameGeneration")) number(args, "afterFrameGeneration", -1) else -1L
        require(after <= original.generation) { "不能跳过尚未收到的画面" }
        val input = JSONObject().put("taskId", original.task).put("expectedRunId", original.run)
            .put("expectedEpoch", original.epoch).put("afterFrameGeneration", after)
        original.session?.let { input.put("expectedSessionId", it).put("expectedDisplayId", original.display) }
        reading = true
        nextRead = now() + 180L
        try {
            read(input, { current(original) }) { result, error, code ->
                reading = false
                if (!current(original)) { done(null, "原画面窗口已变化，未显示迟到的画面", "phone_viewer_stale") }
                else if (error != null || result == null) {
                    if (code in setOf("phone_viewer_stale", "phone_viewer_ended", "phone_viewer_hidden")) clear()
                    done(null, error ?: "画面暂不可读取", code.ifEmpty { "preview_unavailable" })
                } else try {
                    require(result.optString("taskId") == original.task && result.optString("runId") == original.run &&
                        number(result, "epoch", 1) == original.epoch.toLong())
                    val session = text(result, "sessionId")
                    val display = number(result, "displayId", 1).also { require(it <= Int.MAX_VALUE) }.toInt()
                    require(original.session == null || original.session == session && original.display == display)
                    val generation = number(result, "frameGeneration")
                    val capturedAt = number(result, "capturedAt")
                    require(generation >= original.generation && (capturedAt == 0L || capturedAt >= original.capturedAt))
                    require(result.optString("state") in setOf("ready", "pending", "unchanged"))
                    if (result.optString("state") == "ready") {
                        require(result.optString("mime") == "image/jpeg")
                        val image = result.opt("imageBase64") as? String
                        require(image != null && image.isNotEmpty() && image.length <= 2 * 1024 * 1024)
                        require(number(result, "sourceWidth", 1) > 0 && number(result, "sourceHeight", 1) > 0)
                        require(number(result, "previewWidth", 1) <= 960 && number(result, "previewHeight", 1) <= 960)
                    } else require(!result.has("imageBase64"))
                    original.session = session; original.display = display
                    original.generation = generation; original.capturedAt = maxOf(original.capturedAt, capturedAt)
                    done(result, null, "")
                } catch (_: Exception) {
                    clear(); done(null, "原手机画面身份无法核对，请重新打开", "phone_viewer_stale")
                }
            }
        } catch (failure: Exception) { reading = false; done(null, failure.message ?: "画面暂不可读取", "preview_unavailable") }
    }
}
