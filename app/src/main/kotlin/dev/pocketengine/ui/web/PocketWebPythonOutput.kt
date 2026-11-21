package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.FeatureHost
import dev.pocketengine.features.local.runtime.OwnerPythonOutputText
import java.util.UUID
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import org.json.JSONObject

/** One native buffer in the existing private Work read window. No parameters permit a new read or run. */
internal class PocketWebPythonOutput(
    private val host: () -> FeatureHost,
    private val work: (Runnable) -> Unit,
    private val capture: () -> PocketWebWindowTicket,
    private val accepts: (PocketWebWindowTicket) -> Boolean,
    private val selected: () -> String?
) {
    companion object {
        private const val READ = "projects.pythonRun.read"
        private const val SAVE = "projects.pythonRun.saveOutput"
        val COMMANDS = setOf(READ, SAVE)
        private const val HOLD_MILLIS = 120_000L
        private val uuid = Regex("[a-f0-9-]{36}")
    }
    private data class Offer(val task: String, val window: PocketWebWindowTicket, val id: String,
        val until: Long, val snapshot: OwnerPythonOutputText)
    private val main = Handler(Looper.getMainLooper())
    private var offer: Offer? = null
    @Volatile private var active: Any? = null

    fun clear() { active = null; offer?.snapshot?.close(); offer = null }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        val saving = action == SAVE
        val keys = setOf("taskId", "runId", "epoch", "operationId") + if (saving) setOf("outputHandle") else emptySet()
        require(args.keys().asSequence().toSet() == keys && keys.filter { it != "epoch" }.all { args.opt(it) is String })
        val task = args.getString("taskId")
        val epoch = args.opt("epoch")
        require(uuid.matches(task) && task == args.opt("runId") && task == args.opt("operationId") &&
            (epoch is Int || epoch is Long) && (epoch as Number).toLong() == 1L &&
            (!saving || uuid.matches(args.getString("outputHandle")))) { "请核对同一次原 Python 工作" }
        val window = capture()
        fun visible() = accepts(window) && selected() == task
        check(visible() && active == null) { "请回到同一次原私人脚本工作" }
        val reviewed = if (saving) offer?.takeIf {
            it.task == task && it.window == window && it.id == args.opt("outputHandle") && SystemClock.elapsedRealtime() < it.until
        } ?: error("请先读取这次原输出，没有保存其他内容") else null
        if (!saving) offer?.snapshot?.close()
        offer = null
        val flight = Any(); active = flight
        try {
            work(Runnable {
                fun current() = visible() && active === flight && (reviewed == null || SystemClock.elapsedRealtime() < reviewed.until)
                var candidate: OwnerPythonOutputText? = null
                var next: Offer? = null
                val receipt = runCatching {
                    check(current()) { "原脚本工作窗口已关闭" }
                    if (reviewed != null) return@runCatching reviewed.snapshot.use {
                        host().saveOwnerPythonTextForOwner(task, task, 1, task, it, BooleanSupplier { current() })
                    }
                    val observed = host().readOwnerPythonForOwner(task, task, 1, task, BooleanSupplier { current() }, Consumer { candidate = it })
                    check(current()) { "原脚本工作窗口已关闭" }
                    candidate?.let { snapshot ->
                        val token = Offer(task, window, UUID.randomUUID().toString(), SystemClock.elapsedRealtime() + HOLD_MILLIS, snapshot)
                        observed.put("textOutputSave", snapshot.pin().offer().put("handle", token.id))
                        next = token; candidate = null
                    }
                    observed
                }
                candidate?.close(); reviewed?.snapshot?.close()
                main.post {
                    val stillCurrent = active === flight && visible()
                    if (active === flight) active = null
                    if (!stillCurrent) { next?.snapshot?.close(); done(null, "原脚本窗口已变化，请从原工作核对本次结果。") }
                    else receipt.fold({ result ->
                        offer = next
                        next?.let { held -> main.postDelayed({ if (offer === held) { held.snapshot.close(); offer = null } }, HOLD_MILLIS) }
                        done(result, null)
                    }, {
                        next?.snapshot?.close()
                        done(null, if (saving) "未核对到本次 TXT 保存，请读取原工作核对；不会自动保存第二次。"
                            else "没有核对到原脚本结果；没有再次运行。")
                    })
                }
            })
        } catch (failure: Exception) {
            if (active === flight) active = null
            reviewed?.snapshot?.close()
            throw failure
        }
    }
}
