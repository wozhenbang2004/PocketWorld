package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.store.TaskStore
import java.util.UUID
import org.json.JSONObject

/** Explicit private owner review/save in the same original Work window. No JS path or body. */
internal class PocketWebNodeCompilerOutput(
    private val tasks: () -> TaskStore,
    private val local: () -> LocalModule,
    private val work: (Runnable) -> Unit,
    private val capture: () -> PocketWebWindowTicket,
    private val accepts: (PocketWebWindowTicket) -> Boolean,
    private val selected: () -> String?
) {
    companion object {
        private const val REVIEW = "projects.nodeCompilerOutput.review"
        private const val SAVE = "projects.nodeCompilerOutput.save"
        val COMMANDS = setOf(REVIEW, SAVE)
    }
    private data class Original(val task: String, val run: String, val epoch: Int, val build: String)
    private data class Offer(val original: Original, val pin: JSONObject, val window: PocketWebWindowTicket,
        val id: String, val until: Long)
    private val main = Handler(Looper.getMainLooper())
    private var offer: Offer? = null
    @Volatile private var active: RunControl? = null

    fun clear() { offer = null; active?.cancel() }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        val saving = action == SAVE
        val keys = setOf("taskId", "runId", "epoch", "buildOperationId") + if (saving) setOf("reviewHandle") else emptySet()
        require(args.keys().asSequence().toSet() == keys && keys.filter { it != "epoch" }.all { args.opt(it) is String })
        val epoch = args.get("epoch")
        require(epoch is Int || epoch is Long)
        val original = Original(args.getString("taskId"), args.getString("runId"), args.getInt("epoch"), args.getString("buildOperationId"))
        require(original.task.matches(Regex("[a-f0-9-]{36}")) && original.run.matches(Regex("[a-f0-9-]{36}")) &&
            original.build.matches(Regex("[A-Za-z0-9_-]{1,80}")) && original.epoch > 0 && original.epoch.toLong() == (epoch as Number).toLong())
        val window = capture()
        fun visible() = accepts(window) && selected() == original.task
        check(visible() && active == null) { "请回正在查看的这次原构建" }
        val reviewed = if (saving) offer?.takeIf {
            it.original == original && it.window == window && it.id == args.getString("reviewHandle") &&
                SystemClock.elapsedRealtime() < it.until
        } ?: error("请重新核对这次原输出后再确认") else null
        offer = null
        val control = RunControl(); active = control
        try {
            work(Runnable {
                fun current() = visible() && active === control && !control.isCancelled
                fun requireCurrent() { control.check(); check(current()) { "已离开原构建，没有读取其它结果" } }
                var next: Offer? = null
                val result = runCatching {
                    requireCurrent()
                    val store = tasks()
                    val build = store.nodeCompilerBuildForOwner(original.task, original.run, original.epoch, original.build)
                    val delivery = build.delivery()
                    val state = store.nodeCompilerSaveStateForOwner(delivery)
                    val local = local()
                    requireCurrent()
                    if (reviewed != null) {
                        check(state == "ready" && delivery.matches(reviewed.pin)) { "原保存或原构建已变化，请核对这笔原保存" }
                        val published = local.saveNodeCompilerOutputForOwner(build, control, ::current) {
                            requireCurrent()
                            check(SystemClock.elapsedRealtime() < reviewed.until) { "原输出审阅已过期，没有保存" }
                            store.beginNodeCompilerSaveForOwner(build, control)
                        }
                        store.finishNodeCompilerSaveForOwner(delivery, published)
                    } else if (state != "ready") {
                        // Read only one original Book publication. Nothing here can select/re-read dist or write a file.
                        val saved = runCatching { local.findNodeCompilerOutputForOwner(delivery, control) }.getOrNull()
                        requireCurrent()
                        if (saved != null) store.finishNodeCompilerSaveForOwner(delivery, saved)
                        else if (state == "pending") delivery.view("pending")
                        else error("原保存曾登记，但当前不能核对固定文件。不会重新保存。")
                    } else {
                        local.reviewNodeCompilerOutputForOwner(build, control, ::current)
                        requireCurrent()
                        val unchanged = store.nodeCompilerBuildForOwner(original.task, original.run, original.epoch, original.build)
                        check(unchanged.delivery().matches(delivery.pin()) && store.nodeCompilerSaveStateForOwner(delivery) == "ready") {
                            "原保存或原构建已变化，请重新核对"
                        }
                        requireCurrent()
                        val proposal = Offer(original, delivery.pin(), window, UUID.randomUUID().toString(),
                            SystemClock.elapsedRealtime() + 120_000L)
                        next = proposal
                        delivery.view("ready").put("reviewHandle", proposal.id).put("expiresAt", System.currentTimeMillis() + 120_000L)
                    }
                }
                main.post {
                    if (active === control) active = null
                    if (!visible() || control.isCancelled) done(null, "已离开原构建。回来只能核对同一原保存，不会重新执行。")
                    else result.fold({ reply ->
                        offer = next
                        next?.let { token -> main.postDelayed({ if (offer === token) offer = null }, 120_000L) }
                        done(reply, null)
                    }, { failure -> done(null, failure.message ?: "未核对到这份原输出，没有换成其他文件") })
                }
            })
        } catch (failure: Exception) { active = null; throw failure }
    }
}
