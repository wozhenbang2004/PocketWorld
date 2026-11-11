package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.devices.NativeDeviceTaskScopeHost as Host
import dev.pocketengine.store.TaskStoreDeviceTasks
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Presentation of the original private task's native route selection, never a new grant or runner. */
internal class PocketWebDeviceTaskScope(
    private val controller: PocketWorldController,
    private val taskVisible: (String) -> Boolean,
    private val preparationVisible: () -> Boolean
) {
    private class Page(val id: String, val taskId: String, val owner: Any, val native: Host.Window) {
        @Volatile var alive = true
    }
    private class Review(
        val id: String, val page: Page, val expires: Long,
        val scope: TaskStoreDeviceTasks.SourceScopeReview? = null,
        val project: TaskStoreDeviceTasks.ProjectImportReview? = null
    )
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val lifetime = Any()
    @Volatile private var generation = 0L
    @Volatile private var page: Page? = null
    @Volatile private var review: Review? = null

    companion object {
        val COMMANDS = setOf("deviceTaskScope.prepare", "deviceTaskScope.open", "deviceTaskScope.close", "deviceTaskScope.review",
            "deviceTaskScope.confirm", "deviceTaskScope.dismissReview", "deviceTaskScope.reviewImport", "deviceTaskScope.confirmImport")
        private fun fields(value: JSONObject, vararg allowed: String) = PocketWebGroupReviews.fields(value, *allowed)
        private fun text(value: JSONObject, key: String, max: Int = 240) = PocketWebGroupReviews.text(value, key, max)
        private fun integer(value: JSONObject, key: String, min: Long, max: Long) = PocketWebGroupReviews.integer(value, key, min, max)
        private fun rows(value: JSONObject, key: String, max: Int): List<JSONObject> {
            val array = value.getJSONArray(key); require(array.length() <= max) { "设备范围选项过多" }
            return (0 until array.length()).map(array::getJSONObject)
        }
    }
    private fun visible(task: String) = controller.webPrivateOwnerVisible() && taskVisible(task)
    private fun current(value: Page) = value.alive && page === value && visible(value.taskId) && value.owner == controller.webWindowTicket()
    fun clear() = synchronized(lifetime) {
        generation++; review = null
        page?.let { it.alive = false; it.native.close() }; page = null
    }
    fun foreground(value: Boolean) { if (!value) clear() }
    private fun selected(input: JSONObject): Page = checkNotNull(page) { "设备范围页已关闭，请重新读取" }.also {
        check(it.id == text(input, "pageHandle") && current(it)) { "原私人任务或窗口已变化" }
    }
    private fun work(original: Page?, task: String, done: (JSONObject?, String?) -> Unit, block: () -> JSONObject) {
        check(busy.compareAndSet(false, true)) { "请等待本次设备范围回执" }
        val epoch = generation; val owner = controller.webWindowTicket()
        fun accepted() = epoch == generation && owner == controller.webWindowTicket() && visible(task) && (original == null || current(original))
        try {
            controller.runDeviceWorkspaceWork(Runnable {
                val result = runCatching { check(accepted()) { "原设备范围窗口已变化" }; block() }
                main.post {
                    busy.set(false)
                    if (!accepted()) done(null, "原窗口已变化；请重新读取原任务的已保存范围，没有自动重做")
                    else result.fold({ done(PocketWebState.publicValue(it) as JSONObject, null) }, { done(null, it.message ?: "设备范围结果尚未确认") })
                }
            })
        } catch (failure: Exception) { busy.set(false); done(null, failure.message ?: "设备读取队列不可用") }
    }
    private fun selection(input: JSONObject): Host.Selection = Host.Selection(rows(input, "routes", 16).map { row ->
        fields(row, "optionId", "toolBudget", "modelBudget", "deadline", "localToolShare", "localModelShare", "shares", "files", "projects")
        val shares = rows(row, "shares", 128).map {
            fields(it, "optionId", "toolBudget", "modelBudget")
            Host.ShareChoice(text(it, "optionId"), integer(it, "toolBudget", 0, 400).toInt(), integer(it, "modelBudget", 0, 1000).toInt())
        }
        val files = rows(row, "files", 4).map {
            fields(it, "attachmentId", "sha256", "path", "expectedVersion")
            Host.FileChoice(text(it, "attachmentId"), text(it, "sha256", 64), text(it, "path", 1024), text(it, "expectedVersion", 80))
        }
        val projects = rows(row, "projects", 1).map {
            fields(it, "artifactId", "sha256", "path")
            Host.ProjectChoice(text(it, "artifactId"), text(it, "sha256", 64), text(it, "path", 1024))
        }
        Host.RouteChoice(text(row, "optionId"), integer(row, "toolBudget", 0, 400).toInt(), integer(row, "modelBudget", 0, 1000).toInt(),
            integer(row, "deadline", 1, 9_007_199_254_740_991L), integer(row, "localToolShare", 0, 400).toInt(),
            integer(row, "localModelShare", 0, 1000).toInt(), shares, Host.ContextChoice.GOAL_ONLY, files, projects)
    })
    private fun issue(original: Page, scope: TaskStoreDeviceTasks.SourceScopeReview? = null, project: TaskStoreDeviceTasks.ProjectImportReview? = null): JSONObject = synchronized(lifetime) {
        check(current(original)) { "原设备范围窗口已变化" }
        val captured = Review(UUID.randomUUID().toString(), original, SystemClock.elapsedRealtime() + 120_000, scope, project)
        review = captured
        (scope?.view() ?: checkNotNull(project).view()).put("reviewHandle", captured.id)
            .put("reviewExpiresAt", System.currentTimeMillis() + 120_000)
            .put("taskId", original.taskId).put("pageHandle", original.id).put("kind", if (scope != null) "scope" else "import")
    }
    fun dispatch(action: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action == "deviceTaskScope.close") {
            fields(input, "pageHandle")
            synchronized(lifetime) { if (page?.id == text(input, "pageHandle")) clear() }
            done(JSONObject().put("closed", true).put("taskStopped", false), null); return
        }
        if (action == "deviceTaskScope.dismissReview") {
            fields(input, "reviewHandle")
            synchronized(lifetime) { if (review?.id == text(input, "reviewHandle")) review = null }
            done(JSONObject().put("closed", true), null); return
        }
        when (action) {
            "deviceTaskScope.prepare" -> {
                fields(input, "requestId", "goal", "deadlineAt", "toolLimit", "modelCallLimit")
                val request = text(input, "requestId", 36); val goal = text(input, "goal", 8000)
                val deadline = integer(input, "deadlineAt", 1, 9_007_199_254_740_991L)
                val tools = integer(input, "toolLimit", 1, 400).toInt(); val models = integer(input, "modelCallLimit", 1, 512).toInt()
                val epoch = generation; val owner = controller.webWindowTicket()
                fun current() = epoch == generation && owner == controller.webWindowTicket() && controller.webPrivateOwnerVisible() && preparationVisible()
                check(current()) { "请回到设备工作台准备原任务" }
                check(busy.compareAndSet(false, true)) { "请等待原准备请求回执" }
                try {
                    controller.runDeviceWorkspaceWork(Runnable {
                        val result = runCatching { controller.prepareOwnerDeviceTask(request, goal, deadline, tools, models, ::current) }
                        main.post {
                            busy.set(false)
                            if (!current()) done(null, "原准备窗口已关闭；请在任务记录核对原编号，没有自动重新创建")
                            else result.fold({ done(JSONObject().put("taskId", it.getString("id")).put("prepared", true).put("taskStarted", false), null) },
                                { done(null, it.message ?: "准备回执尚未确认，请保留原编号核对") })
                        }
                    })
                } catch (failure: Exception) { busy.set(false); done(null, failure.message ?: "准备队列不可用") }
            }
            "deviceTaskScope.open" -> {
                fields(input, "taskId"); val task = text(input, "taskId")
                check(visible(task)) { "请先打开这份原私人任务" }
                check(!busy.get()) { "请等待原设备范围操作" }; clear()
                val epoch = generation; val owner = controller.webWindowTicket()
                work(null, task, done) {
                    val native = (controller.nativeDeviceTaskScope() ?: error("设备任务宿主尚未准备")).open(task)
                    val created = Page(UUID.randomUUID().toString(), task, owner, native)
                    try {
                        synchronized(lifetime) {
                            check(epoch == generation && visible(task) && owner == controller.webWindowTicket()) { "原任务窗口已变化" }
                            page = created
                        }
                        native.view().put("pageHandle", created.id)
                    } catch (failure: Throwable) {
                        synchronized(lifetime) { if (page === created) page = null; created.alive = false; native.close() }
                        throw failure
                    }
                }
            }
            "deviceTaskScope.review" -> {
                fields(input, "pageHandle", "routes"); val original = selected(input); val choices = selection(input)
                synchronized(lifetime) { review = null }
                work(original, original.taskId, done) { issue(original, scope = original.native.review(choices)) }
            }
            "deviceTaskScope.reviewImport" -> {
                fields(input, "pageHandle", "operationId"); val original = selected(input); val operation = text(input, "operationId")
                synchronized(lifetime) { review = null }
                work(original, original.taskId, done) { issue(original, project = original.native.reviewProjectImport(operation)) }
            }
            "deviceTaskScope.confirm", "deviceTaskScope.confirmImport" -> {
                fields(input, "pageHandle", "reviewHandle", "acknowledged")
                require(input.opt("acknowledged") == true) { "请确认已核对这次准确设备范围" }
                val original = selected(input)
                check(!busy.get()) { "请等待原设备范围操作" }
                val captured = synchronized(lifetime) {
                    checkNotNull(review) { "原审阅已使用或关闭，请重新读取已保存范围" }.also {
                        check(it.page === original && it.id == text(input, "reviewHandle") && it.expires > SystemClock.elapsedRealtime()) { "原设备范围审阅已变化或过期" }
                        check(if (action == "deviceTaskScope.confirm") it.scope != null else it.project != null)
                        review = null
                    }
                }
                work(original, original.taskId, done) {
                    val saved = if (captured.scope != null) original.native.confirm(captured.scope) else original.native.confirmProjectImport(checkNotNull(captured.project))
                    main.post { controller.deviceTaskWorkspaceChanged(original.taskId) }
                    JSONObject().put("taskId", original.taskId).put("reviewHandle", captured.id).put("saved", true)
                        .put("kind", if (captured.scope != null) "scope" else "import").put("scope", saved)
                        .put("taskStarted", false).put("executionAuthorized", false).put("budgetReserved", false)
                }
            }
            else -> error("设备任务范围操作未接入")
        }
    }
}
