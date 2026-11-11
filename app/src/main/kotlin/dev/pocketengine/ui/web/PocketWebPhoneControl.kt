package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.devices.OwnerPhoneControlSession
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Original native reviews/sessions only. UI handles never reconstruct a grant or a frame. */
internal class PocketWebPhoneControl(
    private val controller: PocketWorldController,
    private val taskVisible: (String) -> Boolean,
    private val preparationVisible: () -> Boolean
) {
    private class Page(val id: String, val task: String, val owner: Any) {
        @Volatile var alive = true
        @Volatile var stopRequested = false
        @Volatile var review: TaskStore.OwnerPhoneControlReview? = null
        @Volatile var session: OwnerPhoneControlSession? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val lifetime = Any()
    @Volatile private var generation = 0L
    @Volatile private var page: Page? = null
    companion object {
        val COMMANDS = setOf("phoneControl.prepare", "phoneControl.open", "phoneControl.start", "phoneControl.read", "phoneControl.input", "phoneControl.reconcile", "phoneControl.stop", "phoneControl.close")
        private fun fields(value: JSONObject, vararg names: String) = PocketWebGroupReviews.fields(value, *names)
        private fun text(value: JSONObject, key: String, max: Int = 240) = PocketWebGroupReviews.text(value, key, max)
        private fun integer(value: JSONObject, key: String, min: Long, max: Long) = PocketWebGroupReviews.integer(value, key, min, max)
    }
    private fun visible(task: String) = controller.webPrivateOwnerVisible() && taskVisible(task)
    private fun current(value: Page) = value.alive && !value.stopRequested && page === value && visible(value.task) && value.owner == controller.webWindowTicket()
    fun clear() {
        val retired = synchronized(lifetime) { generation++; page.also { page = null; it?.alive = false; it?.review = null } }
        retired?.session?.close()
    }
    fun foreground(value: Boolean) { if (!value) clear() }
    private fun selected(input: JSONObject): Page = checkNotNull(page) { "原手控页已关闭，不恢复旧输入权限" }.also {
        check(it.id == text(input, "pageHandle") && current(it)) { "原私人手控窗口已变化" }
    }
    private fun work(original: Page, done: (JSONObject?, String?) -> Unit, block: () -> JSONObject) {
        check(busy.compareAndSet(false, true)) { "请等待原手控回执" }
        try { controller.runDeviceWorkspaceWork(Runnable {
            val result = runCatching { check(current(original)) { "原手控页已关闭" }; block() }
            main.post {
                busy.set(false)
                if (!current(original)) done(null, "原页面已离开；已停止后续输入，请从原任务核对已发操作。")
                else result.fold({ done(it.put("pageHandle", original.id), null) }, { done(null, it.message ?: "原手控回执尚未确认") })
            }
        }) } catch (failure: Exception) { busy.set(false); done(null, failure.message ?: "原设备队列不可用") }
    }
    fun dispatch(action: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action == "phoneControl.close") {
            fields(input, "pageHandle")
            if (page?.id == text(input, "pageHandle")) clear()
            done(JSONObject().put("closed", true).put("stopRequested", true).put("remoteStopVerified", false), null); return
        }
        if (action == "phoneControl.prepare") {
            fields(input, "requestId", "deadlineAt", "toolLimit")
            val id = text(input, "requestId", 36); val deadline = integer(input, "deadlineAt", 1, 9_007_199_254_740_991L); val tools = integer(input, "toolLimit", 4, 200).toInt()
            val epoch = generation; val owner = controller.webWindowTicket()
            fun current() = epoch == generation && owner == controller.webWindowTicket() && controller.webPrivateOwnerVisible() && preparationVisible()
            check(current()) { "请在设备页准备手控" }; check(busy.compareAndSet(false, true)) { "请等待原准备回执" }
            try { controller.runDeviceWorkspaceWork(Runnable {
                val result = runCatching { controller.prepareOwnerPhoneControl(id, deadline, tools, ::current) }
                main.post { busy.set(false)
                    if (!current()) done(null, "准备结果请在原编号核对；没有自动再次创建或启动。")
                    else result.fold({ done(JSONObject().put("taskId", it.getString("id")).put("prepared", true).put("taskStarted", false), null) }, { done(null, it.message ?: "准备回执尚未确认") })
                }
            }) } catch (failure: Exception) { busy.set(false); done(null, failure.message ?: "准备队列不可用") }; return
        }
        if (action == "phoneControl.open") {
            fields(input, "taskId"); val task = text(input, "taskId")
            check(visible(task) && !busy.get()) { "请先打开原私人任务并等待原回执" }
            // Re-reading an open original session never creates another task, run or range.
            val existing = page
            if (existing != null && existing.task == task && current(existing)) {
                work(existing, done) { existing.session?.view() ?: checkNotNull(existing.review).view().put("reviewReady", true) }; return
            }
            clear(); val created = Page(UUID.randomUUID().toString(), task, controller.webWindowTicket())
            synchronized(lifetime) { page = created }
            work(created, done) {
                val review = controller.reviewOwnerPhoneControl(task) { current(created) }
                synchronized(lifetime) { check(current(created)); created.review = review }
                review.view().put("reviewReady", true)
            }; return
        }
        val original = selected(input)
        if (action == "phoneControl.stop") {
            fields(input, "pageHandle")
            // Stop is not queued behind the network operation. Original RunControl fences dispatch.
            synchronized(lifetime) { original.stopRequested = true; original.review = null }
            original.session?.stop("主人已停止手控")
            done((original.session?.status() ?: JSONObject().put("active", false)).put("pageHandle", original.id).put("stopRequested", true).put("remoteStopVerified", false), null); return
        }
        when (action) {
            "phoneControl.start" -> {
                fields(input, "pageHandle", "acknowledged"); check(input.opt("acknowledged") == true) { "请先核对本次屏幕、全屏捕获与输入范围、时长和额度" }
                check(!busy.get()) { "请等待原回执" }
                val review = synchronized(lifetime) { checkNotNull(original.review) { "原审阅已使用，请读取原结果，不能重复开始" }.also { original.review = null } }
                work(original, done) {
                    val session = controller.startOwnerPhoneControl(review)
                    try { synchronized(lifetime) { check(current(original)); original.session = session }; session.view() }
                    catch (failure: Throwable) { session.close(); throw failure }
                }
            }
            "phoneControl.read" -> { fields(input, "pageHandle"); work(original, done) { original.session?.status() ?: checkNotNull(original.review) { "原开始回执仍待核对，不重复开始" }.view().put("reviewReady", true) } }
            "phoneControl.input" -> {
                fields(input, "pageHandle", "sessionId", "serial", "frameHandle", "tool", "intent")
                val session = checkNotNull(original.session); val identity = text(input, "sessionId", 36); val serial = integer(input, "serial", 0, 9_007_199_254_740_991L)
                val frame = input.getString("frameHandle").also { require(it.length <= 36) }; val tool = text(input, "tool", 40); val intent = JSONObject(input.getJSONObject("intent").toString())
                work(original, done) { session.input(identity, serial, frame, tool, intent) }
            }
            "phoneControl.reconcile" -> { fields(input, "pageHandle", "sessionId"); val session = checkNotNull(original.session); val identity = text(input, "sessionId", 36); work(original, done) { session.reconcile(identity) } }
            else -> error("原手控操作未接入")
        }
    }
}
